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

public class HandDslTest {

    @Test
    public void handDslExecutes() throws Exception {
        String dsl = loadDsl("hand.dsl");
        List<PythonParser.ParsedNode> ast = parseDsl(dsl);

        Map<String, Class<? extends MeshNode>> registry = NodeGraphRuntime.annotationRegistryClasses();
        List<String> errors = GraphValidator.validate(ast, registry);
        assertTrue(errors.isEmpty(), "Validation errors: " + errors);

        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        MeshTopology mesh = runtime.executeGraphToMesh(ast, "hand_tagged", "geometry");
        assertNotNull(mesh, "hand.dsl returned null mesh");
        assertTrue(mesh.vertexCount() > 0, "mesh has 0 vertices");
        assertTrue(mesh.faceCount() > 0, "mesh has 0 faces");
        System.out.println("hand.dsl: verts=" + mesh.vertexCount() + " faces=" + mesh.faceCount());
    }

    @Test
    public void quadCylinderTestDslExecutes() throws Exception {
        String dsl = loadDsl("quad_cylinder_test.dsl");
        List<PythonParser.ParsedNode> ast = parseDsl(dsl);

        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        MeshTopology mesh = runtime.executeGraphToMesh(ast, "smooth", "mesh");
        assertNotNull(mesh, "quad_cylinder_test.dsl returned null mesh");
        assertTrue(mesh.vertexCount() > 0, "mesh has 0 vertices");
        System.out.println("quad_cylinder_test.dsl: verts=" + mesh.vertexCount() + " faces=" + mesh.faceCount());
    }

    private static String loadDsl(String filename) throws Exception {
        try (InputStream in = HandDslTest.class.getClassLoader().getResourceAsStream("dsl/" + filename)) {
            Objects.requireNonNull(in, "DSL resource not found: " + filename);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<PythonParser.ParsedNode> parseDsl(String dsl) {
        return new PythonParser(new PythonLexer(dsl)).parseGraph();
    }
}
