package ixdar.geometry.mesh.quadlayout.seamless;

import java.util.ArrayDeque;
import java.util.Arrays;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import ixdar.geometry.mesh.quadlayout.seamless.exact.ExactArithmetic;
import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.NormalMatrix;
import ixdar.geometry.mesh.quadlayout.Singularity;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.seamless.exact.SeamlessProjector;
import ixdar.geometry.mesh.quadlayout.solver.DirectSolver;

/**
 * BZK09 §5 seamless parametrization, stage 3 of the Lyon 2021 quad-layout
 * pipeline.
 *
 * <p>
 * Given a {@link CrossField} (per-face θ, per-edge period jumps, singularities,
 * local frames), produces per-corner (u, v) on the triangle mesh whose
 * gradients follow the cross-field directions and that is <em>seamless</em>
 * across cuts: across each cut edge,
 *
 * <pre>
 *   (u', v') = R<sub>r_e · π/2</sub>(u, v) + (s<sub>e</sub>, t<sub>e</sub>)
 * </pre>
 *
 * with rotation r<sub>e</sub> ∈ {0,1,2,3} fixed by the cross field and real
 * translation (s<sub>e</sub>, t<sub>e</sub>). The output is the input the Lyon
 * 2021 §3 motorcycle T-mesh expects.
 *
 * <p>
 * Algorithm verbatim from BZK09 §5: an all-continuous solve, then BZK09 §2
 * greedy mixed-integer rounding of (a) every singularity's chart-vertex (u, v)
 * and (b) every cut edge's translation (j, k), then optional §5.4 IRLS
 * stiffening. The (j, k) ∈ ℤ and integer-pinned singularities make the result
 * an integer-grid map of the surface, exactly the seamless input Lyon 2021's
 * motorcycle T-mesh stage expects.
 *
 * <h3>Pipeline</h3>
 * <ol>
 * <li><b>C1 cut graph</b>: dual spanning tree → primal cuts → trim dangling
 * paths → connect each interior singularity by Dijkstra path to the cut.
 * <li><b>C2 branch propagation</b>: BFS over non-cut edges assigns a per-face
 * branch g_f ∈ {0,1,2,3} so neighbouring faces share an oriented cross.
 * <li><b>C3 chart vertices</b>: union-find over corners across non-cut edges.
 * Each (mesh-vertex, chart) pair gets one chart-vertex id with two real DOFs
 * (u, v).
 * <li><b>C4 system assembly</b>: per-triangle gradient-target energy plus
 * per-cut-edge soft transition penalty plus a gauge pin on chart-vertex 0.
 * <li><b>C5 solve</b>: sparse SPD normal equations via EJML's
 * {@code LinearSolverFactory_DSCC.cholesky}.
 * <li><b>C6 stiffening</b>: if any face flipped, double its IRLS weight and
 * re-solve, capped at {@link #maxStiffeningIterations}.
 * <li><b>C7 project</b>: chart-vertex DOFs → per-corner (u, v).
 * </ol>
 *
 * @see CrossField
 */
public final class SeamlessParameterization {

    /** Triangle corner count. */
    public static final int CORNERS_PER_FACE = 3;
    /** Number of cross-field branches (a 4-RoSy field has 4). */
    public static final int BRANCH_COUNT = 4;
    static final float HALF_PI = (float) (Math.PI / 2.0);
    private static final float HALF = 0.5f;
    private static final double HALF_D = 0.5;
    private static final double DEGENERATE_AREA_EPS = 1.0e-30;
    private static final double SVD_DET_FACTOR = 4.0;
    /**
     * Coefficient-magnitude threshold below which a leftover-row entry is
     * treated as zero during sparse Gauss-Jordan elimination. Sized to swallow
     * round-off from {@code R · (s, t)} cancellations at singularity nodes.
     */
    private static final double LEFTOVER_REDUCE_TOLERANCE = 1.0e-10;
    /** Components per chart vertex: 2 (u and v). */
    private static final int COMPONENTS_PER_CHART_VERTEX = 2;
    private static final double NANOS_PER_SEC = 1.0e9;
    private static final int SHIFT_32 = 32;
    private static final long MASK_32 = 0xFFFFFFFFL;
    private static final int AVG_NONZEROS_PER_ROW = 8;
    private static final String DIAG_PROP = "seamlessParam.diag";
    private static final String DIAG_TRUE = "true";
    private static final int DIAG_LOG_EVERY = 10;

    public final HalfEdgeMesh mesh;
    public final CrossField crossField;

    /** Per-corner u, length {@code 3 * faceCount} (active-face order). */
    public float[] uCorner;
    /** Per-corner v, length {@code 3 * faceCount}. */
    public float[] vCorner;

    /**
     * Cut transition translation s<sub>e</sub>; only valid for INTERIOR cut edges.
     */
    public float[] cutTranslationS;
    /**
     * Cut transition translation t<sub>e</sub>; only valid for INTERIOR cut edges.
     */
    public float[] cutTranslationT;

    /** True iff every triangle has positive UV signed area. */
    public boolean injective;
    /**
     * Number of §5.4 stiffening re-solves performed (0 if the relaxed solve was
     * injective).
     */
    public int stiffeningIterations;

    /**
     * Global UV scale (BZK09 §5 "h"). Defaults to mean mesh edge length so the
     * resulting (u, v) is in world-distance units and the {@code 1/h} gradient
     * targets are well-conditioned.
     */
    public float h;

    /** Diagonal pin weight on each per-chart gauge corner. */
    public float gaugePinWeight = 1.0e6f;

    /** Hard cap on §5.4 IRLS iterations. */
    public int maxStiffeningIterations = 50;

    /**
     * §5.4 weight growth factor per pass for flipped triangles (multiplicative not
     * additive). 4.0 converges sphere-class meshes in ~30 iters; harder meshes
     * (bolt, rockerarm) diverge under this simplified IRLS — see
     * {@link #stiffeningSmoothPasses} and the class-level note on §5.4 fidelity.
     */
    public float stiffeningGrowth = 4.0f;

    /**
     * §5.4 maximum per-face IRLS weight; bounds the multiplicative flip kick.
     */
    public double stiffeningWeightCap = 1.0e8;

    /**
     * BZK09 §5.4 uniform-Laplacian smoothing passes applied to the per-face weight
     * field after each multiplicative bump. Smoothing damps the single-face
     * oscillations the simplified stiffening otherwise exhibits on non-trivial
     * meshes (a flip in face f raises w(f), the next solve flips face f's
     * neighbour, etc.). 3 rings is enough on the test fixtures.
     */
    public int stiffeningSmoothPasses = 1;

    /**
     * §5.4 proportionality constant for the {@code |Δλ|} bump. Paper recommends c =
     * 1 for IGM where flips are rare; for the seamless mode without integer
     * singularity pinning, the relaxed solve has many flips and needs much faster
     * weight growth — c = 100 converges sphere in &lt;30 iterations.
     */
    public double stiffeningC = 100.0;

    /**
     * §5.4 maximum per-pass weight bump (paper: d = 5; we raise it to keep up with
     * c).
     */
    public double stiffeningD = 1.0e4;

    /**
     * If true, run BZK09 §2 greedy mixed-integer rounding of (j, k) cut
     * translations and singularity chart-vertex (u, v) — produces an INTEGER-GRID
     * MAP per BZK09 §5. Lyon 2021 §3 wants a SEAMLESS map (real (s, t), real
     * singularities) as input — the integer grid is built later by the ILP. Default
     * false to match Lyon's pipeline; enable for downstream stages (e.g. QEx-style
     * quad mesh extraction direct from BZK09's IGM).
     */
    public boolean integerGridMap = false;

