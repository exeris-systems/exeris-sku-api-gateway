package eu.exeris.sku.gateway;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A {@link MemoryAllocator} that hands out a fixed set of buffers built up front and takes them back on
 * {@code close()}, so that borrowing from it allocates nothing. It stands in for a pooled allocator whose
 * borrow costs no heap object, to show that the code under test adds none of its own; it counts what is
 * out so that a test can show every borrow was returned.
 */
final class RecyclingAllocator implements MemoryAllocator {

    private final AtomicReferenceArray<Buffer> free;
    private final AtomicInteger outstanding = new AtomicInteger();
    private final AtomicInteger borrows = new AtomicInteger();
    private final int capacity;

    RecyclingAllocator(int buffers, int capacity) {
        this.free = new AtomicReferenceArray<>(buffers);
        this.capacity = capacity;
        for (int i = 0; i < buffers; i++) {
            free.set(i, new Buffer(this, MemorySegment.ofArray(new byte[capacity])));
        }
    }

    int outstanding() {
        return outstanding.get();
    }

    int borrows() {
        return borrows.get();
    }

    @Override
    public LoanedBuffer allocateNetwork(int estimatedBytes) {
        if (estimatedBytes > capacity) {
            throw new IllegalArgumentException("a buffer holds " + capacity + " bytes, asked for " + estimatedBytes);
        }
        for (int i = 0; i < free.length(); i++) {
            Buffer buffer = free.getAndSet(i, null);
            if (buffer != null) {
                outstanding.incrementAndGet();
                borrows.incrementAndGet();
                buffer.open = true;
                return buffer;
            }
        }
        throw new IllegalStateException("every buffer is out");
    }

    private void giveBack(Buffer buffer) {
        for (int i = 0; i < free.length(); i++) {
            if (free.compareAndSet(i, null, buffer)) {
                outstanding.decrementAndGet();
                return;
            }
        }
        throw new IllegalStateException("a buffer was returned that this allocator did not lend");
    }

    @Override
    public LoanedBuffer allocate(AllocationHint hint) {
        return allocateNetwork(hint.sizeBytes());
    }

    @Override
    public LoanedBuffer allocateCarrierSlab(int carrierIndex) {
        return allocateNetwork(capacity);
    }

    @Override
    public LoanedBuffer allocateInfrastructure(long sizeBytes) {
        return allocateNetwork((int) sizeBytes);
    }

    @Override
    public MemoryStats stats() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
    }

    private static final class Buffer implements LoanedBuffer {
        private final RecyclingAllocator owner;
        private final MemorySegment segment;
        volatile boolean open;

        Buffer(RecyclingAllocator owner, MemorySegment segment) {
            this.owner = owner;
            this.segment = segment;
        }

        @Override
        public MemorySegment segment() {
            if (!open) {
                throw new IllegalStateException("buffer is closed");
            }
            return segment;
        }

        @Override
        public long size() {
            return 0;
        }

        @Override
        public long capacity() {
            return segment.byteSize();
        }

        @Override
        public void close() {
            if (!open) {
                throw new IllegalStateException("buffer closed twice");
            }
            open = false;
            owner.giveBack(this);
        }

        @Override
        public boolean isAlive() {
            return open;
        }

        @Override
        public int refCount() {
            return open ? 1 : 0;
        }

        @Override
        public void setSize(long newSize) {
        }

        @Override
        public LoanedBuffer slice(long offset, long length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LoanedBuffer view() {
            throw new UnsupportedOperationException();
        }

        @Override
        public LoanedBuffer peek(long offset, long length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void retain() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addCloseAction(Runnable action) {
            throw new UnsupportedOperationException();
        }
    }
}
