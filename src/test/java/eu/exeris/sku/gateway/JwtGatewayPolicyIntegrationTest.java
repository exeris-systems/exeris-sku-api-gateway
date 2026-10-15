package eu.exeris.sku.gateway;

import eu.exeris.caps.corspolicy.api.CorsPolicies;
import eu.exeris.caps.corspolicy.api.CorsPolicy;
import eu.exeris.caps.gatewaycore.GatewayCoreModule;
import eu.exeris.caps.gatewaycore.api.GatewayLifecycle;
import eu.exeris.caps.gatewaycore.api.IngressBootstrap;
import eu.exeris.caps.gatewaycore.api.PolicyChain;
import eu.exeris.caps.jwt.api.JwtAdmissionPolicies;
import eu.exeris.caps.jwt.api.JwtAdmissionPolicy;
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
import eu.exeris.sku.gateway.cors.CorsGatewayPolicy;
import eu.exeris.sku.gateway.jwt.JwtGatewayPolicy;
import eu.exeris.sku.gateway.ratelimit.RateLimitGatewayPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JwtGatewayPolicyIntegrationTest")
class JwtGatewayPolicyIntegrationTest {

    private static final String SECRET = "gateway-jwt-integration-test-secret-key-256";

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

        // 1. CORS Policy (-100)
        CorsPolicy corsPolicy = CorsPolicies.forOrigins("https://app.example.com");
        policyChain.addPolicy(new CorsGatewayPolicy(corsPolicy));

        // 2. Rate Limit Policy (-50)
        RateLimitPolicy rateLimitPolicy = RateLimitPolicies.builder()
                .rate(10, Duration.ofSeconds(1))
                .burst(5)
                .capacity(64)
                .build();
        policyChain.addPolicy(new RateLimitGatewayPolicy(rateLimitPolicy));

        // 3. JWT Admission Policy (-40)
        JwtAdmissionPolicy jwtPolicy = JwtAdmissionPolicies.builder()
                .secret(SECRET)
                .expectedIssuer("https://auth.example.com")
                .leeway(Duration.ofSeconds(30))
                .build();
        policyChain.addPolicy(new JwtGatewayPolicy(jwtPolicy, Set.of("/actuator/health"), List.of("/public/")));

        upstreamHandlerCalled = new AtomicBoolean(false);

        // Register protected upstream endpoint
        ingress.registerHandler("/api/v1/accounts", exchange -> {
            upstreamHandlerCalled.set(true);
            exchange.respond(new HttpResponse(
                    HttpStatus.OK,
                    HttpVersion.HTTP_1_1,
                    List.of(new HttpHeader("Content-Type", "application/json")),
                    null
            ));
        });

        // Register public health endpoint
        ingress.registerHandler("/actuator/health", exchange -> {
            upstreamHandlerCalled.set(true);
            exchange.respond(new HttpResponse(
                    HttpStatus.OK,
                    HttpVersion.HTTP_1_1,
                    List.of(new HttpHeader("Content-Type", "application/json")),
                    null
            ));
        });

