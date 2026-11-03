package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.ops.RepairEdges;
import ixdar.geometry.mesh.data.representation.EdgeKey;

/** The repair's edge table, built once and then edited face by face (CRAW-93). */
public final class RepairEdgesTest {

    private static final int GRID = 6;

    private static final int EDITS = 400;

    private static final long SEED = 93L;

    @Test
    void twoTrianglesShareOneManifoldEdge() {
        int[] corners = {0, 1, 2, 2, 1, 3};
        RepairEdges edges = RepairEdges.build(corners, new boolean[] {true, true}, 2, 4);

        assertEquals(5, edges.edgeCount(), "four rim edges and the shared diagonal");
        int shared = edges.find(1, 2);
        assertEquals(2, edges.useCount(shared), "both triangles use the diagonal");
        assertTrue(edges.manifold(shared), "they run it in opposite directions");
        assertEquals(1, edges.use(shared, 0), "the first triangle's slot files first");
        assertEquals(3, edges.use(shared, 1), "the second triangle's slot files second");
        assertEquals(RepairEdges.NO_EDGE, edges.find(0, 3), "no face spans the other diagonal");
    }

    @Test
    void aThirdFaceOnAnEdgeIsFiledInSlotOrderAndLeavesIt() {
        int[] corners = {0, 1, 2, 2, 1, 3, 1, 2, 4};
        boolean[] alive = {true, true, false};
        RepairEdges edges = RepairEdges.build(corners, alive, 3, 5);

        edges.addFace(2, corners, 5);
        int shared = edges.find(1, 2);
        assertEquals(3, edges.useCount(shared), "a fin makes the diagonal non-manifold");
        assertFalse(edges.manifold(shared), "three uses are not manifold");
        assertEquals(6, edges.use(shared, 2), "the fin's slot files last");

        edges.removeFace(0);
        assertEquals(2, edges.useCount(shared), "dropping a face takes its use off");
        assertEquals(3, edges.use(shared, 0), "the remaining uses close up in slot order");
        assertEquals(0, edges.useCount(edges.find(0, 1)), "an edge every face has left reports none");
    }

    @Test
    void editsLeaveTheSameUsesAsAFreshBuild() {
        int vertices = GRID * GRID;
        List<int[]> grid = new ArrayList<>();
        for (int row = 0; row + 1 < GRID; row++) {
            for (int column = 0; column + 1 < GRID; column++) {
                int corner = row * GRID + column;
                grid.add(new int[] {corner, corner + 1, corner + GRID});
                grid.add(new int[] {corner + 1, corner + GRID + 1, corner + GRID});
            }
        }
        int faces = grid.size();
        int[] corners = new int[faces * 3];
        for (int face = 0; face < faces; face++) {
            System.arraycopy(grid.get(face), 0, corners, face * 3, 3);
        }
        boolean[] alive = new boolean[faces];
        Arrays.fill(alive, true);
        RepairEdges edges = RepairEdges.build(corners, alive, faces, vertices);
        Random random = new Random(SEED);
        for (int edit = 0; edit < EDITS; edit++) {
            int face = random.nextInt(faces);
            if (alive[face]) {
                edges.removeFace(face);
                alive[face] = false;
            } else {
                // Re-added faces may come back turned over, as the orientation stage turns them.
                if (random.nextBoolean()) {
                    int swap = corners[face * 3 + 1];
                    corners[face * 3 + 1] = corners[face * 3 + 2];
                    corners[face * 3 + 2] = swap;
                }
                edges.addFace(face, corners, vertices);
                alive[face] = true;
            }
        }
        RepairEdges fresh = RepairEdges.build(corners, alive, faces, vertices);
        assertEquals(usesByKey(fresh), usesByKey(edges), "every edge has the uses a rebuild finds");
        for (int slot = 0; slot < faces * 3; slot++) {
            if (alive[slot / 3]) {
                assertEquals(fresh.forward(slot), edges.forward(slot), "slot " + slot + " direction");
                assertEquals(fresh.key(fresh.edgeOfSlot(slot)), edges.key(edges.edgeOfSlot(slot)),
                        "slot " + slot + " edge");
            }
        }
    }

    @Test
    void aFlipInPlaceLeavesTheTableARefileWould() {
        int vertices = GRID * GRID;
        int faces = (GRID - 1) * (GRID - 1) * 2;
        int[] corners = new int[faces * 3];
        int next = 0;
        for (int row = 0; row + 1 < GRID; row++) {
            for (int column = 0; column + 1 < GRID; column++) {
                int corner = row * GRID + column;
                int[] pair = {corner, corner + 1, corner + GRID, corner + 1, corner + GRID + 1, corner + GRID};
                System.arraycopy(pair, 0, corners, next, pair.length);
                next += pair.length;
            }
        }
        boolean[] alive = new boolean[faces];
        Arrays.fill(alive, true);
        RepairEdges inPlace = RepairEdges.build(corners, alive, faces, vertices);
        RepairEdges refiled = RepairEdges.build(corners, alive, faces, vertices);
        Random random = new Random(SEED);
        for (int edit = 0; edit < EDITS; edit++) {
            int face = random.nextInt(faces);
            int swap = corners[face * 3 + 1];
            corners[face * 3 + 1] = corners[face * 3 + 2];
            corners[face * 3 + 2] = swap;
            inPlace.flipFace(face, corners);
            refiled.removeFace(face);
            refiled.addFace(face, corners, vertices);
        }
        assertEquals(refiled.edgeCount(), inPlace.edgeCount(), "no edge is added");
        for (int edge = 0; edge < refiled.edgeCount(); edge++) {
            assertEquals(refiled.key(edge), inPlace.key(edge), "edge " + edge + " key");
            assertEquals(refiled.useCount(edge), inPlace.useCount(edge), "edge " + edge + " uses");
            for (int index = 0; index < refiled.useCount(edge); index++) {
                assertEquals(refiled.use(edge, index), inPlace.use(edge, index), "edge " + edge + " use " + index);
            }
            assertEquals(refiled.manifold(edge), inPlace.manifold(edge), "edge " + edge + " manifold");
        }
        for (int slot = 0; slot < faces * 3; slot++) {
            assertEquals(refiled.edgeOfSlot(slot), inPlace.edgeOfSlot(slot), "slot " + slot + " edge");
            assertEquals(refiled.forward(slot), inPlace.forward(slot), "slot " + slot + " direction");
        }
    }

    private static Map<Long, List<Integer>> usesByKey(RepairEdges edges) {
        Map<Long, List<Integer>> uses = new TreeMap<>();
        for (int edge = 0; edge < edges.edgeCount(); edge++) {
            if (edges.useCount(edge) == 0) {
                continue;
            }
            List<Integer> slots = new ArrayList<>();
            for (int index = 0; index < edges.useCount(edge); index++) {
                slots.add(edges.use(edge, index));
            }
            uses.put(edges.key(edge), slots);
        }
        assertTrue(uses.keySet().stream().allMatch(key -> EdgeKey.minVertex(key) <= EdgeKey.maxVertex(key)),
                "keys are undirected");
        return uses;
    }
}