    /**
     * If true, run MC19 (Mandad–Campen 2019) exact-constraint projection after
     * the §5.4 stiffening loop. Drives the per-cut-edge transition residual to
     * literal zero, making the output safe to feed into Lyon 2021's T-mesh
     * stage. Default false until the projection is proven on all fixtures.
     */
    public boolean exactSeams = false;

    /** Cut graph. */
    public CutGraph cutGraph;

    /** Metrics summary populated by {@link #build()}. */
    public ParameterizationMetrics metrics;

    public int faceCount;
    public int edgeCount;

    /**
     * Active-edge → active-face index on the "A" side; -1 if that side is boundary.
     */
    public int[] edgeFaceA;
    /**
     * Active-edge → active-face index on the "B" side; -1 if that side is boundary.
     */
    public int[] edgeFaceB;

    /**
     * Active-edge → corner index of {@code halfEdgeVertex(edgeHalfEdge)} in face A.
     */
    public int[] edgeCornerInA;
    /**
     * Active-edge → corner index of {@code halfEdgeVertex(edgeHalfEdge)} in face B.
     */
    public int[] edgeCornerInB;

    /**
     * Per active edge: index of the {@code s} translation raw-DOF for that
     * cut edge; -1 if the edge is not an interior cut edge. Raw DOFs live in
     * a pre-elimination space; non-pivot raw DOFs map to final DOFs via
     * {@link #rawDofToFinal}.
     */
    private int[] cutEdgeSDof;
    /** Sibling of {@link #cutEdgeSDof} for the {@code t} translation. */
    private int[] cutEdgeTDof;
    /**
     * Pre-leftover-elimination DOF count
     * ({@code 2*primaryChartCount + 2*interiorCutEdgeCount}).
     */
    private int rawDofCount;
    /**
     * Per raw DOF: dense final-DOF index in {@code [0, dofCount)}, or -1 if
     * the raw DOF was a pivot eliminated by leftover-row reduction.
     */
    private int[] rawDofToFinal;
    /**
     * Substitution rules from leftover-row reduction. Indexed by raw DOF:
     * pivot raw-DOFs map to the list of non-pivot raw-DOFs they expand into
     * (coefficients in {@link #leftoverPivotCoefs}); non-pivot raw DOFs are
     * {@code null}.
     */
    private int[][] leftoverPivotDofs;
    /** Coefficients matching {@link #leftoverPivotDofs}. */
    private double[][] leftoverPivotCoefs;
    /**
     * Per chart vertex, per component (u=0, v=1): final-DOF indices that
     * this chart vertex's value expands to after both per-cut-edge
     * substitution and leftover-row elimination.
     */
    private int[][][] chartVertexFinalDofs;
    /** Coefficients matching {@link #chartVertexFinalDofs}. */
    private double[][][] chartVertexFinalCoefs;

    /** §5.4 IRLS weights, initialized to 1. */
    private double[] faceWeight;

    /** Per-face 3D area (active-face order). */
    private double[] faceArea;
    /** Length 3 per face: b_i (= ∂φ/∂x coefficients in local frame). */
    private double[] faceShapeB;
    /** Length 3 per face: c_i (= ∂φ/∂y coefficients in local frame). */
    private double[] faceShapeC;
    /** u_T(ξ) x-component in local frame, post branch rotation. */
    private double[] faceUtxLocal;
    /** u_T(ξ) y-component in local frame, post branch rotation. */
    private double[] faceUtyLocal;
    /** v_T(ξ) x-component in local frame, post branch rotation. */
    private double[] faceVtxLocal;
    /** v_T(ξ) y-component in local frame, post branch rotation. */
    private double[] faceVtyLocal;

    /** Last solver output (size {@link #dofCount}). */
    private double[] solution;
    /** Total final-DOF count after leftover-row elimination. */
    private int dofCount;

    /**
     * BZK09 §5 mixed-integer state. dofIsInteger[i] is true if DOF i is one of the
     * variables that the greedy MI loop will round (singularity (u, v) or cut
     * translation (s, t)). dofPinned[i] is true after greedy rounding has committed
     * an integer for that DOF; dofPinnedValue[i] holds the integer.
     */
    private boolean[] dofIsInteger;
    private boolean[] dofPinned;
    private double[] dofPinnedValue;

    /** Soft-pin diagonal weight applied to a rounded integer DOF. */
    private double integerPinWeight = 1.0e10;

    /**
     * Adopts a built {@link CrossField}. Caller must invoke {@link #build()} to
     * actually compute the parametrization.
     *
     * @param crossField a CrossField that has already had {@code build()} called
     */
    public SeamlessParameterization(CrossField crossField) {
        this.crossField = crossField;
        this.mesh = crossField.mesh;
    }

    /**
     * Run the BZK09 §5 pipeline; populate the public output arrays.
     *
     * @return {@code this}
     */
    public ParameterizationMetrics build() {
        this.faceCount = mesh.faceCount();
        this.edgeCount = mesh.edgeCount();
        if (this.h <= 0f) {
            this.h = mesh.computeAverageEdgeLength();
            if (this.h <= 0f)
                this.h = 1.0f;
        }

        edgeFaceA = new int[edgeCount];
        edgeFaceB = new int[edgeCount];
        edgeCornerInA = new int[edgeCount];
        edgeCornerInB = new int[edgeCount];
        Arrays.fill(edgeFaceA, -1);
        Arrays.fill(edgeFaceB, -1);
        Arrays.fill(edgeCornerInA, -1);
        Arrays.fill(edgeCornerInB, -1);

        for (int ae2 = 0; ae2 < edgeCount; ae2++) {
            int eId = mesh.edgeIdAt(ae2);
            int hCanon = mesh.edgeHalfEdge(eId);
            int faceAId = mesh.halfEdgeFace(hCanon);
            int twin = mesh.halfEdgeTwin(hCanon);
            int faceBId = (twin == MeshTopology.NONE) ? MeshTopology.NONE : mesh.halfEdgeFace(twin);
            int vStart = mesh.halfEdgeVertex(hCanon);

            if (faceAId != MeshTopology.NONE) {
                edgeFaceA[ae2] = crossField.faceIdToActive.get(faceAId);
                int corner = -1;
                for (int c1 = 0; c1 < CORNERS_PER_FACE; c1++) {
                    if (mesh.faceVertexAt(faceAId, c1) == vStart) {
                        corner = c1;
                        break;
                    }
                }
                edgeCornerInA[ae2] = corner;
            }
            if (faceBId != MeshTopology.NONE) {
                edgeFaceB[ae2] = crossField.faceIdToActive.get(faceBId);
                int corner1 = -1;
                for (int c2 = 0; c2 < CORNERS_PER_FACE; c2++) {
                    if (mesh.faceVertexAt(faceBId, c2) == vStart) {
                        corner1 = c2;
                        break;
                    }
                }
                edgeCornerInB[ae2] = corner1;
            }
        }

        cutGraph = new CutGraph(mesh, crossField, this);
        cutGraph.buildCutGraph();

        precomputePerFaceGeometryAndTargets();

        this.faceWeight = new double[faceCount];
        Arrays.fill(faceWeight, 1.0);

        assignCutEdgeTranslationDofs();
        this.rawDofCount = 2 * cutGraph.primaryChartCount + 2 * cutGraph.interiorCutEdgeCount;
        reduceLeftoverConstraints();
        buildChartVertexFinalExpansions();

        dofIsInteger = new boolean[dofCount];
        dofPinned = new boolean[dofCount];
        dofPinnedValue = new double[dofCount];
        markIntegerDofs();

        // BZK09 §5: (1) all-continuous solve. If {@link #integerGridMap} is on,
        // (2) BZK09 §2 greedy round (j, k) and singularity (u, v) to integers,
        // re-solving after each pin. Then (3) §5.4 IRLS stiffening using the
        // Hormann-Lévy-Sheffer distortion + uniform-Laplacian weight updates
        // (paper recipe). Lyon 2021 wants a *seamless* map (real (s, t), real
        // singularities), so {@link #integerGridMap} defaults off.
        solveOnce();
        if (integerGridMap) {
            runGreedyIntegerRounding();
        }
        runStiffeningLoop();

        int totalCorners = faceCount * CORNERS_PER_FACE;
        uCorner = new float[totalCorners];
        vCorner = new float[totalCorners];
        writeChartVerticesFromSolution(totalCorners);
        cutTranslationS = new float[edgeCount];
        cutTranslationT = new float[edgeCount];
        writeCutTranslationsFromSolution();
        // Re-evaluate injectivity from the float-cast outputs (cheap consistency
        // check).
        boolean inj = true;
        for (int af = 0; af < faceCount; af++) {
            int o = af * CORNERS_PER_FACE;
            float u0 = uCorner[o], v0p = vCorner[o];
            float u1 = uCorner[o + 1], v1 = vCorner[o + 1];
            float u2 = uCorner[o + 2], v2 = vCorner[o + 2];
            float sa = HALF * ((u1 - u0) * (v2 - v0p) - (u2 - u0) * (v1 - v0p));
            if (sa <= 0f) {
                inj = false;
                break;
            }
        }
        this.injective = inj && this.injective;
        if (exactSeams) {
            new SeamlessProjector(this).project();
        }
        this.metrics = new ParameterizationMetrics(this, mesh);
        return this.metrics;
    }

