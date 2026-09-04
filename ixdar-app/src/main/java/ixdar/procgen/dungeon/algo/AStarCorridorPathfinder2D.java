package ixdar.procgen.dungeon.algo;

import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.nodes.api.Vector3Field;
import ixdar.procgen.dungeon.values.CellType;

/**
 * A* corridor carving over a 2D grid: for each edge of the rooms graph, runs grid A* between
 * the two room centers and marks traversed empty cells as HALLWAY. Edges carve into a shared
 * working grid in input order, so callers must supply edges deterministically.
 */
public final class AStarCorridorPathfinder2D {
    public static final int NUM_4 = 4;

    /** Default per-cell entry costs: reusing a hallway is cheap, empty space moderate, a room interior steep. */
    public static final double DEFAULT_HALLWAY_REUSE_COST = 1.0;
    public static final double DEFAULT_EMPTY_CELL_COST = 5.0;
    public static final double DEFAULT_THROUGH_ROOM_COST = 50.0;

    private static final int[] DX = { 1, -1, 0, 0 };
    private static final int[] DY = { 0, 0, 1, -1 };

    private AStarCorridorPathfinder2D() {
    }

    /**
     * Paint rooms onto a fresh grid then carve a corridor for every edge pair with grid A*,
     * marking each traversed EMPTY cell as {@link CellType#HALLWAY}.
     *
     * @param gridW       grid width in cells
     * @param gridH       grid height in cells
     * @param rooms       room points (cells inside each room AABB are marked {@link CellType#ROOM})
     * @param halfExtents per-vertex room half extents in dense vertex order
     * @param edgePairs         flat pairs of dense vertex indices to carve as corridors
     * @param hallwayReuseCost  entry cost of an existing HALLWAY cell
     * @param emptyCellCost     entry cost of an EMPTY cell
     * @param throughRoomCost   entry cost of a ROOM cell
     * @return row-major cell types for the fully-populated grid
     */
    public static CellType[] carve(int gridW, int gridH,
                                   MeshTopology rooms,
                                   Vector3Field halfExtents,
                                   int[] edgePairs,
                                   double hallwayReuseCost, double emptyCellCost, double throughRoomCost) {
        CellType[] cells = new CellType[gridW * gridH];
        Arrays.fill(cells, CellType.EMPTY);
        // Paint rooms first so pathfinder sees them as obstacles.
        int n = rooms.vertexCount();
        Vector3f p = new Vector3f();
        for (int i = 0; i < n; i++) {
            rooms.vertexPosition(rooms.vertexIdAt(i), p);
            int x0 = (int) Math.floor(p.x - halfExtents.getX(i));
            int x1 = (int) Math.ceil(p.x + halfExtents.getX(i));
            int y0 = (int) Math.floor(p.y - halfExtents.getY(i));
            int y1 = (int) Math.ceil(p.y + halfExtents.getY(i));
            for (int y = Math.max(0, y0); y < Math.min(gridH, y1); y++) {
                for (int x = Math.max(0, x0); x < Math.min(gridW, x1); x++) {
                    cells[y * gridW + x] = CellType.ROOM;
                }
            }
        }
        // Carve corridors for each edge in input order.
        Vector3f pa = new Vector3f();
        Vector3f pb = new Vector3f();
        for (int i = 0; i < edgePairs.length; i += 2) {
            rooms.vertexPosition(rooms.vertexIdAt(edgePairs[i]), pa);
            rooms.vertexPosition(rooms.vertexIdAt(edgePairs[i + 1]), pb);
            int sx = clamp((int) Math.floor(pa.x), 0, gridW - 1);
            int sy = clamp((int) Math.floor(pa.y), 0, gridH - 1);
            int tx = clamp((int) Math.floor(pb.x), 0, gridW - 1);
            int ty = clamp((int) Math.floor(pb.y), 0, gridH - 1);
            int[] path = aStar(cells, gridW, gridH, sx, sy, tx, ty,
                    hallwayReuseCost, emptyCellCost, throughRoomCost);
            if (path == null) continue;
            for (int idx : path) {
                if (cells[idx] == CellType.EMPTY) {
                    cells[idx] = CellType.HALLWAY;
                }
                // Cells already ROOM or HALLWAY are left as-is.
            }
        }
        return cells;
    }

