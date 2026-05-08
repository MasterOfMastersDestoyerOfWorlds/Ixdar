package ixdar.geometry.mesh.quadlayout.vectorfield.alignment;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.ArrayMesh;

/**
 * Per-face principal curvature directions and magnitudes.
 *
 * <h2>Citations</h2>
 * <ul>
 *   <li><b>ACDLD03 §2.1 eq.(1)</b> — robust 3D curvature tensor estimation:
 *       {@code T(v) = (1/|B|) Σ_e β(e) |e ∩ B| ē·ē^T}. Alliez et al. 2003,
 *       <i>Anisotropic Polygonal Remeshing</i>.</li>
 *   <li><b>ACDLD03 §2.1 ¶3</b> — default integration radius
 *       {@code r_geo = bbox/100}.</li>
 *   <li><b>CIE*16 §3.2 ¶1</b> "Principal Curvature" — robustness fix: estimate
 *       surface normal {@code n} separately (face-normal averaging), then
 *       {@code n = a_min × n' × a_min}, {@code a_max = n × a_min}.
 *       Campen, Ibing, Ebke, Zorin, Kobbelt 2016, <i>Scale-Invariant
 *       Directional Alignment of Surface Parametrizations</i>.</li>
 *   <li><b>Lyon 2021 §7 ¶1</b> — confirms this detector + BZK09 cross-field
 *       is what Lyon uses for ROCKERARM (Table 1, p. 311).</li>
 * </ul>
 *
 * <p>Master citation index: {@code alignment/PAPERS.md}.
 *
 * <p><b>Implementation note</b>: ACDLD03 §2.1 estimates {@code T(v)} per
 * vertex, then interpolates linearly across triangles. We instead evaluate
 * {@code T} at each face centroid directly. This eliminates the
 * vertex→triangle interpolation step (ACDLD03 §2.1 last paragraph) while
 * preserving the integration-over-geodesic-disk semantics. CIE*16 §3.2 ¶2
 * already requires per-face line fields ("we consider the line fields a_min
 * and a_max as unit vector fields, with one vector per face each"), so a
 * face-centric tensor is the natural representation.
 *
 * <p><b>Line field, not vector field</b>: Both {@code a_min} and {@code a_max}
 * are returned with arbitrary sign — they are line fields, not oriented
 * vector fields. Downstream consumers (e.g. {@link GeodesicCurvature}) must
 * resolve sign ambiguity locally via {@code min‖± ‖}.
 */
public final class PrincipalCurvatureField {
    public static final int NUM_3 = 3;
    public static final float NUM_1e_30 = 1e-30f;
    public static final float NUM_1 = 1f;
    public static final float NUM_3_2 = 3f;
    public static final float NUM_0_5 = 0.5f;
    public static final double NUM_1e_30_2 = 1e-30;
    public static final int NUM_6 = 6;
    public static final int NUM_4 = 4;
    public static final int NUM_5 = 5;
    public static final float NUM_0 = 0f;
    public static final double NUM_1e_60 = 1e-60;
    public static final double NUM_2_0 = 2.0;
    public static final double NUM_4_0 = 4.0;
    public static final int NUM_50 = 50;
    public static final double NUM_1e_18 = 1e-18;
    public static final double NUM_1e15 = 1e15;
    public static final double NUM_0_5_2 = 0.5;

    /**
     * ACDLD03 §2.3 default — number of Gaussian-on-dual-graph smoothing.
     */
    public static final int DEFAULT_SMOOTH_ITERS = 8;

    /**
     * ACDLD03 §2.5 default feature-edge dihedral threshold (degrees).
     *  Edges with |β| above this are treated as feature lines: tensor
     *  integration BFS does not cross them, and tensor smoothing does not
     *  average across them. 30° is a common default for CAD-style meshes;
     */
    public static final double DEFAULT_FEATURE_DIHEDRAL_DEG = 30.0;

    private final int F;
    /** Per-face min principal direction (unit, line field). Layout f*3+(0..2). */
    private final float[] aMin;
    /** Per-face max principal direction (unit, line field). Layout f*3+(0..2). */
    private final float[] aMax;
    /** Per-face robust surface normal (unit). Layout f*3+(0..2). */
    private final float[] normal;
    /** Per-face minimum principal curvature magnitude (signed). */
    private final double[] kappaMin;
    /** Per-face maximum principal curvature magnitude (signed, |κ_max| ≥ |κ_min|). */
    private final double[] kappaMax;

