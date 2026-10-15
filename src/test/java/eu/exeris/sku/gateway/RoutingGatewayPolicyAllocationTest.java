package eu.exeris.sku.gateway;

import eu.exeris.caps.breaker.api.CircuitBreakerConfig;
import eu.exeris.caps.breaker.api.CircuitBreakers;
import eu.exeris.caps.gatewaycore.api.PolicyResult;
import eu.exeris.caps.routing.api.RequestRouter;
import eu.exeris.caps.routing.api.RequestRouters;
import eu.exeris.caps.routing.api.RouteLookup;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor.Config;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor.Contract;
import eu.exeris.sku.gateway.breaker.CircuitBreakerIngress;
import eu.exeris.sku.gateway.routing.RoutingGatewayPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves, with the kernel allocation TCK's zero contract, that the route match allocates nothing in
 * {@link RoutingGatewayPolicy} on a matched path and in {@link CircuitBreakerIngress} on a path that matches no
 * route, each request on a new virtual thread as the kernel serves a connection; see
 * {@link VirtualThreadAllocation} for why the TCK's own {@code measure} cannot observe that. The claim is made for
 * the match in these two classes and for nothing else of the SKU: an admitted request still allocates.
 */
@DisplayName("Route matching allocation, one virtual thread per request")
class RoutingGatewayPolicyAllocationTest {

    private static final int WARMUP = 5_000;
    private static final int MEASURED = 10_000;

    private static final RequestRouter ROUTER = RequestRouters.builder()
            .route("orders").pathTemplate("/api/orders/{id}/lines/{line}").targetUri("http://orders:1").and()
            .route("rest").pathPrefix("/api").targetUri("http://rest:1").and()
            .build();

    private static Config config() {
        return new Config("RouteMatching", "RoutingGatewayPolicyAllocationTest", WARMUP, MEASURED, Contract.zero());
    }

    @Test
    @DisplayName("RoutingGatewayPolicy allocates nothing on a path that matches a template route")
    void policyOnMatchedRoute() throws Exception {
        RoutingGatewayPolicy policy = new RoutingGatewayPolicy(ROUTER, Set.of("/health"));
        TestExchange exchange = new TestExchange(HttpMethod.GET, "/api/orders/42/lines/7?expand=1", List.of());
        long[] proceeded = new long[1];
        Config config = config();
        JfrAllocationMonitor.Result result = VirtualThreadAllocation.measure(config, i -> {
            if (policy.preRouting(exchange) == PolicyResult.proceed()) {
                proceeded[0]++;
            }
        });
        assertThat(proceeded[0]).as("every request matched").isEqualTo(WARMUP + MEASURED);
        JfrAllocationMonitor.assertContract(config, result, "RoutingGatewayPolicy.preRouting, matched route");
    }

    @Test
    @DisplayName("CircuitBreakerIngress allocates nothing in deciding that a path matches no route")
    void ingressOnUnmatchedRoute() throws Exception {
        long[] delegated = new long[1];
        HttpHandler delegate = exchange -> delegated[0]++;
        CircuitBreakerIngress ingress = new CircuitBreakerIngress(delegate,
                CircuitBreakers.newRegistry(CircuitBreakerConfig.builder().build()), RequestRouters.builder()
                        .route("orders").pathPrefix("/orders").targetUri("http://orders:1").and().build(),
                Set.of("/health"));
        TestExchange exchange = new TestExchange(HttpMethod.GET, "/elsewhere/42?x=1", List.of());
        Config config = config();
        JfrAllocationMonitor.Result result = VirtualThreadAllocation.measure(config, i -> ingress.handle(exchange));
        assertThat(delegated[0]).as("every request went to the delegate").isEqualTo(WARMUP + MEASURED);
        JfrAllocationMonitor.assertContract(config, result, "CircuitBreakerIngress.handle, no route");
    }

    @Test
    @DisplayName("Control: a lookup kept per thread is caught on virtual threads")
    void threadLocalLookupIsCaught() throws Exception {
        ThreadLocal<RouteLookup> lookups = ThreadLocal.withInitial(ROUTER::newLookup);
        long[] matched = new long[1];
        Config config = config();
        JfrAllocationMonitor.Result result = VirtualThreadAllocation.measure(config, i -> {
            if (ROUTER.match("GET", "/api/orders/42/lines/7?expand=1", lookups.get())) {
                matched[0]++;
            }
        });
        assertThat(matched[0]).isEqualTo(WARMUP + MEASURED);
        assertThatThrownBy(() -> JfrAllocationMonitor.assertContract(config, result, "thread-local lookup"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("Control: the same thread-local lookup passes when every request runs on one platform thread, "
            + "which is the measurement that hid the cost")
    void threadLocalLookupPassesOnOnePlatformThread() throws IOException {
        ThreadLocal<RouteLookup> lookups = ThreadLocal.withInitial(ROUTER::newLookup);
        long[] matched = new long[1];
        Config config = config();
        JfrAllocationMonitor.Result result = JfrAllocationMonitor.measure(config, iterations -> {
            for (int i = 0; i < iterations; i++) {
                if (ROUTER.match("GET", "/api/orders/42/lines/7?expand=1", lookups.get())) {
                    matched[0]++;
                }
            }
        });
        JfrAllocationMonitor.assertContract(config, result, "thread-local lookup, one platform thread");
    }

    @Test
    @DisplayName("Control: the measurement fails for a handler that allocates per request")
    void measurementDetectsAllocation() throws Exception {
        Object[] sink = new Object[1];
        HttpHandler allocating = exchange -> sink[0] = new StringBuilder("q=").append(sink.length).toString();
        TestExchange exchange = new TestExchange(HttpMethod.GET, "/x", List.of());
        Config config = config();
        JfrAllocationMonitor.Result result = VirtualThreadAllocation.measure(config, i -> allocating.handle(exchange));
        assertThatThrownBy(() -> JfrAllocationMonitor.assertContract(config, result, "control"))
                .isInstanceOf(AssertionError.class);
    }
}
