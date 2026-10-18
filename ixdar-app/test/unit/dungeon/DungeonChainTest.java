package unit.dungeon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.joml.Vector3f;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.nodegraph.field.BoolField;
import ixdar.nodegraph.field.IntField;
import ixdar.nodegraph.field.Vector3Field;
import ixdar.procgen.dungeon.algo.DungeonGrids;
import ixdar.procgen.dungeon.values.CellType;

/**
 * The 2D and 3D dungeon graphs run end to end through the same nodes: room points, a Delaunay
 * edge mesh, an MST selection, a tile grid or lattice, and a non-empty final mesh.
 */
class DungeonChainTest {

    private static final String ROOMS = "rooms";
    private static final String TRIANGULATION = "tri";
    private static final String SPANNING_TREE = "mst";
    private static final String SELECTION = "selection";
    private static final String TILES = "tiles";
    private static final String DUNGEON = "dungeon";
    private static final String ROOMS_PUBLISHED = "random_rooms publishes a geometry bundle";
    private static final String HALF_EXTENT_PER_ROOM = "one half extent per room vertex";
    private static final String FLAG_PER_EDGE = "one flag per delaunay edge";
    private static final String MESH_HAS_VERTICES = "the dungeon mesh has vertices";
    private static final String MESH_HAS_FACES = "the dungeon mesh has faces";
    private static final String START_ROOM_CENTERED = "the start room straddles the grid center";

    @Test
    void the2dChainFlowsPointsEdgesSelectionAndGridFaces() {
        NodeGraphRuntime runtime = NodeGraphRuntime.executeResource("dsl/dungeon_2d.dsl", Map.of());

        GeometryBundle rooms = (GeometryBundle) runtime.getNodeOutput(ROOMS, NodeGraphRuntime.GEOMETRY);
        assertNotNull(rooms, ROOMS_PUBLISHED);
        int n = rooms.mesh().vertexCount();
        assertTrue(n >= 3, "enough rooms placed to triangulate, got " + n);
        Vector3Field halfExtents = DungeonGrids.halfExtents(rooms);
        assertEquals(n, halfExtents.length(), HALF_EXTENT_PER_ROOM);
        Vector3f p = new Vector3f();
        for (int i = 0; i < n; i++) {
            rooms.mesh().vertexPosition(rooms.mesh().vertexIdAt(i), p);
            assertEquals(0f, p.z, 0f, "planar rooms sit at z = 0");
            assertEquals(0f, halfExtents.getZ(i), 0f, "planar half extents have zero z");
        }

        GeometryBundle tri = (GeometryBundle) runtime.getNodeOutput(TRIANGULATION, NodeGraphRuntime.GEOMETRY);
        assertNotNull(tri, "delaunay_graph publishes a geometry bundle");
        assertEquals(n, tri.mesh().vertexCount(), "delaunay preserves the room vertices");
        int edges = tri.mesh().edgeCount();
        assertTrue(edges >= n - 1, "a connected triangulation has at least n-1 edges, got " + edges);
        assertNotNull(tri.slots().get(DungeonGrids.HALF_EXTENT), "half extents carry through delaunay");

        BoolField selection = (BoolField) runtime.getNodeOutput(SPANNING_TREE, SELECTION);
        assertNotNull(selection, "minimum_spanning_tree publishes a selection");
        assertEquals(edges, selection.length(), FLAG_PER_EDGE);
        int kept = 0;
        for (int i = 0; i < edges; i++) {
            if (selection.get(i)) kept++;
        }
        assertTrue(kept >= n - 1, "the spanning tree alone keeps n-1 edges, got " + kept);
        assertTrue(kept <= edges, "the selection keeps at most every candidate edge");

        GeometryBundle tiles = (GeometryBundle) runtime.getNodeOutput(TILES, NodeGraphRuntime.GEOMETRY);
        assertNotNull(tiles, "astar_corridors publishes a geometry bundle");
        assertFalse(DungeonGrids.isLattice(tiles), "grid_d = 0 makes astar_corridors emit a quad grid");
        int[] dims = DungeonGrids.gridDims(tiles);
        assertEquals(30, dims[0], "grid width matches the graph literal");
        assertEquals(30, dims[1], "grid height matches the graph literal");
        assertEquals(30 * 30, tiles.mesh().faceCount(), "one grid face per cell");
        IntField cellTypes = (IntField) tiles.slots().get(DungeonGrids.CELL_TYPE);
        assertEquals(30 * 30, cellTypes.length(), "one cell type per face");
        CellType[] cells = DungeonGrids.gridCells(tiles, dims[0], dims[1]);
        int roomCells = 0;
        int hallwayCells = 0;
        for (CellType c : cells) {
            if (c == CellType.ROOM) roomCells++;
            if (c == CellType.HALLWAY) hallwayCells++;
        }
        assertTrue(roomCells > 0, "rooms are painted into the grid");
        assertTrue(hallwayCells > 0, "corridors are carved into the grid");

        ArrayMesh mesh = (ArrayMesh) ((GeometryBundle) runtime.getNodeOutput(DUNGEON, NodeGraphRuntime.MESH)).mesh();
        assertNotNull(mesh, "dungeon_grid_to_mesh publishes the final mesh from the grid");
        assertTrue(mesh.vertexCount() > 0, MESH_HAS_VERTICES);
        assertTrue(mesh.faceCount() > 0, MESH_HAS_FACES);
    }

