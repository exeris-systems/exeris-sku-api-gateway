package eu.exeris.sku.gateway;

import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.core.contract.crypto.IssuerKeyResolver;
import eu.exeris.kernel.core.contract.crypto.JsonCanonicalizer;
import eu.exeris.kernel.core.contract.crypto.LicenseManifestVerifier;
import eu.exeris.kernel.spi.contract.EntitlementRequirement;
import eu.exeris.kernel.spi.contract.ExecutionContract;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.contract.ContractBreachException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Licence enforcement of this SKU and the commercial capabilities it composes against the kernel's Phase 0 contract gate (ADR-088, ADR-089).
 *
 * <p>The boot cases run the real {@link KernelBootstrap#boot(Runnable)} with the Community config provider,
 * which reads {@code exeris.<key>} system properties, so the gate sees exactly what a deployment declares.
 * The gate trusts only the issuer keys embedded in the kernel, and offers no public way to run it against
 * another key, so the cases that need a signed manifest which does or does not entitle this cap verify it
 * with {@link LicenseManifestVerifier} against a test key and then enforce the cap's declared requirement
 * through {@link ExecutionContract#assertCapability(String)}, the call the gate makes for each one.
 */
@DisplayName("exeris-sku-api-gateway: licence enforcement")
class ApiGatewaySkuLicenceEnforcementTest {

    private static final String ENVIRONMENT_PROPERTY = "exeris.environment";
    private static final String MANIFEST_PATH_PROPERTY = "exeris.license.manifest.path";
    private static final String TEST_KEY_ID = "api-gateway-test-k1";
    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");

    private static KeyPair testIssuer;

    @BeforeAll
    static void generateTestIssuer() throws Exception {
        testIssuer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    @AfterEach
    void clearDeclarations() {
        System.clearProperty(ENVIRONMENT_PROPERTY);
        System.clearProperty(MANIFEST_PATH_PROPERTY);
    }

    @Test
    @DisplayName("The classpath declares the SKU's identifier and each composed commercial capability's")
    void classpathDeclaresTheSkuAndItsCapabilities() {
        assertThat(declaredCapabilities()).contains(ApiGatewaySkuEntitlement.CAPABILITY_ID,
                "exeris-caps-gateway-core", "exeris-caps-request-routing", "exeris-caps-upstream-pool",
                "exeris-caps-circuit-breaker", "exeris-caps-gateway-observability", "exeris-caps-waf-rules",
                "exeris-caps-rate-limiting", "exeris-caps-jwt-validation");
        // cors-policy is a community capability and has no entitlement
        assertThat(declaredCapabilities()).doesNotContain("exeris-caps-cors-policy");
    }

    @Test
    @DisplayName("Production boot with no manifest stops with EX-LIC-0005 before the application runs")
    void productionWithoutManifestIsRefused() {
        System.setProperty(ENVIRONMENT_PROPERTY, "production");
        AtomicBoolean ran = new AtomicBoolean();

        assertThatThrownBy(() -> KernelBootstrap.builder().build().boot(() -> ran.set(true)))
                .isInstanceOfSatisfying(ContractBreachException.class, breach ->
                        assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0005));
        assertThat(ran).isFalse();
    }

    @Test
    @DisplayName("Production boot with a manifest from an issuer the kernel does not trust stops with EX-LIC-0002")
    void productionWithUntrustedIssuerIsRefused(@TempDir Path dir) throws Exception {
        Path manifest = dir.resolve("license-manifest.json");
        Files.write(manifest, signedManifest(List.of(ApiGatewaySkuEntitlement.CAPABILITY_ID)));
        System.setProperty(ENVIRONMENT_PROPERTY, "production");
        System.setProperty(MANIFEST_PATH_PROPERTY, manifest.toString());
        AtomicBoolean ran = new AtomicBoolean();

        assertThatThrownBy(() -> KernelBootstrap.builder().build().boot(() -> ran.set(true)))
                .isInstanceOfSatisfying(ContractBreachException.class, breach ->
                        assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0002));
        assertThat(ran).isFalse();
    }

    @Test
    @DisplayName("A signed manifest that entitles other capabilities but not this SKU is a HARD breach (EX-LIC-0004)")
    void manifestWithoutTheSkuIsHardBreach() throws Exception {
        List<String> others = new ArrayList<>(declaredCapabilities());
        others.remove(ApiGatewaySkuEntitlement.CAPABILITY_ID);
        ExecutionContract contract = verify(signedManifest(others));

        assertThatCode(() -> contract.assertEnvironment("production")).doesNotThrowAnyException();
        assertThatThrownBy(() -> enforceEveryRequirement(contract))
                .isInstanceOfSatisfying(ContractBreachException.class, breach ->
                        assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0004));
    }

    @Test
    @DisplayName("A manifest that leaves out any one composed commercial capability is a HARD breach (EX-LIC-0004)")
    void manifestMissingAComposedCapabilityIsHardBreach() throws Exception {
        for (String missing : declaredCapabilities()) {
            List<String> listed = new ArrayList<>(declaredCapabilities());
            listed.remove(missing);
            ExecutionContract contract = verify(signedManifest(listed));

            assertThatThrownBy(() -> enforceEveryRequirement(contract))
                    .as("manifest without " + missing)
                    .isInstanceOfSatisfying(ContractBreachException.class, breach ->
                            assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0004));
        }
    }

    @Test
    @DisplayName("A signed manifest that lists the SKU and every composed commercial capability satisfies the gate")
    void manifestWithEveryIdPasses() throws Exception {
        ExecutionContract contract = verify(signedManifest(new ArrayList<>(declaredCapabilities())));

        assertThat(contract.assertEnvironment("production")).isEmpty();
        assertThatCode(() -> enforceEveryRequirement(contract)).doesNotThrowAnyException();
    }

    private static Set<String> declaredCapabilities() {
        Set<String> declared = new TreeSet<>();
        for (EntitlementRequirement requirement : ServiceLoader.load(EntitlementRequirement.class)) {
            declared.addAll(requirement.requiredCapabilities());
        }
        return declared;
    }

    /** Enforces every requirement on the classpath, the SKU's and the capabilities', as the gate does. */
    private static void enforceEveryRequirement(ExecutionContract contract) {
        for (EntitlementRequirement requirement : ServiceLoader.load(EntitlementRequirement.class)) {
            for (String capability : requirement.requiredCapabilities()) {
                assertThat(contract.assertCapability(capability)).isEmpty();
            }
        }
    }

    private static ExecutionContract verify(byte[] manifest) {
        return LicenseManifestVerifier.verify(
                new String(manifest, StandardCharsets.UTF_8),
                NOW,
                IssuerKeyResolver.of(Map.of(TEST_KEY_ID, testIssuer.getPublic())));
    }

    private static byte[] signedManifest(List<String> capabilities) throws Exception {
        String unsigned = "{\"$schema\":\"" + LicenseManifestVerifier.SCHEMA_V1 + "\","
                + "\"contract\":{\"id\":\"EXR-TEST-API-GATEWAY\",\"commercialModel\":\"STANDARD\"},"
                + "\"entitlement\":{\"edition\":\"commercial\",\"sku\":\"exeris-sku-api-gateway\","
                + "\"licenseMode\":\"SUBSCRIPTION\",\"capabilities\":" + jsonArray(capabilities) + "},"
                + "\"execution\":{\"environments\":[\"production\"],\"authorizedInstances\":1,"
                + "\"validFrom\":\"2026-01-01T00:00:00Z\",\"validUntil\":\"2027-01-01T00:00:00Z\"},"
                + "\"enforcementRules\":{\"capability\":\"HARD\",\"environment\":\"HARD\"},"
                + "\"issuer\":{\"keyId\":\"" + TEST_KEY_ID + "\",\"issuedAt\":\"2026-01-01T00:00:00Z\"}}";
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(testIssuer.getPrivate());
        signer.update(JsonCanonicalizer.canonicalize(unsigned));
        String value = Base64.getEncoder().encodeToString(signer.sign());
        String signed = unsigned.substring(0, unsigned.length() - 1)
                + ",\"signature\":{\"algorithm\":\"Ed25519\",\"canonicalization\":\"RFC-8785\","
                + "\"value\":\"" + value + "\"}}";
        return signed.getBytes(StandardCharsets.UTF_8);
    }

    private static String jsonArray(List<String> values) {
        return values.stream().map(v -> "\"" + v + "\"")
                .reduce((a, b) -> a + "," + b)
                .map(joined -> "[" + joined + "]")
                .orElse("[]");
    }
}
