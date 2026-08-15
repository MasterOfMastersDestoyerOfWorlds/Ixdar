package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import ixdar.annotations.meshnode.MapNodeContext;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.primitives.GridMeshNode;

/**
 * The grid is the substrate of the planar layout fixtures, which rely on it being a disk
 * ({@code V - E + F = 1}) with the documented row-major vertex ordering, triangulated when
 * asked so the quad-layout stages can consume it.
 */
class GridMeshNodeTest {

    /** Tiles along U, distinct from V to catch index mix-ups. */
    private static final int U_TILES = 7;

    /** Tiles along V. */
    private static final int V_TILES = 5;

    @Test
    void quadGridIsADisk() {
        HalfEdgeMesh mesh = grid(false);

        int expectedVertices = (U_TILES + 1) * (V_TILES + 1);
        int expectedFaces = U_TILES * V_TILES;
        int expectedEdges = U_TILES * (V_TILES + 1) + V_TILES * (U_TILES + 1);
        assertEquals(expectedVertices, mesh.vertexCount(), "vertex count");
        assertEquals(expectedFaces, mesh.faceCount(), "quad count");
        assertEquals(expectedEdges, mesh.edgeCount(), "edge count");
        assertEquals(1, eulerCharacteristic(mesh), "V - E + F must be 1 for a disk");
    }

    @Test
    void triangulatedGridIsADiskOfTriangles() {
        HalfEdgeMesh mesh = grid(true);

        assertEquals((U_TILES + 1) * (V_TILES + 1), mesh.vertexCount(), "vertex count");
        assertEquals(2 * U_TILES * V_TILES, mesh.faceCount(),
                "each quad splits into two triangles");
        assertEquals(U_TILES * (V_TILES + 1) + V_TILES * (U_TILES + 1) + U_TILES * V_TILES,
                mesh.edgeCount(), "triangulating adds one diagonal per quad");
        assertEquals(1, eulerCharacteristic(mesh),
                "triangulating must not change the surface's topology");
    }

    @Test
    void boundaryEdgeCountMatchesThePerimeter() {
        HalfEdgeMesh mesh = grid(true);
        int boundaryEdges = 0;
        for (int index = 0; index < mesh.edgeCount(); index++) {
            int halfEdge = mesh.edgeHalfEdge(mesh.edgeIdAt(index));
            if (mesh.halfEdgeFace(halfEdge) < 0
                    || mesh.halfEdgeFace(mesh.halfEdgeTwin(halfEdge)) < 0) {
                boundaryEdges++;
            }
        }
        assertEquals(2 * (U_TILES + V_TILES), boundaryEdges,
                "the boundary is the grid's perimeter");
    }

    /**
     * Build a grid through the node's own port interface, as the fixtures drive it.
     *
     * @param triangulate whether to split each quad into two triangles
     * @return the generated mesh
     */
    private static HalfEdgeMesh grid(boolean triangulate) {
        GridMeshNode node = new GridMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput(GridMeshNode.U_TILES_2, U_TILES);
        context.setInput(GridMeshNode.V_TILES_2, V_TILES);
        context.setInput(GridMeshNode.TRIANGULATE_2, triangulate);
        node.evaluate(context);
        return context.getOutput(GridMeshNode.MESH_2, HalfEdgeMesh.class);
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
