package eu.exeris.sku.gateway.observability;

import eu.exeris.sku.gateway.paths.PathMatch;
import eu.exeris.caps.observability.api.GatewayObservabilityService;
import eu.exeris.caps.observability.api.TraceContext;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpTypedResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wraps the gateway's ingress to measure every request end to end and to propagate W3C trace context.
 *
 * <p>It sits outside the policy chain, so a response a policy produces by short-circuiting (a rate limit,
 * a JWT rejection, a WAF block) is counted and traced exactly like one an upstream produced. Each request
 * is finished by the response it receives, and no state is kept between requests, so a request that is
 * refused early leaves nothing behind.
 *
 * <p>A request is recorded under its path alone: the query and fragment are the client's to choose, so keeping
 * them would let a client create as many distinct metric keys as it sends distinct queries.
 *
 * <p>Virtual-thread safe and stateless per ADR-007: nothing is held in a {@code ThreadLocal}.
 */
public final class ObservabilityIngress implements HttpHandler {

    private final HttpHandler delegate;
    private final GatewayObservabilityService observability;
    private final String[] excludedPaths;

    /**
     * Creates the wrapper.
     *
     * @param delegate       the handler to measure, normally the gateway's ingress
     * @param observability  the service that records requests
     * @param excludedPaths  exact paths not measured, such as the endpoints that serve the measurements
     */
    public ObservabilityIngress(HttpHandler delegate, GatewayObservabilityService observability,
                                Set<String> excludedPaths) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.observability = Objects.requireNonNull(observability, "observability");
        this.excludedPaths = PathMatch.toArray(Set.copyOf(Objects.requireNonNull(excludedPaths, "excludedPaths")));
    }

    @Override
    public void handle(HttpExchange exchange) {
        HttpRequest request = exchange.request();
        if (PathMatch.matchesAny(request.path(), excludedPaths)) {
            delegate.handle(exchange);
            return;
        }

        String incoming = request.firstHeader("traceparent")
                .or(() -> request.firstHeader("X-Trace-Id"))
                .orElse(null);
        TraceContext trace = observability.traceContextManager().extractOrCreate(incoming);
        ObservedExchange observed = new ObservedExchange(exchange, trace, System.nanoTime());
        observability.incrementActiveRequests();
        try {
            delegate.handle(observed);
        } catch (RuntimeException | Error e) {
            observed.finish(500);
            throw e;
        }
    }

    private final class ObservedExchange implements HttpExchange {
        private final HttpExchange delegateExchange;
        private final TraceContext trace;
        private final long startNanos;
        private final AtomicBoolean finished = new AtomicBoolean();

        ObservedExchange(HttpExchange delegateExchange, TraceContext trace, long startNanos) {
            this.delegateExchange = delegateExchange;
            this.trace = trace;
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
            Objects.requireNonNull(response, "response");
            finish(response.status().code());
            delegateExchange.respond(new HttpResponse(
                    response.status(), response.version(), withTrace(response.headers()), response.body()));
        }

        @Override
        public void respond(HttpTypedResponse response) {
            Objects.requireNonNull(response, "response");
            finish(response.status().code());
            delegateExchange.respond(new HttpTypedResponse(
                    response.status(), withTrace(response.headers()), response.payload()));
        }

        void finish(int statusCode) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            observability.decrementActiveRequests();
            HttpRequest request = request();
            String method = request.method() != null ? request.method().name() : "GET";
            observability.recordRequest(method, PathMatch.pathOf(request.path()), statusCode,
                    System.nanoTime() - startNanos, trace.traceId());
        }

        private List<HttpHeader> withTrace(List<HttpHeader> headers) {
            List<HttpHeader> result = new ArrayList<>(headers.size() + 2);
            for (HttpHeader header : headers) {
                if (!header.nameEqualsIgnoreCase("X-Trace-Id") && !header.nameEqualsIgnoreCase("traceparent")) {
                    result.add(header);
                }
            }
            result.add(new HttpHeader("X-Trace-Id", trace.traceId()));
            result.add(new HttpHeader("traceparent", trace.toTraceparent()));
            return result;
        }
    }
}
