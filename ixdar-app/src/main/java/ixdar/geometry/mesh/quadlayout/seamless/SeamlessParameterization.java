package ixdar.geometry.mesh.quadlayout.seamless;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh.EdgeFaceIds;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.seamless.exact.SeamlessProjector;
import ixdar.geometry.mesh.quadlayout.solver.AMDOrdering;
import ixdar.geometry.mesh.quadlayout.solver.CholeskyBackend;
import ixdar.geometry.mesh.quadlayout.solver.DirectSolver;
import ixdar.geometry.mesh.quadlayout.solver.IncrementalCholeskySolver;
import ixdar.geometry.mesh.quadlayout.solver.InteriorPointQp;
import ixdar.geometry.mesh.quadlayout.solver.NormalMatrix;
import ixdar.platform.Platforms;

/**
 * Turns a {@link CrossField} into per-corner (u, v) satisfying
 *
 * <pre>
 *   (u', v') = R<sub>r_e · π/2</sub>(u, v) + (s<sub>e</sub>, t<sub>e</sub>)
 * </pre>
 *
 * across every cut edge, with r<sub>e</sub> fixed by the cross field.
 *
 * <p>
 * See also: BZK09 Section 5
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

    /**
     * A parametric triangle below this fraction of its expected area
     * ({@code faceArea / targetQuadEdgeLength²}) counts as a local-injectivity
     * violation alongside flips, because collapsed triangles merge singularities.
     */
    private static final double DEGENERATE_UV_AREA_FRACTION = 1.0e-6;

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

    /** Hard cap on lazy-constraint rounds (BCE13 §3.4's outer iterations). */
    public int maxConstraintRounds = 60;

    /**
     * If true, run MC19 (Mandad–Campen 2019) exact-constraint projection after the
     * injectivity-constraint solve. Drives the per-cut-edge transition residual to
     * literal zero, making the output safe to feed into Lyon 2021's T-mesh stage;
     * the BCE13 ε margin absorbs the projection's adjustment (MC19 §7).
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
     * DOF state + cached assembly plan. Constructed in {@link #build}.
     */
    public SeamlessDofSystem dofSystem;

    /**
     * Native factor of the base system, created by the rounding stage's
     * no-integer-DOFs fast path. Released by the injectivity loop, which owns its
     * own handle on the fixed superset pattern.
     */
    public DirectSolver.CholeskyHandle baseFactorHandle;

    /**
     * Matrix backing {@link #baseFactorHandle}; needed by
     * {@code DirectSolver.solveCompact} when solving through the handle.
     */
    public NormalMatrix baseFactorMatrix;

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
     * @throws IllegalStateException if the projected parametrization still contains
     *                               flipped triangles after MC19 §5.4 repair;
     *                               downstream motorcycle / ILP stages require an
     *                               injective parametrization
     * @return the {@link ParameterizationMetrics} computed from the final
     *         parametrization
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

        long dofSystemStart = System.nanoTime();
        this.dofSystem = new SeamlessDofSystem(this, cutGraph);
        Platforms.log("[seamless timing] dof system %.3fs%n",
                (System.nanoTime() - dofSystemStart) / 1.0e9);

        System.out.println("[seamless] Running greedy integer rounding");
        long roundingStart = System.nanoTime();
        runGreedyIntegerRounding();
        Platforms.log("[seamless timing] greedy integer rounding %.3fs%n",
                (System.nanoTime() - roundingStart) / 1.0e9);

        System.out.println("[seamless] Running BCE13 injectivity-constraint loop");
        long constraintStart = System.nanoTime();
        runInjectivityConstraintLoop();
        Platforms.log("[seamless timing] injectivity loop %.3fs%n",
                (System.nanoTime() - constraintStart) / 1.0e9);

        System.out.println("[seamless] Writing chart vertices from solution");
        writeChartVerticesFromSolution();

        if (exactSeams) {

            System.out.println("[seamless] Projecting onto exact-seam parameterization");
            new SeamlessProjector(this).project();
        }
        this.metrics = new ParameterizationMetrics(this, mesh);
        System.out.println("[seamless] Metrics computed, returning");
        System.out.println("[seamless] Metrics: " + this.metrics);
        return this.metrics;
    }

    /**
     * Greedy rounding: repeatedly snap the unpinned integer DOF closest to an
     * integer and re-solve. When constraint reduction already pinned every integer
     * DOF, the base system is factored natively once and solved directly.
     *
     * <p>
     * See also: BZK09 Section 5
     */
    private void runGreedyIntegerRounding() {

        // Cold-factor the base system once; each pin then becomes a rank-1
        // update of L instead of a full re-factor. Davis ch. 4.10.
        long assembleStart = System.nanoTime();
        NormalMatrix baseMatrix = dofSystem.assemble(faceWeight);
        long amdStart = System.nanoTime();
        AMDOrdering ordering = new AMDOrdering();
        ordering.order(baseMatrix);
        int[] perm = ordering.permutation;
        long amdEnd = System.nanoTime();
        this.solution = new double[dofSystem.dofCount];

        boolean anyUnpinnedInteger = false;
        for (int i = 0; i < dofSystem.dofCount; i++) {
            if (dofSystem.dofIsInteger[i] && !dofSystem.dofPinned[i]) {
                anyUnpinnedInteger = true;
                break;
            }
        }
        if (!anyUnpinnedInteger && CholeskyBackend.pardisoAvailable()) {
            long nativeFactorStart = System.nanoTime();
            boolean[] noneFixed = new boolean[dofSystem.dofCount];
            baseFactorHandle = DirectSolver.factorizeWithPerm(baseMatrix, noneFixed, perm);
            baseFactorMatrix = baseMatrix;
            DirectSolver.solveCompact(baseFactorHandle, baseMatrix,
                    baseMatrix.rightHandSide, solution, solution, noneFixed);
            Platforms.log(
                    "[seamless timing] rounding assemble %.3fs, amd %.3fs, native factor+solve %.3fs"
                            + " (n=%d, 0 integer DOFs to pin)%n",
                    (amdStart - assembleStart) / 1.0e9,
                    (amdEnd - amdStart) / 1.0e9,
                    (System.nanoTime() - nativeFactorStart) / 1.0e9,
                    dofSystem.dofCount);
            return;
        }

        long coldFactorStart = System.nanoTime();
        IncrementalCholeskySolver incremental = new IncrementalCholeskySolver();
        if (!incremental.setAWithPerm(baseMatrix, perm)) {
            throw new IllegalStateException(
                    "IGM rounding: cold Cholesky factor of the base system failed");
        }
        long pinLoopStart = System.nanoTime();
        Platforms.log("[seamless timing] rounding assemble %.3fs, amd %.3fs, cold factor %.3fs (n=%d)%n",
                (amdStart - assembleStart) / 1.0e9,
                (coldFactorStart - amdStart) / 1.0e9,
                (pinLoopStart - coldFactorStart) / 1.0e9,
                dofSystem.dofCount);
        incremental.solve(baseMatrix.rightHandSide, solution);
        double[] runningRhs = baseMatrix.rightHandSide.clone();

        int pinCount = 0;
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
            if (!incremental.pinDof(bestIdx, integerPinWeight)) {
                throw new IllegalStateException(
                        "IGM rounding: rank-1 update failed at DOF " + bestIdx);
            }
            runningRhs[bestIdx] += integerPinWeight * bestValue;
            incremental.solve(runningRhs, solution);
            pinCount++;
        }
        Platforms.log("[seamless timing] rounding pin+solve loop %.3fs (%d pins)%n",
                (System.nanoTime() - pinLoopStart) / 1.0e9, pinCount);
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
     * BCE13 §3.4's lazy-constraint loop: evaluate every Equation 4 inequality,
     * activate the violated plus every one below the activation threshold, and
     * re-solve the hard-constrained convex QP over the active set with
     * {@link InteriorPointQp} until no constraint is violated or the round cap is
     * reached.
     */
    private void runInjectivityConstraintLoop() {
        if (baseFactorHandle != null) {
            DirectSolver.releaseHandle(baseFactorHandle);
            baseFactorHandle = null;
            baseFactorMatrix = null;
        }
        InjectivityConstraints constraints = new InjectivityConstraints(this).build();
        double[] values = new double[constraints.constraintCount];
        boolean[] constraintActive = new boolean[constraints.constraintCount];
        int activeCount = 0;
        NormalMatrix baseMatrix = dofSystem.assemble(faceWeight);
        dofSystem.applyIntegerPinPenalty(baseMatrix);
        int violated = -1;
        for (int round = 0; round <= maxConstraintRounds; round++) {
            constraints.evaluateNormalized(solution, values);
            violated = 0;
            double worst = Double.POSITIVE_INFINITY;
            for (int constraint = 0; constraint < constraints.constraintCount; constraint++) {
                worst = Math.min(worst, values[constraint]);
                violated += values[constraint] < 0.0 ? 1 : 0;
            }
            Platforms.log("[injectivity] round %d violated=%d active=%d worst=%.4f%n",
                    round, violated, activeCount, worst);
            if (violated == 0 || round == maxConstraintRounds) {
                break;
            }
            long roundStart = System.nanoTime();
            for (int constraint = 0; constraint < constraints.constraintCount; constraint++) {
                if (!constraintActive[constraint]
                        && values[constraint] < InjectivityConstraints.ACTIVATION_THRESHOLD) {
                    constraintActive[constraint] = true;
                    activeCount++;
                }
            }
            int[][] activeDofs = new int[activeCount][];
            double[][] activeCoefs = new double[activeCount][];
            double[] activeBound = new double[activeCount];
            int activeCursor = 0;
            for (int constraint = 0; constraint < constraints.constraintCount; constraint++) {
                if (!constraintActive[constraint]) {
                    continue;
                }
                activeDofs[activeCursor] = constraints.gradientDofs(constraint);
                activeCoefs[activeCursor] = constraints.gradientCoefs(constraint);
                // The normalized constraint a·x − δε ≥ 0 becomes a·x ≥ δε.
                activeBound[activeCursor] = constraints.normalizer[constraint]
                        * constraints.rawThreshold[constraint];
                activeCursor++;
            }
            InteriorPointQp qp = new InteriorPointQp(baseMatrix, activeDofs, activeCoefs,
                    activeBound);
            qp.solve(solution);
            Platforms.log(
                    "[seamless timing] injectivity round %d %.3fs (ipIterations=%d, factorizations=%d, converged=%b)%n",
                    round, (System.nanoTime() - roundStart) / 1.0e9, qp.iterationCount,
                    qp.factorizationCount, qp.converged);
        }
        injective = violated == 0;
        Platforms.log("[injectivity] done violated=%d flippedTriangles=%d%n", violated,
                countFlippedTrianglesFromSolution());
    }

    /**
     * Materialise per-corner {@code uCorner} / {@code vCorner} from the current
     * {@link #solution} via each chart vertex's final-DOF expansion.
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
     * Count local-injectivity violations of the current solution: flipped triangles
     * (negative parametric area) and collapsed ones (parametric area below
     * {@link #DEGENERATE_UV_AREA_FRACTION} of the face's expected area
     * {@code faceArea / h²}). Both counts must reach zero.
     *
     * @return number of flipped or collapsed triangles
     */
    private int countFlippedTrianglesFromSolution() {
        int flipped = 0;
        double inverseTargetAreaScale = 1.0 / (targetQuadEdgeLength * targetQuadEdgeLength);
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
            double expectedUvArea = faceArea[af] * inverseTargetAreaScale;
            if (sa <= DEGENERATE_UV_AREA_FRACTION * expectedUvArea)
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

    /**
     * Corner UV coordinates for an active face.
     *
     * @param activeFace active face index
     * @param out        length-6 buffer receiving {@code [u0,v0,u1,v1,u2,v2]}
     */
    public void faceCornerUv(int activeFace, double[] out) {
        int base = activeFace * CORNERS_PER_FACE;
        out[0] = uCorner[base];
        out[1] = vCorner[base];
        out[2] = uCorner[base + 1];
        out[3] = vCorner[base + 1];
        out[4] = uCorner[base + 2];
        out[5] = vCorner[base + 2];
    }
}
