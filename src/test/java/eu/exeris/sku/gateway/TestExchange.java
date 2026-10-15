package eu.exeris.sku.gateway;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** A hand-written exchange that records the one response it is given. */
final class TestExchange implements HttpExchange {

    final HttpRequest request;
    final AtomicReference<HttpResponse> response = new AtomicReference<>();

    TestExchange(HttpMethod method, String path, List<HttpHeader> headers) {
        this(method, path, headers, null);
    }

    TestExchange(HttpMethod method, String path, List<HttpHeader> headers, LoanedBuffer body) {
        this.request = new HttpRequest(method, null, path, HttpVersion.HTTP_1_1, headers, body);
    }

    static TestExchange get(String path, HttpHeader... headers) {
        return new TestExchange(HttpMethod.GET, path, List.of(headers));
    }

    @Override
    public HttpRequest request() {
        return request;
    }

    @Override
    public void respond(HttpResponse r) {
        if (!response.compareAndSet(null, r)) {
            throw new IllegalStateException("Already responded");
        }
    }

    @Override
    public void respond(HttpTypedResponse r) {
        respond(new HttpResponse(r.status(), HttpVersion.HTTP_1_1, r.headers(), null));
    }

    int status() {
        return response.get().status().code();
    }

    boolean hasHeader(String name) {
        return response.get().headers().stream().anyMatch(h -> h.nameEqualsIgnoreCase(name));
    }
}
