package ixdar.geometry.mesh.quadlayout.vectorfield.alignment;

import java.util.ArrayDeque;
import java.util.Deque;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.ArrayMesh;

/**
 * Connected-component grouping of smooth faces, plus per-region significance
 * angle ∠F filtering.
 *
 * <h2>Citations</h2>
 * <ul>
 *   <li><b>CIE*16 §3.1</b> — defines a smooth region as a maximal connected
 *       component where κ^g(x) &lt; |κ_max(x)| (eq. 2).</li>
 *   <li><b>CIE*16 §3.2 ¶4</b> "Significance" + <b>eq. (3)</b> —
 *       {@code ∠F = max over a_max-streamlines of ∫|κ_max| ds}, equivalent
 *       to {@code max_streamlines max_substring |Σ_i β_i|} where
 *       {@code β_i = ±arccos(n_i·n_{i+1})} per face traversed.</li>
 *   <li><b>CIE*16 §3.2 page 6 col 2</b> "Topology" — cyclic regions (turning
 *       number ≠ 0) get {@code ∠F = 360°} treatment.</li>
 *   <li><b>CIE*16 §3.2 ¶4</b> — default threshold {@code 70°}: "we used a
 *       setting of 70°".</li>
 * </ul>
 *
 * <p>Master citation index: {@code alignment/PAPERS.md}.
 */
public final class SmoothRegions {
    public static final double NUM_360_0 = 360.0;
    public static final int NUM_2000 = 2000;
    public static final int NUM_3 = 3;
    public static final float NUM_1 = 1f;
    public static final float NUM_3_2 = 3f;
    public static final float NUM_1e_20 = 1e-20f;
    public static final double NUM_1e_9 = 1e-9;
    public static final float NUM_0_9 = 0.9f;
    public static final float NUM_0 = 0f;
    public static final double NUM_1e_20_2 = 1e-20;
    public static final double NUM_1e_6 = 1e-6;

    /** CIE*16 §3.2 ¶4 default. */
    public static final double DEFAULT_SIGNIFICANCE_DEG = 70.0;

    private SmoothRegions() {}

    /**
     * Detect smooth regions and return per-face region id (-1 = not in a kept
     * region).
     *
     * @param significanceDegMin minimum ∠F (degrees) to keep a region. CIE*16 default 70°.
     * @param mesh TODO: describe
     * @param smoothFaces TODO: describe
     * @param pdf TODO: describe
     * @return TODO: describe
     */
    public static int[] detect(ArrayMesh mesh, boolean[] smoothFaces,
                                PrincipalCurvatureField pdf,
                                double significanceDegMin) {
        int F = mesh.faceCount();
        int[] regionId = new int[F];
        for (int f = 0; f < F; f++) regionId[f] = -1;
        if (F == 0) return regionId;

        // CIE*16 §3.1 ¶3: connected components of smooth faces, walking the
        //   dual graph.
        int[] tentativeRegion = new int[F];
        for (int f = 0; f < F; f++) tentativeRegion[f] = -1;
        int regionCount = 0;
        Deque<Integer> queue = new ArrayDeque<>();
        for (int seed = 0; seed < F; seed++) {
            if (!smoothFaces[seed] || tentativeRegion[seed] != -1) continue;
            int rid = regionCount++;
            tentativeRegion[seed] = rid;
            queue.add(seed);
            while (!queue.isEmpty()) {
                int f = queue.poll();
                int n = mesh.faceHalfEdgeCount(f);
                for (int c = 0; c < n; c++) {
                    int he = mesh.faceHalfEdgeAt(f, c);
                    int twin = mesh.halfEdgeTwin(he);
                    int nbr = mesh.halfEdgeFace(twin);
                    if (nbr < 0) continue;
                    if (!smoothFaces[nbr] || tentativeRegion[nbr] != -1) continue;
                    tentativeRegion[nbr] = rid;
                    queue.add(nbr);
                }
            }
        }

        // CIE*16 §3.2 ¶4: per-region significance via a_max-streamline tracing.
        //   For each face on the boundary of the region (i.e. has at least one
        //   non-smooth or boundary neighbour), trace an a_max-streamline through
        //   the region until exit. Compute the inner-max of |Σ β_i| along the
        //   streamline, where β_i is the signed normal-change between
        //   consecutive faces. Region significance = max over streamlines.
        //
        //   Cyclic regions (no boundary): assign ∠F = 360° — CIE*16 page 6
        //   col 2 ("conservative manner and assign ∠F = 360°"). Phase B
        //   coarsely treats "no boundary face found" = cyclic.
        boolean[] kept = new boolean[regionCount];
        boolean[] hasBoundary = new boolean[regionCount];
        double[] regionAngleDeg = new double[regionCount];

        for (int f = 0; f < F; f++) {
            int rid = tentativeRegion[f];
            if (rid < 0) continue;
            if (!isRegionBoundary(mesh, f, tentativeRegion, rid)) continue;
            hasBoundary[rid] = true;
            // Trace from this boundary face into the region, along a_max.
            double angle = traceStreamlineSignificanceDeg(mesh, pdf, tentativeRegion, rid, f);
            if (angle > regionAngleDeg[rid]) regionAngleDeg[rid] = angle;
        }
        for (int rid = 0; rid < regionCount; rid++) {
            if (!hasBoundary[rid]) {
                // CIE*16 §3.2 page 6 col 2: cyclic region — ∠F = 360°.
                regionAngleDeg[rid] = NUM_360_0;
            }
            kept[rid] = regionAngleDeg[rid] >= significanceDegMin;
        }

        // Compact kept-region ids and emit per-face index.
        int[] remap = new int[regionCount];
        int next = 0;
        for (int rid = 0; rid < regionCount; rid++) {
            remap[rid] = kept[rid] ? next++ : -1;
        }
        for (int f = 0; f < F; f++) {
            int rid = tentativeRegion[f];
            regionId[f] = (rid < 0) ? -1 : remap[rid];
        }
        return regionId;
    }

