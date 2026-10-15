package eu.exeris.sku.gateway;

import eu.exeris.caps.corspolicy.api.CorsPolicies;
import eu.exeris.caps.corspolicy.api.CorsPolicy;
import eu.exeris.caps.gatewaycore.GatewayCoreModule;
import eu.exeris.caps.gatewaycore.api.GatewayLifecycle;
import eu.exeris.caps.gatewaycore.api.IngressBootstrap;
import eu.exeris.caps.gatewaycore.api.PolicyChain;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.sku.gateway.cors.CorsGatewayPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CorsGatewayPolicyIntegrationTest")
class CorsGatewayPolicyIntegrationTest {

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

        CorsPolicy corsPolicy = CorsPolicies.forOrigins("https://app.example.com", "http://localhost:3000");
        policyChain.addPolicy(new CorsGatewayPolicy(corsPolicy));

        upstreamHandlerCalled = new AtomicBoolean(false);
        ingress.registerHandler("/api/transactions", exchange -> {
            upstreamHandlerCalled.set(true);
            byte[] bodyBytes = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.respond(new HttpResponse(
                    HttpStatus.OK,
                    HttpVersion.HTTP_1_1,
                    List.of(new HttpHeader("Content-Type", "application/json")),
                    new ByteArrayLoanedBuffer(bodyBytes)
            ));
        });

        gatewayModule.initialize();
        gatewayModule.ready();
    }

    @Test
    @DisplayName("CORS preflight for allowed origin short-circuits with 204 No Content and never touches upstream")
    void preflightAllowedOriginShortCircuits() {
        HttpRequest preflightReq = new HttpRequest(
                HttpMethod.OPTIONS,
                "gateway.local",
                "/api/transactions",
                HttpVersion.HTTP_1_1,
                List.of(
                        new HttpHeader("Origin", "https://app.example.com"),
                        new HttpHeader("Access-Control-Request-Method", "POST"),
                        new HttpHeader("Access-Control-Request-Headers", "Content-Type, Authorization")
                ),
                null
        );

        RecordingExchange exchange = new RecordingExchange(preflightReq);
        ingress.handle(exchange);

        assertThat(exchange.capturedResponse).isNotNull();
        assertThat(exchange.capturedResponse.status()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(upstreamHandlerCalled.get()).isFalse(); // Upstream never invoked!

        List<HttpHeader> headers = exchange.capturedResponse.headers();
        assertThat(headers).anyMatch(h -> h.nameEqualsIgnoreCase("Access-Control-Allow-Origin")
                && h.value().equals("https://app.example.com"));
        assertThat(headers).anyMatch(h -> h.nameEqualsIgnoreCase("Access-Control-Allow-Credentials")
                && h.value().equals("true"));
        assertThat(headers).anyMatch(h -> h.nameEqualsIgnoreCase("Access-Control-Allow-Methods")
                && h.value().contains("POST"));
        assertThat(headers).anyMatch(h -> h.nameEqualsIgnoreCase("Access-Control-Allow-Headers")
                && h.value().contains("Content-Type"));
    }

    @Test
    @DisplayName("CORS preflight for disallowed origin short-circuits with 403 Forbidden")
    void preflightDisallowedOriginRejected() {
        HttpRequest preflightReq = new HttpRequest(
                HttpMethod.OPTIONS,
                "gateway.local",
                "/api/transactions",
                HttpVersion.HTTP_1_1,
                List.of(
                        new HttpHeader("Origin", "https://evil-site.com"),
                        new HttpHeader("Access-Control-Request-Method", "POST")
                ),
                null
        );

        RecordingExchange exchange = new RecordingExchange(preflightReq);
        ingress.handle(exchange);

        assertThat(exchange.capturedResponse).isNotNull();
        assertThat(exchange.capturedResponse.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(upstreamHandlerCalled.get()).isFalse();
    }

    @Test
    @DisplayName("Cross-origin GET forwards to upstream and appends CORS response headers")
    void crossOriginActualRequestForwardsAndAppendsHeaders() {
        HttpRequest getReq = new HttpRequest(
                HttpMethod.GET,
                "gateway.local",
                "/api/transactions",
                HttpVersion.HTTP_1_1,
                List.of(
                        new HttpHeader("Origin", "https://app.example.com")
                ),
                null
        );

        RecordingExchange exchange = new RecordingExchange(getReq);
        ingress.handle(exchange);

        assertThat(upstreamHandlerCalled.get()).isTrue(); // Upstream was invoked
        assertThat(exchange.capturedResponse).isNotNull();
        assertThat(exchange.capturedResponse.status()).isEqualTo(HttpStatus.OK);

        List<HttpHeader> headers = exchange.capturedResponse.headers();
        assertThat(headers).anyMatch(h -> h.nameEqualsIgnoreCase("Content-Type") && h.value().equals("application/json"));
        assertThat(headers).anyMatch(h -> h.nameEqualsIgnoreCase("Access-Control-Allow-Origin")
                && h.value().equals("https://app.example.com"));
        assertThat(headers).anyMatch(h -> h.nameEqualsIgnoreCase("Vary") && h.value().equals("Origin"));
    }

    @Test
    @DisplayName("Same-origin request without Origin header bypasses CORS and receives normal upstream response")
    void sameOriginBypassesCors() {
        HttpRequest getReq = new HttpRequest(
                HttpMethod.GET,
                "gateway.local",
                "/api/transactions",
                HttpVersion.HTTP_1_1,
                List.of(),
                null
        );

        RecordingExchange exchange = new RecordingExchange(getReq);
        ingress.handle(exchange);

        assertThat(upstreamHandlerCalled.get()).isTrue();
        assertThat(exchange.capturedResponse).isNotNull();
        assertThat(exchange.capturedResponse.status()).isEqualTo(HttpStatus.OK);
        assertThat(exchange.capturedResponse.headers())
                .noneMatch(h -> h.nameEqualsIgnoreCase("Access-Control-Allow-Origin"));
    }

    private static final class RecordingExchange implements HttpExchange {
        private final HttpRequest request;
        private HttpResponse capturedResponse;

        private RecordingExchange(HttpRequest request) {
            this.request = request;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Map<String, String> pathParams() {
            return Map.of();
        }

        @Override
        public void respond(HttpResponse response) {
            this.capturedResponse = response;
        }

        @Override
        public void respond(HttpTypedResponse typedResponse) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void respond(HttpStatus status) {
            this.capturedResponse = new HttpResponse(status, HttpVersion.HTTP_1_1, List.of(), null);
        }
    }
}
