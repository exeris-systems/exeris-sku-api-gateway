package eu.exeris.sku.gateway;

import eu.exeris.caps.gatewaycore.api.PolicyResult;
import eu.exeris.caps.routing.api.RequestRouter;
import eu.exeris.caps.routing.api.RequestRouters;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.tck.contract.AbstractSubsystemZeroAllocTck;
import eu.exeris.sku.gateway.routing.RoutingGatewayPolicy;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves, with the kernel allocation TCK, that the policy allocates nothing on a request whose path matches a
 * route: the claim is made for this class alone, and for no other class of the SKU.
 */
@DisplayName("RoutingGatewayPolicy: matched-route path zero-alloc TCK")
class RoutingGatewayPolicyAllocationTest extends AbstractSubsystemZeroAllocTck {

    private RoutingGatewayPolicy policy;
    private TestExchange exchange;

    @Override
    protected String subsystemName() {
        return "RoutingGatewayPolicy";
    }

    @Override
    protected String hotPathDescription() {
        return "preRouting on a path that matches a template route";
    }

    @Override
    protected boolean supportsZeroGcHotPath() {
        return true;
    }

    @Override
    protected void bootstrapSubsystem() {
        RequestRouter router = RequestRouters.builder()
                .route("orders").pathTemplate("/api/orders/{id}/lines/{line}").targetUri("http://orders:1").and()
                .route("rest").pathPrefix("/api").targetUri("http://rest:1").and()
                .build();
        policy = new RoutingGatewayPolicy(router, Set.of("/health"));
        exchange = new TestExchange(HttpMethod.GET, "/api/orders/42/lines/7?expand=1", List.of());
    }

    @Override
    protected void runSingleIteration() {
        if (policy.preRouting(exchange) != PolicyResult.proceed()) {
            throw new AssertionError("a matched route must proceed");
        }
    }

    @Override
    protected void tearDownSubsystem() {
        assertThat(policy.order()).isEqualTo(-10);
    }
}
