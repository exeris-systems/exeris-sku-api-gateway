package eu.exeris.sku.gateway.routing;

import eu.exeris.caps.gatewaycore.api.GatewayPolicy;
import eu.exeris.caps.gatewaycore.api.PolicyResult;
import eu.exeris.caps.routing.api.HeaderEntry;
import eu.exeris.caps.routing.api.RequestRouter;
import eu.exeris.caps.routing.api.RouteMatch;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Adapter integrating {@link RequestRouter} into the Exeris Gateway {@link eu.exeris.caps.gatewaycore.api.PolicyChain}.
 *
 * <p>Operates during {@code preRouting} at order {@code -10} (after CORS -100, Rate Limiting -75, and JWT -40).
 * Validates route existence via the compiled Radix Tree and provides fast 404 rejection on unknown routes.
 * In {@code postRouting}, applies response header transformations specified on the matched route.
 *
 */
public final class RoutingGatewayPolicy implements GatewayPolicy {

    private static final String POLICY_NAME = "request-routing-policy";
    private static final int POLICY_ORDER = -10;

    private final RequestRouter router;
    private final Set<String> excludedExactPaths;

    public RoutingGatewayPolicy(RequestRouter router) {
        this(router, Set.of("/actuator/health", "/health"));
    }

    public RoutingGatewayPolicy(RequestRouter router, Set<String> excludedExactPaths) {
        this.router = Objects.requireNonNull(router, "router");
        this.excludedExactPaths = excludedExactPaths != null ? excludedExactPaths : Set.of();
    }

    @Override
    public String name() {
        return POLICY_NAME;
    }

    @Override
    public int order() {
        return POLICY_ORDER;
    }

    @Override
    public PolicyResult preRouting(HttpExchange exchange) {
        String path = exchange.request().path();
        if (isExcluded(path)) {
            return PolicyResult.proceed();
        }

        String method = exchange.request().method() != null ? exchange.request().method().name() : null;
        RouteMatch match = router.match(method, path);

        if (!match.isMatched()) {
            return PolicyResult.shortCircuit(HttpStatus.NOT_FOUND);
        }

        return PolicyResult.proceed();
    }

    @Override
    public HttpResponse postRouting(HttpExchange exchange, HttpResponse response) {
        return response;
    }

    private boolean isExcluded(String path) {
        if (path == null) {
            return false;
        }
        int queryIdx = path.indexOf('?');
        String base = queryIdx < 0 ? path : path.substring(0, queryIdx);
        return excludedExactPaths.contains(base);
    }
}