    private PrincipalCurvatureField(int F) {
        this.F = F;
        this.aMin = new float[F * NUM_3];
        this.aMax = new float[F * NUM_3];
        this.normal = new float[F * NUM_3];
        this.kappaMin = new double[F];
        this.kappaMax = new double[F];
    }

    /**
     * TODO: document {@code faceCount}.
     *
     * @return TODO: describe
     */
    public int faceCount() { return F; }

    /**
     * TODO: document {@code aMin}.
     *
     * @param faceId TODO: describe
     * @param dest TODO: describe
     * @return TODO: describe
     */
    public Vector3f aMin(int faceId, Vector3f dest) {
        int o = faceId * NUM_3;
        return dest.set(aMin[o], aMin[o + 1], aMin[o + 2]);
    }

    /**
     * TODO: document {@code aMax}.
     *
     * @param faceId TODO: describe
     * @param dest TODO: describe
     * @return TODO: describe
     */
    public Vector3f aMax(int faceId, Vector3f dest) {
        int o = faceId * NUM_3;
        return dest.set(aMax[o], aMax[o + 1], aMax[o + 2]);
    }

    /**
     * TODO: document {@code normal}.
     *
     * @param faceId TODO: describe
     * @param dest TODO: describe
     * @return TODO: describe
     */
    public Vector3f normal(int faceId, Vector3f dest) {
        int o = faceId * NUM_3;
        return dest.set(normal[o], normal[o + 1], normal[o + 2]);
    }

    /**
     * TODO: document {@code kappaMin}.
     *
     * @param faceId TODO: describe
     * @return TODO: describe
     */
    public double kappaMin(int faceId) { return kappaMin[faceId]; }
    /**
     * TODO: document {@code kappaMax}.
     *
     * @param faceId TODO: describe
     * @return TODO: describe
     */
    public double kappaMax(int faceId) { return kappaMax[faceId]; }

    /**
     * Compute the field over {@code mesh} using a geodesic-disk approximation
     * of radius {@code rGeo} (ACDLD03 §2.1 ¶3 default = bbox/100), with
     * default tensor-smoothing (ACDLD03 §2.3) and feature-edge clipping
     * (ACDLD03 §2.5).
     *
     * @param mesh TODO: describe
     * @param rGeo TODO: describe
     * @return TODO: describe
     */
    public static PrincipalCurvatureField compute(ArrayMesh mesh, double rGeo) {
        return compute(mesh, rGeo, DEFAULT_SMOOTH_ITERS, DEFAULT_FEATURE_DIHEDRAL_DEG);
    }

