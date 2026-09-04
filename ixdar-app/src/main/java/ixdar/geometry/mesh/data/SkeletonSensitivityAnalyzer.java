package ixdar.geometry.mesh.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ixdar.geometry.mesh.data.MeshSkeletonComparator.DetailedBranchMatch;

import ixdar.geometry.mesh.nodes.api.PortType;

import ixdar.geometry.mesh.nodes.api.MeshNodeSchema;

import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.data.MeshSkeletonComparator.DetailedComparisonResult;
import ixdar.geometry.mesh.data.MeshSkeletonComparator.JointDelta;
import ixdar.geometry.mesh.data.MeshSkeletonExtractor.SkeletonResult;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.ArrayMeshEngine;
import ixdar.geometry.mesh.graph.InputParameterDescriptor;
import ixdar.geometry.mesh.graph.InputParameterDescriptor.InputParameterKind;
import ixdar.geometry.mesh.graph.LiteralParameterDescriptor;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.graph.OptimizableParameter;
import ixdar.parsing.python.PythonParser;

/**
 * Skeleton-to-parameter sensitivity analyzer for any DSL mesh. Builds a finite-difference Jacobian
 * by perturbing each input parameter and remeasuring joint positions, then solves by damped least
 * squares for the parameter adjustments that best match a reference skeleton.
 */
public final class SkeletonSensitivityAnalyzer {
    public static final int NUM_3 = 3;
    public static final int NUM_4 = 4;
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_1e_12 = 1e-12f;
    public static final float NUM_0 = 0f;

    private static final float DEFAULT_EPSILON_RELATIVE = 0.02f; // 2% of parameter range
    private static final float MIN_EPSILON = 1e-3f;
    private static final float DAMPING_LAMBDA = 0.1f;

    // ─── Jacobian computation ───

