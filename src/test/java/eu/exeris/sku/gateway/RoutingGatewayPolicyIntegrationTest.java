package eu.exeris.sku.gateway;

import eu.exeris.caps.gatewaycore.GatewayCoreModule;
import eu.exeris.caps.gatewaycore.api.GatewayLifecycle;
import eu.exeris.caps.gatewaycore.api.IngressBootstrap;
import eu.exeris.caps.gatewaycore.api.PolicyChain;
import eu.exeris.caps.routing.api.RequestRouter;
import eu.exeris.caps.routing.api.RequestRouters;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.sku.gateway.routing.RoutingGatewayPolicy;
import eu.exeris.sku.gateway.routing.RoutingReverseProxyHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RoutingGatewayPolicy and ReverseProxyHandler Integration Test")
class RoutingGatewayPolicyIntegrationTest {

    private GatewayCoreModule gatewayModule;
    private IngressBootstrap ingress;
    private GatewayLifecycle lifecycle;
    private PolicyChain policyChain;
    private RequestRouter router;
    private AtomicReference<HttpRequest> upstreamReceivedReq;
    private AtomicReference<HttpResponse> upstreamReturnedResp;
    private HttpClientEngine mockEngine;

    @BeforeEach
    void setUp() throws Exception {
        gatewayModule = new GatewayCoreModule();
        ingress = gatewayModule.ingressBootstrap();
        lifecycle = gatewayModule.gatewayLifecycle();
        policyChain = gatewayModule.policyChain();

        upstreamReceivedReq = new AtomicReference<>();
        upstreamReturnedResp = new AtomicReference<>();

        mockEngine = new HttpClientEngine() {
            @Override
            public String engineName() {
                return "mock";
            }

            @Override
            public boolean isRunning() {
                return true;
            }

            @Override
            public void start() {}

            @Override
            public void close() {}

            @Override
            public HttpResponse send(HttpRequest request) {
                upstreamReceivedReq.set(request);
                return upstreamReturnedResp.get() != null
                        ? upstreamReturnedResp.get()
                        : new HttpResponse(HttpStatus.OK, HttpVersion.HTTP_1_1, List.of(new HttpHeader("Server", "Internal-Mock")), null);
            }
        };

        // Build RequestRouter with sample routes
        router = RequestRouters.builder()
                .route("items-route")
                    .pathPrefix("/api/items")
                    .targetUri("http://mock-service:9000")
                    .addRequestHeader("X-Gateway-Route", "items-route")
                    .injectClaimHeader("X-User-Id", "sub")
                    .removeRequestHeader("Authorization")
                    .addResponseHeader("X-Handled-By", "exeris-gateway")
                    .removeResponseHeader("Server")
                    .and()
                .route("strip-route")
                    .pathPrefix("/service-prefix")
                    .stripPrefix("/service-prefix")
                    .targetUri("http://mock-service:9000")
                    .and()
                .build();

        policyChain.addPolicy(new RoutingGatewayPolicy(router));

        // The admission policy has verified the bearer token, so its claims may be injected
        ingress.setDelegateHandler(new RoutingReverseProxyHandler(router, mockEngine, null, false, true));

        gatewayModule.initialize();
        gatewayModule.ready();
    }

    @Test
    @DisplayName("unknown route is rejected with 404 NOT_FOUND by RoutingGatewayPolicy")
    void unknownRouteRejectedWith404() {
        HttpRequest req = new HttpRequest(HttpMethod.GET, null, "/unknown/nonexistent", HttpVersion.HTTP_1_1, List.of(), null);
        AtomicReference<HttpResponse> responseRef = new AtomicReference<>();

        ingress.handle(createExchange(req, responseRef));

        HttpResponse resp = responseRef.get();
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(upstreamReceivedReq.get()).isNull(); // Did not forward to upstream
    }

    @Test
    @DisplayName("known route matches, strips Authorization, injects X-User-Id claim, and transforms response")
    void knownRouteDispatchesAndTransformsHeaders() {
        // JWT payload: {"sub":"alice_123","iss":"exeris"}
        // eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJhbGljZV8xMjMiLCJpc3MiOiJleGVyaXMifQ.signature
        String token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJhbGljZV8xMjMiLCJpc3MiOiJleGVyaXMifQ.fakesig";

        HttpRequest req = new HttpRequest(
                HttpMethod.GET,
                null,
                "/api/items/orders",
                HttpVersion.HTTP_1_1,
                List.of(
                        new HttpHeader("Host", "gateway.example.com"),
                        new HttpHeader("Authorization", "Bearer " + token),
                        new HttpHeader("Accept", "application/json")
                ),
                null
        );

        AtomicReference<HttpResponse> responseRef = new AtomicReference<>();
        ingress.handle(createExchange(req, responseRef));

        // 1. Verify upstream request
        HttpRequest sentToUpstream = upstreamReceivedReq.get();
        assertThat(sentToUpstream).isNotNull();
        assertThat(sentToUpstream.authority()).isEqualTo("mock-service:9000");
        assertThat(sentToUpstream.path()).isEqualTo("/api/items/orders");

        // Authorization was stripped
        assertThat(sentToUpstream.firstHeader("Authorization")).isEmpty();

        // X-User-Id claim was injected
        assertThat(sentToUpstream.firstHeader("X-User-Id")).contains("alice_123");

        // X-Gateway-Route was added
        assertThat(sentToUpstream.firstHeader("X-Gateway-Route")).contains("items-route");

        // 2. Verify downstream response
        HttpResponse resp = responseRef.get();
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo(HttpStatus.OK);
        // Server header removed from upstream response
        assertThat(resp.headers().stream().anyMatch(h -> h.nameEqualsIgnoreCase("Server"))).isFalse();
        // X-Handled-By added
        assertThat(resp.headers().stream().anyMatch(h -> h.nameEqualsIgnoreCase("X-Handled-By") && h.value().equals("exeris-gateway"))).isTrue();
    }

    @Test
    @DisplayName("prefix strip route rewrites path correctly before forwarding")
    void prefixStripRewritesPath() {
        HttpRequest req = new HttpRequest(
                HttpMethod.GET,
                null,
                "/service-prefix/items?page=1",
                HttpVersion.HTTP_1_1,
                List.of(),
                null
        );

        AtomicReference<HttpResponse> responseRef = new AtomicReference<>();
        ingress.handle(createExchange(req, responseRef));

        HttpRequest sentToUpstream = upstreamReceivedReq.get();
        assertThat(sentToUpstream).isNotNull();
        assertThat(sentToUpstream.path()).isEqualTo("/items?page=1");
    }

    private HttpExchange createExchange(HttpRequest request, AtomicReference<HttpResponse> responseRef) {
        return new HttpExchange() {
            @Override
            public HttpRequest request() {
                return request;
            }

            @Override
            public void respond(HttpResponse response) {
                responseRef.set(response);
            }

            @Override
            public void respond(HttpTypedResponse typedResponse) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void respond(HttpStatus status) {
                responseRef.set(new HttpResponse(status, HttpVersion.HTTP_1_1, List.of(), null));
            }
        };
    }
}
