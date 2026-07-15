package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.annotations.meshnode.MapNodeContext;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.primitives.TorusMeshNode;

/**
 * The torus is the substrate the quad-layout stages are tested on, so what matters here
 * is not that it looks like a torus but that it is topologically the surface the later
 * tests assume: closed, boundary-free, and of genus 1.
 *
 * <p>Genus 1 means {@code V - E + F = 0}, and that single equation is the reason the
 * torus was chosen. Every downstream stage — the carve, the operators, the T-junction
 * extension — must preserve the Euler characteristic of the surface, so a substrate with
 * a known, non-trivial characteristic turns "did this stage corrupt the complex?" into
 * one cheap integer comparison. A sphere would not do: its characteristic is 2 and it has
 * poles, and a plane would not do either, because it has a boundary and would drag in the
 * free-boundary machinery we deliberately do not implement.
 */
class TorusMeshNodeTest {

    /** Divisions the long way around, chosen coprime-ish to the tube count to catch index mix-ups. */
    private static final int MAJOR_SEGMENTS = 16;

    /** Divisions around the tube. */
    private static final int MINOR_SEGMENTS = 9;

    @Test
    void quadTorusIsClosedAndGenusOne() {
        HalfEdgeMesh mesh = torus(false);

        int expectedVertices = MAJOR_SEGMENTS * MINOR_SEGMENTS;
        int expectedFaces = MAJOR_SEGMENTS * MINOR_SEGMENTS;
        int expectedEdges = 2 * MAJOR_SEGMENTS * MINOR_SEGMENTS;

        assertEquals(expectedVertices, mesh.vertexCount(), "vertex count");
        assertEquals(expectedFaces, mesh.faceCount(), "quad count");
        assertEquals(expectedEdges, mesh.edgeCount(), "edge count");
        assertEquals(0, eulerCharacteristic(mesh), "V - E + F must be 0 for a genus-1 surface");
    }

    @Test
    void triangulatedTorusIsClosedAndGenusOne() {
        HalfEdgeMesh mesh = torus(true);

        assertEquals(MAJOR_SEGMENTS * MINOR_SEGMENTS, mesh.vertexCount(), "vertex count");
        assertEquals(2 * MAJOR_SEGMENTS * MINOR_SEGMENTS, mesh.faceCount(),
                "each quad splits into two triangles");
        assertEquals(3 * MAJOR_SEGMENTS * MINOR_SEGMENTS, mesh.edgeCount(),
                "triangulating adds one diagonal per quad");
        assertEquals(0, eulerCharacteristic(mesh),
                "triangulating must not change the surface's topology");
    }

    /**
     * A closed surface has no boundary, so every edge is shared by exactly two faces.
     * This is what makes the Euler check meaningful: on a mesh with holes the same
     * characteristic could arise for the wrong reason.
     */
    @Test
    void torusHasNoBoundaryEdges() {
        HalfEdgeMesh mesh = torus(true);
        for (int index = 0; index < mesh.edgeCount(); index++) {
            int edgeId = mesh.edgeIdAt(index);
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            int twin = mesh.halfEdgeTwin(halfEdge);
            assertTrue(mesh.halfEdgeFace(halfEdge) >= 0 && mesh.halfEdgeFace(twin) >= 0,
                    "edge " + edgeId + " is on a boundary, so the surface is not closed");
        }
    }

    /**
     * Build a torus through the node's own port interface, so the test exercises the
     * primitive as the graph would drive it rather than reaching past it.
     *
     * @param triangulate whether to split each quad into two triangles
     * @return the generated mesh
     */
    private static HalfEdgeMesh torus(boolean triangulate) {
        TorusMeshNode node = new TorusMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput(TorusMeshNode.MAJOR_SEGMENTS_2, MAJOR_SEGMENTS);
        context.setInput(TorusMeshNode.MINOR_SEGMENTS_2, MINOR_SEGMENTS);
        context.setInput(TorusMeshNode.TRIANGULATE_2, triangulate);
        node.evaluate(context);
        return context.getOutput(TorusMeshNode.MESH_2, HalfEdgeMesh.class);
    }

    /**
     * The surface's Euler characteristic, {@code V - E + F}.
     *
     * @param mesh mesh to measure
     * @return the characteristic, which is {@code 2 - 2g} for a closed surface of genus g
     */
    private static int eulerCharacteristic(HalfEdgeMesh mesh) {
        return mesh.vertexCount() - mesh.edgeCount() + mesh.faceCount();
    }
}