    /**
     * Compute the field with explicit smoothing iteration count and feature
     * dihedral threshold (degrees).
     *
     * <p>ACDLD03 §2.3 Tensor Field Smoothing: the raw per-face tensors are
     * noisy on CAD meshes (where edge dihedrals are concentrated and the
     * geodesic disk only catches a few). We smooth the 6 unique tensor
     * coefficients (xx, yy, zz, xy, xz, yz) by repeated averaging over the
     * dual-face neighbourhood — discrete Gaussian on the dual graph. After
     * smoothing, principal directions are extracted via eigendecomposition.
     *
     * <p>ACDLD03 §2.5 Taking Care of Features: for CAD meshes, sharp dihedral
     * edges (|β| > {@code featureDihedralDeg}) act as feature lines. Tensor
     * integration BFS does not cross feature edges (one-sided evaluation per
     * §2.5 ¶1) and tensor smoothing does not average across them (per §2.5
     * ¶2). Without this, ACDLD03's tensor on a flat-region face near a sharp
     * edge becomes structurally rank-1 (dominated by the sharp edge's
     * dihedral), producing spurious principal directions perpendicular to
     * the edge throughout the supposedly-flat region.
     *
     * @param mesh TODO: describe
     * @param rGeo TODO: describe
     * @param smoothIters TODO: describe
     * @param featureDihedralDeg TODO: describe
     * @return TODO: describe
     */
    public static PrincipalCurvatureField compute(ArrayMesh mesh, double rGeo,
                                                   int smoothIters, double featureDihedralDeg) {
        int F = mesh.faceCount();
        PrincipalCurvatureField pdf = new PrincipalCurvatureField(F);
        if (F == 0) return pdf;
        double featureDihedralRad = Math.toRadians(featureDihedralDeg);

        // Pre-compute face normals (unit) and centroids.
        float[] faceNormal = new float[F * NUM_3];
        float[] faceCentroid = new float[F * NUM_3];
        float[] faceArea = new float[F];
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        Vector3f e1 = new Vector3f();
        Vector3f e2 = new Vector3f();
        Vector3f n = new Vector3f();
        for (int f = 0; f < F; f++) {
            mesh.vertexPosition(mesh.faceVertexAt(f, 0), p0);
            mesh.vertexPosition(mesh.faceVertexAt(f, 1), p1);
            mesh.vertexPosition(mesh.faceVertexAt(f, 2), p2);
            e1.set(p1).sub(p0);
            e2.set(p2).sub(p0);
            e1.cross(e2, n);
            float twoA = n.length();
            if (twoA > NUM_1e_30) n.mul(NUM_1 / twoA);
            int o = f * NUM_3;
            faceNormal[o] = n.x; faceNormal[o + 1] = n.y; faceNormal[o + 2] = n.z;
            faceCentroid[o] = (p0.x + p1.x + p2.x) / NUM_3_2;
            faceCentroid[o + 1] = (p0.y + p1.y + p2.y) / NUM_3_2;
            faceCentroid[o + 2] = (p0.z + p1.z + p2.z) / NUM_3_2;
            faceArea[f] = NUM_0_5 * twoA;
        }

        // ACDLD03 §2.1 eq.(1): T(c) = (1/|B|) Σ_e β(e) |e ∩ B| ē·ē^T
        //   c = face centroid; B = sphere of radius rGeo around c.
        // β(e) = signed dihedral angle across edge e (positive convex, negative concave).
        // |e ∩ B| = clipped edge length within B.
        // ē = unit edge direction.
        //
        // We use a BFS on the face dual graph from each face f, expanding
        // outwards while the mesh edge's nearest endpoint is within rGeo of c.
        // Each interior mesh edge contributes one term; boundary edges have
        // β = 0 by convention (no dihedral angle).
        int E = mesh.edgeCount();
        int totalFaces = F;
        // Pre-compute, per interior edge: signed dihedral angle, edge endpoints, edge unit dir.
        boolean[] edgeIsInterior = new boolean[E];
        double[] edgeDihedral = new double[E];
        float[] edgeP0 = new float[E * NUM_3];
        float[] edgeP1 = new float[E * NUM_3];
        float[] edgeUnit = new float[E * NUM_3];
        double[] edgeLength = new double[E];
        Vector3f tmpA = new Vector3f();
        Vector3f tmpB = new Vector3f();
        Vector3f tmpEdge = new Vector3f();
        Vector3f nA = new Vector3f();
        Vector3f nB = new Vector3f();
        for (int eId = 0; eId < E; eId++) {
            if (mesh.isBoundaryEdge(eId)) continue;
            int he = mesh.edgeHalfEdge(eId);
            int twin = mesh.halfEdgeTwin(he);
            int fA = mesh.halfEdgeFace(he);
            int fB = mesh.halfEdgeFace(twin);
            if (fA < 0 || fB < 0) continue;
            edgeIsInterior[eId] = true;

            int v0 = mesh.halfEdgeVertex(he);
            int v1 = mesh.halfEdgeEndVertex(he);
            mesh.vertexPosition(v0, tmpA);
            mesh.vertexPosition(v1, tmpB);
            int eo = eId * NUM_3;
            edgeP0[eo] = tmpA.x; edgeP0[eo + 1] = tmpA.y; edgeP0[eo + 2] = tmpA.z;
            edgeP1[eo] = tmpB.x; edgeP1[eo + 1] = tmpB.y; edgeP1[eo + 2] = tmpB.z;
            tmpEdge.set(tmpB).sub(tmpA);
            double el = tmpEdge.length();
            edgeLength[eId] = el;
            if (el > NUM_1e_30_2) tmpEdge.mul((float) (1.0 / el));
            edgeUnit[eo] = tmpEdge.x; edgeUnit[eo + 1] = tmpEdge.y; edgeUnit[eo + 2] = tmpEdge.z;

            // ACDLD03 §2.1: β(e) = signed angle between face normals.
            //   Sign convention: positive if convex (outward-pointing dihedral),
            //   negative if concave. We sign by sign of (nA × nB) · ē.
            int oA = fA * NUM_3, oB = fB * NUM_3;
            nA.set(faceNormal[oA], faceNormal[oA + 1], faceNormal[oA + 2]);
            nB.set(faceNormal[oB], faceNormal[oB + 1], faceNormal[oB + 2]);
            double dot = Math.max(-1.0, Math.min(1.0, nA.dot(nB)));
            double angle = Math.acos(dot);
            // Signed: cross product axis aligned with edge → convex.
            float cx = nA.y * nB.z - nA.z * nB.y;
            float cy = nA.z * nB.x - nA.x * nB.z;
            float cz = nA.x * nB.y - nA.y * nB.x;
            double signProj = cx * tmpEdge.x + cy * tmpEdge.y + cz * tmpEdge.z;
            edgeDihedral[eId] = (signProj >= 0) ? angle : -angle;
        }

        // ACDLD03 §2.5 ¶1: identify feature edges (|β| > threshold).
        boolean[] isFeatureEdge = new boolean[E];
        int featureEdgeCount = 0;
        for (int eId = 0; eId < E; eId++) {
            if (!edgeIsInterior[eId]) continue;
            if (Math.abs(edgeDihedral[eId]) > featureDihedralRad) {
                isFeatureEdge[eId] = true;
                featureEdgeCount++;
            }
        }

        // Build face-edge adjacency (for the dual BFS) and per-face mesh-edge list.
        int[][] faceEdges = new int[F][];
        {
            int[] degree = new int[F];
            for (int eId = 0; eId < E; eId++) {
                if (!edgeIsInterior[eId]) continue;
                int he = mesh.edgeHalfEdge(eId);
                int twin = mesh.halfEdgeTwin(he);
                int fA = mesh.halfEdgeFace(he);
                int fB = mesh.halfEdgeFace(twin);
                if (fA >= 0) degree[fA]++;
                if (fB >= 0) degree[fB]++;
            }
            for (int f = 0; f < F; f++) faceEdges[f] = new int[degree[f]];
            int[] cursor = new int[F];
            for (int eId = 0; eId < E; eId++) {
                if (!edgeIsInterior[eId]) continue;
                int he = mesh.edgeHalfEdge(eId);
                int twin = mesh.halfEdgeTwin(he);
                int fA = mesh.halfEdgeFace(he);
                int fB = mesh.halfEdgeFace(twin);
                if (fA >= 0) faceEdges[fA][cursor[fA]++] = eId;
                if (fB >= 0) faceEdges[fB][cursor[fB]++] = eId;
            }
        }

        // BFS faces by dual graph; collect all interior edges within rGeo of
        // each face's centroid. ACDLD03 §2.1 says the integration neighborhood
        // is a geodesic disk around the evaluation point; we approximate by a
        // sphere of fixed radius (BZK09 §3 also approximates this way).
        double rSq = rGeo * rGeo;
        boolean[] visited = new boolean[F];
        int[] bfsQueue = new int[F];
        int[] edgeMark = new int[E];
        int marker = 0;
        // Tensor accumulator (3x3 symmetric — 6 unique entries: xx, yy, zz, xy, xz, yz)
        double[] T = new double[NUM_6];
        Vector3f cF = new Vector3f();
        Vector3f eP0 = new Vector3f();
        Vector3f eP1 = new Vector3f();
        Vector3f eDir = new Vector3f();

        // Build face dual adjacency once. Parallel arrays:
        //   faceDualNbr[f]      = list of dual-neighbour face ids
        //   faceDualNbrEdge[f]  = list of corresponding mesh edge ids
        // We keep the edge id so that ACDLD03 §2.5 feature-edge clipping can
        // be checked at BFS / smoothing time without rebuilding adjacency.
        int[][] faceDualNbr = new int[F][];
        int[][] faceDualNbrEdge = new int[F][];
        {
            int[] degree = new int[F];
            for (int eId = 0; eId < E; eId++) {
                if (!edgeIsInterior[eId]) continue;
                int he = mesh.edgeHalfEdge(eId);
                int twin = mesh.halfEdgeTwin(he);
                int fA = mesh.halfEdgeFace(he);
                int fB = mesh.halfEdgeFace(twin);
                if (fA >= 0) degree[fA]++;
                if (fB >= 0) degree[fB]++;
            }
            for (int f = 0; f < F; f++) {
                faceDualNbr[f] = new int[degree[f]];
                faceDualNbrEdge[f] = new int[degree[f]];
            }
            int[] cursor = new int[F];
            for (int eId = 0; eId < E; eId++) {
                if (!edgeIsInterior[eId]) continue;
                int he = mesh.edgeHalfEdge(eId);
                int twin = mesh.halfEdgeTwin(he);
                int fA = mesh.halfEdgeFace(he);
                int fB = mesh.halfEdgeFace(twin);
                if (fA >= 0 && fB >= 0) {
                    faceDualNbr[fA][cursor[fA]] = fB;
                    faceDualNbrEdge[fA][cursor[fA]] = eId;
                    cursor[fA]++;
                    faceDualNbr[fB][cursor[fB]] = fA;
                    faceDualNbrEdge[fB][cursor[fB]] = eId;
                    cursor[fB]++;
                }
            }
        }

        Vector3f n1 = new Vector3f();
        Vector3f normalAvgScratch = new Vector3f();

        // ACDLD03 §2.1 PASS 1: compute raw per-face tensors (xx,yy,zz,xy,xz,yz)
        //   and per-face robust normals n' (area-weighted face-normal average
        //   over the geodesic disk neighbourhood). Defer eigendecomposition
        //   until after ACDLD03 §2.3 smoothing.
        double[] tensorXX = new double[F];
        double[] tensorYY = new double[F];
        double[] tensorZZ = new double[F];
        double[] tensorXY = new double[F];
        double[] tensorXZ = new double[F];
        double[] tensorYZ = new double[F];
        float[] nPrime = new float[F * NUM_3];

        for (int f = 0; f < F; f++) {
            int oc = f * NUM_3;
            cF.set(faceCentroid[oc], faceCentroid[oc + 1], faceCentroid[oc + 2]);

            for (int i = 0; i < NUM_6; i++) T[i] = 0.0;
            normalAvgScratch.set(0, 0, 0);
            double normalAreaSum = 0.0;
            marker++;

            for (int i = 0; i < totalFaces; i++) visited[i] = false;
            int qHead = 0, qTail = 0;
            bfsQueue[qTail++] = f;
            visited[f] = true;

            while (qHead < qTail) {
                int g = bfsQueue[qHead++];
                int og = g * NUM_3;
                n1.set(faceNormal[og], faceNormal[og + 1], faceNormal[og + 2]);
                normalAvgScratch.add(n1.mul(faceArea[g]));
                normalAreaSum += faceArea[g];

                int[] edges = faceEdges[g];
                for (int eIdx = 0; eIdx < edges.length; eIdx++) {
                    int eId = edges[eIdx];
                    if (edgeMark[eId] == marker) continue;
                    edgeMark[eId] = marker;
                    int eo = eId * NUM_3;
                    eP0.set(edgeP0[eo], edgeP0[eo + 1], edgeP0[eo + 2]);
                    eP1.set(edgeP1[eo], edgeP1[eo + 1], edgeP1[eo + 2]);
                    double clipped = clippedEdgeLengthInBall(eP0, eP1, cF, rGeo);
                    if (clipped <= 0) continue;
                    double beta = edgeDihedral[eId];      // ACDLD03 §2.1 — signed dihedral
                    double w = beta * clipped;            // ACDLD03 §2.1 — β(e) · |e ∩ B|
                    eDir.set(edgeUnit[eo], edgeUnit[eo + 1], edgeUnit[eo + 2]);
                    // ACDLD03 §2.1 eq.(1):  T += β(e) |e ∩ B| ē · ē^T
                    T[0] += w * eDir.x * eDir.x;
                    T[1] += w * eDir.y * eDir.y;
                    T[2] += w * eDir.z * eDir.z;
                    T[NUM_3] += w * eDir.x * eDir.y;
                    T[NUM_4] += w * eDir.x * eDir.z;
                    T[NUM_5] += w * eDir.y * eDir.z;
                }

                // ACDLD03 §2.5 ¶1: BFS for tensor integration is CLIPPED at
                //   feature edges — don't expand across them. This makes T(f)
                //   one-sided near sharp dihedrals, removing the rank-1 bias
                //   that otherwise dominates the tensor on flat-region faces
                //   adjacent to sharp edges.
                int[] nbrs = faceDualNbr[g];
                int[] nbrEdges = faceDualNbrEdge[g];
                for (int k = 0; k < nbrs.length; k++) {
                    int h = nbrs[k];
                    if (visited[h]) continue;
                    if (isFeatureEdge[nbrEdges[k]]) continue;
                    if (faceTouchesBall(mesh, h, cF, rSq)) {
                        visited[h] = true;
                        bfsQueue[qTail++] = h;
                    }
                }
            }

            double area = (normalAreaSum > NUM_1e_30_2) ? normalAreaSum : 1.0;
            tensorXX[f] = T[0] / area; tensorYY[f] = T[1] / area; tensorZZ[f] = T[2] / area;
            tensorXY[f] = T[NUM_3] / area; tensorXZ[f] = T[NUM_4] / area; tensorYZ[f] = T[NUM_5] / area;

            // CIE*16 §3.2 ¶1: per-face n' = area-weighted face-normal average.
            double nl = normalAvgScratch.length();
            if (nl > NUM_1e_30_2) normalAvgScratch.mul((float) (1.0 / nl));
            else normalAvgScratch.set(faceNormal[oc], faceNormal[oc + 1], faceNormal[oc + 2]);
            nPrime[oc] = normalAvgScratch.x;
            nPrime[oc + 1] = normalAvgScratch.y;
            nPrime[oc + 2] = normalAvgScratch.z;
        }

        // ACDLD03 §2.3 Tensor Field Smoothing — Gaussian-on-dual-graph
        //   replacement for ACDLD03's 2D parameter-space Gaussian. For each
        //   smoothing pass: T_new[f] = (T[f] + Σ_nbrs T[nbr]) / (1 + |nbrs|).
        //   This dampens noise from sparse edge sampling in CAD meshes
        //   (rocker-arm needs ~8 passes to stabilize principal directions).
        if (smoothIters > 0) {
            double[] xxBuf = new double[F];
            double[] yyBuf = new double[F];
            double[] zzBuf = new double[F];
            double[] xyBuf = new double[F];
            double[] xzBuf = new double[F];
            double[] yzBuf = new double[F];
            // ACDLD03 §2.5 ¶2: smoothing is CLIPPED at feature edges, so
            //   each tensor smooths only with neighbours on the same side
            //   of any sharp dihedral.
            for (int it = 0; it < smoothIters; it++) {
                for (int f = 0; f < F; f++) {
                    double sxx = tensorXX[f], syy = tensorYY[f], szz = tensorZZ[f];
                    double sxy = tensorXY[f], sxz = tensorXZ[f], syz = tensorYZ[f];
                    int[] nbrs = faceDualNbr[f];
                    int[] nbrEdges = faceDualNbrEdge[f];
                    int nUsed = 1;     // self
                    for (int k = 0; k < nbrs.length; k++) {
                        if (isFeatureEdge[nbrEdges[k]]) continue;
                        int h = nbrs[k];
                        sxx += tensorXX[h]; syy += tensorYY[h]; szz += tensorZZ[h];
                        sxy += tensorXY[h]; sxz += tensorXZ[h]; syz += tensorYZ[h];
                        nUsed++;
                    }
                    double inv = 1.0 / nUsed;
                    xxBuf[f] = sxx * inv; yyBuf[f] = syy * inv; zzBuf[f] = szz * inv;
                    xyBuf[f] = sxy * inv; xzBuf[f] = sxz * inv; yzBuf[f] = syz * inv;
                }
                System.arraycopy(xxBuf, 0, tensorXX, 0, F);
                System.arraycopy(yyBuf, 0, tensorYY, 0, F);
                System.arraycopy(zzBuf, 0, tensorZZ, 0, F);
                System.arraycopy(xyBuf, 0, tensorXY, 0, F);
                System.arraycopy(xzBuf, 0, tensorXZ, 0, F);
                System.arraycopy(yzBuf, 0, tensorYZ, 0, F);
            }
        }

        // PASS 2: eigendecompose smoothed tensor; CIE*16 §3.2 ¶1 robustness.
        double[][] T3 = new double[NUM_3][NUM_3];
        double[][] V3 = new double[NUM_3][NUM_3];
        double[] eigvals = new double[NUM_3];
        Vector3f normalAvg = new Vector3f();
        for (int f = 0; f < F; f++) {
            int oc = f * NUM_3;
            normalAvg.set(nPrime[oc], nPrime[oc + 1], nPrime[oc + 2]);

            T3[0][0] = tensorXX[f]; T3[1][1] = tensorYY[f]; T3[2][2] = tensorZZ[f];
            T3[0][1] = T3[1][0] = tensorXY[f];
            T3[0][2] = T3[2][0] = tensorXZ[f];
            T3[1][2] = T3[2][1] = tensorYZ[f];
            jacobiEigen3(T3, eigvals, V3);

            // ACDLD03 §2.1 + CIE*16 §3.2 ¶1: pick eigenvector most aligned
            //   with n' as the normal direction; the other two are tangent
            //   plane directions; SWAPPED assignment per ACDLD03 (small
            //   |eigenvalue| → max-curvature direction).
            int idxNormal = 0;
            double minDotN = Math.abs(eigVecDotN(V3, 0, normalAvg));
            for (int k = 1; k < NUM_3; k++) {
                double d = Math.abs(eigVecDotN(V3, k, normalAvg));
                if (d > minDotN) { minDotN = d; idxNormal = k; }
            }
            int idxA = (idxNormal + 1) % NUM_3;
            int idxB = (idxNormal + 2) % NUM_3;
            double absA = Math.abs(eigvals[idxA]);
            double absB = Math.abs(eigvals[idxB]);
            int idxAMin, idxAMax;
            if (absA >= absB) { idxAMin = idxA; idxAMax = idxB; }
            else              { idxAMin = idxB; idxAMax = idxA; }

            float aMx = (float) V3[0][idxAMin];
            float aMy = (float) V3[1][idxAMin];
            float aMz = (float) V3[2][idxAMin];
            float dotN = aMx * normalAvg.x + aMy * normalAvg.y + aMz * normalAvg.z;
            aMx -= dotN * normalAvg.x;
            aMy -= dotN * normalAvg.y;
            aMz -= dotN * normalAvg.z;
            float aMlen = (float) Math.sqrt(aMx * aMx + aMy * aMy + aMz * aMz);
            if (aMlen > NUM_1e_30) { aMx /= aMlen; aMy /= aMlen; aMz /= aMlen; }
            else { aMx = NUM_1; aMy = NUM_0; aMz = NUM_0; }

            // CIE*16 §3.2 ¶1: n = (a_min × n') × a_min.
            float crossX = aMy * normalAvg.z - aMz * normalAvg.y;
            float crossY = aMz * normalAvg.x - aMx * normalAvg.z;
            float crossZ = aMx * normalAvg.y - aMy * normalAvg.x;
            float nFx = crossY * aMz - crossZ * aMy;
            float nFy = crossZ * aMx - crossX * aMz;
            float nFz = crossX * aMy - crossY * aMx;
            float nFlen = (float) Math.sqrt(nFx * nFx + nFy * nFy + nFz * nFz);
            if (nFlen > NUM_1e_30) { nFx /= nFlen; nFy /= nFlen; nFz /= nFlen; }
            else { nFx = normalAvg.x; nFy = normalAvg.y; nFz = normalAvg.z; }

            // CIE*16 §3.2 ¶1: a_max = n × a_min.
            float aMxx = nFy * aMz - nFz * aMy;
            float aMxy = nFz * aMx - nFx * aMz;
            float aMxz = nFx * aMy - nFy * aMx;
            float aMxlen = (float) Math.sqrt(aMxx * aMxx + aMxy * aMxy + aMxz * aMxz);
            if (aMxlen > NUM_1e_30) { aMxx /= aMxlen; aMxy /= aMxlen; aMxz /= aMxlen; }

            int oF3 = f * NUM_3;
            pdf.aMin[oF3] = aMx;     pdf.aMin[oF3 + 1] = aMy;     pdf.aMin[oF3 + 2] = aMz;
            pdf.aMax[oF3] = aMxx;    pdf.aMax[oF3 + 1] = aMxy;    pdf.aMax[oF3 + 2] = aMxz;
            pdf.normal[oF3] = nFx;   pdf.normal[oF3 + 1] = nFy;   pdf.normal[oF3 + 2] = nFz;
            pdf.kappaMin[f] = eigvals[idxAMax];
            pdf.kappaMax[f] = eigvals[idxAMin];
        }
        return pdf;
    }

