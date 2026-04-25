package ixdar.geometry.mesh.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walk a patch's boundary into an ordered ring and slice the ring at
 * corners (turn angle > {@code T_CORNER_RAD}). Used by PATCH-16 to
 * convert a mesh patch's outline into the four sides a Coons fit
 * expects.
 *
 * <p>PATCH-20 extended the walker to handle patches with holes
 * (multiple disconnected boundary rings) and with vertices that have
 * more than two boundary neighbours (patch pinch points, three-way
 * junctions). For multi-ring patches we keep only the outermost ring —
 * the one with the most boundary vertices — on the assumption that
 * inner rings bound concavity-carved holes we don't want to fit. For
 * multi-neighbour vertices the walker picks the next step that makes
 * the sharpest turn consistent with a simple ring; this breaks ties
 * deterministically but may still miss pathological boundaries.
 */
public final class PatchBoundaryWalker {

    // Must match SemanticPatchDecomposer.T_CORNER_RAD. Duplicated here
    // because that constant is private; keep the two in lockstep.
    private static final float T_CORNER_RAD = 1.22f;

    private PatchBoundaryWalker() {}

    public record BoundarySides(List<int[]> sides, int[] cornerVertices) {}

    /**
     * Extract the ordered boundary polyline sides for one patch.
     *
     * @return the sides (4) + corner vertices (4), or {@code null} if
     *         no simple boundary ring of length ≥ 4 could be found —
     *         caller should fall back to shape-proxy heuristics.
     */
    public static BoundarySides extract(List<Integer> faces, int[] facePatch, int patchId,
                                        int[] faceIdx, int[][] adj, float[] positions) {
        Map<Integer, List<Integer>> neighbours = new HashMap<>();
        for (int f : faces) {
            for (int e = 0; e < 3; e++) {
                int nb = adj[f][e];
                if (nb >= 0 && facePatch[nb] == patchId) continue;  // interior edge
                int u = faceIdx[f * 3 + e];
                int v = faceIdx[f * 3 + (e + 1) % 3];
                addNeighbour(neighbours, u, v);
                addNeighbour(neighbours, v, u);
            }
        }
        if (neighbours.isEmpty()) return null;

        // Pick the largest ring from all reachable rings starting at any
        // still-unvisited boundary vertex. Small rings are assumed to
        // bound holes inside the patch (concavity carve-outs) and are
        // ignored — the Coons fit is meant to approximate the outer
        // shape only.
        Set<Integer> globallyVisited = new HashSet<>();
        int[] bestRing = null;
        for (Integer start : neighbours.keySet()) {
            if (globallyVisited.contains(start)) continue;
            int[] ring = walkRing(start, neighbours, globallyVisited, positions);
            if (ring == null) continue;
            if (bestRing == null || ring.length > bestRing.length) bestRing = ring;
        }
        if (bestRing == null || bestRing.length < 4) return null;
        int total = bestRing.length;

        // Corner detection: turn angle at each ring vertex. Same
        // straight-line-deviation test as boundarySideCount. The walker
        // always returns the **four strongest** corners so mesh-sampling
        // noise (triangulation wobble on an otherwise-smooth boundary)
        // doesn't inflate side count above 4.
        float[] deviationStrength = new float[total];
        List<Integer> allCorners = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            int at = bestRing[i];
            int before = bestRing[(i - 1 + total) % total];
            int after = bestRing[(i + 1) % total];
            float[] da = unitDir(positions, at, before);
            float[] db = unitDir(positions, at, after);
            float dot = da[0] * db[0] + da[1] * db[1] + da[2] * db[2];
            dot = Math.max(-1f, Math.min(1f, dot));
            float deviation = Math.abs((float) Math.acos(dot) - (float) Math.PI);
            deviationStrength[i] = deviation;
            if (deviation > T_CORNER_RAD) allCorners.add(i);
        }

        List<Integer> corners;
        if (allCorners.size() >= 4) {
            Integer[] sorted = allCorners.toArray(new Integer[0]);
            Arrays.sort(sorted, (a, b) ->
                    Float.compare(deviationStrength[b], deviationStrength[a]));
            corners = new ArrayList<>();
            for (int i = 0; i < 4; i++) corners.add(sorted[i]);
        } else {
            Integer[] all = new Integer[total];
            for (int i = 0; i < total; i++) all[i] = i;
            Arrays.sort(all, (a, b) ->
                    Float.compare(deviationStrength[b], deviationStrength[a]));
            corners = new ArrayList<>();
            for (int i = 0; i < 4; i++) corners.add(all[i]);
        }
        java.util.Collections.sort(corners);

