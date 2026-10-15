package eu.exeris.sku.gateway.breaker;

import eu.exeris.caps.breaker.api.CallOutcome;
import eu.exeris.caps.breaker.api.CircuitBreaker;
import eu.exeris.caps.breaker.api.CircuitBreakerLimitExceededException;
import eu.exeris.caps.breaker.api.CircuitBreakerRegistry;
import eu.exeris.caps.breaker.api.Permit;
import eu.exeris.caps.routing.api.RequestRouter;
import eu.exeris.caps.routing.api.RouteMatch;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.sku.gateway.ByteArrayLoanedBuffer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Guards the routes with one circuit breaker each.
 *
 * <p>This is the whole of the SKU's use of the circuit-breaker capability, kept in one class: it asks the
 * breaker of the matched route for a {@link Permit}, answers {@code 503} itself when the circuit refuses,
 * and otherwise reports the response the request ends with against that same permit. The permit lives in
 * the call, not between calls, so a request that ends any way at all leaves nothing behind.
 *
 * <p>A request that matches no route has no breaker: the number of breakers is bounded by the route table,
 * never by what clients send.
 */
public final class CircuitBreakerIngress implements HttpHandler {

    private static final byte[] SERVICE_UNAVAILABLE_BODY =
            "{\"error\":\"Service Unavailable\",\"circuit\":\"OPEN\"}".getBytes(StandardCharsets.UTF_8);

    private final HttpHandler delegate;
    private final CircuitBreakerRegistry registry;
    private final RequestRouter router;
    private final Set<String> excludedPaths;

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
        this.excludedPaths = Set.copyOf(excludedPaths);
    }

    @Override
    public void handle(HttpExchange exchange) {
        HttpRequest request = exchange.request();
        String path = request.path();
        int query = path.indexOf('?');
        if (excludedPaths.contains(query < 0 ? path : path.substring(0, query))) {
            delegate.handle(exchange);
            return;
        }
        RouteMatch match = router.match(request.method() != null ? request.method().name() : null, path);
        if (!match.isMatched()) {
            delegate.handle(exchange);
            return;
        }

        CircuitBreaker breaker;
        try {
            breaker = registry.getOrCreate(match.route().id());
        } catch (CircuitBreakerLimitExceededException e) {
            delegate.handle(exchange);
            return;
        }
        Permit permit = breaker.acquirePermission();
        if (!permit.permitted()) {
            refuse(exchange, breaker);
            return;
        }
        GuardedExchange guarded = new GuardedExchange(exchange, breaker, permit, System.nanoTime());
        try {
            delegate.handle(guarded);
        } catch (RuntimeException | Error e) {
            guarded.report(CallOutcome.transportError());
            throw e;
        }
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
        private final HttpExchange delegateExchange;
        private final CircuitBreaker breaker;
        private final Permit permit;
        private final long startNanos;
        private final AtomicBoolean reported = new AtomicBoolean();

        GuardedExchange(HttpExchange delegateExchange, CircuitBreaker breaker, Permit permit, long startNanos) {
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
            report(CallOutcome.response(response.status().code()));
            delegateExchange.respond(response);
        }

        @Override
        public void respond(HttpTypedResponse response) {
            report(CallOutcome.response(response.status().code()));
            delegateExchange.respond(response);
        }

        void report(CallOutcome outcome) {
            if (reported.compareAndSet(false, true)) {
                breaker.onOutcome(permit, outcome, System.nanoTime() - startNanos);
            }
        }
    }
}
