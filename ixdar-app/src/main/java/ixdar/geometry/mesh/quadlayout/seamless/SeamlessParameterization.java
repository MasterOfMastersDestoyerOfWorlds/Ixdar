package ixdar.geometry.mesh.quadlayout.seamless;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh.EdgeFaceIds;
import ixdar.geometry.mesh.quadlayout.NormalMatrix;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.seamless.exact.SeamlessProjector;
import ixdar.geometry.mesh.quadlayout.solver.AdaptiveSolver;
import ixdar.geometry.mesh.quadlayout.solver.DirectSolver;
import ixdar.geometry.mesh.quadlayout.solver.IncrementalCholeskySolver;

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
    private static final double DEGENERATE_AREA_EPSILON = 1.0e-30;
    private static final double SVD_DET_FACTOR = 4.0;
    private static final String DIAG_PROP = "seamlessParam.diag";
    private static final String DIAG_TRUE = "true";
    private static final int DIAG_LOG_EVERY = 10;
    /** Width in characters of the §5.4 stiffening progress bar. */
    private static final int PROGRESS_BAR_WIDTH = 30;

    public final HalfEdgeMesh mesh;
    public final CrossField crossField;

    /** Per-corner u, length {@code 3 * faceCount} (active-face order). */
    public double[] uCorner;
    /** Per-corner v, length {@code 3 * faceCount}. */
    public double[] vCorner;

    /**
     * Cut transition translation s<sub>e</sub>; only valid for INTERIOR cut edges.
     */
    public double[] cutTranslationS;
    /**
     * Cut transition translation t<sub>e</sub>; only valid for INTERIOR cut edges.
     */
    public double[] cutTranslationT;

    /** True iff every triangle has positive UV signed area. */
    public boolean injective;

    /** Hard cap on number of stiffening iterations. */
    public int maxStiffeningIterations = 20;

    /**
     * BZK09 §5.4 proportionality constant for the {@code |Δλ|} bump. Paper's value:
     * {@code c = 1}.
     */
    public double stiffeningC = 1.0;

    /**
     * BZK09 §5.4 hard cap on the per-iteration weight bump. Paper's value:
     * {@code d = 5}.
     */
    public double stiffeningD = 5;

    /**
     * If true, run MC19 (Mandad–Campen 2019) exact-constraint projection after the
     * §5.4 stiffening loop. Drives the per-cut-edge transition residual to literal
     * zero, making the output safe to feed into Lyon 2021's T-mesh stage. Default
     * false until the projection is proven on all fixtures.
     */
    public boolean exactSeams = true;

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

    /** §5.4 IRLS weights, initialized to 1. */
    public double[] faceWeight;

    /** Per-face 3D area (active-face order). */
    public double[] faceArea;
    /** Length 3 per face: b_i (= ∂φ/∂x coefficients in local frame). */
    public double[] faceShapeB;
    /** Length 3 per face: c_i (= ∂φ/∂y coefficients in local frame). */
    public double[] faceShapeC;
    /** u_T(ξ) x-component in local frame, post branch rotation. */
    public double[] faceUtxLocal;
    /** u_T(ξ) y-component in local frame, post branch rotation. */
    public double[] faceUtyLocal;
    /** v_T(ξ) x-component in local frame, post branch rotation. */
    public double[] faceVtxLocal;
    /** v_T(ξ) y-component in local frame, post branch rotation. */
    public double[] faceVtyLocal;

    /**
     * Soft-pin diagonal weight applied to a rounded integer DOF; passed to
     * {@link SeamlessDofSystem}.
     */
    public double integerPinWeight = 1.0e10;

    /**
     * DOF state + cached assembly plan + AMD perm. Constructed in {@link #build}.
     */
    public SeamlessDofSystem dofSystem;

    /**
     * §5.4 stiffening preconditioner: the cold Cholesky factor of iter 0's matrix,
     * reused as the preconditioner for warm-started PCG on iters ≥ 1. Null outside
     * {@link #runStiffeningLoop}.
     */
    public IncrementalCholeskySolver stiffeningPreconditioner;

    /**
     * Max PCG iterations per stiffening iter ≥ 1. With a good preconditioner
     * (iter-0 L) the matrix usually converges in well under 50 iters; if PCG hits
     * this cap, the preconditioner has gone stale and a fresh cold factor is
     * probably warranted.
     */
    public int stiffeningPcgMaxIterations = 200;

    /**
     * PCG relative residual tolerance: {@code ‖r‖² ≤ τ² · max(‖b‖², 1)}.
     */
    public double stiffeningPcgRelativeTolerance = 1.0e-8;

    /**
     * Target quad edge length, expressed as a fraction of the bounding-box
     * diagonal.
     */
    public float targetEdgeLengthFractionOfBounds = 0.01f;

    /**
     * Target quad edge length.
     */
    public float targetQuadEdgeLength;

    /** Last solver output (size {@code dofSystem.dofCount}). */
    private double[] solution;

    /**
     * Adopts a built {@link CrossField}. Caller must invoke {@link #build()} to
     * actually compute the parametrization.
     *
     * @param crossField a CrossField that has already had {@code build()} called
     */
    public SeamlessParameterization(CrossField crossField) {
        this.crossField = crossField;
        this.mesh = crossField.mesh;
        this.faceCount = mesh.faceCount();
        this.edgeCount = mesh.edgeCount();

        this.targetQuadEdgeLength = targetEdgeLengthFractionOfBounds * mesh.computeBoundingBoxDiagonal();

        edgeFaceA = new int[edgeCount];
        edgeFaceB = new int[edgeCount];
        edgeCornerInA = new int[edgeCount];
        edgeCornerInB = new int[edgeCount];
        Arrays.fill(edgeFaceA, -1);
        Arrays.fill(edgeFaceB, -1);
        Arrays.fill(edgeCornerInA, -1);
        Arrays.fill(edgeCornerInB, -1);

        cutGraph = new CutGraph(mesh, crossField, this);
    }

    /**
     * Run the BZK09 §5 pipeline; populate the public output arrays.
     *
     * @return the {@link ParameterizationMetrics} computed from the final
     *         parametrization
     * @throws IllegalStateException if the projected parametrization still contains
     *                               flipped triangles after MC19 §5.4 repair;
     *                               downstream motorcycle / ILP stages require an
     *                               injective parametrization
     */
    public ParameterizationMetrics build() {
        System.out.println("[seamless] Building seamless parameterization");
        for (int ae2 = 0; ae2 < edgeCount; ae2++) {
            EdgeFaceIds edgeFaceIds = mesh.edgeFaceIds(ae2);

            if (edgeFaceIds.faceA != MeshTopology.NONE) {
                edgeFaceA[ae2] = crossField.faceIdToActive.get(edgeFaceIds.faceA);
                int corner = -1;
                for (int c1 = 0; c1 < CORNERS_PER_FACE; c1++) {
                    if (mesh.faceVertexAt(edgeFaceIds.faceA, c1) == edgeFaceIds.edgeStartVertex) {
                        corner = c1;
                        break;
                    }
                }
                edgeCornerInA[ae2] = corner;
            }
            if (edgeFaceIds.faceB != MeshTopology.NONE) {
                edgeFaceB[ae2] = crossField.faceIdToActive.get(edgeFaceIds.faceB);
                int corner1 = -1;
                for (int c2 = 0; c2 < CORNERS_PER_FACE; c2++) {
                    if (mesh.faceVertexAt(edgeFaceIds.faceB, c2) == edgeFaceIds.edgeStartVertex) {
                        corner1 = c2;
                        break;
                    }
                }
                edgeCornerInB[ae2] = corner1;
            }
        }

        System.out.println("[seamless] Mesh setup done, building cut graph");

        cutGraph.buildCutGraph();

        System.out.println("[seamless] Cut graph built, precomputing per-face geometry and targets");
        precomputePerFaceGeometryAndTargets();

        System.out.println("[seamless] Per-face geometry and targets precomputed, assigning cut edge translation DOFs");

        this.faceWeight = new double[faceCount];
        Arrays.fill(faceWeight, 1.0);

        this.dofSystem = new SeamlessDofSystem(this, cutGraph);

        System.out.println("[seamless] Solving once");

        NormalMatrix matrix = dofSystem.assemble(faceWeight);
        dofSystem.applyIntegerPinPenalty(matrix);
        int[] perm = dofSystem.amdPermutation(matrix);
        double[] start = new double[dofSystem.dofCount];
        boolean[] fixed = new boolean[dofSystem.dofCount];
        solution = DirectSolver.solveWithPerm(matrix, start, fixed, perm);

        System.out.println("[seamless] Running greedy integer rounding");
        runGreedyIntegerRounding();

        System.out.println("[seamless] Running stiffening loop");
        runStiffeningLoop();

        System.out.println("[seamless] Writing chart vertices from solution");
        writeChartVerticesFromSolution();

        if (exactSeams) {

            System.out.println("[seamless] Projecting onto exact-seam parameterization");
            new SeamlessProjector(this).project();
        }
        this.metrics = new ParameterizationMetrics(this, mesh);
        System.out.println("[seamless] Metrics computed, returning");
        System.out.println("[seamless] Metrics: " + this.metrics);
        if (this.metrics.flippedTriangleCount > 0) {
            throw new IllegalStateException(
                    "seamless parametrization left " + this.metrics.flippedTriangleCount
                            + " flipped triangle(s) after projection; downstream motorcycle/ILP"
                            + " stages require a valid (injective, fold-over-free) parametrization."
                            + " Metrics: " + this.metrics);
        }
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
        for (int i = 0; i < dofSystem.dofCount; i++)
            if (dofSystem.dofIsInteger[i])
                totalToRound++;
        if (diag) {
            System.err.printf("[seamlessParam] greedy rounding: %d integer DOFs%n", totalToRound);
            double maxAbs = 0.0;
            int nearZero = 0;
            for (int i = 0; i < dofSystem.dofCount; i++) {
                if (!dofSystem.dofIsInteger[i])
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

        // Cold-factor the base system once; each pin then becomes a rank-1
        // update of L instead of a full re-factor. Davis ch. 4.10. AMD perm
        // is shared with the stiffening loop's solveOnce calls — same
        // matrix structure across the whole build.
        NormalMatrix baseMatrix = dofSystem.assemble(faceWeight);
        int[] perm = dofSystem.amdPermutation(baseMatrix);
        IncrementalCholeskySolver incremental = new IncrementalCholeskySolver();
        if (!incremental.setAWithPerm(baseMatrix, perm)) {
            throw new IllegalStateException(
                    "IGM rounding: cold Cholesky factor of the base system failed");
        }
        double[] runningRhs = baseMatrix.rightHandSide.clone();

        int rounded = 0;
        while (true) {
            int bestIdx = -1;
            double bestDist = Double.POSITIVE_INFINITY;
            double bestValue = 0;
            for (int i = 0; i < dofSystem.dofCount; i++) {
                if (!dofSystem.dofIsInteger[i] || dofSystem.dofPinned[i])
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
            dofSystem.pinDof(bestIdx, bestValue);
            rounded++;
            if (!incremental.pinDof(bestIdx, integerPinWeight)) {
                throw new IllegalStateException(
                        "IGM rounding: rank-1 update failed at DOF " + bestIdx);
            }
            runningRhs[bestIdx] += integerPinWeight * bestValue;
            incremental.solve(runningRhs, solution);
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
    public double u(int faceId, int cornerIdx) {
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
    public double v(int faceId, int cornerIdx) {
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
            if (Math.abs(twoArea) < DEGENERATE_AREA_EPSILON) {
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
        stiffeningPreconditioner = null;
        int initialFlipped = -1;
        int previousFlipped = -1;
        for (int iter = 0; iter <= maxStiffeningIterations; iter++) {
            long t0 = System.nanoTime();
            NormalMatrix matrix = dofSystem.assemble(faceWeight);
            dofSystem.applyIntegerPinPenalty(matrix);
            int dofCount = dofSystem.dofCount;
            if (stiffeningPreconditioner == null) {
                int[] perm = dofSystem.amdPermutation(matrix);
                stiffeningPreconditioner = new IncrementalCholeskySolver();
                if (!stiffeningPreconditioner.setAWithPerm(matrix, perm)) {
                    throw new IllegalStateException(
                            "stiffening: cold Cholesky factor of the base system failed");
                }
                solution = new double[dofCount];
                stiffeningPreconditioner.solve(matrix.rightHandSide, solution);
                System.out.println("[stiffening pcg] iter 0 cold factor + back-solve");
            } else {
                AdaptiveSolver.PcgResult result = AdaptiveSolver.preconditionedConjugateGradient(
                        matrix, solution, dofSystem.dofPinned,
                        stiffeningPreconditioner::solve,
                        stiffeningPcgMaxIterations, stiffeningPcgRelativeTolerance);
                System.out.printf("[stiffening pcg] %s in %d iters%n",
                        result.converged() ? "converged" : "DID NOT converge",
                        result.iterations());
            }
            long t1 = System.nanoTime();
            int flipped = countFlippedTrianglesFromSolution();
            if (initialFlipped < 0) {
                initialFlipped = flipped;
            }
            printStiffeningProgress(iter, flipped, initialFlipped, previousFlipped, t1 - t0);
            previousFlipped = flipped;
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

                double u0 = dofSystem.evaluateChartComponent(chartVertex0, 0, solution);
                double v0 = dofSystem.evaluateChartComponent(chartVertex0, 1, solution);
                double u1 = dofSystem.evaluateChartComponent(chartVertex1, 0, solution);
                double v1 = dofSystem.evaluateChartComponent(chartVertex1, 1, solution);
                double u2 = dofSystem.evaluateChartComponent(chartVertex2, 0, solution);
                double v2 = dofSystem.evaluateChartComponent(chartVertex2, 1, solution);

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

                perFaceDistortion[activeFace] = Math.abs(orientationSign * sigma1 * targetQuadEdgeLength - 1.0)
                        + Math.abs(orientationSign * sigma2 * targetQuadEdgeLength - 1.0);
            }

            if (iter == 0) {
                double minDistortion = Double.POSITIVE_INFINITY;
                double maxDistortion = Double.NEGATIVE_INFINITY;
                double sumDistortion = 0.0;
                int countedFaces = 0;
                for (int activeFace = 0; activeFace < faceCount; activeFace++) {
                    if (faceArea[activeFace] <= 0) {
                        continue;
                    }
                    double d = perFaceDistortion[activeFace];
                    if (d < minDistortion) {
                        minDistortion = d;
                    }
                    if (d > maxDistortion) {
                        maxDistortion = d;
                    }
                    sumDistortion += d;
                    countedFaces++;
                }
                double meanDistortion = countedFaces == 0 ? 0.0 : sumDistortion / countedFaces;
                System.out.printf(
                        "[stiffening-diag] perFaceDistortion @ iter 0 (h=%.6f): min=%.6g mean=%.6g max=%.6g (%d faces)%n",
                        targetQuadEdgeLength, minDistortion, meanDistortion, maxDistortion, countedFaces);
                int sampleCount = Math.min(20, faceCount);
                StringBuilder sample = new StringBuilder("[stiffening-diag] first ");
                sample.append(sampleCount).append(" face distortions:");
                for (int activeFace = 0; activeFace < sampleCount; activeFace++) {
                    sample.append(String.format(" f%d=%.4g", activeFace, perFaceDistortion[activeFace]));
                }
                System.out.println(sample.toString());
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
        }
    }

    /**
     * Materialise per-corner {@code uCorner} / {@code vCorner} from the current
     * {@link #solution} via each chart vertex's final-DOF expansion.
     *
     * @param totalCorners {@code 3 * faceCount}
     */
    private void writeChartVerticesFromSolution() {
        int totalCorners = faceCount * CORNERS_PER_FACE;
        uCorner = new double[totalCorners];
        vCorner = new double[totalCorners];
        for (int corner = 0; corner < totalCorners; corner++) {
            int chartVertex = cutGraph.cornerToChartVertex[corner];
            uCorner[corner] = dofSystem.evaluateChartComponent(chartVertex, 0, solution);
            vCorner[corner] = dofSystem.evaluateChartComponent(chartVertex, 1, solution);
        }
        cutTranslationS = new double[edgeCount];
        cutTranslationT = new double[edgeCount];
        for (int activeEdge = 0; activeEdge < edgeCount; activeEdge++) {
            if (dofSystem.cutEdgeSDof[activeEdge] < 0) {
                continue;
            }
            cutTranslationS[activeEdge] = dofSystem.evaluateRawDof(
                    dofSystem.cutEdgeSDof[activeEdge], solution);
            cutTranslationT[activeEdge] = dofSystem.evaluateRawDof(
                    dofSystem.cutEdgeTDof[activeEdge], solution);
        }
        boolean inj = true;
        for (int af = 0; af < faceCount; af++) {
            int o = af * CORNERS_PER_FACE;
            double u0 = uCorner[o], v0p = vCorner[o];
            double u1 = uCorner[o + 1], v1 = vCorner[o + 1];
            double u2 = uCorner[o + 2], v2 = vCorner[o + 2];
            double sa = HALF * ((u1 - u0) * (v2 - v0p) - (u2 - u0) * (v1 - v0p));
            if (sa <= 0f) {
                inj = false;
                break;
            }
        }

        this.injective = inj && this.injective;
    }

    /**
     * Print one §5.4 stiffening iteration's progress: iteration number,
     * flipped-triangle bar with initial-vs-current scale, signed delta since
     * previous iteration, and solve time. Output goes to stdout so it interleaves
     * with the rest of the build log.
     *
     * @param iter            zero-based iteration index
     * @param flipped         current flipped-triangle count
     * @param initialFlipped  flipped count at iter 0 (denominator of the bar)
     * @param previousFlipped flipped count one iteration ago, or -1 on iter 0
     * @param solveNanos      elapsed nanoseconds for this iteration's solve
     */
    private void printStiffeningProgress(int iter, int flipped, int initialFlipped,
            int previousFlipped, long solveNanos) {
        int barWidth = PROGRESS_BAR_WIDTH;
        int filled = initialFlipped == 0 ? 0
                : Math.max(0, Math.min(barWidth, (int) Math.round(
                        (double) flipped * barWidth / Math.max(1, initialFlipped))));
        StringBuilder bar = new StringBuilder(barWidth + 2);
        bar.append('[');
        for (int i = 0; i < barWidth; i++) {
            bar.append(i < filled ? '#' : '.');
        }
        bar.append(']');
        String delta;
        if (previousFlipped < 0) {
            delta = "(initial)";
        } else {
            int d = flipped - previousFlipped;
            delta = String.format("(%+d)", d);
        }
        System.out.printf("[stiffening] %s iter %2d/%d  flipped=%4d/%-4d %s  %.2fs%n",
                bar.toString(), iter, maxStiffeningIterations,
                flipped, initialFlipped, delta, solveNanos / 1.0e9);
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
            double u0 = dofSystem.evaluateChartComponent(cv0, 0, solution);
            double v0 = dofSystem.evaluateChartComponent(cv0, 1, solution);
            double u1 = dofSystem.evaluateChartComponent(cv1, 0, solution);
            double v1 = dofSystem.evaluateChartComponent(cv1, 1, solution);
            double u2 = dofSystem.evaluateChartComponent(cv2, 0, solution);
            double v2 = dofSystem.evaluateChartComponent(cv2, 1, solution);
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
    public double uvSignedArea(int faceId) {
        int activeFace = crossField.faceIdToActive.get(faceId);
        int o = activeFace * CORNERS_PER_FACE;
        double u0 = uCorner[o], v0 = vCorner[o];
        double u1 = uCorner[o + 1], v1 = vCorner[o + 1];
        double u2 = uCorner[o + 2], v2 = vCorner[o + 2];
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
    public double[] lookupCorners(int faceId, int vStart, int vEnd) {
        int cStart = -1, cEnd = -1;
        for (int c = 0; c < SeamlessParameterization.CORNERS_PER_FACE; c++) {
            int v = mesh.faceVertexAt(faceId, c);
            if (v == vStart)
                cStart = c;
            else if (v == vEnd)
                cEnd = c;
        }
        return new double[] {
                u(faceId, cStart), v(faceId, cStart),
                u(faceId, cEnd), v(faceId, cEnd),
        };
    }
}
