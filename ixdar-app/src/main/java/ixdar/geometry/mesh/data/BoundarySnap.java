package ixdar.geometry.mesh.data;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import ixdar.geometry.mesh.data.representation.ArrayMesh;

/**
 * Post-assembly feature-edge boundary snap: reroutes cell-pair boundaries onto high-confidence
 * feature edges by banded Dijkstra, moving geometry while leaving MSC topology intact.
 *
 * <p>A closed-loop curve, where one cell fully encloses another, has no corners to route between
 * and keeps its original edges.
 *
 * <p>See also: Dong 2005 Section 4
 */
public final class BoundarySnap {
    public static final int NUM_3 = 3;
    public static final int NUM_32 = 32;
    public static final long NUM_0xffffffff = 0xffffffffL;
    public static final float NUM_0 = 0f;

    private static final int   BAND_HOPS       = 8;
    private static final int   MIN_BAND_VERTS  = 6;
    private static final float COST_HIGH_CREST = 0.05f;
    private static final float COST_HIGH       = 0.15f;
    private static final float COST_FEATURE    = 0.4f;
    private static final float COST_NONFEATURE = 1.0f;

    private BoundarySnap() {}

    /**
     * Snaps cell-pair boundaries onto detected feature edges and returns the
     * relabeled per-face labels (see class Javadoc for the three-stage pipeline).
     *
     * @param mesh triangle mesh whose face indices and positions are used
     * @param faceLabels per-face cell label assigned by the prior MSC pass
     * @param highConfidenceEdges canonical (lo,hi) edge keys with strongest crease evidence
     * @param dihedralEdges edges flagged by dihedral-angle thresholding
     * @param principalEdges edges aligned with principal curvature directions
     * @param crestEdges edges on a detected crest line (highest priority)
     * @return relabeled face labels (length equals face count)
     */
    public static int[] snap(ArrayMesh mesh,
                              int[] faceLabels,
                              Set<Long> highConfidenceEdges,
                              Set<Long> dihedralEdges,
                              Set<Long> principalEdges,
                              Set<Long> crestEdges) {
        int[] faceIdx = mesh.copyFaceIndices();
        int nv = mesh.vertexCount();
        float[] positions = mesh.copyPositions();

        int[][] ring = buildOneRing(faceIdx, nv);
        Map<Long, int[]> edgeFaces = buildEdgeFaceMap(faceIdx);
        int[][] vertFaces = buildVertexFaces(faceIdx, nv);

        int[][] vertLabels = collectVertexLabels(vertFaces, faceLabels);
        boolean[] isCorner = new boolean[nv];
        int cornerCount = 0;
        for (int v = 0; v < nv; v++) {
            if (vertLabels[v].length >= NUM_3) { isCorner[v] = true; cornerCount++; }
        }

        Map<Long, List<Long>> edgesByPair = new HashMap<>();
        int totalLabels = 0;
        for (int l : faceLabels) if (l + 1 > totalLabels) totalLabels = l + 1;
        for (Map.Entry<Long, int[]> e : edgeFaces.entrySet()) {
            int[] fp = e.getValue();
            if (fp[1] < 0) continue;
            int la = faceLabels[fp[0]], lb = faceLabels[fp[1]];
            if (la == lb) continue;
            long pairK = la < lb ? ((long) la << NUM_32) | (lb & NUM_0xffffffff)
                     : ((long) lb << NUM_32) | (la & NUM_0xffffffff);
            edgesByPair.computeIfAbsent(pairK, k -> new ArrayList<>()).add(e.getKey());
        }

        List<Curve> curves = new ArrayList<>();
        for (Map.Entry<Long, List<Long>> entry : edgesByPair.entrySet()) {
            long pairK = entry.getKey();
            int labelA = (int)(pairK >>> NUM_32);
            int labelB = (int)(pairK & NUM_0xffffffff);
            curves.addAll(buildCurves(entry.getValue(), isCorner, labelA, labelB));
        }

        int totalEdges = 0, closedCount = 0, minLen = Integer.MAX_VALUE, maxLen = 0;
        for (Curve c : curves) {
            int n = c.vertices.size() - 1;
            totalEdges += n;
            if (c.isClosed) closedCount++;
            if (n < minLen) minLen = n;
            if (n > maxLen) maxLen = n;
        }
        System.out.println("[BoundarySnap] corners=" + cornerCount
                + " curves=" + curves.size() + " (closed=" + closedCount + ")"
                + " mean-edges=" + (curves.isEmpty() ? 0 : totalEdges / curves.size())
                + " min=" + (curves.isEmpty() ? 0 : minLen)
                + " max=" + maxLen);

        Set<Long> finalBoundary = new HashSet<>();
        int snappedCount = 0, unchangedCount = 0, skippedClosed = 0, skippedBand = 0;
        for (Curve c : curves) {
            List<Integer> finalPath;
            if (c.isClosed) {
                finalPath = c.vertices;
                skippedClosed++;
            } else {
                List<Integer> reroute = rerouteCurve(c, faceLabels, ring,
                        vertFaces, positions, highConfidenceEdges,
                        dihedralEdges, principalEdges, crestEdges);
                if (reroute == null) {
                    finalPath = c.vertices;
                    skippedBand++;
                } else if (pathEquals(reroute, c.vertices)) {
                    finalPath = c.vertices;
                    unchangedCount++;
                } else {
                    finalPath = reroute;
                    snappedCount++;
                }
            }
            for (int i = 0; i < finalPath.size() - 1; i++) {
                finalBoundary.add(edgeKey(finalPath.get(i), finalPath.get(i + 1)));
            }
        }
        System.out.println("[BoundarySnap] snapped=" + snappedCount
                + " unchanged=" + unchangedCount
                + " skipped-closed=" + skippedClosed
                + " skipped-band=" + skippedBand);

        int[] relabeled = floodFillRelabel(faceIdx, faceLabels, finalBoundary, edgeFaces);

        boolean[] seenOld = new boolean[totalLabels];
        boolean[] seenNew = new boolean[totalLabels];
        for (int l : faceLabels) seenOld[l] = true;
        for (int l : relabeled) seenNew[l] = true;
        int oldDistinct = 0, newDistinct = 0;
        for (boolean b : seenOld) if (b) oldDistinct++;
        for (boolean b : seenNew) if (b) newDistinct++;
        if (newDistinct != oldDistinct) {
            System.out.println("[BoundarySnap] WARN label count changed: "
                    + oldDistinct + " -> " + newDistinct);
        }
        return relabeled;
    }

