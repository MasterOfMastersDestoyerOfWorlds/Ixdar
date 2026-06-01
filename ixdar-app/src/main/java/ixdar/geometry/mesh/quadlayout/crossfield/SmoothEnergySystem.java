package ixdar.geometry.mesh.quadlayout.crossfield;

/*
     * A4. Smooth-energy MIP solver (greedy rounding of period jumps)
     *
     * Energy: E = Σ_e (θ_i + κ_ij + (π/2)·p_ij − θ_j)² non-boundary edges
     *
     * Each row r of the design matrix A: A[r, θ_i] = +1 A[r, θ_j] = −1 A[r, p_e] =
     * +π/2 with rhs b[r] = −κ_ij so that residual = A·x − b.
     *
     * Hard constraints (θ̂_f for f ∈ F_c, p̂_e for e ∈ fixed_edges) are applied by
     * elimination — we move them to the rhs and solve the reduced normal equations
     * Hᵣ · x_free = gᵣ via Conjugate Gradient.
     *
     * Greedy MIP rounding (BZK09 §2): while any free p variable remains:
     * relax-solve, then fix the free p with the smallest |p − round(p)|
     */

import java.util.Arrays;
import java.util.Map;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.NormalMatrix;
import ixdar.geometry.mesh.quadlayout.solver.AdaptiveSolver;

public final class SmoothEnergySystem {
    public static final double DEFAULT_LOCAL_TOLERANCE = 1e-6;
    public static final int DEFAULT_ROUND_BATCH_SIZE = 256;
    public static final double DEFAULT_ROUND_BATCH_TOLERANCE = 0.15;
    public static final double DEFAULT_CG_TOLERANCE = 1e-3;
    final int faceCount;
    final int edgeCount;
    final boolean[] faceConstrained;
    final float[] faceConstraintAngle;
    final VornoiForest vornoiForest;

    int[] rowFaceI;
    int[] rowFaceJ;
    int[] rowEdgeAi;
    float[] rowKappa;
    int rowCount;
    int[] chordOfEdge;
    int[] edgeOfChord;
    int chordCount;

    float[] solutionTheta;
    float[] solutionPeriod;
    boolean[] periodFixed;
    boolean[] fixedVariables;
    double[] solution;
    NormalMatrix normalMatrix;
    AdaptiveSolver.Options adaptiveOptions;
    int roundBatchSize;
    double roundBatchTol;
    int localGsConverged;
    int cgConverged;
    int directFallbacks;
    int failedSolves;
    int totalLocalGsIterations;
    int totalCgIterations;
    int localGsCapHits;
    int totalLocalInitialQueueSize;
    int maxLocalQueueSize;
    double maxLocalCapResidual;
    int localCapFaceRows;
    int localCapChordRows;
    int roundedPeriods;
    int batchCount;
    int totalBatchSize;
    int maxBatchSize;
    int batchRejectedByOverlap;
    int batchRejectedByRoundoff;
    String lastAdaptiveMethod = "none";
    double lastAdaptiveResidual;

    SmoothEnergySystem(int faceCount, int edgeCount,
            boolean[] faceConstrained, float[] faceConstraintAngle,
            VornoiForest vornoiForest) {
        this.faceCount = faceCount;
        this.edgeCount = edgeCount;
        this.faceConstrained = faceConstrained;
        this.faceConstraintAngle = faceConstraintAngle;
        this.vornoiForest = vornoiForest;
    }

