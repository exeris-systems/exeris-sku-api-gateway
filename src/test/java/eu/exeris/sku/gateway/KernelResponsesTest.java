package eu.exeris.sku.gateway;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("KernelResponses: response bodies live in kernel buffers")
class KernelResponsesTest {

    private static MemoryAllocator base;

    @BeforeAll
    static void openAllocator() {
        base = TestBuffers.newAllocator();
    }

    @AfterAll
    static void closeAllocator() {
        base.close();
    }

    private static final List<HttpHeader> HEADERS = List.of(
            new HttpHeader("Content-Type", "application/json"),
            new HttpHeader("X-Probe", "1"));

    private static byte[] payload(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) ('a' + (i % 23));
        }
        return bytes;
    }

    static <T> T bound(MemoryAllocator allocator, java.util.function.Supplier<T> action) {
        AtomicReference<T> out = new AtomicReference<>();
        ScopedValueRunner.run(allocator, () -> out.set(action.get()));
        return out.get();
    }

    @ParameterizedTest(name = "{0} bytes")
    @ValueSource(ints = {1, 2, 51, 512, 4095, 4096, 4097, 16 * 1024, 70_000, 300_000})
    @DisplayName("The kernel receives exactly the encoded bytes, sized to them, from the allocator, and releases once")
    void bytesMatchAcrossSizes(int length) {
        TrackingAllocator allocator = new TrackingAllocator(base);
        byte[] body = payload(length);

        HttpResponse response = bound(allocator, () -> KernelResponses.bytes(HttpStatus.OK, HttpVersion.HTTP_1_1, HEADERS, body));

        assertThat(allocator.allocations()).isEqualTo(1);
        assertThat(allocator.requested()).containsExactly(length);
        assertThat(response.body()).isSameAs(allocator.only());
        assertThat(response.body().size()).isEqualTo(length);
        assertThat(response.body().capacity()).isGreaterThanOrEqualTo(length);
        assertThat(response.body().refCount()).isEqualTo(1);
        assertThat(TestBuffers.read(response.body())).isEqualTo(body);
        assertThat(allocator.outstanding()).as("the caller of bytes() has not released it: the engine does").isEqualTo(1);

        response.body().close();
        assertThat(allocator.outstanding()).isZero();
        assertThat(allocator.finalReleases()).isEqualTo(1);
        assertThat(allocator.only().closeCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("Status, version and headers are preserved and no Content-Length is set")
    void statusVersionAndHeaders() {
        TrackingAllocator allocator = new TrackingAllocator(base);
        for (HttpVersion version : HttpVersion.values()) {
            HttpResponse response = bound(allocator,
                    () -> KernelResponses.bytes(HttpStatus.SERVICE_UNAVAILABLE, version, HEADERS, payload(10)));
            assertThat(response.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.version()).isEqualTo(version);
            assertThat(response.headers()).containsExactlyElementsOf(HEADERS);
            assertThat(response.headers()).noneMatch(h -> h.nameEqualsIgnoreCase("Content-Length"));
            response.body().close();
        }
        assertThat(allocator.outstanding()).isZero();
    }

    @Test
    @DisplayName("Stale bytes in recycled memory never reach the response")
    void staleBytesAreNotSent() {
        TrackingAllocator allocator = new TrackingAllocator(base);
        for (int i = 0; i < 50; i++) {
            HttpResponse dirty = bound(allocator,
                    () -> KernelResponses.bytes(HttpStatus.OK, HttpVersion.HTTP_1_1, HEADERS, payload(256)));
            dirty.body().segment().fill((byte) 0xFF);
            dirty.body().close();
        }
        byte[] shorter = payload(100);
        HttpResponse response = bound(allocator,
                () -> KernelResponses.bytes(HttpStatus.OK, HttpVersion.HTTP_1_1, HEADERS, shorter));
        assertThat(response.body().size()).isEqualTo(100);
        assertThat(TestBuffers.read(response.body())).isEqualTo(shorter);
        response.body().close();
    }

    @Test
    @DisplayName("An empty body allocates nothing and yields a body-less response")
    void emptyBodyAllocatesNothing() {
        TrackingAllocator allocator = new TrackingAllocator(base);
        MemoryStats before = base.stats();

        HttpResponse response = bound(allocator,
                () -> KernelResponses.bytes(HttpStatus.NO_CONTENT, HttpVersion.HTTP_2, HEADERS, new byte[0]));

        assertThat(response.body()).isNull();
        assertThat(response.status()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.version()).isEqualTo(HttpVersion.HTTP_2);
        assertThat(allocator.allocations()).isZero();
        assertThat(base.stats().allocationCount() - before.allocationCount()).isZero();
    }

    @Test
    @DisplayName("Capacity strictly below the length is rejected and released once")
    void capacityBelowLengthFails() {
        // An allocator reporting a capacity under the request, whatever size classes the real one uses.
        TrackingAllocator real = new TrackingAllocator(base);
        MemoryAllocator tiny = new MemoryAllocatorDelegate(real) {
            @Override
            public eu.exeris.kernel.spi.memory.LoanedBuffer allocateNetwork(int estimatedBytes) {
                return new MemoryAllocatorDelegate.CappedBuffer(real.allocateNetwork(estimatedBytes), estimatedBytes - 1);
            }
        };
        assertThatThrownBy(() -> bound(tiny,
                () -> KernelResponses.bytes(HttpStatus.OK, HttpVersion.HTTP_1_1, HEADERS, payload(64))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(real.allocations()).isEqualTo(1);
        assertThat(real.outstanding()).isZero();
        assertThat(real.only().closeCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("A failure while building the response releases the buffer exactly once")
    void failureBuildingTheResponseReleases() {
        TrackingAllocator allocator = new TrackingAllocator(base);
        assertThatThrownBy(() -> bound(allocator,
                () -> KernelResponses.bytes(null, HttpVersion.HTTP_1_1, HEADERS, payload(64))))
                .isInstanceOf(RuntimeException.class);
        // The constructor may validate before or after the allocation; either way nothing stays out.
        assertThat(allocator.outstanding()).isZero();
        allocator.buffers().forEach(b -> assertThat(b.closeCalls()).isEqualTo(1));
    }

    @Test
    @DisplayName("Without a bound allocator the response keeps its status and has no body")
    void unboundAllocator() {
        HttpResponse response = KernelResponses.bytes(HttpStatus.FORBIDDEN, HttpVersion.HTTP_2, HEADERS, payload(32));
        assertThat(KernelProviders.MEMORY_ALLOCATOR.isBound()).isFalse();
        assertThat(response.body()).isNull();
        assertThat(response.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.version()).isEqualTo(HttpVersion.HTTP_2);
        assertThat(response.headers()).containsExactlyElementsOf(HEADERS);
    }

    @Test
    @DisplayName("A null body is refused")
    void nullBody() {
        assertThatThrownBy(() -> KernelResponses.bytes(HttpStatus.OK, HttpVersion.HTTP_1_1, HEADERS, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("A shared source array is never aliased by the response")
    void sourceArrayIsCopied() {
        TrackingAllocator allocator = new TrackingAllocator(base);
        byte[] shared = payload(40);
        byte[] original = shared.clone();
        HttpResponse response = bound(allocator,
                () -> KernelResponses.bytes(HttpStatus.OK, HttpVersion.HTTP_1_1, HEADERS, shared));

        response.body().segment().fill((byte) 0xFF);
        assertThat(shared).isEqualTo(original);
        shared[0] = 0x00;
        assertThat(response.body().segment().get(ValueLayout.JAVA_BYTE, 0)).isEqualTo((byte) 0xFF);
        response.body().close();
    }

    @Test
    @DisplayName("Under paranoid leak detection nothing leaks after many responses")
    void noLeaksAfterManyResponses() {
        MemoryAllocator own = TestBuffers.newAllocator();
        try {
            MemoryStats before = own.stats();
            List<byte[]> sizes = new ArrayList<>();
            for (int i = 1; i <= 300; i++) {
                sizes.add(payload(1 + (i * 37) % 9000));
            }
            for (byte[] body : sizes) {
                HttpResponse response = bound(own,
                        () -> KernelResponses.bytes(HttpStatus.OK, HttpVersion.HTTP_1_1, HEADERS, body));
                response.body().close();
            }
            System.gc();
            MemoryStats after = own.stats();
            assertThat(after.allocationCount() - before.allocationCount()).isEqualTo(300);
            assertThat(after.releaseCount() - before.releaseCount()).isEqualTo(300);
            assertThat(after.allocatedBytes()).isZero();
            assertThat(after.leakCount()).isZero();
        } finally {
            own.close();
        }
    }
}
