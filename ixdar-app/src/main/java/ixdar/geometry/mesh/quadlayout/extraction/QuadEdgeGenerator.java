package ixdar.geometry.mesh.quadlayout.extraction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import ixdar.geometry.mesh.data.ArrayMesh;

/**
 * Stage 3 of QEx (Ebke 2013): connect ports into {@link QEdge}s along the
 * parametric iso-lines.
 *
 * <p>Phase B/C — full cross-triangle tracing:
 * <ol>
 *   <li>Group ports by face for fast same-face mate lookup.</li>
 *   <li>For each unconnected port {@code P} at (uv, dir): try to find a
 *       same-face port at {@code uv + dir} pointing back ({@code -dir}).
 *       If found, connect.</li>
 *   <li>If no same-face mate, advance the iso-line into the neighbouring
 *       triangle: find which face edge the iso-line exits, transform
 *       (uv, dir) by the half-edge's TRS matrix
 *       (rotation R + translation T from {@link TransitionMatrix}), and
 *       repeat in the new face.</li>
 *   <li>Cap the hop count to prevent runaway tracing on bad inputs;
 *       unconnected ports at the cap stay unconnected.</li>
 * </ol>
 */
public final class QuadEdgeGenerator {

    public static final double EPS = QuadVertexGenerator.EPS;

    /** Match tolerance for the iso-line endpoint lookup. Looser than
     *  {@link #EPS} so small float drift accumulated across TRS hops
     *  doesn't reject legitimate same-target ports. */
    public static final double MATCH_EPS = 1e-3;

    /** Max face hops before giving up on an iso-line. Iso-lines on a
     *  30k-tri mesh routinely cross 30+ mesh edges between integer
     *  points; cap stays generous so we never reject a legitimate match
     *  on path length. */
    private static final int MAX_HOPS = 256;

    /** PATCH-61: per-trace outcome counters for diagnostics. Cleared on
     *  every {@link #generate} call; readable via the {@code static} fields
     *  for caller-side reporting. */
    public static int statTracesAttempted;
    public static int statSameFaceHits;
    public static int statCrossFaceHits;
    public static int statHopCapHits;
    public static int statNoExitFound;
    public static int statBoundaryHit;

    /** PATCH-61 debug: when set, the first {@code N} unconnected traces dump
     *  per-hop state to stdout. Set via system property
     *  {@code ixdar.quadlayout.qex.tracePorts=N}. */
    private static final int TRACE_PORTS = Integer.getInteger(
            "ixdar.quadlayout.qex.tracePorts", 0);
    private static int tracedSoFar;

    private QuadEdgeGenerator() {}

    public static List<QEdge> generate(ArrayMesh mesh,
                                       List<QPort> ports,
                                       float[] uCorner, float[] vCorner,
                                       TransitionMatrix trs) {
        statTracesAttempted = 0;
        statSameFaceHits = 0;
        statCrossFaceHits = 0;
        statHopCapHits = 0;
        statNoExitFound = 0;
        statBoundaryHit = 0;
        tracedSoFar = 0;
        // Bucket ports by faceId.
        HashMap<Integer, List<Integer>> byFace = new HashMap<>();
        for (QPort p : ports) {
            byFace.computeIfAbsent(p.faceId, k -> new ArrayList<>()).add(p.id);
        }

        ArrayList<QEdge> edges = new ArrayList<>();
        for (int i = 0; i < ports.size(); i++) {
            QPort src = ports.get(i);
            if (src.connected) continue;
            statTracesAttempted++;
            int mate = traceIsoLine(mesh, ports, byFace, uCorner, vCorner, trs, src);
            if (mate >= 0) {
                int edgeId = edges.size();
                src.connected = true;
                src.connectedEdgeId = edgeId;
                QPort mp = ports.get(mate);
                mp.connected = true;
                mp.connectedEdgeId = edgeId;
                edges.add(new QEdge(edgeId, src.id, mate));
            }
        }
        return edges;
    }