    void assemble(HalfEdgeMesh mesh, Map<Integer, Integer> faceIdToActive, float[] kappa,
            int solverLocalMaxIterations, int solverCgMaxIterations) {

        int nbCount = 0;
        for (int eAi = 0; eAi < edgeCount; eAi++) {
            if (!mesh.isBoundaryEdge(mesh.edgeIdAt(eAi)))
                nbCount++;
        }
        rowFaceI = new int[nbCount];
        rowFaceJ = new int[nbCount];
        rowEdgeAi = new int[nbCount];
        rowKappa = new float[nbCount];
        rowCount = 0;
        for (int eAi = 0; eAi < edgeCount; eAi++) {
            int eId = mesh.edgeIdAt(eAi);
            if (mesh.isBoundaryEdge(eId))
                continue;
            int he = mesh.edgeHalfEdge(eId);
            int twin = mesh.halfEdgeTwin(he);
            rowFaceI[rowCount] = faceIdToActive.get(mesh.halfEdgeFace(he));
            rowFaceJ[rowCount] = faceIdToActive.get(mesh.halfEdgeFace(twin));
            rowEdgeAi[rowCount] = eAi;
            rowKappa[rowCount] = kappa[eAi];
            rowCount++;
        }

        periodFixed = vornoiForest.periodFixed.clone();
        chordOfEdge = new int[edgeCount];
        Arrays.fill(chordOfEdge, -1);
        chordCount = 0;
        for (int eAi = 0; eAi < edgeCount; eAi++) {
            if (!periodFixed[eAi]) {
                chordOfEdge[eAi] = chordCount++;
            }
        }
        edgeOfChord = new int[chordCount];
        for (int eAi = 0; eAi < edgeCount; eAi++) {
            int chord = chordOfEdge[eAi];
            if (chord >= 0) {
                edgeOfChord[chord] = eAi;
            }
        }

        solutionTheta = new float[faceCount];
        solutionPeriod = new float[edgeCount];
        solution = new double[faceCount + chordCount];
        fixedVariables = new boolean[faceCount + chordCount];
        for (int fAi = 0; fAi < faceCount; fAi++) {
            solutionTheta[fAi] = faceConstrained[fAi] ? faceConstraintAngle[fAi] : 0f;
            solution[fAi] = solutionTheta[fAi];
            fixedVariables[fAi] = faceConstrained[fAi];
        }
        for (int eAi = 0; eAi < edgeCount; eAi++) {
            solutionPeriod[eAi] = periodFixed[eAi] ? vornoiForest.periodValue[eAi] : 0f;
            int chord = chordOfEdge[eAi];
            if (chord >= 0) {
                solution[faceCount + chord] = solutionPeriod[eAi];
            }
        }
        normalMatrix = new NormalMatrix(faceCount, chordCount, rowCount, rowFaceI, rowFaceJ, rowEdgeAi, chordOfEdge,
                vornoiForest.periodValue, rowKappa);
        adaptiveOptions = new AdaptiveSolver.Options();
        adaptiveOptions.localMaxIterations = solverLocalMaxIterations;
        adaptiveOptions.localTolerance = DEFAULT_LOCAL_TOLERANCE;
        adaptiveOptions.cgMaxIterations = solverCgMaxIterations;
        adaptiveOptions.cgTolerance = DEFAULT_CG_TOLERANCE;
        adaptiveOptions.useDirectFallback = true;

        roundBatchSize = DEFAULT_ROUND_BATCH_SIZE;
        roundBatchTol = DEFAULT_ROUND_BATCH_TOLERANCE;
    }

    void solveGreedyMIP(String lastDiagnostics) {
        lastAdaptiveMethod = "BOOTSTRAP_DIRECT_PENDING";
        lastAdaptiveResidual = Double.NaN;
        solveRelaxed(-1);
        int[] roundedVariables = new int[roundBatchSize];
        int[] roundedEdges = new int[roundBatchSize];
        int[] patch = new int[normalMatrix.size()];
        boolean[] patchMarked = new boolean[normalMatrix.size()];
        boolean[] candidateMarked = new boolean[normalMatrix.size()];
        BatchCandidate[] candidates = new BatchCandidate[chordCount];
        while (true) {
            int candidateCount = buildBatchCandidates(candidates);
            if (candidateCount == 0)
                break;
            int batchSize = selectRoundingBatch(candidates, candidateCount,
                    roundedVariables, roundedEdges, patch, patchMarked, candidateMarked);
            if (batchSize == 0) {
                break;
            }
            for (int i = 0; i < batchSize; i++) {
                int variable = roundedVariables[i];
                int eAi = roundedEdges[i];
                int rounded = (int) Math.round(solution[variable]);
                periodFixed[eAi] = true;
                vornoiForest.periodValue[eAi] = rounded;
                solutionPeriod[eAi] = rounded;
                solution[variable] = rounded;
                fixedVariables[variable] = true;
            }
            roundedPeriods += batchSize;
            batchCount++;
            totalBatchSize += batchSize;
            maxBatchSize = Math.max(maxBatchSize, batchSize);
            lastAdaptiveMethod = batchSize == 1 ? "ROUND_LOCAL_GS_PENDING" : "BATCH_LOCAL_GS_PENDING";
            lastAdaptiveResidual = Double.NaN;
            solveRelaxed(roundedVariables, batchSize);
        }
    }

