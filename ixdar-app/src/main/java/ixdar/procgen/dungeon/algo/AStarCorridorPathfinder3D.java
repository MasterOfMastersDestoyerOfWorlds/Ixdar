package ixdar.procgen.dungeon.algo;

import java.util.Arrays;
import java.util.PriorityQueue;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.nodes.api.Vector3Field;
import ixdar.procgen.dungeon.values.CellType;

/**
 * 3D corridor carving: {@link AStarCorridorPathfinder2D} movement per floor plus a stair move
 * changing floor by one (Y is the floor axis). A stair runs from {@code (x, y, z)} to
 * {@code (x + 2*dx, y±1, z + 2*dz)}; its two intermediate cells must be EMPTY and become
 * STAIR_UP / STAIR_DOWN on commit.
 */
public final class AStarCorridorPathfinder3D {
    public static final int NUM_4 = 4;
    public static final int NUM_3 = 3;

    /**
     * Stair build cost — a stair carve consumes 3 cell-traversals (two intermediates plus the
     * destination) so the cost is the sum of those entries plus a small build premium.
     */
    public static final double STAIR_BUILD_PREMIUM = 2.0;

    private static final int[] DX = { 1, -1, 0, 0 };
    private static final int[] DZ = { 0, 0, 1, -1 };

    private AStarCorridorPathfinder3D() {
    }

    /**
     * Paint rooms into a fresh 3D grid then carve a corridor for every edge pair with 3D A*,
     * marking traversed cells as {@link CellType#HALLWAY} and inserting STAIR_UP/STAIR_DOWN
     * pairs wherever the path changes floor.
     *
     * @param gridW       grid width in cells (X)
     * @param gridH       grid height in floors (Y)
     * @param gridD       grid depth in cells (Z)
     * @param rooms       room points (cells inside each room AABB are marked {@link CellType#ROOM})
     * @param halfExtents per-vertex room half extents in dense vertex order
     * @param edgePairs         flat pairs of dense vertex indices to carve as corridors
     * @param hallwayReuseCost  entry cost of an existing HALLWAY or STAIR cell
     * @param emptyCellCost     entry cost of an EMPTY cell
     * @param throughRoomCost   entry cost of a ROOM cell
     * @return cell types indexed {@code x + gridW * (z + gridD * y)}, with rooms, hallways, and stairs
     */
    public static CellType[] carve(int gridW, int gridH, int gridD,
                                   MeshTopology rooms,
                                   Vector3Field halfExtents,
                                   int[] edgePairs,
                                   double hallwayReuseCost, double emptyCellCost, double throughRoomCost) {
        int total = gridW * gridH * gridD;
        CellType[] cells = new CellType[total];
        Arrays.fill(cells, CellType.EMPTY);
        // Paint rooms.
        int n = rooms.vertexCount();
        Vector3f p = new Vector3f();
        for (int i = 0; i < n; i++) {
            rooms.vertexPosition(rooms.vertexIdAt(i), p);
            int x0 = (int) Math.floor(p.x - halfExtents.getX(i));
            int x1 = (int) Math.ceil(p.x + halfExtents.getX(i));
            int y0 = (int) Math.floor(p.y - halfExtents.getY(i));
            int y1 = (int) Math.ceil(p.y + halfExtents.getY(i));
            int z0 = (int) Math.floor(p.z - halfExtents.getZ(i));
            int z1 = (int) Math.ceil(p.z + halfExtents.getZ(i));
            for (int y = Math.max(0, y0); y < Math.min(gridH, y1); y++) {
                for (int z = Math.max(0, z0); z < Math.min(gridD, z1); z++) {
                    for (int x = Math.max(0, x0); x < Math.min(gridW, x1); x++) {
                        cells[idx(x, y, z, gridW, gridD)] = CellType.ROOM;
                    }
                }
            }
        }
        // Carve corridors per edge.
        Vector3f pa = new Vector3f();
        Vector3f pb = new Vector3f();
        for (int i = 0; i < edgePairs.length; i += 2) {
            rooms.vertexPosition(rooms.vertexIdAt(edgePairs[i]), pa);
            rooms.vertexPosition(rooms.vertexIdAt(edgePairs[i + 1]), pb);
            int sx = clamp((int) Math.floor(pa.x), 0, gridW - 1);
            int sy = clamp((int) Math.floor(pa.y), 0, gridH - 1);
            int sz = clamp((int) Math.floor(pa.z), 0, gridD - 1);
            int tx = clamp((int) Math.floor(pb.x), 0, gridW - 1);
            int ty = clamp((int) Math.floor(pb.y), 0, gridH - 1);
            int tz = clamp((int) Math.floor(pb.z), 0, gridD - 1);
            int[][] path = aStar(cells, gridW, gridH, gridD, sx, sy, sz, tx, ty, tz,
                    hallwayReuseCost, emptyCellCost, throughRoomCost);
            if (path == null) continue;
            applyPath(cells, gridW, gridD, path);
        }
        return cells;
    }

