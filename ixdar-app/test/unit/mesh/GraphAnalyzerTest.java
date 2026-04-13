package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.graph.GraphAnalyzer;
import ixdar.geometry.mesh.graph.GraphAnalyzer.SeamAnalysis;
import ixdar.geometry.mesh.graph.GraphAnalyzer.SeamNode;
import ixdar.parsing.python.PythonParser;
import ixdar.parsing.python.PythonParser.NodeReference;
import ixdar.parsing.python.PythonParser.ParsedNode;

/**
 * Unit tests for GraphAnalyzer seam detection.
 */
public class GraphAnalyzerTest {

    /**
     * Test a linear chain with a seam.
     * 
     * Structure: node1 -> node2 -> node3 -> node4 (seam) -> node5 -> node6 -> node7
     * node3 produces MESH, and nodes 4,5,6,7 depend only on node3's output.
     */
    @Test
    public void testLinearChainWithSeam() {
        List<ParsedNode> graph = new ArrayList<>();
        
        // node1 -> node2 -> node3 (mesh producer) -> node4 -> node5 -> node6 -> node7
        ParsedNode n1 = createNode("n1", "node1", Map.of());
        ParsedNode n2 = createNode("n2", "node2", Map.of("input", ref("n1", "out")));
        ParsedNode n3 = createNode("n3", "meshProducer", Map.of("input", ref("n2", "out")));
        ParsedNode n4 = createNode("n4", "node4", Map.of("mesh", ref("n3", "mesh")));
        ParsedNode n5 = createNode("n5", "node5", Map.of("mesh", ref("n4", "mesh")));
        ParsedNode n6 = createNode("n6", "node6", Map.of("mesh", ref("n5", "mesh")));
        ParsedNode n7 = createNode("n7", "node7", Map.of("mesh", ref("n6", "mesh")));
        
        graph.addAll(List.of(n1, n2, n3, n4, n5, n6, n7));
        
        SeamAnalysis analysis = GraphAnalyzer.analyze(graph);
        
        // node3 should be the only seam (dominates n3,n4,n5,n6,n7 = 5 nodes >= 3)
        assertEquals(1, analysis.seams.size(), "Expected 1 seam");
        
        SeamNode seam = analysis.seams.get(0);
        assertEquals("n3", seam.nodeId, "Expected seam at n3");
        assertEquals("meshProducer", seam.nodeType, "Expected meshProducer type");
        
        // Seam should dominate n3,n4,n5,n6,n7 (5 nodes)
        assertEquals(5, seam.dominatedNodeIds.size(), "Expected 5 dominated nodes");
        assertTrue(seam.dominatedNodeIds.contains("n3"));
        assertTrue(seam.dominatedNodeIds.contains("n4"));
        assertTrue(seam.dominatedNodeIds.contains("n5"));
        assertTrue(seam.dominatedNodeIds.contains("n6"));
        assertTrue(seam.dominatedNodeIds.contains("n7"));
    }

    /**
     * Test a mesh processing chain with a seam.
     * 
     * Structure: cube -> loop_cut -> inset -> extrude (seam) -> smooth -> output
     */
    @Test
    public void testMeshProcessingChain() {
        List<ParsedNode> graph = new ArrayList<>();
        
        ParsedNode cube = createNode("cube", "cube", Map.of("size", 1.0));
        ParsedNode loopCut = createNode("loopCut", "loop_cut", Map.of("mesh", ref("cube", "mesh"), "axis", "Z", "cuts", 3));
        ParsedNode inset = createNode("inset", "inset_faces", Map.of("geometry", ref("loopCut", "geometry"), "inset", 0.1));
        ParsedNode extrude = createNode("extrude", "extrude_mesh", Map.of("geometry", ref("inset", "geometry"), "offset", 1.0));
        ParsedNode smooth = createNode("smooth", "subdivision_surface", Map.of("geometry", ref("extrude", "geometry"), "levels", 2));
        ParsedNode output = createNode("output", "transform_geometry", Map.of("geometry", ref("smooth", "geometry"), "scale", "<1,1,1>"));
        
        graph.addAll(List.of(cube, loopCut, inset, extrude, smooth, output));
        
        SeamAnalysis analysis = GraphAnalyzer.analyze(graph);
        
        // extrude should be the seam (dominates extrude, smooth, output = 3 nodes)
        assertEquals(1, analysis.seams.size(), "Expected 1 seam");
        
        SeamNode seam = analysis.seams.get(0);
        assertEquals("extrude", seam.nodeId, "Expected seam at extrude");
        assertEquals(3, seam.dominatedNodeIds.size(), "Expected 3 dominated nodes");
    }