    /**
     * Populate {@code candidates} with the free chords ordered ascending by
     * roundoff distance to the nearest integer; respects {@link #roundBatchTol}
     * when batching is on.
     *
     * @param candidates output array sized at least {@link #chordCount}
     * @return number of valid entries written to {@code candidates}
     */
    public int buildBatchCandidates(BatchCandidate[] candidates) {
        int candidateCount = 0;
        int rejectedByRoundoff = 0;
        BatchCandidate best = null;
        for (int chord = 0; chord < chordCount; chord++) {
            int eAi = edgeOfChord[chord];
            if (periodFixed[eAi]) {
                continue;
            }
            double value = solution[faceCount + chord];
            BatchCandidate candidate = new BatchCandidate(
                    chord, eAi, Math.abs(value - Math.rint(value)));
            if (best == null || candidate.roundoff < best.roundoff) {
                best = candidate;
            }
            if (roundBatchSize == 1 || candidate.roundoff <= roundBatchTol) {
                candidates[candidateCount++] = candidate;
            } else {
                rejectedByRoundoff++;
            }
        }
        if (roundBatchSize == 1) {
            if (best == null) {
                return 0;
            }
            candidates[0] = best;
            return 1;
        }
        if (candidateCount == 0) {
            if (best == null) {
                return 0;
            }
            candidates[0] = best;
            return 1;
        }
        Arrays.sort(candidates, 0, candidateCount,
                (a, b) -> Double.compare(a.roundoff, b.roundoff));
        batchRejectedByRoundoff += rejectedByRoundoff;
        return candidateCount;
    }

    /**
     * Pick a non-overlapping subset of {@code candidates} (each variable's two-hop
     * dependency patch must be disjoint within the batch) of size up to
     * {@link #roundBatchSize}.
     *
     * @param candidates       roundoff-sorted candidate list
     * @param candidateCount   valid prefix length of {@code candidates}
     * @param roundedVariables output buffer for picked variable indices
     * @param roundedEdges     output buffer for picked edge active indices
     * @param candidatePatch   scratch buffer for one candidate's patch members
     * @param selectedPatch    scratch flag array marking variables already claimed
     *                         by the batch
     * @param candidateMarked  scratch marker buffer for
     *                         {@link AdaptiveSolver#collectAffectedPatch}
     * @return number of variables admitted to the batch
     */
    public int selectRoundingBatch(BatchCandidate[] candidates,
            int candidateCount,
            int[] roundedVariables,
            int[] roundedEdges,
            int[] candidatePatch,
            boolean[] selectedPatch,
            boolean[] candidateMarked) {
        Arrays.fill(selectedPatch, false);
        int batchSize = 0;
        for (int i = 0; i < candidateCount && batchSize < roundBatchSize; i++) {
            BatchCandidate candidate = candidates[i];
            int variable = faceCount + candidate.chord;
            int patchCount = AdaptiveSolver.collectAffectedPatch(
                    normalMatrix, variable, fixedVariables, candidatePatch, candidateMarked);
            boolean overlaps = false;
            for (int p = 0; p < patchCount; p++) {
                if (selectedPatch[candidatePatch[p]]) {
                    overlaps = true;
                    break;
                }
            }
            if (overlaps) {
                batchRejectedByOverlap++;
            } else {
                roundedVariables[batchSize] = variable;
                roundedEdges[batchSize] = candidate.edgeAi;
                batchSize++;
                for (int p = 0; p < patchCount; p++) {
                    selectedPatch[candidatePatch[p]] = true;
                }
            }
            for (int p = 0; p < patchCount; p++) {
                candidateMarked[candidatePatch[p]] = false;
            }
        }
        return batchSize;
    }

