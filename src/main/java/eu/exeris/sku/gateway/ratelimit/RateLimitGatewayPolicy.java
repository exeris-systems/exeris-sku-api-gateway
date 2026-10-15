package eu.exeris.sku.gateway.ratelimit;

import eu.exeris.caps.gatewaycore.api.GatewayPolicy;
import eu.exeris.caps.gatewaycore.api.PolicyResult;
import eu.exeris.caps.ratelimit.api.RateLimitDecision;
import eu.exeris.caps.ratelimit.api.RateLimitPolicy;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Adapter integrating {@link RateLimitPolicy} into the Exeris Gateway {@link eu.exeris.caps.gatewaycore.api.PolicyChain}.
 *
 * <p>Executes during {@code preRouting} at order {@code -50} (after CORS preflight, before upstream forwarding).
 * If rate limit is exceeded, short-circuits with {@code 429 Too Many Requests} and RFC 6585 rate limit headers.
 */
public final class RateLimitGatewayPolicy implements GatewayPolicy {

    private static final String POLICY_NAME = "rate-limit-policy";
    private static final int POLICY_ORDER = -50;
    private static final String SHARED_KEY = "shared";

    private final RateLimitPolicy rateLimitPolicy;
    private final Function<HttpExchange, CharSequence> keyExtractor;
    private final boolean includeHeadersOnAllowed;

    public RateLimitGatewayPolicy(RateLimitPolicy rateLimitPolicy) {
        this(rateLimitPolicy, sharedKeyExtractor(), false);
    }

    public RateLimitGatewayPolicy(
            RateLimitPolicy rateLimitPolicy,
            Function<HttpExchange, CharSequence> keyExtractor,
            boolean includeHeadersOnAllowed
    ) {
        this.rateLimitPolicy = Objects.requireNonNull(rateLimitPolicy, "rateLimitPolicy");
        this.keyExtractor = Objects.requireNonNull(keyExtractor, "keyExtractor");
        this.includeHeadersOnAllowed = includeHeadersOnAllowed;
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
        CharSequence key = keyExtractor.apply(exchange);
        RateLimitDecision decision = rateLimitPolicy.evaluate(key, 1L);

        if (!decision.allowed()) {
            List<HttpHeader> headers = new ArrayList<>(5);
            headers.add(new HttpHeader("Retry-After", String.valueOf(decision.retryAfterSeconds())));
            headers.add(new HttpHeader("X-RateLimit-Limit", String.valueOf(decision.limit())));
            headers.add(new HttpHeader("X-RateLimit-Remaining", "0"));
            headers.add(new HttpHeader("X-RateLimit-Reset", String.valueOf(decision.retryAfterSeconds())));
            headers.add(new HttpHeader("Content-Length", "0"));

            HttpResponse response429 = new HttpResponse(
                    HttpStatus.TOO_MANY_REQUESTS,
                    exchange.request().version() != null ? exchange.request().version() : HttpVersion.HTTP_1_1,
                    headers,
                    null
            );
            return PolicyResult.shortCircuit(response429);
        }

        return PolicyResult.proceed();
    }

    @Override
    public HttpResponse postRouting(HttpExchange exchange, HttpResponse response) {
        if (!includeHeadersOnAllowed) {
            return response;
        }

        // Optional decoration of response with RateLimit info
        CharSequence key = keyExtractor.apply(exchange);
        // Note: read-only/soft evaluate with cost 0
        RateLimitDecision decision = rateLimitPolicy.evaluate(key, 0L);

        List<HttpHeader> newHeaders = new ArrayList<>(response.headers().size() + 2);
        newHeaders.addAll(response.headers());
        newHeaders.add(new HttpHeader("X-RateLimit-Limit", String.valueOf(decision.limit())));
        newHeaders.add(new HttpHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining())));

        return new HttpResponse(
                response.status(),
                response.version(),
                newHeaders,
                response.body()
        );
    }

    /**
     * One shared limit for every request. This is the default because the kernel does not tell a handler
     * which address a request came from, and no request header can stand in for it: a client chooses
     * every header it sends.
     *
     * @return the extractor
     */
    public static Function<HttpExchange, CharSequence> sharedKeyExtractor() {
        return exchange -> SHARED_KEY;
    }

    /**
     * A limit per value of {@code header}. Use it only when a trusted proxy in front of the gateway sets
     * that header on every request and replaces any value the client sent: otherwise a client picks its
     * own bucket. A request without the header falls into the shared limit.
     *
     * @param header the header whose value keys the limit
     * @return the extractor
     */
    public static Function<HttpExchange, CharSequence> headerKeyExtractor(String header) {
        Objects.requireNonNull(header, "header");
        return exchange -> {
            Optional<String> value = exchange.request().firstHeader(header);
            return value.isPresent() && !value.get().isBlank() ? value.get().trim() : SHARED_KEY;
        };
    }
}
