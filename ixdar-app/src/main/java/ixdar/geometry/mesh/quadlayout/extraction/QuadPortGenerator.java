package ixdar.geometry.mesh.quadlayout.extraction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.ArrayMesh;

/**
 * Stage 2 of QEx (Ebke 2013): emit {@link QPort}s for every {@link QVert}.
 *
 * <p>Phase B/C handles all three source kinds with proper parametric
 * wedges. Mirrors metriko's {@code generate_*_qport} routines:
 *
 * <ul>
 *   <li><b>FACE QVert:</b> 4 ports at the QVert's UV, all 4 cardinals,
 *       all anchored to the source face. Trivial — the QVert is strictly
 *       inside the triangle so every cardinal points into a valid face
 *       wedge.</li>
 *   <li><b>EDGE QVert:</b> Walk both half-edges of the source edge. For
 *       each face, emit cardinals that point INTO that face's wedge
 *       (the half-plane bounded by the shared edge). Deduped across both
 *       faces by 3D extrinsic direction so a cardinal that aligns with
 *       the edge in 3D doesn't get emitted twice. Total: 4 ports.</li>
 *   <li><b>VERT QVert:</b> Walk every incident half-edge of the source
 *       vertex. For each face, emit cardinals that point INTO that face's
 *       wedge at the vertex. Regular interior vertex emits 4 ports
 *       (one per quadrant); singularities emit more or fewer; boundary
 *       vertices emit fewer.</li>
 * </ul>
 *
 * <p>Ports per QVert are prev/next-linked cyclically in CCW order around
 * the QVert (mesh-CCW for FACE/EDGE, surface-CCW one-ring for VERT).
 */
public final class QuadPortGenerator {
    public static final int NUM_4 = 4;
    public static final int NUM_3 = 3;
    public static final float NUM_1e_20 = 1e-20f;
    public static final float NUM_0 = 0f;
    public static final float NUM_1 = 1f;
    public static final float NUM_1e_9 = 1e-9f;
    public static final float NUM_1e_5 = 1e-5f;

    /** Tolerance for orientation / collinearity tests. */
    private static final double EPS = QuadVertexGenerator.EPS;

    /** Four cardinal (du, dv) pairs in CCW order: +u, +v, -u, -v. */
    private static final int[] DIR_U = { 1,  0, -1,  0 };
    private static final int[] DIR_V = { 0,  1,  0, -1 };

    private QuadPortGenerator() {}

    /**
     * TODO: document {@code generate}.
     *
     * @param mesh TODO: describe
     * @param qVerts TODO: describe
     * @param uCorner TODO: describe
     * @param vCorner TODO: describe
     * @return TODO: describe
     */
    public static Result generate(ArrayMesh mesh,
                                  QuadVertexGenerator.Result qVerts,
                                  float[] uCorner, float[] vCorner) {
        ArrayList<QPort> ports = new ArrayList<>();
        HashMap<Integer, int[]> byQVert = new HashMap<>();

        // ---- FACE QVerts ----
        for (QVert qv : qVerts.faceQVerts()) {
            int[] portIds = emitFourCardinals(qv, qv.sourceId(), ports);
            byQVert.put(qv.id(), portIds);
        }

        // ---- EDGE QVerts ----
        for (QVert qv : qVerts.edgeQVerts()) {
            int[] portIds = emitEdgePorts(mesh, qv, uCorner, vCorner, ports);
            if (portIds.length > 0) byQVert.put(qv.id(), portIds);
        }

        // ---- VERT QVerts ----
        for (QVert qv : qVerts.vertQVerts()) {
            int[] portIds = emitVertPorts(mesh, qv, uCorner, vCorner, ports);
            if (portIds.length > 0) byQVert.put(qv.id(), portIds);
        }

        return new Result(ports, byQVert);
    }

    /** FACE-source path: 4 cardinals all in the source face. */
    private static int[] emitFourCardinals(QVert qv, int faceId, List<QPort> ports) {
        int[] ids = new int[NUM_4];
        int base = ports.size();
        for (int i = 0; i < NUM_4; i++) {
            ids[i] = base + i;
            ports.add(new QPort(base + i, qv.id(), qv.source(), qv.sourceId(),
                    faceId, qv.u(), qv.v(),
                    DIR_U[i], DIR_V[i],
                    new Vector3f(qv.position())));
        }
        cyclicLink(ports, ids);
        return ids;
    }

