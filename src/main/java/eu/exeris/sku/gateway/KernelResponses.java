package eu.exeris.sku.gateway;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
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
 * <p>No {@code Content-Length} is set: the engine derives it from the buffer size. A body-less response
 * allocates nothing.
 */
public final class KernelResponses {

    private KernelResponses() {
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
        LoanedBuffer buffer = allocator.allocateNetwork(body.length);
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
}
