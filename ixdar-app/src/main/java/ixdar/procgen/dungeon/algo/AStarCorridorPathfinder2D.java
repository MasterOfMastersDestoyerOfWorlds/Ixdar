package ixdar.procgen.dungeon.algo;

import java.util.Arrays;
import java.util.PriorityQueue;

import ixdar.procgen.dungeon.values.CellType;
import ixdar.procgen.dungeon.values.EdgeGraphValue;
import ixdar.procgen.dungeon.values.RoomListValue;
import ixdar.procgen.dungeon.values.RoomListValue.Room;
import ixdar.procgen.dungeon.values.TileGridValue;

/**
 * A* corridor carving over a 2D grid. Stage 4 of the vazgriz dungeon pipeline. For each edge
 * in the input MST+extras graph, runs grid A* from the center of one room to the other and
 * marks traversed empty cells as HALLWAY. Cost weights favor reusing existing corridors and
 * going around rooms rather than through them.
 *
 * <p>The pathfinder mutates a working grid in order of input edges. Later edges benefit from
 * the {@code hallwayReuseCost} incentive, so multiple corridors naturally consolidate onto a
 * shared trunk rather than cutting parallel paths. For this to be stable across runs, callers
 * must feed edges in a deterministic order (the output of {@link PrimMinimumSpanningTree} is
 * sorted by Delaunay index).
 */
public final class AStarCorridorPathfinder2D {

    /** Sensible defaults: reuse is cheap, empty is moderate, through-room is steep. */
    public static final CostWeights DEFAULT_WEIGHTS = new CostWeights(1.0, 5.0, 50.0);

    public record CostWeights(double hallwayReuseCost, double emptyCellCost, double throughRoomCost) { }

    private AStarCorridorPathfinder2D() {
    }

    /**
     * @param gridW    grid width in cells
     * @param gridH    grid height in cells
     * @param rooms    placed rooms (cells inside each room AABB are marked {@link CellType#ROOM})
     * @param mstEdges MST (+ extras) edges to carve into corridors
     * @param weights  per-cell-type entry costs
     */
    public static TileGridValue carve(int gridW, int gridH,
                                      RoomListValue rooms,
                                      EdgeGraphValue mstEdges,
                                      CostWeights weights) {
        CellType[] cells = new CellType[gridW * gridH];
        Arrays.fill(cells, CellType.EMPTY);
        // Paint rooms first so pathfinder sees them as obstacles.
        for (int i = 0; i < rooms.size(); i++) {
            Room r = rooms.get(i);
            int x0 = (int) Math.floor(r.minX());
            int x1 = (int) Math.ceil(r.maxX());
            int y0 = (int) Math.floor(r.minY());
            int y1 = (int) Math.ceil(r.maxY());
            for (int y = Math.max(0, y0); y < Math.min(gridH, y1); y++) {
                for (int x = Math.max(0, x0); x < Math.min(gridW, x1); x++) {
                    cells[y * gridW + x] = CellType.ROOM;
                }
            }
        }
        // Carve corridors for each edge in input order.
        for (int i = 0; i < mstEdges.edgeCount(); i++) {
            int[] e = mstEdges.edge(i);
            Room a = rooms.get(e[0]);
            Room b = rooms.get(e[1]);
            int sx = clamp((int) Math.floor(a.centerX()), 0, gridW - 1);
            int sy = clamp((int) Math.floor(a.centerY()), 0, gridH - 1);
            int tx = clamp((int) Math.floor(b.centerX()), 0, gridW - 1);
            int ty = clamp((int) Math.floor(b.centerY()), 0, gridH - 1);
            int[] path = aStar(cells, gridW, gridH, sx, sy, tx, ty, weights);
            if (path == null) continue;
            for (int idx : path) {
                if (cells[idx] == CellType.EMPTY) {
                    cells[idx] = CellType.HALLWAY;
                }
                // Cells already ROOM or HALLWAY are left as-is.
            }
        }
        return new TileGridValue(gridW, gridH, cells);
    }

    /**
     * 4-connected grid A* with Manhattan heuristic. Returns the full path as cell indices
     * (including start and end) or {@code null} if no path exists.
     */
    static int[] aStar(CellType[] cells, int gridW, int gridH,
                       int sx, int sy, int tx, int ty, CostWeights weights) {
        int size = gridW * gridH;
        double[] gScore = new double[size];
        int[] parent = new int[size];
        Arrays.fill(gScore, Double.POSITIVE_INFINITY);
        Arrays.fill(parent, -1);
        int startIdx = sy * gridW + sx;
        int targetIdx = ty * gridW + tx;
        gScore[startIdx] = 0;
        // Heuristic lower-bound per step is the hallway-reuse cost (cheapest move). Using a
        // smaller value keeps h admissible even if the target requires cutting through rooms.
        double hUnit = Math.min(weights.hallwayReuseCost(),
                Math.min(weights.emptyCellCost(), weights.throughRoomCost()));
        PriorityQueue<Entry> open = new PriorityQueue<>();
        open.add(new Entry(startIdx, 0.0, heuristic(sx, sy, tx, ty, hUnit)));
        while (!open.isEmpty()) {
            Entry cur = open.poll();
            if (cur.idx == targetIdx) break;
            if (cur.g > gScore[cur.idx]) continue; // outdated queue entry
            int cx = cur.idx % gridW;
            int cy = cur.idx / gridW;
            for (int d = 0; d < 4; d++) {
                int nx = cx + DX[d];
                int ny = cy + DY[d];
                if (nx < 0 || nx >= gridW || ny < 0 || ny >= gridH) continue;
                int nIdx = ny * gridW + nx;
                double step = enterCost(cells[nIdx], weights);
                double ng = cur.g + step;
                if (ng < gScore[nIdx]) {
                    gScore[nIdx] = ng;
                    parent[nIdx] = cur.idx;
                    open.add(new Entry(nIdx, ng, ng + heuristic(nx, ny, tx, ty, hUnit)));
                }
            }
        }
        if (parent[targetIdx] < 0 && startIdx != targetIdx) return null;
        // Reconstruct path end-to-start, then reverse.
        int[] tmp = new int[size];
        int len = 0;
        int node = targetIdx;
        while (node >= 0) {
            tmp[len++] = node;
            if (node == startIdx) break;
            node = parent[node];
        }
        int[] path = new int[len];
        for (int i = 0; i < len; i++) path[i] = tmp[len - 1 - i];
        return path;
    }

    private static double enterCost(CellType cell, CostWeights w) {
        return switch (cell) {
            case HALLWAY -> w.hallwayReuseCost();
            case EMPTY   -> w.emptyCellCost();
            case ROOM    -> w.throughRoomCost();
            // Stair cells should not appear in 2D output but treat them as expensive if seen.
            case STAIR_UP, STAIR_DOWN -> w.throughRoomCost();
        };
    }

    private static double heuristic(int ax, int ay, int bx, int by, double perStep) {
        return (Math.abs(ax - bx) + Math.abs(ay - by)) * perStep;
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private static final int[] DX = { 1, -1, 0, 0 };
    private static final int[] DY = { 0, 0, 1, -1 };

    private record Entry(int idx, double g, double f) implements Comparable<Entry> {
        @Override
        public int compareTo(Entry o) {
            int c = Double.compare(f, o.f);
            if (c != 0) return c;
            // Tie-break by g then idx so the PriorityQueue is fully deterministic.
            int c2 = Double.compare(g, o.g);
            if (c2 != 0) return c2;
            return Integer.compare(idx, o.idx);
        }
    }
}
