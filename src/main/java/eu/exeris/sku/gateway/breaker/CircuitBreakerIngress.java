package eu.exeris.sku.gateway.breaker;

import eu.exeris.sku.gateway.paths.PathMatch;
import eu.exeris.caps.breaker.api.CallOutcome;
import eu.exeris.caps.breaker.api.CircuitBreaker;
import eu.exeris.caps.breaker.api.CircuitBreakerLimitExceededException;
import eu.exeris.caps.breaker.api.CircuitBreakerRegistry;
import eu.exeris.caps.breaker.api.Permits;
import eu.exeris.caps.routing.api.RequestRouter;
import eu.exeris.caps.routing.api.RouteLookup;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.sku.gateway.ByteArrayLoanedBuffer;
import eu.exeris.sku.gateway.routing.RouteLookupPool;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * Guards the routes with one circuit breaker each.
 *
 * <p>This is the whole of the SKU's use of the circuit-breaker capability, kept in one class: it asks the
 * breaker of the matched route for a permit, answers {@code 503} itself when the circuit refuses, and
 * otherwise reports the response the request ends with against that same permit. The permit is a primitive
 * {@code long} that lives in the call, not between calls, so a request that ends any way at all leaves
 * nothing behind.
 *
 * <p>The route is found with a {@link RouteLookup} borrowed from a {@link RouteLookupPool} for the match alone,
 * so matching allocates nothing in steady state and holds no lookup while the request is served. An admitted request allocates its guarded exchange; the outcome it reports is a
 * preallocated value for every status code.
 *
 * <p>A request that matches no route has no breaker: the number of breakers is bounded by the route table,
 * never by what clients send.
 */
public final class CircuitBreakerIngress implements HttpHandler {

    private static final byte[] SERVICE_UNAVAILABLE_BODY =
            "{\"error\":\"Service Unavailable\",\"circuit\":\"OPEN\"}".getBytes(StandardCharsets.UTF_8);

    private static final CallOutcome TRANSPORT_ERROR = CallOutcome.transportError();
    private static final int FIRST_STATUS = 100;
    private static final CallOutcome[] RESPONSE_OUTCOMES = new CallOutcome[500];

    static {
        for (int i = 0; i < RESPONSE_OUTCOMES.length; i++) {
            RESPONSE_OUTCOMES[i] = CallOutcome.response(FIRST_STATUS + i);
        }
    }

    private final HttpHandler delegate;
    private final CircuitBreakerRegistry registry;
    private final RequestRouter router;
    private final String[] excludedPaths;
    /** A lookup is not thread-safe: one is held for the match and given back before the request is served. */
    private final RouteLookupPool lookups;

    /**
     * Creates the guard.
     *
     * @param delegate       the handler to protect, normally the gateway's ingress
     * @param registry       the breakers
     * @param router         the route table that names the breaker of a request
     * @param excludedPaths  exact paths never guarded, such as health
     */
    public CircuitBreakerIngress(HttpHandler delegate, CircuitBreakerRegistry registry, RequestRouter router,
                                 Set<String> excludedPaths) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.router = Objects.requireNonNull(router, "router");
        this.excludedPaths = PathMatch.toArray(Set.copyOf(excludedPaths));
        this.lookups = new RouteLookupPool(router);
    }

    private static CallOutcome outcome(int statusCode) {
        int index = statusCode - FIRST_STATUS;
        return index >= 0 && index < RESPONSE_OUTCOMES.length
                ? RESPONSE_OUTCOMES[index]
                : CallOutcome.response(statusCode);
    }

    @Override
    public void handle(HttpExchange exchange) {
        HttpRequest request = exchange.request();
        String path = request.path();
        if (isExcluded(path)) {
            delegate.handle(exchange);
            return;
        }
        String routeId;
        RouteLookup lookup = lookups.acquire();
        try {
            routeId = router.match(request.method() != null ? request.method().name() : null, path, lookup)
                    ? lookup.route().id()
                    : null;
        } finally {
            lookups.release(lookup);
        }
        if (routeId == null) {
            delegate.handle(exchange);
            return;
        }

        CircuitBreaker breaker;
        try {
            breaker = registry.getOrCreate(routeId);
        } catch (CircuitBreakerLimitExceededException e) {
            delegate.handle(exchange);
            return;
        }
        long permit = breaker.acquirePermission();
        if (!Permits.permitted(permit)) {
            refuse(exchange, breaker);
            return;
        }
        GuardedExchange guarded = new GuardedExchange(exchange, breaker, permit, System.nanoTime());
        try {
            delegate.handle(guarded);
        } catch (RuntimeException | Error e) {
            guarded.report(TRANSPORT_ERROR);
            throw e;
        }
    }

    private boolean isExcluded(String path) {
        return PathMatch.matchesAny(path, excludedPaths);
    }

    private static void refuse(HttpExchange exchange, CircuitBreaker breaker) {
        long retryAfterSec = Math.max(1, breaker.config().waitDurationInOpenStateNanos() / 1_000_000_000L);
        List<HttpHeader> headers = List.of(
                new HttpHeader("Content-Type", "application/json"),
                new HttpHeader("Retry-After", String.valueOf(retryAfterSec)),
                new HttpHeader("X-Circuit-State", breaker.state().name()),
                new HttpHeader("Content-Length", String.valueOf(SERVICE_UNAVAILABLE_BODY.length)));
        HttpVersion version = exchange.request().version() != null ? exchange.request().version() : HttpVersion.HTTP_1_1;
        exchange.respond(new HttpResponse(HttpStatus.SERVICE_UNAVAILABLE, version, headers,
                new ByteArrayLoanedBuffer(SERVICE_UNAVAILABLE_BODY)));
    }

    private static final class GuardedExchange implements HttpExchange {
        private static final AtomicIntegerFieldUpdater<GuardedExchange> REPORTED =
                AtomicIntegerFieldUpdater.newUpdater(GuardedExchange.class, "reported");

        private final HttpExchange delegateExchange;
        private final CircuitBreaker breaker;
        private final long permit;
        private final long startNanos;
        private volatile int reported;

        GuardedExchange(HttpExchange delegateExchange, CircuitBreaker breaker, long permit, long startNanos) {
            this.delegateExchange = delegateExchange;
            this.breaker = breaker;
            this.permit = permit;
            this.startNanos = startNanos;
        }

        @Override
        public HttpRequest request() {
            return delegateExchange.request();
        }

        @Override
        public Map<String, String> pathParams() {
            return delegateExchange.pathParams();
        }

        @Override
        public void respond(HttpResponse response) {
            report(outcome(response.status().code()));
            delegateExchange.respond(response);
        }

        @Override
        public void respond(HttpTypedResponse response) {
            report(outcome(response.status().code()));
            delegateExchange.respond(response);
        }

        void report(CallOutcome outcome) {
            if (REPORTED.compareAndSet(this, 0, 1)) {
                breaker.onOutcome(permit, outcome, System.nanoTime() - startNanos);
            }
        }
    }
}
