package ixdar.geometry.mesh.data;

import java.util.ArrayList;
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
 * <p>Parallels the logic in {@code SemanticPatchDecomposer.boundarySideCount}
 * but produces ordered polylines instead of just a corner count.
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
     * @return the sides + corner vertices, or {@code null} if the
     *         boundary can't be walked cleanly (disconnected, a vertex
     *         has more than 2 boundary neighbours, etc.) — caller
     *         should fall back to shape-proxy heuristics.
     */
    public static BoundarySides extract(List<Integer> faces, int[] facePatch, int patchId,
                                        int[] faceIdx, int[][] adj, float[] positions) {
        Map<Integer, int[]> neighbours = new HashMap<>();
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

        // Sanity: every boundary vertex should have exactly 2 boundary
        // neighbours. More than 2 means a non-manifold junction (e.g.
        // two boundary loops pinched at a vertex) — bail, caller uses
        // shape proxies.
        for (int[] pair : neighbours.values()) {
            if (pair[0] < 0 || pair[1] < 0) return null;
        }

        // Walk: start at an arbitrary vertex, hop to one neighbour, and
        // keep stepping to the "other" neighbour at each node until we
        // close the ring. Bail if we revisit before closing.
        int start = neighbours.keySet().iterator().next();
        int total = neighbours.size();
        if (total < 4) return null;  // can't 4-corner a triangle-boundary or shorter
        int[] ring = new int[total];
        Set<Integer> visited = new HashSet<>();
        ring[0] = start;
        visited.add(start);
        int prev = -1;
        int cur = start;
        for (int step = 1; step < total; step++) {
            int[] nbs = neighbours.get(cur);
            int next = (nbs[0] == prev) ? nbs[1] : nbs[0];
            if (visited.contains(next)) return null;
            ring[step] = next;
            visited.add(next);
            prev = cur;
            cur = next;
        }
        // Closure check — the last vertex's other neighbour must be the start.
        int[] lastNbs = neighbours.get(cur);
        int closing = (lastNbs[0] == prev) ? lastNbs[1] : lastNbs[0];
        if (closing != start) return null;

        // Corner detection: turn angle at each ring vertex. Same
        // straight-line-deviation test as boundarySideCount. Then the
        // walker always returns the **four strongest** corners so mesh-
        // sampling noise (triangulation wobble on an otherwise-smooth
        // boundary) doesn't inflate side count above 4. Coons needs
        // exactly 4; any more and we couldn't fit.
        float[] deviationStrength = new float[total];
        List<Integer> allCorners = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            int at = ring[i];
            int before = ring[(i - 1 + total) % total];
            int after = ring[(i + 1) % total];
            float[] da = unitDir(positions, at, before);
            float[] db = unitDir(positions, at, after);
            float dot = da[0] * db[0] + da[1] * db[1] + da[2] * db[2];
            dot = Math.max(-1f, Math.min(1f, dot));
            float deviation = Math.abs((float) Math.acos(dot) - (float) Math.PI);
            deviationStrength[i] = deviation;
            if (deviation > T_CORNER_RAD) allCorners.add(i);
        }
        if (allCorners.isEmpty()) return null;

        // If the raw threshold gave us more than 4 corners, prune to the
        // 4 sharpest; if fewer than 4, promote additional high-deviation
        // ring positions until we have 4. Either way: four sides out, in
        // circular order around the ring.
        List<Integer> corners;
        if (allCorners.size() >= 4) {
            Integer[] sorted = allCorners.toArray(new Integer[0]);
            java.util.Arrays.sort(sorted, (a, b) ->
                    Float.compare(deviationStrength[b], deviationStrength[a]));
            corners = new ArrayList<>();
            for (int i = 0; i < 4; i++) corners.add(sorted[i]);
        } else {
            // Rank all ring positions by deviation; take top 4. At least
            // T_CORNER_RAD for the ones we found; the extras we promote
            // are genuinely softer turns so Coons fit may be looser there
            // — that's fine, the error metric catches over-soft corners.
            Integer[] all = new Integer[total];
            for (int i = 0; i < total; i++) all[i] = i;
            java.util.Arrays.sort(all, (a, b) ->
                    Float.compare(deviationStrength[b], deviationStrength[a]));
            corners = new ArrayList<>();
            for (int i = 0; i < 4; i++) corners.add(all[i]);
        }
        // Re-sort corners into ring order (ascending index) so sides are
        // emitted in traversal order.
        java.util.Collections.sort(corners);

        // Slice the ring at corners into sides. Each side's first
        // vertex is a corner; the last vertex of side K equals the
        // first vertex of side K+1.
        int cornerCount = corners.size();
        int[] cornerVerts = new int[cornerCount];
        for (int i = 0; i < cornerCount; i++) cornerVerts[i] = ring[corners.get(i)];
        List<int[]> sides = new ArrayList<>(cornerCount);
        for (int c = 0; c < cornerCount; c++) {
            int from = corners.get(c);
            int to = corners.get((c + 1) % cornerCount);
            int len = (to - from + total) % total + 1;  // include both endpoints
            int[] side = new int[len];
            for (int k = 0; k < len; k++) side[k] = ring[(from + k) % total];
            sides.add(side);
        }
        return new BoundarySides(sides, cornerVerts);
    }

    private static void addNeighbour(Map<Integer, int[]> m, int at, int other) {
        int[] pair = m.get(at);
        if (pair == null) {
            m.put(at, new int[]{other, -1});
        } else if (pair[1] == -1 && pair[0] != other) {
            pair[1] = other;
        }
        // Triplet+ collisions are dropped here (stay -1 / -1) and the
        // caller's "both must be ≥ 0" check will mark the boundary
        // non-manifold. That's the right escape hatch.
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