    /**
     * EDGE-source path: walk both half-edges of the source edge, emit
     * cardinals that point INTO each face's wedge at the QVert.
     *
     * <p>{@code orientation(uv_a, uv_b, uv + dir) > 0} = {@code uv + dir}
     * lies on the LEFT of the directed edge {@code (uv_a, uv_b)}. The
     * face's interior is on one side of its edges; we walk both faces and
     * pick whichever side puts {@code uv + dir} into that face's wedge.
     */
    private static int[] emitEdgePorts(ArrayMesh mesh, QVert qv,
                                        float[] uCorner, float[] vCorner,
                                        List<QPort> ports) {
        int eId = qv.sourceId();
        if (!mesh.hasEdge(eId)) return new int[0];
        int he = mesh.edgeHalfEdge(eId);
        int twin = mesh.halfEdgeTwin(he);
        int[] halves = (twin >= 0) ? new int[]{he, twin} : new int[]{he};

        ArrayList<PortCandidate> cands = new ArrayList<>();
        ArrayList<Vector3f> seen3D = new ArrayList<>();
        Vector3f normalAccum = new Vector3f();

        for (int hSide : halves) {
            int faceId = mesh.halfEdgeFace(hSide);
            if (faceId < 0) continue;
            normalAccum.add(faceNormal(mesh, faceId));
            int cTail = hSide % NUM_3;
            int cHead = mesh.halfEdgeNext(hSide) % NUM_3;
            float aU = uCorner[faceId * NUM_3 + cTail];
            float aV = vCorner[faceId * NUM_3 + cTail];
            float bU = uCorner[faceId * NUM_3 + cHead];
            float bV = vCorner[faceId * NUM_3 + cHead];

            // For each cardinal direction d, check if (qv.uv + d) lies in
            // the face's wedge — i.e., on the LEFT of edge a→b (since the
            // face is on the LEFT of its CCW-ordered edges).
            for (int i = 0; i < NUM_4; i++) {
                double tu = qv.u() + DIR_U[i];
                double tv = qv.v() + DIR_V[i];
                double cross = (bU - aU) * (tv - aV) - (bV - aV) * (tu - aU);
                if (cross < EPS) continue;  // not on face's interior side
                Vector3f d3D = directionInWorld(mesh, faceId, uCorner, vCorner,
                        qv.u(), qv.v(), DIR_U[i], DIR_V[i]);
                if (d3D == null) continue;
                if (already3D(seen3D, d3D)) continue;
                seen3D.add(d3D);
                cands.add(new PortCandidate(faceId, i, d3D));
            }
        }

        return finalisePorts(qv, cands, normalAccum, ports);
    }

    /**
     * VERT-source path: walk every face incident to the source vertex,
     * emit cardinals that point INTO that face's wedge at the vertex.
     *
     * <p>For face {@code f} with vertex at corner {@code c}, the wedge is
     * bounded by the two outgoing edges {@code c→(c+1)} and
     * {@code c→(c+2)}. A direction {@code d} is in the wedge iff its
     * polar angle is between those two edges (CCW from edge1 to edge2).
     */
    private static int[] emitVertPorts(ArrayMesh mesh, QVert qv,
                                        float[] uCorner, float[] vCorner,
                                        List<QPort> ports) {
        int vId = qv.sourceId();
        if (vId < 0 || vId >= mesh.vertexCount()) return new int[0];

        ArrayList<PortCandidate> cands = new ArrayList<>();
        ArrayList<Vector3f> seen3D = new ArrayList<>();
        Vector3f normalAccum = new Vector3f();

        int outCount = mesh.vertexOutgoingHalfEdgeCount(vId);
        for (int oh = 0; oh < outCount; oh++) {
            int he = mesh.vertexOutgoingHalfEdgeAt(vId, oh);
            int faceId = mesh.halfEdgeFace(he);
            if (faceId < 0) continue;
            normalAccum.add(faceNormal(mesh, faceId));
            int cV = he % NUM_3;
            int cN = mesh.halfEdgeNext(he) % NUM_3;
            int cP = mesh.halfEdgePrev(he) % NUM_3;
            float vU = uCorner[faceId * NUM_3 + cV];
            float vV = vCorner[faceId * NUM_3 + cV];
            float nU = uCorner[faceId * NUM_3 + cN];
            float nV = vCorner[faceId * NUM_3 + cN];
            float pU = uCorner[faceId * NUM_3 + cP];
            float pV = vCorner[faceId * NUM_3 + cP];

            double e1x = nU - vU, e1y = nV - vV;
            double e2x = pU - vU, e2y = pV - vV;

            for (int i = 0; i < NUM_4; i++) {
                double dx = DIR_U[i];
                double dy = DIR_V[i];
                if (!inWedgeCCW(e1x, e1y, e2x, e2y, dx, dy)) continue;
                Vector3f d3D = directionInWorld(mesh, faceId, uCorner, vCorner,
                        qv.u(), qv.v(), DIR_U[i], DIR_V[i]);
                if (d3D == null) continue;
                if (already3D(seen3D, d3D)) continue;
                seen3D.add(d3D);
                cands.add(new PortCandidate(faceId, i, d3D));
            }
        }

        return finalisePorts(qv, cands, normalAccum, ports);
    }

