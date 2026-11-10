package eu.exeris.sku.gateway;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.util.List;

/**
 * An exchange that plays the engine's part in the body hand-over: it accepts one response, copies the bytes
 * the buffer holds and releases the buffer, either at once (HTTP/1.1) or when {@link #flush} is called after
 * the handler has returned (HTTP/2). It can also refuse the response before taking the body, or take and
 * release the body and then fail.
 */
final class EngineExchange implements HttpExchange {

    enum Mode {
        /** Takes the body and releases it before {@code respond} returns. */
        ACCEPT,
        /** Takes the body and releases it on {@link #flush}. */
        DEFER,
        /** Throws before touching the body. */
        REJECT_BEFORE_TAKE,
        /** Releases the body, then throws. */
        FAIL_AFTER_RELEASE
    }

    private final HttpRequest request;
    private final Mode mode;
    private HttpResponse response;
    private LoanedBuffer held;
    private int respondCalls;
    private byte[] bytes;
    private long sizeAtRespond = -1;
    private int refCountAtRespond = -1;

    EngineExchange(Mode mode, HttpVersion version, String path) {
        this.mode = mode;
        this.request = new HttpRequest(HttpMethod.GET, null, path, version, List.of(), null);
    }

    static EngineExchange http11(String path) {
        return new EngineExchange(Mode.ACCEPT, HttpVersion.HTTP_1_1, path);
    }

    static EngineExchange http2(String path) {
        return new EngineExchange(Mode.DEFER, HttpVersion.HTTP_2, path);
    }

    @Override
    public HttpRequest request() {
        return request;
    }

    @Override
    public void respond(HttpResponse r) {
        respondCalls++;
        if (response != null || mode == Mode.REJECT_BEFORE_TAKE) {
            throw new IllegalStateException("Already responded");
        }
        response = r;
        LoanedBuffer body = r.body();
        if (body != null) {
            sizeAtRespond = body.size();
            refCountAtRespond = body.refCount();
        }
        switch (mode) {
            case DEFER -> held = body;
            case ACCEPT -> release(body);
            case FAIL_AFTER_RELEASE -> {
                release(body);
                throw new IllegalStateException("write failed");
            }
            default -> throw new AssertionError(mode);
        }
    }

    @Override
    public void respond(HttpTypedResponse r) {
        throw new UnsupportedOperationException("typed responses are not played by this double");
    }

    /** Releases a body held back by {@link Mode#DEFER}. */
    void flush() {
        if (held != null) {
            LoanedBuffer body = held;
            held = null;
            release(body);
        }
    }

    private void release(LoanedBuffer body) {
        if (body == null) {
            return;
        }
        bytes = TestBuffers.read(body);
        body.close();
    }

    /** True while a deferred body has not yet been released. */
    boolean holdsBody() {
        return held != null;
    }

    LoanedBuffer held() {
        return held;
    }

    HttpResponse response() {
        return response;
    }

    int respondCalls() {
        return respondCalls;
    }

    HttpStatus status() {
        return response.status();
    }

    /** The bytes the engine read from the body; null when it had none. */
    byte[] bytes() {
        return bytes;
    }

    long sizeAtRespond() {
        return sizeAtRespond;
    }

    int refCountAtRespond() {
        return refCountAtRespond;
    }

    String header(String name) {
        return response.headers().stream().filter(h -> h.nameEqualsIgnoreCase(name))
                .map(HttpHeader::value).findFirst().orElse(null);
    }
}
