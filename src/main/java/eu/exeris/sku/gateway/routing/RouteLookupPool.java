package eu.exeris.sku.gateway.routing;

import eu.exeris.caps.routing.api.RequestRouter;
import eu.exeris.caps.routing.api.RouteLookup;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A bounded, lock-free pool of the {@link RouteLookup}s that match a request to a route.
 *
 * <p>A lookup is needed only while a route is being matched, which is a short section that does not block.
 * The caller {@linkplain #acquire() acquires} one, matches into it, copies out what it still needs (the route,
 * the rewritten path) and {@linkplain #release releases} it, in a {@code finally}, before anything that can
 * block, such as a call to an upstream. A lookup is therefore held by one caller at a time and for far less
 * than a request, so the pool needs about as many lookups as there are threads matching at the same instant,
 * not as many as there are requests in flight.
 *
 * <p>The kernel serves a connection on a virtual thread that is new for that connection, so a lookup kept per
 * thread would be built again for every connection. The pool is shared, and what it holds does not depend on
 * which thread asks.
 *
 * <p>Allocation: {@code acquire} and {@code release} allocate nothing when a lookup is free. A caller that
 * finds the pool empty gets a new lookup from the router, and a release into a full pool drops the lookup, so
 * the pool never holds more than its capacity and a burst above it costs allocation, never correctness. A
 * pooled lookup keeps the path it last matched until the next match overwrites it.
 *
 * <p>Thread safety: every method is safe to call from any thread. A lookup obtained from {@code acquire} is
 * not thread-safe and must not be used after {@code release}.
 */
public final class RouteLookupPool {

    private static final int MINIMUM_CAPACITY = 4;

    private final RequestRouter router;
    private final AtomicReferenceArray<RouteLookup> free;

    /**
     * Creates a pool sized for the threads that can run at once: twice the available processors, which is the
     * most that match at the same instant on the kernel's virtual-thread scheduler, with room for threads that
     * are not scheduled by it.
     *
     * @param router the router whose lookups the pool holds
     */
    public RouteLookupPool(RequestRouter router) {
        this(router, Math.max(MINIMUM_CAPACITY, 2 * Runtime.getRuntime().availableProcessors()));
    }

    /**
     * Creates a pool that holds at most {@code capacity} lookups.
     *
     * @param router   the router whose lookups the pool holds
     * @param capacity the most lookups held at rest; at least one
     */
    public RouteLookupPool(RequestRouter router, int capacity) {
        this.router = Objects.requireNonNull(router, "router");
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1, was " + capacity);
        }
        this.free = new AtomicReferenceArray<>(capacity);
    }

    /**
     * Takes a lookup that no other caller holds.
     *
     * @return a pooled lookup, or a new one when none is free
     */
    public RouteLookup acquire() {
        for (int i = 0, n = free.length(); i < n; i++) {
            RouteLookup lookup = free.get(i);
            if (lookup != null && free.compareAndSet(i, lookup, null)) {
                return lookup;
            }
        }
        return router.newLookup();
    }

    /**
     * Gives a lookup back. The caller must not touch it afterwards.
     *
     * @param lookup a lookup from {@link #acquire()}
     */
    public void release(RouteLookup lookup) {
        for (int i = 0, n = free.length(); i < n; i++) {
            if (free.get(i) == null && free.compareAndSet(i, null, lookup)) {
                return;
            }
        }
    }

    /**
     * Counts the lookups at rest, which is every lookup that was acquired and released, up to the capacity.
     * It is a snapshot for diagnostics and tests.
     *
     * @return the number of free lookups
     */
    public int idle() {
        int idle = 0;
        for (int i = 0, n = free.length(); i < n; i++) {
            if (free.get(i) != null) {
                idle++;
            }
        }
        return idle;
    }
}
