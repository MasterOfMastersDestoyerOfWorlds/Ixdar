package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import ixdar.annotations.meshnode.MapNodeContext;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.primitives.DiskMeshNode;

/**
 * The polar disk is the substrate of the fan-collapse fixture, which relies on its documented
 * ordering — center id 0, then ring-major/angular-minor — and on the center vertex carrying
 * valence {@code angular_segments}, which no grid interior vertex can.
 */
class DiskMeshNodeTest {

    /** Concentric rings, distinct from the angular count to catch index mix-ups. */
    private static final int RINGS = 5;

    /** Vertices per ring, and the required center valence. */
    private static final int ANGULAR_SEGMENTS = 24;

    @Test
    void triangulatedDiskHasDiskTopologyAndCounts() {
        HalfEdgeMesh mesh = disk(true);

        assertEquals(1 + RINGS * ANGULAR_SEGMENTS, mesh.vertexCount(), "vertex count");
        assertEquals(ANGULAR_SEGMENTS + (RINGS - 1) * ANGULAR_SEGMENTS * 2, mesh.faceCount(),
                "a fan to ring one plus two triangles per annulus cell");
        assertEquals(1, eulerCharacteristic(mesh), "V - E + F must be 1 for a disk");
    }

    @Test
    void quadDiskKeepsDiskTopology() {
        HalfEdgeMesh mesh = disk(false);

        assertEquals(1 + RINGS * ANGULAR_SEGMENTS, mesh.vertexCount(), "vertex count");
        assertEquals(ANGULAR_SEGMENTS + (RINGS - 1) * ANGULAR_SEGMENTS, mesh.faceCount(),
                "a fan to ring one plus one quad per annulus cell");
        assertEquals(1, eulerCharacteristic(mesh), "V - E + F must be 1 for a disk");
    }

    @Test
    void centerVertexCarriesTheFullValence() {
        HalfEdgeMesh mesh = disk(true);
        assertEquals(ANGULAR_SEGMENTS, mesh.vertexEdgeCount(0),
                "the center vertex is id 0 and touches one edge per angular segment");
    }

    @Test
    void boundaryIsTheOuterRing() {
        HalfEdgeMesh mesh = disk(true);
        int boundaryEdges = 0;
        for (int index = 0; index < mesh.edgeCount(); index++) {
            int halfEdge = mesh.edgeHalfEdge(mesh.edgeIdAt(index));
            if (mesh.halfEdgeFace(halfEdge) < 0
                    || mesh.halfEdgeFace(mesh.halfEdgeTwin(halfEdge)) < 0) {
                boundaryEdges++;
            }
        }
        assertEquals(ANGULAR_SEGMENTS, boundaryEdges,
                "the only boundary is the outermost ring");
    }

    /**
     * Build a disk through the node's own port interface, as the fixture drives it.
     *
     * @param triangulate whether annulus quads split into two triangles
     * @return the generated mesh
     */
    private static HalfEdgeMesh disk(boolean triangulate) {
        DiskMeshNode node = new DiskMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput(DiskMeshNode.RINGS.name, RINGS);
        context.setInput(DiskMeshNode.ANGULAR_SEGMENTS.name, ANGULAR_SEGMENTS);
        context.setInput(DiskMeshNode.TRIANGULATE.name, triangulate);
        node.evaluate(context);
        return context.getOutput(DiskMeshNode.MESH.name, HalfEdgeMesh.class);
    }

    /**
     * The surface's Euler characteristic, {@code V - E + F}.
     *
     * @param mesh mesh to measure
     * @return the characteristic, 1 for a disk
     */
    private static int eulerCharacteristic(HalfEdgeMesh mesh) {
        return mesh.vertexCount() - mesh.edgeCount() + mesh.faceCount();
    }
}
