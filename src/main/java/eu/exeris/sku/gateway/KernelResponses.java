package eu.exeris.sku.gateway;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Objects;

/**
 * Builds responses whose body lives in a kernel buffer.
 *
 * <p>The body is copied into a buffer from the allocator the kernel binds around the request handler. A
 * buffer is the caller's until the response holding it has been handed to the exchange; from then on the
 * engine owns and releases it. {@link #bytes} therefore releases the buffer on every path that does not
 * return its response, and never touches it after returning.
 *
 * <p>A body that fits one of the size hints is written into a buffer from
 * {@link MemoryAllocator#allocate(AllocationHint)}, using the smallest hint that holds it. Only a body
 * larger than the largest hint falls back to {@link MemoryAllocator#allocateNetwork(int)}: the responses
 * built here include metrics and dashboard exports whose size grows with the number of routes and series
 * and has no fixed bound. A buffer's capacity may exceed the body; only {@code size()} bytes are sent, and
 * no byte of the buffer is assumed to be zero.
 *
 * <p>No {@code Content-Length} is set: the engine derives it from the buffer size. A body-less response
 * allocates nothing.
 */
public final class KernelResponses {

    /** Body-capable hints in ascending {@code sizeBytes()} order. */
    private static final AllocationHint[] BODY_HINTS = {
            AllocationHint.MICRO,
            AllocationHint.SMALL,
            AllocationHint.MEDIUM,
            AllocationHint.STREAMING_CHUNK,
            AllocationHint.NETWORK_FRAME,
            AllocationHint.JUMBO
    };

    private KernelResponses() {
    }

    /**
     * Returns the smallest hint whose {@link AllocationHint#sizeBytes()} is at least {@code length}, or
     * {@code null} when {@code length} exceeds the largest hint. {@code SESSION} is never chosen: it is
     * reserved for per-session context.
     *
     * @param length body length in bytes, not negative
     */
    static AllocationHint hintFor(int length) {
        for (AllocationHint hint : BODY_HINTS) {
            if (length <= hint.sizeBytes()) {
                return hint;
            }
        }
        return null;
    }

    /**
     * Builds a response carrying {@code body}.
     *
     * <p>When no allocator is bound, or {@code body} is empty, the response has no body.
     *
     * @param headers headers without {@code Content-Length}
     */
    public static HttpResponse bytes(HttpStatus status, HttpVersion version, List<HttpHeader> headers,
                                     byte[] body) {
        Objects.requireNonNull(body, "body");
        if (body.length == 0 || !KernelProviders.MEMORY_ALLOCATOR.isBound()) {
            return HttpResponse.noBody(status, version, headers);
        }
        MemoryAllocator allocator = KernelProviders.allocator();
        AllocationHint hint = hintFor(body.length);
        LoanedBuffer buffer = hint != null ? allocator.allocate(hint) : allocator.allocateNetwork(body.length);
        boolean built = false;
        try {
            if (buffer.capacity() < body.length) {
                throw new IllegalStateException("allocator returned capacity " + buffer.capacity()
                        + " for " + body.length + " bytes");
            }
            MemorySegment.copy(body, 0, buffer.segment(), ValueLayout.JAVA_BYTE, 0L, body.length);
            buffer.setSize(body.length);
            HttpResponse response = new HttpResponse(status, version, headers, buffer);
            built = true;
            return response;
        } finally {
            if (!built) {
                buffer.close();
            }
        }
    }

    /**
     * Builds a response carrying {@code body} and hands it to {@code exchange}.
     *
     * <p>Until {@code exchange.respond} returns normally the body is the caller's, so it is released when
     * {@code respond} throws; after a normal return the engine owns it and it is never touched again.
     * Closing is idempotent, so a body the engine already released stays released.
     *
     * @param headers headers without {@code Content-Length}
     */
    public static void respond(HttpExchange exchange, HttpStatus status, HttpVersion version,
                               List<HttpHeader> headers, byte[] body) {
        HttpResponse response = bytes(status, version, headers, body);
        boolean handedOver = false;
        try {
            exchange.respond(response);
            handedOver = true;
        } finally {
            if (!handedOver && response.body() != null) {
                response.body().close();
            }
        }
    }
}