    /**
     * BZK09 §2 / §5 greedy rounding. Repeatedly: among unpinned integer DOFs, pick
     * the one whose current solution value is closest to its nearest integer, snap
     * it to that integer, re-solve. Stop when no integer DOF is unpinned.
     */
    private void runGreedyIntegerRounding() {
        boolean diag = DIAG_TRUE.equals(System.getProperty(DIAG_PROP));
        int totalToRound = 0;
        for (int i = 0; i < dofCount; i++)
            if (dofIsInteger[i])
                totalToRound++;
        if (diag) {
            System.err.printf("[seamlessParam] greedy rounding: %d integer DOFs%n", totalToRound);
        }
        if (diag) {
            // Distribution of integer-DOF values pre-rounding.
            double maxAbs = 0.0;
            int nearZero = 0;
            for (int i = 0; i < dofCount; i++) {
                if (!dofIsInteger[i])
                    continue;
                double v = Math.abs(solution[i]);
                if (v > maxAbs)
                    maxAbs = v;
                if (v < HALF_D)
                    nearZero++;
            }
            System.err.printf("[seamlessParam] pre-round int DOF distribution: max|x|=%.3f  |x|<0.5: %d/%d%n",
                    maxAbs, nearZero, totalToRound);
        }
        int rounded = 0;
        while (true) {
            int bestIdx = -1;
            double bestDist = Double.POSITIVE_INFINITY;
            double bestValue = 0;
            for (int i = 0; i < dofCount; i++) {
                if (!dofIsInteger[i] || dofPinned[i])
                    continue;
                double x = solution[i];
                double rounded01 = Math.rint(x);
                double dist = Math.abs(x - rounded01);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestIdx = i;
                    bestValue = rounded01;
                }
            }
            if (bestIdx < 0)
                break;
            dofPinned[bestIdx] = true;
            dofPinnedValue[bestIdx] = bestValue;
            rounded++;
            solveOnce();
            if (diag && (rounded % DIAG_LOG_EVERY == 0 || rounded == totalToRound)) {
                System.err.printf("[seamlessParam] rounded %d/%d  lastDist=%.4f%n",
                        rounded, totalToRound, bestDist);
            }
        }
    }

    /**
     * Per-corner u accessor.
     *
     * @param faceId    mesh face id
     * @param cornerIdx corner index in {@code [0, 3)}
     * @return u-coordinate at the given corner
     */
    public float u(int faceId, int cornerIdx) {
        int activeFace = crossField.faceIdToActive.get(faceId);
        return uCorner[activeFace * CORNERS_PER_FACE + cornerIdx];
    }

    /**
     * Per-corner v accessor.
     *
     * @param faceId    mesh face id
     * @param cornerIdx corner index in {@code [0, 3)}
     * @return v-coordinate at the given corner
     */
    public float v(int faceId, int cornerIdx) {
        int activeFace = crossField.faceIdToActive.get(faceId);
        return vCorner[activeFace * CORNERS_PER_FACE + cornerIdx];
    }

    // =====================================================================
    // C4 prep. per-face shape gradients + branch-rotated cross targets.
    // =====================================================================

    private void precomputePerFaceGeometryAndTargets() {
        faceArea = new double[faceCount];
        faceShapeB = new double[faceCount * CORNERS_PER_FACE];
        faceShapeC = new double[faceCount * CORNERS_PER_FACE];
        faceUtxLocal = new double[faceCount];
        faceUtyLocal = new double[faceCount];
        faceVtxLocal = new double[faceCount];
        faceVtyLocal = new double[faceCount];

        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        Vector3f rel = new Vector3f();

        for (int af = 0; af < faceCount; af++) {
            int fId = mesh.faceIdAt(af);
            int v0 = mesh.faceVertexAt(fId, 0);
            int v1 = mesh.faceVertexAt(fId, 1);
            int v2 = mesh.faceVertexAt(fId, 2);
            mesh.vertexPosition(v0, p0);
            mesh.vertexPosition(v1, p1);
            mesh.vertexPosition(v2, p2);

            Vector3f xAxis = crossField.faceX[af];
            Vector3f yAxis = crossField.faceY[af];

            // Project (p_i - p_0) into local frame.
            double x0 = 0, y0 = 0;
            rel.set(p1).sub(p0);
            double x1 = rel.dot(xAxis), y1 = rel.dot(yAxis);
            rel.set(p2).sub(p0);
            double x2 = rel.dot(xAxis), y2 = rel.dot(yAxis);

            double twoArea = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
            if (Math.abs(twoArea) < DEGENERATE_AREA_EPS) {
                // Degenerate triangle: skip.
                faceArea[af] = 0.0;
                continue;
            }
            faceArea[af] = HALF_D * Math.abs(twoArea);

            int o = af * CORNERS_PER_FACE;
            // ∇φ = (Σ b_i φ_i, Σ c_i φ_i) for linear φ on a 2D triangle.
            faceShapeB[o] = (y1 - y2) / twoArea;
            faceShapeB[o + 1] = (y2 - y0) / twoArea;
            faceShapeB[o + 2] = (y0 - y1) / twoArea;
            faceShapeC[o] = (x2 - x1) / twoArea;
            faceShapeC[o + 1] = (x0 - x2) / twoArea;
            faceShapeC[o + 2] = (x1 - x0) / twoArea;

            // Branch-rotated cross targets.
            double theta = crossField.theta[af] + cutGraph.faceBranch[af] * HALF_PI;
            double cu = Math.cos(theta), su = Math.sin(theta);
            faceUtxLocal[af] = cu;
            faceUtyLocal[af] = su;
            faceVtxLocal[af] = -su;
            faceVtyLocal[af] = cu;
        }
    }

    /**
     * BZK09 §5.4 weight update — verbatim from the paper.
     *
     * <p>
     * For every triangle T:
     * <ol>
     * <li>Compute the parametric Jacobian
     * {@code J = [[∂u/∂x, ∂u/∂y], [∂v/∂x, ∂v/∂y]]} in the local face frame, its
     * singular values σ₁ ≥ σ₂, and the orientation sign {@code τ = sign(det J)}.
     * <li>Distortion {@code λ(T) = |τ·σ₁/h − 1| + |τ·σ₂/h − 1|} (paper Eq. between
     * §5.4 and §6).
     * <li>{@code Δλ(T)} = uniform Laplacian of λ on the dual mesh (i.e. mean of λ
     * over T's face-neighbours minus λ(T)).
     * <li>{@code w(T) ← w(T) + min(c · |Δλ|, d)} with paper-prescribed {@code c=1,
     *       d=5}. Then a couple of uniform smoothing passes over {@code w}.
     * </ol>
     *
     * <p>
     * Using {@code Δλ} (not λ itself) is the paper's key insight: a globally
     * uniform stretch is OK and shouldn't trigger reweighting; only LOCAL spikes in
     * distortion (boundaries of high-distortion regions, isolated flipped faces)
     * get bumped. This is what stops the IRLS oscillations the naive "bump every
     * flipped face" version exhibits.
     */
    private void runStiffeningLoop() {
        boolean diag = DIAG_TRUE.equals(System.getProperty(DIAG_PROP));
        for (int iter = 0; iter <= maxStiffeningIterations; iter++) {
            stiffeningIterations = iter;
            long t0 = System.nanoTime();
            solveOnce();
            long t1 = System.nanoTime();
            int flipped = countFlippedTrianglesFromSolution();
            if (diag) {
                System.err.printf("[seamlessParam] iter=%d flipped=%d  solve=%.2fs%n",
                        iter, flipped, (t1 - t0) / NANOS_PER_SEC);
            }
            if (flipped == 0) {
                injective = true;
                return;
            }
            if (iter == maxStiffeningIterations) {
                injective = false;
                return;
            }
            // Step 1: BZK09 §5.4 Δλ-Laplacian update — penalises *boundaries* of
            // high-distortion regions, not the interiors. Damps the IRLS so it
            // doesn't oscillate.
            double[] perFaceDistortion = new double[faceCount];
            for (int activeFace = 0; activeFace < faceCount; activeFace++) {
                if (faceArea[activeFace] <= 0) {
                    perFaceDistortion[activeFace] = 0.0;
                    continue;
                }
                int faceCornerBase = activeFace * CORNERS_PER_FACE;
                int chartVertex0 = cutGraph.cornerToChartVertex[faceCornerBase];
                int chartVertex1 = cutGraph.cornerToChartVertex[faceCornerBase + 1];
                int chartVertex2 = cutGraph.cornerToChartVertex[faceCornerBase + 2];

                double u0 = evaluateChartComponent(chartVertex0, 0);
                double v0 = evaluateChartComponent(chartVertex0, 1);
                double u1 = evaluateChartComponent(chartVertex1, 0);
                double v1 = evaluateChartComponent(chartVertex1, 1);
                double u2 = evaluateChartComponent(chartVertex2, 0);
                double v2 = evaluateChartComponent(chartVertex2, 1);

                double shapeGradX0 = faceShapeB[faceCornerBase];
                double shapeGradX1 = faceShapeB[faceCornerBase + 1];
                double shapeGradX2 = faceShapeB[faceCornerBase + 2];
                double shapeGradY0 = faceShapeC[faceCornerBase];
                double shapeGradY1 = faceShapeC[faceCornerBase + 1];
                double shapeGradY2 = faceShapeC[faceCornerBase + 2];

                // Jacobian of the (u, v) map in face-local (x, y) coords.
                double duDx = shapeGradX0 * u0 + shapeGradX1 * u1 + shapeGradX2 * u2;
                double duDy = shapeGradY0 * u0 + shapeGradY1 * u1 + shapeGradY2 * u2;
                double dvDx = shapeGradX0 * v0 + shapeGradX1 * v1 + shapeGradX2 * v2;
                double dvDy = shapeGradY0 * v0 + shapeGradY1 * v1 + shapeGradY2 * v2;

                double jacobianDet = duDx * dvDy - duDy * dvDx;
                double frobeniusSquared = duDx * duDx + duDy * duDy + dvDx * dvDx + dvDy * dvDy;

                // Singular values of a 2×2 matrix from the relations
                // σ₁² + σ₂² = ‖J‖²_F and σ₁²·σ₂² = det(J)².
                // Solving the quadratic in σ² gives the closed-form below.
                double svdDiscriminant = Math.max(0.0,
                        frobeniusSquared * frobeniusSquared - SVD_DET_FACTOR * jacobianDet * jacobianDet);
                double svdDiscriminantSqrt = Math.sqrt(svdDiscriminant);
                double sigma1 = Math.sqrt(HALF_D * (frobeniusSquared + svdDiscriminantSqrt));
                double sigma2 = Math.sqrt(HALF_D * Math.max(0.0, frobeniusSquared - svdDiscriminantSqrt));
                double orientationSign = jacobianDet >= 0 ? 1.0 : -1.0;

                perFaceDistortion[activeFace] = Math.abs(orientationSign * sigma1 / h - 1.0)
                        + Math.abs(orientationSign * sigma2 / h - 1.0);
            }

            // Δλ on the dual mesh: mean of neighbours' distortion minus own distortion.
            double[] perFaceDistortionLaplacian = new double[faceCount];
            for (int activeFace = 0; activeFace < faceCount; activeFace++) {
                int faceId = mesh.faceIdAt(activeFace);
                double neighbourDistortionSum = 0.0;
                int neighbourCount = 0;
                for (int corner = 0; corner < CORNERS_PER_FACE; corner++) {
                    int edgeId = mesh.faceEdgeAt(faceId, corner);
                    int activeEdge = crossField.edgeIdToActive.get(edgeId);
                    int neighbourFaceA = edgeFaceA[activeEdge];
                    int neighbourFaceB = edgeFaceB[activeEdge];
                    int otherFace = (neighbourFaceA == activeFace) ? neighbourFaceB : neighbourFaceA;
                    if (otherFace < 0) {
                        continue;
                    }
                    neighbourDistortionSum += perFaceDistortion[otherFace];
                    neighbourCount++;
                }
                perFaceDistortionLaplacian[activeFace] = (neighbourCount == 0)
                        ? 0.0
                        : (neighbourDistortionSum / neighbourCount - perFaceDistortion[activeFace]);
            }

            // Apply the paper's additive bump: w(T) += min(c · |Δλ|, d).
            for (int activeFace = 0; activeFace < faceCount; activeFace++) {
                double weightBump = Math.min(
                        stiffeningC * Math.abs(perFaceDistortionLaplacian[activeFace]),
                        stiffeningD);
                faceWeight[activeFace] += weightBump;
            }

            // Step 2: aggressive multiplicative kick on actually-flipped faces.
            // The paper's c=1, d=5 grow weights too slowly to fix orientation
            // inversions; without this kick a flipped face never accumulates
            // enough weight to flip back. Capped by {@link #stiffeningWeightCap}.
            for (int activeFace = 0; activeFace < faceCount; activeFace++) {
                if (faceArea[activeFace] <= 0) {
                    continue;
                }
                int faceCornerBase = activeFace * CORNERS_PER_FACE;
                int chartVertex0 = cutGraph.cornerToChartVertex[faceCornerBase];
                int chartVertex1 = cutGraph.cornerToChartVertex[faceCornerBase + 1];
                int chartVertex2 = cutGraph.cornerToChartVertex[faceCornerBase + 2];
                double u0 = evaluateChartComponent(chartVertex0, 0);
                double v0 = evaluateChartComponent(chartVertex0, 1);
                double u1 = evaluateChartComponent(chartVertex1, 0);
                double v1 = evaluateChartComponent(chartVertex1, 1);
                double u2 = evaluateChartComponent(chartVertex2, 0);
                double v2 = evaluateChartComponent(chartVertex2, 1);

                double uvSignedArea = HALF_D * ((u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0));
                if (uvSignedArea <= 0.0) {
                    faceWeight[activeFace] = Math.min(
                            faceWeight[activeFace] * stiffeningGrowth, stiffeningWeightCap);
                } else {
                    faceWeight[activeFace] = Math.min(faceWeight[activeFace], stiffeningWeightCap);
                }
            }

            // Smoothing passes: replace each face's weight with the average of itself
            // and its face-neighbours' weights. Paper recommends a couple of passes.
            if (stiffeningSmoothPasses <= 0) {
                return;
            }
            double[] smoothedWeights = new double[faceCount];
            for (int pass = 0; pass < stiffeningSmoothPasses; pass++) {
                for (int activeFace = 0; activeFace < faceCount; activeFace++) {
                    int faceId = mesh.faceIdAt(activeFace);
                    double weightSum = faceWeight[activeFace];
                    int neighbourCount = 1;
                    for (int corner = 0; corner < CORNERS_PER_FACE; corner++) {
                        int edgeId = mesh.faceEdgeAt(faceId, corner);
                        int activeEdge = crossField.edgeIdToActive.get(edgeId);
                        int neighbourFaceA = edgeFaceA[activeEdge];
                        int neighbourFaceB = edgeFaceB[activeEdge];
                        int otherFace = (neighbourFaceA == activeFace) ? neighbourFaceB : neighbourFaceA;
                        if (otherFace < 0) {
                            continue;
                        }
                        weightSum += faceWeight[otherFace];
                        neighbourCount++;
                    }
                    smoothedWeights[activeFace] = weightSum / neighbourCount;
                }
                System.arraycopy(smoothedWeights, 0, faceWeight, 0, faceCount);
            }
        }
    }

    /**
     * Accumulate symmetric SPD entries into a diagonal vector + upper-triangle.
     */
    private void solveOnce() {
        double[] systemDiagonal = new double[dofCount];
        HashMap<Long, Double> systemUpperTriangle = new HashMap<>(dofCount * AVG_NONZEROS_PER_ROW);
        double[] systemRhs = new double[dofCount];

        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            double area = faceArea[activeFace];
            if (area <= 0) {
                continue;
            }
            double faceWeightedArea = faceWeight[activeFace] * area;
            int faceCornerBase = activeFace * CORNERS_PER_FACE;

            double[] shapeGradX = new double[CORNERS_PER_FACE];
            double[] shapeGradY = new double[CORNERS_PER_FACE];
            int[] cornerChartVertex = new int[CORNERS_PER_FACE];
            for (int corner = 0; corner < CORNERS_PER_FACE; corner++) {
                shapeGradX[corner] = faceShapeB[faceCornerBase + corner];
                shapeGradY[corner] = faceShapeC[faceCornerBase + corner];
                cornerChartVertex[corner] = cutGraph.cornerToChartVertex[faceCornerBase + corner];
            }
            double targetUx = faceUtxLocal[activeFace], targetUy = faceUtyLocal[activeFace];
            double targetVx = faceVtxLocal[activeFace], targetVy = faceVtyLocal[activeFace];
            double edgeLengthSquared = (double) h * h;

            // Full 3×3 corner iteration + outer-product expansion through
            // the per-chart-vertex final-DOF map. Off-diagonal double-count
            // from the (i, j) + (j, i) symmetry is corrected by the
            // halve-upper pass below.
            for (int cornerI = 0; cornerI < CORNERS_PER_FACE; cornerI++) {
                for (int cornerJ = 0; cornerJ < CORNERS_PER_FACE; cornerJ++) {
                    double stiffness = faceWeightedArea * edgeLengthSquared
                            * (shapeGradX[cornerI] * shapeGradX[cornerJ]
                                    + shapeGradY[cornerI] * shapeGradY[cornerJ]);
                    if (stiffness == 0.0) {
                        continue;
                    }
                    accumulateOuterProduct(systemDiagonal, systemUpperTriangle,
                            chartVertexFinalDofs[cornerChartVertex[cornerI]][0],
                            chartVertexFinalCoefs[cornerChartVertex[cornerI]][0],
                            chartVertexFinalDofs[cornerChartVertex[cornerJ]][0],
                            chartVertexFinalCoefs[cornerChartVertex[cornerJ]][0],
                            stiffness);
                    accumulateOuterProduct(systemDiagonal, systemUpperTriangle,
                            chartVertexFinalDofs[cornerChartVertex[cornerI]][1],
                            chartVertexFinalCoefs[cornerChartVertex[cornerI]][1],
                            chartVertexFinalDofs[cornerChartVertex[cornerJ]][1],
                            chartVertexFinalCoefs[cornerChartVertex[cornerJ]][1],
                            stiffness);
                }
            }

            for (int corner = 0; corner < CORNERS_PER_FACE; corner++) {
                double uRhs = faceWeightedArea * h
                        * (shapeGradX[corner] * targetUx + shapeGradY[corner] * targetUy);
                double vRhs = faceWeightedArea * h
                        * (shapeGradX[corner] * targetVx + shapeGradY[corner] * targetVy);
                int[] uExpDofs = chartVertexFinalDofs[cornerChartVertex[corner]][0];
                double[] uExpCoefs = chartVertexFinalCoefs[cornerChartVertex[corner]][0];
                for (int i = 0; i < uExpDofs.length; i++) {
                    systemRhs[uExpDofs[i]] += uRhs * uExpCoefs[i];
                }
                int[] vExpDofs = chartVertexFinalDofs[cornerChartVertex[corner]][1];
                double[] vExpCoefs = chartVertexFinalCoefs[cornerChartVertex[corner]][1];
                for (int i = 0; i < vExpDofs.length; i++) {
                    systemRhs[vExpDofs[i]] += vRhs * vExpCoefs[i];
                }
            }
        }

        boolean[] visitedFace = new boolean[faceCount];
        ArrayList<Integer> picks = new ArrayList<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int seed = 0; seed < faceCount; seed++) {
            if (visitedFace[seed])
                continue;
            visitedFace[seed] = true;
            picks.add(cutGraph.cornerToChartVertex[seed * CORNERS_PER_FACE]);
            queue.add(seed);
            while (!queue.isEmpty()) {
                int af = queue.poll();
                int fId = mesh.faceIdAt(af);
                for (int c = 0; c < CORNERS_PER_FACE; c++) {
                    int eId = mesh.faceEdgeAt(fId, c);
                    int ae = crossField.edgeIdToActive.get(eId);
                    if (cutGraph.isCutEdge[ae])
                        continue;
                    int afA = edgeFaceA[ae];
                    int afB = edgeFaceB[ae];
                    if (afA < 0 || afB < 0)
                        continue;
                    int afOther = (afA == af) ? afB : afA;
                    if (visitedFace[afOther])
                        continue;
                    visitedFace[afOther] = true;
                    queue.add(afOther);
                }
            }
        }

        // Half off-diagonal upper to correct the full-3×3 + outer-product
        // double-count from (i, j) and (j, i) both routing to upper(min, max).
        systemUpperTriangle.replaceAll((key, value) -> value * HALF_D);

        // Gauge pins go in AFTER the halve so each chart-vertex pin
        // contributes exactly its outer product μ · vᵀv.
        for (int chartVertex : picks) {
            addOuterSparse(systemDiagonal, systemUpperTriangle, gaugePinWeight,
                    chartVertexFinalDofs[chartVertex][0],
                    chartVertexFinalCoefs[chartVertex][0]);
            addOuterSparse(systemDiagonal, systemUpperTriangle, gaugePinWeight,
                    chartVertexFinalDofs[chartVertex][1],
                    chartVertexFinalCoefs[chartVertex][1]);
        }

        if (dofPinned != null) {
            for (int dofIdx = 0; dofIdx < dofCount; dofIdx++) {
                if (!dofPinned[dofIdx]) {
                    continue;
                }
                systemDiagonal[dofIdx] += integerPinWeight;
                systemRhs[dofIdx] += integerPinWeight * dofPinnedValue[dofIdx];
            }
        }

        boolean[] fixed = new boolean[dofCount];
        double[] start = new double[dofCount];
        NormalMatrix matrix = new NormalMatrix(systemDiagonal, systemUpperTriangle, systemRhs);
        solution = DirectSolver.solve(matrix, start, fixed);
    }

    /**
     * Accumulates a value into the diagonal and upper triangular part of the
     * system.
     * 
     * @param diag  the diagonal of the system
     * @param upper the upper triangular part of the system
     * @param row   the row index
     * @param col   the column index
     * @param v     the value
     */
    private static void accumulate(double[] diag, HashMap<Long, Double> upper,
            int row, int col, double v) {
        if (v == 0.0)
            return;
        if (row == col) {
            diag[row] += v;
            return;
        }
        int r = Math.min(row, col), c = Math.max(row, col);
        long key = ((long) r << SHIFT_32) | (c & MASK_32);
        upper.merge(key, v, Double::sum);
    }

    /**
     * Adds μ · a aᵀ to the symmetric system.
     *
     * @param diag  the diagonal of the system
     * @param upper the upper triangular part of the system
     * @param mu    the weight
     * @param cols  the columns of the system
     * @param vals  the values of the system
     */
    private static void addOuterSparse(double[] diag, HashMap<Long, Double> upper,
            double mu, int[] cols, double[] vals) {
        for (int i = 0; i < cols.length; i++) {
            for (int j = i; j < cols.length; j++) {
                double k = mu * vals[i] * vals[j];
                if (k == 0.0)
                    continue;
                accumulate(diag, upper, cols[i], cols[j], k);
            }
        }
    }

    /**
     * Accumulate {@code stiffness · v_a · v_b} from outer product of two
     * expansion vectors using full {@code (a, b)} iteration. Off-diagonal
     * upper entries are double-counted by the symmetric (i, j) + (j, i)
     * iteration in the caller; the post-assembly halve-upper pass in
     * {@link #solveOnce} corrects that.
     *
     * @param diag      SPD diagonal accumulator
     * @param upper     SPD upper-triangle accumulator
     * @param dofsA     final-DOF indices of the first vector
     * @param coefsA    coefficients of the first vector
     * @param dofsB     final-DOF indices of the second vector
     * @param coefsB    coefficients of the second vector
     * @param stiffness scale factor
     */
    private static void accumulateOuterProduct(double[] diag, HashMap<Long, Double> upper,
            int[] dofsA, double[] coefsA, int[] dofsB, double[] coefsB, double stiffness) {
        for (int a = 0; a < dofsA.length; a++) {
            for (int b = 0; b < dofsB.length; b++) {
                accumulate(diag, upper, dofsA[a], dofsB[b], stiffness * coefsA[a] * coefsB[b]);
            }
        }
    }

    /**
     * Pre-leftover-elimination raw-DOF expansion of {@code chartVertex}'s
     * {@code component}. Primary chart vertices expand to a single raw DOF
     * (their u or v slot); secondary chart vertices expand to
     * {@code (partnerU, partnerV, s_e or t_e)} with rotation coefficients.
     *
     * @param chartVertex chart vertex index
     * @param component   0 for u, 1 for v
     * @return raw-DOF indices array
     */
    private int[] rawExpansionDofs(int chartVertex, int component) {
        if (cutGraph.chartVertexIsPrimary[chartVertex]) {
            return new int[] { 2 * cutGraph.primaryChartIndex[chartVertex] + component };
        }
        int activeEdge = cutGraph.secondaryEdge[chartVertex];
        int partner = cutGraph.secondaryPartner[chartVertex];
        int partnerBaseDof = 2 * cutGraph.primaryChartIndex[partner];
        int translationDof = (component == 0) ? cutEdgeSDof[activeEdge] : cutEdgeTDof[activeEdge];
        return new int[] { partnerBaseDof, partnerBaseDof + 1, translationDof };
    }

    /**
     * Coefficients matching {@link #rawExpansionDofs}.
     *
     * @param chartVertex chart vertex index
     * @param component   0 for u, 1 for v
     * @return coefficient array
     */
    private double[] rawExpansionCoefs(int chartVertex, int component) {
        if (cutGraph.chartVertexIsPrimary[chartVertex]) {
            return new double[] { 1.0 };
        }
        int activeEdge = cutGraph.secondaryEdge[chartVertex];
        int rotation = cutGraph.cutRotation[activeEdge];
        int cos = ExactArithmetic.integerCosine(rotation);
        int sin = ExactArithmetic.integerSine(rotation);
        if (component == 0) {
            return new double[] { cos, -sin, 1.0 };
        }
        return new double[] { sin, cos, 1.0 };
    }

    /**
     * Scatter {@code outerCoef · (raw-DOF expansion of chartVertex's
     * component)} into {@code accumulator}.
     *
     * @param accumulator raw-DOF → coefficient accumulator
     * @param chartVertex chart vertex whose component to expand
     * @param component   0 for u, 1 for v
     * @param outerCoef   multiplier applied to every term in the expansion
     */
    private void addRawExpansionTo(HashMap<Integer, Double> accumulator,
            int chartVertex, int component, double outerCoef) {
        if (outerCoef == 0.0) {
            return;
        }
        int[] dofs = rawExpansionDofs(chartVertex, component);
        double[] coefs = rawExpansionCoefs(chartVertex, component);
        for (int i = 0; i < dofs.length; i++) {
            accumulator.merge(dofs[i], outerCoef * coefs[i], Double::sum);
        }
    }

    /**
     * Assign per-cut-edge {@code (s, t)} translation raw DOFs in the suffix
     * of the raw-DOF vector after the primary chart-vertex (u, v): the
     * {@code s} block starts at {@code 2*primaryChartCount} for
     * {@code interiorCutEdgeCount} entries, then the {@code t} block.
     */
    private void assignCutEdgeTranslationDofs() {
        cutEdgeSDof = new int[edgeCount];
        cutEdgeTDof = new int[edgeCount];
        Arrays.fill(cutEdgeSDof, -1);
        Arrays.fill(cutEdgeTDof, -1);
        int sBase = 2 * cutGraph.primaryChartCount;
        int tBase = sBase + cutGraph.interiorCutEdgeCount;
        for (int activeEdge = 0; activeEdge < edgeCount; activeEdge++) {
            int dense = cutGraph.cutEdgeDenseIdx[activeEdge];
            if (dense < 0) {
                continue;
            }
            cutEdgeSDof[activeEdge] = sBase + dense;
            cutEdgeTDof[activeEdge] = tBase + dense;
        }
    }

    /**
     * Reduce the leftover-constraint system {@code L · x = 0} to a set of
     * substitution rules, one per pivoted raw DOF, by sparse Gauss-Jordan
     * elimination with partial pivoting on the largest-magnitude entry. Each
     * leftover record emits two scalar rows
     * ({@code u_chartB − R · u_chartA − s_e = 0} and the v variant) in the
     * raw-DOF column space; rank-deficient rows are silently dropped.
     * Populates {@link #leftoverPivotDofs}, {@link #leftoverPivotCoefs},
     * {@link #rawDofToFinal} and {@link #dofCount}.
     */
    private void reduceLeftoverConstraints() {
        leftoverPivotDofs = new int[rawDofCount][];
        leftoverPivotCoefs = new double[rawDofCount][];

        ArrayList<HashMap<Integer, Double>> rows = new ArrayList<>();
        for (int[] record : cutGraph.leftoverConstraints) {
            int activeEdge = record[0];
            int chartA = record[1];
            int chartB = record[2];
            int rotation = cutGraph.cutRotation[activeEdge];
            int cos = ExactArithmetic.integerCosine(rotation);
            int sin = ExactArithmetic.integerSine(rotation);
            int sDof = cutEdgeSDof[activeEdge];
            int tDof = cutEdgeTDof[activeEdge];
            HashMap<Integer, Double> rowU = new HashMap<>();
            addRawExpansionTo(rowU, chartB, 0, 1.0);
            addRawExpansionTo(rowU, chartA, 0, -cos);
            addRawExpansionTo(rowU, chartA, 1, sin);
            rowU.merge(sDof, -1.0, Double::sum);
            rows.add(rowU);
            HashMap<Integer, Double> rowV = new HashMap<>();
            addRawExpansionTo(rowV, chartB, 1, 1.0);
            addRawExpansionTo(rowV, chartA, 0, -sin);
            addRawExpansionTo(rowV, chartA, 1, -cos);
            rowV.merge(tDof, -1.0, Double::sum);
            rows.add(rowV);
        }

        int totalRows = rows.size();
        boolean[] rowPivoted = new boolean[totalRows];
        for (int processed = 0; processed < totalRows; processed++) {
            int bestRow = -1;
            int bestPivot = -1;
            double bestMagnitude = 0.0;
            for (int rowIdx = 0; rowIdx < totalRows; rowIdx++) {
                if (rowPivoted[rowIdx]) {
                    continue;
                }
                for (Map.Entry<Integer, Double> entry : rows.get(rowIdx).entrySet()) {
                    double magnitude = Math.abs(entry.getValue());
                    if (magnitude > bestMagnitude) {
                        bestMagnitude = magnitude;
                        bestRow = rowIdx;
                        bestPivot = entry.getKey();
                    }
                }
            }
            if (bestRow < 0 || bestMagnitude < LEFTOVER_REDUCE_TOLERANCE) {
                break;
            }
            HashMap<Integer, Double> pivotRow = rows.get(bestRow);
            double pivotCoef = pivotRow.remove(bestPivot);
            int[] subsDofs = new int[pivotRow.size()];
            double[] subsCoefs = new double[pivotRow.size()];
            int idx = 0;
            for (Map.Entry<Integer, Double> entry : pivotRow.entrySet()) {
                subsDofs[idx] = entry.getKey();
                subsCoefs[idx] = -entry.getValue() / pivotCoef;
                idx++;
            }
            leftoverPivotDofs[bestPivot] = subsDofs;
            leftoverPivotCoefs[bestPivot] = subsCoefs;
            rowPivoted[bestRow] = true;
            for (int rowIdx = 0; rowIdx < totalRows; rowIdx++) {
                if (rowIdx == bestRow) {
                    continue;
                }
                HashMap<Integer, Double> otherRow = rows.get(rowIdx);
                Double otherCoef = otherRow.remove(bestPivot);
                if (otherCoef == null) {
                    continue;
                }
                for (int i = 0; i < subsDofs.length; i++) {
                    otherRow.merge(subsDofs[i], otherCoef * subsCoefs[i], Double::sum);
                }
                otherRow.entrySet().removeIf(e -> Math.abs(e.getValue()) < LEFTOVER_REDUCE_TOLERANCE);
            }
        }

        rawDofToFinal = new int[rawDofCount];
        int nextFinal = 0;
        for (int rawDof = 0; rawDof < rawDofCount; rawDof++) {
            if (leftoverPivotDofs[rawDof] != null) {
                rawDofToFinal[rawDof] = -1;
            } else {
                rawDofToFinal[rawDof] = nextFinal++;
            }
        }
        this.dofCount = nextFinal;
    }

    /**
     * Bake the per-cut-edge substitution and leftover-row elimination into a
     * per-chart-vertex final-DOF expansion. After this the solver works in
     * the final DOF space directly: each chart vertex's u or v is
     * {@code Σ coef · solution[finalDof]}.
     */
    private void buildChartVertexFinalExpansions() {
        int chartVertexCount = cutGraph.chartVertexCount;
        chartVertexFinalDofs = new int[chartVertexCount][COMPONENTS_PER_CHART_VERTEX][];
        chartVertexFinalCoefs = new double[chartVertexCount][COMPONENTS_PER_CHART_VERTEX][];
        for (int chartVertex = 0; chartVertex < chartVertexCount; chartVertex++) {
            for (int component = 0; component < COMPONENTS_PER_CHART_VERTEX; component++) {
                HashMap<Integer, Double> finalAccum = new HashMap<>();
                int[] rawDofs = rawExpansionDofs(chartVertex, component);
                double[] rawCoefs = rawExpansionCoefs(chartVertex, component);
                for (int i = 0; i < rawDofs.length; i++) {
                    accumulateRawDofAsFinal(finalAccum, rawDofs[i], rawCoefs[i]);
                }
                int size = finalAccum.size();
                int[] dofs = new int[size];
                double[] coefs = new double[size];
                int idx = 0;
                for (Map.Entry<Integer, Double> entry : finalAccum.entrySet()) {
                    dofs[idx] = entry.getKey();
                    coefs[idx] = entry.getValue();
                    idx++;
                }
                chartVertexFinalDofs[chartVertex][component] = dofs;
                chartVertexFinalCoefs[chartVertex][component] = coefs;
            }
        }
    }

    /**
     * Fold one raw-DOF contribution into a final-DOF accumulator: a non-pivot
     * raw DOF maps directly to its dense final index; a pivot raw DOF
     * expands recursively through {@link #leftoverPivotDofs} /
     * {@link #leftoverPivotCoefs}.
     *
     * @param finalAccum final-DOF → coefficient accumulator
     * @param rawDof     the raw-DOF index whose contribution to fold in
     * @param coef       coefficient to multiply into the expansion
     */
    private void accumulateRawDofAsFinal(HashMap<Integer, Double> finalAccum,
            int rawDof, double coef) {
        if (leftoverPivotDofs[rawDof] != null) {
            int[] subsDofs = leftoverPivotDofs[rawDof];
            double[] subsCoefs = leftoverPivotCoefs[rawDof];
            for (int i = 0; i < subsDofs.length; i++) {
                accumulateRawDofAsFinal(finalAccum, subsDofs[i], coef * subsCoefs[i]);
            }
            return;
        }
        int finalDof = rawDofToFinal[rawDof];
        finalAccum.merge(finalDof, coef, Double::sum);
    }

    /**
     * Evaluate one component of a chart vertex in the current
     * {@link #solution} by summing its final-DOF expansion.
     *
     * @param chartVertex chart vertex index
     * @param component   0 for u, 1 for v
     * @return the chart vertex's component value
     */
    private double evaluateChartComponent(int chartVertex, int component) {
        int[] dofs = chartVertexFinalDofs[chartVertex][component];
        double[] coefs = chartVertexFinalCoefs[chartVertex][component];
        double value = 0.0;
        for (int i = 0; i < dofs.length; i++) {
            value += coefs[i] * solution[dofs[i]];
        }
        return value;
    }

    /**
     * Evaluate the value of a raw DOF in the current {@link #solution}: a
     * non-pivot raw DOF reads its dense final entry; a pivot raw DOF
     * evaluates recursively through {@link #leftoverPivotDofs} /
     * {@link #leftoverPivotCoefs}.
     *
     * @param rawDof a raw-DOF index in {@code [0, rawDofCount)}
     * @return the value of {@code rawDof} implied by the current solution
     */
    private double evaluateRawDof(int rawDof) {
        if (leftoverPivotDofs[rawDof] != null) {
            int[] subsDofs = leftoverPivotDofs[rawDof];
            double[] subsCoefs = leftoverPivotCoefs[rawDof];
            double value = 0.0;
            for (int i = 0; i < subsDofs.length; i++) {
                value += subsCoefs[i] * evaluateRawDof(subsDofs[i]);
            }
            return value;
        }
        return solution[rawDofToFinal[rawDof]];
    }

    /**
     * Materialise per-corner {@code uCorner} / {@code vCorner} from the
     * current {@link #solution} via each chart vertex's final-DOF expansion.
     *
     * @param totalCorners {@code 3 * faceCount}
     */
    private void writeChartVerticesFromSolution(int totalCorners) {
        for (int corner = 0; corner < totalCorners; corner++) {
            int chartVertex = cutGraph.cornerToChartVertex[corner];
            uCorner[corner] = (float) evaluateChartComponent(chartVertex, 0);
            vCorner[corner] = (float) evaluateChartComponent(chartVertex, 1);
        }
    }

    /**
     * Back-fill {@code cutTranslationS} / {@code cutTranslationT} from the
     * raw {@code (s, t)} DOFs, evaluating through the leftover-row
     * substitution if those DOFs were pivot-eliminated.
     */
    private void writeCutTranslationsFromSolution() {
        for (int activeEdge = 0; activeEdge < edgeCount; activeEdge++) {
            if (cutEdgeSDof[activeEdge] < 0) {
                continue;
            }
            cutTranslationS[activeEdge] = (float) evaluateRawDof(cutEdgeSDof[activeEdge]);
            cutTranslationT[activeEdge] = (float) evaluateRawDof(cutEdgeTDof[activeEdge]);
        }
    }

    /**
     * Mark which final DOFs must round to integers in {@link #integerGridMap}
     * mode: every per-cut-edge {@code (s, t)} pair, and the (u, v) of every
     * primary chart vertex that touches a singularity mesh vertex.
     * Pivot-eliminated raw DOFs are skipped — their values are determined by
     * the non-eliminated free DOFs.
     */
    private void markIntegerDofs() {
        if (!integerGridMap) {
            return;
        }
        for (int activeEdge = 0; activeEdge < edgeCount; activeEdge++) {
            if (cutEdgeSDof[activeEdge] < 0) {
                continue;
            }
            markRawDofAsInteger(cutEdgeSDof[activeEdge]);
            markRawDofAsInteger(cutEdgeTDof[activeEdge]);
        }
        Set<Integer> singularVertexIds = new HashSet<>();
        for (Singularity s : crossField.singularities) {
            singularVertexIds.add(s.vertexId());
        }
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            for (int corner = 0; corner < CORNERS_PER_FACE; corner++) {
                if (!singularVertexIds.contains(mesh.faceVertexAt(faceId, corner))) {
                    continue;
                }
                int chartVertex = cutGraph.cornerToChartVertex[activeFace * CORNERS_PER_FACE + corner];
                if (!cutGraph.chartVertexIsPrimary[chartVertex]) {
                    continue;
                }
                int primaryIdx = cutGraph.primaryChartIndex[chartVertex];
                markRawDofAsInteger(2 * primaryIdx);
                markRawDofAsInteger(2 * primaryIdx + 1);
            }
        }
    }

    /**
     * Mark the final DOF corresponding to {@code rawDof} as integer-rounded.
     * No-op if {@code rawDof} was pivot-eliminated by leftover-row reduction.
     *
     * @param rawDof a raw-DOF index
     */
    private void markRawDofAsInteger(int rawDof) {
        if (leftoverPivotDofs[rawDof] != null) {
            return;
        }
        dofIsInteger[rawDofToFinal[rawDof]] = true;
    }

    /**
     * Counts the number of flipped triangles from the solution.
     * 
     * @return the number of flipped triangles
     */
    private int countFlippedTrianglesFromSolution() {
        int flipped = 0;
        for (int af = 0; af < faceCount; af++) {
            if (faceArea[af] <= 0)
                continue;
            int o = af * CORNERS_PER_FACE;
            int cv0 = cutGraph.cornerToChartVertex[o];
            int cv1 = cutGraph.cornerToChartVertex[o + 1];
            int cv2 = cutGraph.cornerToChartVertex[o + 2];
            double u0 = evaluateChartComponent(cv0, 0), v0 = evaluateChartComponent(cv0, 1);
            double u1 = evaluateChartComponent(cv1, 0), v1 = evaluateChartComponent(cv1, 1);
            double u2 = evaluateChartComponent(cv2, 0), v2 = evaluateChartComponent(cv2, 1);
            double sa = HALF_D * ((u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0));
            if (sa <= 0.0)
                flipped++;
        }
        return flipped;
    }

    /**
     * Signed UV area of a face; positive iff orientation is preserved.
     *
     * @param faceId mesh face id
     * @return signed UV-space triangle area
     */
    public float uvSignedArea(int faceId) {
        int activeFace = crossField.faceIdToActive.get(faceId);
        int o = activeFace * CORNERS_PER_FACE;
        float u0 = uCorner[o], v0 = vCorner[o];
        float u1 = uCorner[o + 1], v1 = vCorner[o + 1];
        float u2 = uCorner[o + 2], v2 = vCorner[o + 2];
        return HALF * ((u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0));
    }

    /**
     * Returns [u_p, v_p, u_q, v_q] for face's corners at vStart and vEnd.
     * 
     * @param faceId the face id
     * @param vStart the start vertex id
     * @param vEnd   the end vertex id
     * @return the corners coordinates
     */
    public float[] lookupCorners(int faceId, int vStart, int vEnd) {
        int cStart = -1, cEnd = -1;
        for (int c = 0; c < SeamlessParameterization.CORNERS_PER_FACE; c++) {
            int v = mesh.faceVertexAt(faceId, c);
            if (v == vStart)
                cStart = c;
            else if (v == vEnd)
                cEnd = c;
        }
        return new float[] {
                u(faceId, cStart), v(faceId, cStart),
                u(faceId, cEnd), v(faceId, cEnd),
        };
    }
}
