package ixdar.nodegraph.optimize;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonComparator.DetailedBranchMatch;

import ixdar.nodegraph.api.PortType;

import ixdar.nodegraph.api.MeshNodeSchema;

import ixdar.nodegraph.api.MeshNode;
import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonComparator.DetailedComparisonResult;
import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonComparator.JointDelta;
import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonExtractor.SkeletonResult;
import ixdar.geometry.mesh.data.io.MeshLoader;

import ixdar.nodegraph.optimize.InputParameterDescriptor.InputParameterKind;
import ixdar.numeric.sparse.DirectSolver;
import ixdar.numeric.sparse.matrix.NormalMatrix;
import ixdar.numeric.sparse.ordering.OrderingMethod;

import ixdar.nodegraph.runtime.GroupLibrary;
import ixdar.nodegraph.runtime.NodeGraphRuntime;

import ixdar.nodegraph.syntax.PythonParser;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonComparator;
import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonExtractor;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.PackedPoints;
/**
 * Skeleton-to-parameter sensitivity analyzer for any DSL mesh. Builds a finite-difference Jacobian
 * by perturbing each input parameter and remeasuring joint positions, then solves by damped least
 * squares for the parameter adjustments that best match a reference skeleton.
 */
public final class SkeletonSensitivityAnalyzer {
    public static final int MAX_BACKOFF_ATTEMPTS = 4;

    private static final float DEFAULT_EPSILON_RELATIVE = 0.02f; // 2% of parameter range
    private static final float MIN_EPSILON = 1e-3f;
    private static final float DAMPING_LAMBDA = 0.1f;


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

        List<OptimizableParameter> params = collectAllParameters(parsed);

        if (params.isEmpty()) {
            return new SensitivityResult(params, List.of(), new float[0][0][0],
                    0, List.of(), Map.of(), 0, List.of());
        }

        MeshTopology refMesh = MeshLoader.load(refMeshPath);
        SkeletonResult refSkel = MeshSkeletonExtractor.extract(refMesh, resolution);

        PythonParser.ParsedNode lastNode = parsed.get(parsed.size() - 1);
        List<String> ports = candidatePorts(parsed);

        NodeGraphRuntime runtime = GroupLibrary.load(parsed, Map.of());

        MeshTopology baselineMesh = executeDsl(runtime, lastNode.id, ports, Map.of());
        if (baselineMesh == null) {
            throw new IllegalStateException("Baseline DSL execution produced no mesh");
        }
        SkeletonResult baselineSkel = MeshSkeletonExtractor.extract(baselineMesh, resolution);
        DetailedComparisonResult baselineComparison = MeshSkeletonComparator.compareDetailed(baselineSkel, refSkel);

        List<BranchJointIndex> jointIndices = new ArrayList<>();
        List<JointDelta> baselineErrors = new ArrayList<>();
        List<Vector3f> baselinePositions = new ArrayList<>();
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

        float[][][] jacobian3D = new float[M][N][3];
        List<String> unstableParams = new ArrayList<>();

