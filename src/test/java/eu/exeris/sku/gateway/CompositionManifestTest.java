package eu.exeris.sku.gateway;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.exeris.sdk.composition.CapManifest;
import eu.exeris.sdk.composition.runtime.CompositionStampAssertion;
import eu.exeris.tooling.codegen.core.capability.CapabilityGraph;
import eu.exeris.tooling.codegen.core.capability.CapabilityModuleDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@code composition.json} is generated, not authored: it is the capability graph of the composed
 * capabilities' own metadata, stamped the way the codegen stamps a graph. This test regenerates it and
 * fails when the committed file differs. To rewrite the file after a change to the composed set, run
 * {@code mvn test -Dtest=CompositionManifestTest -Dexeris.composition.regenerate=true}.
 */
@DisplayName("composition.json (ADR-053)")
class CompositionManifestTest {

    private static final Path MANIFEST = Path.of("composition.json");
    private static final List<String> COMPOSED = List.of(
            "CircuitBreakerModule", "CorsPolicyModule", "GatewayCoreModule", "JwtValidationModule",
            "ObservabilityModule", "RateLimitModule", "RequestRoutingModule", "UpstreamPoolModule", "WafModule");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static String generate() throws Exception {
        List<CapabilityModuleDescriptor> descriptors = new ArrayList<>();
        for (URL dir : Collections.list(Thread.currentThread().getContextClassLoader().getResources("exeris-metadata"))) {
            if (!"jar".equals(dir.getProtocol())) {
                continue;
            }
            JarFile jar = ((JarURLConnection) dir.openConnection()).getJarFile();
            for (JarEntry entry : Collections.list(jar.entries())) {
                String name = entry.getName();
                if (name.startsWith("exeris-metadata/capability_") && name.endsWith(".json")) {
                    descriptors.add(MAPPER.readValue(jar.getInputStream(entry), CapabilityModuleDescriptor.class));
                }
            }
        }
        CapabilityGraph graph = CapabilityGraph.build(descriptors, projectVersion());
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return MAPPER.writer(printer).writeValueAsString(graph) + "\n";
    }

    /** The release identity of the composition: the POM version without its SNAPSHOT qualifier. */
    private static String projectVersion() throws Exception {
        Matcher m = Pattern.compile("<artifactId>exeris-sku-api-gateway</artifactId>\\s*<version>([^<]+)</version>")
                .matcher(Files.readString(Path.of("pom.xml")));
        assertThat(m.find()).isTrue();
        return m.group(1).replace("-SNAPSHOT", "");
    }

    @Test
    @DisplayName("is the generated capability graph of the composed set; regenerate with -Dexeris.composition.regenerate=true")
    void manifestIsGenerated() throws Exception {
        String generated = generate();
        if (Boolean.getBoolean("exeris.composition.regenerate")) {
            Files.writeString(MANIFEST, generated, StandardCharsets.UTF_8);
        }

        assertThat(Files.readString(MANIFEST, StandardCharsets.UTF_8)).isEqualTo(generated);
    }

    @Test
    @DisplayName("lists exactly the nine composed modules and passes CompositionStampAssertion")
    void manifestIsConsistent() throws Exception {
        JsonNode root = MAPPER.readTree(Files.readString(MANIFEST));
        List<String> modules = new ArrayList<>();
        List<CapManifest.Module> specModules = new ArrayList<>();
        for (JsonNode module : root.get("modules")) {
            modules.add(module.get("name").asText());
            List<CapManifest.Provided> provided = new ArrayList<>();
            for (JsonNode p : module.get("module").get("provides")) {
                provided.add(new CapManifest.Provided(p.get("service").asText(), p.get("version").asText()));
            }
            JsonNode owner = module.get("module").get("lifecycleOwner");
            specModules.add(new CapManifest.Module(module.get("qualifiedName").asText(),
                    new CapManifest.ModuleBody(provided, owner == null || owner.isNull() ? null : owner.asText())));
        }
        List<String> initOrder = new ArrayList<>();
        root.get("initOrder").forEach(n -> initOrder.add(n.asText()));
        JsonNode stamp = root.get("stamp");
        CapManifest manifest = new CapManifest(2,
                new CapManifest.Stamp(stamp.get("validated").asBoolean(),
                        stamp.get("compositionVersion").asText(), stamp.get("contentBinding").asText()),
                specModules, initOrder);

        assertThat(modules).containsExactlyInAnyOrderElementsOf(COMPOSED);
        assertThat(stamp.get("validated").asBoolean()).isTrue();
        assertThat(stamp.get("compositionVersion").asText()).isEqualTo(projectVersion());
        assertThatCode(() -> CompositionStampAssertion.assertConsistent(manifest)).doesNotThrowAnyException();
    }
}