    /**
     * Trace the iso-line emanating from {@code src} through the surface
     * until we land on a matching port, return its id; -1 if no match
     * within {@link #MAX_HOPS} hops.
     */
    private static int traceIsoLine(ArrayMesh mesh, List<QPort> ports,
                                    HashMap<Integer, List<Integer>> byFace,
                                    float[] uCorner, float[] vCorner,
                                    TransitionMatrix trs, QPort src) {
        int curFace = src.faceId;
        double curU = src.uvU;
        double curV = src.uvV;
        double dirU = src.dirU;
        double dirV = src.dirV;
        // Fixed iso-line endpoint in the CURRENT face's frame. Stays at
        // the original integer (uv + dir) and gets transformed by TRS at
        // each face crossing — NOT drifted to "current uv + dir" as the
        // ray walks across triangles.
        double targetU = src.uvU + src.dirU;
        double targetV = src.uvV + src.dirV;
        // Half-edge we just entered curFace through — skip it when picking
        // the exit edge so we don't immediately ping-pong back. -1 on the
        // first hop (we're starting inside the face, not entering).
        int entryHalfEdge = -1;
        boolean trace = tracedSoFar < TRACE_PORTS;
        if (trace) {
            tracedSoFar++;
            System.out.printf("[trace] port %d qVert=%d face=%d uv=(%.4f,%.4f) dir=(%.1f,%.1f)%n",
                    src.id, src.qVertId, curFace, curU, curV, dirU, dirV);
        }

        for (int hop = 0; hop < MAX_HOPS; hop++) {
            // Target is the fixed iso-line endpoint in the current face's
            // frame, transformed forward by TRS at every face crossing.
            if (trace && hop < 12) {
                int nFacePortsHere = byFace.containsKey(curFace) ? byFace.get(curFace).size() : 0;
                System.out.printf("  hop %d face=%d uv=(%.4f,%.4f) target=(%.4f,%.4f) dir=(%.2f,%.2f) facePorts=%d%n",
                        hop, curFace, curU, curV, targetU, targetV, dirU, dirV, nFacePortsHere);
            }
            List<Integer> facePorts = byFace.get(curFace);
            if (facePorts != null) {
                for (int pid : facePorts) {
                    if (pid == src.id) continue;
                    QPort cand = ports.get(pid);
                    if (cand.connected) continue;
                    if (Math.abs(cand.uvU - targetU) > MATCH_EPS) continue;
                    if (Math.abs(cand.uvV - targetV) > MATCH_EPS) continue;
                    if (Math.abs(cand.dirU + dirU) > MATCH_EPS) continue;
                    if (Math.abs(cand.dirV + dirV) > MATCH_EPS) continue;
                    if (hop == 0) statSameFaceHits++; else statCrossFaceHits++;
                    return pid;
                }
            }

            // No same-face mate — try to advance through the face by
            // finding which face edge the iso-line exits. Skip the
            // half-edge we just entered through to avoid ping-ponging.
            int[] exit = findExitHalfEdge(mesh, curFace, curU, curV, dirU, dirV,
                    uCorner, vCorner, entryHalfEdge);
            if (exit == null) {
                statNoExitFound++;
                return -1;
            }
            int hExit = exit[0];
            int twin = mesh.halfEdgeTwin(hExit);
            if (twin < 0) { statBoundaryHit++; return -1; }
            int nextFace = mesh.halfEdgeFace(twin);
            if (nextFace < 0) { statBoundaryHit++; return -1; }

            // Compute hit point in current face's UV frame, then transform
            // it (and direction) into the neighbour's frame via the TRS.
            double hitU = curU + dirU * exit[1] / 1000.0;
            double hitV = curV + dirV * exit[1] / 1000.0;
            // exit[1] is the t along the iso-ray that hits the face edge,
            // encoded as int (t * 1000) for rough storage; recompute it
            // properly using the float ratios returned via a re-call.
            float[] hit = computeHitPoint(curU, curV, dirU, dirV, hExit,
                    curFace, uCorner, vCorner);
            if (hit == null) return -1;
            hitU = hit[0];
            hitV = hit[1];

            // Apply rotation + translation to point, direction, AND target.
            float[] uv = {(float) hitU, (float) hitV};
            float[] d = {(float) dirU, (float) dirV};
            float[] tgt = {(float) targetU, (float) targetV};
            trs.transformPoint(hExit, uv);
            trs.transformDirection(hExit, d);
            trs.transformPoint(hExit, tgt);
            curU = uv[0];
            curV = uv[1];
            dirU = d[0];
            dirV = d[1];
            targetU = tgt[0];
            targetV = tgt[1];
            curFace = nextFace;
            entryHalfEdge = twin;   // we entered nextFace via twin
        }
        statHopCapHits++;
        return -1;
    }

