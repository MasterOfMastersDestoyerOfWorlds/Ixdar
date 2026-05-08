package ixdar.procgen.dungeon.algo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.procgen.dungeon.values.EdgeGraphValue;
import ixdar.procgen.dungeon.values.RoomListValue;

/**
 * Delaunay triangulation over 2D room centers via Bowyer-Watson incremental insertion. Stage 2
 * of the vazgriz dungeon pipeline — produces the candidate edge set the MST pass filters.
 *
 * <p>Output edges are always sorted lexicographically by (min-idx, max-idx) so results are
 * byte-deterministic for identical inputs. The input {@link RoomListValue} must have at least
 * three rooms for a meaningful triangulation; fewer rooms degenerate to a trivial edge set.
 *
 * <p>Implementation uses the in-circle determinant test on doubles to minimize precision loss
 * when room centers happen to land near a circumcircle (a common artifact of integer-grid
 * placement). O(N^2) per insertion, fine for dungeon-scale N &lt; 200.
 */
public final class DelaunayTriangulation2D {
    public static final int NUM_3 = 3;
    public static final int NUM_20 = 20;

    private DelaunayTriangulation2D() {
    }

    /**
     * Bowyer-Watson incremental insertion over the room centers, then strip super-triangles and
     * emit the unique edges sorted lexicographically.
     *
     * @param rooms input rooms whose centers act as Delaunay sites
     * @return edge graph indexed by room id; empty for &lt; 2 rooms, a single edge for 2 rooms
     */
    public static EdgeGraphValue triangulate(RoomListValue rooms) {
        int n = rooms.size();
        if (n == 0) return new EdgeGraphValue(0, new int[0][]);
        if (n == 1) return new EdgeGraphValue(1, new int[0][]);
        if (n == 2) return new EdgeGraphValue(2, new int[][] { { 0, 1 } });

        double[] xs = new double[n + NUM_3];
        double[] ys = new double[n + NUM_3];
        for (int i = 0; i < n; i++) {
            xs[i] = rooms.get(i).centerX();
            ys[i] = rooms.get(i).centerY();
        }

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

        List<Triangle> tris = new ArrayList<>();
        tris.add(ccwTriangle(n, n + 1, n + 2, xs, ys));

        for (int p = 0; p < n; p++) {
            List<Triangle> bad = new ArrayList<>();
            for (Triangle t : tris) {
                if (inCircumcircle(
                        xs[t.a], ys[t.a],
                        xs[t.b], ys[t.b],
                        xs[t.c], ys[t.c],
                        xs[p], ys[p])) {
                    bad.add(t);
                }
            }
            // Boundary of the polygon hole: edges that appear in exactly one bad triangle.
            Map<Edge, Integer> edgeCount = new LinkedHashMap<>();
            for (Triangle t : bad) {
                edgeCount.merge(new Edge(t.a, t.b), 1, Integer::sum);
                edgeCount.merge(new Edge(t.b, t.c), 1, Integer::sum);
                edgeCount.merge(new Edge(t.c, t.a), 1, Integer::sum);
            }
            tris.removeAll(bad);
            for (Map.Entry<Edge, Integer> e : edgeCount.entrySet()) {
                if (e.getValue() == 1) {
                    Edge edge = e.getKey();
                    tris.add(ccwTriangle(edge.a, edge.b, p, xs, ys));
                }
            }
        }

        Set<Edge> edges = new LinkedHashSet<>();
        for (Triangle t : tris) {
            if (t.a >= n || t.b >= n || t.c >= n) continue;
            edges.add(new Edge(t.a, t.b));
            edges.add(new Edge(t.b, t.c));
            edges.add(new Edge(t.c, t.a));
        }
        List<Edge> sortedEdges = new ArrayList<>(edges);
        sortedEdges.sort(Edge.COMPARATOR);
        int[][] out = new int[sortedEdges.size()][];
        for (int i = 0; i < sortedEdges.size(); i++) {
            Edge e = sortedEdges.get(i);
            out[i] = new int[] { e.a, e.b };
        }
        return new EdgeGraphValue(n, out);
    }

    /** Returns a triangle with CCW vertex order (positive signed area). */
    private static Triangle ccwTriangle(int a, int b, int c, double[] xs, double[] ys) {
        double signed = (xs[b] - xs[a]) * (ys[c] - ys[a]) - (xs[c] - xs[a]) * (ys[b] - ys[a]);
        if (signed < 0) return new Triangle(a, c, b);
        return new Triangle(a, b, c);
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

    record Triangle(int a, int b, int c) { }

    record Edge(int a, int b) {
        static final Comparator<Edge> COMPARATOR =
                Comparator.comparingInt(Edge::a).thenComparingInt(Edge::b);
        Edge {
            if (a > b) {
                int tmp = a;
                a = b;
                b = tmp;
            }
        }
    }
}
