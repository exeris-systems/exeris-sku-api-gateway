package eu.exeris.sku.gateway.cors;

import eu.exeris.caps.corspolicy.api.CorsDecision;
import eu.exeris.caps.corspolicy.api.CorsPolicy;
import eu.exeris.caps.corspolicy.api.CorsRequest;
import eu.exeris.caps.gatewaycore.api.GatewayPolicy;
import eu.exeris.caps.gatewaycore.api.PolicyResult;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Adapter integrating {@link CorsPolicy} into the Exeris Gateway {@link eu.exeris.caps.gatewaycore.api.PolicyChain}.
 *
 * <p>Handles:
 * <ul>
 *   <li><b>Pre-routing:</b> Detects cross-origin preflight requests ({@code OPTIONS} carrying
 *       {@code Access-Control-Request-Method}) and short-circuits on the gateway edge with
 *       {@code 204 No Content} and standard CORS response headers when allowed, or {@code 403 Forbidden}
 *       when denied. Non-preflight requests from unauthorized origins are rejected early.</li>
 *   <li><b>Post-routing:</b> Appends CORS response headers ({@code Access-Control-Allow-Origin},
 *       {@code Vary: Origin}, etc.) to the upstream response before sending it downstream to the browser.</li>
 * </ul>
 */
public final class CorsGatewayPolicy implements GatewayPolicy {

    private static final String POLICY_NAME = "cors-policy";
    private static final int POLICY_ORDER = -100; // Early in pre-routing, late in post-routing

    private final CorsPolicy corsPolicy;

    public CorsGatewayPolicy(CorsPolicy corsPolicy) {
        this.corsPolicy = Objects.requireNonNull(corsPolicy, "corsPolicy");
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
        HttpRequest req = exchange.request();
        Optional<String> originOpt = req.firstHeader("Origin");
        if (originOpt.isEmpty() || originOpt.get().isBlank()) {
            return PolicyResult.proceed();
        }

        String origin = originOpt.get().trim();
        boolean isOptions = req.method() == HttpMethod.OPTIONS;
        Optional<String> reqMethodOpt = req.firstHeader("Access-Control-Request-Method");

        if (isOptions && reqMethodOpt.isPresent()) {
            // CORS Preflight request
            String targetMethod = reqMethodOpt.get().trim();
            List<String> requestedHeaders = req.firstHeader("Access-Control-Request-Headers")
                    .map(h -> Arrays.stream(h.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList())
                    .orElse(List.of());

            CorsRequest corsReq = CorsRequest.preflight(origin, targetMethod, requestedHeaders);
            CorsDecision decision = corsPolicy.evaluate(corsReq);

            if (!decision.allowed()) {
                return PolicyResult.shortCircuit(HttpStatus.FORBIDDEN);
            }

            List<HttpHeader> headers = new ArrayList<>();
            for (Map.Entry<String, String> entry : decision.headers().entrySet()) {
                headers.add(new HttpHeader(entry.getKey(), entry.getValue()));
            }
            headers.add(new HttpHeader("Content-Length", "0"));

            HttpResponse preflightResponse = new HttpResponse(
                    HttpStatus.NO_CONTENT,
                    HttpVersion.HTTP_1_1,
                    headers,
                    null
            );
            return PolicyResult.shortCircuit(preflightResponse);
        }

        // Simple or actual cross-origin request
        CorsRequest corsReq = CorsRequest.simple(origin, req.method().name());
        CorsDecision decision = corsPolicy.evaluate(corsReq);
        if (!decision.allowed()) {
            return PolicyResult.shortCircuit(HttpStatus.FORBIDDEN);
        }

        return PolicyResult.proceed();
    }

    @Override
    public HttpResponse postRouting(HttpExchange exchange, HttpResponse response) {
        Optional<String> originOpt = exchange.request().firstHeader("Origin");
        if (originOpt.isEmpty() || originOpt.get().isBlank()) {
            return response;
        }

        String origin = originOpt.get().trim();
        CorsRequest corsReq = CorsRequest.simple(origin, exchange.request().method().name());
        CorsDecision decision = corsPolicy.evaluate(corsReq);

        if (!decision.allowed()) {
            return response;
        }

        Map<String, String> corsHeaders = decision.headers();
        if (corsHeaders.isEmpty()) {
            return response;
        }

        List<HttpHeader> mergedHeaders = new ArrayList<>(response.headers().size() + corsHeaders.size());
        for (HttpHeader existing : response.headers()) {
            if (!corsHeaders.containsKey(existing.name())) {
                mergedHeaders.add(existing);
            }
        }
        for (Map.Entry<String, String> entry : corsHeaders.entrySet()) {
            mergedHeaders.add(new HttpHeader(entry.getKey(), entry.getValue()));
        }

        return new HttpResponse(
                response.status(),
                response.version(),
                mergedHeaders,
                response.body()
        );
    }
}