        int[] cornerVerts = new int[4];
        for (int i = 0; i < 4; i++) cornerVerts[i] = bestRing[corners.get(i)];
        List<int[]> sides = new ArrayList<>(4);
        for (int c = 0; c < 4; c++) {
            int from = corners.get(c);
            int to = corners.get((c + 1) % 4);
            int len = (to - from + total) % total + 1;  // include both endpoints
            int[] side = new int[len];
            for (int k = 0; k < len; k++) side[k] = bestRing[(from + k) % total];
            sides.add(side);
        }
        return new BoundarySides(sides, cornerVerts);
    }

    /**
     * Walk one closed ring starting at {@code start} using the
     * neighbour map. At each step picks the neighbour that (a) hasn't
     * been visited in this ring already (except closing back to start)
     * and (b) minimises the direction change from the previous edge,
     * to keep multi-neighbour junctions tracking the smoother local
     * boundary. Returns {@code null} if the ring can't close.
     */
    private static int[] walkRing(int start, Map<Integer, List<Integer>> neighbours,
                                  Set<Integer> globallyVisited, float[] positions) {
        List<Integer> ring = new ArrayList<>();
        Set<Integer> ringVisited = new HashSet<>();
        int cur = start;
        int prev = -1;
        ring.add(cur);
        ringVisited.add(cur);
        while (true) {
            List<Integer> nbs = neighbours.get(cur);
            if (nbs == null || nbs.isEmpty()) return null;
            int next = pickNext(cur, prev, nbs, ringVisited, start, positions);
            if (next < 0) return null;
            if (next == start) {
                // Successful closure.
                globallyVisited.addAll(ringVisited);
                int[] out = new int[ring.size()];
                for (int i = 0; i < out.length; i++) out[i] = ring.get(i);
                return out;
            }
            ring.add(next);
            ringVisited.add(next);
            prev = cur;
            cur = next;
            // Defensive cap — a ring the size of all mesh vertices is
            // almost certainly broken; bail rather than spin forever.
            if (ring.size() > neighbours.size() + 2) return null;
        }
    }

    /**
     * Pick the next ring step from {@code cur}'s neighbour list. Prefer
     * unvisited neighbours; among those, pick the one that continues
     * most straight (smallest turn). If no unvisited option but
     * {@code start} is a neighbour, close the ring there.
     */
    private static int pickNext(int cur, int prev, List<Integer> nbs,
                                 Set<Integer> ringVisited, int start, float[] positions) {
        float[] incoming = prev >= 0 ? unitDir(positions, cur, prev) : null;
        int bestUnvisited = -1;
        float bestUnvisitedDot = Float.NEGATIVE_INFINITY;
        boolean startReachable = false;
        for (int n : nbs) {
            if (n == prev) continue;
            if (n == start) {
                startReachable = true;
                continue;
            }
            if (ringVisited.contains(n)) continue;
            float score;
            if (incoming == null) {
                score = 0f;
            } else {
                float[] outgoing = unitDir(positions, cur, n);
                float dot = incoming[0] * outgoing[0] + incoming[1] * outgoing[1]
                        + incoming[2] * outgoing[2];
                // Ring walking wants the edge that continues STRAIGHT, i.e.
                // whose unit vector FROM cur is anti-parallel to the
                // incoming unit vector (dot ≈ −1). Score = −dot so higher
                // score = more-opposite = better continuation.
                score = -dot;
            }
            if (score > bestUnvisitedDot) {
                bestUnvisitedDot = score;
                bestUnvisited = n;
            }
        }
        if (bestUnvisited >= 0) return bestUnvisited;
        if (startReachable) return start;
        return -1;
    }

    private static void addNeighbour(Map<Integer, List<Integer>> m, int at, int other) {
        List<Integer> list = m.computeIfAbsent(at, k -> new ArrayList<>(2));
        if (!list.contains(other)) list.add(other);
    }

    private static float[] unitDir(float[] positions, int from, int to) {
        float dx = positions[to * 3]     - positions[from * 3];
        float dy = positions[to * 3 + 1] - positions[from * 3 + 1];
        float dz = positions[to * 3 + 2] - positions[from * 3 + 2];
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-20f) return new float[]{0f, 0f, 0f};
        return new float[]{dx / len, dy / len, dz / len};
    }
}