    private static int idx(int x, int y, int z, int gridW, int gridD) {
        return x + gridW * (z + gridD * y);
    }

    /**
     * Returns the path as an array of {@code (x, y, z, kind)} steps where kind=0 is a
     * horizontal step and kind=1 is the destination cell of a stair (the two intermediate
     * cells of the stair are inferred during {@link #applyPath}).
     */
    static int[][] aStar(CellType[] cells, int gridW, int gridH, int gridD,
                         int sx, int sy, int sz, int tx, int ty, int tz,
                         double hallwayReuseCost, double emptyCellCost, double throughRoomCost) {
        int total = gridW * gridH * gridD;
        double[] g = new double[total];
        double[] f = new double[total];
        int[] parent = new int[total];
        int[] kindIn = new int[total];
        boolean[] queued = new boolean[total];
        Arrays.fill(g, Double.POSITIVE_INFINITY);
        Arrays.fill(f, Double.POSITIVE_INFINITY);
        Arrays.fill(parent, -1);
        int start = idx(sx, sy, sz, gridW, gridD);
        int target = idx(tx, ty, tz, gridW, gridD);
        g[start] = 0;
        double hUnit = Math.min(hallwayReuseCost, Math.min(emptyCellCost, throughRoomCost));
        f[start] = heuristic(sx, sy, sz, tx, ty, tz, hUnit);
        PriorityQueue<Integer> open = new PriorityQueue<>(AStarCorridorPathfinder2D.cellOrder(f, g));
        open.add(start);
        queued[start] = true;
        while (!open.isEmpty()) {
            int cur = open.poll();
            queued[cur] = false;
            if (cur == target) break;
            int cx = cur % gridW;
            int rest = cur / gridW;
            int cz = rest % gridD;
            int cy = rest / gridD;
            // Horizontal cardinal moves.
            for (int d = 0; d < NUM_4; d++) {
                int nx = cx + DX[d];
                int nz = cz + DZ[d];
                if (nx < 0 || nx >= gridW || nz < 0 || nz >= gridD) continue;
                int nIdx = idx(nx, cy, nz, gridW, gridD);
                double step = enterCost(cells[nIdx], hallwayReuseCost, emptyCellCost, throughRoomCost);
                relax(open, queued, g, f, parent, kindIn, cur, nIdx, step, 0,
                        nx, cy, nz, tx, ty, tz, hUnit);
            }
            // Stair moves: up one floor, two cells horizontal.
            for (int d = 0; d < NUM_4; d++) {
                int dx = DX[d], dz = DZ[d];
                if (cy + 1 < gridH) trySairStep(cells, gridW, gridH, gridD,
                        open, queued, g, f, parent, kindIn, cur,
                        hallwayReuseCost, emptyCellCost, throughRoomCost,
                        cx, cy, cz, dx, dz, +1, tx, ty, tz, hUnit);
                if (cy - 1 >= 0)    trySairStep(cells, gridW, gridH, gridD,
                        open, queued, g, f, parent, kindIn, cur,
                        hallwayReuseCost, emptyCellCost, throughRoomCost,
                        cx, cy, cz, dx, dz, -1, tx, ty, tz, hUnit);
            }
        }
        if (parent[target] < 0 && start != target) return null;
        // Reconstruct path: list of (x, y, z, kind) where kind=1 means destination of stair.
        int[][] tmp = new int[total][];
        int len = 0;
        int node = target;
        while (node >= 0) {
            int x = node % gridW;
            int rest = node / gridW;
            int z = rest % gridD;
            int y = rest / gridD;
            tmp[len++] = new int[] { x, y, z, kindIn[node] };
            if (node == start) break;
            node = parent[node];
        }
        int[][] path = new int[len][];
        for (int i = 0; i < len; i++) path[i] = tmp[len - 1 - i];
        return path;
    }