    /**
     * Compute the sensitivity matrix (Jacobian) of skeleton joint positions
     * with respect to DSL parameters.
     *
     * @param parsed        parsed DSL statements
     * @param refMeshPath   path to reference OBJ file
     * @param resolution    TEASAR voxel resolution
     * @param epsilon       relative perturbation size (0 = use default 0.5%)
     * @throws Exception if DSL execution, mesh load, or skeleton extraction fails
     * @throws IllegalStateException if the unperturbed DSL run produces no mesh
     * @return sensitivity result with Jacobian, baseline errors, and suggested deltas
     */
    public static SensitivityResult analyze(
            List<PythonParser.ParsedNode> parsed,
            String refMeshPath,
            int resolution,
            float epsilon) throws Exception {

        if (epsilon <= 0) epsilon = DEFAULT_EPSILON_RELATIVE;

        // 1. Discover parameters (input nodes + literal arguments)
        List<OptimizableParameter> params = collectAllParameters(parsed);

        if (params.isEmpty()) {
            return new SensitivityResult(params, List.of(), new float[0][0][0],
                    0, List.of(), Map.of(), 0, List.of());
        }

        // 2. Load reference skeleton
        ArrayMesh refMesh = MeshLoader.load(refMeshPath);
        SkeletonResult refSkel = MeshSkeletonExtractor.extract(refMesh, resolution);

        // 3. Find output node and ports (same pattern as BatchDslEvaluator)
        PythonParser.ParsedNode lastNode = parsed.get(parsed.size() - 1);
        List<String> ports = candidatePorts(parsed);

        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();

        // 4. Execute baseline (all defaults)
        MeshTopology baselineMesh = executeDsl(runtime, parsed, lastNode.id, ports, Map.of());
        if (baselineMesh == null) {
            throw new IllegalStateException("Baseline DSL execution produced no mesh");
        }
        ArrayMesh baselineArrayMesh = ArrayMeshEngine.fromUniformMeshTopology(baselineMesh);
        SkeletonResult baselineSkel = MeshSkeletonExtractor.extract(baselineArrayMesh, resolution);
        DetailedComparisonResult baselineComparison = MeshSkeletonComparator.compareDetailed(baselineSkel, refSkel);

        // 5. Flatten baseline joint positions and errors across all matched branches.
        //    Index by branch label so perturbed comparisons can match correctly even
        //    if branch ordering differs.
        List<BranchJointIndex> jointIndices = new ArrayList<>();
        List<JointDelta> baselineErrors = new ArrayList<>();
        List<float[]> baselinePositions = new ArrayList<>();
        // Maps branch label → range [startIdx, endIdx) in the flattened joint list
        Map<String, int[]> branchRanges = new LinkedHashMap<>();

        for (int bi = 0; bi < baselineComparison.detailedMatches().size(); bi++) {
            DetailedBranchMatch dbm = baselineComparison.detailedMatches().get(bi);
            if (dbm.summary().genBranchId() < 0) continue; // skip unmatched
            int startIdx = jointIndices.size();
            for (JointDelta jd : dbm.jointDeltas()) {
                jointIndices.add(new BranchJointIndex(bi, jd.jointIndex(), dbm.summary().label()));
                baselineErrors.add(jd);
                baselinePositions.add(jd.genPosition());
            }
            branchRanges.put(dbm.summary().label(), new int[]{startIdx, jointIndices.size()});
        }

        int M = jointIndices.size(); // total joints
        int N = params.size();       // total params

        if (M == 0) {
            return new SensitivityResult(params, jointIndices, new float[0][0][0],
                    baselineComparison.summary().skeletonScore(),
                    baselineErrors, Map.of(),
                    baselineComparison.summary().skeletonScore(), List.of());
        }

        // 6. Compute Jacobian: perturb each parameter.
        //    Match perturbed branches to baseline branches BY LABEL (not iteration order)
        //    to handle cases where greedy nearest-tip matching produces different orderings.
        float[][][] jacobian3D = new float[M][N][NUM_3];
        List<String> unstableParams = new ArrayList<>();

        for (int pi = 0; pi < N; pi++) {
            OptimizableParameter param = params.get(pi);
            float defaultVal = param.defaultValue();
            float eps = computeEpsilon(param, epsilon);

            Map<String, Object> overrides = new HashMap<>();
            overrides.put(param.overrideKey(), defaultVal + eps);

            try {
                MeshTopology perturbedMesh = executeDsl(runtime, parsed, lastNode.id, ports, overrides);
                if (perturbedMesh == null) {
                    unstableParams.add(param.overrideKey());
                    continue;
                }

                ArrayMesh perturbedArrayMesh = ArrayMeshEngine.fromUniformMeshTopology(perturbedMesh);
                SkeletonResult perturbedSkel = MeshSkeletonExtractor.extract(perturbedArrayMesh, resolution);
                DetailedComparisonResult perturbedComparison =
                        MeshSkeletonComparator.compareDetailed(perturbedSkel, refSkel);

                // Build label → perturbed gen positions map
                Map<String, List<float[]>> perturbedByLabel = new HashMap<>();
                for (DetailedBranchMatch dbm : perturbedComparison.detailedMatches()) {
                    if (dbm.summary().genBranchId() < 0) continue;
                    List<float[]> positions = new ArrayList<>();
                    for (JointDelta jd : dbm.jointDeltas()) {
                        positions.add(jd.genPosition());
                    }
                    perturbedByLabel.put(dbm.summary().label(), positions);
                }

                // Match branches by label and compute Jacobian entries
                int matchedJoints = 0;
                float invEps = 1.0f / eps;
                for (var entry : branchRanges.entrySet()) {
                    String label = entry.getKey();
                    int[] range = entry.getValue();
                    List<float[]> perturbedPos = perturbedByLabel.get(label);
                    if (perturbedPos == null || perturbedPos.size() != (range[1] - range[0])) {
                        continue;
                    }
                    for (int ji = range[0]; ji < range[1]; ji++) {
                        float[] bp = baselinePositions.get(ji);
                        float[] pp = perturbedPos.get(ji - range[0]);
                        jacobian3D[ji][pi][0] = (pp[0] - bp[0]) * invEps;
                        jacobian3D[ji][pi][1] = (pp[1] - bp[1]) * invEps;
                        jacobian3D[ji][pi][2] = (pp[2] - bp[2]) * invEps;
                        matchedJoints++;
                    }
                }

                if (matchedJoints < M / 2) {
                    unstableParams.add(param.overrideKey());
                }
            } catch (Exception e) {
                unstableParams.add(param.overrideKey());
            }
        }

        // 7. Compute suggested parameter deltas via damped least-squares
        Map<String, Float> suggestedDeltas = solveDampedLeastSquares(
                jacobian3D, baselineErrors, params, DAMPING_LAMBDA);

        // 8. Estimate projected score by applying suggested deltas
        float projectedScore = estimateProjectedScore(
                runtime, parsed, lastNode.id, ports, params, suggestedDeltas, refSkel, resolution);

        return new SensitivityResult(
                params, jointIndices, jacobian3D,
                baselineComparison.summary().skeletonScore(),
                baselineErrors, suggestedDeltas, projectedScore, unstableParams);
    }

