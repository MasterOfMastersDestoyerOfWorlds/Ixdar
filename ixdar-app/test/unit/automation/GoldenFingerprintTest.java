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
 * Fixtures and the ring, dungeon and quad graphs match their goldens; accept by copying
 * {@code target/golden-actual/} over {@code test/resources/golden/}. Tag {@code mesh} runs by
 * default, skipped by {@code mvn test -Dixdar.test.excludedGroups=heavy,mesh}; the crawfish scans,
 * tag {@code heavy}, run by {@code mvn test -Dtest=GoldenFingerprintTest
 * -Dixdar.test.excludedGroups= -Dgroups=heavy}.
 */
class GoldenFingerprintTest {

    private static final Path GOLDEN = Path.of("test/resources/golden");

    private static final Path ACTUAL = Path.of("target/golden-actual");

    private static final String RESOURCES = "src/main/resources/";

    private static final String FIXTURES = "dsl/fixtures";

    private static final String CRAWFISH_SCANS = "dsl/crawfish";

    private static final String CRAWFISH_RINGS = "dsl/fixtures/crawfish_rings.dsl";

    private static final List<String> NAMED_GRAPHS = List.of(
            "dsl/dungeon_2d.dsl",
            "dsl/dungeon_3d.dsl",
            "dsl/quad_cylinder_test.dsl",
            "test/resources/dsl/quad_layout_sphere.dsl");

    /**
     * Every fixture on disk, so a new one fails until it has a golden, less the crawfish scan's,
     * then the dungeon graphs, the quad cylinder and the sphere's quad layout.
     *
     * @throws IOException when the fixture folder cannot be listed
     * @return one test per graph
     */
    @Tag("mesh")
    @TestFactory
    Stream<DynamicTest> lightGraphsMatchTheirGoldens() throws IOException {
        List<String> graphs = graphsIn(FIXTURES);
        graphs.remove(CRAWFISH_RINGS);
        graphs.addAll(NAMED_GRAPHS);
        return graphs.stream().map(graph -> dynamicTest(graph, () -> assertMatchesGolden(graph)));
    }

    /**
     * The ring graphs over the crawfish scans: the fixture's and one per segmented scan.
     *
     * @throws IOException when the scan graph folder cannot be listed
     * @return one test per graph
     */
    @Tag("heavy")
    @Tag("mesh")
    @TestFactory
    Stream<DynamicTest> crawfishGraphsMatchTheirGoldens() throws IOException {
        List<String> graphs = graphsIn(CRAWFISH_SCANS);
        graphs.add(0, CRAWFISH_RINGS);
        return graphs.stream().map(graph -> dynamicTest(graph, () -> assertMatchesGolden(graph)));
    }

    /**
     * The graph resources in one folder of the packaged resources, sorted by name.
     *
     * @param folder folder under the resource root, e.g. {@code dsl/fixtures}
     * @throws IOException when the folder cannot be listed
     * @return resource paths, e.g. {@code dsl/fixtures/twin_cell.dsl}
     */
    private static List<String> graphsIn(String folder) throws IOException {
        List<String> graphs = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of(RESOURCES + folder))) {
            files.map(file -> folder + "/" + file.getFileName())
                    .filter(graph -> graph.endsWith(Snapshot.GRAPH_SUFFIX))
                    .sorted()
                    .forEach(graphs::add);
        }
        return graphs;
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
