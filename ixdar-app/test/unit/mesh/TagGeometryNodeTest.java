package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.graph.GraphNodeContext;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.nodes.data.TagGeometryNode;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;

public class TagGeometryNodeTest {

    @Test
    public void tagsSingleLabel() {
        String dsl = "b = cube(size=1.0)\nt = tag_geometry(geometry=b.mesh, tags=\"skull\")";
        List<PythonParser.ParsedNode> ast = new PythonParser(new PythonLexer(dsl)).parseGraph();

        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        try {
            Object result = runtime.executeGraphResult(ast, "t", "geometry");
            assertNotNull(result);
            assertTrue(result instanceof GeometryBundle);
            GeometryBundle gb = (GeometryBundle) result;
            Map<String, boolean[]> tags = TagGeometryNode.getTags(gb);
            assertNotNull(tags);
            assertTrue(tags.containsKey("skull"));
            assertEquals(gb.mesh().vertexCount(), tags.get("skull").length);
            for (boolean v : tags.get("skull")) {
                assertTrue(v);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void tagsMultipleLabels() {
        String dsl = "b = cube(size=1.0)\nt = tag_geometry(geometry=b.mesh, tags=\"arm,hand,thumb\")";
        List<PythonParser.ParsedNode> ast = new PythonParser(new PythonLexer(dsl)).parseGraph();

        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        try {
            Object result = runtime.executeGraphResult(ast, "t", "geometry");
            GeometryBundle gb = (GeometryBundle) result;
            Map<String, boolean[]> tags = TagGeometryNode.getTags(gb);
            assertNotNull(tags);
            assertEquals(3, tags.size());
            assertTrue(tags.containsKey("arm"));
            assertTrue(tags.containsKey("hand"));
            assertTrue(tags.containsKey("thumb"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void emptyTagsPassThrough() {
        String dsl = "b = cube(size=1.0)\nt = tag_geometry(geometry=b.mesh, tags=\"\")";
        List<PythonParser.ParsedNode> ast = new PythonParser(new PythonLexer(dsl)).parseGraph();

        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        try {
            Object result = runtime.executeGraphResult(ast, "t", "geometry");
            GeometryBundle gb = (GeometryBundle) result;
            Map<String, boolean[]> tags = TagGeometryNode.getTags(gb);
            assertNull(tags);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void tagsMergeFromUpstream() {
        String dsl = """
            b = cube(size=1.0)
            t1 = tag_geometry(geometry=b.mesh, tags="arm")
            t2 = tag_geometry(geometry=t1.geometry, tags="hand,thumb")
            """;
        List<PythonParser.ParsedNode> ast = new PythonParser(new PythonLexer(dsl)).parseGraph();

        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        try {
            Object result = runtime.executeGraphResult(ast, "t2", "geometry");
            GeometryBundle gb = (GeometryBundle) result;
            Map<String, boolean[]> tags = TagGeometryNode.getTags(gb);
            assertNotNull(tags);
            assertEquals(3, tags.size());
            assertTrue(tags.containsKey("arm"));
            assertTrue(tags.containsKey("hand"));
            assertTrue(tags.containsKey("thumb"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
