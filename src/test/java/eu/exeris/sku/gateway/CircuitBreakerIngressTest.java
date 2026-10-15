package eu.exeris.sku.gateway;

import eu.exeris.caps.breaker.api.CircuitBreaker;
import eu.exeris.caps.breaker.api.CircuitBreakerConfig;
import eu.exeris.caps.breaker.api.CircuitBreakerRegistry;
import eu.exeris.caps.breaker.api.CircuitBreakers;
import eu.exeris.caps.breaker.api.CircuitState;
import eu.exeris.caps.gatewaycore.GatewayCoreModule;
import eu.exeris.caps.routing.api.RequestRouter;
import eu.exeris.caps.routing.api.RequestRouters;
import eu.exeris.caps.gatewaycore.api.GatewayLifecycle;
import eu.exeris.caps.gatewaycore.api.IngressBootstrap;
import eu.exeris.caps.gatewaycore.api.PolicyChain;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.sku.gateway.breaker.CircuitBreakerIngress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CircuitBreakerIngressTest")
class CircuitBreakerIngressTest {

    private GatewayCoreModule gatewayModule;
    private IngressBootstrap ingress;
    private GatewayLifecycle lifecycle;
    private PolicyChain policyChain;
    private CircuitBreakerRegistry cbRegistry;
    private HttpHandler guarded;
    private AtomicInteger upstreamCalls;
    private AtomicInteger upstreamStatusCode;

    @BeforeEach
    void setUp() throws Exception {
        gatewayModule = new GatewayCoreModule();
        ingress = gatewayModule.ingressBootstrap();
        lifecycle = gatewayModule.gatewayLifecycle();
        policyChain = gatewayModule.policyChain();

        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(3)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofMillis(80))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build();

        cbRegistry = CircuitBreakers.newRegistry(config);
        RequestRouter router = RequestRouters.builder()
                .route("items").pathPrefix("/api/items").targetUri("http://upstream:9000").and()
                .build();
        guarded = new CircuitBreakerIngress(ingress, cbRegistry, router, Set.of("/health", "/actuator/health"));

        upstreamCalls = new AtomicInteger(0);
        upstreamStatusCode = new AtomicInteger(200);

        ingress.registerHandler("/api/items", exchange -> {
            upstreamCalls.incrementAndGet();
            int code = upstreamStatusCode.get();
            HttpStatus status = (code == 500) ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.OK;
            byte[] bytes = ("{\"code\":" + code + "}").getBytes(StandardCharsets.UTF_8);
            exchange.respond(new HttpResponse(
                    status,
                    HttpVersion.HTTP_1_1,
                    List.of(new HttpHeader("Content-Type", "application/json")),
                    new ByteArrayLoanedBuffer(bytes)
            ));
        });

        lifecycle.initialize();
        lifecycle.ready();
    }

    @Test
    @DisplayName("Healthy requests pass through and keep circuit CLOSED")
    void healthyRequestsPassThrough() {
        MockExchange ex = new MockExchange(HttpMethod.GET, "/api/items/test", List.of());
        guarded.handle(ex);

        assertThat(upstreamCalls.get()).isEqualTo(1);
        assertThat(ex.recordedResponse.get().status()).isEqualTo(HttpStatus.OK);

        CircuitBreaker breaker = cbRegistry.getOrCreate("items");
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("Upstream failures trip circuit to OPEN and subsequent calls are short-circuited at 503")
    void upstreamFailuresTripCircuit() {
        upstreamStatusCode.set(500);

        // 3 consecutive failures to satisfy minCalls=3 and failRate >= 50%
        for (int i = 0; i < 3; i++) {
            MockExchange ex = new MockExchange(HttpMethod.GET, "/api/items/failing", List.of());
            guarded.handle(ex);
            assertThat(ex.recordedResponse.get().status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        assertThat(upstreamCalls.get()).isEqualTo(3);

        CircuitBreaker breaker = cbRegistry.getOrCreate("items");
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);

        // 4th request: Circuit is OPEN -> wire-speed short-circuit with 503!
        MockExchange shortCircuited = new MockExchange(HttpMethod.GET, "/api/items/failing", List.of());
        guarded.handle(shortCircuited);

        // Upstream was NEVER called for the 4th request!
        assertThat(upstreamCalls.get()).isEqualTo(3);
        assertThat(shortCircuited.recordedResponse.get().status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(shortCircuited.recordedResponse.get().headers())
                .anyMatch(h -> "X-Circuit-State".equalsIgnoreCase(h.name()) && "OPEN".equals(h.value()));
    }

    @Test
    @DisplayName("Quarantine expiry transitions to HALF-OPEN and recovers to CLOSED when probe calls succeed")
    void quarantineExpiryRecoversToClosed() throws InterruptedException {
        upstreamStatusCode.set(500);

        for (int i = 0; i < 3; i++) {
            guarded.handle(new MockExchange(HttpMethod.GET, "/api/items/test", List.of()));
        }

        CircuitBreaker breaker = cbRegistry.getOrCreate("items");
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);

        // Wait for quarantine window (80ms)
        Thread.sleep(100);

        // Upstream is now healthy again
        upstreamStatusCode.set(200);

        // Probe 1
        MockExchange probe1 = new MockExchange(HttpMethod.GET, "/api/items/test", List.of());
        guarded.handle(probe1);
        assertThat(probe1.recordedResponse.get().status()).isEqualTo(HttpStatus.OK);
        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);

        // Probe 2
        MockExchange probe2 = new MockExchange(HttpMethod.GET, "/api/items/test", List.of());
        guarded.handle(probe2);
        assertThat(probe2.recordedResponse.get().status()).isEqualTo(HttpStatus.OK);

        // After 2 successful probes in HALF_OPEN -> recovered to CLOSED!
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("Paths that match no route create no breaker, so clients cannot grow the registry")
    void unmatchedPathsCreateNoBreaker() {
        for (int i = 0; i < 50; i++) {
            guarded.handle(new MockExchange(HttpMethod.GET, "/api/random-" + i + "/x", List.of()));
        }

        assertThat(cbRegistry.all()).isEmpty();
    }

    @Test
    @DisplayName("Requests on one route share one breaker whatever the rest of the path")
    void routeSharesOneBreaker() {
        guarded.handle(new MockExchange(HttpMethod.GET, "/api/items/1", List.of()));
        guarded.handle(new MockExchange(HttpMethod.GET, "/api/items/2/detail", List.of()));

        assertThat(cbRegistry.all()).hasSize(1);
        assertThat(cbRegistry.find("items")).isPresent();
    }

    private static final class MockExchange implements HttpExchange {
        final HttpMethod method;
        final String path;
        final List<HttpHeader> headers;
        final AtomicBoolean responded = new AtomicBoolean(false);
        final AtomicReference<HttpResponse> recordedResponse = new AtomicReference<>();

        MockExchange(HttpMethod method, String path, List<HttpHeader> headers) {
            this.method = method;
            this.path = path;
            this.headers = headers;
        }

        @Override
        public HttpRequest request() {
            return new HttpRequest(method, path, HttpVersion.HTTP_1_1, headers, null);
        }

        @Override
        public void respond(HttpResponse response) {
            if (!responded.compareAndSet(false, true)) {
                throw new IllegalStateException("Already responded");
            }
            recordedResponse.set(response);
        }

        @Override
        public void respond(HttpTypedResponse response) {
            respond(new HttpResponse(response.status(), HttpVersion.HTTP_1_1, List.of(), null));
        }
    }
}
