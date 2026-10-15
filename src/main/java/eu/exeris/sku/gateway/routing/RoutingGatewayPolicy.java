package eu.exeris.sku.gateway.routing;

import eu.exeris.caps.gatewaycore.api.GatewayPolicy;
import eu.exeris.caps.gatewaycore.api.PolicyResult;
import eu.exeris.caps.routing.api.RequestRouter;
import eu.exeris.caps.routing.api.RouteLookup;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;

import java.util.Objects;
import java.util.Set;

/**
 * Adapter integrating {@link RequestRouter} into the Exeris Gateway {@link eu.exeris.caps.gatewaycore.api.PolicyChain}.
 *
 * <p>Operates during {@code preRouting} at order {@code -10}, after request size, CORS, rate limiting, the WAF
 * and JWT admission. It answers {@code 404} for a path no route matches, so an unknown route never reaches
 * the proxy. It reads the match from a {@link RouteLookup} borrowed from a {@link RouteLookupPool} for the
 * match alone, and keeps nothing of it: the proxy matches again to forward.
 */
public final class RoutingGatewayPolicy implements GatewayPolicy {

    private static final String POLICY_NAME = "request-routing-policy";
    private static final int POLICY_ORDER = -10;

    private final RequestRouter router;
    private final String[] excludedExactPaths;
    /** A lookup is not thread-safe: one is held for the match and given back before this method returns. */
    private final RouteLookupPool lookups;

    public RoutingGatewayPolicy(RequestRouter router) {
        this(router, Set.of("/actuator/health", "/health"));
    }

    public RoutingGatewayPolicy(RequestRouter router, Set<String> excludedExactPaths) {
        this.router = Objects.requireNonNull(router, "router");
        this.excludedExactPaths = excludedExactPaths != null ? excludedExactPaths.toArray(String[]::new) : new String[0];
        this.lookups = new RouteLookupPool(router);
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
        RouteLookup lookup = lookups.acquire();
        boolean matched;
        try {
            matched = router.match(method, path, lookup);
        } finally {
            lookups.release(lookup);
        }
        if (!matched) {
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
        // Compared in place: the path is not cut at its query, so nothing is allocated
        for (String excluded : excludedExactPaths) {
            if (path.startsWith(excluded)
                    && (path.length() == excluded.length() || path.charAt(excluded.length()) == '?')) {
                return true;
            }
        }
        return false;
    }
}
