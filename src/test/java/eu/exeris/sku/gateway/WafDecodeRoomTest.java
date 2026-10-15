package eu.exeris.sku.gateway;

import eu.exeris.caps.gatewaycore.api.PolicyResult;
import eu.exeris.caps.waf.api.WafPolicies;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.sku.gateway.waf.WafGatewayPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WafGatewayPolicy decodes into room borrowed from the allocator the kernel binds around the handler")
class WafDecodeRoomTest {

    @Test
    @DisplayName("A percent-encoded attack is refused using one borrow, returned, on a virtual thread")
    void borrowsFromTheBoundAllocator() throws Exception {
        RecyclingAllocator allocator = new RecyclingAllocator(2, 64 * 1_024);
        WafGatewayPolicy policy = new WafGatewayPolicy(WafPolicies.defaultCoreRules());
        TestExchange exchange = TestExchange.get("/api/v1/users?id=1%20union%20select%201,2");
        AtomicReference<PolicyResult> result = new AtomicReference<>();

        // The kernel binds its allocator around every request handler, on the virtual thread that serves
        // the connection; a thread started otherwise inherits nothing
        Thread thread = Thread.ofVirtual().start(() ->
                ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                        .run(() -> result.set(policy.preRouting(exchange))));
        thread.join();

        assertThat(result.get().isShortCircuit()).isTrue();
        assertThat(allocator.borrows()).isEqualTo(1);
        assertThat(allocator.outstanding()).isZero();
    }

    @Test
    @DisplayName("A request with nothing to decode borrows nothing")
    void cleanRequestBorrowsNothing() throws Exception {
        RecyclingAllocator allocator = new RecyclingAllocator(2, 64 * 1_024);
        WafGatewayPolicy policy = new WafGatewayPolicy(WafPolicies.defaultCoreRules());
        TestExchange exchange = TestExchange.get("/api/v1/users?id=1", new eu.exeris.kernel.spi.http.HttpHeader("Accept", "x"));
        AtomicReference<PolicyResult> result = new AtomicReference<>();

        Thread thread = Thread.ofVirtual().start(() ->
                ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                        .run(() -> result.set(policy.preRouting(exchange))));
        thread.join();

        assertThat(result.get().isShortCircuit()).isFalse();
        assertThat(allocator.borrows()).isZero();
    }
}
