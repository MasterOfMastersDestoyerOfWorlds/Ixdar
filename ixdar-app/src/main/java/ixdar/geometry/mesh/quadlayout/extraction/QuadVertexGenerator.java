package ixdar.geometry.mesh.quadlayout.extraction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.ArrayMesh;

/**
 * Stage 1 of QEx (Ebke 2013): walk the mesh + UV map and emit one
 * {@link QVert} for every integer (u, v) point in the parametric domain
 * whose preimage falls on the surface.
 *
 * <p>Three passes, matching {@code metriko/qex/gen_q_vert.h}:
 * <ol>
 *   <li><b>Vert pass.</b> Each mesh vertex sits at one (u, v) per incident
 *       face corner; if any of those (u, v) is integer (modulo 1 within
 *       {@link #EPS}), we emit one VERT-source QVert. The vertex's
 *       parametric coordinate is taken from the first checked corner.</li>
 *   <li><b>Edge pass.</b> For each interior edge, walk the integer-grid
 *       points lying on the segment between the two endpoint UVs and emit
 *       one EDGE-source QVert per collinear interior point.</li>
 *   <li><b>Face pass.</b> For each face, walk all integer (u, v) points
 *       inside the triangle's UV bounding box and emit one FACE-source
 *       QVert per point that lies strictly inside the UV triangle.</li>
 * </ol>
 *
 * <p>Tie-breaking on the integer boundary: a point on the parametric
 * boundary of two faces is owned by the EDGE pass (not duplicated in the
 * FACE pass). Likewise a point at a vertex is owned by the VERT pass.
 * Tolerance is {@link #EPS}.
 */
public final class QuadVertexGenerator {
    public static final int NUM_3 = 3;
    public static final float NUM_1 = 1f;
    public static final float NUM_3_2 = 3f;
    public static final int NUM_40 = 40;
    public static final int NUM_0xFFFF = 0xFFFFF;
    public static final int NUM_20 = 20;

    /**
     * Tolerance for "is integer" / "ratio in (EPS, 1-EPS)" / orientation
     *  sign tests. Tight because integer values are exact and orientation
     */
    public static final double EPS = 1e-7;

    /**
     * Tolerance for "perpendicular distance from integer point to mesh edge"
     *  collinearity tests. Looser than {@link #EPS} because real-world UVs
     *  (e.g. metriko stage2) come from a numerical solve and integer-aligned
     *  iso-lines through edges only land near (not exactly on) integer
     *  points. Default {@code 1e-4} (0.01% of a unit cell) — empirically
     *  the best quad-count balance on Hand-30k stage2 (above this, EDGE
     *  QVerts proliferate but cycle walks break because EDGE-port prev/next
     *  ordering isn't yet CCW-correct in 3D — see PATCH-62). Override via
     */
    public static final double COLLINEAR_EPS = Double.parseDouble(
            System.getProperty("ixdar.quadlayout.qex.collinearEps", "1e-4"));

    private QuadVertexGenerator() {}