    /**
     * True if face {@code f}'s closest vertex to {@code center} is within √rSq.
     *
     * @param mesh TODO: describe
     * @param f TODO: describe
     * @param center TODO: describe
     * @param rSq TODO: describe
     * @return TODO: describe
     */
    private static boolean faceTouchesBall(ArrayMesh mesh, int f, Vector3f center, double rSq) {
        Vector3f p = new Vector3f();
        for (int c = 0; c < NUM_3; c++) {
            mesh.vertexPosition(mesh.faceVertexAt(f, c), p);
            double dx = p.x - center.x, dy = p.y - center.y, dz = p.z - center.z;
            if (dx * dx + dy * dy + dz * dz <= rSq) return true;
        }
        return false;
    }

    /**
     * ACDLD03 §2.1: |e ∩ B(c, r)| — length of edge segment p0→p1 intersected
     * with a ball of radius {@code r} around {@code c}. Solves a quadratic in
     * the line parameter t ∈ [0, 1]; returns 0 if the segment misses the ball
     * entirely.
     *
     * @param p0 TODO: describe
     * @param p1 TODO: describe
     * @param c TODO: describe
     * @param r TODO: describe
     * @return TODO: describe
     */
    private static double clippedEdgeLengthInBall(Vector3f p0, Vector3f p1,
                                                  Vector3f c, double r) {
        double dx = p1.x - p0.x, dy = p1.y - p0.y, dz = p1.z - p0.z;
        double L2 = dx * dx + dy * dy + dz * dz;
        if (L2 < NUM_1e_60) return 0.0;
        double L = Math.sqrt(L2);
        // Parametric: point(t) = p0 + t·(p1 − p0).  We want |point − c|² ≤ r².
        double fx = p0.x - c.x, fy = p0.y - c.y, fz = p0.z - c.z;
        // a·t² + b·t + cc = 0 with a = L², b = 2 (f · d), cc = |f|² − r².
        double a = L2;
        double b = NUM_2_0 * (fx * dx + fy * dy + fz * dz);
        double cc = fx * fx + fy * fy + fz * fz - r * r;
        double disc = b * b - NUM_4_0 * a * cc;
        if (disc < 0.0) return 0.0;
        double s = Math.sqrt(disc);
        double t0 = (-b - s) / (NUM_2_0 * a);
        double t1 = (-b + s) / (NUM_2_0 * a);
        if (t1 < 0.0 || t0 > 1.0) return 0.0;
        double tA = Math.max(0.0, t0);
        double tB = Math.min(1.0, t1);
        return Math.max(0.0, (tB - tA) * L);
    }

