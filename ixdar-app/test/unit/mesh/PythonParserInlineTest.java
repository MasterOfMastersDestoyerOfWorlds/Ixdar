package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;

public class PythonParserInlineTest {

    private static List<PythonParser.ParsedNode> parse(String dsl) {
        return new PythonParser(new PythonLexer(dsl)).parseGraph();
    }

    @Test
    public void simpleInlineWithExplicitPort() {
        List<PythonParser.ParsedNode> nodes = parse("a = foo(x=bar(n=1).out)");
        assertEquals(2, nodes.size());
        // First node is the synthetic inline
        assertEquals("bar", nodes.get(0).type);
        assertTrue(nodes.get(0).id.startsWith("__inline_"));
        // Second node references the synthetic
        PythonParser.NodeReference ref = assertInstanceOf(
                PythonParser.NodeReference.class, nodes.get(1).arguments.get("x"));
        assertEquals(nodes.get(0).id, ref.nodeId);
        assertEquals("out", ref.portName);
    }

    @Test
    public void inlineDefaultsToResultPort() {
        List<PythonParser.ParsedNode> nodes = parse("a = foo(x=bar(n=1))");
        assertEquals(2, nodes.size());
        PythonParser.NodeReference ref = assertInstanceOf(
                PythonParser.NodeReference.class, nodes.get(1).arguments.get("x"));
        assertEquals("result", ref.portName);
    }

    @Test
    public void nestedThreeDeep() {
        List<PythonParser.ParsedNode> nodes = parse(
                "a = foo(x=bar(y=baz(n=1).out).result)");
        assertEquals(3, nodes.size());
        // Order: baz, bar, foo
        assertEquals("baz", nodes.get(0).type);
        assertEquals("bar", nodes.get(1).type);
        assertEquals("foo", nodes.get(2).type);
    }

    @Test
    public void multipleInlineArgs() {
        List<PythonParser.ParsedNode> nodes = parse(
                "a = foo(x=bar(n=1).out, y=baz(m=2).out)");
        assertEquals(3, nodes.size());
        // Both synthetic nodes before 'a'
        assertEquals("foo", nodes.get(2).type);
        assertEquals("a", nodes.get(2).id);
    }

    @Test
    public void mixedInlineAndRegularRefs() {
        List<PythonParser.ParsedNode> nodes = parse(
                "b = thing()\na = foo(x=b.out, y=bar(n=1).result)");
        // thing, __inline_0 (bar), foo
        assertEquals(3, nodes.size());
        assertEquals("thing", nodes.get(0).type);
        assertEquals("b", nodes.get(0).id);
        assertEquals("bar", nodes.get(1).type);
        assertEquals("foo", nodes.get(2).type);
    }

    @Test
    public void inlineCallWithNoArgs() {
        List<PythonParser.ParsedNode> nodes = parse("a = foo(x=bar())");
        assertEquals(2, nodes.size());
        assertEquals("bar", nodes.get(0).type);
        assertTrue(nodes.get(0).arguments.isEmpty());
    }

    @Test
    public void existingDslStillParses() {
        // Flattened style (no inline) must still work
        String dsl = "b = cube(size=1.0)\ns = subdivision_surface(mesh=b.mesh, levels=3)";
        List<PythonParser.ParsedNode> nodes = parse(dsl);
        assertEquals(2, nodes.size());
        assertEquals("cube", nodes.get(0).type);
        assertEquals("subdivision_surface", nodes.get(1).type);
    }
}