    // ---------------- B5a: chain extraction ----------------

    private static List<Curve> buildCurves(List<Long> pairEdges,
                                            boolean[] isCorner,
                                            int labelA, int labelB) {
        Map<Integer, List<Integer>> sub = new HashMap<>();
        for (long ek : pairEdges) {
            int u = (int)(ek >>> NUM_32);
            int v = (int)(ek & NUM_0xffffffff);
            sub.computeIfAbsent(u, k -> new ArrayList<>()).add(v);
            sub.computeIfAbsent(v, k -> new ArrayList<>()).add(u);
        }
        Set<Long> visited = new HashSet<>();
        List<Curve> curves = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> entry : sub.entrySet()) {
            int v = entry.getKey();
            List<Integer> nbs = entry.getValue();
            boolean isEndpoint = nbs.size() != 2 || isCorner[v];
            if (!isEndpoint) continue;
            for (int next : nbs) {
                long ek = edgeKey(v, next);
                if (visited.contains(ek)) continue;
                curves.add(walkOneChain(v, next, sub, isCorner, visited,
                        labelA, labelB, /*closed=*/false));
            }
        }
        for (Map.Entry<Integer, List<Integer>> entry : sub.entrySet()) {
            int v = entry.getKey();
            for (int next : entry.getValue()) {
                long ek = edgeKey(v, next);
                if (visited.contains(ek)) continue;
                curves.add(walkOneChain(v, next, sub, isCorner, visited,
                        labelA, labelB, /*closed=*/true));
            }
        }
        return curves;
    }

    private static Curve walkOneChain(int start, int firstStep,
                                       Map<Integer, List<Integer>> sub,
                                       boolean[] isCorner,
                                       Set<Long> visitedEdges,
                                       int labelA, int labelB,
                                       boolean closed) {
        List<Integer> path = new ArrayList<>();
        path.add(start);
        int prev = start, cur = firstStep;
        while (true) {
            long ek = edgeKey(prev, cur);
            if (visitedEdges.contains(ek)) break;
            visitedEdges.add(ek);
            path.add(cur);
            if (cur == start) break;
            List<Integer> nbs = sub.get(cur);
            if (nbs.size() != 2 || isCorner[cur]) break;
            int next = nbs.get(0) == prev ? nbs.get(1) : nbs.get(0);
            prev = cur;
            cur = next;
        }
        Curve c = new Curve();
        c.vertices = path;
        c.labelA = labelA;
        c.labelB = labelB;
        c.isClosed = closed;
        return c;
    }

    // ---------------- B5b: banded Dijkstra ----------------

    private static List<Integer> rerouteCurve(Curve c, int[] faceLabels,
                                                int[][] ring, int[][] vertFaces,
                                                float[] positions,
                                                Set<Long> highConfidenceEdges,
                                                Set<Long> dihedralEdges,
                                                Set<Long> principalEdges,
                                                Set<Long> crestEdges) {
        int s = c.vertices.get(0);
        int t = c.vertices.get(c.vertices.size() - 1);
        if (s == t) return null;

        int nv = ring.length;
        boolean[] inBand = new boolean[nv];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (int v : c.vertices) {
            if (!inBand[v]) { inBand[v] = true; q.add(new int[]{v, 0}); }
        }
        int bandSize = 0;
        for (boolean b : inBand) if (b) bandSize++;

        int labelA = c.labelA, labelB = c.labelB;
        while (!q.isEmpty()) {
            int[] head = q.poll();
            int u = head[0]; int dist = head[1];
            if (dist >= BAND_HOPS) continue;
            for (int n : ring[u]) {
                if (inBand[n]) continue;
                if (!allFacesInPair(n, vertFaces, faceLabels, labelA, labelB)) continue;
                inBand[n] = true; bandSize++;
                q.add(new int[]{n, dist + 1});
            }
        }
        if (bandSize < MIN_BAND_VERTS) return null;

        float[] dist = new float[nv];
        Arrays.fill(dist, Float.POSITIVE_INFINITY);
        int[] prev = new int[nv];
        Arrays.fill(prev, -1);
        dist[s] = NUM_0;
        PriorityQueue<DijkstraNode> pq = new PriorityQueue<>(
                (x, y) -> Float.compare(x.dist, y.dist));
        pq.add(new DijkstraNode(NUM_0, s));
        while (!pq.isEmpty()) {
            DijkstraNode head = pq.poll();
            if (head.dist > dist[head.v]) continue;
            int u = head.v;
            if (u == t) break;
            for (int n : ring[u]) {
                if (!inBand[n]) continue;
                float w = edgeCost(u, n, positions, highConfidenceEdges,
                        dihedralEdges, principalEdges, crestEdges);
                float nd = head.dist + w;
                if (nd < dist[n]) {
                    dist[n] = nd;
                    prev[n] = u;
                    pq.add(new DijkstraNode(nd, n));
                }
            }
        }
        if (Float.isInfinite(dist[t])) return null;

        List<Integer> path = new ArrayList<>();
        int cur = t;
        while (cur != -1) { path.add(cur); cur = prev[cur]; }
        Collections.reverse(path);
        return path;
    }

    private static boolean allFacesInPair(int v, int[][] vertFaces, int[] faceLabels,
                                            int labelA, int labelB) {
        int[] vf = vertFaces[v];
        if (vf.length == 0) return false;
        for (int f : vf) {
            int l = faceLabels[f];
            if (l != labelA && l != labelB) return false;
        }
        return true;
    }

    private static float edgeCost(int u, int v, float[] positions,
                                    Set<Long> highConfidenceEdges,
                                    Set<Long> dihedralEdges,
                                    Set<Long> principalEdges,
                                    Set<Long> crestEdges) {
        long ek = edgeKey(u, v);
        boolean isCrest = crestEdges.contains(ek);
        boolean isHigh  = highConfidenceEdges.contains(ek);
        boolean isAny   = isCrest || isHigh
                          || dihedralEdges.contains(ek)
                          || principalEdges.contains(ek);
        float mult;
        if (isCrest && isHigh) mult = COST_HIGH_CREST;
        else if (isHigh) mult = COST_HIGH;
        else if (isAny) mult = COST_FEATURE;
        else mult = COST_NONFEATURE;
        float dx = positions[v*NUM_3]   - positions[u*NUM_3];
        float dy = positions[v*NUM_3+1] - positions[u*NUM_3+1];
        float dz = positions[v*NUM_3+2] - positions[u*NUM_3+2];
        float len = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
        return mult * len;
    }

    // ---------------- B5c: face flood-fill relabel ----------------

    private static int[] floodFillRelabel(int[] faceIdx, int[] faceLabels,
                                            Set<Long> finalBoundary,
                                            Map<Long, int[]> edgeFaces) {
        int faceCount = faceIdx.length / NUM_3;
        int[] adj = new int[faceCount * NUM_3];
        Arrays.fill(adj, -1);
        for (int f = 0; f < faceCount; f++) {
            for (int e = 0; e < NUM_3; e++) {
                int u = faceIdx[f*NUM_3+e], v = faceIdx[f*NUM_3+(e+1)%NUM_3];
                long ek = edgeKey(u, v);
                if (finalBoundary.contains(ek)) continue;
                int[] pair = edgeFaces.get(ek);
                if (pair == null || pair[1] < 0) continue;
                adj[f*NUM_3+e] = (pair[0] == f) ? pair[1] : pair[0];
            }
        }
        int[] component = new int[faceCount];
        Arrays.fill(component, -1);
        List<Integer> compLabel = new ArrayList<>();
        for (int f = 0; f < faceCount; f++) {
            if (component[f] >= 0) continue;
            int comp = compLabel.size();
            Map<Integer, Integer> votes = new HashMap<>();
            ArrayDeque<Integer> qq = new ArrayDeque<>();
            qq.add(f); component[f] = comp;
            while (!qq.isEmpty()) {
                int g = qq.poll();
                votes.merge(faceLabels[g], 1, Integer::sum);
                for (int e = 0; e < NUM_3; e++) {
                    int nb = adj[g*NUM_3+e];
                    if (nb < 0 || component[nb] >= 0) continue;
                    component[nb] = comp;
                    qq.add(nb);
                }
            }
            int best = -1, bestCount = -1;
            for (Map.Entry<Integer, Integer> e : votes.entrySet()) {
                if (e.getValue() > bestCount) { bestCount = e.getValue(); best = e.getKey(); }
            }
            compLabel.add(best);
        }
        int[] out = new int[faceCount];
        for (int f = 0; f < faceCount; f++) out[f] = compLabel.get(component[f]);
        return out;
    }

    // ---------------- helpers ----------------

    private static int[][] buildOneRing(int[] faceIdx, int nv) {
        int faceCount = faceIdx.length / NUM_3;
        List<HashSet<Integer>> tmp = new ArrayList<>(nv);
        for (int i = 0; i < nv; i++) tmp.add(new HashSet<>(MIN_BAND_VERTS));
        for (int f = 0; f < faceCount; f++) {
            int a = faceIdx[f*NUM_3], b = faceIdx[f*NUM_3+1], c = faceIdx[f*NUM_3+2];
            tmp.get(a).add(b); tmp.get(a).add(c);
            tmp.get(b).add(a); tmp.get(b).add(c);
            tmp.get(c).add(a); tmp.get(c).add(b);
        }
        int[][] out = new int[nv][];
        for (int i = 0; i < nv; i++) {
            HashSet<Integer> set = tmp.get(i);
            int[] arr = new int[set.size()];
            int j = 0;
            for (int n : set) arr[j++] = n;
            out[i] = arr;
        }
        return out;
    }

    private static Map<Long, int[]> buildEdgeFaceMap(int[] faceIdx) {
        int faceCount = faceIdx.length / NUM_3;
        Map<Long, int[]> edgeFaces = new HashMap<>();
        for (int f = 0; f < faceCount; f++) {
            for (int e = 0; e < NUM_3; e++) {
                int u = faceIdx[f*NUM_3+e], v = faceIdx[f*NUM_3+(e+1)%NUM_3];
                long ek = edgeKey(u, v);
                int[] arr = edgeFaces.get(ek);
                if (arr == null) edgeFaces.put(ek, new int[]{f, -1});
                else if (arr[1] == -1) arr[1] = f;
            }
        }
        return edgeFaces;
    }

    private static int[][] buildVertexFaces(int[] faceIdx, int nv) {
        int faceCount = faceIdx.length / NUM_3;
        int[] counts = new int[nv];
        for (int i = 0; i < faceCount * NUM_3; i++) counts[faceIdx[i]]++;
        int[][] out = new int[nv][];
        for (int i = 0; i < nv; i++) out[i] = new int[counts[i]];
        int[] cursor = new int[nv];
        for (int f = 0; f < faceCount; f++) {
            for (int e = 0; e < NUM_3; e++) {
                int v = faceIdx[f*NUM_3+e];
                out[v][cursor[v]++] = f;
            }
        }
        return out;
    }

    private static int[][] collectVertexLabels(int[][] vertFaces, int[] faceLabels) {
        int[][] out = new int[vertFaces.length][];
        HashSet<Integer> set = new HashSet<>();
        for (int v = 0; v < vertFaces.length; v++) {
            set.clear();
            for (int f : vertFaces[v]) set.add(faceLabels[f]);
            int[] arr = new int[set.size()];
            int j = 0;
            for (int l : set) arr[j++] = l;
            out[v] = arr;
        }
        return out;
    }

    private static long edgeKey(int u, int v) {
        return u < v ? ((long) u << NUM_32) | (v & NUM_0xffffffff)
                     : ((long) v << NUM_32) | (u & NUM_0xffffffff);
    }

    private static boolean pathEquals(List<Integer> a, List<Integer> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equals(b.get(i))) return false;
        }
        return true;
    }

    private static final class Curve {
        List<Integer> vertices;
        int labelA, labelB;
        boolean isClosed;
    }

    private static final class DijkstraNode {
        final float dist;
        final int v;
        DijkstraNode(float d, int u) { dist = d; v = u; }
    }
}