    // ─── Iterative Gauss-Newton optimization ───

    /**
     * Run iterative skeleton optimization by repeatedly computing the Jacobian
     * and applying damped least-squares updates.
     *
     * @param parsed parsed DSL statements defining the mesh graph
     * @param refMeshPath path to the reference OBJ whose skeleton drives the loss
     * @param resolution TEASAR voxel resolution used for skeleton extraction
     * @param maxIters maximum Gauss-Newton iterations
     * @param targetScore early-stop skeleton-similarity score (in percent)
     * @throws Exception if DSL execution, mesh load, or skeleton extraction fails
     * @return trajectory of iterations plus the final parameter map and scores
     */
    public static OptimizationResult optimize(
            List<PythonParser.ParsedNode> parsed,
            String refMeshPath,
            int resolution,
            int maxIters,
            float targetScore) throws Exception {

        List<OptimizableParameter> params = collectAllParameters(parsed);

        ArrayMesh refMesh = MeshLoader.load(refMeshPath);
        SkeletonResult refSkel = MeshSkeletonExtractor.extract(refMesh, resolution);

        PythonParser.ParsedNode lastNode = parsed.get(parsed.size() - 1);
        List<String> ports = candidatePorts(parsed);

        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();

        // Current parameter values (start at defaults)
        Map<String, Float> currentParams = new LinkedHashMap<>();
        for (OptimizableParameter p : params) {
            currentParams.put(p.overrideKey(), p.defaultValue());
        }

        List<OptimizationStep> steps = new ArrayList<>();
        float currentScore = evaluateScore(runtime, parsed, lastNode.id, ports, currentParams, refSkel, resolution);
        float initialScore = currentScore;

        steps.add(new OptimizationStep(0, currentScore, new LinkedHashMap<>(currentParams), 0));
        System.err.printf("Iteration 0: score=%.2f%%%n", currentScore);

        for (int iter = 1; iter <= maxIters; iter++) {
            if (currentScore >= targetScore) break;

            // Build overrides map from current params
            Map<String, Object> overridesBase = new HashMap<>();
            for (var entry : currentParams.entrySet()) {
                overridesBase.put(entry.getKey(), entry.getValue());
            }

            // Execute at current params
            MeshTopology mesh = executeDsl(runtime, parsed, lastNode.id, ports, overridesBase);
            if (mesh == null) break;

            ArrayMesh arrayMesh = ArrayMeshEngine.fromUniformMeshTopology(mesh);
            SkeletonResult skel = MeshSkeletonExtractor.extract(arrayMesh, resolution);
            DetailedComparisonResult comparison = MeshSkeletonComparator.compareDetailed(skel, refSkel);

            // Flatten joint positions and errors, indexed by branch label
            List<float[]> basePositions = new ArrayList<>();
            List<JointDelta> errors = new ArrayList<>();
            Map<String, int[]> iterBranchRanges = new LinkedHashMap<>();
            for (DetailedBranchMatch dbm : comparison.detailedMatches()) {
                if (dbm.summary().genBranchId() < 0) continue;
                int start = basePositions.size();
                for (JointDelta jd : dbm.jointDeltas()) {
                    basePositions.add(jd.genPosition());
                    errors.add(jd);
                }
                iterBranchRanges.put(dbm.summary().label(), new int[]{start, basePositions.size()});
            }

            int M = basePositions.size();
            int N = params.size();
            if (M == 0) break;

            // Compute Jacobian at current parameter values (label-matched)
            float[][][] jacobian = new float[M][N][NUM_3];
            for (int pi = 0; pi < N; pi++) {
                OptimizableParameter param = params.get(pi);
                float currentVal = currentParams.get(param.overrideKey());
                float eps = computeEpsilon(param, DEFAULT_EPSILON_RELATIVE);

                Map<String, Object> overrides = new HashMap<>(overridesBase);
                overrides.put(param.overrideKey(), currentVal + eps);

                try {
                    MeshTopology perturbedMesh = executeDsl(runtime, parsed, lastNode.id, ports, overrides);
                    if (perturbedMesh == null) continue;

                    ArrayMesh perturbedArrayMesh = ArrayMeshEngine.fromUniformMeshTopology(perturbedMesh);
                    SkeletonResult perturbedSkel = MeshSkeletonExtractor.extract(perturbedArrayMesh, resolution);
                    DetailedComparisonResult perturbedComparison =
                            MeshSkeletonComparator.compareDetailed(perturbedSkel, refSkel);

                    // Build label → perturbed gen positions map
                    Map<String, List<float[]>> perturbedByLabel = new HashMap<>();
                    for (DetailedBranchMatch dbm : perturbedComparison.detailedMatches()) {
                        if (dbm.summary().genBranchId() < 0) continue;
                        List<float[]> positions = new ArrayList<>();
                        for (JointDelta jd : dbm.jointDeltas()) {
                            positions.add(jd.genPosition());
                        }
                        perturbedByLabel.put(dbm.summary().label(), positions);
                    }

                    // Match by label
                    float invEps = 1.0f / eps;
                    for (var brEntry : iterBranchRanges.entrySet()) {
                        String label = brEntry.getKey();
                        int[] range = brEntry.getValue();
                        List<float[]> perturbedPos = perturbedByLabel.get(label);
                        if (perturbedPos == null || perturbedPos.size() != (range[1] - range[0])) continue;
                        for (int ji = range[0]; ji < range[1]; ji++) {
                            float[] bp = basePositions.get(ji);
                            float[] pp = perturbedPos.get(ji - range[0]);
                            jacobian[ji][pi][0] = (pp[0] - bp[0]) * invEps;
                            jacobian[ji][pi][1] = (pp[1] - bp[1]) * invEps;
                            jacobian[ji][pi][2] = (pp[2] - bp[2]) * invEps;
                        }
                    }
                } catch (Exception e) {
                    // skip this parameter for this iteration
                }
            }

            // Solve for parameter updates
            Map<String, Float> deltas = solveDampedLeastSquares(jacobian, errors, params, DAMPING_LAMBDA);

            // Apply updates with step-size backoff
            Map<String, Float> candidateParams = new LinkedHashMap<>(currentParams);
            float stepSize = 1.0f;
            float candidateScore = 0;

            for (int attempt = 0; attempt < NUM_4; attempt++) {
                for (var entry : deltas.entrySet()) {
                    String paramKey = entry.getKey();
                    float delta = entry.getValue() * stepSize;
                    float newVal = currentParams.get(paramKey) + delta;
                    OptimizableParameter desc = findParam(params, paramKey);
                    if (desc != null) {
                        newVal = Math.max(desc.minValue(), Math.min(desc.maxValue(), newVal));
                    }
                    candidateParams.put(paramKey, newVal);
                }

                candidateScore = evaluateScore(runtime, parsed, lastNode.id, ports, candidateParams, refSkel, resolution);

                if (candidateScore > currentScore) break;
                stepSize *= NUM_0_5;
                candidateParams = new LinkedHashMap<>(currentParams);
            }

            if (candidateScore > currentScore) {
                float improvement = candidateScore - currentScore;
                currentParams = candidateParams;
                currentScore = candidateScore;
                steps.add(new OptimizationStep(iter, currentScore, new LinkedHashMap<>(currentParams), improvement));
                System.err.printf("Iteration %d: score=%.2f%% (+%.2f)%n", iter, currentScore, improvement);
            } else {
                steps.add(new OptimizationStep(iter, currentScore, new LinkedHashMap<>(currentParams), 0));
                System.err.printf("Iteration %d: no improvement (score=%.2f%%)%n", iter, currentScore);
                break; // converged
            }
        }

        return new OptimizationResult(steps, currentParams, initialScore, currentScore);
    }

