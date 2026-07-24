package ixdar.geometry.mesh.quadlayout.crossfield;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.Singularity;
import ixdar.geometry.mesh.quadlayout.crossfield.constraint.BoundaryConstraints;
import ixdar.geometry.mesh.quadlayout.crossfield.constraint.ConstraintSource;
import ixdar.geometry.mesh.quadlayout.crossfield.constraint.FeatureEdgeConstraints;
import ixdar.geometry.mesh.quadlayout.solver.AdaptiveSolver;
import ixdar.geometry.mesh.quadlayout.solver.DirectSolver;
import ixdar.geometry.mesh.quadlayout.solver.NormalMatrix;
import ixdar.geometry.mesh.quadlayout.solver.OrderingMethod;

/**
 * Cross field solved as a mixed-integer problem over per-face angles and
 * per-edge period jumps, with greedy rounding followed by a local search over
 * period jumps.
 *
 * <p>See also: BZK09 Section 4
 */
public class BommesCrossField extends CrossField {
    public static final long LOCAL_SEARCH_BUDGET_MS = 3000L;
    /**
     * The distances to search for local minima in the smoothness energy.
     */
    public static final int[] LOCAL_SEARCH_DELTAS = { -1, 1 };

    /**
     * The Voronoi forest of the cross field.
     */
    public VornoiForest vornoiForest;

    /**
     * Maximum number of local Gauss-Seidel iterations on the AdaptiveSolver before
     * falling back to a conjugate gradient solve.
     */
    public int solverLocalMaxIterations = 5000;

    /**
     * Maximum number of conjugate gradient iterations on the AdaptiveSolver before
     * falling back to a sparse Cholesky factorization.
     */
    public int solverCgMaxIterations = 50;

    /**
     * Target quad edge length, expressed as a fraction of the bounding-box
     * diagonal.
     */
    public float targetEdgeLengthFractionOfBounds = 0.04f;

    /**
     * Target quad edge length.
     */
    public float targetQuadEdgeLength;

    /**
     *
     * Cross field construction.
     *
     * @param mesh half-edge mesh providing geometry, topology, and active-id
     *             mapping
     */
    public BommesCrossField(HalfEdgeMesh mesh) {
        super(mesh);
        this.targetQuadEdgeLength = targetEdgeLengthFractionOfBounds * mesh.computeBoundingBoxDiagonal();
    }