    /**
     * Generate QVerts for {@code mesh} given a per-corner UV map.
     *
     * @param mesh mesh on which the UV map lives
     * @param uCorner per-corner u value, length {@code 3 * F}
     * @param vCorner per-corner v value, length {@code 3 * F}
     * @return per-source QVert lists for the VERT, EDGE, and FACE passes
     */
    public static Result generate(ArrayMesh mesh,
                                  float[] uCorner, float[] vCorner) {
        int F = mesh.faceCount();
        int V = mesh.vertexCount();
        int eCount = mesh.edgeCount();

        List<QVert> vertQVerts = new ArrayList<>();
        List<QVert> edgeQVerts = new ArrayList<>();
        List<QVert> faceQVerts = new ArrayList<>();

        // ---- VERT pass ----
        // For each mesh vertex, find any face corner that references it and
        // check if its (u, v) is integer. Use a one-corner-per-vertex visit
        // map so a vertex on a seam isn't double-counted.
        boolean[] visitedVert = new boolean[V];
        Vector3f vpos = new Vector3f();
        int idCounter = 0;
        for (int f = 0; f < F; f++) {
            for (int c = 0; c < NUM_3; c++) {
                int vId = mesh.faceVertexAt(f, c);
                if (visitedVert[vId]) continue;
                visitedVert[vId] = true;
                float u = uCorner[f * NUM_3 + c];
                float v = vCorner[f * NUM_3 + c];
                if (isInteger(u) && isInteger(v)) {
                    mesh.vertexPosition(vId, vpos);
                    vertQVerts.add(new QVert(idCounter++, QVert.Source.VERT, vId,
                            (float) Math.rint(u), (float) Math.rint(v),
                            new Vector3f(vpos)));
                }
            }
        }

        // ---- EDGE pass ----
        // Iterate every mesh edge (interior + boundary). For each, pick one
        // half-edge to read corner UVs from, then walk every integer point
        // inside the UV bounding box of the edge segment and emit a QVert
        // if the point is strictly between the endpoints.
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f scratch = new Vector3f();
        HashSet<Long> edgePointKeys = new HashSet<>();
        for (int eMesh = 0; eMesh < eCount; eMesh++) {
            if (!mesh.hasEdge(eMesh)) continue;
            int he = mesh.edgeHalfEdge(eMesh);
            if (he < 0) continue;
            int faceA = mesh.halfEdgeFace(he);
            if (faceA < 0) {
                // Pick the twin if this side has no face.
                int twin = mesh.halfEdgeTwin(he);
                if (twin < 0) continue;
                he = twin;
                faceA = mesh.halfEdgeFace(he);
                if (faceA < 0) continue;
            }
            int cornerA = he % NUM_3;
            int cornerB = mesh.halfEdgeNext(he) % NUM_3;
            int vertA = mesh.faceVertexAt(faceA, cornerA);
            int vertB = mesh.faceVertexAt(faceA, cornerB);
            float u0 = uCorner[faceA * NUM_3 + cornerA];
            float v0u = vCorner[faceA * NUM_3 + cornerA];
            float u1 = uCorner[faceA * NUM_3 + cornerB];
            float v1u = vCorner[faceA * NUM_3 + cornerB];

            int minX = (int) Math.floor(Math.min(u0, u1) - EPS);
            int maxX = (int) Math.ceil(Math.max(u0, u1) + EPS);
            int minY = (int) Math.floor(Math.min(v0u, v1u) - EPS);
            int maxY = (int) Math.ceil(Math.max(v0u, v1u) + EPS);

            mesh.vertexPosition(vertA, p0);
            mesh.vertexPosition(vertB, p1);

            for (int xi = minX; xi <= maxX; xi++) {
                for (int yi = minY; yi <= maxY; yi++) {
                    double a = collinearRatio(u0, v0u, u1, v1u, xi, yi);
                    if (a > EPS && a < 1.0 - EPS) {
                        long key = pack(eMesh, xi, yi);
                        if (!edgePointKeys.add(key)) continue;
                        scratch.set(p1).sub(p0).mul((float) a).add(p0);
                        edgeQVerts.add(new QVert(idCounter++, QVert.Source.EDGE,
                                eMesh, xi, yi, new Vector3f(scratch)));
                    }
                }
            }
        }

        // ---- FACE pass ----
        // For each face, walk every integer point in its UV bounding box and
        // emit a QVert if the point is STRICTLY inside the triangle (excludes
        // edges + vertices, those are owned by the prior passes).
        Vector3f q0 = new Vector3f();
        Vector3f q1 = new Vector3f();
        Vector3f q2 = new Vector3f();
        for (int f = 0; f < F; f++) {
            float u0 = uCorner[f * NUM_3];
            float v0 = vCorner[f * NUM_3];
            float u1 = uCorner[f * NUM_3 + 1];
            float v1 = vCorner[f * NUM_3 + 1];
            float u2 = uCorner[f * NUM_3 + 2];
            float v2 = vCorner[f * NUM_3 + 2];

            int minX = (int) Math.floor(Math.min(u0, Math.min(u1, u2)) - EPS);
            int maxX = (int) Math.ceil (Math.max(u0, Math.max(u1, u2)) + EPS);
            int minY = (int) Math.floor(Math.min(v0, Math.min(v1, v2)) - EPS);
            int maxY = (int) Math.ceil (Math.max(v0, Math.max(v1, v2)) + EPS);

            mesh.vertexPosition(mesh.faceVertexAt(f, 0), q0);
            mesh.vertexPosition(mesh.faceVertexAt(f, 1), q1);
            mesh.vertexPosition(mesh.faceVertexAt(f, 2), q2);

            for (int xi = minX; xi <= maxX; xi++) {
                for (int yi = minY; yi <= maxY; yi++) {
                    if (!strictlyInsideTriangle(u0, v0, u1, v1, u2, v2, xi, yi)) continue;
                    Vector3f pos = barycentricInterp(u0, v0, u1, v1, u2, v2,
                            xi, yi, q0, q1, q2);
                    faceQVerts.add(new QVert(idCounter++, QVert.Source.FACE,
                            f, xi, yi, pos));
                }
            }
        }

        return new Result(vertQVerts, edgeQVerts, faceQVerts);
    }

    private static boolean isInteger(double x) {
        double frac = Math.abs(x - Math.rint(x));
        return frac < EPS;
    }

