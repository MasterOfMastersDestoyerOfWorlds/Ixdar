package unit.procgen.dungeon;

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
import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.graph.GraphValidator;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;

/** End-to-end integration test: dungeon_2d.dsl parses, validates, runs, and produces a mesh. */
public class Dungeon2dDslTest {

    @Test
    public void dungeon2dDslParsesValidatesAndExecutes() throws Exception {
        String dsl = loadDsl("dsl/dungeon_2d.dsl");
        List<PythonParser.ParsedNode> ast = parseDsl(dsl);

        Map<String, Class<? extends MeshNode>> registry = NodeGraphRuntime.annotationRegistryClasses();
        List<String> errors = GraphValidator.validate(ast, registry);
        assertTrue(errors.isEmpty(), () -> "graph validation errors: " + errors);

        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        Object result = runtime.executeGraphResult(ast, "dungeon", "mesh");
        assertNotNull(result);
        assertTrue(result instanceof ArrayMesh, "dungeon output should be an ArrayMesh, got "
                + result.getClass().getSimpleName());
        ArrayMesh mesh = (ArrayMesh) result;
        assertTrue(mesh.vertexCount() > 0, "dungeon mesh should have vertices");
        assertTrue(mesh.faceCount() > 0, "dungeon mesh should have faces");
    }

    @Test
    public void dungeon2dDslIsDeterministic() throws Exception {
        String dsl = loadDsl("dsl/dungeon_2d.dsl");
        List<PythonParser.ParsedNode> ast1 = parseDsl(dsl);
        List<PythonParser.ParsedNode> ast2 = parseDsl(dsl);
        NodeGraphRuntime r1 = new NodeGraphRuntime();
        r1.registerAllFromAnnotationRegistry();
        NodeGraphRuntime r2 = new NodeGraphRuntime();
        r2.registerAllFromAnnotationRegistry();
        ArrayMesh m1 = (ArrayMesh) r1.executeGraphResult(ast1, "dungeon", "mesh");
        ArrayMesh m2 = (ArrayMesh) r2.executeGraphResult(ast2, "dungeon", "mesh");
        assertEquals(m1.vertexCount(), m2.vertexCount());
        assertEquals(m1.faceCount(), m2.faceCount());
        float[] p1 = m1.copyPositions();
        float[] p2 = m2.copyPositions();
        assertEquals(p1.length, p2.length);
        for (int i = 0; i < p1.length; i++) {
            assertEquals(p1[i], p2[i], 0f, "position " + i);
        }
    }

    private static String loadDsl(String resource) throws Exception {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            return new String(Objects.requireNonNull(in, resource).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<PythonParser.ParsedNode> parseDsl(String dsl) {
        return new PythonParser(new PythonLexer(dsl)).parseGraph();
    }
}
