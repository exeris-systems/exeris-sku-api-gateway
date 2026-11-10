package eu.exeris.sku.gateway;

import eu.exeris.caps.gatewaycore.GatewayCoreModule;
import eu.exeris.caps.gatewaycore.api.GatewayLifecycle;
import eu.exeris.caps.gatewaycore.api.IngressBootstrap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;

class GatewaySkuTest {

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
