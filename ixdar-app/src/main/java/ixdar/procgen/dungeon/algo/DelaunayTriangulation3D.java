package ixdar.procgen.dungeon.algo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.procgen.dungeon.values.EdgeGraphValue;
import ixdar.procgen.dungeon.values.RoomListValue3D;

/**
 * 3D Delaunay tetrahedralization over room centers via incremental Bowyer-Watson, returning the
 * complex's unique edges as an {@link EdgeGraphValue} indexed by room id.
 *
 * <p>Tetrahedra are stored with positive signed volume, orientation being repaired at insertion
 * so the in-sphere determinant sign reads consistently.
 */
public final class DelaunayTriangulation3D {
    public static final int NUM_4 = 4;
    public static final int NUM_20 = 20;
    public static final int NUM_3 = 3;

    private DelaunayTriangulation3D() {
    }

    /**
     * Bowyer-Watson incremental insertion over the 3D room centers, then strip tetrahedra
     * touching the super-tetrahedron and emit the unique edges sorted lexicographically.
     *
     * @param rooms input rooms whose centers act as Delaunay sites
     * @return edge graph indexed by room id; empty for &lt; 2 rooms, a single edge for 2 rooms
     */
    public static EdgeGraphValue triangulate(RoomListValue3D rooms) {
        int n = rooms.size();
        if (n == 0) return new EdgeGraphValue(0, new int[0][]);
        if (n == 1) return new EdgeGraphValue(1, new int[0][]);
        if (n == 2) return new EdgeGraphValue(2, new int[][] { { 0, 1 } });

        // Pack point coordinates into parallel arrays. Indices [n .. n+3] reserved for the
        // super-tetrahedron's vertices.
        double[] xs = new double[n + NUM_4];
        double[] ys = new double[n + NUM_4];
        double[] zs = new double[n + NUM_4];
        for (int i = 0; i < n; i++) {
            xs[i] = rooms.get(i).centerX();
            ys[i] = rooms.get(i).centerY();
            zs[i] = rooms.get(i).centerZ();
        }

        double minX = xs[0], maxX = xs[0], minY = ys[0], maxY = ys[0], minZ = zs[0], maxZ = zs[0];
        for (int i = 1; i < n; i++) {
            if (xs[i] < minX) minX = xs[i]; if (xs[i] > maxX) maxX = xs[i];
            if (ys[i] < minY) minY = ys[i]; if (ys[i] > maxY) maxY = ys[i];
            if (zs[i] < minZ) minZ = zs[i]; if (zs[i] > maxZ) maxZ = zs[i];
        }
        double dmax = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        if (dmax == 0) dmax = 1;
        double midX = (minX + maxX) / 2;
        double midY = (minY + maxY) / 2;
        double midZ = (minZ + maxZ) / 2;
        // Corner-style super-tetrahedron well outside the bounding sphere.
        double R = NUM_20 * dmax;
        xs[n]   = midX - R; ys[n]   = midY - R; zs[n]   = midZ - R;
        xs[n+1] = midX + NUM_3*R; ys[n+1] = midY - R; zs[n+1] = midZ - R;
        xs[n+2] = midX - R; ys[n+2] = midY + NUM_3*R; zs[n+2] = midZ - R;
        xs[n+NUM_3] = midX - R; ys[n+NUM_3] = midY - R; zs[n+NUM_3] = midZ + NUM_3*R;

        List<Tet> tets = new ArrayList<>();
        tets.add(orient(n, n + 1, n + 2, n + NUM_3, xs, ys, zs));

        for (int p = 0; p < n; p++) {
            List<Tet> bad = new ArrayList<>();
            for (Tet t : tets) {
                if (insideCircumsphere(
                        xs[t.a], ys[t.a], zs[t.a],
                        xs[t.b], ys[t.b], zs[t.b],
                        xs[t.c], ys[t.c], zs[t.c],
                        xs[t.d], ys[t.d], zs[t.d],
                        xs[p],   ys[p],   zs[p])) {
                    bad.add(t);
                }
            }
            // Boundary triangles: faces appearing in exactly one bad tet.
            Map<Face, Integer> faceCount = new LinkedHashMap<>();
            for (Tet t : bad) {
                faceCount.merge(new Face(t.a, t.b, t.c), 1, Integer::sum);
                faceCount.merge(new Face(t.a, t.b, t.d), 1, Integer::sum);
                faceCount.merge(new Face(t.a, t.c, t.d), 1, Integer::sum);
                faceCount.merge(new Face(t.b, t.c, t.d), 1, Integer::sum);
            }
            tets.removeAll(bad);
            for (Map.Entry<Face, Integer> e : faceCount.entrySet()) {
                if (e.getValue() == 1) {
                    Face f = e.getKey();
                    tets.add(orient(f.a, f.b, f.c, p, xs, ys, zs));
                }
            }
        }

        // Strip tets touching the super-tetrahedron, then collect unique edges.
        Set<Edge> edges = new LinkedHashSet<>();
        for (Tet t : tets) {
            if (t.a >= n || t.b >= n || t.c >= n || t.d >= n) continue;
            edges.add(new Edge(t.a, t.b));
            edges.add(new Edge(t.a, t.c));
            edges.add(new Edge(t.a, t.d));
            edges.add(new Edge(t.b, t.c));
            edges.add(new Edge(t.b, t.d));
            edges.add(new Edge(t.c, t.d));
        }
        List<Edge> sorted = new ArrayList<>(edges);
        sorted.sort(Edge.COMPARATOR);
        int[][] out = new int[sorted.size()][];
        for (int i = 0; i < sorted.size(); i++) {
            Edge e = sorted.get(i);
            out[i] = new int[] { e.a, e.b };
        }
        return new EdgeGraphValue(n, out);
    }

