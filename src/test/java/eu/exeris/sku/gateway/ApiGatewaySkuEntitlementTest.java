package eu.exeris.sku.gateway;

import eu.exeris.kernel.spi.contract.EntitlementClass;
import eu.exeris.kernel.spi.contract.EntitlementRequirement;
import eu.exeris.kernel.tck.contract.AbstractEntitlementRequirementTck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("exeris-sku-api-gateway: EntitlementRequirement")
class ApiGatewaySkuEntitlementTest extends AbstractEntitlementRequirementTck {

    @Override
    protected Class<? extends EntitlementRequirement> providerClass() {
        return ApiGatewaySkuEntitlement.class;
    }

    @Test
    @DisplayName("ServiceLoader discovers the provider from the cap's own service registration")
    void serviceLoaderDiscoversTheProvider() {
        List<Class<? extends EntitlementRequirement>> types = ServiceLoader.load(EntitlementRequirement.class)
                .stream()
                .map(ServiceLoader.Provider::type)
                .toList();

        assertThat(types).contains(ApiGatewaySkuEntitlement.class);
    }

    @Test
    @DisplayName("It requires exactly this cap's own capability identifier")
    void requiresOnlyItsOwnCapability() {
        assertThat(new ApiGatewaySkuEntitlement().requiredCapabilities()).containsExactly("exeris-sku-api-gateway");
        assertThat(ApiGatewaySkuEntitlement.CAPABILITY_ID).isEqualTo("exeris-sku-api-gateway");
    }

    @Test
    @DisplayName("It declares the COMMERCIAL entitlement class, stable across calls")
    void entitlementClassIsCommercialAndStable() {
        ApiGatewaySkuEntitlement entitlement = new ApiGatewaySkuEntitlement();
        EntitlementClass first = entitlement.entitlementClass();

        assertThat(first).isEqualTo(EntitlementClass.COMMERCIAL);
        assertThat(entitlement.entitlementClass()).isSameAs(first);
        assertThat(new ApiGatewaySkuEntitlement().entitlementClass()).isEqualTo(EntitlementClass.COMMERCIAL);
    }
}
