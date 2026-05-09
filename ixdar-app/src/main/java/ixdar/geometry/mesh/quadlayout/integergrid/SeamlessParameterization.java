package ixdar.geometry.mesh.quadlayout.integergrid;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.CrossField;
import ixdar.geometry.mesh.quadlayout.vectorfield.CombedField;
import ixdar.geometry.mesh.quadlayout.vectorfield.FaceRosyField;
import ixdar.geometry.mesh.quadlayout.vectorfield.Singularity;

/**
 * Seamless integer-grid parametrization (Bommes-Campen-Ebke-Alliez-Kobbelt 2013,
 * also Campen 2014 thesis Stage 3c) — PATCH-48.
 *
 * <p>Builds on {@link AlignedParameterization} (PATCH-40 v2): the relaxed
 * real-valued solve produces continuous (u, v) per corner but admits triangle
 * flips. PATCH-114 added BZK09 §5.4 {@link LocalStiffening} IRLS as the
 * post-relaxation step (replacing PATCH-49's never-paper-faithful log-barrier);
 * the iterative-rounding stage then drives singularity corners to integer
 * (u, v), committing each pin only if injectivity (positive UV signed area
 * on every triangle) is preserved.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Solve the relaxed system (PATCH-40).</li>
 *   <li>Run BZK09 §5.4 {@link LocalStiffening} IRLS — re-weight per-face
 *       energy by the Hormann-Lévy-Sheffer distortion measure, re-solve,
 *       repeat until injective and isometric (PATCH-114).</li>
 *   <li>Pin every corner that touches a singularity vertex to the integer
 *       (u, v) closest to its relaxed value. Re-solve.</li>
 *   <li>Iteratively pin the unpinned corner whose floating UV is closest to
 *       integer; reject the pin if it would flip a triangle, blacklist that
 *       (corner, axis), continue with the next-closest. Stop when no candidate
 *       pin can be committed.</li>
 * </ol>
 *
 * <h3>Public API</h3>
 * Same getters as {@link AlignedParameterization} ({@link #u}, {@link #v},
 * {@link #uvSignedArea}) plus pin introspection ({@link #pinnedCorners},
 * {@link #isSingularityCorner}, {@link #iterationCount},
 * {@link #injectiveOnAllTriangles}).
 */
public final class SeamlessParameterization {
    public static final int NUM_3 = 3;
    public static final float NUM_0_5 = 0.5f;
    public static final double NUM_100_0 = 100.0;
    public static final double NUM_1e_12 = 1e-12;
    public static final int NUM_6 = 6;
    public static final int NUM_4 = 4;
    public static final int NUM_5 = 5;
    public static final int NUM_100 = 100;
    public static final int NUM_12 = 12;
    public static final double NUM_0_5_2 = 0.5;
    public static final double NUM_2_0 = 2.0;
    public static final long NUM_4_2 = 4L;
    public static final float NUM_1 = 1f;
    public static final float NUM_1e_12_2 = 1e-12f;
    public static final float NUM_3_0 = 3.0f;

    private final int faceCount;
    private final float[] uCorner;
    private final float[] vCorner;
    private final boolean[] singularityCorner;
    private final boolean[] uPinned;
    private final boolean[] vPinned;
    private final int iterationCount;
    private final boolean injective;

    /**
     * Production constructor: runs the full IGM pipeline (relaxed solve →
     * BZK09 §5.4 LocalStiffening → iterative integer rounding) with no cap
     * on the rounding loop.
     *
     * @param mesh           underlying triangle mesh
     * @param field          smoothed cross field providing per-face frames
     * @param combed         combed branches / matchings / seam set
     * @param singularities  singularity list (their incident corners get
     *                       seeded with integer pins first)
     */
    public SeamlessParameterization(ArrayMesh mesh,
                                    FaceRosyField field,
                                    CombedField combed,
                                    List<Singularity> singularities) {
        this(mesh, field, combed, singularities, Integer.MAX_VALUE);
    }

