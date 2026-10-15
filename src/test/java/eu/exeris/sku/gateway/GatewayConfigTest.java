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

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

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
    @DisplayName("JWT enabled without a secret refuses to start and names the setting")
    void jwtWithoutSecretIsRefused() {
        assertThatThrownBy(() -> GatewayConfig.load(env("EXERIS_JWT_ENABLED", "true")))
                .isInstanceOf(GatewayConfigException.class)
                .hasMessageContaining("EXERIS_JWT_SECRET");
    }

    @Test
    @DisplayName("A JWT secret shorter than an HMAC-SHA-256 key is refused")
    void shortJwtSecretIsRefused() {
        assertThatThrownBy(() -> GatewayConfig.load(env("EXERIS_JWT_ENABLED", "true", "EXERIS_JWT_SECRET", "short")))
                .isInstanceOf(GatewayConfigException.class)
                .hasMessageContaining("shorter than 32");
    }

    @Test
    @DisplayName("A configured JWT secret is accepted and never printed")
    void jwtSecretIsRedacted() {
        GatewayConfig c = GatewayConfig.load(env("EXERIS_JWT_ENABLED", "true", "EXERIS_JWT_SECRET", SECRET));

        assertThat(c.jwt().secret()).isEqualTo(SECRET);
        assertThat(c.toString()).doesNotContain(SECRET);
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
