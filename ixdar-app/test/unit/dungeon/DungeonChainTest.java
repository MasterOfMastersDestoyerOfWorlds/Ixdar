package unit.dungeon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.joml.Vector3f;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.nodes.api.BoolField;
import ixdar.geometry.mesh.nodes.api.IntField;
import ixdar.geometry.mesh.nodes.api.Vector3Field;
import ixdar.procgen.dungeon.algo.DungeonGrids;
import ixdar.procgen.dungeon.values.CellType;

/**
 * End-to-end dungeon chain over the general shapes: rooms as a point cloud with half-extent
 * attributes, delaunay as a mesh whose wire edges are the graph, MST as a per-edge BOOLEAN
 * selection, tile grids as grid faces (2D) / a point lattice (3D) with a per-cell int
 * attribute, and a final non-empty ArrayMesh.
 */
class DungeonChainTest {

    @Test
    void the2dChainFlowsPointsEdgesSelectionAndGridFaces() {
        NodeGraphRuntime runtime = NodeGraphRuntime.executeResource("dsl/dungeon_2d.dsl", Map.of());

        GeometryBundle rooms = (GeometryBundle) runtime.getNodeOutput("rooms", "geometry");
        assertNotNull(rooms, "random_rooms publishes a geometry bundle");
        int n = rooms.mesh().vertexCount();
        assertTrue(n >= 3, "enough rooms placed to triangulate, got " + n);
        Vector3Field halfExtents = DungeonGrids.halfExtents(rooms);
        assertEquals(n, halfExtents.length(), "one half extent per room vertex");
        Vector3f p = new Vector3f();
        for (int i = 0; i < n; i++) {
            rooms.mesh().vertexPosition(rooms.mesh().vertexIdAt(i), p);
            assertEquals(0f, p.z, 0f, "planar rooms sit at z = 0");
            assertEquals(0f, halfExtents.getZ(i), 0f, "planar half extents have zero z");
        }

        GeometryBundle tri = (GeometryBundle) runtime.getNodeOutput("tri", "geometry");
        assertNotNull(tri, "delaunay_graph publishes a geometry bundle");
        assertEquals(n, tri.mesh().vertexCount(), "delaunay preserves the room vertices");
        int edges = tri.mesh().edgeCount();
        assertTrue(edges >= n - 1, "a connected triangulation has at least n-1 edges, got " + edges);
        assertNotNull(tri.slots().get(DungeonGrids.HALF_EXTENT), "half extents carry through delaunay");

        BoolField selection = (BoolField) runtime.getNodeOutput("mst", "selection");
        assertNotNull(selection, "minimum_spanning_tree publishes a selection");
        assertEquals(edges, selection.length(), "one flag per delaunay edge");
        int kept = 0;
        for (int i = 0; i < edges; i++) {
            if (selection.get(i)) kept++;
        }
        assertTrue(kept >= n - 1, "the spanning tree alone keeps n-1 edges, got " + kept);
        assertTrue(kept <= edges, "the selection keeps at most every candidate edge");

        GeometryBundle tiles = (GeometryBundle) runtime.getNodeOutput("tiles", "geometry");
        assertNotNull(tiles, "astar_corridors publishes a geometry bundle");
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

        ArrayMesh mesh = (ArrayMesh) ((GeometryBundle) runtime.getNodeOutput("dungeon", "mesh")).mesh();
        assertNotNull(mesh, "dungeon_grid_to_mesh publishes the final mesh");
        assertTrue(mesh.vertexCount() > 0, "the dungeon mesh has vertices");
        assertTrue(mesh.faceCount() > 0, "the dungeon mesh has faces");
    }

    @Test
    void the3dChainFlowsPointsEdgesSelectionAndLattice() {
        NodeGraphRuntime runtime = NodeGraphRuntime.executeResource("dsl/dungeon_3d.dsl", Map.of());

        GeometryBundle rooms = (GeometryBundle) runtime.getNodeOutput("rooms", "geometry");
        assertNotNull(rooms, "random_rooms publishes a geometry bundle");
        MeshTopology roomsMesh = rooms.mesh();
        int n = roomsMesh.vertexCount();
        assertTrue(n >= 3, "enough rooms placed to triangulate, got " + n);
        assertEquals(n, DungeonGrids.halfExtents(rooms).length(), "one half extent per room vertex");
        Vector3f start = roomsMesh.vertexPosition(roomsMesh.vertexIdAt(0), new Vector3f());
        assertEquals(15f, start.x, 1f, "the start room straddles the grid center");
        assertEquals(15f, start.z, 1f, "the start room straddles the grid center");

        GeometryBundle tri = (GeometryBundle) runtime.getNodeOutput("tri", "geometry");
        int edges = tri.mesh().edgeCount();
        assertTrue(edges >= n - 1, "a connected complex has at least n-1 edges, got " + edges);

        BoolField selection = (BoolField) runtime.getNodeOutput("mst", "selection");
        assertEquals(edges, selection.length(), "one flag per delaunay edge");
        int kept = 0;
        for (int i = 0; i < edges; i++) {
            if (selection.get(i)) kept++;
        }
        assertTrue(kept >= n - 1, "the spanning tree alone keeps n-1 edges, got " + kept);

        GeometryBundle tiles = (GeometryBundle) runtime.getNodeOutput("tiles", "geometry");
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

        ArrayMesh mesh = (ArrayMesh) ((GeometryBundle) runtime.getNodeOutput("dungeon", "mesh")).mesh();
        assertNotNull(mesh, "dungeon_grid_to_mesh_3d publishes the final mesh");
        assertTrue(mesh.vertexCount() > 0, "the dungeon mesh has vertices");
        assertTrue(mesh.faceCount() > 0, "the dungeon mesh has faces");
    }
}