    // ─── Damped least-squares solver ───

    /**
     * Solve delta_params = J^T * (J * J^T + lambda * I)^{-1} * (-error)
     * for the parameter update that minimizes joint position errors.
     *
     * @param jacobian3D per-joint, per-parameter, per-axis sensitivities ({@code [M][N][3]})
     * @param errors flattened baseline joint errors (one entry per row of {@code jacobian3D})
     * @param params parameter descriptors aligned with the second axis of {@code jacobian3D}
     * @param lambda Tikhonov damping added to the diagonal of {@code J^T J}
     * @return parameter delta keyed by {@link OptimizableParameter#overrideKey()}, clamped to each parameter's range
     */
    private static Map<String, Float> solveDampedLeastSquares(
            float[][][] jacobian3D, List<JointDelta> errors,
            List<OptimizableParameter> params, float lambda) {

        int M = errors.size();
        int N = params.size();
        int rows = M * NUM_3;

        float[][] J = new float[rows][N];
        for (int ji = 0; ji < M; ji++) {
            for (int pi = 0; pi < N; pi++) {
                J[ji * NUM_3][pi] = jacobian3D[ji][pi][0];
                J[ji * NUM_3 + 1][pi] = jacobian3D[ji][pi][1];
                J[ji * NUM_3 + 2][pi] = jacobian3D[ji][pi][2];
            }
        }

        float[] e = new float[rows];
        for (int ji = 0; ji < M; ji++) {
            JointDelta jd = errors.get(ji);
            e[ji * NUM_3] = jd.delta()[0];
            e[ji * NUM_3 + 1] = jd.delta()[1];
            e[ji * NUM_3 + 2] = jd.delta()[2];
        }

        float[] JTe = new float[N];
        for (int pi = 0; pi < N; pi++) {
            float sum = 0;
            for (int r = 0; r < rows; r++) {
                sum += J[r][pi] * e[r];
            }
            JTe[pi] = sum;
        }

        float[][] JTJ = new float[N][N];
        for (int i = 0; i < N; i++) {
            for (int j = 0; j < N; j++) {
                float sum = 0;
                for (int r = 0; r < rows; r++) {
                    sum += J[r][i] * J[r][j];
                }
                JTJ[i][j] = sum;
            }
            JTJ[i][i] += lambda;
        }

        float[] dp = solveLinearSystem(JTJ, JTe, N);

        Map<String, Float> deltas = new LinkedHashMap<>();
        for (int pi = 0; pi < N; pi++) {
            OptimizableParameter param = params.get(pi);
            float delta = dp[pi];
            float current = param.defaultValue();
            delta = Math.max(param.minValue() - current, Math.min(param.maxValue() - current, delta));
            deltas.put(param.overrideKey(), delta);
        }
        return deltas;
    }

