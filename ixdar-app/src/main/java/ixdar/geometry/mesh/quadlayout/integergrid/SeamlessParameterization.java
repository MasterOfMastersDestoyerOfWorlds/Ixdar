package ixdar.geometry.mesh.quadlayout.integergrid;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.vectorfield.CombedField;
import ixdar.geometry.mesh.quadlayout.vectorfield.FaceRosyField;
import ixdar.geometry.mesh.quadlayout.vectorfield.Singularity;

/**
 * Seamless integer-grid parametrization (Bommes-Campen-Ebke-Alliez-Kobbelt 2013,
 * also Campen 2014 thesis Stage 3c) — PATCH-48.
 *
 * <p>Builds on {@link AlignedParameterization} (PATCH-40 v2): the relaxed
 * real-valued solve produces continuous (u, v) per corner but admits triangle
 * flips. This class iteratively rounds singularity corners to integer (u, v)
 * and grows a pin set greedily, committing each pin only if injectivity
 * (positive UV signed area on every triangle) is preserved.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Solve the relaxed system (PATCH-40).</li>
 *   <li>Pin every corner that touches a singularity vertex to the integer
 *       (u, v) closest to its relaxed value. Re-solve.</li>
 *   <li>Iteratively pin the unpinned corner whose floating UV is closest to
 *       integer; reject the pin if it would flip a triangle, blacklist that
 *       (corner, axis), continue with the next-closest. Stop when no candidate
 *       pin can be committed.</li>
 * </ol>
 *
 * <p>For v1 we use reject-and-retry instead of the log-barrier formulation
 * from the paper. Reject-and-retry is sufficient on small inputs (cube,
 * subdivided cube); log-barrier is a perf improvement deferred to a later
 * PATCH.
 *
 * <h3>Public API</h3>
 * Same getters as {@link AlignedParameterization} ({@link #u}, {@link #v},
 * {@link #uvSignedArea}) plus pin introspection ({@link #pinnedCorners},
 * {@link #isSingularityCorner}, {@link #iterationCount},
 * {@link #injectiveOnAllTriangles}).
 */
public final class SeamlessParameterization {

    private final int faceCount;
    private final float[] uCorner;
    private final float[] vCorner;
    private final boolean[] singularityCorner;
    private final boolean[] uPinned;
    private final boolean[] vPinned;
    private final int iterationCount;
    private final boolean injective;