    /**
     * Private "external" constructor — bypasses the IGM solve.
     *
     * @param faceCount face count; UV array lengths must equal
     *                  {@code 3 * faceCount}
     * @param uCorner   per-corner u (defensively copied)
     * @param vCorner   per-corner v (defensively copied)
     * @param injective whether the caller guarantees positive UV signed area
     *                  on every triangle
     * @throws IllegalArgumentException if {@code uCorner} or {@code vCorner}
     *                                  has the wrong length
     */
    private SeamlessParameterization(int faceCount, float[] uCorner, float[] vCorner,
                                     boolean injective) {
        this.faceCount = faceCount;
        int C = faceCount * NUM_3;
        if (uCorner.length != C || vCorner.length != C) {
            throw new IllegalArgumentException("UV corner arrays must be 3 * faceCount = " + C);
        }
        this.uCorner = uCorner.clone();
        this.vCorner = vCorner.clone();
        this.singularityCorner = new boolean[C];
        this.uPinned = new boolean[C];
        this.vPinned = new boolean[C];
        this.iterationCount = 0;
        this.injective = injective;
    }

    /**
     * Test/bootstrap-friendly variant: cap the iterative-rounding loop at
     * {@code maxRoundingIter} pins. Each pin attempt re-solves the IGM Hessian,
     * which on large meshes (Hand-30k+) goes through the MTJ iterative solver
     * (PATCH-53) and is too slow to run the natural {@code C*4} cap. Callers
     * that just want shape-compatibility (PrecomputedFieldImporter test) pass a small
     * cap; production solves leave it at {@link Integer#MAX_VALUE}.
     *
     * @param mesh            underlying triangle mesh
     * @param field           smoothed cross field providing per-face frames
     * @param combed          combed branches / matchings / seam set
     * @param singularities   singularity list (incident corners seeded first)
     * @param maxRoundingIter maximum number of iterative-rounding pin attempts;
     *                        production solves typically pass
     *                        {@link Integer#MAX_VALUE}
     */
    public SeamlessParameterization(ArrayMesh mesh,
                                    FaceRosyField field,
                                    CombedField combed,
                                    List<Singularity> singularities,
                                    int maxRoundingIter) {
        this.faceCount = mesh.faceCount();
        int C = faceCount * NUM_3;
        this.singularityCorner = new boolean[C];

        // Identify singularity-vertex set.
        HashSet<Integer> singVerts = new HashSet<>();
        for (Singularity s : singularities) singVerts.add(s.vertexId());
        for (int f = 0; f < faceCount; f++) {
            for (int c = 0; c < NUM_3; c++) {
                if (singVerts.contains(mesh.faceVertexAt(f, c))) {
                    singularityCorner[f * NUM_3 + c] = true;
                }
            }
        }

        // Bommes-2009-style global scale: q has units of "world distance per
        // UV unit", chosen so that one quad cell covers approximately one
        // average triangle. With Ā = mean per-face area, q = sqrt(Ā) yields
        // UV gradients of magnitude 1/q (i.e. UV edge ~ world edge / q).
        // Replaces the median-edge-length post-rescale heuristic that left
        // the relaxed solve free to collapse to all-zero UVs.
        double q = computeGlobalScale(mesh);
        double scale = 1.0 / q;

        // PATCH-110 diagnostic: per-stage timing so we can see which stage is slow.
        boolean diag = "true".equals(System.getProperty("ixdar.lyon.paramDiag"));
        long ts0 = System.currentTimeMillis();
        // Single shared Hessian — built once with the chosen scale; re-used
        // by the relaxed solve, the log-barrier Newton refinement, and the
        // iterative rounding loop.
        IgmHessian H = new IgmHessian(mesh, field, combed, scale);
        if (diag) System.err.printf("[seamparam-diag] built IgmHessian: %dms%n",
                System.currentTimeMillis() - ts0);

        // Step 1: relaxed solve (no pins).
        long ts1 = System.currentTimeMillis();
        double[] xRelax = H.solveWithPins(null, null, null, null);
        if (diag) System.err.printf("[seamparam-diag] step 1 relaxed solve: %dms%n",
                System.currentTimeMillis() - ts1);
        float[] uRelax = new float[C];
        float[] vRelax = new float[C];
        // Project chart-vertex solution back to per-corner. PATCH-54: variable
        // layout is now indexed by chart-vertex id, not face*3+corner.
        for (int f = 0; f < faceCount; f++) {
            for (int c = 0; c < NUM_3; c++) {
                int cv = H.chart.chartVertexAt(f, c);
                int corner = f * NUM_3 + c;
                uRelax[corner] = (float) xRelax[H.uBase + cv];
                vRelax[corner] = (float) xRelax[H.vBase + cv];
            }
        }

        // The relaxed solve is gauge-invariant under uniform UV scaling. The
        // cross-field-target energy fixes UV gradient direction but the
        // residual + Tikhonov mass cause solutions to under-shoot in absolute
        // magnitude when seam constraints are over-determined (cube case).
        // Post-rescale so the upper-quartile per-triangle edge length is ~1,
        // putting integer rounding within reach.
        float postScale = computeUvRescale(mesh, uRelax, vRelax);
        for (int i = 0; i < C; i++) {
            uRelax[i] *= postScale;
            vRelax[i] *= postScale;
        }

        // PATCH-127 diagnostic: characterize the relaxed solve's flip
        //   distribution. For each face, check signed UV area; for flipped
        //   ones, classify by adjacency to a seam edge or a singularity vertex.
        if (diag) {
            int flipCount = 0;
            int flipNearSeam = 0;
            int flipNearSing = 0;
            int flipNearBoth = 0;
            int flipFarFromBoth = 0;
            HashSet<Integer> singVertSet = new HashSet<>();
            for (Singularity s : singularities) singVertSet.add(s.vertexId());
            for (int f = 0; f < faceCount; f++) {
                int o = f * NUM_3;
                float u0 = uRelax[o], v0 = vRelax[o];
                float u1 = uRelax[o + 1], v1 = vRelax[o + 1];
                float u2 = uRelax[o + 2], v2 = vRelax[o + 2];
                float sa = NUM_0_5 * ((u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0));
                if (sa > 0) continue;
                flipCount++;
                boolean nearSing = false;
                for (int c = 0; c < NUM_3; c++) {
                    if (singVertSet.contains(mesh.faceVertexAt(f, c))) {
                        nearSing = true;
                        break;
                    }
                }
                boolean nearSeam = false;
                int faceHe = mesh.faceHalfEdgeAt(f, 0);
                for (int c = 0; c < NUM_3; c++) {
                    int he = mesh.faceHalfEdgeAt(f, c);
                    int meshEdge = mesh.halfEdgeEdge(he);
                    int Ei = field.interiorEdgeCount();
                    for (int ie = 0; ie < Ei; ie++) {
                        if (field.edgeMeshId(ie) == meshEdge && combed.isSeamEdge(ie)) {
                            nearSeam = true;
                            break;
                        }
                    }
                    if (nearSeam) break;
                }
                if (nearSing && nearSeam) flipNearBoth++;
                else if (nearSing) flipNearSing++;
                else if (nearSeam) flipNearSeam++;
                else flipFarFromBoth++;
            }
            System.err.printf("[seamparam-diag] PATCH-127: flippedFaces=%d (%.1f%%): "
                    + "nearSing=%d nearSeam=%d nearBoth=%d farFromBoth=%d%n",
                    flipCount, NUM_100_0 * flipCount / faceCount,
                    flipNearSing, flipNearSeam, flipNearBoth, flipFarFromBoth);

            // PATCH-127 second diagnostic: distribution of UV signed area
            //   magnitudes. If most flips have area ≈ -1 (consistent), the
            //   energy is systematically inverting orientation. If they're
            //   all near zero, faces are degenerate (zero-area), suggesting
            //   field-target mismatch causing collapse.
            double[] flipAreas = new double[flipCount];
            int fi = 0;
            for (int f = 0; f < faceCount; f++) {
                int o = f * NUM_3;
                float u0 = uRelax[o], v0 = vRelax[o];
                float u1 = uRelax[o + 1], v1 = vRelax[o + 1];
                float u2 = uRelax[o + 2], v2 = vRelax[o + 2];
                float sa = NUM_0_5 * ((u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0));
                if (sa <= 0) flipAreas[fi++] = sa;
            }
            // Mean flipped-area, mean positive-area for comparison.
            double sumFlip = 0;
            for (double a : flipAreas) sumFlip += a;
            double meanFlip = sumFlip / Math.max(flipCount, 1);
            double sumPos = 0;
            int posCount = 0;
            for (int f = 0; f < faceCount; f++) {
                int o = f * NUM_3;
                float u0 = uRelax[o], v0 = vRelax[o];
                float u1 = uRelax[o + 1], v1 = vRelax[o + 1];
                float u2 = uRelax[o + 2], v2 = vRelax[o + 2];
                float sa = NUM_0_5 * ((u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0));
                if (sa > 0) { sumPos += sa; posCount++; }
            }
            double meanPos = sumPos / Math.max(posCount, 1);
            System.err.printf("[seamparam-diag] PATCH-127 area dist: meanFlipArea=%.4f  meanPosArea=%.4f  ratio=%.3f%n",
                    meanFlip, meanPos, Math.abs(meanFlip) / Math.max(meanPos, NUM_1e_12));

            // PATCH-127 third diagnostic: also check the SAME flipped-area
            // measure against the LOCAL-FRAME signed area (the 3D-to-2D
            // projection's orientation). If the local frame and the UV-frame
            // disagree per-face, the energy formulation is incoherent.
            int localPosUvFlip = 0;
            int localNegUvFlip = 0;
            for (int f = 0; f < faceCount; f++) {
                int o = f * NUM_3;
                float u0 = uRelax[o], v0 = vRelax[o];
                float u1 = uRelax[o + 1], v1 = vRelax[o + 1];
                float u2 = uRelax[o + 2], v2 = vRelax[o + 2];
                float uvSa = NUM_0_5 * ((u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0));
                if (uvSa > 0) continue;
                int oQ = f * NUM_6;
                float q1u = H.localQ[oQ + 2], q1v = H.localQ[oQ + NUM_3];
                float q2u = H.localQ[oQ + NUM_4], q2v = H.localQ[oQ + NUM_5];
                float localSa = NUM_0_5 * (q1u * q2v - q2u * q1v);
                if (localSa > 0) localPosUvFlip++;
                else localNegUvFlip++;
            }
            System.err.printf("[seamparam-diag] PATCH-127 frame check: of %d uv-flipped faces, "
                    + "localFrame +area=%d, localFrame -area=%d (any -area suggests bad frame)%n",
                    flipCount, localPosUvFlip, localNegUvFlip);

            // PATCH-134 NOTE: an earlier per-face residual diagnostic was here
            //   but compared post-rescaled gradients against pre-rescale targets,
            //   making it misleading. Removed. The actual fix from PATCH-134
            //   was bumping IterativeSolver.DEFAULT_MAX_ITER from 1000 to 10000
            //   so the relaxed solve converges (was hitting the cap with
            //   ok=false and returning a partial iterate). Convergence now
            //   confirmed via -Dixdar.quadlayout.solver.profile=true: relaxed
            //   IGM solve takes ~3700 iterations and converges to tolerance.

            // PATCH-131: chart structure summary.
            System.err.printf("[seamparam-diag] PATCH-131 chart structure: chartCount=%d  chartVertexCount=%d  seamEdgeCount=%d  N=%d (uBase=%d vBase=%d jBase=%d kBase=%d)%n",
                    H.chart.chartCount, H.chart.chartVertexCount,
                    H.seamEdgeCount, H.N, H.uBase, H.vBase, H.jBase, H.kBase);

            // PATCH-132: dump (j, k) post-solve values for small meshes.
            //   These should be small (near 0 or small integers); pathological
            //   large values suggest the relaxed solve is using them to absorb
            //   distortions.
            if (faceCount <= NUM_100 && H.seamEdgeCount > 0) {
                System.err.println("[seamparam-diag] PATCH-132 (j, k) post-solve values:");
                int Ei = field.interiorEdgeCount();
                for (int ie = 0; ie < Ei; ie++) {
                    int slot = H.seamSlot[ie];
                    if (slot < 0) continue;
                    double j = xRelax[H.jBase + slot] / postScale;
                    double k = xRelax[H.kBase + slot] / postScale;
                    int fa = field.edgeFaceA(ie), fb = field.edgeFaceB(ie);
                    int chartA = H.chart.faceChart[fa];
                    int chartB = H.chart.faceChart[fb];
                    int r = combed.matching(ie);
                    System.err.printf("  edge=%d (fA=%d/chartA=%d  fB=%d/chartB=%d  r=%d):  j=%.4f  k=%.4f%n",
                            ie, fa, chartA, fb, chartB, r, j, k);
                }
            }

            // PATCH-132: corner -> chart-vertex map for small meshes. Verify
            //   each mesh vertex maps to ONE chart-vertex within the same chart.
            if (faceCount <= NUM_12) {
                System.err.println("[seamparam-diag] PATCH-132 corner -> chartVertex map:");
                for (int f = 0; f < faceCount; f++) {
                    System.err.printf("  f=%d:", f);
                    for (int c = 0; c < NUM_3; c++) {
                        int meshV = mesh.faceVertexAt(f, c);
                        int cv = H.chart.chartVertexAt(f, c);
                        System.err.printf("  c%d:meshV=%d→cv=%d", c, meshV, cv);
                    }
                    System.err.println();
                }
            }

            // PATCH-131: per-face Jacobian dump (only for small meshes — would
            //   spam the log on rocker-arm). Compares solved Jacobian vs target.
            if (faceCount <= NUM_100) {
                System.err.printf("[seamparam-diag] PATCH-131 per-face dump (F=%d):%n", faceCount);
                for (int f = 0; f < faceCount; f++) {
                    int o = f * NUM_3;
                    int oQ = f * NUM_6;
                    float q1u = H.localQ[oQ + 2], q1v = H.localQ[oQ + NUM_3];
                    float q2u = H.localQ[oQ + NUM_4], q2v = H.localQ[oQ + NUM_5];
                    double sa = NUM_0_5_2 * (q1u * q2v - q2u * q1v);
                    double inv2A = 1.0 / (NUM_2_0 * sa);
                    double b0 = (q1v - q2v) * inv2A, c0 = (q2u - q1u) * inv2A;
                    double b1 = (q2v - 0)   * inv2A, c1 = (0 - q2u)   * inv2A;
                    double b2 = (0 - q1v)   * inv2A, c2 = (q1u - 0)   * inv2A;
                    double u0 = uRelax[o], u1 = uRelax[o + 1], u2 = uRelax[o + 2];
                    double v0 = vRelax[o], v1 = vRelax[o + 1], v2 = vRelax[o + 2];
                    double dudx = u0 * b0 + u1 * b1 + u2 * b2;
                    double dudy = u0 * c0 + u1 * c1 + u2 * c2;
                    double dvdx = v0 * b0 + v1 * b1 + v2 * b2;
                    double dvdy = v0 * c0 + v1 * c1 + v2 * c2;
                    double det = dudx * dvdy - dudy * dvdx;
                    double tx = H.uTarget[f * 2], ty = H.uTarget[f * 2 + 1];
                    double tvx = H.vTarget[f * 2], tvy = H.vTarget[f * 2 + 1];
                    double targetDet = tx * tvy - ty * tvx;
                    int chart = H.chart.faceChart[f];
                    System.err.printf("  f=%2d chart=%d  J=[%.3f %.3f; %.3f %.3f] det=%.3f  "
                            + "target=[%.3f %.3f; %.3f %.3f] tdet=%.3f%n",
                            f, chart, dudx, dudy, dvdx, dvdy, det,
                            tx, ty, tvx, tvy, targetDet);
                }
            }
        }

        // Step 2: BZK09 §5.4 LocalStiffening (PATCH-114). IRLS reweighting
        // drives flipped / heavily-distorted triangles back toward isometry
        // before integer rounding takes over. Each iteration is a convex QP,
        // so unlike the old log-barrier path it can't diverge to NaN. Operates
        // on a Hessian whose targets are pre-multiplied by the post-scale so
        // the IRLS distortion measure matches the (u, v) magnitudes.
        long ts2 = System.currentTimeMillis();
        IgmHessian stiffenedH = new IgmHessian(mesh, field, combed, scale * postScale);
        LocalStiffening.Result stiff = LocalStiffening.refine(stiffenedH, uRelax, vRelax);
        if (diag) System.err.printf("[seamparam-diag] step 2 local-stiffening: %dms injective=%s iters=%d%n",
                System.currentTimeMillis() - ts2, stiff.injective, stiff.iterations);
        float[] uStart = stiff.u;
        float[] vStart = stiff.v;
        // The iterative-rounding Hessian must use the post-scale also, so its
        // re-solves preserve the magnitude that integer pins are anchored to.
        // The stiffening weights left installed by LocalStiffening carry into
        // the rounding stage — BZK09's intent: distortion-aware weighting all
        // the way through.
        H = stiffenedH;

        IterativeRounding loop = new IterativeRounding(H);
        int[] singArr = singVerts.stream().mapToInt(Integer::intValue).toArray();
        loop.seedSingularityPins(singArr, uStart, vStart);

        // Cap iterations so a degenerate case can't run forever; 4 * corners is
        // generous (each corner can only be pinned/blacklisted once per axis).
        // Large meshes pass a smaller explicit cap because each re-solve goes
        // through the MTJ iterative solver (PATCH-53) and the natural cap is
        // multi-hour at 30k-tri scale.
        // PATCH-54: each pin attempt is now a direct sparse LU on N ~ 2V+2Es
        // (~21k for rocker-arm, ~33k for Hand-30k). At those sizes we can let
        // the natural cap run; ojAlgo SparseLu handles each re-solve in <1s.
        long naturalCap = (long) loop.numCV() * NUM_4_2;
        int cap = (int) Math.min(naturalCap, maxRoundingIter);
        long ts3 = System.currentTimeMillis();
        IterativeRounding.Result result = loop.run(cap);
        if (diag) System.err.printf("[seamparam-diag] step 3 iterative rounding: %dms (cap=%d) injective=%s%n",
                System.currentTimeMillis() - ts3, cap, result.injective);
        this.uCorner = result.uCorner != null ? result.uCorner : uStart;
        this.vCorner = result.vCorner != null ? result.vCorner : vStart;
        // Project chart-vertex pin state back to per-corner.
        this.uPinned = projectPinsCV(result.uPinnedCV, H);
        this.vPinned = projectPinsCV(result.vPinnedCV, H);
        this.iterationCount = result.iterationCount;
        this.injective = result.injective;
    }

