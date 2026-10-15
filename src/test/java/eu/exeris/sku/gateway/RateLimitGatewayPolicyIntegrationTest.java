package eu.exeris.sku.gateway;

import eu.exeris.caps.corspolicy.api.CorsPolicies;
import eu.exeris.caps.corspolicy.api.CorsPolicy;
import eu.exeris.caps.gatewaycore.GatewayCoreModule;
import eu.exeris.caps.gatewaycore.api.GatewayLifecycle;
import eu.exeris.caps.gatewaycore.api.IngressBootstrap;
import eu.exeris.caps.gatewaycore.api.PolicyChain;
import eu.exeris.caps.ratelimit.api.RateLimitPolicies;
import eu.exeris.caps.ratelimit.api.RateLimitPolicy;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.sku.gateway.cors.CorsGatewayPolicy;
import eu.exeris.sku.gateway.ratelimit.RateLimitGatewayPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RateLimitGatewayPolicyIntegrationTest")
class RateLimitGatewayPolicyIntegrationTest {

    private GatewayCoreModule gatewayModule;
    private IngressBootstrap ingress;
    private GatewayLifecycle lifecycle;
    private PolicyChain policyChain;
    private AtomicBoolean upstreamHandlerCalled;

    @BeforeEach
    void setUp() throws Exception {
        gatewayModule = new GatewayCoreModule();
        ingress = gatewayModule.ingressBootstrap();
        lifecycle = gatewayModule.gatewayLifecycle();
        policyChain = gatewayModule.policyChain();

        // 1. CORS Policy
        CorsPolicy corsPolicy = CorsPolicies.forOrigins("https://app.example.com");
        policyChain.addPolicy(new CorsGatewayPolicy(corsPolicy));

        // 2. Rate Limit Policy: 10 req/s, burst = 2
        RateLimitPolicy rateLimitPolicy = RateLimitPolicies.builder()
                .rate(10, Duration.ofSeconds(1))
                .burst(2)
                .capacity(64)
                .build();
        policyChain.addPolicy(new RateLimitGatewayPolicy(
                rateLimitPolicy,
                RateLimitGatewayPolicy.headerKeyExtractor("X-API-Key"),
                true
        ));

        upstreamHandlerCalled = new AtomicBoolean(false);
        ingress.registerHandler("/api/items", exchange -> {
            upstreamHandlerCalled.set(true);
            byte[] bodyBytes = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.respond(new HttpResponse(
                    HttpStatus.OK,
                    HttpVersion.HTTP_1_1,
                    List.of(new HttpHeader("Content-Type", "application/json")),
                    new ByteArrayLoanedBuffer(bodyBytes)
            ));
        });

        lifecycle.initialize();
        lifecycle.ready();
    }

    @Test
    @DisplayName("Allows requests within burst quota and invokes upstream")
    void allowsRequestsWithinQuota() {
        // Request 1
        MockExchange ex1 = new MockExchange(
                HttpMethod.GET,
                "/api/items",
                List.of(new HttpHeader("X-API-Key", "client-1"))
        );
        ingress.handle(ex1);

        assertThat(upstreamHandlerCalled).isTrue();
        assertThat(ex1.responded.get()).isTrue();
        assertThat(ex1.recordedResponse.get().status()).isEqualTo(HttpStatus.OK);

        // Reset flag and send Request 2
        upstreamHandlerCalled.set(false);
        MockExchange ex2 = new MockExchange(
                HttpMethod.GET,
                "/api/items",
                List.of(new HttpHeader("X-API-Key", "client-1"))
        );
        ingress.handle(ex2);

        assertThat(upstreamHandlerCalled).isTrue();
        assertThat(ex2.recordedResponse.get().status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Short-circuits with 429 when burst limit is exceeded without calling upstream")
    void shortCircuits429WhenExceeded() {
        // Consume burst (2 requests)
        ingress.handle(new MockExchange(HttpMethod.GET, "/api/items", List.of(new HttpHeader("X-API-Key", "client-spam"))));
        ingress.handle(new MockExchange(HttpMethod.GET, "/api/items", List.of(new HttpHeader("X-API-Key", "client-spam"))));

        // 3rd request should be throttled
        upstreamHandlerCalled.set(false);
        MockExchange ex3 = new MockExchange(
                HttpMethod.GET,
                "/api/items",
                List.of(new HttpHeader("X-API-Key", "client-spam"))
        );
        ingress.handle(ex3);

        // Upstream was NEVER called!
        assertThat(upstreamHandlerCalled).isFalse();
        assertThat(ex3.responded.get()).isTrue();
        HttpResponse resp = ex3.recordedResponse.get();
        assertThat(resp.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(resp.headers()).anyMatch(h -> "Retry-After".equalsIgnoreCase(h.name()));
        assertThat(resp.headers()).anyMatch(h -> "X-RateLimit-Remaining".equalsIgnoreCase(h.name()) && "0".equals(h.value()));
    }

    @Test
    @DisplayName("Different clients have isolated quotas")
    void isolatedQuotasPerClient() {
        // Exhaust client A
        ingress.handle(new MockExchange(HttpMethod.GET, "/api/items", List.of(new HttpHeader("X-API-Key", "client-A"))));
        ingress.handle(new MockExchange(HttpMethod.GET, "/api/items", List.of(new HttpHeader("X-API-Key", "client-A"))));

        MockExchange exA3 = new MockExchange(HttpMethod.GET, "/api/items", List.of(new HttpHeader("X-API-Key", "client-A")));
        ingress.handle(exA3);
        assertThat(exA3.recordedResponse.get().status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Client B is fresh and should succeed!
        upstreamHandlerCalled.set(false);
        MockExchange exB = new MockExchange(HttpMethod.GET, "/api/items", List.of(new HttpHeader("X-API-Key", "client-B")));
        ingress.handle(exB);
        assertThat(upstreamHandlerCalled).isTrue();
        assertThat(exB.recordedResponse.get().status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("CORS Preflight passes through without being blocked by rate limiter")
    void corsPreflightNotBlocked() {
        // OPTIONS preflight from allowed origin
        MockExchange preflight = new MockExchange(
                HttpMethod.OPTIONS,
                "/api/items",
                List.of(
                        new HttpHeader("Origin", "https://app.example.com"),
                        new HttpHeader("Access-Control-Request-Method", "POST")
                )
        );
        ingress.handle(preflight);

        // CORS short-circuits with 204
        assertThat(preflight.recordedResponse.get().status()).isEqualTo(HttpStatus.NO_CONTENT);
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
            return new HttpRequest(
                    method,
                    path,
                    HttpVersion.HTTP_1_1,
                    headers,
                    null
            );
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
