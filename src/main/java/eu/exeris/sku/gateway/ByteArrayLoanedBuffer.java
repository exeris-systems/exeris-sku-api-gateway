package eu.exeris.sku.gateway;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

public final class ByteArrayLoanedBuffer implements LoanedBuffer {

    private final MemorySegment segment;
    private final long capacity;
    private long size;
    private final AtomicInteger refCount = new AtomicInteger(1);
    private final List<Runnable> closeActions = new ArrayList<>();
    private volatile boolean alive = true;

    public ByteArrayLoanedBuffer(byte[] bytes) {
        this(MemorySegment.ofArray(Objects.requireNonNull(bytes, "bytes")), bytes.length, bytes.length);
    }

    private ByteArrayLoanedBuffer(MemorySegment segment, long size, long capacity) {
        this.segment = segment;
        this.size = size;
        this.capacity = capacity;
    }

    @Override
    public MemorySegment segment() {
        return segment;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public long capacity() {
        return capacity;
    }

    @Override
    public LoanedBuffer slice(long offset, long length) {
        return new ByteArrayLoanedBuffer(segment.asSlice(offset, length), length, length);
    }

    @Override
    public LoanedBuffer view() {
        return slice(0, size);
    }

    @Override
    public LoanedBuffer peek(long offset, long length) {
        return slice(offset, length);
    }

    @Override
    public void retain() {
        refCount.incrementAndGet();
    }

    @Override
    public void close() {
        if (refCount.decrementAndGet() <= 0) {
            alive = false;
            for (Runnable action : closeActions) {
                try {
                    action.run();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    public int refCount() {
        return refCount.get();
    }

    @Override
    public void setSize(long size) {
        this.size = size;
    }

    @Override
    public boolean isAlive() {
        return alive;
    }

    @Override
    public void addCloseAction(Runnable action) {
        if (action != null) {
            closeActions.add(action);
        }
    }
}
