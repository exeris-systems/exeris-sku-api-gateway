package eu.exeris.sku.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real application, kernel included, in its own JVM against a stand-in upstream, and talks to it
 * over sockets. This is the check that the kernel's HTTP server and the management listener do what the
 * assembly assumes.
 */
@DisplayName("GatewaySkuApplication in a process")
class GatewayProcessIT {

    private Process process;
    private HttpServer upstream;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2)).build();

    @AfterEach
    void stop() {
        if (process != null) {
            process.destroy();
        }
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private Process launch(Map<String, String> env) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("--enable-native-access=ALL-UNNAMED");
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("eu.exeris.sku.gateway.GatewaySkuApplication");
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.redirectOutput(new java.io.File(System.getProperty("java.io.tmpdir"), "gateway-it-" + System.nanoTime() + ".log"));
        pb.environment().putAll(env);
        return pb.start();
    }

    private HttpResponse<String> get(int port, String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET();
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void awaitHealthy(int port) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new AssertionError("gateway exited with " + process.exitValue());
            }
            try {
                if (get(port, "/health").statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
                // not up yet
            }
            Thread.sleep(250);
        }
        throw new AssertionError("gateway did not become healthy");
    }

    @Test
    @DisplayName("Forwards with client forwarding headers dropped; diagnostics only on the management port")
    void endToEnd() throws Exception {
        List<Map<String, List<String>>> seen = new CopyOnWriteArrayList<>();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            seen.add(Map.copyOf(exchange.getRequestHeaders()));
            byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();

        int ingress = freePort();
        int management = freePort();
        process = launch(Map.of(
                "EXERIS_HTTP_BIND_HOST", "127.0.0.1",
                "EXERIS_HTTP_PORT", String.valueOf(ingress),
                "EXERIS_GATEWAY_ROUTES", "/api=http://127.0.0.1:" + upstream.getAddress().getPort(),
                "EXERIS_OBSERVABILITY_ENABLED", "true",
                "EXERIS_MANAGEMENT_ENABLED", "true",
                "EXERIS_MANAGEMENT_PORT", String.valueOf(management)));
        awaitHealthy(ingress);

        HttpResponse<String> proxied = get(ingress, "/api/ping",
                "X-Forwarded-For", "6.6.6.6", "Forwarded", "for=6.6.6.6", "X-Real-IP", "6.6.6.6");
        assertThat(proxied.statusCode()).isEqualTo(200);
        assertThat(proxied.body()).isEqualTo("hello");
        Map<String, List<String>> headers = seen.get(0);
        assertThat(headers.keySet()).noneMatch(k -> k.equalsIgnoreCase("X-Forwarded-For")
                || k.equalsIgnoreCase("Forwarded") || k.equalsIgnoreCase("X-Real-IP"));

        assertThat(get(ingress, "/metrics").statusCode()).isEqualTo(404);
        assertThat(get(ingress, "/actuator/allocations").statusCode()).isEqualTo(404);
        assertThat(get(management, "/metrics").statusCode()).isEqualTo(200);
        assertThat(get(management, "/actuator/allocations").statusCode()).isEqualTo(200);
        assertThat(get(management, "/api/ping").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("A request body over the configured limit is not forwarded")
    void oversizedBodyIsNotForwarded() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        upstream.start();

        int ingress = freePort();
        process = launch(Map.of(
                "EXERIS_HTTP_BIND_HOST", "127.0.0.1",
                "EXERIS_HTTP_PORT", String.valueOf(ingress),
                "EXERIS_HTTP_MAX_REQUEST_BODY_BYTES", "1024",
                "EXERIS_GATEWAY_ROUTES", "/api=http://127.0.0.1:" + upstream.getAddress().getPort()));
        awaitHealthy(ingress);

        HttpRequest small = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ingress + "/api/in"))
                .POST(HttpRequest.BodyPublishers.ofString("x".repeat(100))).timeout(Duration.ofSeconds(5)).build();
        assertThat(client.send(small, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(204);

        HttpRequest large = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ingress + "/api/in"))
                .POST(HttpRequest.BodyPublishers.ofString("x".repeat(64 * 1024))).timeout(Duration.ofSeconds(5)).build();
        int status;
        try {
            status = client.send(large, HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (IOException connectionRefused) {
            status = -1;
        }
        assertThat(status).isNotEqualTo(204);
        assertThat(bodies).hasSize(1);
    }

    @Test
    @DisplayName("An upstream that stops answering is cut off after the upstream timeout and answers 502")
    void stalledUpstreamIsCutOff() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))) {
            Thread acceptor = Thread.startVirtualThread(() -> {
                try {
                    while (true) {
                        silent.accept(); // accepts and never answers
                    }
                } catch (IOException closed) {
                    // test over
                }
            });
            int ingress = freePort();
            process = launch(Map.of(
                    "EXERIS_HTTP_BIND_HOST", "127.0.0.1",
                    "EXERIS_HTTP_PORT", String.valueOf(ingress),
                    "EXERIS_UPSTREAM_TIMEOUT_MS", "1000",
                    "EXERIS_GATEWAY_ROUTES", "/api=http://127.0.0.1:" + silent.getLocalPort()));
            awaitHealthy(ingress);

            long start = System.nanoTime();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ingress + "/api/slow"))
                            .timeout(Duration.ofSeconds(25)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(502);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(20));
            acceptor.interrupt();
        }
    }

    @Test
    @DisplayName("A production environment with no licence manifest does not start")
    void productionWithoutManifestDoesNotStart() throws Exception {
        int ingress = freePort();
        process = launch(Map.of(
                "EXERIS_HTTP_BIND_HOST", "127.0.0.1",
                "EXERIS_HTTP_PORT", String.valueOf(ingress),
                "EXERIS_ENVIRONMENT", "production",
                "EXERIS_GATEWAY_ROUTES", "/api=http://127.0.0.1:1"));

        assertThat(process.waitFor(40, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isNotZero();
    }

    @Test
    @DisplayName("JWT enabled without a secret exits with the configuration status before the kernel starts")
    void jwtWithoutSecretDoesNotStart() throws Exception {
        process = launch(Map.of(
                "EXERIS_JWT_ENABLED", "true",
                "EXERIS_GATEWAY_ROUTES", "/api=http://127.0.0.1:1"));

        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isEqualTo(GatewaySkuApplication.EXIT_CONFIG);
    }
}
