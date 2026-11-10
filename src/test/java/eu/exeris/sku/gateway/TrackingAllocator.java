package eu.exeris.sku.gateway;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps a real allocator and counts what is borrowed, how often each borrow is closed, and when the memory is
 * finally released. {@code close()} is idempotent on the kernel's buffers, so only the per-handle close count
 * shows a redundant close.
 */
final class TrackingAllocator implements MemoryAllocator {

    private final MemoryAllocator base;
    private final AtomicInteger allocations = new AtomicInteger();
    private final AtomicInteger finalReleases = new AtomicInteger();
    private final List<CountingBuffer> buffers = Collections.synchronizedList(new ArrayList<>());
    private final List<Integer> requested = Collections.synchronizedList(new ArrayList<>());

    TrackingAllocator(MemoryAllocator base) {
        this.base = base;
    }

    int allocations() {
        return allocations.get();
    }

    int finalReleases() {
        return finalReleases.get();
    }

    int outstanding() {
        return allocations.get() - finalReleases.get();
    }

    List<CountingBuffer> buffers() {
        synchronized (buffers) {
            return List.copyOf(buffers);
        }
    }

    List<Integer> requested() {
        synchronized (requested) {
            return List.copyOf(requested);
        }
    }

    /** The most recent borrow: the response body when the code under test borrows last. */
    CountingBuffer last() {
        List<CountingBuffer> all = buffers();
        if (all.isEmpty()) {
            throw new AssertionError("nothing was borrowed");
        }
        return all.get(all.size() - 1);
    }

    CountingBuffer only() {
        List<CountingBuffer> all = buffers();
        if (all.size() != 1) {
            throw new AssertionError("expected one buffer, got " + all.size());
        }
        return all.get(0);
    }

    @Override
    public LoanedBuffer allocateNetwork(int estimatedBytes) {
        requested.add(estimatedBytes);
        LoanedBuffer real = base.allocateNetwork(estimatedBytes);
        real.addCloseAction(finalReleases::incrementAndGet);
        allocations.incrementAndGet();
        CountingBuffer counting = new CountingBuffer(real);
        buffers.add(counting);
        return counting;
    }

    @Override
    public LoanedBuffer allocate(AllocationHint hint) {
        return allocateNetwork(hint.sizeBytes());
    }

    @Override
    public LoanedBuffer allocateCarrierSlab(int carrierIndex) {
        return base.allocateCarrierSlab(carrierIndex);
    }

    @Override
    public LoanedBuffer allocateInfrastructure(long sizeBytes) {
        return base.allocateInfrastructure(sizeBytes);
    }

    @Override
    public MemoryStats stats() {
        return base.stats();
    }

    @Override
    public void close() {
        // the wrapped allocator belongs to whoever created it
    }

    /** A buffer that counts every {@code close()} call made on this handle. */
    static final class CountingBuffer implements LoanedBuffer {
        private final LoanedBuffer real;
        private final AtomicInteger closeCalls = new AtomicInteger();

        CountingBuffer(LoanedBuffer real) {
            this.real = real;
        }

        int closeCalls() {
            return closeCalls.get();
        }

        @Override
        public MemorySegment segment() {
            return real.segment();
        }

        @Override
        public long size() {
            return real.size();
        }

        @Override
        public long capacity() {
            return real.capacity();
        }

        @Override
        public LoanedBuffer slice(long offset, long length) {
            return real.slice(offset, length);
        }

        @Override
        public LoanedBuffer view() {
            return real.view();
        }

        @Override
        public LoanedBuffer peek(long offset, long length) {
            return real.peek(offset, length);
        }

        @Override
        public void retain() {
            real.retain();
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            real.close();
        }

        @Override
        public int refCount() {
            return real.refCount();
        }

        @Override
        public void setSize(long newSize) {
            real.setSize(newSize);
        }

        @Override
        public boolean isAlive() {
            return real.isAlive();
        }

        @Override
        public void addCloseAction(Runnable action) {
            real.addCloseAction(action);
        }
    }
}
