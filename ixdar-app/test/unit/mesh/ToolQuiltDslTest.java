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
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.graph.GraphValidator;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;

public class ToolQuiltDslTest {

    @Test
    public void toolQuiltDslValidatesAndExecutes() throws Exception {
        String dsl;
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream("dsl/tool_quilt.dsl")) {
            dsl = new String(Objects.requireNonNull(in, "dsl/tool_quilt.dsl").readAllBytes(), StandardCharsets.UTF_8);
        }
        PythonLexer lexer = new PythonLexer(dsl);
        PythonParser parser = new PythonParser(lexer);
        List<PythonParser.ParsedNode> ast = parser.parseGraph();

        Map<String, Class<? extends MeshNode>> registry = NodeGraphRuntime.annotationRegistryClasses();
        List<String> errors = GraphValidator.validate(ast, registry);
        assertTrue(errors.isEmpty(), errors::toString);

        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        HalfEdgeMesh mesh = runtime.executeGraphToMesh(ast, "quilt_out", "geometry");
        assertNotNull(mesh);
        assertTrue(mesh.vertexCount() > 0);
    }
}
