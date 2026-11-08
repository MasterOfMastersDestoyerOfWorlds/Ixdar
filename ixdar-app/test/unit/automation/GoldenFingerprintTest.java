package unit.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import ixdar.automation.Snapshot;
import ixdar.automation.endpoints.mesh.Fingerprint;
import ixdar.nodegraph.runtime.NodeGraphRuntime;

/**
 * The ring graphs over real scans (crawfish, fertility) match their goldens. Tag {@code heavy}: run by
 * {@code mvn test -Dtest=GoldenFingerprintTest -Dixdar.test.excludedGroups= -Dgroups=heavy}; accept by
 * copying {@code target/golden-actual/} over {@code test/resources/golden/}.
 */
class GoldenFingerprintTest {

    private static final Path GOLDEN = Path.of("test/resources/golden");

    private static final Path ACTUAL = Path.of("target/golden-actual");

    private static final String RESOURCES = "src/main/resources/";

    private static final String CRAWFISH_SCANS = "dsl/crawfish";

    private static final List<String> FIXTURE_SCANS = List.of(
            "dsl/fixtures/crawfish_rings.dsl",
            "dsl/fixtures/fertility_rings.dsl");

    /**
     * The ring graphs over the scans: the fixtures' and one per segmented crawfish scan.
     *
     * @throws IOException when the scan graph folder cannot be listed
     * @return one test per graph
     */
    @Tag("heavy")
    @Tag("mesh")
    @TestFactory
    Stream<DynamicTest> scanGraphsMatchTheirGoldens() throws IOException {
        List<String> graphs = new ArrayList<>(FIXTURE_SCANS);
        try (Stream<Path> files = Files.list(Path.of(RESOURCES + CRAWFISH_SCANS))) {
            files.map(file -> CRAWFISH_SCANS + "/" + file.getFileName())
                    .filter(graph -> graph.endsWith(Snapshot.GRAPH_SUFFIX))
                    .sorted()
                    .forEach(graphs::add);
        }
        return graphs.stream().map(graph -> dynamicTest(graph, () -> assertMatchesGolden(graph)));
    }

    /**
     * Run a graph and compare its fingerprint, as the snapshot entry point writes it, with the
     * golden at the graph's path under {@link #GOLDEN}; on a difference, leave the new text at the
     * same path under {@link #ACTUAL}.
     *
     * @param graph graph resource or file path
     * @throws IOException when the golden cannot be read or the actual cannot be written
     */
    private static void assertMatchesGolden(String graph) throws IOException {
        String name = graph.substring(0, graph.length() - Snapshot.GRAPH_SUFFIX.length()) + ".json";
        String actual = Snapshot.PRETTY_GSON.toJson(
                Fingerprint.graphFingerprint(NodeGraphRuntime.executeResource(graph, Map.of()))) + "\n";
        Path golden = GOLDEN.resolve(name);
        String expected = Files.exists(golden)
                ? new String(Files.readAllBytes(golden), StandardCharsets.UTF_8) : "";
        if (!actual.equals(expected)) {
            Path written = ACTUAL.resolve(name);
            Files.createDirectories(written.getParent());
            Files.write(written, actual.getBytes(StandardCharsets.UTF_8));
        }
        String accept = "accept with: cp -r ixdar-app/" + ACTUAL + "/. ixdar-app/" + GOLDEN + "/";
        assertTrue(Files.exists(golden), graph + " has no golden; " + accept);
        assertEquals(expected, actual, graph + " fingerprints differently from its golden; " + accept);
    }
}
