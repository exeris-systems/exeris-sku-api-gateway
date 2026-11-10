package eu.exeris.sku.gateway;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

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

    /** The largest body that fits a hint; larger bodies are outside the hint range. */
    private static final int LARGEST_HINT = AllocationHint.JUMBO.sizeBytes();

    /** The hints a body may be written into, smallest first. SESSION is for per-session context only. */
    private static final List<AllocationHint> BODY_HINTS = Arrays.stream(AllocationHint.values())
            .filter(h -> h != AllocationHint.SESSION)
            .sorted(Comparator.comparingInt(AllocationHint::sizeBytes))
            .toList();

    /** The smallest body hint of at least {@code length} bytes, computed independently of the code under test. */
    private static AllocationHint expectedHint(int length) {
        return BODY_HINTS.stream().filter(h -> h.sizeBytes() >= length).findFirst().orElse(null);
    }

    private static TrackingAllocator strictDirty() {
        return new TrackingAllocator(base, (byte) 0x5A, LARGEST_HINT);
    }

    static IntStream boundaryLengths() {
        IntStream.Builder lengths = IntStream.builder();
        for (AllocationHint hint : BODY_HINTS) {
            int size = hint.sizeBytes();
            lengths.add(size - 1).add(size).add(size + 1);
        }
        return lengths.add(1).add(2).add(51).add(300_000).build().distinct().sorted();
    }

    @Test
    @DisplayName("The hint ladder is MICRO 512, SMALL 4K, MEDIUM 16K, STREAMING_CHUNK 32K, NETWORK_FRAME 64K, JUMBO 128K")
    void hintLadder() {
        assertThat(BODY_HINTS).containsExactly(AllocationHint.MICRO, AllocationHint.SMALL, AllocationHint.MEDIUM,
                AllocationHint.STREAMING_CHUNK, AllocationHint.NETWORK_FRAME, AllocationHint.JUMBO);
        assertThat(LARGEST_HINT).isEqualTo(128 * 1024);
    }

    @ParameterizedTest(name = "{0} bytes")
    @MethodSource("boundaryLengths")
    @DisplayName("A body is written into a buffer from allocate(hint) with the smallest hint that holds it, sized to the body")
    void bytesMatchAcrossSizes(int length) {
        TrackingAllocator allocator = strictDirty();
        byte[] body = payload(length);

        HttpResponse response = bound(allocator, () -> KernelResponses.bytes(HttpStatus.OK, HttpVersion.HTTP_1_1, HEADERS, body));

        assertThat(allocator.allocations()).isEqualTo(1);
        AllocationHint expected = expectedHint(length);
        if (expected != null) {
            assertThat(allocator.hints()).containsExactly(expected);
            assertThat(allocator.requested()).as("no byte size passed to the allocator").isEmpty();
            assertThat(response.body().capacity()).isGreaterThanOrEqualTo(expected.sizeBytes());
        } else {
            assertThat(allocator.hints()).isEmpty();
            assertThat(allocator.requested()).containsExactly(length);
        }
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

    @ParameterizedTest(name = "{0} bytes")
    @MethodSource("boundaryLengths")
    @DisplayName("Only the body is written: the rest of the capacity keeps whatever it held, nothing assumes zeroed memory")
    void bodyIsWrittenOverNonZeroMemory(int length) {
        TrackingAllocator allocator = strictDirty();
        byte[] body = payload(length);

        HttpResponse response = bound(allocator, () -> KernelResponses.bytes(HttpStatus.OK, HttpVersion.HTTP_1_1, HEADERS, body));

        assertThat(TestBuffers.read(response.body())).isEqualTo(body);
        long capacity = response.body().capacity();
        if (length <= LARGEST_HINT) {
            assertThat(capacity).as("a buffer with room beyond the body, or exactly at a boundary")
                    .isGreaterThanOrEqualTo(length);
        }
        for (long i = length; i < capacity; i++) {
            if (response.body().segment().get(ValueLayout.JAVA_BYTE, i) != (byte) 0x5A) {
                throw new AssertionError("byte " + i + " beyond the body was overwritten (length " + length + ")");
            }
        }
        response.body().close();
        assertThat(allocator.outstanding()).isZero();
    }

    @Test
    @DisplayName("A body shorter than its hint leaves the capacity larger than the size")
    void capacityExceedsSizeBelowTheHint() {
        TrackingAllocator allocator = strictDirty();
        HttpResponse response = bound(allocator,
                () -> KernelResponses.bytes(HttpStatus.OK, HttpVersion.HTTP_1_1, HEADERS, payload(100)));
        assertThat(allocator.hints()).containsExactly(AllocationHint.MICRO);
        assertThat(response.body().size()).isEqualTo(100);
        assertThat(response.body().capacity()).isGreaterThan(100);
        response.body().close();
    }

    @Test
    @DisplayName("The hint is chosen by a pure function: smallest body hint of at least the length, over the whole range")
    void hintChoiceOverTheWholeRange() {
        for (int length = 0; length <= LARGEST_HINT; length++) {
            AllocationHint hint = KernelResponses.hintFor(length);
            if (hint != expectedHint(length)) {
                throw new AssertionError("length " + length + ": " + hint + ", expected " + expectedHint(length));
            }
        }
        assertThat(KernelResponses.hintFor(LARGEST_HINT + 1)).isNull();
        assertThat(KernelResponses.hintFor(Integer.MAX_VALUE)).isNull();
        for (int length : new int[] {1, 511, 4095, 16_383, 32_767, 65_535, 131_071}) {
            assertThat(KernelResponses.hintFor(length)).isNotEqualTo(AllocationHint.SESSION);
        }
    }

    @Test
    @DisplayName("Boundary pairs: 512/513, 4096/4097, 16K/16K+1, 32K/32K+1, 64K/64K+1, 128K/128K+1")
    void boundaryPairs() {
        assertThat(KernelResponses.hintFor(512)).isEqualTo(AllocationHint.MICRO);
        assertThat(KernelResponses.hintFor(513)).isEqualTo(AllocationHint.SMALL);
        assertThat(KernelResponses.hintFor(4096)).isEqualTo(AllocationHint.SMALL);
        assertThat(KernelResponses.hintFor(4097)).isEqualTo(AllocationHint.MEDIUM);
        assertThat(KernelResponses.hintFor(16 * 1024)).isEqualTo(AllocationHint.MEDIUM);
        assertThat(KernelResponses.hintFor(16 * 1024 + 1)).isEqualTo(AllocationHint.STREAMING_CHUNK);
        assertThat(KernelResponses.hintFor(32 * 1024)).isEqualTo(AllocationHint.STREAMING_CHUNK);
        assertThat(KernelResponses.hintFor(32 * 1024 + 1)).isEqualTo(AllocationHint.NETWORK_FRAME);
        assertThat(KernelResponses.hintFor(64 * 1024)).isEqualTo(AllocationHint.NETWORK_FRAME);
        assertThat(KernelResponses.hintFor(64 * 1024 + 1)).isEqualTo(AllocationHint.JUMBO);
        assertThat(KernelResponses.hintFor(128 * 1024)).isEqualTo(AllocationHint.JUMBO);
        assertThat(KernelResponses.hintFor(128 * 1024 + 1)).isNull();
    }

    @Test
    @DisplayName("A body larger than the largest hint is never truncated: the whole body is sent, from allocateNetwork")
    void bodyBeyondTheLargestHintIsComplete() {
        TrackingAllocator allocator = new TrackingAllocator(base, (byte) 0x5A, LARGEST_HINT);
        byte[] body = payload(LARGEST_HINT + 1);

        HttpResponse response = bound(allocator, () -> KernelResponses.bytes(HttpStatus.OK, HttpVersion.HTTP_1_1, HEADERS, body));

        assertThat(response.body().size()).isEqualTo(body.length);
        assertThat(TestBuffers.read(response.body())).isEqualTo(body);
        assertThat(allocator.hints()).isEmpty();
        response.body().close();
        assertThat(allocator.outstanding()).isZero();
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

            @Override
            public eu.exeris.kernel.spi.memory.LoanedBuffer allocate(AllocationHint hint) {
                return new MemoryAllocatorDelegate.CappedBuffer(real.allocate(hint), 63);
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
    @DisplayName("Beyond the largest hint, capacity below the length is rejected and released once")
    void networkCapacityBelowLengthFails() {
        TrackingAllocator real = new TrackingAllocator(base);
        MemoryAllocator tiny = new MemoryAllocatorDelegate(real) {
            @Override
            public eu.exeris.kernel.spi.memory.LoanedBuffer allocateNetwork(int estimatedBytes) {
                return new MemoryAllocatorDelegate.CappedBuffer(real.allocateNetwork(estimatedBytes), estimatedBytes - 1);
            }
        };
        assertThatThrownBy(() -> bound(tiny,
                () -> KernelResponses.bytes(HttpStatus.OK, HttpVersion.HTTP_1_1, HEADERS, payload(LARGEST_HINT + 10))))
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