    /**
     * If (xi, yi) is collinear with the segment (u0, v0)-(u1, v1) within
     * {@link #EPS}, return its parametric ratio along the segment (0 at start,
     * 1 at end). Otherwise return {@link Double#NaN}.
     *
     * @param u0 segment-start u
     * @param v0 segment-start v
     * @param u1 segment-end u
     * @param v1 segment-end v
     * @param xi integer-grid u
     * @param yi integer-grid v
     * @return parametric ratio in {@code [0, 1]}, or {@link Double#NaN} if not collinear within tolerance
     */
    private static double collinearRatio(double u0, double v0,
                                         double u1, double v1,
                                         double xi, double yi) {
        double dx = u1 - u0;
        double dy = v1 - v0;
        double len2 = dx * dx + dy * dy;
        if (len2 < EPS * EPS) return Double.NaN;
        // Perpendicular distance from (xi, yi) to the line through (u0, v0)-(u1, v1):
        //   d = |cross| / sqrt(len2)
        // We compare d directly against COLLINEAR_EPS (geometric absolute
        // tolerance, no segment-length scaling).
        double cross = (xi - u0) * dy - (yi - v0) * dx;
        double perpDist = Math.abs(cross) / Math.sqrt(len2);
        if (perpDist > COLLINEAR_EPS) return Double.NaN;
        // Project onto segment to get the ratio.
        double dot = (xi - u0) * dx + (yi - v0) * dy;
        return dot / len2;
    }

    /**
     * Strict-inside test: orientation of (xi, yi) against all three edges
     * of the triangle must be the same sign and strictly bounded away from
     * zero by {@link #EPS}.
     *
     * @param u0 first-corner u
     * @param v0 first-corner v
     * @param u1 second-corner u
     * @param v1 second-corner v
     * @param u2 third-corner u
     * @param v2 third-corner v
     * @param xi candidate integer-grid u
     * @param yi candidate integer-grid v
     * @return true if {@code (xi, yi)} lies strictly inside (excludes edges/vertices)
     */
    private static boolean strictlyInsideTriangle(double u0, double v0,
                                                  double u1, double v1,
                                                  double u2, double v2,
                                                  double xi, double yi) {
        double o0 = orient(u0, v0, u1, v1, xi, yi);
        double o1 = orient(u1, v1, u2, v2, xi, yi);
        double o2 = orient(u2, v2, u0, v0, xi, yi);
        return (o0 > EPS && o1 > EPS && o2 > EPS)
                || (o0 < -EPS && o1 < -EPS && o2 < -EPS);
    }

    private static double orient(double ax, double ay, double bx, double by,
                                 double cx, double cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    /**
     * Recover 3D position by mapping (xi, yi) into the triangle's barycentric coords.
     *
     * @param u0 first-corner u
     * @param v0 first-corner v
     * @param u1 second-corner u
     * @param v1 second-corner v
     * @param u2 third-corner u
     * @param v2 third-corner v
     * @param xi target integer-grid u
     * @param yi target integer-grid v
     * @param p0 first-corner 3D position
     * @param p1 second-corner 3D position
     * @param p2 third-corner 3D position
     * @return 3D position interpolated by the barycentric weights of {@code (xi, yi)}; falls back to centroid for degenerate triangles
     */
    private static Vector3f barycentricInterp(double u0, double v0,
                                              double u1, double v1,
                                              double u2, double v2,
                                              double xi, double yi,
                                              Vector3f p0, Vector3f p1, Vector3f p2) {
        double denom = orient(u0, v0, u1, v1, u2, v2);
        if (Math.abs(denom) < EPS) {
            // Degenerate triangle — fall back to centroid.
            return new Vector3f(p0).add(p1).add(p2).mul(NUM_1 / NUM_3_2);
        }
        double l0 = orient(u1, v1, u2, v2, xi, yi) / denom;
        double l1 = orient(u2, v2, u0, v0, xi, yi) / denom;
        double l2 = 1.0 - l0 - l1;
        Vector3f out = new Vector3f();
        out.x = (float) (l0 * p0.x + l1 * p1.x + l2 * p2.x);
        out.y = (float) (l0 * p0.y + l1 * p1.y + l2 * p2.y);
        out.z = (float) (l0 * p0.z + l1 * p1.z + l2 * p2.z);
        return out;
    }

    /**
     * Pack (edgeId, xi, yi) into a 64-bit dedupe key.
     *
     * @param e  mesh edge id
     * @param xi integer u
     * @param yi integer v
     * @return 64-bit hash key combining the three components
     */
    private static long pack(int e, int xi, int yi) {
        return ((long) e << NUM_40) | ((long) (xi & NUM_0xFFFF) << NUM_20) | (long) (yi & NUM_0xFFFF);
    }

    /** Result of the vertex-generation pass: per-source QVert lists. */
    public record Result(List<QVert> vertQVerts,
                         List<QVert> edgeQVerts,
                         List<QVert> faceQVerts) {

        /**
         * Combined size of the vert-, edge-, and face-source QVert lists.
         *
         * @return total QVert count across the VERT, EDGE, and FACE source lists
         */
        public int total() {
            return vertQVerts.size() + edgeQVerts.size() + faceQVerts.size();
        }
    }
}
