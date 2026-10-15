package eu.exeris.sku.gateway;

import eu.exeris.caps.routing.api.RequestRouter;
import eu.exeris.caps.routing.api.RequestRouters;
import eu.exeris.caps.routing.api.RouteLookup;
import eu.exeris.sku.gateway.routing.RouteLookupPool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RouteLookupPool")
class RouteLookupPoolTest {

    private static final RequestRouter ROUTER = RequestRouters.builder()
            .route("alpha").pathPrefix("/alpha").targetUri("http://alpha:1").and()
            .route("beta").pathPrefix("/beta").targetUri("http://beta:2").and()
            .build();

    @Test
    @DisplayName("A released lookup is the one the next caller gets")
    void releasedLookupIsReused() {
        RouteLookupPool pool = new RouteLookupPool(ROUTER, 2);
        RouteLookup first = pool.acquire();
        pool.release(first);
        assertThat(pool.idle()).isEqualTo(1);
        assertThat(pool.acquire()).isSameAs(first);
        assertThat(pool.idle()).isZero();
    }

    @Test
    @DisplayName("Two callers never hold the same lookup")
    void heldLookupsAreDistinct() {
        RouteLookupPool pool = new RouteLookupPool(ROUTER, 2);
        RouteLookup a = pool.acquire();
        RouteLookup b = pool.acquire();
        assertThat(a).isNotSameAs(b);
    }

    @Test
    @DisplayName("Above its capacity the pool hands out new lookups and drops the surplus on release")
    void overflowCostsAllocationNeverCorrectness() {
        RouteLookupPool pool = new RouteLookupPool(ROUTER, 2);
        List<RouteLookup> held = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            held.add(pool.acquire());
        }
        assertThat(held).doesNotHaveDuplicates();
        held.forEach(pool::release);
        assertThat(pool.idle()).isEqualTo(2);
    }

    @Test
    @DisplayName("A pool holds at least one lookup")
    void capacityIsValidated() {
        assertThatThrownBy(() -> new RouteLookupPool(ROUTER, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Virtual threads matching at once each read their own result")
    void concurrentMatchesOnVirtualThreads() throws Exception {
        RouteLookupPool pool = new RouteLookupPool(ROUTER, 4);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int t = 0; t < 128; t++) {
                final int task = t;
                results.add(executor.submit(() -> {
                    int wrong = 0;
                    for (int i = 0; i < 2_000; i++) {
                        boolean alpha = ((i + task) & 1) == 0;
                        RouteLookup lookup = pool.acquire();
                        try {
                            boolean matched = ROUTER.match("GET", alpha ? "/alpha/x" : "/beta/x", lookup);
                            if (!matched || !lookup.route().id().equals(alpha ? "alpha" : "beta")) {
                                wrong++;
                            }
                        } finally {
                            pool.release(lookup);
                        }
                    }
                    return wrong;
                }));
            }
            int wrong = 0;
            for (Future<Integer> result : results) {
                wrong += result.get(60, TimeUnit.SECONDS);
            }
            assertThat(wrong).isZero();
        }
        assertThat(pool.idle()).isBetween(1, 4);
    }
}
