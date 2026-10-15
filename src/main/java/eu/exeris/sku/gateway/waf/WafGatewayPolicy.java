package eu.exeris.sku.gateway.waf;

import eu.exeris.caps.gatewaycore.api.GatewayPolicy;
import eu.exeris.caps.gatewaycore.api.PolicyResult;
import eu.exeris.caps.waf.api.WafDecision;
import eu.exeris.caps.waf.api.WafHeader;
import eu.exeris.caps.waf.api.WafPolicy;
import eu.exeris.caps.waf.api.WafRequest;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.sku.gateway.ByteArrayLoanedBuffer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Refuses a request the {@link WafPolicy} blocks with {@code 403 Forbidden}, before JWT admission runs.
 * It sits in the Exeris Gateway {@link eu.exeris.caps.gatewaycore.api.PolicyChain}.
 *
 * <p>Executes during {@code preRouting} at order {@code -45}, after rate limiting and before JWT admission,
 * so a request the WAF blocks costs no signature verification. A request that is not excluded is copied into
 * a {@link WafRequest} for the policy, which allocates; this class makes no allocation claim.
 *
 * <p>A decision that detects without blocking (a policy in {@code DETECT} mode) lets the request through
 * and is logged with its category and rule id; the matched text is not logged because the client wrote it.
 */
public final class WafGatewayPolicy implements GatewayPolicy {

    private static final System.Logger LOG = System.getLogger(WafGatewayPolicy.class.getName());

    private static final String POLICY_NAME = "waf-policy";
    /** Position in the policy chain: after rate limiting, before JWT admission. */
    public static final int DEFAULT_ORDER = -45;

    private final WafPolicy wafPolicy;
    private final Set<String> excludedPaths;
    private final int order;

    public WafGatewayPolicy(WafPolicy wafPolicy) {
        this(wafPolicy, Set.of("/health", "/actuator/health"), DEFAULT_ORDER);
    }

    public WafGatewayPolicy(WafPolicy wafPolicy, Set<String> excludedPaths, int order) {
        this.wafPolicy = Objects.requireNonNull(wafPolicy, "wafPolicy");
        this.excludedPaths = Objects.requireNonNull(excludedPaths, "excludedPaths");
        this.order = order;
    }

    @Override
    public String name() {
        return POLICY_NAME;
    }

    @Override
    public int order() {
        return order;
    }

    @Override
    public PolicyResult preRouting(HttpExchange exchange) {
        HttpRequest req = exchange.request();
        String rawUri = req.path();
        if (rawUri == null || excludedPaths.contains(rawUri)) {
            return PolicyResult.proceed();
        }

        // Split path and query
        int qIdx = rawUri.indexOf('?');
        String path = (qIdx >= 0) ? rawUri.substring(0, qIdx) : rawUri;
        String query = (qIdx >= 0 && qIdx + 1 < rawUri.length()) ? rawUri.substring(qIdx + 1) : "";

        if (excludedPaths.contains(path)) {
            return PolicyResult.proceed();
        }

        // Convert headers efficiently
        List<HttpHeader> reqHeaders = req.headers();
        List<WafHeader> wafHeaders;
        if (reqHeaders == null || reqHeaders.isEmpty()) {
            wafHeaders = List.of();
        } else {
            wafHeaders = new ArrayList<>(reqHeaders.size());
            for (int i = 0; i < reqHeaders.size(); i++) {
                HttpHeader h = reqHeaders.get(i);
                wafHeaders.add(new WafHeader(h.name(), h.value()));
            }
        }

        WafRequest wafReq = new WafRequest(
                req.method() != null ? req.method().name() : "GET",
                rawUri,
                path,
                query,
                wafHeaders
        );

        WafDecision decision = wafPolicy.evaluate(wafReq);
        if (decision.isDetected()) {
            LOG.log(System.Logger.Level.WARNING, "WAF detected {0} (rule {1}) on {2} {3}; request allowed",
                    decision.category(), decision.ruleId(), wafReq.method(), path);
        }
        if (!decision.isAllowed()) {
            String category = (decision.category() != null) ? decision.category().name() : "THREAT_DETECTED";
            String ruleId = (decision.ruleId() != null) ? decision.ruleId() : "WAF-000";
            byte[] bodyBytes = ("{\"error\":\"Forbidden\",\"message\":\"Request blocked by WAF\",\"category\":\""
                    + category + "\",\"ruleId\":\"" + ruleId + "\"}").getBytes(StandardCharsets.UTF_8);

            List<HttpHeader> respHeaders = List.of(
                    new HttpHeader("Content-Type", "application/json"),
                    new HttpHeader("X-WAF-Blocked", "true"),
                    new HttpHeader("X-WAF-Category", category),
                    new HttpHeader("X-WAF-Rule", ruleId),
                    new HttpHeader("Content-Length", String.valueOf(bodyBytes.length))
            );

            HttpResponse response403 = new HttpResponse(
                    HttpStatus.FORBIDDEN,
                    req.version() != null ? req.version() : HttpVersion.HTTP_1_1,
                    respHeaders,
                    new ByteArrayLoanedBuffer(bodyBytes)
            );
            return PolicyResult.shortCircuit(response403);
        }

        return PolicyResult.proceed();
    }

    @Override
    public HttpResponse postRouting(HttpExchange exchange, HttpResponse response) {
        return response;
    }
}
