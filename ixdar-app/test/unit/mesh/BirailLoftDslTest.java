package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.annotations.meshnode.MeshNode;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.GraphValidator;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;

public class BirailLoftDslTest {

    @Test
    public void birailLoftRibbonDslValidatesAndExecutes() throws Exception {
        String dsl = loadDsl();
        List<PythonParser.ParsedNode> ast = new PythonParser(new PythonLexer(dsl)).parseGraph();
        Map<String, Class<? extends MeshNode>> registry = NodeGraphRuntime.annotationRegistryClasses();
        assertTrue(GraphValidator.validate(ast, registry).isEmpty());

        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        MeshTopology mesh = runtime.executeGraphToMesh(ast, "ribbon", "geometry");
        assertNotNull(mesh);
        assertTrue(mesh.vertexCount() >= 32 * 6, "u_segments x v_segments grid");
        assertTrue(mesh.faceCount() >= 31 * 5, "quads along closed U");

        Vector3f mn = mesh.boundsMin(new Vector3f());
        Vector3f mx = mesh.boundsMax(new Vector3f());
        assertTrue(Float.isFinite(mn.x) && Float.isFinite(mx.x));
        assertTrue(mx.y - mn.y > 0.15f, "ribbon should span rail offset in Y");
    }

    private static String loadDsl() throws Exception {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("dsl/birail_loft_ribbon.dsl")) {
            return new String(Objects.requireNonNull(in, "dsl/birail_loft_ribbon.dsl").readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