    @Test
    void the3dChainFlowsPointsEdgesSelectionAndLattice() {
        NodeGraphRuntime runtime = NodeGraphRuntime.executeResource("dsl/dungeon_3d.dsl", Map.of());

        GeometryBundle rooms = (GeometryBundle) runtime.getNodeOutput(ROOMS, NodeGraphRuntime.GEOMETRY);
        assertNotNull(rooms, ROOMS_PUBLISHED);
        MeshTopology roomsMesh = rooms.mesh();
        int n = roomsMesh.vertexCount();
        assertTrue(n >= 3, "enough rooms placed to triangulate, got " + n);
        assertEquals(n, DungeonGrids.halfExtents(rooms).length(), HALF_EXTENT_PER_ROOM);
        Vector3f start = roomsMesh.vertexPosition(roomsMesh.vertexIdAt(0), new Vector3f());
        assertEquals(15f, start.x, 1f, START_ROOM_CENTERED);
        assertEquals(15f, start.z, 1f, START_ROOM_CENTERED);

        GeometryBundle tri = (GeometryBundle) runtime.getNodeOutput(TRIANGULATION, NodeGraphRuntime.GEOMETRY);
        int edges = tri.mesh().edgeCount();
        assertTrue(edges >= n - 1, "a connected complex has at least n-1 edges, got " + edges);

        BoolField selection = (BoolField) runtime.getNodeOutput(SPANNING_TREE, SELECTION);
        assertEquals(edges, selection.length(), FLAG_PER_EDGE);
        int kept = 0;
        for (int i = 0; i < edges; i++) {
            if (selection.get(i)) kept++;
        }
        assertTrue(kept >= n - 1, "the spanning tree alone keeps n-1 edges, got " + kept);

        GeometryBundle tiles = (GeometryBundle) runtime.getNodeOutput(TILES, NodeGraphRuntime.GEOMETRY);
        assertTrue(DungeonGrids.isLattice(tiles), "grid_d > 0 makes astar_corridors emit a lattice");
        int[] dims = DungeonGrids.latticeDims(tiles);
        assertEquals(30, dims[0], "lattice width matches the graph literal");
        assertEquals(5, dims[1], "lattice height matches the graph literal");
        assertEquals(30, dims[2], "lattice depth matches the graph literal");
        assertEquals(30 * 5 * 30, tiles.mesh().vertexCount(), "one lattice vertex per cell");
        CellType[] cells = DungeonGrids.latticeCells(tiles, dims[0], dims[1], dims[2]);
        int roomCells = 0;
        int hallwayCells = 0;
        for (CellType c : cells) {
            if (c == CellType.ROOM) roomCells++;
            if (c == CellType.HALLWAY) hallwayCells++;
        }
        assertTrue(roomCells > 0, "rooms are painted into the lattice");
        assertTrue(hallwayCells > 0, "corridors are carved into the lattice");

        ArrayMesh mesh = (ArrayMesh) ((GeometryBundle) runtime.getNodeOutput(DUNGEON, NodeGraphRuntime.MESH)).mesh();
        assertNotNull(mesh, "dungeon_grid_to_mesh publishes the final mesh from the lattice");
        assertTrue(mesh.vertexCount() > 0, MESH_HAS_VERTICES);
        assertTrue(mesh.faceCount() > 0, MESH_HAS_FACES);
    }
}
