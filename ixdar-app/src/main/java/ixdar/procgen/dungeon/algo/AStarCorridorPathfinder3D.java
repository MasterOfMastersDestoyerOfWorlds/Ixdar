package ixdar.procgen.dungeon.algo;

import java.util.Arrays;
import java.util.PriorityQueue;

import ixdar.procgen.dungeon.algo.AStarCorridorPathfinder2D.CostWeights;
import ixdar.procgen.dungeon.values.CellType;
import ixdar.procgen.dungeon.values.EdgeGraphValue;
import ixdar.procgen.dungeon.values.RoomListValue3D;
import ixdar.procgen.dungeon.values.RoomListValue3D.Room;
import ixdar.procgen.dungeon.values.TileGridValue3D;

/**
 * 3D corridor carving: {@link AStarCorridorPathfinder2D} movement per floor plus a stair move
 * that changes floor by exactly one.
 *
 * <p>A stair runs from {@code (x, y, z)} to {@code (x + 2*dx, y±1, z + 2*dz)}; both intermediate
 * cells must be EMPTY, and on commit become {@link CellType#STAIR_UP} and
 * {@link CellType#STAIR_DOWN}.
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
     * Paint rooms into a fresh 3D grid then carve a corridor for every input edge with 3D A*,
     * marking traversed cells as {@link CellType#HALLWAY} and inserting STAIR_UP/STAIR_DOWN
     * pairs wherever the path changes floor.
     *
     * @param gridW    grid width in cells (X)
     * @param gridH    grid height in floors (Y)
     * @param gridD    grid depth in cells (Z)
     * @param rooms    placed rooms (cells inside each room AABB are marked {@link CellType#ROOM})
     * @param mstEdges MST (+ extras) edges to carve into corridors
     * @param weights  per-cell-type entry costs
     * @return a new tile grid with rooms, hallways, and stairs
     */
    public static TileGridValue3D carve(int gridW, int gridH, int gridD,
                                        RoomListValue3D rooms,
                                        EdgeGraphValue mstEdges,
                                        CostWeights weights) {
        int total = gridW * gridH * gridD;
        CellType[] cells = new CellType[total];
        Arrays.fill(cells, CellType.EMPTY);
        // Paint rooms.
        for (int i = 0; i < rooms.size(); i++) {
            Room r = rooms.get(i);
            int x0 = (int) Math.floor(r.minX()), x1 = (int) Math.ceil(r.maxX());
            int y0 = (int) Math.floor(r.minY()), y1 = (int) Math.ceil(r.maxY());
            int z0 = (int) Math.floor(r.minZ()), z1 = (int) Math.ceil(r.maxZ());
            for (int y = Math.max(0, y0); y < Math.min(gridH, y1); y++) {
                for (int z = Math.max(0, z0); z < Math.min(gridD, z1); z++) {
                    for (int x = Math.max(0, x0); x < Math.min(gridW, x1); x++) {
                        cells[idx(x, y, z, gridW, gridD)] = CellType.ROOM;
                    }
                }
            }
        }
        // Carve corridors per MST edge.
        for (int i = 0; i < mstEdges.edgeCount(); i++) {
            int[] e = mstEdges.edge(i);
            Room a = rooms.get(e[0]);
            Room b = rooms.get(e[1]);
            int sx = clamp((int) Math.floor(a.centerX()), 0, gridW - 1);
            int sy = clamp((int) Math.floor(a.centerY()), 0, gridH - 1);
            int sz = clamp((int) Math.floor(a.centerZ()), 0, gridD - 1);
            int tx = clamp((int) Math.floor(b.centerX()), 0, gridW - 1);
            int ty = clamp((int) Math.floor(b.centerY()), 0, gridH - 1);
            int tz = clamp((int) Math.floor(b.centerZ()), 0, gridD - 1);
            int[][] path = aStar(cells, gridW, gridH, gridD, sx, sy, sz, tx, ty, tz, weights);
            if (path == null) continue;
            applyPath(cells, gridW, gridD, path);
        }
        return new TileGridValue3D(gridW, gridH, gridD, cells);
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
                         CostWeights weights) {
        int total = gridW * gridH * gridD;
        double[] g = new double[total];
        int[] parent = new int[total];
        int[] kindIn = new int[total];
        Arrays.fill(g, Double.POSITIVE_INFINITY);
        Arrays.fill(parent, -1);
        int start = idx(sx, sy, sz, gridW, gridD);
        int target = idx(tx, ty, tz, gridW, gridD);
        g[start] = 0;
        double hUnit = Math.min(weights.hallwayReuseCost(),
                Math.min(weights.emptyCellCost(), weights.throughRoomCost()));
        PriorityQueue<Entry> open = new PriorityQueue<>();
        open.add(new Entry(start, 0.0, heuristic(sx, sy, sz, tx, ty, tz, hUnit)));
        while (!open.isEmpty()) {
            Entry cur = open.poll();
            if (cur.idx == target) break;
            if (cur.gScore > g[cur.idx]) continue;
            int cx = cur.idx % gridW;
            int rest = cur.idx / gridW;
            int cz = rest % gridD;
            int cy = rest / gridD;
            // Horizontal cardinal moves.
            for (int d = 0; d < NUM_4; d++) {
                int nx = cx + DX[d];
                int nz = cz + DZ[d];
                if (nx < 0 || nx >= gridW || nz < 0 || nz >= gridD) continue;
                int nIdx = idx(nx, cy, nz, gridW, gridD);
                double step = enterCost(cells[nIdx], weights);
                relax(open, g, parent, kindIn, cur, nIdx, step, 0,
                        nx, cy, nz, tx, ty, tz, hUnit);
            }
            // Stair moves: up one floor, two cells horizontal.
            for (int d = 0; d < NUM_4; d++) {
                int dx = DX[d], dz = DZ[d];
                if (cy + 1 < gridH) trySairStep(cells, gridW, gridH, gridD,
                        open, g, parent, kindIn, cur, weights,
                        cx, cy, cz, dx, dz, +1, tx, ty, tz, hUnit);
                if (cy - 1 >= 0)    trySairStep(cells, gridW, gridH, gridD,
                        open, g, parent, kindIn, cur, weights,
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
            PriorityQueue<Entry> open, double[] g, int[] parent, int[] kindIn,
            Entry cur, CostWeights weights,
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
        double step = enterCost(cells[lowerIdx], weights)
                    + enterCost(cells[upperIdx], weights)
                    + enterCost(cells[destIdx], weights)
                    + STAIR_BUILD_PREMIUM;
        relax(open, g, parent, kindIn, cur, destIdx, step, 1,
                destX, destY, destZ, tx, ty, tz, hUnit);
    }

    private static boolean stairBuildable(CellType c) {
        return c == CellType.EMPTY || c == CellType.STAIR_UP || c == CellType.STAIR_DOWN;
    }

    private static void relax(PriorityQueue<Entry> open, double[] g, int[] parent, int[] kindIn,
                              Entry cur, int nIdx, double step, int kind,
                              int nx, int ny, int nz, int tx, int ty, int tz, double hUnit) {
        double ng = cur.gScore + step;
        if (ng < g[nIdx]) {
            g[nIdx] = ng;
            parent[nIdx] = cur.idx;
            kindIn[nIdx] = kind;
            open.add(new Entry(nIdx, ng, ng + heuristic(nx, ny, nz, tx, ty, tz, hUnit)));
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

    private static double enterCost(CellType cell, CostWeights w) {
        return switch (cell) {
            case HALLWAY, STAIR_UP, STAIR_DOWN -> w.hallwayReuseCost();
            case EMPTY -> w.emptyCellCost();
            case ROOM  -> w.throughRoomCost();
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

    private record Entry(int idx, double gScore, double f) implements Comparable<Entry> {
        /**
         * Order by f, then g, then idx so the priority queue is fully deterministic.
         *
         * @param o other entry to compare against
         * @return negative / zero / positive per {@link Comparable}
         */
        @Override
        public int compareTo(Entry o) {
            int c = Double.compare(f, o.f);
            if (c != 0) return c;
            int c2 = Double.compare(gScore, o.gScore);
            if (c2 != 0) return c2;
            return Integer.compare(idx, o.idx);
        }
    }
}
