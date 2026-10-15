package eu.exeris.sku.gateway.limits;

import eu.exeris.caps.gatewaycore.api.GatewayPolicy;
import eu.exeris.caps.gatewaycore.api.PolicyResult;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpStatus;

import java.util.Optional;

/**
 * Refuses a request whose body is larger than the configured limit with {@code 413 Content Too Large}
 * before anything else looks at it and before it can be forwarded.
 *
 * <p>The kernel's HTTP/1.1 server hands a handler a request whose body it has already read, and applies its
 * own body limit to HTTP/2 only. This policy is what bounds the size of a request the gateway forwards,
 * whichever protocol carried it. It judges the declared {@code Content-Length} and the body actually held,
 * so a declared length that understates the body does not get past it.
 */
public final class RequestSizeGatewayPolicy implements GatewayPolicy {

    private static final String POLICY_NAME = "request-size-policy";

    /** Position in the policy chain: ahead of every other policy. */
    public static final int ORDER = -200;

    private final long maxBodyBytes;

    /**
     * Creates the policy.
     *
     * @param maxBodyBytes largest body, in bytes, a request may carry
     */
    public RequestSizeGatewayPolicy(long maxBodyBytes) {
        if (maxBodyBytes < 1) {
            throw new IllegalArgumentException("maxBodyBytes must be positive");
        }
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    public String name() {
        return POLICY_NAME;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public PolicyResult preRouting(HttpExchange exchange) {
        HttpRequest request = exchange.request();
        if (declaredLength(request) > maxBodyBytes || (request.hasBody() && request.body().size() > maxBodyBytes)) {
            return PolicyResult.shortCircuit(HttpStatus.CONTENT_TOO_LARGE);
        }
        return PolicyResult.proceed();
    }

    private static long declaredLength(HttpRequest request) {
        Optional<String> value = request.firstHeader("Content-Length");
        if (value.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(value.get().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