    /**
     * Continuous L2 solve with currently-fixed variables held constant. Uses the
     * BZK09 adaptive ladder: local GS, then CG, then direct fallback.
     *
     * @param roundedVariable variable index just rounded, or negative for the
     *                        bootstrap (no rounded variable yet)
     */
    void solveRelaxed(int roundedVariable) {
        if (roundedVariable < 0) {
            solveRelaxed((int[]) null, 0);
            return;
        }
        solveRelaxed(new int[] { roundedVariable }, 1);
    }

    void solveRelaxed(int[] roundedVariables, int roundedCount) {
        AdaptiveSolver.Result result = AdaptiveSolver.solveAfterRounding(
                normalMatrix, solution, fixedVariables,
                roundedVariables, roundedCount, adaptiveOptions);
        solution = result.x();
        AdaptiveSolver.Stats stats = result.stats();
        switch (stats.method()) {
        case LOCAL_GAUSS_SEIDEL -> localGsConverged++;
        case CONJUGATE_GRADIENT -> cgConverged++;
        case DIRECT -> directFallbacks++;
        case FAILED -> failedSolves++;
        }
        if (!stats.converged() && stats.method() != AdaptiveSolver.Method.FAILED) {
            failedSolves++;
        }
        if (stats.localHitCap()) {
            localGsCapHits++;
            if (stats.capResidualRow() >= 0 && stats.capResidualRow() < faceCount) {
                localCapFaceRows++;
            } else if (stats.capResidualRow() >= faceCount) {
                localCapChordRows++;
            }
            maxLocalCapResidual = Math.max(maxLocalCapResidual, stats.capResidualNorm());
        }
        totalLocalInitialQueueSize += stats.initialQueueSize();
        maxLocalQueueSize = Math.max(maxLocalQueueSize, stats.maxQueueSize());
        lastAdaptiveMethod = stats.method().name();
        lastAdaptiveResidual = stats.residualNorm();
        totalLocalGsIterations += stats.localIterations();
        totalCgIterations += stats.cgIterations();
        for (int fAi = 0; fAi < faceCount; fAi++) {
            solutionTheta[fAi] = (float) solution[fAi];
        }
        for (int eAi = 0; eAi < edgeCount; eAi++) {
            int chord = chordOfEdge[eAi];
            if (chord >= 0) {
                solutionPeriod[eAi] = (float) solution[faceCount + chord];
            } else {
                solutionPeriod[eAi] = vornoiForest.periodValue[eAi];
            }
        }
    }

    void unpackInto(HalfEdgeMesh mesh, CrossField cf) {
        cf.theta = solutionTheta.clone();
        cf.periodJump = new int[edgeCount];
        for (int eAi = 0; eAi < edgeCount; eAi++) {
            int eId = mesh.edgeIdAt(eAi);
            if (mesh.isBoundaryEdge(eId)) {
                cf.periodJump[eAi] = 0;
                continue;
            }
            int chord = chordOfEdge[eAi];
            if (chord >= 0) {

                cf.periodJump[eAi] = (int) Math.round(solutionPeriod[eAi]);
            } else {

                cf.periodJump[eAi] = vornoiForest.periodValue[eAi];
            }
        }
    }

    public final class BatchCandidate {
        final int chord;
        final int edgeAi;
        final double roundoff;

        BatchCandidate(int chord, int edgeAi, double roundoff) {
            this.chord = chord;
            this.edgeAi = edgeAi;
            this.roundoff = roundoff;
        }
    }
}