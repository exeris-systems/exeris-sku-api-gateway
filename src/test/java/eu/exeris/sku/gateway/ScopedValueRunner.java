package eu.exeris.sku.gateway;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

/** Binds the allocator slot the way the Community driver binds it around a request handler. */
final class ScopedValueRunner {

    private ScopedValueRunner() {
    }

    static void run(MemoryAllocator allocator, Runnable action) {
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator).run(action);
    }
}
