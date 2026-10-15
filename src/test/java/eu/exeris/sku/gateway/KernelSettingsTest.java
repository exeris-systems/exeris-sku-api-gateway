package eu.exeris.sku.gateway;

import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.sku.gateway.config.GatewayConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("What the configuration hands the kernel")
class KernelSettingsTest {

    private static GatewayConfig config(String... kv) {
        Map<String, String> env = new java.util.HashMap<>();
        env.put("EXERIS_GATEWAY_ROUTES", "/api=http://backend:8080");
        for (int i = 0; i < kv.length; i += 2) {
            env.put(kv[i], kv[i + 1]);
        }
        return GatewayConfig.load(env);
    }

    @Test
    @DisplayName("Request-size limits reach the kernel's HTTP server and carry a documented default")
    void requestLimitsAreSet() {
        Map<String, String> defaults = GatewaySkuApplication.kernelProperties(config());
        assertThat(defaults).containsEntry("exeris.http.maxRequestBodyBytes", String.valueOf(10L * 1024 * 1024))
                .containsEntry("exeris.http.maxRequestHeaderCount", "100")
                .containsEntry("exeris.http.maxRequestHeaderSize", "8192");

        Map<String, String> custom = GatewaySkuApplication.kernelProperties(config(
                "EXERIS_HTTP_MAX_REQUEST_BODY_BYTES", "4096", "EXERIS_HTTP_PORT", "8443"));
        assertThat(custom).containsEntry("exeris.http.maxRequestBodyBytes", "4096")
                .containsEntry("exeris.http.port", "8443");
    }

    @Test
    @DisplayName("The gateway sets no TLS property: it neither claims nor declines TLS")
    void noTlsFlag() {
        assertThat(GatewaySkuApplication.kernelProperties(config()).keySet())
                .noneMatch(key -> key.toLowerCase().contains("tls"));
    }

    @Test
    @DisplayName("The upstream timeout and response limit reach the client engine")
    void upstreamLimitsReachTheClient() {
        HttpConfig client = GatewaySkuApplication.clientConfig(config(
                "EXERIS_UPSTREAM_TIMEOUT_MS", "1500", "EXERIS_UPSTREAM_MAX_RESPONSE_BYTES", "2048"));

        assertThat(client.idleTimeoutMillis()).isEqualTo(1500);
        assertThat(client.maxResponseBodyBytes()).isEqualTo(2048);
        assertThat(GatewaySkuApplication.clientConfig(config()).idleTimeoutMillis()).isEqualTo(30_000);
    }

    @Test
    @DisplayName("The management listener is loopback unless configured otherwise")
    void managementListenerBinding() {
        GatewayConfig c = config("EXERIS_MANAGEMENT_ENABLED", "true");
        HttpConfig management = GatewaySkuApplication.managementConfig(c.management());

        assertThat(management.bindHost()).isEqualTo("127.0.0.1");
        assertThat(management.port()).isEqualTo(9090);

        GatewayConfig wide = config("EXERIS_MANAGEMENT_ENABLED", "true",
                "EXERIS_MANAGEMENT_BIND_HOST", "0.0.0.0", "EXERIS_MANAGEMENT_PORT", "9191");
        assertThat(GatewaySkuApplication.managementConfig(wide.management()).bindHost()).isEqualTo("0.0.0.0");
    }
}
