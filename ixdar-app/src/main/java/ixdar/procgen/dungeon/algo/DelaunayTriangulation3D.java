package ixdar.procgen.dungeon.algo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.geometry.mesh.data.EdgeKey;

/**
 * 3D Delaunay tetrahedralization over sites via incremental Bowyer-Watson, returning the
 * complex's unique edges as flat site-index pairs.
 *
 * <p>Tetrahedra are stored with positive signed volume, orientation being repaired at insertion
 * so the in-sphere determinant sign reads consistently.
 */
public final class DelaunayTriangulation3D {
    public static final int NUM_4 = 4;
    public static final int NUM_20 = 20;
    public static final int NUM_3 = 3;

    private static final int FACE_INDEX_BITS = 21;
    private static final int FACE_INDEX_LIMIT = 1 << FACE_INDEX_BITS;

    private DelaunayTriangulation3D() {
    }

    /**
     * Bowyer-Watson incremental insertion over the 3D sites, then strip tetrahedra touching
     * the super-tetrahedron and emit the unique edges sorted lexicographically.
     *
     * @param us x coordinate per site
     * @param vs y coordinate per site
     * @param ws z coordinate per site
     * @return flat pairs of site indices; empty for &lt; 2 sites, a single pair for 2 sites
     */
    public static int[] triangulate(double[] us, double[] vs, double[] ws) {
        int n = us.length;
        if (n < 2) {
            return new int[0];
        }
        if (n == 2) {
            return new int[] { 0, 1 };
        }

        // Pack point coordinates into parallel arrays. Indices [n .. n+3] reserved for the
        // super-tetrahedron's vertices.
        double[] xs = new double[n + NUM_4];
        double[] ys = new double[n + NUM_4];
        double[] zs = new double[n + NUM_4];
        System.arraycopy(us, 0, xs, 0, n);
        System.arraycopy(vs, 0, ys, 0, n);
        System.arraycopy(ws, 0, zs, 0, n);

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
        double bigR = NUM_20 * dmax;
        xs[n]   = midX - bigR; ys[n]   = midY - bigR; zs[n]   = midZ - bigR;
        xs[n+1] = midX + NUM_3*bigR; ys[n+1] = midY - bigR; zs[n+1] = midZ - bigR;
        xs[n+2] = midX - bigR; ys[n+2] = midY + NUM_3*bigR; zs[n+2] = midZ - bigR;
        xs[n+NUM_3] = midX - bigR; ys[n+NUM_3] = midY - bigR; zs[n+NUM_3] = midZ + NUM_3*bigR;

        // Tetrahedra are int[4] rows of site indices with positive signed volume; boundary
        // faces are ascending-sorted index triples packed 3 x 21 bits into a long map key.
        List<int[]> tets = new ArrayList<>();
        tets.add(orient(n, n + 1, n + 2, n + NUM_3, xs, ys, zs));

        for (int p = 0; p < n; p++) {
            List<int[]> bad = new ArrayList<>();
            for (int[] t : tets) {
                if (insideCircumsphere(
                        xs[t[0]], ys[t[0]], zs[t[0]],
                        xs[t[1]], ys[t[1]], zs[t[1]],
                        xs[t[2]], ys[t[2]], zs[t[2]],
                        xs[t[NUM_3]], ys[t[NUM_3]], zs[t[NUM_3]],
                        xs[p],   ys[p],   zs[p])) {
                    bad.add(t);
                }
            }
            // Boundary triangles: faces appearing in exactly one bad tet.
            Map<Long, Integer> faceCount = new LinkedHashMap<>();
            for (int[] t : bad) {
                faceCount.merge(faceKey(t[0], t[1], t[2]), 1, Integer::sum);
                faceCount.merge(faceKey(t[0], t[1], t[NUM_3]), 1, Integer::sum);
                faceCount.merge(faceKey(t[0], t[2], t[NUM_3]), 1, Integer::sum);
                faceCount.merge(faceKey(t[1], t[2], t[NUM_3]), 1, Integer::sum);
            }
            tets.removeAll(bad);
            for (Map.Entry<Long, Integer> e : faceCount.entrySet()) {
                if (e.getValue() == 1) {
                    long f = e.getKey();
                    tets.add(orient(faceVertex(f, 0), faceVertex(f, 1), faceVertex(f, 2), p, xs, ys, zs));
                }
            }
        }

        // Strip tets touching the super-tetrahedron, then collect unique edges.
        Set<Long> edges = new LinkedHashSet<>();
        for (int[] t : tets) {
            if (t[0] >= n || t[1] >= n || t[2] >= n || t[NUM_3] >= n) continue;
            edges.add(EdgeKey.undirected(t[0], t[1]));
            edges.add(EdgeKey.undirected(t[0], t[2]));
            edges.add(EdgeKey.undirected(t[0], t[NUM_3]));
            edges.add(EdgeKey.undirected(t[1], t[2]));
            edges.add(EdgeKey.undirected(t[1], t[NUM_3]));
            edges.add(EdgeKey.undirected(t[2], t[NUM_3]));
        }
        return DelaunayTriangulation2D.sortedPairs(edges);
    }

    /** Packs an unordered face's three site indices, sorted ascending, into 3 x 21 bits. */
    private static long faceKey(int a, int b, int c) {
        assert a < FACE_INDEX_LIMIT && b < FACE_INDEX_LIMIT && c < FACE_INDEX_LIMIT
                : "site index exceeds " + FACE_INDEX_BITS + " bits";
        int x = a;
        int y = b;
        int z = c;
        int t;
        if (x > y) { t = x; x = y; y = t; }
        if (y > z) { t = y; y = z; z = t; }
        if (x > y) { t = x; x = y; y = t; }
        return ((long) x << (2 * FACE_INDEX_BITS)) | ((long) y << FACE_INDEX_BITS) | z;
    }

    private static int faceVertex(long faceKey, int slot) {
        return (int) ((faceKey >>> ((2 - slot) * FACE_INDEX_BITS)) & (FACE_INDEX_LIMIT - 1));
    }

    /**
     * Returns the input vertex order if signed volume is positive, otherwise a transposed
     * order (swap c and d) so the resulting tet has positive volume. This keeps the
     * {@link #insideCircumsphere} sign reading consistent.
     */
    private static int[] orient(int a, int b, int c, int d, double[] xs, double[] ys, double[] zs) {
        double abx = xs[b] - xs[a], aby = ys[b] - ys[a], abz = zs[b] - zs[a];
        double acx = xs[c] - xs[a], acy = ys[c] - ys[a], acz = zs[c] - zs[a];
        double adx = xs[d] - xs[a], ady = ys[d] - ys[a], adz = zs[d] - zs[a];
        // Signed volume * 6 = (b-a) . ((c-a) x (d-a))
        double crossX = acy * adz - acz * ady;
        double crossY = acz * adx - acx * adz;
        double crossZ = acx * ady - acy * adx;
        double vol6 = abx * crossX + aby * crossY + abz * crossZ;
        if (vol6 < 0) return new int[] { a, b, d, c };
        return new int[] { a, b, c, d };
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
}