        lifecycle.initialize();
        lifecycle.ready();
    }

    @Test
    @DisplayName("Request with valid JWT is admitted and reaches upstream handler")
    void testValidJwtAdmitted() {
        String token = createToken(SECRET, "user-42", "https://auth.example.com", System.currentTimeMillis() / 1000 + 3600);

        MockExchange exchange = new MockExchange(
                HttpMethod.GET,
                "/api/v1/accounts",
                List.of(
                        new HttpHeader("Host", "gateway.example.com"),
                        new HttpHeader("Authorization", "Bearer " + token)
                )
        );

        ingress.handle(exchange);

        assertThat(upstreamHandlerCalled).isTrue();
        assertThat(exchange.recordedResponse.get()).isNotNull();
        assertThat(exchange.recordedResponse.get().status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Request without Authorization header short-circuits with 401 Unauthorized")
    void testMissingAuthorizationHeaderShortCircuits() {
        MockExchange exchange = new MockExchange(
                HttpMethod.GET,
                "/api/v1/accounts",
                List.of(new HttpHeader("Host", "gateway.example.com"))
        );

        ingress.handle(exchange);

        assertThat(upstreamHandlerCalled).isFalse();
        assertThat(exchange.recordedResponse.get()).isNotNull();
        assertThat(exchange.recordedResponse.get().status()).isEqualTo(HttpStatus.UNAUTHORIZED);

        Optional<String> wwwAuth = findHeader(exchange.recordedResponse.get(), "WWW-Authenticate");
        assertThat(wwwAuth).isPresent();
        assertThat(wwwAuth.get()).contains("invalid_token");
    }

    @Test
    @DisplayName("Request with forged signature short-circuits with 401 Unauthorized")
    void testForgedSignatureShortCircuits() {
        String forgedToken = createToken("attacker-wrong-secret-key-32b", "user-attacker", "https://auth.example.com", System.currentTimeMillis() / 1000 + 3600);

        MockExchange exchange = new MockExchange(
                HttpMethod.GET,
                "/api/v1/accounts",
                List.of(
                        new HttpHeader("Host", "gateway.example.com"),
                        new HttpHeader("Authorization", "Bearer " + forgedToken)
                )
        );

        ingress.handle(exchange);

        assertThat(upstreamHandlerCalled).isFalse();
        assertThat(exchange.recordedResponse.get()).isNotNull();
        assertThat(exchange.recordedResponse.get().status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Request with expired token short-circuits with 401 Unauthorized")
    void testExpiredTokenShortCircuits() {
        String expiredToken = createToken(SECRET, "user-42", "https://auth.example.com", System.currentTimeMillis() / 1000 - 3600);

        MockExchange exchange = new MockExchange(
                HttpMethod.GET,
                "/api/v1/accounts",
                List.of(
                        new HttpHeader("Host", "gateway.example.com"),
                        new HttpHeader("Authorization", "Bearer " + expiredToken)
                )
        );

        ingress.handle(exchange);

        assertThat(upstreamHandlerCalled).isFalse();
        assertThat(exchange.recordedResponse.get()).isNotNull();
        assertThat(exchange.recordedResponse.get().status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Excluded path bypasses JWT validation without token")
    void testExcludedPathBypassesAuth() {
        MockExchange exchange = new MockExchange(
                HttpMethod.GET,
                "/actuator/health",
                List.of(new HttpHeader("Host", "gateway.example.com"))
        );

        ingress.handle(exchange);

        assertThat(upstreamHandlerCalled).isTrue();
        assertThat(exchange.recordedResponse.get()).isNotNull();
        assertThat(exchange.recordedResponse.get().status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("CORS preflight OPTIONS passes with 204 without auth")
    void testCorsPreflightBypassesAuth() {
        MockExchange exchange = new MockExchange(
                HttpMethod.OPTIONS,
                "/api/v1/accounts",
                List.of(
                        new HttpHeader("Origin", "https://app.example.com"),
                        new HttpHeader("Access-Control-Request-Method", "POST")
                )
        );

        ingress.handle(exchange);

        // Preflight short-circuits at CORS layer (-100) with 204 before reaching JWT (-40)
        assertThat(upstreamHandlerCalled).isFalse();
        assertThat(exchange.recordedResponse.get()).isNotNull();
        assertThat(exchange.recordedResponse.get().status()).isEqualTo(HttpStatus.NO_CONTENT);

        Optional<String> allowOrigin = findHeader(exchange.recordedResponse.get(), "Access-Control-Allow-Origin");
        assertThat(allowOrigin).contains("https://app.example.com");
    }

    private static Optional<String> findHeader(HttpResponse response, String headerName) {
        if (response == null || response.headers() == null) {
            return Optional.empty();
        }
        return response.headers().stream()
                .filter(h -> h.name().equalsIgnoreCase(headerName))
                .map(HttpHeader::value)
                .findFirst();
    }

    private static String createToken(String secret, String sub, String iss, long exp) {
        String headerJson = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payloadJson = "{\"sub\":\"" + sub + "\",\"iss\":\"" + iss + "\",\"exp\":" + exp + "}";

        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String headerB64 = encoder.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String payloadB64 = encoder.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signingInput = headerB64 + "." + payloadB64;

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sigBytes = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
            String sigB64 = encoder.encodeToString(sigBytes);
            return signingInput + "." + sigB64;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
