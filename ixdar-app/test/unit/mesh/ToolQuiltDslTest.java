package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.annotations.meshnode.MeshNode;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.graph.GraphValidator;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;
import ixdar.scenes.mesh.MeshNodeViewerScene;

public class ToolQuiltDslTest {

    @Test
    public void toolQuiltMockDslValidatesAndExecutes() throws Exception {
        String dsl = MeshNodeViewerScene.readClasspathResourceUtf8("dsl/tool_quilt_mock.dsl");
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