    /**
     * 4-connected grid A* with Manhattan heuristic. Returns the full path as cell indices
     * (including start and end) or {@code null} if no path exists.
     *
     * @param cells   working grid (read-only here; cell type drives entry cost)
     * @param gridW   grid width in cells
     * @param gridH   grid height in cells
     * @param sx      start cell x
     * @param sy      start cell y
     * @param tx      target cell x
     * @param ty      target cell y
     * @param hallwayReuseCost entry cost of an existing HALLWAY cell
     * @param emptyCellCost    entry cost of an EMPTY cell
     * @param throughRoomCost  entry cost of a ROOM cell; the cheapest of the three drives the
     *                         admissible heuristic
     * @return cell indices from start to target (inclusive), or {@code null} if unreachable
     */
    static int[] aStar(CellType[] cells, int gridW, int gridH,
                       int sx, int sy, int tx, int ty,
                       double hallwayReuseCost, double emptyCellCost, double throughRoomCost) {
        int size = gridW * gridH;
        double[] gScore = new double[size];
        double[] fScore = new double[size];
        int[] parent = new int[size];
        boolean[] queued = new boolean[size];
        Arrays.fill(gScore, Double.POSITIVE_INFINITY);
        Arrays.fill(fScore, Double.POSITIVE_INFINITY);
        Arrays.fill(parent, -1);
        int startIdx = sy * gridW + sx;
        int targetIdx = ty * gridW + tx;
        gScore[startIdx] = 0;
        double hUnit = Math.min(hallwayReuseCost, Math.min(emptyCellCost, throughRoomCost));
        fScore[startIdx] = heuristic(sx, sy, tx, ty, hUnit);
        PriorityQueue<Integer> open = new PriorityQueue<>(cellOrder(fScore, gScore));
        open.add(startIdx);
        queued[startIdx] = true;
        while (!open.isEmpty()) {
            int cur = open.poll();
            queued[cur] = false;
            if (cur == targetIdx) break;
            int cx = cur % gridW;
            int cy = cur / gridW;
            for (int d = 0; d < NUM_4; d++) {
                int nx = cx + DX[d];
                int ny = cy + DY[d];
                if (nx < 0 || nx >= gridW || ny < 0 || ny >= gridH) continue;
                int nIdx = ny * gridW + nx;
                double step = enterCost(cells[nIdx], hallwayReuseCost, emptyCellCost, throughRoomCost);
                double ng = gScore[cur] + step;
                if (ng < gScore[nIdx]) {
                    if (queued[nIdx]) open.remove(Integer.valueOf(nIdx));
                    gScore[nIdx] = ng;
                    fScore[nIdx] = ng + heuristic(nx, ny, tx, ty, hUnit);
                    parent[nIdx] = cur;
                    open.add(nIdx);
                    queued[nIdx] = true;
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

    /**
     * Open-set order over cells whose keys live in the score arrays: estimated total, then cost
     * so far, then cell index. A cell is removed from the queue before its scores change, so the
     * keys of queued cells never move.
     */
    static Comparator<Integer> cellOrder(double[] fScore, double[] gScore) {
        return (left, right) -> {
            int c = Double.compare(fScore[left], fScore[right]);
            if (c != 0) {
                return c;
            }
            c = Double.compare(gScore[left], gScore[right]);
            return c != 0 ? c : Integer.compare(left, right);
        };
    }

    private static double enterCost(CellType cell,
            double hallwayReuseCost, double emptyCellCost, double throughRoomCost) {
        return switch (cell) {
            case HALLWAY -> hallwayReuseCost;
            case EMPTY   -> emptyCellCost;
            case ROOM    -> throughRoomCost;
            // Stair cells should not appear in 2D output but treat them as expensive if seen.
            case STAIR_UP, STAIR_DOWN -> throughRoomCost;
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
}
