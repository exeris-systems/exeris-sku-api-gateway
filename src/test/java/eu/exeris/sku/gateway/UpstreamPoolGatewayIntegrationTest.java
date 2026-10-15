package eu.exeris.sku.gateway;

import eu.exeris.caps.pool.api.LoadBalancingAlgorithm;
import eu.exeris.caps.pool.api.PassiveHealthCheckConfig;
import eu.exeris.caps.pool.api.UpstreamCluster;
import eu.exeris.caps.pool.api.UpstreamPoolService;
import eu.exeris.caps.pool.api.UpstreamPools;
import eu.exeris.caps.routing.api.RequestRouter;
import eu.exeris.caps.routing.api.RequestRouters;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.sku.gateway.routing.RoutingReverseProxyHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class UpstreamPoolGatewayIntegrationTest {

    @Test
    @DisplayName("RoutingReverseProxyHandler balances requests across UpstreamCluster endpoints")
    void testLoadBalancingAcrossCluster() {
        UpstreamPoolService poolService = UpstreamPools.newService();

        UpstreamCluster cluster = UpstreamPools.cluster("test-pool")
                .algorithm(LoadBalancingAlgorithm.ROUND_ROBIN)
                .addEndpoint("http://backend-1:9001")
                .addEndpoint("http://backend-2:9002")
                .build();
        poolService.registerCluster(cluster);

        RequestRouter router = RequestRouters.builder()
                .route("cluster-route")
                .pathPrefix("/api/service")
                .targetUri("cluster://test-pool")
                .and()
                .build();

        ConcurrentHashMap<String, AtomicInteger> receivedByHost = new ConcurrentHashMap<>();

        HttpClientEngine mockEngine = new HttpClientEngine() {
            @Override
            public String engineName() { return "mock"; }
            @Override
            public boolean isRunning() { return true; }
            @Override
            public void start() {}
            @Override
            public void close() {}
            @Override
            public HttpResponse send(HttpRequest request) {
                receivedByHost.computeIfAbsent(request.authority(), k -> new AtomicInteger(0)).incrementAndGet();
                byte[] resp = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
                return new HttpResponse(
                        HttpStatus.OK,
                        HttpVersion.HTTP_1_1,
                        List.of(),
                        new ByteArrayLoanedBuffer(resp)
                );
            }
        };

        RoutingReverseProxyHandler handler = new RoutingReverseProxyHandler(router, mockEngine, poolService, true, false);

        for (int i = 0; i < 10; i++) {
            HttpRequest req = new HttpRequest(
                    HttpMethod.GET, null, "/api/service/item", HttpVersion.HTTP_1_1, List.of(), null
            );
            AtomicReference<HttpResponse> respRef = new AtomicReference<>();
            HttpExchange exchange = createMockExchange(req, respRef);
            handler.handle(exchange);

            assertThat(respRef.get()).isNotNull();
            assertThat(respRef.get().status()).isEqualTo(HttpStatus.OK);
        }

        assertThat(receivedByHost.get("backend-1:9001").get()).isEqualTo(5);
        assertThat(receivedByHost.get("backend-2:9002").get()).isEqualTo(5);

        poolService.close();
    }

    @Test
    @DisplayName("RoutingReverseProxyHandler performs zero-allocation failover when upstream returns 500")
    void testFailoverOn500() {
        UpstreamPoolService poolService = UpstreamPools.newService();

        UpstreamCluster cluster = UpstreamPools.cluster("failover-pool")
                .algorithm(LoadBalancingAlgorithm.ROUND_ROBIN)
                .passiveHealthCheck(PassiveHealthCheckConfig.of(1, Duration.ofSeconds(10)))
                .addEndpoint("http://failing-node:9001")
                .addEndpoint("http://healthy-node:9002")
                .build();
        poolService.registerCluster(cluster);

        RequestRouter router = RequestRouters.builder()
                .route("failover-route")
                .pathPrefix("/api/failover")
                .targetUri("cluster://failover-pool")
                .and()
                .build();

        HttpClientEngine mockEngine = new HttpClientEngine() {
            @Override
            public String engineName() { return "mock"; }
            @Override
            public boolean isRunning() { return true; }
            @Override
            public void start() {}
            @Override
            public void close() {}
            @Override
            public HttpResponse send(HttpRequest request) {
                if (request.authority().contains("failing-node")) {
                    byte[] err = "{\"error\":\"crash\"}".getBytes(StandardCharsets.UTF_8);
                    return new HttpResponse(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            HttpVersion.HTTP_1_1,
                            List.of(),
                            new ByteArrayLoanedBuffer(err)
                    );
                } else {
                    byte[] ok = "{\"status\":\"recovered\"}".getBytes(StandardCharsets.UTF_8);
                    return new HttpResponse(
                            HttpStatus.OK,
                            HttpVersion.HTTP_1_1,
                            List.of(),
                            new ByteArrayLoanedBuffer(ok)
                    );
                }
            }
        };

        RoutingReverseProxyHandler handler = new RoutingReverseProxyHandler(router, mockEngine, poolService, true, false);

        // First request is routed to failing-node, which returns 500, triggering immediate failover to healthy-node!
        HttpRequest req = new HttpRequest(
                HttpMethod.GET, null, "/api/failover/test", HttpVersion.HTTP_1_1, List.of(), null
        );
        AtomicReference<HttpResponse> respRef = new AtomicReference<>();
        HttpExchange exchange = createMockExchange(req, respRef);
        handler.handle(exchange);

        assertThat(respRef.get()).isNotNull();
        // Client receives 200 OK because gateway successfully failed over to healthy-node!
        assertThat(respRef.get().status()).isEqualTo(HttpStatus.OK);

        // And failing-node should now be marked UNHEALTHY by passive outlier detection
        assertThat(cluster.allHosts().get(0).isAvailable()).isFalse();

        poolService.close();
    }

    private static HttpExchange createMockExchange(HttpRequest request, AtomicReference<HttpResponse> capturedResp) {
        return new HttpExchange() {
            @Override
            public HttpRequest request() {
                return request;
            }
            @Override
            public void respond(HttpResponse response) {
                capturedResp.set(response);
            }
            @Override
            public void respond(HttpTypedResponse typedResponse) {
                throw new UnsupportedOperationException();
            }
            @Override
            public void respond(HttpStatus status) {
                capturedResp.set(new HttpResponse(status, HttpVersion.HTTP_1_1, List.of(), null));
            }
        };
    }
}
