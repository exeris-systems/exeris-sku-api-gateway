package eu.exeris.sku.gateway;

import eu.exeris.caps.gatewaycore.GatewayCoreModule;
import eu.exeris.caps.gatewaycore.api.GatewayLifecycle;
import eu.exeris.caps.gatewaycore.api.IngressBootstrap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class GatewaySkuTest {

    @Test
    @DisplayName("ByteArrayLoanedBuffer manages bytes, reference counting and close callbacks")
    void testByteArrayLoanedBuffer() {
        byte[] original = "test payload 12345".getBytes(StandardCharsets.UTF_8);
        ByteArrayLoanedBuffer buffer = new ByteArrayLoanedBuffer(original);

        assertThat(buffer.size()).isEqualTo(original.length);
        assertThat(buffer.capacity()).isEqualTo(original.length);
        assertThat(buffer.isAlive()).isTrue();
        assertThat(buffer.refCount()).isEqualTo(1);

        byte firstByte = buffer.segment().get(ValueLayout.JAVA_BYTE, 0);
        assertThat(firstByte).isEqualTo((byte) 't');

        AtomicBoolean actionExecuted = new AtomicBoolean(false);
        buffer.addCloseAction(() -> actionExecuted.set(true));

        buffer.retain();
        assertThat(buffer.refCount()).isEqualTo(2);

        buffer.close();
        assertThat(buffer.isAlive()).isTrue();
        assertThat(buffer.refCount()).isEqualTo(1);
        assertThat(actionExecuted.get()).isFalse();

        buffer.close();
        assertThat(buffer.isAlive()).isFalse();
        assertThat(buffer.refCount()).isEqualTo(0);
        assertThat(actionExecuted.get()).isTrue();
    }

    @Test
    @DisplayName("GatewayCoreModule initializes and provides IngressBootstrap and GatewayLifecycle")
    void testGatewayCoreModuleIntegration() throws Exception {
        GatewayCoreModule module = new GatewayCoreModule();
        IngressBootstrap ingress = module.ingressBootstrap();
        GatewayLifecycle lifecycle = module.gatewayLifecycle();

        assertThat(ingress).isNotNull();
        assertThat(lifecycle).isNotNull();
        assertThat(lifecycle.isAcceptingTraffic()).isFalse();

        module.initialize();
        module.ready();
        assertThat(lifecycle.isAcceptingTraffic()).isTrue();
        assertThat(module.policyChain()).isNotNull();

        module.terminate();
        assertThat(lifecycle.isAcceptingTraffic()).isFalse();
    }
}
