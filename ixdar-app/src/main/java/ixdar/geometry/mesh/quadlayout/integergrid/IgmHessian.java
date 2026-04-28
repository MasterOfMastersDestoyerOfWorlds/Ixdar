package ixdar.geometry.mesh.quadlayout.integergrid;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.solver.IterativeSolver;
import ixdar.geometry.mesh.quadlayout.solver.MtjSparseMatrix;
import ixdar.geometry.mesh.quadlayout.solver.SparseLu;
import ixdar.geometry.mesh.quadlayout.solver.SparseMatrix;
import ixdar.geometry.mesh.quadlayout.vectorfield.CombedField;
import ixdar.geometry.mesh.quadlayout.vectorfield.FaceRosyField;

/**
 * Per-vertex IGM Hessian (PATCH-54). Variable layout (Bommes 2013 Sec 3 /
 * Campen 2014 Sec 6.4):
 *
 * <pre>
 *   [0          .. numCV)             -> u per chart-vertex
 *   [numCV      .. 2*numCV)           -> v per chart-vertex
 *   [2*numCV    .. 2*numCV + Es)      -> j per seam edge   (u-translation)
 *   [2*numCV+Es .. 2*numCV + 2*Es)    -> k per seam edge   (v-translation)
 * </pre>
 *
 * <p>{@code numCV = ChartVertexMap.chartVertexCount} is the number of distinct
 * (mesh_vertex, chart) pairs after cutting the surface along seam edges. For
 * a closed surface with no seams, {@code numCV = V}; with seams,
 * {@code V <= numCV <= V + 2*Es}. Inside one chart all face corners on the
 * same mesh vertex collapse to a single variable — no soft equality, no
 * redundancy.
 *
 * <p>Across the seam, the same mesh vertex appears as TWO chart-vertices
 * (one per chart), and the seam transition row
 * <pre>
 *   (u, v)_t_chart = R(r_st) (u, v)_s_chart + (j, k)
 * </pre>
 * glues them with weight {@link #SEAM_WEIGHT}. Non-seam interior edges
 * already share one chart-vertex on each endpoint, so they contribute zero
 * rows — pure savings vs the old per-corner formulation.
 *
 * <p>For rocker-arm-20k (Lyon 2021 reference) the per-vertex layout gives
 * {@code N ≈ 21k}; the per-corner layout we replaced gave {@code N ≈ 120k}.
 * The smaller system fits ojAlgo's {@link SparseMatrix}/{@link SparseLu}
 * direct solver below the 32-bit flat-index overflow threshold, replacing
 * the MTJ + iterative-CG path that failed to converge at the larger size
 * regardless of preconditioner choice.
 */
final class IgmHessian {

    // Penalty weights are chosen to keep the condition number reasonable.
    // ojAlgo's SparseLu handles ~1e8 cleanly; pushing higher costs accuracy
    // for negligible constraint-strictness gain.
    static final double SEAM_WEIGHT = 1e2;
    static final double GAUGE_WEIGHT = 1e4;
    static final double PIN_WEIGHT = 1e6;
    static final double TIKHONOV = 1e-8;

    final ArrayMesh mesh;
    final FaceRosyField field;
    final CombedField combed;
    final ChartVertexMap chart;
    final int faceCount;
    final int interiorEdgeCount;
    final int seamEdgeCount;
    /** seamSlot[edgeIdx] = index into [0, seamEdgeCount) for seam edges, -1 otherwise. */
    final int[] seamSlot;
    final int uBase;
    final int vBase;
    final int jBase;
    final int kBase;
    final int N;

    final float[] localQ;
    final double[] uTarget;
    final double[] vTarget;
    final double[] areaWeight;

    /** Cached symmetric Hessian without pins/gauge (rebuilt on solve via copy). */
    private final SparseMatrix baseH;
    private final double[] baseRhs;

    IgmHessian(ArrayMesh mesh, FaceRosyField field, CombedField combed) {
        this(mesh, field, combed, 1.0);
    }

