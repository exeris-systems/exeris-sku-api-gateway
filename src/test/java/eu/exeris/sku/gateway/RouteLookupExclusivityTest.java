package eu.exeris.sku.gateway;

import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.sku.gateway.config.GatewayConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("A route lookup is held by one request at a time")
class RouteLookupExclusivityTest {

    private static final int THREADS = 64;
    private static final int REQUESTS_PER_THREAD = 1_500;

    /** Answers with the authority it was asked to reach, so a caller can tell which route forwarded it. */
    private static final class EchoEngine implements HttpClientEngine {
        @Override
        public HttpResponse send(HttpRequest request) {
            return new HttpResponse(HttpStatus.OK, HttpVersion.HTTP_1_1,
                    List.of(new HttpHeader("X-Reached", request.authority() + request.path())),
                    new ByteArrayLoanedBuffer("ok".getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public void start() {
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public String engineName() {
            return "echo";
        }

        @Override
        public void close() {
        }
    }

    @Test
    @DisplayName("Concurrent requests on virtual threads for different routes are each forwarded by their own route, through all three match sites")
    void concurrentRequestsKeepTheirRoute() throws Exception {
        Map<String, String> env = Map.of(
                "EXERIS_GATEWAY_ROUTES", "/alpha=http://alpha:1;/beta=http://beta:2,strip",
                "EXERIS_CIRCUIT_BREAKER_ENABLED", "true");
        try (GatewayRuntime gateway = GatewayRuntime.create(GatewayConfig.load(env))) {
            gateway.attach(new EchoEngine());
            gateway.module().initialize();
            gateway.module().ready();

            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            try {
                List<Future<Integer>> results = new java.util.ArrayList<>();
                for (int t = 0; t < THREADS; t++) {
                    final int thread = t;
                    results.add(pool.submit(() -> {
                        int wrong = 0;
                        for (int i = 0; i < REQUESTS_PER_THREAD; i++) {
                            boolean alpha = ((i + thread) & 1) == 0;
                            String path = (alpha ? "/alpha/item" : "/beta/item") + (i % 50);
                            TestExchange ex = TestExchange.get(path);
                            gateway.ingress().handle(ex);
                            String reached = ex.response.get().headers().stream()
                                    .filter(h -> h.nameEqualsIgnoreCase("X-Reached"))
                                    .map(HttpHeader::value).findFirst().orElse("");
                            String expected = alpha ? "alpha:1" + path : "beta:2/item" + (i % 50);
                            if (ex.status() != 200 || !reached.equals(expected)) {
                                wrong++;
                            }
                        }
                        return wrong;
                    }));
                }
                int wrong = 0;
                for (Future<Integer> result : results) {
                    wrong += result.get(60, TimeUnit.SECONDS);
                }
                assertThat(wrong).isZero();
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