        for (int pi = 0; pi < N; pi++) {
            OptimizableParameter param = params.get(pi);
            float defaultVal = param.defaultValue();
            float eps = computeEpsilon(param, epsilon);

            Map<String, Object> overrides = new HashMap<>();
            overrides.put(param.overrideKey(), defaultVal + eps);

            try {
                MeshTopology perturbedMesh = executeDsl(runtime, lastNode.id, ports, overrides);
                if (perturbedMesh == null) {
                    unstableParams.add(param.overrideKey());
                    continue;
                }

                SkeletonResult perturbedSkel = MeshSkeletonExtractor.extract(perturbedMesh, resolution);
                DetailedComparisonResult perturbedComparison =
                        MeshSkeletonComparator.compareDetailed(perturbedSkel, refSkel);

                Map<String, List<Vector3f>> perturbedByLabel = new HashMap<>();
                for (DetailedBranchMatch dbm : perturbedComparison.detailedMatches()) {
                    if (dbm.summary().genBranchId() < 0) continue;
                    List<Vector3f> positions = new ArrayList<>();
                    for (JointDelta jd : dbm.jointDeltas()) {
                        positions.add(jd.genPosition());
                    }
                    perturbedByLabel.put(dbm.summary().label(), positions);
                }

                int matchedJoints = 0;
                float invEps = 1.0f / eps;
                for (var entry : branchRanges.entrySet()) {
                    String label = entry.getKey();
                    int[] range = entry.getValue();
                    List<Vector3f> perturbedPos = perturbedByLabel.get(label);
                    if (perturbedPos == null || perturbedPos.size() != (range[1] - range[0])) {
                        continue;
                    }
                    for (int ji = range[0]; ji < range[1]; ji++) {
                        Vector3f slope = new Vector3f(perturbedPos.get(ji - range[0]))
                                .sub(baselinePositions.get(ji)).mul(invEps);
                        PackedPoints.store(jacobian3D[ji][pi], 0, slope);
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

        Map<String, Float> suggestedDeltas = solveDampedLeastSquares(
                jacobian3D, baselineErrors, params, DAMPING_LAMBDA);

        float projectedScore = estimateProjectedScore(
                runtime, lastNode.id, ports, params, suggestedDeltas, refSkel, resolution);

        return new SensitivityResult(
                params, jointIndices, jacobian3D,
                baselineComparison.summary().skeletonScore(),
                baselineErrors, suggestedDeltas, projectedScore, unstableParams);
    }


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

        MeshTopology refMesh = MeshLoader.load(refMeshPath);
        SkeletonResult refSkel = MeshSkeletonExtractor.extract(refMesh, resolution);

        PythonParser.ParsedNode lastNode = parsed.get(parsed.size() - 1);
        List<String> ports = candidatePorts(parsed);

        NodeGraphRuntime runtime = GroupLibrary.load(parsed, Map.of());

        Map<String, Float> currentParams = new LinkedHashMap<>();
        for (OptimizableParameter p : params) {
            currentParams.put(p.overrideKey(), p.defaultValue());
        }

        List<OptimizationStep> steps = new ArrayList<>();
        float currentScore = evaluateScore(runtime, lastNode.id, ports, currentParams, refSkel, resolution);
        float initialScore = currentScore;

        steps.add(new OptimizationStep(0, currentScore, new LinkedHashMap<>(currentParams), 0));
        System.err.printf("Iteration 0: score=%.2f%%%n", currentScore);

        for (int iter = 1; iter <= maxIters; iter++) {
            if (currentScore >= targetScore) break;

            Map<String, Object> overridesBase = new HashMap<>();
            for (var entry : currentParams.entrySet()) {
                overridesBase.put(entry.getKey(), entry.getValue());
            }

            MeshTopology mesh = executeDsl(runtime, lastNode.id, ports, overridesBase);
            if (mesh == null) break;

            SkeletonResult skel = MeshSkeletonExtractor.extract(mesh, resolution);
            DetailedComparisonResult comparison = MeshSkeletonComparator.compareDetailed(skel, refSkel);

            List<Vector3f> basePositions = new ArrayList<>();
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

            float[][][] jacobian = new float[M][N][3];
            for (int pi = 0; pi < N; pi++) {
                OptimizableParameter param = params.get(pi);
                float currentVal = currentParams.get(param.overrideKey());
                float eps = computeEpsilon(param, DEFAULT_EPSILON_RELATIVE);

                Map<String, Object> overrides = new HashMap<>(overridesBase);
                overrides.put(param.overrideKey(), currentVal + eps);

                try {
                    MeshTopology perturbedMesh = executeDsl(runtime, lastNode.id, ports, overrides);
                    if (perturbedMesh == null) continue;

                    SkeletonResult perturbedSkel = MeshSkeletonExtractor.extract(perturbedMesh, resolution);
                    DetailedComparisonResult perturbedComparison =
                            MeshSkeletonComparator.compareDetailed(perturbedSkel, refSkel);

                    Map<String, List<Vector3f>> perturbedByLabel = new HashMap<>();
                    for (DetailedBranchMatch dbm : perturbedComparison.detailedMatches()) {
                        if (dbm.summary().genBranchId() < 0) continue;
                        List<Vector3f> positions = new ArrayList<>();
                        for (JointDelta jd : dbm.jointDeltas()) {
                            positions.add(jd.genPosition());
                        }
                        perturbedByLabel.put(dbm.summary().label(), positions);
                    }

                    float invEps = 1.0f / eps;
                    for (var brEntry : iterBranchRanges.entrySet()) {
                        String label = brEntry.getKey();
                        int[] range = brEntry.getValue();
                        List<Vector3f> perturbedPos = perturbedByLabel.get(label);
                        if (perturbedPos == null || perturbedPos.size() != (range[1] - range[0])) continue;
                        for (int ji = range[0]; ji < range[1]; ji++) {
                            Vector3f slope = new Vector3f(perturbedPos.get(ji - range[0]))
                                    .sub(basePositions.get(ji)).mul(invEps);
                            PackedPoints.store(jacobian[ji][pi], 0, slope);
                        }
                    }
                } catch (Exception e) {
                }
            }

            Map<String, Float> deltas = solveDampedLeastSquares(jacobian, errors, params, DAMPING_LAMBDA);

            Map<String, Float> candidateParams = new LinkedHashMap<>(currentParams);
            float stepSize = 1.0f;
            float candidateScore = 0;

            for (int attempt = 0; attempt < MAX_BACKOFF_ATTEMPTS; attempt++) {
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

                candidateScore = evaluateScore(runtime, lastNode.id, ports, candidateParams, refSkel, resolution);

                if (candidateScore > currentScore) break;
                stepSize *= 0.5f;
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
        int rows = M * 3;

        float[][] J = new float[rows][N];
        for (int ji = 0; ji < M; ji++) {
            for (int pi = 0; pi < N; pi++) {
                J[ji * 3][pi] = jacobian3D[ji][pi][0];
                J[ji * 3 + 1][pi] = jacobian3D[ji][pi][1];
                J[ji * 3 + 2][pi] = jacobian3D[ji][pi][2];
            }
        }

        float[] e = new float[rows];
        for (int ji = 0; ji < M; ji++) {
            JointDelta jd = errors.get(ji);
            PackedPoints.store(e, ji * 3, jd.delta());
        }

        double[] JTe = new double[N];
        for (int pi = 0; pi < N; pi++) {
            double sum = 0;
            for (int r = 0; r < rows; r++) {
                sum += J[r][pi] * e[r];
            }
            JTe[pi] = sum;
        }

        double[] diagonal = new double[N];
        long[] upperKeys = new long[N * (N - 1) / 2];
        double[] upperValues = new double[upperKeys.length];
        int slot = 0;
        for (int i = 0; i < N; i++) {
            for (int j = i; j < N; j++) {
                double sum = 0;
                for (int r = 0; r < rows; r++) {
                    sum += J[r][i] * J[r][j];
                }
                if (j == i) {
                    diagonal[i] = sum + lambda;
                } else {
                    upperKeys[slot] = ((long) i << NormalMatrix.KEY_ROW_SHIFT) | j;
                    upperValues[slot++] = sum;
                }
            }
        }

        double[] dp = DirectSolver.solve(new NormalMatrix(diagonal, upperKeys, upperValues, JTe), new double[N],
                new boolean[N], OrderingMethod.NATURAL);

        Map<String, Float> deltas = new LinkedHashMap<>();
        for (int pi = 0; pi < N; pi++) {
            OptimizableParameter param = params.get(pi);
            float delta = (float) dp[pi];
            float current = param.defaultValue();
            delta = Math.max(param.minValue() - current, Math.min(param.maxValue() - current, delta));
            deltas.put(param.overrideKey(), delta);
        }
        return deltas;
    }


    private static MeshTopology executeDsl(NodeGraphRuntime runtime,
            String lastNodeId,
            List<String> ports, Map<String, Object> overrides) throws Exception {
        for (String port : ports) {
            Object raw = runtime.executeGraphResult(lastNodeId, port, overrides);
            MeshTopology mesh = null;
            if (raw instanceof GeometryBundle gb) mesh = gb.mesh();
            if (mesh != null && mesh.vertexCount() > 0) return mesh;
        }
        return null;
    }

    private static float evaluateScore(NodeGraphRuntime runtime,
            String lastNodeId,
            List<String> ports, Map<String, Float> paramValues,
            SkeletonResult refSkel, int resolution) {
        try {
            Map<String, Object> overrides = new HashMap<>(paramValues);
            MeshTopology mesh = executeDsl(runtime, lastNodeId, ports, overrides);
            if (mesh == null) return 0;
            SkeletonResult skel = MeshSkeletonExtractor.extract(mesh, resolution);
            return MeshSkeletonComparator.compare(skel, refSkel).skeletonScore();
        } catch (Exception e) {
            return 0;
        }
    }

    private static float estimateProjectedScore(NodeGraphRuntime runtime,
            String lastNodeId,
            List<String> ports, List<OptimizableParameter> params,
            Map<String, Float> suggestedDeltas, SkeletonResult refSkel, int resolution) {
        try {
            Map<String, Float> projected = new LinkedHashMap<>();
            for (OptimizableParameter p : params) {
                float base = p.defaultValue();
                float delta = suggestedDeltas.getOrDefault(p.overrideKey(), 0f);
                projected.put(p.overrideKey(), Math.max(p.minValue(), Math.min(p.maxValue(), base + delta)));
            }
            return evaluateScore(runtime, lastNodeId, ports, projected, refSkel, resolution);
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
        PythonParser.ParsedNode last = parsed.get(parsed.size() - 1);
        Supplier<? extends MeshNode> supplier = NodeGraphRuntime.supplierFor(last.type);
        if (supplier != null) {
            MeshNodeSchema schema = MeshNodeSchema.from(supplier.get());
            List<String> names = new ArrayList<>();
            for (var op : schema.outputs()) {
                if (op.type == PortType.GEOMETRY_BUNDLE) {
                    names.add(op.name);
                }
            }
            if (!names.isEmpty()) return names;
        }
        return List.of("geometry", "mesh");
    }


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
