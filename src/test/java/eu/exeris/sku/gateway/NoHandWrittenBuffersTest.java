package eu.exeris.sku.gateway;

import eu.exeris.kernel.spi.memory.LoanedBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Response bodies are kernel buffers: production code defines no buffer type of its own. */
@DisplayName("Production code defines no LoanedBuffer implementation")
class NoHandWrittenBuffersTest {

    private static final Path CLASSES = Path.of("target", "classes");
    private static final Path SOURCES = Path.of("src", "main", "java");
    private static final Pattern SOURCE_RULE = Pattern.compile(
            "(implements|extends)\\s+[^{;]*\\b(LoanedBuffer|AbstractLoanedBuffer)\\b|new\\s+LoanedBuffer\\s*\\(");

    @Test
    @DisplayName("No compiled class under src/main implements LoanedBuffer or extends AbstractLoanedBuffer")
    void compiledClasses() throws Exception {
        assertThat(CLASSES).isDirectory();
        List<String> offenders = new ArrayList<>();
        int inspected = 0;
        try (Stream<Path> files = Files.walk(CLASSES);
             URLClassLoader loader = new URLClassLoader(new URL[] {CLASSES.toUri().toURL()},
                     NoHandWrittenBuffersTest.class.getClassLoader())) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".class"))::iterator) {
                String name = CLASSES.relativize(file).toString().replace('/', '.').replaceAll("\\.class$", "");
                if (name.equals("module-info") || name.endsWith("package-info")) {
                    continue;
                }
                Class<?> type = Class.forName(name, false, loader);
                inspected++;
                if (LoanedBuffer.class.isAssignableFrom(type)) {
                    offenders.add(name);
                }
                for (Class<?> nested : type.getDeclaredClasses()) {
                    if (LoanedBuffer.class.isAssignableFrom(nested)) {
                        offenders.add(nested.getName());
                    }
                }
            }
        }
        assertThat(inspected).as("classes inspected").isGreaterThan(20);
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("No source under src/main declares or instantiates a LoanedBuffer implementation")
    void sources() throws IOException {
        List<String> offenders = new ArrayList<>();
        int inspected = 0;
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                inspected++;
                String text = Files.readString(file);
                if (SOURCE_RULE.matcher(text).find()) {
                    offenders.add(SOURCES.relativize(file).toString());
                }
            }
        }
        assertThat(inspected).as("sources inspected").isGreaterThan(5);
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("The source rule recognises a hand-written buffer")
    void ruleRecognisesOffenders() {
        assertThat(SOURCE_RULE.matcher("final class B implements LoanedBuffer {").find()).isTrue();
        assertThat(SOURCE_RULE.matcher("class B extends AbstractLoanedBuffer {").find()).isTrue();
        assertThat(SOURCE_RULE.matcher("class B implements Foo, LoanedBuffer {").find()).isTrue();
        assertThat(SOURCE_RULE.matcher("LoanedBuffer buffer = allocator.allocateNetwork(1);").find()).isFalse();
    }
}
