package eu.exeris.sku.gateway;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;

import java.lang.foreign.MemorySegment;

/** A test allocator that forwards everything; subclasses override what they want to bend. */
class MemoryAllocatorDelegate implements MemoryAllocator {

    private final MemoryAllocator target;

    MemoryAllocatorDelegate(MemoryAllocator target) {
        this.target = target;
    }

    @Override
    public LoanedBuffer allocateNetwork(int estimatedBytes) {
        return target.allocateNetwork(estimatedBytes);
    }

    @Override
    public LoanedBuffer allocate(AllocationHint hint) {
        return target.allocate(hint);
    }

    @Override
    public LoanedBuffer allocateCarrierSlab(int carrierIndex) {
        return target.allocateCarrierSlab(carrierIndex);
    }

    @Override
    public LoanedBuffer allocateInfrastructure(long sizeBytes) {
        return target.allocateInfrastructure(sizeBytes);
    }

    @Override
    public MemoryStats stats() {
        return target.stats();
    }

    @Override
    public void close() {
    }

    /** A buffer that reports a smaller capacity than the one behind it. */
    static final class CappedBuffer implements LoanedBuffer {
        private final LoanedBuffer real;
        private final long capacity;

        CappedBuffer(LoanedBuffer real, long capacity) {
            this.real = real;
            this.capacity = capacity;
        }

        @Override public MemorySegment segment() { return real.segment(); }
        @Override public long size() { return real.size(); }
        @Override public long capacity() { return capacity; }
        @Override public LoanedBuffer slice(long o, long l) { return real.slice(o, l); }
        @Override public LoanedBuffer view() { return real.view(); }
        @Override public LoanedBuffer peek(long o, long l) { return real.peek(o, l); }
        @Override public void retain() { real.retain(); }
        @Override public void close() { real.close(); }
        @Override public int refCount() { return real.refCount(); }
        @Override public void setSize(long n) { real.setSize(n); }
        @Override public boolean isAlive() { return real.isAlive(); }
        @Override public void addCloseAction(Runnable a) { real.addCloseAction(a); }
    }
}
