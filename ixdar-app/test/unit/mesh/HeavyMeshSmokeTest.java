package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.joml.Vector3f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import ixdar.annotations.meshnode.MeshNode;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.GraphValidator;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;

/**
 * Heavy mesh smoke test for tool_quilt.dsl at production subdivision level (6).
 * <p>
 * This test is tagged @Tag("heavy") and excluded from the fast unit/mesh/*Test suite
 * via surefire tag filtering. Agents must run this test before completing mesh/DSL tickets.
 * </p>
 * <p>
 * Maven command to run heavy tests: {@code mvn test -Dgroups=heavy -Xmx4g}
 * </p>
 */
@Tag("heavy")
public class HeavyMeshSmokeTest {

    private static final int SUBDIVISIONS = 6;
    private static final long TIMEOUT_SECONDS = 10;
    private static final int MIN_VERTEX_COUNT = 100_000;
    private static final float MIN_RADIAL_RANGE = 0.001f;

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    public void toolQuiltDslAtDefaultSubdivisionLevel() throws Exception {
        long startTime = System.nanoTime();

        String dsl = loadDsl();
        // Enable stitches to get sufficient vertex count at subdivision level 6
        dsl = dsl.replace(
                "stitches = input_boolean(name=\"stitches\", default=false)",
                "stitches = input_boolean(name=\"stitches\", default=true)");

        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();

        List<PythonParser.ParsedNode> ast = parseDsl(dsl);
        Map<String, Class<? extends MeshNode>> registry = NodeGraphRuntime.annotationRegistryClasses();
        List<String> errors = GraphValidator.validate(ast, registry);
        assertTrue(errors.isEmpty(), "DSL validation errors: " + errors);

        MeshTopology mesh = runtime.executeGraphToMesh(ast, "quilt_out", "geometry");

        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

        assertNotNull(mesh, "Mesh should be non-null");
        assertTrue(mesh.vertexCount() > MIN_VERTEX_COUNT,
                "Vertex count should be > " + MIN_VERTEX_COUNT + " (got " + mesh.vertexCount() + ")");

        float minR = Float.MAX_VALUE;
        float maxR = -Float.MAX_VALUE;
        Vector3f p = new Vector3f();
        for (int i = 0; i < mesh.vertexCount(); i++) {
            mesh.vertexPosition(mesh.vertexIdAt(i), p);
            float r = p.length();
            minR = Math.min(minR, r);
            maxR = Math.max(maxR, r);
        }
        float radialRange = maxR - minR;
        assertTrue(radialRange > MIN_RADIAL_RANGE,
                "Vertex positions should show displacement (radial range=" + radialRange
                        + ", minR=" + minR + ", maxR=" + maxR + ")");
    }

    private static String loadDsl() throws Exception {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("dsl/tool_quilt.dsl")) {
            return new String(Objects.requireNonNull(in, "dsl/tool_quilt.dsl").readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<PythonParser.ParsedNode> parseDsl(String dsl) {
        return new PythonParser(new PythonLexer(dsl)).parseGraph();
    }
}
