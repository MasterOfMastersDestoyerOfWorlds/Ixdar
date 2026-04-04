package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import ixdar.annotations.meshnode.MeshNode;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.GraphValidator;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;

public class CurveSweepTubeDslTest {

    @Test
    public void curveSweepTubeDslValidatesAndExecutes() throws Exception {
        String dsl = loadDsl();
        List<PythonParser.ParsedNode> ast = parseDsl(dsl);
        Map<String, Class<? extends MeshNode>> registry = NodeGraphRuntime.annotationRegistryClasses();
        List<String> errors = GraphValidator.validate(ast, registry);
        assertTrue(errors.isEmpty(), errors::toString);

        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        MeshTopology mesh = runtime.executeGraphToMesh(ast, "tube", "geometry");
        assertNotNull(mesh);
        assertTrue(mesh.vertexCount() >= 64, "BOUNDARY curve sweep should span the strip perimeter");
        assertTrue(mesh.faceCount() >= 32);
        Vector3f mn = mesh.boundsMin(new Vector3f());
        Vector3f mx = mesh.boundsMax(new Vector3f());
        assertTrue(
                mn.distanceSquared(mx) > 1e-8f,
                "bounds should span space; class=" + mesh.getClass().getName() + " min=" + mn + " max=" + mx);
        assertTrue(mesh.radius() > 1e-4f, "radius=" + mesh.radius() + " boundsMin=" + mn + " max=" + mx);
    }

    private static String loadDsl() throws Exception {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("dsl/curve_sweep_tube.dsl")) {
            return new String(Objects.requireNonNull(in, "dsl/curve_sweep_tube.dsl").readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<PythonParser.ParsedNode> parseDsl(String dsl) {
        return new PythonParser(new PythonLexer(dsl)).parseGraph();
    }
}
