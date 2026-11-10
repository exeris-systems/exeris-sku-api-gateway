package eu.exeris.sku.gateway;

import eu.exeris.caps.breaker.api.CircuitBreaker;
import eu.exeris.caps.breaker.api.CircuitBreakerConfig;
import eu.exeris.caps.breaker.api.CircuitBreakerRegistry;
import eu.exeris.caps.breaker.api.CircuitBreakers;
import eu.exeris.caps.breaker.api.CircuitState;
import eu.exeris.caps.gatewaycore.GatewayCoreModule;
import eu.exeris.caps.gatewaycore.api.IngressBootstrap;
import eu.exeris.caps.gatewaycore.api.PolicyResult;
import eu.exeris.caps.routing.api.RequestRouter;
import eu.exeris.caps.routing.api.RequestRouters;
import eu.exeris.caps.waf.api.WafMode;
import eu.exeris.caps.waf.api.WafPolicies;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.sku.gateway.breaker.CircuitBreakerIngress;
import eu.exeris.sku.gateway.waf.WafGatewayPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every response the gateway writes itself, observed from the engine's side of the hand-over: the bytes the
 * engine reads, the single release of the buffer, and what happens to the buffer when the exchange refuses
 * or fails the response.
 */
@DisplayName("Gateway responses hand kernel buffers to the engine")
class ResponseSitesTest {

    private static final byte[] UNAVAILABLE =
            "{\"error\":\"Service Unavailable\",\"circuit\":\"OPEN\"}".getBytes(StandardCharsets.UTF_8);
    private static final String ATTACK = "/api/v1/users?id=1%20union%20select%201,2";

    private static MemoryAllocator base;

    @BeforeAll
    static void open() {
        base = TestBuffers.newAllocator();
    }

    @AfterAll
    static void close() {
        base.close();
    }

    /** One way of producing a response, driven against an exchange. */
    private interface Site {
        String path();

        void drive(EngineExchange exchange);
    }

    private static void inScope(TrackingAllocator allocator, Runnable action) {
        ScopedValueRunner.run(allocator, action);
    }

    // ---- sites -----------------------------------------------------------------------------------------