    /**
     * Test multiple upstream branches - no seam.
     * 
     * Structure:
     *        node1 --\
     *                 node3 -- node5
     *        node2 --/
     * 
     * node5 depends on both node1 and node2 branches, so no seam.
     */
    @Test
    public void testMultipleUpstreamBranches() {
        List<ParsedNode> graph = new ArrayList<>();
        
        ParsedNode n1 = createNode("n1", "node1", Map.of());
        ParsedNode n2 = createNode("n2", "node2", Map.of());
        ParsedNode n3 = createNode("n3", "node3", Map.of("a", ref("n1", "out"), "b", ref("n2", "out")));
        ParsedNode n4 = createNode("n4", "node4", Map.of("mesh", ref("n3", "mesh")));
        ParsedNode n5 = createNode("n5", "node5", Map.of("mesh", ref("n4", "mesh")));
        
        graph.addAll(List.of(n1, n2, n3, n4, n5));
        
        SeamAnalysis analysis = GraphAnalyzer.analyze(graph);
        
        // No seam - n3 is dominated by both n1 and n2, but downstream depends on both branches
        assertEquals(0, analysis.seams.size(), "Expected no seams");
    }

    /**
     * Test small graph - no seam (dominated subgraph < 3).
     */
    @Test
    public void testSmallGraph() {
        List<ParsedNode> graph = new ArrayList<>();
        
        ParsedNode n1 = createNode("n1", "node1", Map.of());
        ParsedNode n2 = createNode("n2", "meshProducer", Map.of("input", ref("n1", "out")));
        ParsedNode n3 = createNode("n3", "node3", Map.of("mesh", ref("n2", "mesh")));
        
        graph.addAll(List.of(n1, n2, n3));
        
        SeamAnalysis analysis = GraphAnalyzer.analyze(graph);
        
        // n2 dominates only n2 and n3 (2 nodes < 3), so no seam
        assertEquals(0, analysis.seams.size(), "Expected no seams");
    }

    /**
     * Test multiple independent seams.
     * 
     * Structure:
     * branch1: a1 -> a2 (seam) -> a3
     * branch2: b1 -> b2 (seam) -> b3
     */
    @Test
    public void testMultipleIndependentSeams() {
        List<ParsedNode> graph = new ArrayList<>();
        
        // Branch 1
        ParsedNode a1 = createNode("a1", "node1", Map.of());
        ParsedNode a2 = createNode("a2", "meshProducer", Map.of("input", ref("a1", "out")));
        ParsedNode a3 = createNode("a3", "node3", Map.of("mesh", ref("a2", "mesh")));
        
        // Branch 2
        ParsedNode b1 = createNode("b1", "node1", Map.of());
        ParsedNode b2 = createNode("b2", "meshProducer", Map.of("input", ref("b1", "out")));
        ParsedNode b3 = createNode("b3", "node3", Map.of("mesh", ref("b2", "mesh")));
        
        graph.addAll(List.of(a1, a2, a3, b1, b2, b3));
        
        SeamAnalysis analysis = GraphAnalyzer.analyze(graph);
        
        // Both a2 and b2 should be seams
        assertEquals(2, analysis.seams.size(), "Expected 2 seams");
        
        SeamNode seamA = findSeamByNodeId(analysis, "a2");
        SeamNode seamB = findSeamByNodeId(analysis, "b2");
        
        assertNotNull(seamA, "Expected seam at a2");
        assertNotNull(seamB, "Expected seam at b2");
    }

