package eu.exeris.sku.gateway;

import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.sku.gateway.config.GatewayConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GatewayRuntime: the assembled gateway")
class GatewayRuntimeTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final List<String> DIAGNOSTIC_PATHS =
            List.of("/metrics", "/actuator/metrics", "/actuator/allocations", "/allocations", "/dashboard", "/observability");

    private GatewayRuntime gateway;
    private final StubEngine engine = new StubEngine();

    @AfterEach
    void close() {
        if (gateway != null) {
            gateway.close();
        }
    }

    private void start(String... kv) throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("EXERIS_GATEWAY_ROUTES", "/api=http://backend:8080");
        for (int i = 0; i < kv.length; i += 2) {
            env.put(kv[i], kv[i + 1]);
        }
        gateway = GatewayRuntime.create(GatewayConfig.load(env));
        gateway.attach(engine);
        gateway.module().initialize();
        gateway.module().ready();
    }

    private TestExchange send(String path, HttpHeader... headers) {
        TestExchange ex = TestExchange.get(path, headers);
        gateway.ingress().handle(ex);
        return ex;
    }

    @Test
    @DisplayName("With management off, no diagnostic path is served on the ingress and there is no management handler")
    void diagnosticsOffServedNowhere() throws Exception {
        start("EXERIS_OBSERVABILITY_ENABLED", "true");

        assertThat(gateway.management()).isNull();
        for (String path : DIAGNOSTIC_PATHS) {
            assertThat(send(path).status()).as(path).isEqualTo(404);
        }
        assertThat(engine.requests).isEmpty();
    }

    @Test
    @DisplayName("With management on, diagnostics are served by the management handler and not on the ingress")
    void diagnosticsOnServedOnlyByManagement() throws Exception {
        start("EXERIS_OBSERVABILITY_ENABLED", "true", "EXERIS_MANAGEMENT_ENABLED", "true");

        for (String path : List.of("/metrics", "/actuator/metrics", "/actuator/allocations", "/dashboard", "/observability")) {
            TestExchange onIngress = send(path);
            assertThat(onIngress.status()).as("ingress " + path).isEqualTo(404);

            TestExchange onManagement = TestExchange.get(path);
            gateway.management().handle(onManagement);
            assertThat(onManagement.status()).as("management " + path).isEqualTo(200);
        }
        TestExchange unknown = TestExchange.get("/anything-else");
        gateway.management().handle(unknown);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(engine.requests).isEmpty();
    }

    @Test
    @DisplayName("The metrics endpoints exist on the management handler only when observability is on")
    void metricsNeedObservability() throws Exception {
        start("EXERIS_MANAGEMENT_ENABLED", "true");

        TestExchange metrics = TestExchange.get("/metrics");
        gateway.management().handle(metrics);
        TestExchange allocations = TestExchange.get("/actuator/allocations");
        gateway.management().handle(allocations);

        assertThat(metrics.status()).isEqualTo(404);
        assertThat(allocations.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("Diagnostic paths are not in the JWT bypass list: with JWT on, they are refused without a token")
    void diagnosticsNotInJwtBypass() throws Exception {
        start("EXERIS_JWT_ENABLED", "true", "EXERIS_JWT_SECRET", SECRET,
                "EXERIS_OBSERVABILITY_ENABLED", "true", "EXERIS_MANAGEMENT_ENABLED", "true");

        for (String path : DIAGNOSTIC_PATHS) {
            assertThat(send(path).status()).as(path).isEqualTo(401);
        }
        assertThat(send("/health").status()).isEqualTo(200);
        assertThat(send("/actuator/health").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("With CORS unconfigured there is no CORS handling: no headers and no refusal of an Origin")
    void corsOffByDefault() throws Exception {
        start();

        TestExchange ex = send("/api/x", new HttpHeader("Origin", "https://anywhere.example"));

        assertThat(ex.status()).isEqualTo(200);
        assertThat(ex.hasHeader("Access-Control-Allow-Origin")).isFalse();
    }

    @Test
    @DisplayName("With CORS origins configured, only those origins are allowed")
    void corsOnlyForConfiguredOrigins() throws Exception {
        start("EXERIS_CORS_ALLOWED_ORIGINS", "https://app.example.com");

        TestExchange allowed = send("/api/x", new HttpHeader("Origin", "https://app.example.com"));
        TestExchange denied = send("/api/x", new HttpHeader("Origin", "https://evil.example"));

        assertThat(allowed.status()).isEqualTo(200);
        assertThat(allowed.hasHeader("Access-Control-Allow-Origin")).isTrue();
        assertThat(denied.status()).isEqualTo(403);
    }

    @Test
    @DisplayName("An explicit origin is allowed with credentials")
    void corsExplicitOriginWithCredentials() throws Exception {
        start("EXERIS_CORS_ALLOWED_ORIGINS", "https://app.example.com");
        TestExchange explicit = send("/api/x", new HttpHeader("Origin", "https://app.example.com"));
        assertThat(explicit.hasHeader("Access-Control-Allow-Credentials")).isTrue();
    }

    @Test
    @DisplayName("A wildcard origin is served only when written, and without credentials")
    void corsWildcardWithoutCredentials() throws Exception {
        start("EXERIS_CORS_ALLOWED_ORIGINS", "*");
        TestExchange wildcard = send("/api/x", new HttpHeader("Origin", "https://anywhere.example"));
        assertThat(wildcard.status()).isEqualTo(200);
        assertThat(wildcard.hasHeader("Access-Control-Allow-Origin")).isTrue();
        assertThat(wildcard.hasHeader("Access-Control-Allow-Credentials")).isFalse();
    }

    @Test
    @DisplayName("With JWT on, a request without a valid token is refused and a valid one is forwarded")
    void jwtAdmission() throws Exception {
        start("EXERIS_JWT_ENABLED", "true", "EXERIS_JWT_SECRET", SECRET);

        assertThat(send("/api/x").status()).isEqualTo(401);
        assertThat(engine.requests).isEmpty();

        TestExchange ok = send("/api/x", new HttpHeader("Authorization", "Bearer " + token(SECRET)));
        assertThat(ok.status()).isEqualTo(200);
        assertThat(engine.requests).hasSize(1);
    }

    @Test
    @DisplayName("A route table with no built-in routes: an unrouted path is a 404 and reaches no upstream")
    void unroutedPathIsNotForwarded() throws Exception {
        start();

        assertThat(send("/other").status()).isEqualTo(404);
        assertThat(engine.requests).isEmpty();
    }

    @Test
    @DisplayName("Rate limiting without a key header is one shared limit that a spoofed header cannot escape")
    void rateLimitIgnoresSpoofedHeaders() throws Exception {
        start("EXERIS_RATELIMIT_ENABLED", "true", "EXERIS_RATELIMIT_RPS", "1", "EXERIS_RATELIMIT_BURST", "2");

        int refused = 0;
        for (int i = 0; i < 20; i++) {
            TestExchange ex = send("/api/x",
                    new HttpHeader("X-Forwarded-For", "10.0.0." + i),
                    new HttpHeader("X-Real-IP", "10.1.0." + i),
                    new HttpHeader("X-API-Key", "key-" + i));
            if (ex.status() == 429) {
                refused++;
            }
        }
        assertThat(refused).isGreaterThan(10);
    }

    @Test
    @DisplayName("WAF in DETECT mode lets an attack pattern through; in BLOCK mode it is refused")
    void wafModes() throws Exception {
        start("EXERIS_WAF_ENABLED", "true", "EXERIS_WAF_MODE", "DETECT");
        assertThat(send("/api/x?q=1%27%20OR%20%271%27=%271").status()).isEqualTo(200);
        gateway.close();
        engine.requests.clear();

        start("EXERIS_WAF_ENABLED", "true", "EXERIS_WAF_MODE", "BLOCK");
        assertThat(send("/api/x?q=1%27%20OR%20%271%27=%271").status()).isEqualTo(403);
        assertThat(engine.requests).isEmpty();
    }

    @Test
    @DisplayName("A body over the limit is refused with 413 and never forwarded, whatever Content-Length says")
    void oversizedBodyIsRefused() throws Exception {
        start("EXERIS_HTTP_MAX_REQUEST_BODY_BYTES", "16");

        TestExchange small = new TestExchange(eu.exeris.kernel.spi.http.HttpMethod.POST, "/api/x", List.of(),
                new ByteArrayLoanedBuffer(new byte[16]));
        TestExchange large = new TestExchange(eu.exeris.kernel.spi.http.HttpMethod.POST, "/api/x",
                List.of(new HttpHeader("Content-Length", "1")), new ByteArrayLoanedBuffer(new byte[17]));
        TestExchange declared = new TestExchange(eu.exeris.kernel.spi.http.HttpMethod.POST, "/api/x",
                List.of(new HttpHeader("Content-Length", "999999")));
        gateway.ingress().handle(small);
        gateway.ingress().handle(large);
        gateway.ingress().handle(declared);

        assertThat(small.status()).isEqualTo(200);
        assertThat(large.status()).isEqualTo(413);
        assertThat(declared.status()).isEqualTo(413);
        assertThat(engine.requests).hasSize(1);
    }

    @Test
    @DisplayName("IP_HASH selects without a key, so it rotates over the hosts until the kernel exposes the peer address")
    void ipHashRotatesWithoutAKey() throws Exception {
        start("EXERIS_GATEWAY_ROUTES", "/api=cluster://pool",
                "EXERIS_GATEWAY_CLUSTERS", "pool=http://a:1,http://b:2",
                "EXERIS_LB_ALGORITHM", "IP_HASH");

        for (int i = 0; i < 4; i++) {
            assertThat(send("/api/x").status()).isEqualTo(200);
        }
        assertThat(engine.requests).extracting(r -> r.authority())
                .containsExactlyInAnyOrder("a:1", "a:1", "b:2", "b:2");
    }

    @Test
    @DisplayName("RANDOM spreads requests over the hosts of a cluster")
    void randomSelectsAmongTheHosts() throws Exception {
        start("EXERIS_GATEWAY_ROUTES", "/api=cluster://pool",
                "EXERIS_GATEWAY_CLUSTERS", "pool=http://a:1,http://b:2",
                "EXERIS_LB_ALGORITHM", "RANDOM");

        for (int i = 0; i < 200; i++) {
            assertThat(send("/api/x").status()).isEqualTo(200);
        }
        assertThat(engine.requests).extracting(r -> r.authority()).contains("a:1", "b:2")
                .allMatch(a -> a.equals("a:1") || a.equals("b:2"));
    }

    @Test
    @DisplayName("Active health checking takes a host that fails its probe out of rotation")
    void activeHealthCheckEjectsAFailingHost() throws Exception {
        com.sun.net.httpserver.HttpServer live = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        live.createContext("/health", x -> {
            x.sendResponseHeaders(200, -1);
            x.close();
        });
        live.start();
        int dead;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
            dead = probe.getLocalPort();
        }
        try {
            String liveAuthority = "127.0.0.1:" + live.getAddress().getPort();
            start("EXERIS_GATEWAY_ROUTES", "/api=cluster://pool",
                    "EXERIS_GATEWAY_CLUSTERS", "pool=http://" + liveAuthority + ",http://127.0.0.1:" + dead,
                    "EXERIS_ACTIVE_HEALTH_CHECK_ENABLED", "true",
                    "EXERIS_ACTIVE_HEALTH_CHECK_INTERVAL_MS", "50",
                    "EXERIS_ACTIVE_HEALTH_CHECK_TIMEOUT_MS", "200",
                    "EXERIS_ACTIVE_UNHEALTHY_THRESHOLD", "1");

            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            boolean onlyLive = false;
            while (!onlyLive && System.nanoTime() < deadline) {
                engine.requests.clear();
                for (int i = 0; i < 6; i++) {
                    send("/api/x");
                }
                onlyLive = engine.requests.stream().allMatch(r -> r.authority().equals(liveAuthority));
                if (!onlyLive) {
                    Thread.sleep(50);
                }
            }
            assertThat(onlyLive).as("every request reaches the host that answers its probe").isTrue();
        } finally {
            live.stop(0);
        }
    }

    @Test
    @DisplayName("The gateway reports DOWN before it is ready")
    void healthReflectsLifecycle() throws Exception {
        Map<String, String> env = Map.of("EXERIS_GATEWAY_ROUTES", "/api=http://backend:8080");
        gateway = GatewayRuntime.create(GatewayConfig.load(env));
        TestExchange before = send("/health");
        assertThat(before.status()).isEqualTo(503);
        gateway.module().initialize();
        gateway.module().ready();
        assertThat(send("/health").status()).isEqualTo(200);
    }

    private static String token(String secret) throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String input = enc.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + enc.encodeToString(("{\"sub\":\"u1\",\"exp\":" + (System.currentTimeMillis() / 1000 + 3600) + "}")
                .getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return input + "." + enc.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    }
}
