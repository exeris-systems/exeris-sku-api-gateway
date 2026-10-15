package eu.exeris.sku.gateway;

import eu.exeris.caps.pool.api.PassiveHealthCheckConfig;
import eu.exeris.caps.pool.api.UpstreamPoolService;
import eu.exeris.caps.pool.api.UpstreamPools;
import eu.exeris.caps.routing.api.RequestRouter;
import eu.exeris.caps.routing.api.RequestRouters;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.sku.gateway.routing.RoutingReverseProxyHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RoutingReverseProxyHandler: what is forwarded")
class ProxyForwardingTest {

    private static final String UNVERIFIED_TOKEN =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJtYWxsb3J5In0.forged";

    private final StubEngine engine = new StubEngine();

    private RequestRouter router(String target) {
        return RequestRouters.builder()
                .route("r").pathPrefix("/api").targetUri(target)
                    .injectClaimHeader("X-User-Id", "sub")
                    .and()
                .build();
    }

    private static boolean has(eu.exeris.kernel.spi.http.HttpRequest r, String name) {
        return r.headers().stream().anyMatch(h -> h.nameEqualsIgnoreCase(name));
    }

    @Test
    @DisplayName("Client-supplied Forwarded, X-Forwarded-* and X-Real-IP are not passed upstream")
    void forwardingHeadersAreDropped() {
        RoutingReverseProxyHandler handler = new RoutingReverseProxyHandler(router("http://up:1"), engine);

        handler.handle(TestExchange.get("/api/x",
                new HttpHeader("Forwarded", "for=1.2.3.4"),
                new HttpHeader("X-Forwarded-For", "1.2.3.4"),
                new HttpHeader("x-forwarded-host", "evil.example"),
                new HttpHeader("X-Forwarded-Proto", "https"),
                new HttpHeader("X-Real-IP", "1.2.3.4"),
                new HttpHeader("Accept", "text/plain")));

        assertThat(engine.last().firstHeader("Accept")).contains("text/plain");
        for (String name : List.of("Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Real-IP")) {
            assertThat(has(engine.last(), name)).as(name).isFalse();
        }
    }

    @Test
    @DisplayName("A header the Connection header names is hop-by-hop and is not passed upstream")
    void connectionTokensAreDropped() {
        RoutingReverseProxyHandler handler = new RoutingReverseProxyHandler(router("http://up:1"), engine);

        handler.handle(TestExchange.get("/api/x",
                new HttpHeader("Connection", "close, X-Hop"),
                new HttpHeader("X-Hop", "1"),
                new HttpHeader("Keep-Alive", "timeout=5"),
                new HttpHeader("X-Keep", "1")));

        assertThat(has(engine.last(), "X-Hop")).isFalse();
        assertThat(has(engine.last(), "Keep-Alive")).isFalse();
        assertThat(has(engine.last(), "Connection")).isFalse();
        assertThat(has(engine.last(), "X-Keep")).isTrue();
    }

    @Test
    @DisplayName("Claims are not read from a token no admission policy verified; a client-sent identity header is dropped")
    void unverifiedTokenInjectsNoClaims() {
        RoutingReverseProxyHandler handler = new RoutingReverseProxyHandler(router("http://up:1"), engine);

        handler.handle(TestExchange.get("/api/x",
                new HttpHeader("Authorization", "Bearer " + UNVERIFIED_TOKEN),
                new HttpHeader("X-User-Id", "admin")));

        assertThat(has(engine.last(), "X-User-Id")).isFalse();
    }

    @Test
    @DisplayName("With the token verified, the claim is injected and replaces what the client sent")
    void verifiedTokenInjectsClaims() {
        RoutingReverseProxyHandler handler =
                new RoutingReverseProxyHandler(router("http://up:1"), engine, null, false, true);

        handler.handle(TestExchange.get("/api/x",
                new HttpHeader("Authorization", "Bearer " + UNVERIFIED_TOKEN),
                new HttpHeader("X-User-Id", "admin")));

        assertThat(engine.last().headers().stream().filter(h -> h.nameEqualsIgnoreCase("X-User-Id")).map(HttpHeader::value))
                .containsExactly("mallory");
    }

    @Test
    @DisplayName("An upstream failure answers 502 without leaking the upstream's error text")
    void upstreamFailureIs502() {
        engine.answer = r -> {
            throw new IllegalStateException("connect to 10.9.8.7:5432 refused");
        };
        RoutingReverseProxyHandler handler = new RoutingReverseProxyHandler(router("http://up:1"), engine);
        TestExchange ex = TestExchange.get("/api/x");

        handler.handle(ex);

        assertThat(ex.status()).isEqualTo(502);
        assertThat(ex.response.get().body()).isNull();
    }

    private UpstreamPoolService pool() {
        UpstreamPoolService service = UpstreamPools.newService();
        service.registerCluster(UpstreamPools.cluster("pool")
                .passiveHealthCheck(PassiveHealthCheckConfig.of(1, Duration.ofSeconds(10)))
                .addEndpoint("http://bad:1")
                .addEndpoint("http://good:2")
                .build());
        return service;
    }

    @Test
    @DisplayName("A route to a cluster the pool does not hold answers 503, never an attempt at the cluster name")
    void unknownClusterIs503() {
        RoutingReverseProxyHandler handler =
                new RoutingReverseProxyHandler(router("cluster://ghost"), engine, pool(), true, false);
        TestExchange ex = TestExchange.get("/api/x");

        handler.handle(ex);

        assertThat(ex.status()).isEqualTo(503);
        assertThat(engine.requests).isEmpty();
    }

    @Test
    @DisplayName("Failover retries a GET on another host and releases the discarded response")
    void failoverRetriesGet() {
        engine.answer = r -> "bad:1".equals(r.authority())
                ? engine.response(HttpStatus.INTERNAL_SERVER_ERROR) : engine.ok();
        RoutingReverseProxyHandler handler =
                new RoutingReverseProxyHandler(router("cluster://pool"), engine, pool(), true, false);
        TestExchange ex = TestExchange.get("/api/x");

        handler.handle(ex);

        assertThat(ex.status()).isEqualTo(200);
        assertThat(engine.requests).hasSize(2);
        // The 500 response that was replaced had its buffer released
        assertThat(engine.bodies.get(0).isAlive()).isFalse();
    }

    @Test
    @DisplayName("A POST is never replayed on another host after a 5xx")
    void postIsNotReplayed() {
        engine.answer = r -> engine.response(HttpStatus.INTERNAL_SERVER_ERROR);
        RoutingReverseProxyHandler handler =
                new RoutingReverseProxyHandler(router("cluster://pool"), engine, pool(), true, false);
        TestExchange ex = new TestExchange(HttpMethod.POST, "/api/pay", List.of(), new ByteArrayLoanedBuffer("x".getBytes()));

        handler.handle(ex);

        assertThat(ex.status()).isEqualTo(500);
        assertThat(engine.requests).hasSize(1);
    }
}