    /**
     * True if face {@code f} is on the boundary of region {@code rid}
     * (i.e. has at least one neighbour that is not in {@code rid}, including
     * mesh-boundary edges).
     *
     * @param mesh TODO: describe
     * @param f TODO: describe
     * @param tentativeRegion TODO: describe
     * @param rid TODO: describe
     * @return TODO: describe
     */
    private static boolean isRegionBoundary(ArrayMesh mesh, int f,
                                            int[] tentativeRegion, int rid) {
        int n = mesh.faceHalfEdgeCount(f);
        for (int c = 0; c < n; c++) {
            int he = mesh.faceHalfEdgeAt(f, c);
            int twin = mesh.halfEdgeTwin(he);
            int nbr = mesh.halfEdgeFace(twin);
            if (nbr < 0) return true;                          // mesh boundary
            if (tentativeRegion[nbr] != rid) return true;      // region boundary
        }
        return false;
    }

    /**
     * Trace an a_max-streamline starting at the centroid of {@code startFace},
     * advancing face-by-face through region {@code rid} via parametric
     * edge-crossing geometry, until exit at a boundary. Return the inner-max
     * of {@code |Σ β_i|} along the streamline (in degrees).
     *
     * <p>CIE*16 §3.2 ¶4 + page 6 col 1:
     * <pre>
     *   ∡(γ_max) = max_{j} Σ_{i=0}^{j} β_i − min_{j} Σ_{i=0}^{j} β_i
     * </pre>
     *
     * <p>Tracing algorithm (proper edge-crossing, replaces the previous
     * dual-graph-walk heuristic):
     * <ol>
     *   <li>Project the streamline ray {@code P + t·D} into the current
     *       face's tangent plane.</li>
     *   <li>For each of the face's two non-entry edges, parametrically
     *       intersect the ray with the segment; pick the smallest valid
     *       {@code t > 0} with intersection parameter {@code s ∈ [0, 1]}.</li>
     *   <li>Move to the neighbour face across the chosen exit edge; the new
     *       entry point is the intersection point.</li>
     *   <li>Levi-Civita-transport the direction {@code D} into the new
     *       face's tangent plane (rotation about {@code n_old × n_new}).</li>
     *   <li>Sign-disambiguate the line field by alignment with the
     *       transported direction.</li>
     * </ol>
     *
     * <p>This matches what CIE*16 §3.2 ¶4 implies (and what Tricoche 2002
     * canonically does for tensor-field streamline integration on a
     * parameterized surface — but applied directly on the 3D mesh here).
     *
     * @param mesh TODO: describe
     * @param pdf TODO: describe
     * @param tentativeRegion TODO: describe
     * @param rid TODO: describe
     * @param startFace TODO: describe
     * @return TODO: describe
     */
    private static double traceStreamlineSignificanceDeg(ArrayMesh mesh,
                                                          PrincipalCurvatureField pdf,
                                                          int[] tentativeRegion,
                                                          int rid, int startFace) {
        int MAX_STEPS = NUM_2000;
        double sumBeta = 0.0;
        double maxSum = 0.0;
        double minSum = 0.0;
        java.util.HashSet<Integer> visited = new java.util.HashSet<>();
        visited.add(startFace);

        Vector3f curN = new Vector3f();
        Vector3f curA = new Vector3f();
        Vector3f nextN = new Vector3f();
        Vector3f nextA = new Vector3f();
        Vector3f axisScratch = new Vector3f();
        Vector3f tmpScratch = new Vector3f();
        Vector3f curATransp = new Vector3f();
        Vector3f vA = new Vector3f();
        Vector3f vB = new Vector3f();

        int curFace = startFace;
        pdf.normal(curFace, curN);
        pdf.aMax(curFace, curA);

        // Initial point: face centroid (CIE*16 §3.2 ¶4 starts streamlines at
        // boundary vertices; centroid of a boundary face is a sensible
        // discrete proxy that avoids vertex-incidence ambiguity).
        Vector3f curP = new Vector3f();
        Vector3f tmp = new Vector3f();
        for (int c = 0; c < NUM_3; c++) {
            mesh.vertexPosition(mesh.faceVertexAt(curFace, c), tmp);
            curP.add(tmp);
        }
        curP.mul(NUM_1 / NUM_3_2);

        // The "previous edge" we entered through — start with -1 (no entry edge).
        int prevEdge = -1;

        for (int step = 0; step < MAX_STEPS; step++) {
            // Project curA onto curFace's tangent plane (it was already there
            // by construction, but numerical drift accumulates).
            float dotN = curA.x * curN.x + curA.y * curN.y + curA.z * curN.z;
            curA.x -= dotN * curN.x;
            curA.y -= dotN * curN.y;
            curA.z -= dotN * curN.z;
            float dirLen = (float) Math.sqrt(curA.x * curA.x + curA.y * curA.y + curA.z * curA.z);
            if (dirLen < NUM_1e_20) break;
            curA.mul(NUM_1 / dirLen);

            // Find exit edge: parametrically intersect ray (curP + t·curA)
            // with each of the face's edges (excluding prevEdge).
            int n = mesh.faceHalfEdgeCount(curFace);
            int bestEdge = -1;
            int bestNbr = -1;
            double bestT = Double.POSITIVE_INFINITY;
            float bestExitX = 0, bestExitY = 0, bestExitZ = 0;
            for (int c = 0; c < n; c++) {
                int he = mesh.faceHalfEdgeAt(curFace, c);
                int eId = mesh.halfEdgeEdge(he);
                if (eId == prevEdge) continue;

                int v0 = mesh.halfEdgeVertex(he);
                int v1 = mesh.halfEdgeEndVertex(he);
                mesh.vertexPosition(v0, vA);
                mesh.vertexPosition(v1, vB);
                // Solve: curP + t·curA = vA + s·(vB-vA), with t > 0, s ∈ [0, 1].
                // In the face plane, this is a 2D linear system. We solve in 3D
                // by projecting onto the face's plane (curN normal).
                double ts = solveRayEdgeIntersection(curP, curA, vA, vB, curN);
                if (ts == Double.POSITIVE_INFINITY) continue;
                double t = ts;
                if (t <= NUM_1e_9 || t >= bestT) continue;
                int twin = mesh.halfEdgeTwin(he);
                int nbr = mesh.halfEdgeFace(twin);
                bestT = t;
                bestEdge = eId;
                bestNbr = nbr;
                bestExitX = (float) (curP.x + t * curA.x);
                bestExitY = (float) (curP.y + t * curA.y);
                bestExitZ = (float) (curP.z + t * curA.z);
            }
            if (bestEdge < 0) break;          // ray didn't hit any edge — degenerate
            if (bestNbr < 0) break;           // mesh boundary edge — region exit
            if (tentativeRegion[bestNbr] != rid) break;   // region boundary — done
            // CIE*16 page 6 col 2: cyclic streamline → ∠F = 360°.
            if (visited.contains(bestNbr)) return NUM_360_0;
            visited.add(bestNbr);

            pdf.normal(bestNbr, nextN);
            pdf.aMax(bestNbr, nextA);

            // β = ±arccos(curN · nextN), sign by orientation of (curN × nextN) · curA.
            double dot = Math.max(-1.0, Math.min(1.0, (double) curN.dot(nextN)));
            double mag = Math.acos(dot);
            float crossX = curN.y * nextN.z - curN.z * nextN.y;
            float crossY = curN.z * nextN.x - curN.x * nextN.z;
            float crossZ = curN.x * nextN.y - curN.y * nextN.x;
            double signProj = crossX * curA.x + crossY * curA.y + crossZ * curA.z;
            double beta = (signProj >= 0) ? mag : -mag;
            sumBeta += beta;
            if (sumBeta > maxSum) maxSum = sumBeta;
            if (sumBeta < minSum) minSum = sumBeta;

            // Levi-Civita transport curA into nextFace's tangent plane.
            GeodesicCurvature.transportLineField(curA, nextN, curN, dot,
                    axisScratch, curATransp, tmpScratch);
            // Sign-resolve the line field at the new face.
            double signAlign = curATransp.dot(nextA);
            if (signAlign < 0) {
                nextA.set(-nextA.x, -nextA.y, -nextA.z);
            }

            // Step bookkeeping.
            curFace = bestNbr;
            curP.set(bestExitX, bestExitY, bestExitZ);
            prevEdge = bestEdge;
            curN.set(nextN);
            curA.set(nextA);
        }

        // CIE*16 §3.2 ¶4 inner-max equivalent = (max Σ β) − (min Σ β).
        return Math.toDegrees(maxSum - minSum);
    }