    public SeamlessParameterization(ArrayMesh mesh,
                                    FaceRosyField field,
                                    CombedField combed,
                                    List<Singularity> singularities) {
        this(mesh, field, combed, singularities, Integer.MAX_VALUE);
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
     */
    public static SeamlessParameterization fromExternal(ArrayMesh mesh,
                                                        float[] uCorner,
                                                        float[] vCorner,
                                                        boolean injective) {
        return new SeamlessParameterization(mesh.faceCount(), uCorner, vCorner, injective);
    }

    /** Private "external" constructor — bypasses the IGM solve. */
    private SeamlessParameterization(int faceCount, float[] uCorner, float[] vCorner,
                                     boolean injective) {
        this.faceCount = faceCount;
        int C = faceCount * 3;
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
     */
    public SeamlessParameterization(ArrayMesh mesh,
                                    FaceRosyField field,
                                    CombedField combed,
                                    List<Singularity> singularities,
                                    int maxRoundingIter) {
        this.faceCount = mesh.faceCount();
        int C = faceCount * 3;
        this.singularityCorner = new boolean[C];

        // Identify singularity-vertex set.
        HashSet<Integer> singVerts = new HashSet<>();
        for (Singularity s : singularities) singVerts.add(s.vertexId());
        for (int f = 0; f < faceCount; f++) {
            for (int c = 0; c < 3; c++) {
                if (singVerts.contains(mesh.faceVertexAt(f, c))) {
                    singularityCorner[f * 3 + c] = true;
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

        // Single shared Hessian — built once with the chosen scale; re-used
        // by the relaxed solve, the log-barrier Newton refinement, and the
        // iterative rounding loop.
        IgmHessian H = new IgmHessian(mesh, field, combed, scale);

        // Step 1: relaxed solve (no pins).
        double[] xRelax = H.solveWithPins(null, null, null, null);
        float[] uRelax = new float[C];
        float[] vRelax = new float[C];
        // Project chart-vertex solution back to per-corner. PATCH-54: variable
        // layout is now indexed by chart-vertex id, not face*3+corner.
        for (int f = 0; f < faceCount; f++) {
            for (int c = 0; c < 3; c++) {
                int cv = H.chart.chartVertexAt(f, c);
                int corner = f * 3 + c;
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

        // Step 2: log-barrier Gauss-Newton refinement (Bommes 2013 Sec 4).
        // Drives any degenerate / flipped triangles in the relaxed solve back
        // to strict positivity before integer rounding takes over. The barrier
        // operates on a Hessian whose targets are pre-multiplied by the same
        // post-scale so the linearisation point matches.
        IgmHessian barrierH = new IgmHessian(mesh, field, combed, scale * postScale);
        LogBarrier.Result barrier = LogBarrier.refine(
                barrierH, uRelax, vRelax,
                null, null, null, null,
                LogBarrier.DEFAULT_WEIGHT);
        float[] uStart = barrier.u;
        float[] vStart = barrier.v;
        // The iterative-rounding Hessian must use the post-scale also, so its
        // re-solves preserve the magnitude that integer pins are anchored to.
        H = barrierH;

        // Step 3: iterative rounding loop on top of the now-injective relaxed
        // solve (or, if the barrier failed to converge, on the relaxed result
        // directly — reject-and-retry will still work, just less effectively).
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
        long naturalCap = (long) loop.numCV() * 4L;
        int cap = (int) Math.min(naturalCap, maxRoundingIter);
        IterativeRounding.Result result = loop.run(cap);
        this.uCorner = result.uCorner != null ? result.uCorner : uStart;
        this.vCorner = result.vCorner != null ? result.vCorner : vStart;
        // Project chart-vertex pin state back to per-corner.
        this.uPinned = projectPinsCV(result.uPinnedCV, H);
        this.vPinned = projectPinsCV(result.vPinnedCV, H);
        this.iterationCount = result.iterationCount;
        this.injective = result.injective;
    }

    /** Map chart-vertex pin flags back to per-corner array used by the public API. */
    private static boolean[] projectPinsCV(boolean[] pinnedCV, IgmHessian H) {
        int F = H.faceCount;
        boolean[] pinnedCorner = new boolean[F * 3];
        for (int f = 0; f < F; f++) {
            for (int c = 0; c < 3; c++) {
                int cv = H.chart.chartVertexAt(f, c);
                pinnedCorner[f * 3 + c] = pinnedCV[cv];
            }
        }
        return pinnedCorner;
    }

    public float u(int faceId, int cornerIdx) {
        return uCorner[faceId * 3 + cornerIdx];
    }

    public float v(int faceId, int cornerIdx) {
        return vCorner[faceId * 3 + cornerIdx];
    }

    public float uvSignedArea(int faceId) {
        int o = faceId * 3;
        float u0 = uCorner[o], v0 = vCorner[o];
        float u1 = uCorner[o + 1], v1 = vCorner[o + 1];
        float u2 = uCorner[o + 2], v2 = vCorner[o + 2];
        return 0.5f * ((u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0));
    }

    public boolean isSingularityCorner(int faceId, int cornerIdx) {
        return singularityCorner[faceId * 3 + cornerIdx];
    }

    /** Returns flat array of corner ids (face*3 + cornerIdx) hard-pinned in BOTH u and v. */
    public int[] pinnedCorners() {
        ArrayList<Integer> out = new ArrayList<>();
        for (int c = 0; c < uPinned.length; c++) {
            if (uPinned[c] && vPinned[c]) out.add(c);
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    public int iterationCount() { return iterationCount; }

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
     */
    private static float computeUvRescale(ArrayMesh mesh, float[] u, float[] v) {
        int F = mesh.faceCount();
        if (F == 0) return 1f;
        float[] lengths = new float[F * 3];
        int written = 0;
        for (int f = 0; f < F; f++) {
            int o = f * 3;
            for (int e = 0; e < 3; e++) {
                int a = e;
                int b = (e + 1) % 3;
                float du = u[o + b] - u[o + a];
                float dv = v[o + b] - v[o + a];
                lengths[written++] = (float) Math.sqrt(du * du + dv * dv);
            }
        }
        java.util.Arrays.sort(lengths, 0, written);
        // 75th percentile rather than median — robust against the cluster of
        // tiny edges near collapsed-by-Tikhonov corners.
        float q75 = lengths[(written * 3) / 4];
        if (q75 < 1e-12f) return 1f;
        // Target: per-triangle UV edge ~ TARGET_FACE_SPAN integer cells.
        // Closed-surface parametrizations under-shoot the relaxed scale, and
        // packing distinct singularities onto distinct integer grid cells
        // requires a span comfortably larger than 1.
        final float TARGET_FACE_SPAN = 3.0f;
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
            double area = 0.5 * cross.length();
            if (area > 0) {
                total += area;
                counted++;
            }
        }
        if (counted == 0) return 1.0;
        double mean = total / counted;
        double q = Math.sqrt(mean);
        if (!(q > 1e-12)) return 1.0;
        return q;
    }

    /** Returns [uMin, uMax, vMin, vMax]. */
    public float[] uvBoundingBox() {
        if (faceCount == 0) return new float[]{0, 0, 0, 0};
        float uMin = uCorner[0], uMax = uCorner[0];
        float vMin = vCorner[0], vMax = vCorner[0];
        int n = faceCount * 3;
        for (int i = 1; i < n; i++) {
            if (uCorner[i] < uMin) uMin = uCorner[i];
            if (uCorner[i] > uMax) uMax = uCorner[i];
            if (vCorner[i] < vMin) vMin = vCorner[i];
            if (vCorner[i] > vMax) vMax = vCorner[i];
        }
        return new float[]{uMin, uMax, vMin, vMax};
    }
}
