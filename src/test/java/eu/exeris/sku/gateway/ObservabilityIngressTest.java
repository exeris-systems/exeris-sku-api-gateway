package eu.exeris.sku.gateway;

import eu.exeris.caps.gatewaycore.GatewayCoreModule;
import eu.exeris.caps.gatewaycore.api.GatewayLifecycle;
import eu.exeris.caps.gatewaycore.api.IngressBootstrap;
import eu.exeris.caps.gatewaycore.api.PolicyChain;
import eu.exeris.caps.observability.api.GatewayObservabilities;
import eu.exeris.caps.observability.api.GatewayObservabilityService;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.caps.ratelimit.api.RateLimitPolicies;
import eu.exeris.sku.gateway.observability.ObservabilityIngress;
import eu.exeris.sku.gateway.ratelimit.RateLimitGatewayPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ObservabilityIngressTest")
class ObservabilityIngressTest {

    private GatewayCoreModule gatewayModule;
    private IngressBootstrap ingress;
    private GatewayLifecycle lifecycle;
    private PolicyChain policyChain;
    private GatewayObservabilityService obsService;
    private HttpHandler measured;

    @BeforeEach
    void setUp() throws Exception {
        gatewayModule = new GatewayCoreModule();
        ingress = gatewayModule.ingressBootstrap();
        lifecycle = gatewayModule.gatewayLifecycle();
        policyChain = gatewayModule.policyChain();

        obsService = GatewayObservabilities.newService(20);
        measured = new ObservabilityIngress(ingress, obsService, Set.of("/metrics", "/dashboard"));

        ingress.registerHandler("/api/items", exchange -> {
            byte[] bytes = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.respond(new HttpResponse(
                    HttpStatus.OK,
                    HttpVersion.HTTP_1_1,
                    List.of(new HttpHeader("Content-Type", "application/json")),
                    new ByteArrayLoanedBuffer(bytes)
            ));
        });

        lifecycle.initialize();
        lifecycle.ready();
    }

    @Test
    @DisplayName("Generates fresh W3C TraceContext and injects X-Trace-Id and traceparent headers into response")
    void generatesTraceHeaders() {
        MockExchange ex = new MockExchange(HttpMethod.GET, "/api/items", List.of());
        measured.handle(ex);

        assertThat(ex.recordedResponse.get().status()).isEqualTo(HttpStatus.OK);
        List<HttpHeader> headers = ex.recordedResponse.get().headers();

        assertThat(headers).anyMatch(h -> "X-Trace-Id".equalsIgnoreCase(h.name()) && h.value().length() == 32);
        assertThat(headers).anyMatch(h -> "traceparent".equalsIgnoreCase(h.name()) && h.value().startsWith("00-"));

        assertThat(obsService.snapshot().totalRequests()).isEqualTo(1);
        assertThat(obsService.snapshot().count2xx()).isEqualTo(1);
    }

    @Test
    @DisplayName("Preserves incoming traceparent trace ID and propagates downstream")
    void preservesIncomingTraceparent() {
        String incomingTrace = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        MockExchange ex = new MockExchange(
                HttpMethod.GET,
                "/api/items",
                List.of(new HttpHeader("traceparent", incomingTrace))
        );
        measured.handle(ex);

        List<HttpHeader> headers = ex.recordedResponse.get().headers();
        assertThat(headers).anyMatch(h -> "X-Trace-Id".equalsIgnoreCase(h.name()) && "4bf92f3577b34da6a3ce929d0e0e4736".equals(h.value()));
        assertThat(headers).anyMatch(h -> "traceparent".equalsIgnoreCase(h.name()) && h.value().startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-"));
    }

    @Test
    @DisplayName("Prometheus and JSON exports reflect processed traffic")
    void exportsPrometheusAndJson() {
        measured.handle(new MockExchange(HttpMethod.GET, "/api/items", List.of()));
        measured.handle(new MockExchange(HttpMethod.GET, "/api/items", List.of()));

        String prom = obsService.exportPrometheus();
        assertThat(prom).contains("gateway_requests_total 2");
        assertThat(prom).contains("gateway_requests_status_total{class=\"2xx\"} 2");

        String json = obsService.exportJson();
        assertThat(json).contains("\"totalRequests\":2");

        String html = obsService.exportDashboardHtml();
        assertThat(html).contains("Exeris Gateway :: Live Observability Engine");
    }

    @Test
    @DisplayName("A request refused by a policy is counted and traced like any other response")
    void shortCircuitedRequestsAreCounted() {
        policyChain.addPolicy(new RateLimitGatewayPolicy(
                RateLimitPolicies.builder().rate(1, java.time.Duration.ofSeconds(60)).burst(1).capacity(8).build()));

        MockExchange allowed = new MockExchange(HttpMethod.GET, "/api/items", List.of());
        MockExchange refused = new MockExchange(HttpMethod.GET, "/api/items", List.of());
        measured.handle(allowed);
        measured.handle(refused);

        assertThat(allowed.recordedResponse.get().status()).isEqualTo(HttpStatus.OK);
        assertThat(refused.recordedResponse.get().status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refused.recordedResponse.get().headers())
                .anyMatch(h -> "X-Trace-Id".equalsIgnoreCase(h.name()));
        assertThat(obsService.snapshot().totalRequests()).isEqualTo(2);
        assertThat(obsService.snapshot().count4xx()).isEqualTo(1);
        assertThat(obsService.snapshot().activeRequests()).isZero();
    }

    @Test
    @DisplayName("The trace headers of an upstream response are replaced, not duplicated")
    void traceHeadersAreReplaced() {
        ingress.registerHandler("/api/traced", exchange -> exchange.respond(new HttpResponse(
                HttpStatus.OK, HttpVersion.HTTP_1_1,
                List.of(new HttpHeader("X-Trace-Id", "upstream-value"), new HttpHeader("traceparent", "upstream")),
                null)));

        MockExchange ex = new MockExchange(HttpMethod.GET, "/api/traced", List.of());
        measured.handle(ex);

        List<HttpHeader> headers = ex.recordedResponse.get().headers();
        assertThat(headers.stream().filter(h -> "X-Trace-Id".equalsIgnoreCase(h.name()))).hasSize(1);
        assertThat(headers.stream().filter(h -> "traceparent".equalsIgnoreCase(h.name()))).hasSize(1);
        assertThat(headers).noneMatch(h -> "upstream-value".equals(h.value()));
    }

    @Test
    @DisplayName("A handler that throws still leaves the active-request gauge at zero")
    void failingHandlerReleasesTheGauge() {
        ingress.registerHandler("/api/boom", exchange -> {
            throw new IllegalStateException("boom");
        });

        MockExchange ex = new MockExchange(HttpMethod.GET, "/api/boom", List.of());
        measured.handle(ex);

        assertThat(ex.recordedResponse.get().status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(obsService.snapshot().activeRequests()).isZero();
        assertThat(obsService.snapshot().totalRequests()).isEqualTo(1);
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