    /**
     * Solve Ax = b via Gaussian elimination with partial pivoting.
     *
     * @param A square coefficient matrix of size {@code n x n}
     * @param b right-hand-side vector of length {@code n}
     * @param n system dimension
     * @return solution vector {@code x}; entries with a singular pivot are zeroed
     */
    private static float[] solveLinearSystem(float[][] A, float[] b, int n) {
        // Augmented matrix
        float[][] aug = new float[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, aug[i], 0, n);
            aug[i][n] = b[i];
        }

        // Forward elimination with partial pivoting
        for (int col = 0; col < n; col++) {
            // Find pivot
            int maxRow = col;
            float maxVal = Math.abs(aug[col][col]);
            for (int row = col + 1; row < n; row++) {
                float val = Math.abs(aug[row][col]);
                if (val > maxVal) { maxVal = val; maxRow = row; }
            }
            // Swap rows
            float[] tmp = aug[col];
            aug[col] = aug[maxRow];
            aug[maxRow] = tmp;

            float pivot = aug[col][col];
            if (Math.abs(pivot) < NUM_1e_12) continue; // singular column

            for (int row = col + 1; row < n; row++) {
                float factor = aug[row][col] / pivot;
                for (int j = col; j <= n; j++) {
                    aug[row][j] -= factor * aug[col][j];
                }
            }
        }

