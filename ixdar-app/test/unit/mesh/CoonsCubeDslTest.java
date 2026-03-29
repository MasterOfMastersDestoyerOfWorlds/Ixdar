package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

public class CoonsCubeDslTest {

    @Test
    public void coonsCubeDslValidatesAndExecutes() throws Exception {
        String dsl = loadDsl();
        List<PythonParser.ParsedNode> ast = parseDsl(dsl);
        Map<String, Class<? extends MeshNode>> registry = NodeGraphRuntime.annotationRegistryClasses();
        List<String> errors = GraphValidator.validate(ast, registry);
        assertTrue(errors.isEmpty(), errors::toString);

        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        MeshTopology mesh = runtime.executeGraphToMesh(ast, "patch_out", "geometry");
        assertNotNull(mesh);
        int n = 4;
        int expectedFaces = 6 * n * n;
        assertEquals(expectedFaces, mesh.faceCount());
        assertTrue(mesh.vertexCount() > 8);
    }

    private static String loadDsl() throws Exception {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("dsl/coons_cube.dsl")) {
            return new String(Objects.requireNonNull(in, "dsl/coons_cube.dsl").readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<PythonParser.ParsedNode> parseDsl(String dsl) {
        return new PythonParser(new PythonLexer(dsl)).parseGraph();
    }
}
