package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    }

    private static String loadDsl() throws Exception {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream("dsl/petal.dsl")) {
            return new String(Objects.requireNonNull(in, "dsl/petal.dsl").readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