        // Back substitution
        float[] x = new float[n];
        for (int i = n - 1; i >= 0; i--) {
            float sum = aug[i][n];
            for (int j = i + 1; j < n; j++) {
                sum -= aug[i][j] * x[j];
            }
            float diag = aug[i][i];
            x[i] = Math.abs(diag) > NUM_1e_12 ? sum / diag : 0;
        }
        return x;
    }

    // ─── Helper methods ───

    private static MeshTopology executeDsl(NodeGraphRuntime runtime,
            List<PythonParser.ParsedNode> parsed, String lastNodeId,
            List<String> ports, Map<String, Object> overrides) throws Exception {
        for (String port : ports) {
            Object raw = runtime.executeGraphResult(parsed, lastNodeId, port, overrides);
            MeshTopology mesh = null;
            if (raw instanceof GeometryBundle gb) mesh = gb.mesh();
            if (mesh != null && mesh.vertexCount() > 0) return mesh;
        }
        return null;
    }

    private static float evaluateScore(NodeGraphRuntime runtime,
            List<PythonParser.ParsedNode> parsed, String lastNodeId,
            List<String> ports, Map<String, Float> paramValues,
            SkeletonResult refSkel, int resolution) {
        try {
            Map<String, Object> overrides = new HashMap<>(paramValues);
            MeshTopology mesh = executeDsl(runtime, parsed, lastNodeId, ports, overrides);
            if (mesh == null) return 0;
            ArrayMesh arrayMesh = ArrayMeshEngine.fromUniformMeshTopology(mesh);
            SkeletonResult skel = MeshSkeletonExtractor.extract(arrayMesh, resolution);
            return MeshSkeletonComparator.compare(skel, refSkel).skeletonScore();
        } catch (Exception e) {
            return 0;
        }
    }

    private static float estimateProjectedScore(NodeGraphRuntime runtime,
            List<PythonParser.ParsedNode> parsed, String lastNodeId,
            List<String> ports, List<OptimizableParameter> params,
            Map<String, Float> suggestedDeltas, SkeletonResult refSkel, int resolution) {
        try {
            Map<String, Float> projected = new LinkedHashMap<>();
            for (OptimizableParameter p : params) {
                float base = p.defaultValue();
                float delta = suggestedDeltas.getOrDefault(p.overrideKey(), NUM_0);
                projected.put(p.overrideKey(), Math.max(p.minValue(), Math.min(p.maxValue(), base + delta)));
            }
            return evaluateScore(runtime, parsed, lastNodeId, ports, projected, refSkel, resolution);
        } catch (Exception e) {
            return 0;
        }
    }

    private static float computeEpsilon(OptimizableParameter param, float epsilonRelative) {
        float range = param.maxValue() - param.minValue();
        if (Float.isInfinite(range) || range <= 0) {
            return Math.max(MIN_EPSILON, Math.abs(param.defaultValue()) * epsilonRelative);
        }
        return Math.max(MIN_EPSILON, range * epsilonRelative);
    }

    private static List<OptimizableParameter> collectAllParameters(List<PythonParser.ParsedNode> parsed) {
        List<OptimizableParameter> params = new ArrayList<>();
        for (InputParameterDescriptor p : InputParameterDescriptor.collect(parsed)) {
            if (p.kind() == InputParameterKind.FLOAT || p.kind() == InputParameterKind.INT) {
                params.add(OptimizableParameter.fromInput(p));
            }
        }
        for (LiteralParameterDescriptor p : LiteralParameterDescriptor.collect(parsed)) {
            params.add(OptimizableParameter.fromLiteral(p));
        }
        return params;
    }

    private static OptimizableParameter findParam(List<OptimizableParameter> params, String key) {
        for (OptimizableParameter p : params) {
            if (p.overrideKey().equals(key)) return p;
        }
        return null;
    }

    private static List<String> candidatePorts(List<PythonParser.ParsedNode> parsed) {
        // Try to detect output port from node schema, fall back to common names
        PythonParser.ParsedNode last = parsed.get(parsed.size() - 1);
        Map<String, Class<? extends MeshNode>> registry =
                NodeGraphRuntime.annotationRegistryClasses();
        Class<? extends MeshNode> clazz = registry.get(last.type);
        if (clazz != null) {
            try {
                MeshNode instance = clazz.getDeclaredConstructor().newInstance();
                MeshNodeSchema schema =
                        MeshNodeSchema.from(instance);
                List<String> names = new ArrayList<>();
                for (var op : schema.outputs()) {
                    if (op.type == PortType.GEOMETRY_BUNDLE) {
                        names.add(op.name);
                    }
                }
                if (!names.isEmpty()) return names;
            } catch (ReflectiveOperationException ignored) {}
        }
        return List.of("geometry", "mesh");
    }

    // ─── Output records ───

    /** Index of a single joint within the flattened list across all matched branches. */
    public record BranchJointIndex(int branchMatchIndex, int jointIndex, String branchLabel) {}

    /** Full sensitivity analysis result. */
    public record SensitivityResult(
            List<OptimizableParameter> parameters,
            List<BranchJointIndex> jointIndices,
            float[][][] jacobian3D,           // [M joints][N params][3 dims]
            float baselineScore,
            List<JointDelta> baselineErrors,  // flattened across all branches
            Map<String, Float> suggestedDeltas,
            float projectedScore,
            List<String> unstableParams       // params that caused topology changes
    ) {}

    /** Result of a single optimization iteration. */
    public record OptimizationStep(
            int iteration,
            float score,
            Map<String, Float> paramValues,
            float improvement
    ) {}

    /** Full optimization trajectory. */
    public record OptimizationResult(
            List<OptimizationStep> steps,
            Map<String, Float> finalParams,
            float initialScore,
            float finalScore
    ) {}
}
