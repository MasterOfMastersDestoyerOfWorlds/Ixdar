package ixdar.procgen.dungeon.algo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.geometry.mesh.data.EdgeKey;

/**
 * Delaunay triangulation over planar sites via Bowyer-Watson incremental insertion, producing
 * the candidate edge set the MST stage filters.
 *
 * <p>Output edges are sorted lexicographically by (min-idx, max-idx). At least three sites are
 * needed for a meaningful triangulation; fewer degenerate to a trivial edge set. Insertion is
 * O(N^2).
 */
public final class DelaunayTriangulation2D {
    public static final int NUM_3 = 3;
    public static final int NUM_20 = 20;

    private DelaunayTriangulation2D() {
    }

    /**
     * Bowyer-Watson incremental insertion over the sites, then strip super-triangles and emit
     * the unique edges sorted lexicographically.
     *
     * @param us first planar coordinate per site
     * @param vs second planar coordinate per site (same length as {@code us})
     * @return flat pairs of site indices; empty for &lt; 2 sites, a single pair for 2 sites
     */
    public static int[] triangulate(double[] us, double[] vs) {
        int n = us.length;
        if (n < 2) {
            return new int[0];
        }
        if (n == 2) {
            return new int[] { 0, 1 };
        }

        double[] xs = new double[n + NUM_3];
        double[] ys = new double[n + NUM_3];
        System.arraycopy(us, 0, xs, 0, n);
        System.arraycopy(vs, 0, ys, 0, n);

        double minX = xs[0], maxX = xs[0], minY = ys[0], maxY = ys[0];
        for (int i = 1; i < n; i++) {
            if (xs[i] < minX) minX = xs[i];
            if (xs[i] > maxX) maxX = xs[i];
            if (ys[i] < minY) minY = ys[i];
            if (ys[i] > maxY) maxY = ys[i];
        }
        double dx = maxX - minX;
        double dy = maxY - minY;
        double dmax = Math.max(dx, dy);
        if (dmax == 0) dmax = 1;
        double midX = (minX + maxX) / 2;
        double midY = (minY + maxY) / 2;
        // Super-triangle clearly encloses the bounding box with margin to spare.
        xs[n] = midX - NUM_20 * dmax;     ys[n] = midY - dmax;
        xs[n + 1] = midX + NUM_20 * dmax; ys[n + 1] = midY - dmax;
        xs[n + 2] = midX;             ys[n + 2] = midY + NUM_20 * dmax;

        // Triangles are int[3] rows of site indices in CCW order; edges are EdgeKey-packed
        // longs (smaller index in the high word), so sorting them as longs is lexicographic.
        List<int[]> tris = new ArrayList<>();
        tris.add(ccwTriangle(n, n + 1, n + 2, xs, ys));

        for (int p = 0; p < n; p++) {
            List<int[]> bad = new ArrayList<>();
            for (int[] t : tris) {
                if (inCircumcircle(
                        xs[t[0]], ys[t[0]],
                        xs[t[1]], ys[t[1]],
                        xs[t[2]], ys[t[2]],
                        xs[p], ys[p])) {
                    bad.add(t);
                }
            }
            // Boundary of the polygon hole: edges that appear in exactly one bad triangle.
            Map<Long, Integer> edgeCount = new LinkedHashMap<>();
            for (int[] t : bad) {
                edgeCount.merge(EdgeKey.undirected(t[0], t[1]), 1, Integer::sum);
                edgeCount.merge(EdgeKey.undirected(t[1], t[2]), 1, Integer::sum);
                edgeCount.merge(EdgeKey.undirected(t[2], t[0]), 1, Integer::sum);
            }
            tris.removeAll(bad);
            for (Map.Entry<Long, Integer> e : edgeCount.entrySet()) {
                if (e.getValue() == 1) {
                    long edge = e.getKey();
                    tris.add(ccwTriangle(EdgeKey.minVertex(edge), EdgeKey.maxVertex(edge), p, xs, ys));
                }
            }
        }

        Set<Long> edges = new LinkedHashSet<>();
        for (int[] t : tris) {
            if (t[0] >= n || t[1] >= n || t[2] >= n) continue;
            edges.add(EdgeKey.undirected(t[0], t[1]));
            edges.add(EdgeKey.undirected(t[1], t[2]));
            edges.add(EdgeKey.undirected(t[2], t[0]));
        }
        return sortedPairs(edges);
    }

    /**
     * Unpacks a set of EdgeKey-packed edges into flat index pairs sorted lexicographically by
     * (min-idx, max-idx).
     *
     * @param edges undirected packed edge keys
     * @return flat pairs of site indices, smaller index first
     */
    static int[] sortedPairs(Set<Long> edges) {
        long[] sorted = new long[edges.size()];
        int count = 0;
        for (long edge : edges) {
            sorted[count++] = edge;
        }
        Arrays.sort(sorted);
        int[] out = new int[sorted.length * 2];
        for (int i = 0; i < sorted.length; i++) {
            out[i * 2] = EdgeKey.minVertex(sorted[i]);
            out[i * 2 + 1] = EdgeKey.maxVertex(sorted[i]);
        }
        return out;
    }

    /** Returns a triangle with CCW vertex order (positive signed area). */
    private static int[] ccwTriangle(int a, int b, int c, double[] xs, double[] ys) {
        double signed = (xs[b] - xs[a]) * (ys[c] - ys[a]) - (xs[c] - xs[a]) * (ys[b] - ys[a]);
        if (signed < 0) return new int[] { a, c, b };
        return new int[] { a, b, c };
    }

    /**
     * Robust in-circle test: returns true iff point d is strictly inside the circle through
     * CCW-ordered triangle (a, b, c). Sign of the 3x3 determinant with the "lift" to a paraboloid.
     */
    private static boolean inCircumcircle(
            double ax, double ay,
            double bx, double by,
            double cx, double cy,
            double dx, double dy) {
        double adx = ax - dx, ady = ay - dy;
        double bdx = bx - dx, bdy = by - dy;
        double cdx = cx - dx, cdy = cy - dy;
        double abdet = adx * bdy - bdx * ady;
        double bcdet = bdx * cdy - cdx * bdy;
        double cadet = cdx * ady - adx * cdy;
        double alift = adx * adx + ady * ady;
        double blift = bdx * bdx + bdy * bdy;
        double clift = cdx * cdx + cdy * cdy;
        return alift * bcdet + blift * cadet + clift * abdet > 0;
    }
}