    /**
     * PATCH-59: build a SeamlessParameterization from externally-supplied
     * per-corner UVs (e.g. metriko's {@code stage2_uv_corners.tsv}).
     * Bypasses the IGM solve entirely and just stores the given UV map
     * so downstream stages (motorcycle / T-mesh / QEx) can be exercised
     * with a known-good input.
     *
     * @param mesh underlying triangle mesh
     * @param uCorner per-corner u, length {@code 3 * F}
     * @param vCorner per-corner v, length {@code 3 * F}
     * @param injective {@code true} if caller guarantees no flipped triangles
     *                  in the input UVs (metriko stage2 is)
     * @return a {@code SeamlessParameterization} with the supplied UVs and
     *         no pin metadata; the IGM solve is not run
     */
    public static SeamlessParameterization fromExternal(ArrayMesh mesh,
                                                        float[] uCorner,
                                                        float[] vCorner,
                                                        boolean injective) {
        return new SeamlessParameterization(mesh.faceCount(), uCorner, vCorner, injective);
    }

    //public SeamlessParameterization(HalfEdgeMesh mesh, CrossField crossField, List<Singularity> singularities) {
    //    //TODO Auto-generated constructor stub
    //}

    /**
     * Map chart-vertex pin flags back to per-corner array used by the public API.
     *
     * @param pinnedCV per-chart-vertex pin mask
     * @param H        IGM Hessian providing the corner→chart-vertex map
     * @return per-corner pin mask of length {@code 3 * faceCount}
     */
    private static boolean[] projectPinsCV(boolean[] pinnedCV, IgmHessian H) {
        int F = H.faceCount;
        boolean[] pinnedCorner = new boolean[F * NUM_3];
        for (int f = 0; f < F; f++) {
            for (int c = 0; c < NUM_3; c++) {
                int cv = H.chart.chartVertexAt(f, c);
                pinnedCorner[f * NUM_3 + c] = pinnedCV[cv];
            }
        }
        return pinnedCorner;
    }