    /**
     * Returns the input vertex order if signed volume is positive, otherwise a transposed
     * order (swap c and d) so the resulting tet has positive volume. This keeps the
     * {@link #insideCircumsphere} sign reading consistent.
     */
    private static Tet orient(int a, int b, int c, int d, double[] xs, double[] ys, double[] zs) {
        double abx = xs[b] - xs[a], aby = ys[b] - ys[a], abz = zs[b] - zs[a];
        double acx = xs[c] - xs[a], acy = ys[c] - ys[a], acz = zs[c] - zs[a];
        double adx = xs[d] - xs[a], ady = ys[d] - ys[a], adz = zs[d] - zs[a];
        // Signed volume * 6 = (b-a) . ((c-a) x (d-a))
        double crossX = acy * adz - acz * ady;
        double crossY = acz * adx - acx * adz;
        double crossZ = acx * ady - acy * adx;
        double vol6 = abx * crossX + aby * crossY + abz * crossZ;
        if (vol6 < 0) return new Tet(a, b, d, c);
        return new Tet(a, b, c, d);
    }

    /**
     * In-sphere predicate for a positively-oriented tetrahedron (a, b, c, d) and point p.
     * Returns true iff p is strictly inside the sphere through a/b/c/d.
     */
    private static boolean insideCircumsphere(
            double ax, double ay, double az,
            double bx, double by, double bz,
            double cx, double cy, double cz,
            double dx, double dy, double dz,
            double px, double py, double pz) {
        double ax_ = ax - px, ay_ = ay - py, az_ = az - pz;
        double bx_ = bx - px, by_ = by - py, bz_ = bz - pz;
        double cx_ = cx - px, cy_ = cy - py, cz_ = cz - pz;
        double dx_ = dx - px, dy_ = dy - py, dz_ = dz - pz;
        double aLift = ax_ * ax_ + ay_ * ay_ + az_ * az_;
        double bLift = bx_ * bx_ + by_ * by_ + bz_ * bz_;
        double cLift = cx_ * cx_ + cy_ * cy_ + cz_ * cz_;
        double dLift = dx_ * dx_ + dy_ * dy_ + dz_ * dz_;
        // 4x4 determinant of [(a-p) | aLift; (b-p) | bLift; (c-p) | cLift; (d-p) | dLift]
        // expanded along the last column.
        double det = aLift * det3(bx_, by_, bz_, cx_, cy_, cz_, dx_, dy_, dz_)
                   - bLift * det3(ax_, ay_, az_, cx_, cy_, cz_, dx_, dy_, dz_)
                   + cLift * det3(ax_, ay_, az_, bx_, by_, bz_, dx_, dy_, dz_)
                   - dLift * det3(ax_, ay_, az_, bx_, by_, bz_, cx_, cy_, cz_);
        return det > 0;
    }

    private static double det3(double a, double b, double c,
                                double d, double e, double f,
                                double g, double h, double i) {
        return a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    }

    record Tet(int a, int b, int c, int d) { }

    record Face(int a, int b, int c) {
        Face {
            // Sort the three indices ascending so {1,2,3} == {3,1,2} as map keys.
            int x = a, y = b, z = c;
            int t;
            if (x > y) { t = x; x = y; y = t; }
            if (y > z) { t = y; y = z; z = t; }
            if (x > y) { t = x; x = y; y = t; }
            a = x; b = y; c = z;
        }
    }

    record Edge(int a, int b) {
        static final Comparator<Edge> COMPARATOR =
                Comparator.comparingInt(Edge::a).thenComparingInt(Edge::b);
        Edge {
            if (a > b) { int t = a; a = b; b = t; }
        }
    }
}
