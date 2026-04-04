package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

public class PetalDslTest {

    @Test
    public void petalDslValidatesAndExecutes() throws Exception {
        String dsl = loadDsl();
        List<PythonParser.ParsedNode> ast = new PythonParser(new PythonLexer(dsl)).parseGraph();
        Map<String, Class<? extends MeshNode>> registry = NodeGraphRuntime.annotationRegistryClasses();
        List<String> errors = GraphValidator.validate(ast, registry);
        assertTrue(errors.isEmpty(), errors::toString);

        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        MeshTopology mesh = runtime.executeGraphToMesh(ast, "petal", "geometry");
        assertNotNull(mesh);
        assertTrue(mesh.vertexCount() > 0);
        assertTrue(mesh.faceCount() > 0);

        Vector3f min = mesh.boundsMin(new Vector3f());
        Vector3f max = mesh.boundsMax(new Vector3f());
        float ex = max.x - min.x;
        float ey = max.y - min.y;
        float ez = max.z - min.z;
        assertTrue(ez > ex * 1.08f,
                () -> "petal length (Z) should exceed width (X); ex=" + ex + " ez=" + ez);
        assertTrue(ey > 0.008f, () -> "cup/notch should produce non-flat Y extent; ey=" + ey);
        assertTrue(ex > 0.30f && ex < 0.44f, () -> "X extent should track default petal_width ~0.38; ex=" + ex);
        assertTrue(ez > 0.48f && ez < 0.62f, () -> "Z extent should track default petal_length ~0.54; ez=" + ez);

        int boundaryEdges = 0;
        for (int i = 0; i < mesh.edgeCount(); i++) {
            if (mesh.isBoundaryEdge(mesh.edgeIdAt(i))) {
                boundaryEdges++;
            }
        }
        assertEquals(0, boundaryEdges, "solidified petal should be a closed manifold sheet");
    }

    private static String loadDsl() throws Exception {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream("dsl/petal.dsl")) {
            return new String(Objects.requireNonNull(in, "dsl/petal.dsl").readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