    /**
     * Finalise a list of {@link PortCandidate}s into actual {@link QPort}s
     * appended to {@code ports}, with prev/next-cyclic links in 3D-CCW
     * order around the QVert's normal.
     *
     * <p>Sorting matters: the QFace 4-cycle walk in
     * {@link QuadFaceGenerator} follows {@code port.prev} at each turn —
     * for the cycle to close it must be a 90° turn in the surface tangent
     * plane. Insertion order from the per-face wedge iteration isn't
     * guaranteed to be CCW around the QVert; explicit polar-angle sort
     * makes it so.
     */
    private static int[] finalisePorts(QVert qv, ArrayList<PortCandidate> cands,
                                        Vector3f normalAccum, List<QPort> ports) {
        int count = cands.size();
        if (count == 0) return new int[0];

        // Build a tangent frame at the QVert: normalise the accumulated
        // face-normal to get the QVert normal; pick any unit vector e1
        // perpendicular to it; e2 = normal × e1. Polar angle of dir3D =
        // atan2(dir3D · e2, dir3D · e1).
        Vector3f normal = new Vector3f(normalAccum);
        if (normal.lengthSquared() < NUM_1e_20) {
            normal.set(NUM_0, NUM_0, NUM_1);
        } else {
            normal.normalize();
        }
        Vector3f e1 = new Vector3f();
        if (Math.abs(normal.x) <= Math.abs(normal.y)
                && Math.abs(normal.x) <= Math.abs(normal.z)) {
            e1.set(NUM_1, NUM_0, NUM_0);
        } else if (Math.abs(normal.y) <= Math.abs(normal.z)) {
            e1.set(NUM_0, NUM_1, NUM_0);
        } else {
            e1.set(NUM_0, NUM_0, NUM_1);
        }
        e1.sub(new Vector3f(normal).mul(normal.dot(e1)));
        if (e1.lengthSquared() < NUM_1e_20) e1.set(NUM_1, NUM_0, NUM_0);
        e1.normalize();
        Vector3f e2 = new Vector3f(normal).cross(e1).normalize();

        record AngleEntry(double angle, PortCandidate cand) {}
        ArrayList<AngleEntry> annotated = new ArrayList<>(count);
        for (PortCandidate c : cands) {
            double x = c.dir3D.dot(e1);
            double y = c.dir3D.dot(e2);
            double angle = Math.atan2(y, x);
            annotated.add(new AngleEntry(angle, c));
        }
        annotated.sort((a, b) -> Double.compare(a.angle, b.angle));

        int[] ids = new int[count];
        int base = ports.size();
        for (int k = 0; k < count; k++) {
            PortCandidate c = annotated.get(k).cand;
            ids[k] = base + k;
            ports.add(new QPort(base + k, qv.id(), qv.source(), qv.sourceId(),
                    c.faceId, qv.u(), qv.v(),
                    DIR_U[c.dirIdx], DIR_V[c.dirIdx],
                    new Vector3f(qv.position())));
        }
        cyclicLink(ports, ids);
        return ids;
    }

    /** Unit-vector face normal (cross product of two edges, normalised). */
    private static Vector3f faceNormal(ArrayMesh mesh, int faceId) {
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        Vector3f c = new Vector3f();
        mesh.vertexPosition(mesh.faceVertexAt(faceId, 0), a);
        mesh.vertexPosition(mesh.faceVertexAt(faceId, 1), b);
        mesh.vertexPosition(mesh.faceVertexAt(faceId, 2), c);
        Vector3f e1 = new Vector3f(b).sub(a);
        Vector3f e2 = new Vector3f(c).sub(a);
        Vector3f n = new Vector3f(e1).cross(e2);
        if (n.lengthSquared() < NUM_1e_20) return new Vector3f();
        return n.normalize();
    }

