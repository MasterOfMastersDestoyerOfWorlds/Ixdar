package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.nodegraph.nodes.data.SceneModelNode;
import ixdar.nodegraph.nodes.quadlayout.QuadLayoutNode;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessUv;
import ixdar.geometry.mesh.quadlayout.embedding.LayoutEmbedding;
import ixdar.geometry.mesh.quadlayout.embedding.model.ArcEdgePath;
import ixdar.geometry.mesh.quadlayout.embedding.model.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.model.EmbeddedArc;

/**
 * The carve maps T-mesh elements one-to-one onto mesh elements: distinct copy vertices per node,
 * distinct edge paths per arc, and no element claimed twice.
 *
 * <p>See also: LCBK19 Section 6.1
 */
class CarveOneToOneTest {

    private static final String OFF_PROPERTY = "tmeshPipeline.off";
    private static final String DEFAULT_OFF =
            "test/resources/quadlayout/figure_7/sphere_base_in_tri.off";
    private static final double ALPHA_RADIANS = Math.toRadians(15.0);

    @Test
    void carveMapsTMeshElementsOntoMeshElementsOneToOne() throws Exception {
        String offPath = System.getProperty(OFF_PROPERTY, DEFAULT_OFF);
        ArrayMesh arrayMesh = MeshLoader.load(offPath);
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());

        NodeGraphRuntime layout = new NodeGraphRuntime(QuadLayoutNode.overSceneModel());
        layout.graphInputs.put(SceneModelNode.BINDING, GeometryBundle.ofMesh(mesh));
        layout.setOverride(QuadLayoutNode.ALPHA_DEGREES_OVERRIDE, (float) Math.toDegrees(ALPHA_RADIANS));
        ArcNetwork arrangement = (ArcNetwork) layout.pull(
                QuadLayoutNode.sceneOutput(QuadLayoutNode.SKELETON));
        SeamlessUv seamless = (SeamlessUv) layout.pull(QuadLayoutNode.sceneOutput(QuadLayoutNode.SEAMLESS));
        LayoutEmbedding embedding = new LayoutEmbedding();
        embedding.build(arrangement, seamless);
        EmbeddedMeshTopology topology = embedding.topology;

        int claimedSourceVertices = 0;
        int freeSourceVertices = 0;
        for (int sourceVertexId = 0; sourceVertexId < mesh.vertexCount(); sourceVertexId++) {
            int copyVertex = topology.copyVertexForSourceVertexId(sourceVertexId);
            if (copyVertex == EmbeddedMeshTopology.UNCLAIMED) {
                continue;
            }
            if (topology.ownerArcByCopyVertex[copyVertex] == EmbeddedMeshTopology.UNCLAIMED
                    && topology.ownerNodeByCopyVertex[copyVertex] == EmbeddedMeshTopology.UNCLAIMED) {
                freeSourceVertices++;
            } else {
                claimedSourceVertices++;
            }
        }
        System.out.printf("[carve-density] %s | input V=%d -> copy V=%d (%.2fx)"
                        + " | nodes=%d arcs=%d | source vertices claimed=%d free=%d%n",
                offPath, mesh.vertexCount(), topology.copy.vertexCount(),
                topology.copy.vertexCount() / (double) mesh.vertexCount(),
                arrangement.nodes.size(), arrangement.arcs.size(),
                claimedSourceVertices, freeSourceVertices);

        assertEquals(0, topology.claimConflictCount,
                "no copy element may be claimed by two T-mesh elements");

        Map<Integer, Integer> nodeByVertex = new HashMap<>();
        for (int nodeId = 0; nodeId < embedding.vertexIdByNode.length; nodeId++) {
            int copyVertex = embedding.vertexIdByNode[nodeId];
            if (copyVertex == EmbeddedMeshTopology.UNCLAIMED) {
                continue;
            }
            Integer previous = nodeByVertex.put(copyVertex, nodeId);
            assertEquals(null, previous, "T-mesh nodes " + previous + " and " + nodeId
                    + " share copy vertex " + copyVertex);
            assertEquals(nodeId, topology.ownerNodeByCopyVertex[copyVertex],
                    "node " + nodeId + " must own the copy vertex it was placed on");
        }

        Map<Integer, Integer> arcByEdge = new HashMap<>();
        Map<Integer, Integer> arcByInteriorVertex = new HashMap<>();
        for (EmbeddedArc arc : arrangement.arcs) {
            ArcEdgePath path = embedding.pathByArc[arc.arcId];
            List<Integer> vertices = path.copyVertexPath;
            assertEquals(vertices.size(), new HashSet<>(vertices).size(),
                    "arc " + arc.arcId + " revisits a copy vertex");

            for (int index = 1; index < vertices.size(); index++) {
                int edgeId = topology.copy.edgeBetween(vertices.get(index - 1), vertices.get(index));
                assertNotEquals(EmbeddedMeshTopology.UNCLAIMED, edgeId,
                        "arc " + arc.arcId + " hop " + index + " has no copy edge");
                assertEquals(arc.arcId, topology.ownerArcByCopyEdge[edgeId],
                        "arc " + arc.arcId + " must own every edge of its path");
                Integer previous = arcByEdge.put(edgeId, arc.arcId);
                assertEquals(null, previous, "arcs " + previous + " and " + arc.arcId
                        + " share copy edge " + edgeId);
            }

            for (int index = 1; index < vertices.size() - 1; index++) {
                int copyVertex = vertices.get(index);
                assertEquals(EmbeddedMeshTopology.UNCLAIMED,
                        topology.ownerNodeByCopyVertex[copyVertex],
                        "arc " + arc.arcId + " passes through copy vertex " + copyVertex
                                + " that a T-mesh node owns, so an arc touches a node it does not"
                                + " end at");
                Integer previous = arcByInteriorVertex.put(copyVertex, arc.arcId);
                assertEquals(null, previous, "arcs " + previous + " and " + arc.arcId
                        + " both pass through copy vertex " + copyVertex + ", so they touch");
            }
        }

        assertTrue(arcByEdge.size() >= arrangement.arcs.size(),
                "every arc contributes at least one edge to the mapping");
    }
}