    /**
     * Run the BZK09 pipeline (local face frames + edge transport angles κ,
     * directional constraints, Voronoi spanning forest, greedy mixed-integer
     * least-squares solve) and extract singularities.
     *
     * @return {@code this}, with field arrays populated and singularities filled
     */
    public CrossField build() {
        super.build();

        long sectionStart = System.nanoTime();

        FeatureEdgeConstraints.applyFeatureEdgeConstraints(mesh, this);
        BoundaryConstraints.applyBoundaryConstraints(mesh, this);
        curvatureConstraints.applyCurvatureConstraints(targetQuadEdgeLength);

        int totalConstraints = 0;
        for (boolean constrained : faceConstrained) {
            if (constrained) {
                totalConstraints++;
            }
        }
        if (totalConstraints == 0 && faceCount > 0) {
            faceConstrained[0] = true;
            faceConstraintAngle[0] = 0f;
            faceConstraintSource[0] = ConstraintSource.ANCHOR;
            totalConstraints = 1;
        }
        System.out.printf("[cross-field timing] directional constraints %.3fs%n",
                (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
        sectionStart = System.nanoTime();

        VornoiForest vornoiForest = new VornoiForest(mesh, this);
        vornoiForest.buildVoronoiSpanningForest();
        System.out.printf("[cross-field timing] Voronoi forest %.3fs%n",
                (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
        sectionStart = System.nanoTime();

        SmoothEnergySystem system = new SmoothEnergySystem(faceCount, edgeCount,
                faceConstrained, faceConstraintAngle, vornoiForest);
        system.assemble(mesh, faceIdToActive, kappa, solverLocalMaxIterations, solverCgMaxIterations);
        System.out.printf("[cross-field timing] smooth-energy assemble %.3fs%n",
                (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
        sectionStart = System.nanoTime();

        system.solveGreedyMIP();
        System.out.printf("[cross-field timing] greedy mixed-integer solve %.3fs%n",
                (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
        sectionStart = System.nanoTime();

        system.unpackInto(mesh, this);
        extractSingularities();
        System.out.printf("[cross-field timing] unpack + extract singularities %.3fs%n",
                (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
        sectionStart = System.nanoTime();

        localSearchSingularityOptimization();
        System.out.printf("[cross-field timing] local search singularity optimization %.3fs%n",
                (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);

        printSolutionDiagnostics(system);
        return this;
    }

    /**
     * For each edge incident to a current singularity, tries changing the period
     * jump by ±1 and re-solves theta, keeping the improvement. The theta-only
     * matrix is factorized once and reused: shifting period jumps changes only the
     * right-hand side.
     *
     * <p>See also: BZK09 Section 4.2
     */
    private void localSearchSingularityOptimization() {

        long deadlineMs = System.currentTimeMillis() + LOCAL_SEARCH_BUDGET_MS;

        if (this.interiorRowCount == 0) {
            return;
        }
        int row = 0;
        for (int i = 0; i < edgeCount; i++) {
            int edgeId = mesh.edgeIdAt(i);
            if (mesh.isBoundaryEdge(edgeId)) {
                continue;
            }
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            int twin = mesh.halfEdgeTwin(halfEdge);
            rowFaceA[row] = faceIdToActive.get(mesh.halfEdgeFace(halfEdge));
            rowFaceB[row] = faceIdToActive.get(mesh.halfEdgeFace(twin));
            rowKappaPlusHalfPiP[row] = kappa[i] + HALF_PI * periodJump[i];
            rowOfEdge[i] = row;
            row++;
        }
        final NormalMatrix matrix = new NormalMatrix(faceCount, interiorRowCount,
                rowFaceA, rowFaceB, rowKappaPlusHalfPiP);
        final double[] start = new double[faceCount];
        for (int fAi = 0; fAi < faceCount; fAi++) {
            start[fAi] = faceConstrained[fAi] ? faceConstraintAngle[fAi] : 0.0;
        }
        final DirectSolver.CholeskyHandle handle = DirectSolver.factorize(matrix, faceConstrained, OrderingMethod.AMD);
        if (handle.factor() == null) {
            return;
        }

        final double[] mainRhs = new double[faceCount];
        final double[] mainTheta = new double[faceCount];
        buildRhs(mainRhs, -1, 0);
        DirectSolver.solveCompact(handle, matrix, mainRhs, mainTheta, start, faceConstrained);
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            theta[activeFace] = (float) mainTheta[activeFace];
        }
        double currentEnergy = energyOfTheta(mainTheta, -1, 0);

        final Set<Integer> candidateEdges = new HashSet<>();
        for (Singularity singularity : singularities) {
            int vertexId = singularity.vertexId();
            int outgoingCount = mesh.vertexOutgoingHalfEdgeCount(vertexId);
            for (int i = 0; i < outgoingCount; i++) {
                int halfEdge = mesh.vertexOutgoingHalfEdgeAt(vertexId, i);
                int edgeId = mesh.halfEdgeEdge(halfEdge);
                if (!mesh.isBoundaryEdge(edgeId)) {
                    candidateEdges.add(edgeIdToActive.get(edgeId));
                }
            }
        }
        List<Integer> candidates = new ArrayList<>(candidateEdges);
        Collections.sort(candidates);

        final double[] perturbationRhs = new double[faceCount];
        final double[] zeroStart = new double[faceCount];
        final double[] response = new double[faceCount];
        final double[] trialTheta = new double[faceCount];

        for (int activeEdge : candidates) {
            if (System.currentTimeMillis() > deadlineMs) {
                // throw new IllegalStateException(LOCAL_SEARCH_TIMEOUT_MESSAGE);
                break;
            }
            int edgeRow = rowOfEdge[activeEdge];
            Arrays.fill(perturbationRhs, 0.0);
            perturbationRhs[rowFaceA[edgeRow]] = -HALF_PI;
            perturbationRhs[rowFaceB[edgeRow]] = HALF_PI;
            DirectSolver.solveCompact(handle, matrix, perturbationRhs, response, zeroStart,
                    faceConstrained);

            int oldPeriodJump = periodJump[activeEdge];
            int bestDelta = 0;
            double bestTrialEnergy = currentEnergy;
            for (int delta : LOCAL_SEARCH_DELTAS) {
                for (int activeFace = 0; activeFace < faceCount; activeFace++) {
                    trialTheta[activeFace] = mainTheta[activeFace] + delta * response[activeFace];
                }
                double energy = energyOfTheta(trialTheta, activeEdge, oldPeriodJump + delta);
                if (energy < bestTrialEnergy) {
                    bestTrialEnergy = energy;
                    bestDelta = delta;
                }
            }
            if (bestDelta != 0) {
                for (int activeFace = 0; activeFace < faceCount; activeFace++) {
                    mainTheta[activeFace] += bestDelta * response[activeFace];
                }
                periodJump[activeEdge] += bestDelta;
                currentEnergy = bestTrialEnergy;
            }
        }
        buildRhs(mainRhs, -1, 0);
        DirectSolver.solveCompact(handle, matrix, mainRhs, mainTheta, start, faceConstrained);
        DirectSolver.releaseHandle(handle);
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            theta[activeFace] = (float) mainTheta[activeFace];
        }
        extractSingularities();
    }

    /**
     * Builds the right-hand side for the theta-only Laplacian by accumulating
     * ±(κ + (π/2)·p) over interior edges into the two incident faces, using
     * {@code perturbedPeriodJump} for {@code perturbEdge} when that is non-negative.
     * Constrained-face contributions are omitted here;
     * {@link AdaptiveSolver#solveCompact} folds them in.
     */
    private void buildRhs(double[] rhs, int perturbEdge, int perturbedPeriodJump) {
        Arrays.fill(rhs, 0.0);
        for (int activeEdgeIndex = 0; activeEdgeIndex < edgeCount; activeEdgeIndex++) {
            int row = rowOfEdge[activeEdgeIndex];
            if (row < 0)
                continue;
            int faceA = rowFaceA[row];
            int faceB = rowFaceB[row];
            double k = kappa[activeEdgeIndex] + HALF_PI *
                    ((activeEdgeIndex == perturbEdge) ? perturbedPeriodJump : periodJump[activeEdgeIndex]);
            rhs[faceA] -= k;
            rhs[faceB] += k;
        }
    }

    /**
     * Sum of squared per-edge residuals for the given theta. {@code perturbEdge}
     * lets a worker evaluate the energy as if
     * {@code periodJump[perturbEdge] == perturbedP} without mutating shared state.
     */
    private double energyOfTheta(double[] thetaFull, int perturbEdge, int perturbedP) {
        double e = 0.0;
        for (int eAi = 0; eAi < edgeCount; eAi++) {
            int r = rowOfEdge[eAi];
            if (r < 0)
                continue;
            int p = (eAi == perturbEdge) ? perturbedP : periodJump[eAi];
            double resid = thetaFull[rowFaceA[r]] + kappa[eAi] + HALF_PI * p - thetaFull[rowFaceB[r]];
            e += resid * resid;
        }
        return e;
    }

    /**
     * Print one-shot solution-quality diagnostics (solver path counts, residuals,
     * smoothness energy, singularity histogram) to stdout.
     *
     * @param system the smooth-energy linear system after the greedy MIP solve
     */
    public void printSolutionDiagnostics(SmoothEnergySystem system) {
        double avgBatch = system.batchCount > 0
                ? (double) system.totalBatchSize / system.batchCount
                : 0.0;
        double avgInitialQueue = system.batchCount > 0
                ? (double) system.totalLocalInitialQueueSize / system.batchCount
                : 0.0;
        System.out.printf(
                "[cross-field] adaptive localGS=%d cg=%d direct=%d failed=%d localIters=%d cgIters=%d localCapHits=%d capFace=%d capChord=%d maxCapResidual=%.6g avgSeedQueue=%.3f maxQueue=%d batches=%d avgBatch=%.3f maxBatch=%d rejectOverlap=%d rejectRoundoff=%d%n",
                system.localGsConverged, system.cgConverged, system.directFallbacks,
                system.failedSolves, system.totalLocalGsIterations, system.totalCgIterations,
                system.localGsCapHits, system.localCapFaceRows, system.localCapChordRows,
                system.maxLocalCapResidual, avgInitialQueue, system.maxLocalQueueSize,
                system.batchCount, avgBatch, system.maxBatchSize,
                system.batchRejectedByOverlap, system.batchRejectedByRoundoff);

        Map<Integer, Integer> histogram = new HashMap<>();
        for (Singularity s : singularities) {
            histogram.merge(s.index4(), 1, Integer::sum);
        }
        System.out.printf("[cross-field] singularityHistogram=%s%n", histogram);
    }

    /**
     * Reduce {@code angle} into the half-open interval {@code [0, PI/2)}.
     *
     * @param angle angle in radians
     * @return canonical representative of {@code angle} modulo PI/2
     */
    public static float canonicalizeMod(float angle) {
        float halfPi = (float) (Math.PI / 2.0);
        float r = (float) (angle - halfPi * Math.floor(angle / halfPi));
        if (r < 0)
            r += halfPi;
        return r;
    }

}