    private static void trySairStep(
            CellType[] cells, int gridW, int gridH, int gridD,
            PriorityQueue<Integer> open, boolean[] queued, double[] g, double[] f,
            int[] parent, int[] kindIn, int cur,
            double hallwayReuseCost, double emptyCellCost, double throughRoomCost,
            int cx, int cy, int cz, int dx, int dz, int dy,
            int tx, int ty, int tz, double hUnit) {
        // Intermediate cells: lower at (cx+dx, cy, cz+dz), upper at (cx+dx, cy+dy, cz+dz).
        int mx = cx + dx, mz = cz + dz;
        int destX = cx + 2 * dx, destZ = cz + 2 * dz;
        int destY = cy + dy;
        if (destX < 0 || destX >= gridW || destZ < 0 || destZ >= gridD) return;
        if (mx < 0 || mx >= gridW || mz < 0 || mz >= gridD) return;
        int lowerIdx = idx(mx, cy, mz, gridW, gridD);
        int upperIdx = idx(mx, destY, mz, gridW, gridD);
        // Both intermediates must be currently EMPTY or already a STAIR (allow reuse).
        if (!stairBuildable(cells[lowerIdx]) || !stairBuildable(cells[upperIdx])) return;
        int destIdx = idx(destX, destY, destZ, gridW, gridD);
        // Cost: sum of entering both intermediates + dest + premium.
        double step = enterCost(cells[lowerIdx], hallwayReuseCost, emptyCellCost, throughRoomCost)
                    + enterCost(cells[upperIdx], hallwayReuseCost, emptyCellCost, throughRoomCost)
                    + enterCost(cells[destIdx], hallwayReuseCost, emptyCellCost, throughRoomCost)
                    + STAIR_BUILD_PREMIUM;
        relax(open, queued, g, f, parent, kindIn, cur, destIdx, step, 1,
                destX, destY, destZ, tx, ty, tz, hUnit);
    }

    private static boolean stairBuildable(CellType c) {
        return c == CellType.EMPTY || c == CellType.STAIR_UP || c == CellType.STAIR_DOWN;
    }

    private static void relax(PriorityQueue<Integer> open, boolean[] queued, double[] g, double[] f,
                              int[] parent, int[] kindIn, int cur, int nIdx, double step, int kind,
                              int nx, int ny, int nz, int tx, int ty, int tz, double hUnit) {
        double ng = g[cur] + step;
        if (ng < g[nIdx]) {
            if (queued[nIdx]) open.remove(Integer.valueOf(nIdx));
            g[nIdx] = ng;
            f[nIdx] = ng + heuristic(nx, ny, nz, tx, ty, tz, hUnit);
            parent[nIdx] = cur;
            kindIn[nIdx] = kind;
            open.add(nIdx);
            queued[nIdx] = true;
        }
    }

    private static void applyPath(CellType[] cells, int gridW, int gridD, int[][] path) {
        for (int i = 0; i < path.length; i++) {
            int x = path[i][0], y = path[i][1], z = path[i][2], kind = path[i][NUM_3];
            int dIdx = idx(x, y, z, gridW, gridD);
            if (kind == 1 && i > 0) {
                // Stair destination — derive intermediates from the previous step.
                int px = path[i - 1][0], py = path[i - 1][1], pz = path[i - 1][2];
                int dx = (x - px) / 2;
                int dz = (z - pz) / 2;
                int dy = y - py;
                int lowerIdx = idx(px + dx, py, pz + dz, gridW, gridD);
                int upperIdx = idx(px + dx, py + dy, pz + dz, gridW, gridD);
                if (cells[lowerIdx] == CellType.EMPTY) cells[lowerIdx] = CellType.STAIR_UP;
                if (cells[upperIdx] == CellType.EMPTY) cells[upperIdx] = CellType.STAIR_DOWN;
            }
            if (cells[dIdx] == CellType.EMPTY) cells[dIdx] = CellType.HALLWAY;
        }
    }

    private static double enterCost(CellType cell,
            double hallwayReuseCost, double emptyCellCost, double throughRoomCost) {
        return switch (cell) {
            case HALLWAY, STAIR_UP, STAIR_DOWN -> hallwayReuseCost;
            case EMPTY -> emptyCellCost;
            case ROOM  -> throughRoomCost;
        };
    }

    private static double heuristic(int ax, int ay, int az, int bx, int by, int bz, double perStep) {
        // Manhattan in 3D; vertical moves cost a single step (stairs span 2 horizontal + 1 vertical
        // in a single A* hop, so this remains an admissible lower bound).
        return (Math.abs(ax - bx) + Math.abs(ay - by) + Math.abs(az - bz)) * perStep;
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