    private static Site breakerRefusal() {
        CircuitBreakerRegistry registry = CircuitBreakers.newRegistry(CircuitBreakerConfig.builder()
                .slidingWindowSize(10).minimumNumberOfCalls(3).failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofMinutes(10)).permittedNumberOfCallsInHalfOpenState(2).build());
        RequestRouter router = RequestRouters.builder()
                .route("items").pathPrefix("/api/items").targetUri("http://upstream:9000").and().build();
        HttpHandler failing = ex -> ex.respond(HttpStatus.INTERNAL_SERVER_ERROR);
        CircuitBreakerIngress ingress = new CircuitBreakerIngress(failing, registry, router, Set.of("/health"));
        for (int i = 0; i < 3; i++) {
            ingress.handle(EngineExchange.http11("/api/items/x"));
        }
        CircuitBreaker breaker = registry.getOrCreate("items");
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        return new Site() {
            public String path() {
                return "/api/items/x";
            }

            public void drive(EngineExchange exchange) {
                ingress.handle(exchange);
            }
        };
    }

    private static Site wafThroughIngress() {
        GatewayCoreModule module = new GatewayCoreModule();
        module.policyChain().addPolicy(new WafGatewayPolicy(WafPolicies.defaultCoreRules(WafMode.BLOCK)));
        try {
            module.initialize();
            module.ready();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        IngressBootstrap ingress = module.ingressBootstrap();
        return new Site() {
            public String path() {
                return ATTACK;
            }

            public void drive(EngineExchange exchange) {
                ingress.handle(exchange);
            }
        };
    }

    private static Site health(boolean accepting) {
        GatewayCoreModule module = new GatewayCoreModule();
        IngressBootstrap ingress = module.ingressBootstrap();
        Endpoints.registerHealth(ingress, module.gatewayLifecycle());
        try {
            if (accepting) {
                module.initialize();
                module.ready();
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return new Site() {
            public String path() {
                return "/health";
            }

            public void drive(EngineExchange exchange) {
                ingress.handle(exchange);
            }
        };
    }

    private static Site management() {
        HttpHandler handler = Endpoints.management(null);
        return new Site() {
            public String path() {
                return Endpoints.ALLOCATIONS_PATH;
            }

            public void drive(EngineExchange exchange) {
                handler.handle(exchange);
            }
        };
    }

    // ---- bytes, headers, version, single release on success -----------------------------------------------

    @Test
    @DisplayName("Circuit refusal: the engine reads the constant body, once, with the request's version")
    void breakerRefusalSucceeds() {
        for (EngineExchange.Mode mode : new EngineExchange.Mode[] {EngineExchange.Mode.ACCEPT, EngineExchange.Mode.DEFER}) {
            Site site = breakerRefusal();
            TrackingAllocator allocator = new TrackingAllocator(base);
            EngineExchange ex = new EngineExchange(mode, HttpVersion.HTTP_2, site.path());

            inScope(allocator, () -> site.drive(ex));
            ex.flush();

            assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(ex.response().version()).isEqualTo(HttpVersion.HTTP_2);
            assertThat(ex.bytes()).isEqualTo(UNAVAILABLE);
            assertThat(ex.sizeAtRespond()).isEqualTo(UNAVAILABLE.length);
            assertThat(ex.header("Content-Type")).isEqualTo("application/json");
            assertThat(ex.header("X-Circuit-State")).isEqualTo("OPEN");
            assertThat(ex.header("Retry-After")).isNotNull();
            assertContentLengthAbsentOrExact(ex, UNAVAILABLE.length);
            assertReleasedOnce(allocator);
        }
    }

    @Test
    @DisplayName("WAF 403: the engine reads the JSON body the policy built, once, with the request's version")
    void wafSucceeds() {
        for (EngineExchange.Mode mode : new EngineExchange.Mode[] {EngineExchange.Mode.ACCEPT, EngineExchange.Mode.DEFER}) {
            Site site = wafThroughIngress();
            TrackingAllocator allocator = new TrackingAllocator(base);
            EngineExchange ex = new EngineExchange(mode, HttpVersion.HTTP_2, site.path());

            inScope(allocator, () -> site.drive(ex));
            ex.flush();

            assertThat(ex.status()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(ex.response().version()).isEqualTo(HttpVersion.HTTP_2);
            String body = new String(ex.bytes(), StandardCharsets.UTF_8);
            assertThat(body).startsWith("{\"error\":\"Forbidden\"").contains("\"category\":\"SQL_INJECTION\"");
            assertThat(ex.header("X-WAF-Blocked")).isEqualTo("true");
            assertContentLengthAbsentOrExact(ex, ex.bytes().length);
            assertReleasedOnce(allocator);
        }
    }

    @Test
    @DisplayName("Health: UP and DOWN bodies reach the engine byte for byte, with the request's version")
    void healthSucceeds() {
        for (boolean accepting : new boolean[] {true}) {
            Site site = health(accepting);
            TrackingAllocator allocator = new TrackingAllocator(base);
            EngineExchange ex = new EngineExchange(EngineExchange.Mode.ACCEPT, HttpVersion.HTTP_2, site.path());

            inScope(allocator, () -> site.drive(ex));

            String expected = accepting ? "{\"status\":\"UP\"}" : "{\"status\":\"DOWN\"}";
            assertThat(ex.status()).isEqualTo(accepting ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(ex.response().version()).isEqualTo(HttpVersion.HTTP_2);
            assertThat(new String(ex.bytes(), StandardCharsets.UTF_8)).isEqualTo(expected);
            assertContentLengthAbsentOrExact(ex, expected.length());
            assertReleasedOnce(allocator);
        }
    }

    @Test
    @DisplayName("Management: the allocations body reaches the engine and is released once")
    void managementSucceeds() {
        Site site = management();
        TrackingAllocator allocator = new TrackingAllocator(base);
        EngineExchange ex = new EngineExchange(EngineExchange.Mode.DEFER, HttpVersion.HTTP_2, site.path());

        inScope(allocator, () -> site.drive(ex));
        ex.flush();

        assertThat(ex.status()).isEqualTo(HttpStatus.OK);
        assertThat(ex.response().version()).isEqualTo(HttpVersion.HTTP_2);
        assertThat(new String(ex.bytes(), StandardCharsets.UTF_8)).startsWith("{\"allocatedBytes\":");
        assertContentLengthAbsentOrExact(ex, ex.bytes().length);
        assertReleasedOnce(allocator);
    }

    // ---- ownership: the caller leaves the buffer alone after a successful respond -----------------------------

    @Test
    @DisplayName("The buffer is alive with one reference at respond and stays alive until the engine releases it")
    void bufferOutlivesTheHandler() {
        Site[] sites = {breakerRefusal(), wafThroughIngress(), health(true), management()};
        for (Site site : sites) {
            TrackingAllocator allocator = new TrackingAllocator(base);
            EngineExchange ex = EngineExchange.http2(site.path());

            inScope(allocator, () -> site.drive(ex));

            assertThat(ex.refCountAtRespond()).as(site.path()).isEqualTo(1);
            assertThat(ex.holdsBody()).isTrue();
            assertThat(ex.held().isAlive()).as("alive after the handler returned: " + site.path()).isTrue();
            assertThat(ex.held().refCount()).isEqualTo(1);
            assertThat(allocator.last().closeCalls()).as("the caller never closes after respond").isZero();
            assertThat(allocator.outstanding()).isEqualTo(1);

            ex.flush();
            assertReleasedOnce(allocator);
        }
    }

    // ---- ownership: respond throws ---------------------------------------------------------------------------

    @Test
    @DisplayName("Circuit refusal: a respond rejected before the body is taken leaves nothing outstanding")
    void breakerRejectedRespond() {
        Site site = breakerRefusal();
        TrackingAllocator allocator = new TrackingAllocator(base);
        EngineExchange ex = new EngineExchange(EngineExchange.Mode.REJECT_BEFORE_TAKE, HttpVersion.HTTP_1_1, site.path());

        runIgnoringFailure(allocator, () -> site.drive(ex));

        assertNothingOutstandingAndNoRedundantClose(allocator);
    }

    @Test
    @DisplayName("Health: a respond rejected before the body is taken leaves nothing outstanding")
    void healthRejectedRespond() {
        Site site = health(true);
        TrackingAllocator allocator = new TrackingAllocator(base);
        EngineExchange ex = new EngineExchange(EngineExchange.Mode.REJECT_BEFORE_TAKE, HttpVersion.HTTP_1_1, site.path());

        runIgnoringFailure(allocator, () -> site.drive(ex));

        assertNothingOutstandingAndNoRedundantClose(allocator);
    }

    @Test
    @DisplayName("Management: a respond rejected before the body is taken leaves nothing outstanding")
    void managementRejectedRespond() {
        Site site = management();
        TrackingAllocator allocator = new TrackingAllocator(base);
        EngineExchange ex = new EngineExchange(EngineExchange.Mode.REJECT_BEFORE_TAKE, HttpVersion.HTTP_1_1, site.path());

        runIgnoringFailure(allocator, () -> site.drive(ex));

        assertNothingOutstandingAndNoRedundantClose(allocator);
    }

    @Test
    @DisplayName("WAF 403: a respond rejected before the body is taken leaves nothing outstanding")
    void wafRejectedRespond() {
        Site site = wafThroughIngress();
        TrackingAllocator allocator = new TrackingAllocator(base);
        EngineExchange ex = new EngineExchange(EngineExchange.Mode.REJECT_BEFORE_TAKE, HttpVersion.HTTP_1_1, site.path());

        runIgnoringFailure(allocator, () -> site.drive(ex));

        assertNothingOutstandingAndNoRedundantClose(allocator);
    }

    @Test
    @DisplayName("Circuit refusal: a write that fails after the engine released the body leaks nothing and closes nothing twice")
    void breakerFailAfterRelease() {
        Site site = breakerRefusal();
        TrackingAllocator allocator = new TrackingAllocator(base);
        EngineExchange ex = new EngineExchange(EngineExchange.Mode.FAIL_AFTER_RELEASE, HttpVersion.HTTP_1_1, site.path());

        runIgnoringFailure(allocator, () -> site.drive(ex));

        assertNothingOutstandingAndNoRedundantClose(allocator);
    }

    @Test
    @DisplayName("Health: a write that fails after the engine released the body leaks nothing and closes nothing twice")
    void healthFailAfterRelease() {
        Site site = health(true);
        TrackingAllocator allocator = new TrackingAllocator(base);
        EngineExchange ex = new EngineExchange(EngineExchange.Mode.FAIL_AFTER_RELEASE, HttpVersion.HTTP_1_1, site.path());

        runIgnoringFailure(allocator, () -> site.drive(ex));

        assertNothingOutstandingAndNoRedundantClose(allocator);
    }

    @Test
    @DisplayName("WAF 403: a write that fails after the engine released the body leaks nothing and closes nothing twice")
    void wafFailAfterRelease() {
        Site site = wafThroughIngress();
        TrackingAllocator allocator = new TrackingAllocator(base);
        EngineExchange ex = new EngineExchange(EngineExchange.Mode.FAIL_AFTER_RELEASE, HttpVersion.HTTP_1_1, site.path());

        runIgnoringFailure(allocator, () -> site.drive(ex));

        assertNothingOutstandingAndNoRedundantClose(allocator);
    }

    // ---- empty bodies and an unbound slot ----------------------------------------------------------------

    @Test
    @DisplayName("Without a bound allocator every site answers with its status and no body, and nothing fails")
    void unboundAllocator() {
        Site[] sites = {breakerRefusal(), wafThroughIngress(), health(true), health(false), management()};
        HttpStatus[] statuses = {HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.FORBIDDEN, HttpStatus.OK,
                HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.OK};
        for (int i = 0; i < sites.length; i++) {
            EngineExchange ex = EngineExchange.http11(sites[i].path());
            sites[i].drive(ex);
            assertThat(ex.status()).as(sites[i].path()).isEqualTo(statuses[i]);
            assertThat(ex.response().body()).isNull();
        }
    }

    @Test
    @DisplayName("A bodyless answer from the management router (404) allocates nothing")
    void notFoundAllocatesNothing() {
        TrackingAllocator allocator = new TrackingAllocator(base);
        EngineExchange ex = EngineExchange.http11("/nothing-here");

        inScope(allocator, () -> Endpoints.management(null).handle(ex));

        assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.response().body()).isNull();
        assertThat(allocator.allocations()).isZero();
    }

    @Test
    @DisplayName("The WAF policy's short-circuit response carries an allocator buffer with the exact body")
    void wafPolicyResponse() {
        WafGatewayPolicy policy = new WafGatewayPolicy(WafPolicies.defaultCoreRules(WafMode.BLOCK));
        TrackingAllocator allocator = new TrackingAllocator(base);
        EngineExchange ex = new EngineExchange(EngineExchange.Mode.ACCEPT, HttpVersion.HTTP_2, ATTACK);
        PolicyResult[] result = new PolicyResult[1];

        inScope(allocator, () -> result[0] = policy.preRouting(ex));

        HttpResponse response = result[0].response();
        assertThat(result[0].isShortCircuit()).isTrue();
        assertThat(response.version()).isEqualTo(HttpVersion.HTTP_2);
        assertThat(response.body()).isSameAs(allocator.last());
        assertThat(TestBuffers.read(response.body())).hasSize(Math.toIntExact(response.body().size()));
        assertThat(allocator.outstanding()).as("only the response body is out").isEqualTo(1);
        response.body().close();
        assertReleasedOnce(allocator);
    }

    // ---- constant bodies are not writable memory ----------------------------------------------------------

    @Test
    @DisplayName("Writing into a refusal's body does not change the next refusal")
    void constantBodyIsNotExposed() {
        Site site = breakerRefusal();
        TrackingAllocator allocator = new TrackingAllocator(base);
        EngineExchange first = EngineExchange.http2(site.path());
        inScope(allocator, () -> site.drive(first));
        first.held().segment().fill((byte) 0xFF);
        first.flush();

        EngineExchange second = EngineExchange.http11(site.path());
        inScope(allocator, () -> site.drive(second));

        assertThat(second.bytes()).isEqualTo(UNAVAILABLE);
        assertThat(allocator.outstanding()).isZero();
    }

    // ---- assertions -------------------------------------------------------------------------------------------

    private static void runIgnoringFailure(TrackingAllocator allocator, Runnable action) {
        try {
            inScope(allocator, action);
        } catch (RuntimeException expectedWhenTheEngineFails) {
            // whether the failure reaches the caller is the site's contract; the buffer's fate is not optional
        }
    }

    private static void assertReleasedOnce(TrackingAllocator allocator) {
        assertThat(allocator.allocations()).isGreaterThanOrEqualTo(1);
        assertThat(allocator.finalReleases()).isEqualTo(allocator.allocations());
        assertThat(allocator.outstanding()).isZero();
        allocator.buffers().forEach(b -> assertThat(b.closeCalls()).as("close calls on a handle").isEqualTo(1));
    }

    private static void assertNothingOutstandingAndNoRedundantClose(TrackingAllocator allocator) {
        assertThat(allocator.allocations()).isGreaterThanOrEqualTo(1);
        assertThat(allocator.outstanding()).as("buffers neither released nor handed on").isZero();
        assertThat(allocator.finalReleases()).isEqualTo(allocator.allocations());
        allocator.buffers().forEach(b -> assertThat(b.closeCalls()).as("close calls on a handle").isLessThanOrEqualTo(2));
    }

    private static void assertContentLengthAbsentOrExact(EngineExchange ex, int length) {
        String value = ex.header("Content-Length");
        assertThat(value == null || value.equals(String.valueOf(length))).as("Content-Length " + value).isTrue();
    }
}
