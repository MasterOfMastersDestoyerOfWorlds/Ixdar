package ixdar.geometry.mesh.data.analysis.segmentation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.PackedPoints;

/**
 * Walks a patch's boundary into an ordered ring and slices it at corners into the four sides a Coons
 * fit expects. Of several disconnected rings only the largest is kept, and junctions resolve to the
 * step that best continues the ring.
 */
public final class PatchBoundaryWalker {
    public static final int CORNER_COUNT = 4;

    private static final float T_CORNER_RAD = 1.22f;

    private PatchBoundaryWalker() {}

    /**
     * Extract the ordered boundary polyline sides for one patch.
     *
     * @param faces face ids belonging to this patch
     * @param facePatch patch id per face index (used to recognise interior edges)
     * @param patchId patch label this call is extracting
     * @param faceIdx flat triangle index buffer ({@code 3 * faceCount} entries)
     * @param adj per-face neighbour table ({@code adj[f][e]} is the face across edge {@code e}, or {@code -1})
     * @param positions vertex positions
     * @return the sides (4) + corner vertices (4), or {@code null} if
     *         no simple boundary ring of length ≥ 4 could be found —
     *         caller should fall back to shape-proxy heuristics.
     */
    public static BoundarySides extract(List<Integer> faces, int[] facePatch, int patchId,
                                        int[] faceIdx, int[][] adj, PackedPoints positions) {
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

        Set<Integer> globallyVisited = new HashSet<>();
        int[] bestRing = null;
        for (Integer start : neighbours.keySet()) {
            if (globallyVisited.contains(start)) continue;
            // Walk one closed ring from start, stepping to the unvisited neighbour that least changes
            // direction so junctions follow the smoother local boundary, and closing at start once no
            // unvisited neighbour is left. A walk that gets stuck or outgrows the boundary is dropped.
            List<Integer> ring = new ArrayList<>();
            Set<Integer> ringVisited = new HashSet<>();
            int cur = start;
            int prev = -1;
            ring.add(cur);
            ringVisited.add(cur);
            boolean closed = false;
            while (!closed && ring.size() <= neighbours.size() + 2) {
                List<Integer> nbs = neighbours.get(cur);
                if (nbs == null || nbs.isEmpty()) break;
                Vector3f incoming = prev >= 0 ? positions.at(prev).sub(positions.at(cur)) : null;
                int next = -1;
                float bestScore = Float.NEGATIVE_INFINITY;
                boolean startReachable = false;
                for (int n : nbs) {
                    if (n == prev) continue;
                    if (n == start) {
                        startReachable = true;
                        continue;
                    }
                    if (ringVisited.contains(n)) continue;
                    // The straightest step turns least: the widest angle between the step back and the step on.
                    float score = incoming == null ? 0f : incoming.angle(positions.at(n).sub(positions.at(cur)));
                    if (score > bestScore) {
                        bestScore = score;
                        next = n;
                    }
                }
                if (next < 0) {
                    closed = startReachable;
                    break;
                }
                ring.add(next);
                ringVisited.add(next);
                prev = cur;
                cur = next;
            }
            if (!closed) continue;
            globallyVisited.addAll(ringVisited);
            if (bestRing == null || ring.size() > bestRing.length) {
                bestRing = ring.stream().mapToInt(Integer::intValue).toArray();
            }
        }
        if (bestRing == null || bestRing.length < CORNER_COUNT) return null;
        int total = bestRing.length;

        float[] deviationStrength = new float[total];
        List<Integer> allCorners = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            Vector3f at = positions.at(bestRing[i]);
            Vector3f da = positions.at(bestRing[(i - 1 + total) % total]).sub(at);
            Vector3f db = positions.at(bestRing[(i + 1) % total]).sub(at);
            float deviation = Math.abs(da.angle(db) - (float) Math.PI);
            deviationStrength[i] = deviation;
            if (deviation > T_CORNER_RAD) allCorners.add(i);
        }

        List<Integer> corners;
        if (allCorners.size() >= CORNER_COUNT) {
            Integer[] sorted = allCorners.toArray(new Integer[0]);
            Arrays.sort(sorted, (a, b) ->
                    Float.compare(deviationStrength[b], deviationStrength[a]));
            corners = new ArrayList<>();
            for (int i = 0; i < CORNER_COUNT; i++) corners.add(sorted[i]);
        } else {
            Integer[] all = new Integer[total];
            for (int i = 0; i < total; i++) all[i] = i;
            Arrays.sort(all, (a, b) ->
                    Float.compare(deviationStrength[b], deviationStrength[a]));
            corners = new ArrayList<>();
            for (int i = 0; i < CORNER_COUNT; i++) corners.add(all[i]);
        }
        Collections.sort(corners);

        int[] cornerVerts = new int[CORNER_COUNT];
        for (int i = 0; i < CORNER_COUNT; i++) cornerVerts[i] = bestRing[corners.get(i)];
        List<int[]> sides = new ArrayList<>(CORNER_COUNT);
        for (int c = 0; c < CORNER_COUNT; c++) {
            int from = corners.get(c);
            int to = corners.get((c + 1) % CORNER_COUNT);
            int len = (to - from + total) % total + 1;  // include both endpoints
            int[] side = new int[len];
            for (int k = 0; k < len; k++) side[k] = bestRing[(from + k) % total];
            sides.add(side);
        }
        return new BoundarySides(sides, cornerVerts);
    }

    private static void addNeighbour(Map<Integer, List<Integer>> m, int at, int other) {
        List<Integer> list = m.computeIfAbsent(at, k -> new ArrayList<>(2));
        if (!list.contains(other)) list.add(other);
    }

    public record BoundarySides(List<int[]> sides, int[] cornerVertices) {}
}
