package eu.exeris.sku.gateway;

import com.sun.management.ThreadMXBean;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A response body is copied into a kernel buffer, not into a heap array: the bytes allocated on the request
 * thread per response stay far below the body length. The borrow itself is taken from an allocator that
 * allocates nothing, so what is measured is what {@link KernelResponses} adds.
 */
@DisplayName("A response body is not allocated on the heap")
class ResponseBodyAllocationTest {

    private static final int BODY_LENGTH = 16 * 1024;
    private static final int WARMUP = 5_000;
    private static final int MEASURED = 10_000;
    private static final List<HttpHeader> HEADERS = List.of(new HttpHeader("Content-Type", "application/json"));

    @Test
    @DisplayName("Per-response heap allocation is a small fraction of the body length")
    void bodyIsNotCopiedToTheHeap() {
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threads.isThreadAllocatedMemorySupported()).isTrue();
        threads.setThreadAllocatedMemoryEnabled(true);
        byte[] body = new byte[BODY_LENGTH];
        RecyclingAllocator allocator = new RecyclingAllocator(2, BODY_LENGTH);

        long[] perResponse = new long[1];
        ScopedValueRunner.run(allocator, () -> {
            for (int i = 0; i < WARMUP; i++) {
                respond(body);
            }
            long threadId = Thread.currentThread().threadId();
            long before = threads.getThreadAllocatedBytes(threadId);
            for (int i = 0; i < MEASURED; i++) {
                respond(body);
            }
            perResponse[0] = (threads.getThreadAllocatedBytes(threadId) - before) / MEASURED;
        });

        assertThat(allocator.outstanding()).as("every borrow was returned").isZero();
        assertThat(allocator.borrows()).isEqualTo(WARMUP + MEASURED);
        assertThat(allocator.networkBorrows()).as("a body that fits a hint is not requested by size").isZero();
        assertThat(perResponse[0]).as("bytes allocated per response, body is " + BODY_LENGTH)
                .isLessThan(BODY_LENGTH / 8);
    }

    @Test
    @DisplayName("Per-response heap allocation does not grow with the body length")
    void heapAllocationIndependentOfBodyLength() {
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        threads.setThreadAllocatedMemoryEnabled(true);
        RecyclingAllocator allocator = new RecyclingAllocator(2, BODY_LENGTH);
        byte[] small = new byte[600];
        byte[] large = new byte[BODY_LENGTH];

        long[] perResponse = new long[2];
        ScopedValueRunner.run(allocator, () -> {
            byte[][] bodies = {small, large};
            for (int k = 0; k < 2; k++) {
                for (int i = 0; i < WARMUP; i++) {
                    respond(bodies[k]);
                }
                long threadId = Thread.currentThread().threadId();
                long before = threads.getThreadAllocatedBytes(threadId);
                for (int i = 0; i < MEASURED; i++) {
                    respond(bodies[k]);
                }
                perResponse[k] = (threads.getThreadAllocatedBytes(threadId) - before) / MEASURED;
            }
        });

        assertThat(allocator.networkBorrows()).isZero();
        assertThat(perResponse[1] - perResponse[0]).as("extra heap bytes per response for a body 27x longer")
                .isLessThan(256);
    }

    @Test
    @DisplayName("Control: copying the body into a fresh array is caught by the same measurement")
    void measurementSeesAHeapCopy() {
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        threads.setThreadAllocatedMemoryEnabled(true);
        byte[] body = new byte[BODY_LENGTH];
        byte[][] sink = new byte[1][];
        for (int i = 0; i < WARMUP; i++) {
            sink[0] = body.clone();
        }
        long threadId = Thread.currentThread().threadId();
        long before = threads.getThreadAllocatedBytes(threadId);
        for (int i = 0; i < MEASURED; i++) {
            sink[0] = body.clone();
        }
        long perResponse = (threads.getThreadAllocatedBytes(threadId) - before) / MEASURED;
        assertThat(perResponse).isGreaterThanOrEqualTo(BODY_LENGTH);
    }

    private static void respond(byte[] body) {
        HttpResponse response = KernelResponses.bytes(HttpStatus.OK, HttpVersion.HTTP_1_1, HEADERS, body);
        response.body().close();
    }
}
