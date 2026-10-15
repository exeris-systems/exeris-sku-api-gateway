package eu.exeris.sku.gateway.jwt;

import eu.exeris.sku.gateway.paths.PathMatch;
import eu.exeris.caps.gatewaycore.api.GatewayPolicy;
import eu.exeris.caps.gatewaycore.api.PolicyResult;
import eu.exeris.caps.jwt.api.JwtAdmissionPolicy;
import eu.exeris.caps.jwt.api.JwtValidationResult;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Adapter integrating {@link JwtAdmissionPolicy} into the Exeris Gateway {@link eu.exeris.caps.gatewaycore.api.PolicyChain}.
 *
 * <p>Executes during {@code preRouting} at order {@code -40} (after CORS -100 and Rate Limiting -50, before upstream forwarding).
 * If the JWT is absent or invalid, short-circuits with {@code 401 Unauthorized} and an RFC 6750 {@code WWW-Authenticate} header.
 *
 */
public final class JwtGatewayPolicy implements GatewayPolicy {

    private static final String POLICY_NAME = "jwt-validation-policy";
    private static final int POLICY_ORDER = -40;

    private final JwtAdmissionPolicy jwtAdmissionPolicy;
    private final String[] excludedExactPaths;
    private final List<String> excludedPrefixes;

    public JwtGatewayPolicy(JwtAdmissionPolicy jwtAdmissionPolicy) {
        this(jwtAdmissionPolicy, Set.of("/actuator/health", "/health"), List.of());
    }

    public JwtGatewayPolicy(
            JwtAdmissionPolicy jwtAdmissionPolicy,
            Set<String> excludedExactPaths,
            List<String> excludedPrefixes
    ) {
        this.jwtAdmissionPolicy = Objects.requireNonNull(jwtAdmissionPolicy, "jwtAdmissionPolicy");
        this.excludedExactPaths = PathMatch.toArray(excludedExactPaths);
        this.excludedPrefixes = excludedPrefixes != null ? excludedPrefixes : List.of();
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

        String authHeader = exchange.request().firstHeader("Authorization")
                .orElseGet(() -> exchange.request().firstHeader("authorization").orElse(null));

        JwtValidationResult result = jwtAdmissionPolicy.validate(authHeader);

        if (!result.isValid()) {
            List<HttpHeader> headers = new ArrayList<>(2);
            // The reason a token failed is not sent: it tells a caller which check to work around
            headers.add(new HttpHeader("WWW-Authenticate", "Bearer error=\"invalid_token\""));
            headers.add(new HttpHeader("Content-Length", "0"));

            HttpResponse response401 = new HttpResponse(
                    HttpStatus.UNAUTHORIZED,
                    exchange.request().version() != null ? exchange.request().version() : HttpVersion.HTTP_1_1,
                    headers,
                    null
            );
            return PolicyResult.shortCircuit(response401);
        }

        return PolicyResult.proceed();
    }

    private boolean isExcluded(String path) {
        if (path == null) {
            return false;
        }
        if (PathMatch.matchesAny(path, excludedExactPaths)) {
            return true;
        }
        for (String prefix : excludedPrefixes) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