    /**
     * Test node types map - verify all node types are handled.
     */
    @Test
    public void testNodeTypesMap() {
        List<ParsedNode> graph = new ArrayList<>();
        
        // Mix of node types
        ParsedNode n1 = createNode("n1", "floatNode", Map.of());
        ParsedNode n2 = createNode("n2", "intNode", Map.of("val", 42));
        ParsedNode n3 = createNode("n3", "meshProducer", Map.of("float", ref("n1", "out")));
        ParsedNode n4 = createNode("n4", "node4", Map.of("mesh", ref("n3", "mesh")));
        ParsedNode n5 = createNode("n5", "node5", Map.of("mesh", ref("n4", "mesh")));
        
        graph.addAll(List.of(n1, n2, n3, n4, n5));
        
        SeamAnalysis analysis = GraphAnalyzer.analyze(graph);
        
        // n3 should be the seam
        assertEquals(1, analysis.seams.size(), "Expected 1 seam");
        SeamNode seam = analysis.seams.get(0);
        assertEquals("n3", seam.nodeId);
    }

    /**
     * Test empty graph.
     */
    @Test
    public void testEmptyGraph() {
        SeamAnalysis analysis = GraphAnalyzer.analyze(new ArrayList<>());
        assertEquals(0, analysis.seams.size(), "Expected no seams in empty graph");
    }

    /**
     * Test single node graph.
     */
    @Test
    public void testSingleNodeGraph() {
        List<ParsedNode> graph = List.of(createNode("n1", "meshProducer", Map.of()));
        
        SeamAnalysis analysis = GraphAnalyzer.analyze(graph);
        
        // Single node dominates only itself (1 < 3), no seam
        assertEquals(0, analysis.seams.size(), "Expected no seams");
    }

    /**
     * Test complex graph with known seam.
     * 
     *        n1
     *         |
     *        n2
     *         |
     *        n3 (seam)
     *       /   \
     *     n4    n5
     *      |     |
     *     n6    n7
     *      \    /
     *       n8 (depends on n4,n6,n5,n7)
     * 
     * n3 dominates n3,n4,n5,n6,n7,n8 (6 nodes) and downstream depends only on n3.
     */
    @Test
    public void testComplexGraphWithKnownSeam() {
        List<ParsedNode> graph = new ArrayList<>();
        
        ParsedNode n1 = createNode("n1", "node1", Map.of());
        ParsedNode n2 = createNode("n2", "node2", Map.of("input", ref("n1", "out")));
        ParsedNode n3 = createNode("n3", "meshProducer", Map.of("input", ref("n2", "out")));
        ParsedNode n4 = createNode("n4", "node4", Map.of("mesh", ref("n3", "mesh")));
        ParsedNode n5 = createNode("n5", "node5", Map.of("mesh", ref("n3", "mesh")));
        ParsedNode n6 = createNode("n6", "node6", Map.of("mesh", ref("n4", "mesh")));
        ParsedNode n7 = createNode("n7", "node7", Map.of("mesh", ref("n5", "mesh")));
        ParsedNode n8 = createNode("n8", "node8", Map.of("mesh", ref("n6", "mesh")));
        
        graph.addAll(List.of(n1, n2, n3, n4, n5, n6, n7, n8));
        
        SeamAnalysis analysis = GraphAnalyzer.analyze(graph);
        
        // n3 should be the seam
        assertEquals(1, analysis.seams.size(), "Expected 1 seam");
        SeamNode seam = analysis.seams.get(0);
        assertEquals("n3", seam.nodeId);
        assertEquals(6, seam.dominatedNodeIds.size(), "Expected 6 dominated nodes");
    }

    private static ParsedNode createNode(String id, String type, Map<String, Object> args) {
        ParsedNode node = new ParsedNode();
        node.id = id;
        node.type = type;
        node.arguments = new HashMap<>(args);
        return node;
    }

    private static NodeReference ref(String nodeId, String portName) {
        return new NodeReference(nodeId, portName);
    }

    private static SeamNode findSeamByNodeId(SeamAnalysis analysis, String nodeId) {
        for (SeamNode seam : analysis.seams) {
            if (seam.nodeId.equals(nodeId)) {
                return seam;
            }
        }
        return null;
    }
}
