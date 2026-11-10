package eu.exeris.sku.gateway;

import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProvider;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ServiceLoader;

/** Kernel-allocated buffers for tests, from the memory provider the kernel registers, without booting a kernel. */
final class TestBuffers {

    private static volatile MemoryAllocator shared;

    private TestBuffers() {
    }

    /** A new allocator with leak detection on; the caller closes it. */
    static MemoryAllocator newAllocator() {
        MemoryProvider provider = ServiceLoader.load(MemoryProvider.class).findFirst()
                .orElseThrow(() -> new IllegalStateException("no memory provider on the test classpath"));
        return provider.createAllocator(MemoryProviderConfig.defaults().withLeakDetection(LeakDetectionMode.PARANOID));
    }

    /** An allocator shared by the tests that only need buffers to hand around. */
    static MemoryAllocator shared() {
        MemoryAllocator allocator = shared;
        if (allocator == null) {
            synchronized (TestBuffers.class) {
                allocator = shared;
                if (allocator == null) {
                    allocator = newAllocator();
                    shared = allocator;
                }
            }
        }
        return allocator;
    }

    /** A buffer from the shared allocator holding {@code bytes}, sized to them. */
    static LoanedBuffer of(byte[] bytes) {
        return fill(shared(), bytes);
    }

    static LoanedBuffer fill(MemoryAllocator allocator, byte[] bytes) {
        LoanedBuffer buffer = allocator.allocateNetwork(bytes.length);
        MemorySegment.copy(bytes, 0, buffer.segment(), ValueLayout.JAVA_BYTE, 0L, bytes.length);
        buffer.setSize(bytes.length);
        return buffer;
    }

    /** The first {@code size()} bytes of the buffer. */
    static byte[] read(LoanedBuffer buffer) {
        byte[] out = new byte[Math.toIntExact(buffer.size())];
        MemorySegment.copy(buffer.segment(), ValueLayout.JAVA_BYTE, 0L, out, 0, out.length);
        return out;
    }
}