    /**
     * Find which face edge the ray {@code (uv, dir)} exits the triangle
     * through, and how far along that edge the hit is.
     *
     * @return [halfEdgeId, ratioAlongRay*1000 (legacy)] or null if no hit
     */
    private static int[] findExitHalfEdge(ArrayMesh mesh, int faceId,
                                          double curU, double curV,
                                          double dirU, double dirV,
                                          float[] uCorner, float[] vCorner,
                                          int skipHalfEdge) {
        // Walk the 3 half-edges of the face and find the one whose UV
        // segment intersects the forward ray (curU, curV) + t*(dirU, dirV)
        // at t > EPS. Skip {@code skipHalfEdge} (the entry half-edge) so
        // we don't immediately exit back the way we came.
        int bestHe = -1;
        double bestT = Double.POSITIVE_INFINITY;
        for (int c = 0; c < 3; c++) {
            int he = faceId * 3 + c;
            if (he == skipHalfEdge) continue;
            float aU = uCorner[faceId * 3 + c];
            float aV = vCorner[faceId * 3 + c];
            float bU = uCorner[faceId * 3 + (c + 1) % 3];
            float bV = vCorner[faceId * 3 + (c + 1) % 3];
            double[] rs = raySegmentIntersect(curU, curV, dirU, dirV,
                    aU, aV, bU, bV);
            if (rs == null) continue;
            double t = rs[0];
            if (t > EPS && t < bestT) {
                bestT = t;
                bestHe = he;
            }
        }
        if (bestHe < 0) return null;
        return new int[]{bestHe, (int) Math.round(bestT * 1000)};
    }

    /** Recompute the actual UV hit point at the face boundary. */
    private static float[] computeHitPoint(double curU, double curV,
                                            double dirU, double dirV,
                                            int hExit, int faceId,
                                            float[] uCorner, float[] vCorner) {
        int c = hExit % 3;
        float aU = uCorner[faceId * 3 + c];
        float aV = vCorner[faceId * 3 + c];
        float bU = uCorner[faceId * 3 + (c + 1) % 3];
        float bV = vCorner[faceId * 3 + (c + 1) % 3];
        double[] rs = raySegmentIntersect(curU, curV, dirU, dirV, aU, aV, bU, bV);
        if (rs == null) return null;
        double t = rs[0];
        return new float[]{(float) (curU + t * dirU), (float) (curV + t * dirV)};
    }

    /**
     * Intersect the ray {@code p + t*d} with the segment {@code (a, b)} in
     * 2D. Returns {@code [t_along_ray, t_along_segment]} if the ray hits
     * the segment with t_along_ray > 0 and t_along_segment in [0, 1];
     * null otherwise.
     *
     * <p>Linear system: solve {@code D*t - S*s = A - P} where {@code S = B - A}.
     * <pre>
     *   [Dx  -Sx] [t]   [Ax - Px]
     *   [Dy  -Sy] [s] = [Ay - Py]
     * </pre>
     * Cramer's rule with denom {@code = Dy*Sx - Dx*Sy}.
     */
    private static double[] raySegmentIntersect(double px, double py,
                                                 double dx, double dy,
                                                 double ax, double ay,
                                                 double bx, double by) {
        double sx = bx - ax;
        double sy = by - ay;
        double denom = dy * sx - dx * sy;     // = Dy*Sx - Dx*Sy
        if (Math.abs(denom) < EPS) return null;   // ray parallel to segment
        double tRay = ((ay - py) * sx - (ax - px) * sy) / denom;
        double tSeg = (dx * (ay - py) - dy * (ax - px)) / denom;
        if (tRay <= EPS) return null;
        if (tSeg < -EPS || tSeg > 1.0 + EPS) return null;
        return new double[]{tRay, tSeg};
    }
}