    IgmHessian(ArrayMesh mesh, FaceRosyField field, CombedField combed, double scale) {
        this.mesh = mesh;
        this.field = field;
        this.combed = combed;
        this.faceCount = mesh.faceCount();
        this.interiorEdgeCount = field.interiorEdgeCount();
        this.chart = ChartVertexMap.build(mesh, field, combed);
        int Ei = interiorEdgeCount;
        this.seamSlot = new int[Ei];
        int seamCounter = 0;
        for (int e = 0; e < Ei; e++) {
            if (combed.isSeamEdge(e)) {
                seamSlot[e] = seamCounter++;
            } else {
                seamSlot[e] = -1;
            }
        }
        this.seamEdgeCount = seamCounter;
        int Es = seamCounter;
        int numCV = chart.chartVertexCount;
        this.uBase = 0;
        this.vBase = numCV;
        this.jBase = 2 * numCV;
        this.kBase = 2 * numCV + Es;
        this.N = 2 * numCV + 2 * Es;

        int F = faceCount;
        this.localQ = new float[F * 6];
        this.uTarget = new double[F * 2];
        this.vTarget = new double[F * 2];
        this.areaWeight = new double[F];

        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        Vector3f e0 = new Vector3f();
        Vector3f e1 = new Vector3f();
        Vector3f uF = new Vector3f();
        Vector3f vF = new Vector3f();
        for (int f = 0; f < F; f++) {
            mesh.vertexPosition(mesh.faceVertexAt(f, 0), p0);
            mesh.vertexPosition(mesh.faceVertexAt(f, 1), p1);
            mesh.vertexPosition(mesh.faceVertexAt(f, 2), p2);
            field.frameU(f, uF);
            field.frameV(f, vF);
            e0.set(p1).sub(p0);
            e1.set(p2).sub(p0);
            float q1u = e0.dot(uF), q1v = e0.dot(vF);
            float q2u = e1.dot(uF), q2v = e1.dot(vF);
            int o = f * 6;
            localQ[o]     = 0f;   localQ[o + 1] = 0f;
            localQ[o + 2] = q1u;  localQ[o + 3] = q1v;
            localQ[o + 4] = q2u;  localQ[o + 5] = q2v;
            float sa = 0.5f * (q1u * q2v - q2u * q1v);
            double area = Math.abs(sa);
            areaWeight[f] = Math.sqrt(Math.max(area, 1e-30));

            double a = combed.combedAngle(f);
            double cu = Math.cos(a);
            double su = Math.sin(a);
            uTarget[f * 2]     = cu * scale;
            uTarget[f * 2 + 1] = su * scale;
            vTarget[f * 2]     = -su * scale;
            vTarget[f * 2 + 1] = cu * scale;
        }

        // Build the base (energy + seam + Tikhonov) once. Pin / gauge layers
        // are added per solve, copying from this template.
        this.baseH = N > 0 ? new SparseMatrix(N, N) : null;
        this.baseRhs = new double[Math.max(N, 1)];
        if (N > 0) {
            for (int i = 0; i < N; i++) baseH.add(i, i, TIKHONOV);
            addEnergyTerms(baseH, baseRhs);
            addSeamTerms(baseH);
        }
    }

    private void addEnergyTerms(SparseMatrix H, double[] rhs) {
        int F = faceCount;
        for (int f = 0; f < F; f++) {
            int o = f * 6;
            float q0u = localQ[o],     q0v = localQ[o + 1];
            float q1u = localQ[o + 2], q1v = localQ[o + 3];
            float q2u = localQ[o + 4], q2v = localQ[o + 5];
            float sa = 0.5f * ((q1u - q0u) * (q2v - q0v) - (q2u - q0u) * (q1v - q0v));
            if (Math.abs(sa) < 1e-20f) continue;
            double inv2A = 1.0 / (2.0 * sa);
            double[] bi = new double[3];
            double[] ci = new double[3];
            bi[0] = (q1v - q2v) * inv2A; ci[0] = (q2u - q1u) * inv2A;
            bi[1] = (q2v - q0v) * inv2A; ci[1] = (q0u - q2u) * inv2A;
            bi[2] = (q0v - q1v) * inv2A; ci[2] = (q1u - q0u) * inv2A;
            double w = Math.abs(sa);

            int cv0 = chart.chartVertexAt(f, 0);
            int cv1 = chart.chartVertexAt(f, 1);
            int cv2 = chart.chartVertexAt(f, 2);
            int[] uCols = new int[]{uBase + cv0, uBase + cv1, uBase + cv2};
            int[] vCols = new int[]{vBase + cv0, vBase + cv1, vBase + cv2};

            double tx = uTarget[f * 2];
            double ty = uTarget[f * 2 + 1];
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    H.add(uCols[i], uCols[j], w * (bi[i] * bi[j] + ci[i] * ci[j]));
                }
                rhs[uCols[i]] += w * (bi[i] * tx + ci[i] * ty);
            }