    /**
     * Solve {@code P + t·D = A + s·(B−A)} for {@code (t, s)} with the points
     * lying in the plane normal to {@code n}. Return {@code t} if a valid
     * intersection exists with {@code t > 0} and {@code s ∈ [0, 1]};
     * otherwise {@code Double.POSITIVE_INFINITY}.
     *
     * <p>2D solution in the plane: choose two basis vectors orthogonal to
     * {@code n}, project everything onto them, solve a 2x2 linear system.
     *
     * @param P TODO: describe
     * @param D TODO: describe
     * @param A TODO: describe
     * @param B TODO: describe
     * @param n TODO: describe
     * @return TODO: describe
     */
    private static double solveRayEdgeIntersection(Vector3f P, Vector3f D,
                                                    Vector3f A, Vector3f B, Vector3f n) {
        // Build 2D basis in the plane.
        // u = an arbitrary unit vector ⊥ n; v = n × u.
        float ux, uy, uz;
        if (Math.abs(n.x) < NUM_0_9) { ux = NUM_1; uy = NUM_0; uz = NUM_0; }
        else                       { ux = NUM_0; uy = NUM_1; uz = NUM_0; }
        float un = ux * n.x + uy * n.y + uz * n.z;
        ux -= un * n.x; uy -= un * n.y; uz -= un * n.z;
        float ulen = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (ulen < NUM_1e_20) return Double.POSITIVE_INFINITY;
        ux /= ulen; uy /= ulen; uz /= ulen;
        float vx = n.y * uz - n.z * uy;
        float vy = n.z * ux - n.x * uz;
        float vz = n.x * uy - n.y * ux;

        // Project D, edge dir (B-A), and (A - P) into the (u, v) basis.
        double dU = D.x * ux + D.y * uy + D.z * uz;
        double dV = D.x * vx + D.y * vy + D.z * vz;
        double eU = (B.x - A.x) * ux + (B.y - A.y) * uy + (B.z - A.z) * uz;
        double eV = (B.x - A.x) * vx + (B.y - A.y) * vy + (B.z - A.z) * vz;
        double rU = (A.x - P.x) * ux + (A.y - P.y) * uy + (A.z - P.z) * uz;
        double rV = (A.x - P.x) * vx + (A.y - P.y) * vy + (A.z - P.z) * vz;

        // Solve  [dU, -eU]   [t]   [rU]
        //        [dV, -eV] * [s] = [rV]
        double det = dU * (-eV) - (-eU) * dV;
        if (Math.abs(det) < NUM_1e_20_2) return Double.POSITIVE_INFINITY;
        double t = (rU * (-eV) - (-eU) * rV) / det;
        double s = (dU * rV - dV * rU) / det;
        if (s < -NUM_1e_6 || s > 1.0 + NUM_1e_6) return Double.POSITIVE_INFINITY;
        if (t <= NUM_1e_9) return Double.POSITIVE_INFINITY;
        return t;
    }
}