    /**
     * Per-corner u parameterization coordinate.
     *
     * @param faceId    active face id
     * @param cornerIdx corner index in {@code [0, 3)}
     * @return per-corner u-coordinate
     */
    public float u(int faceId, int cornerIdx) {
        return uCorner[faceId * NUM_3 + cornerIdx];
    }

    /**
     * Per-corner v parameterization coordinate.
     *
     * @param faceId    active face id
     * @param cornerIdx corner index in {@code [0, 3)}
     * @return per-corner v-coordinate
     */
    public float v(int faceId, int cornerIdx) {
        return vCorner[faceId * NUM_3 + cornerIdx];
    }

    /**
     * Signed area of a face in UV parameter space.
     *
     * @param faceId active face id
     * @return signed UV-space triangle area; positive means the (u, v) image
     *         preserves orientation (no flip)
     */
    public float uvSignedArea(int faceId) {
        int o = faceId * NUM_3;
        float u0 = uCorner[o], v0 = vCorner[o];
        float u1 = uCorner[o + 1], v1 = vCorner[o + 1];
        float u2 = uCorner[o + 2], v2 = vCorner[o + 2];
        return NUM_0_5 * ((u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0));
    }

    /**
     * Whether a corner sits at a singularity vertex.
     *
     * @param faceId    active face id
     * @param cornerIdx corner index in {@code [0, 3)}
     * @return {@code true} iff this corner's mesh vertex is in the
     *         singularity set (independent of pinning)
     */
    public boolean isSingularityCorner(int faceId, int cornerIdx) {
        return singularityCorner[faceId * NUM_3 + cornerIdx];
    }