    /**
     * Check whether direction (dx, dy) lies in the wedge bounded by
     * (e1x, e1y) and (e2x, e2y), going CCW from e1 to e2.
     *
     * <p>Standard test: cross(e1, d) > 0 AND cross(d, e2) > 0. Boundaries
     * (parallel to e1 or e2) are accepted with the EPS margin so cardinals
     * that align exactly with a wedge edge get one port (not zero or two).
     */
    private static boolean inWedgeCCW(double e1x, double e1y,
                                      double e2x, double e2y,
                                      double dx, double dy) {
        double c1 = e1x * dy - e1y * dx;   // cross(e1, d)
        double c2 = dx * e2y - dy * e2x;   // cross(d, e2)
        // Total angle of wedge:
        double cTotal = e1x * e2y - e1y * e2x;
        if (cTotal > 0) {
            // CCW wedge less than 180°.
            return c1 >= -EPS && c2 >= -EPS;
        } else {
            // Reflex wedge (> 180°): direction is in iff NOT in the
            // complementary acute wedge.
            return !(c1 < -EPS && c2 < -EPS);
        }
    }

    /**
     * Convert a 2D direction at (uvU, uvV) in face {@code faceId} into a
     * 3D world-space direction unit vector, used for cross-face dedupe.
     * Returns null if the face is degenerate.
     */
    private static Vector3f directionInWorld(ArrayMesh mesh, int faceId,
                                              float[] uCorner, float[] vCorner,
                                              double uvU, double uvV,
                                              double dirU, double dirV) {
        Vector3f p = baryToWorld(mesh, faceId, uCorner, vCorner, uvU, uvV);
        Vector3f q = baryToWorld(mesh, faceId, uCorner, vCorner, uvU + dirU, uvV + dirV);
        if (p == null || q == null) return null;
        Vector3f d = q.sub(p);
        float len = d.length();
        if (len < NUM_1e_9) return null;
        d.div(len);
        return d;
    }

    private static Vector3f baryToWorld(ArrayMesh mesh, int faceId,
                                        float[] uCorner, float[] vCorner,
                                        double uvU, double uvV) {
        float u0 = uCorner[faceId * NUM_3];
        float v0 = vCorner[faceId * NUM_3];
        float u1 = uCorner[faceId * NUM_3 + 1];
        float v1 = vCorner[faceId * NUM_3 + 1];
        float u2 = uCorner[faceId * NUM_3 + 2];
        float v2 = vCorner[faceId * NUM_3 + 2];
        double denom = (u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0);
        if (Math.abs(denom) < EPS) return null;
        double l1 = ((uvU - u0) * (v2 - v0) - (uvV - v0) * (u2 - u0)) / denom;
        double l2 = ((u1 - u0) * (uvV - v0) - (v1 - v0) * (uvU - u0)) / denom;
        double l0 = 1.0 - l1 - l2;
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        mesh.vertexPosition(mesh.faceVertexAt(faceId, 0), p0);
        mesh.vertexPosition(mesh.faceVertexAt(faceId, 1), p1);
        mesh.vertexPosition(mesh.faceVertexAt(faceId, 2), p2);
        return new Vector3f(
                (float) (l0 * p0.x + l1 * p1.x + l2 * p2.x),
                (float) (l0 * p0.y + l1 * p1.y + l2 * p2.y),
                (float) (l0 * p0.z + l1 * p1.z + l2 * p2.z));
    }

    private static boolean already3D(List<Vector3f> seen, Vector3f d) {
        for (Vector3f s : seen) {
            if (Math.abs(s.x - d.x) < NUM_1e_5
                    && Math.abs(s.y - d.y) < NUM_1e_5
                    && Math.abs(s.z - d.z) < NUM_1e_5) return true;
        }
        return false;
    }

    /** Prev/next-link the given list of port ids cyclically in their list order. */
    private static void cyclicLink(List<QPort> ports, int[] ids) {
        int n = ids.length;
        if (n == 0) return;
        for (int i = 0; i < n; i++) {
            ports.get(ids[i]).prevPort = ids[(i - 1 + n) % n];
            ports.get(ids[i]).nextPort = ids[(i + 1) % n];
        }
    }

    // Suppress unused warnings on legacy helper paths.
    @SuppressWarnings("unused")
    private static void hashSetMarker(HashSet<?> ignore) {}

    public record Result(List<QPort> ports,
                         /** ports[i] indices grouped by QVert id. */
                         HashMap<Integer, int[]> portsByQVert) {
    }

    /**
     * Internal record: one candidate port to emit, with its 3D direction.
     */
    private record PortCandidate(int faceId, int dirIdx, Vector3f dir3D) {}
}
