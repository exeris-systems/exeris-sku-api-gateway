package eu.exeris.sku.gateway;

import eu.exeris.sku.gateway.config.GatewayConfig;
import eu.exeris.sku.gateway.config.GatewayConfigException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GatewayConfig")
class GatewayConfigTest {

    private static Map<String, String> env(String... kv) {
        Map<String, String> m = new HashMap<>();
        m.put("EXERIS_GATEWAY_ROUTES", "/api=http://backend:8080");
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("Defaults: every optional policy and the management listener are off, CORS has no origins")
    void defaultsAreClosed() {
        GatewayConfig c = GatewayConfig.load(env());

        assertThat(c.management()).isNull();
        assertThat(c.corsOrigins()).isEmpty();
        assertThat(c.jwt()).isNull();
        assertThat(c.rateLimit()).isNull();
        assertThat(c.waf()).isNull();
        assertThat(c.http().maxRequestBodyBytes()).isEqualTo(10L * 1024 * 1024);
        assertThat(c.upstream().timeoutMillis()).isEqualTo(30_000L);
        assertThat(c.upstream().maxResponseBytes()).isEqualTo(10L * 1024 * 1024);
    }

    @Test
    @DisplayName("RANDOM and IP_HASH are accepted; the pool defaults leave the ejection budget open and probing off")
    void poolAlgorithmsAndDefaults() {
        assertThat(GatewayConfig.load(env("EXERIS_LB_ALGORITHM", "random")).pool().algorithm()).isEqualTo("RANDOM");
        assertThat(GatewayConfig.load(env("EXERIS_LB_ALGORITHM", "IP_HASH")).pool().algorithm()).isEqualTo("IP_HASH");
        GatewayConfig.Pool defaults = GatewayConfig.load(env()).pool();
        assertThat(defaults.maxEjectionPercent()).isEqualTo(100);
        assertThat(defaults.activeHealthCheck()).isNull();
        assertThatThrownBy(() -> GatewayConfig.load(env("EXERIS_LB_ALGORITHM", "MAGLEV")))
                .isInstanceOf(GatewayConfigException.class)
                .hasMessageContaining("EXERIS_LB_ALGORITHM");
    }

    @Test
    @DisplayName("The ejection budget is bounded to 1..100 and names its setting")
    void maxEjectionPercentIsBounded() {
        assertThat(GatewayConfig.load(env("EXERIS_PASSIVE_MAX_EJECTION_PERCENT", "50")).pool().maxEjectionPercent())
                .isEqualTo(50);
        for (String bad : new String[] {"0", "101"}) {
            assertThatThrownBy(() -> GatewayConfig.load(env("EXERIS_PASSIVE_MAX_EJECTION_PERCENT", bad)))
                    .isInstanceOf(GatewayConfigException.class)
                    .hasMessageContaining("EXERIS_PASSIVE_MAX_EJECTION_PERCENT");
        }
    }

    @Test
    @DisplayName("Active health checking is read when enabled and refuses a path that could change the host")
    void activeHealthCheck() {
        GatewayConfig.ActiveHealthCheck probe = GatewayConfig.load(env(
                "EXERIS_ACTIVE_HEALTH_CHECK_ENABLED", "true",
                "EXERIS_ACTIVE_HEALTH_CHECK_PATH", "/ready",
                "EXERIS_ACTIVE_HEALTH_CHECK_INTERVAL_MS", "250",
                "EXERIS_ACTIVE_HEALTH_CHECK_TIMEOUT_MS", "100",
                "EXERIS_ACTIVE_HEALTHY_THRESHOLD", "4",
                "EXERIS_ACTIVE_UNHEALTHY_THRESHOLD", "5")).pool().activeHealthCheck();
        assertThat(probe).isEqualTo(new GatewayConfig.ActiveHealthCheck("/ready", 250, 100, 4, 5));
        assertThat(GatewayConfig.load(env("EXERIS_ACTIVE_HEALTH_CHECK_ENABLED", "true"))
                .pool().activeHealthCheck().path()).isEqualTo("/health");

        for (String bad : new String[] {"ready", "//evil.example/x", "/a?b=c", "/a@b"}) {
            assertThatThrownBy(() -> GatewayConfig.load(env(
                    "EXERIS_ACTIVE_HEALTH_CHECK_ENABLED", "true", "EXERIS_ACTIVE_HEALTH_CHECK_PATH", bad)))
                    .as(bad)
                    .isInstanceOf(GatewayConfigException.class)
                    .hasMessageContaining("EXERIS_ACTIVE_HEALTH_CHECK_PATH");
        }
    }

    @Test
    @DisplayName("Half-open permits above what the breaker can track are refused at load, naming the setting")
    void halfOpenPermitsAreBoundedByTheBreaker() {
        assertThat(GatewayConfig.load(env("EXERIS_CIRCUIT_BREAKER_ENABLED", "true",
                "EXERIS_CB_PERMITTED_HALF_OPEN", "1024")).circuitBreaker().halfOpenPermits()).isEqualTo(1024);
        assertThatThrownBy(() -> GatewayConfig.load(env("EXERIS_CIRCUIT_BREAKER_ENABLED", "true",
                "EXERIS_CB_PERMITTED_HALF_OPEN", "1025")))
                .isInstanceOf(GatewayConfigException.class)
                .hasMessageContaining("EXERIS_CB_PERMITTED_HALF_OPEN")
                .hasMessageContaining("1024");
    }

    @Test
    @DisplayName("A minimum call count above the sliding window is refused at load, naming both settings")
    void minimumCallsMustFitTheWindow() {
        assertThatThrownBy(() -> GatewayConfig.load(env("EXERIS_CIRCUIT_BREAKER_ENABLED", "true",
                "EXERIS_CB_SLIDING_WINDOW", "10", "EXERIS_CB_MIN_CALLS", "11")))
                .isInstanceOf(GatewayConfigException.class)
                .hasMessageContaining("EXERIS_CB_MIN_CALLS")
                .hasMessageContaining("EXERIS_CB_SLIDING_WINDOW");
    }

    private static final EdDsaTokens TOKENS = new EdDsaTokens();

    private static Map<String, String> jwtEnv(String... kv) {
        Map<String, String> m = env("EXERIS_JWT_ENABLED", "true", "EXERIS_JWT_ISSUER", EdDsaTokens.ISSUER,
                "EXERIS_JWT_AUDIENCE", EdDsaTokens.AUDIENCE);
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("JWT enabled with no key source refuses to start and names both sources")
    void jwtWithoutKeySourceIsRefused() {
        assertThatThrownBy(() -> GatewayConfig.load(jwtEnv()))
                .isInstanceOf(GatewayConfigException.class)
                .hasMessageContaining("EXERIS_JWT_JWKS_URI")
                .hasMessageContaining("EXERIS_JWT_PUBLIC_KEY");
    }

    @Test
    @DisplayName("JWT enabled with both a JWKS URI and a static key refuses to start")
    void jwtWithBothKeySourcesIsRefused() {
        assertThatThrownBy(() -> GatewayConfig.load(jwtEnv("EXERIS_JWT_JWKS_URI", "https://auth.example.com/jwks",
                "EXERIS_JWT_PUBLIC_KEY", TOKENS.pem(), "EXERIS_JWT_KEY_ID", "k1")))
                .isInstanceOf(GatewayConfigException.class)
                .hasMessageContaining("both are set");
    }

    @Test
    @DisplayName("JWT enabled without an issuer or without an audience refuses to start and names the setting")
    void jwtWithoutIssuerOrAudienceIsRefused() {
        Map<String, String> noIssuer = jwtEnv("EXERIS_JWT_JWKS_URI", "https://auth.example.com/jwks");
        noIssuer.remove("EXERIS_JWT_ISSUER");
        assertThatThrownBy(() -> GatewayConfig.load(noIssuer))
                .isInstanceOf(GatewayConfigException.class).hasMessageContaining("EXERIS_JWT_ISSUER");
        Map<String, String> noAudience = jwtEnv("EXERIS_JWT_JWKS_URI", "https://auth.example.com/jwks");
        noAudience.remove("EXERIS_JWT_AUDIENCE");
        assertThatThrownBy(() -> GatewayConfig.load(noAudience))
                .isInstanceOf(GatewayConfigException.class).hasMessageContaining("EXERIS_JWT_AUDIENCE");
    }

    @Test
    @DisplayName("A JWKS URI is https, or http on a loopback host, and nothing else")
    void jwksUriSchemeRule() {
        assertThat(GatewayConfig.load(jwtEnv("EXERIS_JWT_JWKS_URI", "https://auth.example.com/jwks")).jwt().jwksUri())
                .isNotNull();
        assertThat(GatewayConfig.load(jwtEnv("EXERIS_JWT_JWKS_URI", "http://127.0.0.1:9000/jwks")).jwt().jwksUri())
                .isNotNull();
        assertThat(GatewayConfig.load(jwtEnv("EXERIS_JWT_JWKS_URI", "http://localhost/jwks")).jwt().jwksUri())
                .isNotNull();
        for (String bad : new String[] {"http://auth.example.com/jwks", "ftp://127.0.0.1/jwks", "/jwks", "not a uri"}) {
            assertThatThrownBy(() -> GatewayConfig.load(jwtEnv("EXERIS_JWT_JWKS_URI", bad)))
                    .as(bad).isInstanceOf(GatewayConfigException.class).hasMessageContaining("EXERIS_JWT_JWKS_URI");
        }
    }

    @Test
    @DisplayName("A static key needs a key id, is read from a PEM value or file, and must be a public key")
    void staticKey() throws Exception {
        assertThatThrownBy(() -> GatewayConfig.load(jwtEnv("EXERIS_JWT_PUBLIC_KEY", TOKENS.pem())))
                .isInstanceOf(GatewayConfigException.class).hasMessageContaining("EXERIS_JWT_KEY_ID");

        GatewayConfig inline = GatewayConfig.load(jwtEnv("EXERIS_JWT_PUBLIC_KEY", TOKENS.pem(), "EXERIS_JWT_KEY_ID", "k1"));
        assertThat(inline.jwt().publicKey()).isEqualTo(TOKENS.publicKey());
        assertThat(inline.jwt().keyId()).isEqualTo("k1");
        assertThat(inline.jwt().acceptRs256()).isFalse();

        Path pem = Files.writeString(Files.createTempFile("jwt", ".pem"), TOKENS.pem());
        GatewayConfig fromFile = GatewayConfig.load(jwtEnv("EXERIS_JWT_PUBLIC_KEY_FILE", pem.toString(),
                "EXERIS_JWT_KEY_ID", "k1", "EXERIS_JWT_ACCEPT_RS256", "true"));
        assertThat(fromFile.jwt().publicKey()).isEqualTo(TOKENS.publicKey());
        assertThat(fromFile.jwt().acceptRs256()).isTrue();

        assertThatThrownBy(() -> GatewayConfig.load(jwtEnv("EXERIS_JWT_PUBLIC_KEY", TOKENS.pem(),
                "EXERIS_JWT_PUBLIC_KEY_FILE", pem.toString(), "EXERIS_JWT_KEY_ID", "k1")))
                .isInstanceOf(GatewayConfigException.class).hasMessageContaining("both set");
        assertThatThrownBy(() -> GatewayConfig.load(jwtEnv("EXERIS_JWT_PUBLIC_KEY", "garbage", "EXERIS_JWT_KEY_ID", "k1")))
                .isInstanceOf(GatewayConfigException.class).hasMessageContaining("EXERIS_JWT_PUBLIC_KEY");
        assertThatThrownBy(() -> GatewayConfig.load(jwtEnv("EXERIS_JWT_PUBLIC_KEY_FILE", "/nonexistent/key.pem",
                "EXERIS_JWT_KEY_ID", "k1")))
                .isInstanceOf(GatewayConfigException.class).hasMessageContaining("EXERIS_JWT_PUBLIC_KEY_FILE");
    }

    @Test
    @DisplayName("The removed JWT settings refuse to start and name their replacement, with JWT on or off")
    void removedJwtSettingsAreRefused() {
        for (boolean enabled : new boolean[] {true, false}) {
            Map<String, String> secret = env("EXERIS_JWT_ENABLED", String.valueOf(enabled), "EXERIS_JWT_SECRET", "x");
            assertThatThrownBy(() -> GatewayConfig.load(secret))
                    .isInstanceOf(GatewayConfigException.class)
                    .hasMessageContaining("EXERIS_JWT_SECRET")
                    .hasMessageContaining("EXERIS_JWT_JWKS_URI")
                    .hasMessageContaining("EXERIS_JWT_PUBLIC_KEY");
            Map<String, String> cache = env("EXERIS_JWT_ENABLED", String.valueOf(enabled), "EXERIS_JWT_CACHE_CAPACITY", "10");
            assertThatThrownBy(() -> GatewayConfig.load(cache))
                    .isInstanceOf(GatewayConfigException.class)
                    .hasMessageContaining("EXERIS_JWT_CACHE_CAPACITY")
                    .hasMessageContaining("no verification result is cached");
        }
    }

    @Test
    @DisplayName("CORS origins are read when configured; the wildcard only when written")
    void corsOnlyWhenConfigured() {
        assertThat(GatewayConfig.load(env("EXERIS_CORS_ALLOWED_ORIGINS", "https://a.example, https://b.example"))
                .corsOrigins()).containsExactly("https://a.example", "https://b.example");
    }

    @Test
    @DisplayName("No routes refuses to start")
    void noRoutesIsRefused() {
        Map<String, String> m = env();
        m.remove("EXERIS_GATEWAY_ROUTES");
        assertThatThrownBy(() -> GatewayConfig.load(m))
                .isInstanceOf(GatewayConfigException.class)
                .hasMessageContaining("EXERIS_GATEWAY_ROUTES");
    }

    @Test
    @DisplayName("Routes and clusters parse; a route may strip its prefix or target a cluster")
    void routesAndClustersParse() {
        GatewayConfig c = GatewayConfig.load(env(
                "EXERIS_GATEWAY_ROUTES", "/a=http://one:81/base/;/b=cluster://pool,strip",
                "EXERIS_GATEWAY_CLUSTERS", "pool=http://h1:1,http://h2:2"));

        assertThat(c.routes()).hasSize(2);
        assertThat(c.routes().get(0).target()).isEqualTo("http://one:81/base");
        assertThat(c.routes().get(0).stripPrefix()).isFalse();
        assertThat(c.routes().get(1).targetsCluster()).isTrue();
        assertThat(c.routes().get(1).stripPrefix()).isTrue();
        assertThat(c.clusters().get(0).endpoints()).containsExactly("http://h1:1", "http://h2:2");
    }

    @Test
    @DisplayName("A route to an undeclared cluster, an https upstream and a non-canonical prefix are refused")
    void badRoutesAreRefused() {
        assertThatThrownBy(() -> GatewayConfig.load(env("EXERIS_GATEWAY_ROUTES", "/a=cluster://ghost")))
                .isInstanceOf(GatewayConfigException.class).hasMessageContaining("ghost");
        assertThatThrownBy(() -> GatewayConfig.load(env("EXERIS_GATEWAY_ROUTES", "/a=https://secure:443")))
                .isInstanceOf(GatewayConfigException.class).hasMessageContaining("does not provide TLS");
        assertThatThrownBy(() -> GatewayConfig.load(env("EXERIS_GATEWAY_ROUTES", "/a/../b=http://x:1")))
                .isInstanceOf(GatewayConfigException.class);
        assertThatThrownBy(() -> GatewayConfig.load(env("EXERIS_GATEWAY_ROUTES", "api=http://x:1")))
                .isInstanceOf(GatewayConfigException.class);
    }

    @Test
    @DisplayName("Management on binds loopback by default and may not share the ingress port")
    void managementDefaultsToLoopback() {
        GatewayConfig c = GatewayConfig.load(env("EXERIS_MANAGEMENT_ENABLED", "true"));

        assertThat(c.management().bindHost()).isEqualTo("127.0.0.1");
        assertThat(c.management().port()).isEqualTo(9090);
        assertThatThrownBy(() -> GatewayConfig.load(env(
                "EXERIS_MANAGEMENT_ENABLED", "true", "EXERIS_MANAGEMENT_PORT", "8080")))
                .isInstanceOf(GatewayConfigException.class).hasMessageContaining("ingress");
    }

    @Test
    @DisplayName("A malformed value names its setting")
    void malformedValueNamesSetting() {
        assertThatThrownBy(() -> GatewayConfig.load(env("EXERIS_HTTP_PORT", "eighty")))
                .isInstanceOf(GatewayConfigException.class).hasMessageContaining("EXERIS_HTTP_PORT");
        assertThatThrownBy(() -> GatewayConfig.load(env("EXERIS_WAF_ENABLED", "yes")))
                .isInstanceOf(GatewayConfigException.class).hasMessageContaining("EXERIS_WAF_ENABLED");
    }

    @Test
    @DisplayName("A properties file supplies settings, the environment overrides it, an unknown key is refused")
    void fileAndEnvironment(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("gateway.properties");
        Files.writeString(file, "EXERIS_GATEWAY_ROUTES=/f=http://file:1\nEXERIS_HTTP_PORT=9000\n");

        GatewayConfig fromFile = GatewayConfig.load(Map.of("EXERIS_GATEWAY_CONFIG", file.toString()));
        assertThat(fromFile.http().port()).isEqualTo(9000);
        assertThat(fromFile.routes().get(0).prefix()).isEqualTo("/f");

        GatewayConfig overridden = GatewayConfig.load(Map.of(
                "EXERIS_GATEWAY_CONFIG", file.toString(), "EXERIS_HTTP_PORT", "9100"));
        assertThat(overridden.http().port()).isEqualTo(9100);

        Files.writeString(file, "EXERIS_JWT_SECRT=oops\n");
        assertThatThrownBy(() -> GatewayConfig.load(Map.of("EXERIS_GATEWAY_CONFIG", file.toString())))
                .isInstanceOf(GatewayConfigException.class).hasMessageContaining("EXERIS_JWT_SECRT");
    }
}
