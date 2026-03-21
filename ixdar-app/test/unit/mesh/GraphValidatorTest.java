package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.graph.GraphValidator;
import ixdar.geometry.mesh.nodes.modifier.SubdivisionMeshNode;
import ixdar.geometry.mesh.nodes.primitives.CubeMeshNode;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;

public class GraphValidatorTest {

    @Test
    public void validMeshGraphHasNoErrors() {
        String dsl = "b = cube(size=1.0)\n" + "s = subdivision_surface(mesh=b.mesh, levels=3)\n";
        PythonLexer lexer = new PythonLexer(dsl);
        PythonParser parser = new PythonParser(lexer);
        List<PythonParser.ParsedNode> ast = parser.parseGraph();

        Map<String, Class<? extends MeshNode>> registry = new HashMap<>();
        registry.put("cube", CubeMeshNode.class);
        registry.put("subdivision_surface", SubdivisionMeshNode.class);

        List<String> errors = GraphValidator.validate(ast, registry);
        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    public void unknownOutputPortProducesError() {
        String dsl = "b = cube(size=1.0)\n" + "s = subdivision_surface(mesh=b.unknown_port, levels=3)\n";
        PythonLexer lexer = new PythonLexer(dsl);
        PythonParser parser = new PythonParser(lexer);
        List<PythonParser.ParsedNode> ast = parser.parseGraph();

        Map<String, Class<? extends MeshNode>> registry = new HashMap<>();
        registry.put("cube", CubeMeshNode.class);
        registry.put("subdivision_surface", SubdivisionMeshNode.class);

        List<String> errors = GraphValidator.validate(ast, registry);
        assertFalse(errors.isEmpty());
    }

    @Test
    public void randomValueLinkEmitsWarning() {
        String dsl = "r = random_value(seed=1, min=0, max=1, mode=2.0)\n"
                + "k = integer_math(a=r.int_out, b=1, mode=3.0)\n";
        PythonLexer lexer = new PythonLexer(dsl);
        PythonParser parser = new PythonParser(lexer);
        List<PythonParser.ParsedNode> ast = parser.parseGraph();

        Map<String, Class<? extends MeshNode>> registry = new HashMap<>();
        registry.put("random_value", ixdar.geometry.mesh.nodes.math.RandomValueNode.class);
        registry.put("integer_math", ixdar.geometry.mesh.nodes.math.IntegerMathNode.class);

        List<String> warnings = GraphValidator.validateWithRandomValueWarnings(ast, registry);
        assertFalse(warnings.isEmpty());
    }
}