            double tvx = vTarget[f * 2];
            double tvy = vTarget[f * 2 + 1];
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    H.add(vCols[i], vCols[j], w * (bi[i] * bi[j] + ci[i] * ci[j]));
                }
                rhs[vCols[i]] += w * (bi[i] * tvx + ci[i] * tvy);
            }
        }
    }

    private void addSeamTerms(SparseMatrix H) {
        int Ei = interiorEdgeCount;
        for (int e = 0; e < Ei; e++) {
            if (!combed.isSeamEdge(e)) continue;
            int fa = field.edgeFaceA(e);
            int fb = field.edgeFaceB(e);
            int eMesh = field.edgeMeshId(e);
            int heA = halfEdgeOnFace(eMesh, fa);
            int heB = halfEdgeOnFace(eMesh, fb);
            if (heA < 0 || heB < 0) continue;
            int cA0 = heA % 3;
            int cA1 = mesh.halfEdgeNext(heA) % 3;
            int cB0 = heB % 3;
            int cB1 = mesh.halfEdgeNext(heB) % 3;
            int r = combed.matching(e);
            int slot = seamSlot[e];
            int jVar = jBase + slot;
            int kVar = kBase + slot;
            int cvA0 = chart.chartVertexAt(fa, cA0);
            int cvA1 = chart.chartVertexAt(fa, cA1);
            int cvB0 = chart.chartVertexAt(fb, cB0);
            int cvB1 = chart.chartVertexAt(fb, cB1);
            // Half-edges on opposite sides of the same edge run in OPPOSITE
            // directions, so heA's start vertex is heB's end vertex. Pair
            // accordingly: A0 <-> B1, A1 <-> B0.
            int uA0 = uBase + cvA0, vA0 = vBase + cvA0;
            int uA1 = uBase + cvA1, vA1 = vBase + cvA1;
            int uB0 = uBase + cvB0, vB0 = vBase + cvB0;
            int uB1 = uBase + cvB1, vB1 = vBase + cvB1;
            addTransitionRow(H, uA0, vA0, uB1, vB1, jVar, kVar, r);
            addTransitionRow(H, uA1, vA1, uB0, vB0, jVar, kVar, r);
        }
    }

    private static void addTransitionRow(SparseMatrix H,
                                         int uA, int vA, int uB, int vB,
                                         int jVar, int kVar, int r) {
        double rUuA, rUvA, rVuA, rVvA;
        switch (r & 3) {
            case 0:  rUuA = -1; rUvA =  0; rVuA =  0; rVvA = -1; break;
            case 1:  rUuA =  0; rUvA =  1; rVuA = -1; rVvA =  0; break;
            case 2:  rUuA =  1; rUvA =  0; rVuA =  0; rVvA =  1; break;
            default: rUuA =  0; rUvA = -1; rVuA =  1; rVvA =  0; break;
        }
        int[] cols1 = new int[]{uA, vA, uB, jVar};
        double[] cs1 = new double[]{rUuA, rUvA, 1.0, -1.0};
        int[] cols2 = new int[]{uA, vA, vB, kVar};
        double[] cs2 = new double[]{rVuA, rVvA, 1.0, -1.0};
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                H.add(cols1[i], cols1[j], SEAM_WEIGHT * cs1[i] * cs1[j]);
                H.add(cols2[i], cols2[j], SEAM_WEIGHT * cs2[i] * cs2[j]);
            }
        }
    }

    private int halfEdgeOnFace(int edgeId, int targetFace) {
        int he = mesh.edgeHalfEdge(edgeId);
        if (mesh.halfEdgeFace(he) == targetFace) return he;
        int twin = mesh.halfEdgeTwin(he);
        if (twin >= 0 && mesh.halfEdgeFace(twin) == targetFace) return twin;
        return -1;
    }

    /**
     * Solve the system with the given pin set. Each pin clamps either the u
     * or v variable of one chart-vertex to a target value via a strong
     * penalty.
     *
     * @param uPinIdx variable indices in [uBase, uBase+numCV)
     * @param uPinVal target u values (parallel to uPinIdx)
     * @param vPinIdx variable indices in [vBase, vBase+numCV)
     * @param vPinVal target v values (parallel to vPinIdx)
     * @return solution vector of length {@link #N}
     */
    double[] solveWithPins(int[] uPinIdx, double[] uPinVal,
                           int[] vPinIdx, double[] vPinVal) {
        if (N == 0) return new double[0];
        // Default: MTJ iterative (CG + ICC). The per-vertex layout's smaller
        // and better-conditioned Hessian makes CG converge in <100 iterations
        // — orders of magnitude faster than ojAlgo's pure-Java SparseLu without
        // fill-reducing ordering. Override via -Dixdar.quadlayout.integergrid.solver=direct
        // for correctness debugging.
        String mode = System.getProperty("ixdar.quadlayout.integergrid.solver", "iterative");
        if ("direct".equals(mode)) {
            SparseMatrix H = new SparseMatrix(N, N);
            double[] rhs = new double[N];
            copyBaseInto(H, rhs);
            addGaugeAndPins(H, rhs, uPinIdx, uPinVal, vPinIdx, vPinVal);
            SparseLu lu = new SparseLu();
            lu.decompose(H);
            return lu.solve(rhs);
        }
        MtjSparseMatrix H = new MtjSparseMatrix(N, N);
        double[] rhs = new double[N];
        copyBaseIntoMtj(H, rhs);
        addGaugeAndPinsMtj(H, rhs, uPinIdx, uPinVal, vPinIdx, vPinVal);
        return IterativeSolver.solve(H, rhs);
    }

    /** Iterative-path twin of {@link #copyBaseInto}. */
    void copyBaseIntoMtj(MtjSparseMatrix H, double[] rhs) {
        if (baseH == null) return;
        baseH.ojAlgoStore().nonzeros().forEach(view -> {
            int row = (int) view.row();
            int col = (int) view.column();
            H.add(row, col, view.doubleValue());
        });
        for (int i = 0; i < N; i++) rhs[i] = baseRhs[i];
    }

    /** Iterative-path twin of {@link #addGaugeAndPins}. */
    void addGaugeAndPinsMtj(MtjSparseMatrix H, double[] rhs,
                                    int[] uPinIdx, double[] uPinVal,
                                    int[] vPinIdx, double[] vPinVal) {
        if (chart.chartVertexCount > 0) {
            H.add(uBase, uBase, GAUGE_WEIGHT);
            H.add(vBase, vBase, GAUGE_WEIGHT);
        }
        if (uPinIdx != null) {
            for (int p = 0; p < uPinIdx.length; p++) {
                H.add(uPinIdx[p], uPinIdx[p], PIN_WEIGHT);
                rhs[uPinIdx[p]] += PIN_WEIGHT * uPinVal[p];
            }
        }
        if (vPinIdx != null) {
            for (int p = 0; p < vPinIdx.length; p++) {
                H.add(vPinIdx[p], vPinIdx[p], PIN_WEIGHT);
                rhs[vPinIdx[p]] += PIN_WEIGHT * vPinVal[p];
            }
        }
    }

    /**
     * Copy the cached base Hessian and RHS into a freshly-allocated system —
     * exposed so {@link LogBarrier} can layer per-Newton-iteration log-barrier
     * rows on top without rebuilding the energy + seam terms each iteration.
     */
    void copyBaseInto(SparseMatrix H, double[] rhs) {
        if (baseH == null) return;
        baseH.ojAlgoStore().nonzeros().forEach(view -> {
            int row = (int) view.row();
            int col = (int) view.column();
            H.add(row, col, view.doubleValue());
        });
        for (int i = 0; i < N; i++) rhs[i] = baseRhs[i];
    }

    /** Apply the standard gauge pin and any caller-supplied pins. */
    void addGaugeAndPins(SparseMatrix H, double[] rhs,
                         int[] uPinIdx, double[] uPinVal,
                         int[] vPinIdx, double[] vPinVal) {
        if (chart.chartVertexCount > 0) {
            H.add(uBase, uBase, GAUGE_WEIGHT);
            H.add(vBase, vBase, GAUGE_WEIGHT);
        }
        if (uPinIdx != null) {
            for (int p = 0; p < uPinIdx.length; p++) {
                H.add(uPinIdx[p], uPinIdx[p], PIN_WEIGHT);
                rhs[uPinIdx[p]] += PIN_WEIGHT * uPinVal[p];
            }
        }
        if (vPinIdx != null) {
            for (int p = 0; p < vPinIdx.length; p++) {
                H.add(vPinIdx[p], vPinIdx[p], PIN_WEIGHT);
                rhs[vPinIdx[p]] += PIN_WEIGHT * vPinVal[p];
            }
        }
    }
}