    /**
     * Helper: dot product of eigenvector column k with vector n.
     *
     * @param V TODO: describe
     * @param k TODO: describe
     * @param n TODO: describe
     * @return TODO: describe
     */
    private static double eigVecDotN(double[][] V, int k, Vector3f n) {
        return V[0][k] * n.x + V[1][k] * n.y + V[2][k] * n.z;
    }

    /**
     * Jacobi eigendecomposition of a 3x3 symmetric matrix. Eigenvalues in
     * {@code w[0..2]}, eigenvectors as columns of {@code V}. Sorted by
     * eigenvalue (ascending). Standard textbook (Numerical Recipes ch. 11.1).
     *
     * @param A TODO: describe
     * @param w TODO: describe
     * @param V TODO: describe
     */
    private static void jacobiEigen3(double[][] A, double[] w, double[][] V) {
        double[][] a = new double[NUM_3][NUM_3];
        for (int i = 0; i < NUM_3; i++) for (int j = 0; j < NUM_3; j++) a[i][j] = A[i][j];
        for (int i = 0; i < NUM_3; i++) for (int j = 0; j < NUM_3; j++) V[i][j] = (i == j) ? 1.0 : 0.0;
        for (int sweep = 0; sweep < NUM_50; sweep++) {
            double off = Math.abs(a[0][1]) + Math.abs(a[0][2]) + Math.abs(a[1][2]);
            if (off < NUM_1e_18) break;
            for (int p = 0; p < 2; p++) {
                for (int q = p + 1; q < NUM_3; q++) {
                    double apq = a[p][q];
                    if (Math.abs(apq) < NUM_1e_30_2) continue;
                    double app = a[p][p], aqq = a[q][q];
                    double theta = (aqq - app) / (NUM_2_0 * apq);
                    double t;
                    if (Math.abs(theta) > NUM_1e15) t = NUM_0_5_2 / theta;
                    else {
                        double sgn = theta >= 0 ? 1.0 : -1.0;
                        t = sgn / (Math.abs(theta) + Math.sqrt(theta * theta + 1.0));
                    }
                    double cT = 1.0 / Math.sqrt(t * t + 1.0);
                    double sT = t * cT;
                    a[p][p] = app - t * apq;
                    a[q][q] = aqq + t * apq;
                    a[p][q] = a[q][p] = 0.0;
                    for (int r = 0; r < NUM_3; r++) {
                        if (r != p && r != q) {
                            double arp = a[r][p], arq = a[r][q];
                            a[r][p] = a[p][r] = cT * arp - sT * arq;
                            a[r][q] = a[q][r] = sT * arp + cT * arq;
                        }
                        double vrp = V[r][p], vrq = V[r][q];
                        V[r][p] = cT * vrp - sT * vrq;
                        V[r][q] = sT * vrp + cT * vrq;
                    }
                }
            }
        }
        for (int i = 0; i < NUM_3; i++) w[i] = a[i][i];
        // Sort ascending by eigenvalue.
        for (int i = 0; i < 2; i++) {
            int min = i;
            for (int j = i + 1; j < NUM_3; j++) if (w[j] < w[min]) min = j;
            if (min != i) {
                double tw = w[i]; w[i] = w[min]; w[min] = tw;
                for (int r = 0; r < NUM_3; r++) {
                    double tv = V[r][i]; V[r][i] = V[r][min]; V[r][min] = tv;
                }
            }
        }
    }
}