    /**
     * Returns flat array of corner ids (face*3 + cornerIdx) hard-pinned in BOTH u and v.
     *
     * @return ids of every corner pinned in both u and v at the end of the
     *         iterative-rounding loop
     */
    public int[] pinnedCorners() {
        ArrayList<Integer> out = new ArrayList<>();
        for (int c = 0; c < uPinned.length; c++) {
            if (uPinned[c] && vPinned[c]) out.add(c);
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Number of iterative-rounding pin attempts performed.
     *
     * @return number of iterative-rounding pin attempts (commit + reject) the
     *         loop performed before exit
     */
    public int iterationCount() { return iterationCount; }

    /**
     * Whether the final UV map preserves triangle orientation everywhere.
     *
     * @return {@code true} iff every triangle has positive UV signed area at
     *         the final solution
     */
    public boolean injectiveOnAllTriangles() { return injective; }

    /**
     * Post-rescale heuristic used after the relaxed solve to lift UV magnitudes
     * out of the under-shoot regime. Picks a multiplicative factor that puts
     * the relaxed UVs at a typical face span of {@code TARGET_FACE_SPAN}
     * units, i.e. each face spans approximately that many integer grid cells.
     * Falls back to 1.0 if the relaxed solve is degenerate.
     *
     * <p>Why &gt;1: the relaxed solve compromises the cross-field gradient
     * targets against seam constraints and Tikhonov; for closed surfaces
     * (cube, torus) the per-face span often comes out an order of magnitude
     * below the target unit. Without rescale the iterative-rounding step
     * collapses every singularity corner to the same integer (0, 0).
     *
     * @param mesh triangle mesh
     * @param u    relaxed-solve per-corner u
     * @param v    relaxed-solve per-corner v
     * @return multiplicative rescale factor; {@code 1.0} when relaxed solve
     *         is degenerate (75th-percentile edge length below threshold)
     */
    private static float computeUvRescale(ArrayMesh mesh, float[] u, float[] v) {
        int F = mesh.faceCount();
        if (F == 0) return NUM_1;
        float[] lengths = new float[F * NUM_3];
        int written = 0;
        for (int f = 0; f < F; f++) {
            int o = f * NUM_3;
            for (int e = 0; e < NUM_3; e++) {
                int a = e;
                int b = (e + 1) % NUM_3;
                float du = u[o + b] - u[o + a];
                float dv = v[o + b] - v[o + a];
                lengths[written++] = (float) Math.sqrt(du * du + dv * dv);
            }
        }
        java.util.Arrays.sort(lengths, 0, written);
        // 75th percentile rather than median — robust against the cluster of
        // tiny edges near collapsed-by-Tikhonov corners.
        float q75 = lengths[(written * NUM_3) / NUM_4];
        if (q75 < NUM_1e_12_2) return NUM_1;
        // Target: per-triangle UV edge ~ TARGET_FACE_SPAN integer cells.
        // Closed-surface parametrizations under-shoot the relaxed scale, and
        // packing distinct singularities onto distinct integer grid cells
        // requires a span comfortably larger than 1.
        final float TARGET_FACE_SPAN = NUM_3_0;
        return TARGET_FACE_SPAN / q75;
    }

    /**
     * Compute Bommes-2009 / Campen-2014 global scale parameter {@code q}.
     * One unit of UV corresponds to {@code q} units of world distance, so
     * gradient targets in the relaxed energy become unit-vector / q and the
     * resulting UV layout has typical face edge length ~ world_edge / q.
     *
     * <p>Choice: {@code q = sqrt(mean_face_area)}. For unit-density layouts
     * this puts approximately one quad cell per triangle. Falls back to 1.0
     * for degenerate / empty meshes.
     *
     * @param mesh triangle mesh
     * @return global scale parameter {@code q}; {@code 1.0} for empty or
     *         degenerate meshes
     */
    private static double computeGlobalScale(ArrayMesh mesh) {
        int F = mesh.faceCount();
        if (F == 0) return 1.0;
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        Vector3f e0 = new Vector3f();
        Vector3f e1 = new Vector3f();
        Vector3f cross = new Vector3f();
        double total = 0.0;
        int counted = 0;
        for (int f = 0; f < F; f++) {
            mesh.vertexPosition(mesh.faceVertexAt(f, 0), p0);
            mesh.vertexPosition(mesh.faceVertexAt(f, 1), p1);
            mesh.vertexPosition(mesh.faceVertexAt(f, 2), p2);
            e0.set(p1).sub(p0);
            e1.set(p2).sub(p0);
            e0.cross(e1, cross);
            double area = NUM_0_5_2 * cross.length();
            if (area > 0) {
                total += area;
                counted++;
            }
        }
        if (counted == 0) return 1.0;
        double mean = total / counted;
        double q = Math.sqrt(mean);
        if (!(q > NUM_1e_12)) return 1.0;
        return q;
    }

    /**
     * Returns [uMin, uMax, vMin, vMax].
     *
     * @return four-element {@code [uMin, uMax, vMin, vMax]} over all corners,
     *         or all zeros for an empty mesh
     */
    public float[] uvBoundingBox() {
        if (faceCount == 0) return new float[]{0, 0, 0, 0};
        float uMin = uCorner[0], uMax = uCorner[0];
        float vMin = vCorner[0], vMax = vCorner[0];
        int n = faceCount * NUM_3;
        for (int i = 1; i < n; i++) {
            if (uCorner[i] < uMin) uMin = uCorner[i];
            if (uCorner[i] > uMax) uMax = uCorner[i];
            if (vCorner[i] < vMin) vMin = vCorner[i];
            if (vCorner[i] > vMax) vMax = vCorner[i];
        }
        return new float[]{uMin, uMax, vMin, vMax};
    }

    /**
     * Auto-generated unimplemented stub; always throws.
     *
     * @throws UnsupportedOperationException always
     * @return never returns normally
     */
    public SeamlessParameterization build() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'build'");
    }

    /**
     * Auto-generated unimplemented stub; always throws.
     *
     * @throws UnsupportedOperationException always
     * @return never returns normally
     */
    public SeamlessParameterization makeExactlySeamless() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'makeExactlySeamless'");
    }
}
