package ixdar.nodegraph.optimize;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.joml.Vector3f;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.MeshNodeSchema;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;

import ixdar.nodegraph.api.PortType;
import ixdar.geometry.mesh.data.analysis.MeshDistance;
import ixdar.geometry.mesh.data.representation.MeshTopology;

import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.nodegraph.runtime.GroupLibrary;
import ixdar.nodegraph.runtime.NodeGraphRuntime;

import ixdar.nodegraph.syntax.PythonLexer;
import ixdar.nodegraph.syntax.PythonParser;

/**
 * Batch DSL evaluator for parameter optimization. Two modes:
 * <ul>
 *   <li><b>Discover:</b> {@code BatchDslEvaluator <dsl-path> --discover} — outputs parameter space JSON</li>
 *   <li><b>Batch:</b> {@code BatchDslEvaluator <dsl-path> <output-dir> <params-json> [ref-obj]} — evaluates samples, exports OBJs, optionally compares against reference</li>
 * </ul>
 */
public final class BatchDslEvaluator {
    public static final String RESOLUTION = "--resolution";
    public static final String ID = "id";
    public static final String NAME = "name";
    public static final String DEFAULT = "default";
    public static final String MIN = "min";
    public static final String MAX = "max";
    public static final String PARAMETERS = "parameters";
    public static final String OK = "ok";
    public static final String ERROR = "error";
    public static final String REFERENCE_S_N = "  Reference: %s%n";
    public static final String BRANCH = "branch";
    public static final String AVGERROR = "avgError";
    public static final String JOINTS = "joints";
    public static final String IMPROVEMENT = "improvement";
    public static final int MIN_ARGS = 3;
    public static final int DEFAULT_RESOLUTION = 128;
    public static final int DEFAULT_MAX_ITERS = 10;
    public static final int DEFAULT_TARGET_SCORE = 95;
    public static final double ROUND_TO_ONE_DECIMAL = 10.0;

    private static final Set<String> NUMERIC_INPUT_NODE_TYPES = NodeGraphRuntime.INPUT_NODE_TYPES.stream()
            .filter(type -> NodeGraphRuntime.supplierFor(type).get().inputs().stream()
                    .anyMatch(port -> NodeGraphRuntime.DEFAULT_PORT.equals(port.name)
                            && (port.type == PortType.FLOAT || port.type == PortType.INT)))
            .collect(Collectors.toUnmodifiableSet());

    /**
     * CLI dispatcher. Parses {@code args[0]} as a DSL file, then routes on {@code args[1]}:
     * {@code --discover} to print the parameter space, {@code --skeleton-sensitivity} to compute
     * a parameter Jacobian against a reference OBJ, {@code --skeleton-optimize} to iteratively
     * minimize skeleton error, or otherwise treats {@code args[1..3]} as
     * {@code <output-dir> <params-json> [ref-obj]} for batch evaluation.
     *
     * @param args see usage printed by {@link #printUsage()}
     * @throws Exception on parse, IO, or graph-execution failure
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            printUsage();
            System.exit(2);
        }

        Path dslPath = Path.of(args[0]);
        if (!Files.exists(dslPath)) {
            System.err.println("DSL file not found: " + dslPath);
            System.exit(2);
        }

        PythonParser parser = new PythonParser(new PythonLexer(Files.readString(dslPath)));
        List<PythonParser.ParsedNode> parsed = parser.parseGraph();
        NodeGraphRuntime runtime = GroupLibrary.load(parsed, parser.functionDefs());
        Map<String, Supplier<? extends MeshNode>> registry = NodeGraphRuntime.REGISTERED_NODES;

        switch (args[1]) {
            case "--discover" -> discover(parsed, registry);
            case "--skeleton-sensitivity" -> {
                if (args.length < MIN_ARGS) {
                    System.err.println("skeleton-sensitivity requires: <dsl-path> --skeleton-sensitivity <ref-obj> [--resolution N] [--epsilon F]");
                    System.exit(2);
                }
                String refObj = args[2];
                int resolution = intFlag(args, RESOLUTION, DEFAULT_RESOLUTION);
                float epsilon = floatFlag(args, "--epsilon", 0);
                skeletonSensitivity(parsed, refObj, resolution, epsilon);
            }
            case "--skeleton-optimize" -> {
                if (args.length < MIN_ARGS) {
                    System.err.println("skeleton-optimize requires: <dsl-path> --skeleton-optimize <ref-obj> [--resolution N] [--max-iters N] [--target-score F]");
                    System.exit(2);
                }
                String refObj = args[2];
                int resolution = intFlag(args, RESOLUTION, DEFAULT_RESOLUTION);
                int maxIters = intFlag(args, "--max-iters", DEFAULT_MAX_ITERS);
                float targetScore = floatFlag(args, "--target-score", DEFAULT_TARGET_SCORE);
                skeletonOptimize(parsed, refObj, resolution, maxIters, targetScore);
            }
            default -> {
                if (args.length < MIN_ARGS) {
                    System.err.println("Batch mode requires: <dsl-path> <output-dir> <params-json> [ref-obj]");
                    System.exit(2);
                }
                String refObjPath = (args.length >= 4 && !"unused".equals(args[3])) ? args[3] : null;
                batch(runtime, registry, Path.of(args[1]), Path.of(args[2]), refObjPath);
            }
        }
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  BatchDslEvaluator <dsl-path> --discover");
        System.err.println("  BatchDslEvaluator <dsl-path> <output-dir> <params-json> [ref-obj]");
        System.err.println("  BatchDslEvaluator <dsl-path> --skeleton-sensitivity <ref-obj> [--resolution N] [--epsilon F]");
        System.err.println("  BatchDslEvaluator <dsl-path> --skeleton-optimize <ref-obj> [--resolution N] [--max-iters N] [--target-score F]");
    }

    private static void discover(List<PythonParser.ParsedNode> parsed,
            Map<String, Supplier<? extends MeshNode>> registry) {
        List<Map<String, Object>> params = new ArrayList<>();
        for (PythonParser.ParsedNode node : parsed) {
            if (!NUMERIC_INPUT_NODE_TYPES.contains(node.type)) continue;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put(ID, node.id);
            p.put("type", node.type);
            Object nameObj = node.arguments.get(NAME);
            p.put(NAME, nameObj != null ? String.valueOf(nameObj) : node.id);
            p.put(DEFAULT, numArg(node, DEFAULT));
            p.put(MIN, numArg(node, MIN));
            p.put(MAX, numArg(node, MAX));
            params.add(p);
        }

        // Find the last node for output
        String lastId = parsed.isEmpty() ? "" : parsed.get(parsed.size() - 1).id;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(PARAMETERS, params);
        result.put("outputNode", lastId);
        System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }

    private static void batch(NodeGraphRuntime runtime,
            Map<String, Supplier<? extends MeshNode>> registry,
            Path outputDir, Path paramsJson, String refObjPath) throws IOException {
        Files.createDirectories(outputDir);

        String paramsStr = Files.readString(paramsJson);
        JsonObject root = JsonParser.parseString(paramsStr).getAsJsonObject();
        JsonArray samples = root.getAsJsonArray("samples");
        if (samples == null || samples.isEmpty()) {
            System.err.println("No samples found in params JSON");
            System.exit(1);
        }

        // Pre-process reference mesh for comparison (if provided)
        // Normalize to unit bounding-box diagonal (same as viewer normalize=true)
        float[] normalizedRefPositions = null;
        if (refObjPath != null && !refObjPath.isEmpty()) {
            try {
                normalizedRefPositions = MeshDistance.normalizedPositions(MeshLoader.load(refObjPath));
                System.err.printf("Loaded reference mesh: %d verts (normalized)%n",
                        normalizedRefPositions.length / 3);
            } catch (Exception e) {
                System.err.println("Warning: could not load reference mesh: " + e.getMessage());
            }
        }

        // Find the output node and the ports its mesh may come out of.
        String lastId = runtime.lastId();
        Supplier<? extends MeshNode> lastSupplier = registry.get(
                runtime.root.declared.get(runtime.root.declared.size() - 1).type);
        List<String> ports = new ArrayList<>();
        if (lastSupplier != null) {
            for (var op : MeshNodeSchema.from(lastSupplier.get()).outputs()) {
                if (op.type == PortType.GEOMETRY_BUNDLE) {
                    ports.add(op.name);
                }
            }
        }
        if (ports.isEmpty()) {
            ports = List.of("geometry", "mesh");
        }

        List<Map<String, Object>> results = new ArrayList<>();

        for (int i = 0; i < samples.size(); i++) {
            JsonObject sample = samples.get(i).getAsJsonObject();
            Map<String, Object> overrides = new HashMap<>();
            Map<String, Object> paramValues = new LinkedHashMap<>();

            for (var entry : sample.entrySet()) {
                String nodeId = entry.getKey();
                JsonElement val = entry.getValue();
                if (val.isJsonPrimitive()) {
                    Number num = val.getAsNumber();
                    overrides.put(nodeId, num);
                    paramValues.put(nodeId, num);
                }
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            row.put("params", paramValues);

            try {
                MeshTopology mesh = null;
                for (String port : ports) {
                    Object raw = runtime.executeGraphResult(lastId, port, overrides);
                    if (raw instanceof GeometryBundle gb) mesh = gb.mesh();
                    if (mesh != null && mesh.vertexCount() > 0) break;
                }

                if (mesh == null || mesh.vertexCount() == 0) {
                    row.put(OK, false);
                    row.put(ERROR, "empty mesh");
                } else {
                    row.put(OK, true);
                    row.put("vertexCount", mesh.vertexCount());
                    row.put("faceCount", mesh.faceCount());

                    int boundaryEdges = 0;
                    for (int ei = 0; ei < mesh.edgeCount(); ei++) {
                        if (mesh.isBoundaryEdge(mesh.edgeIdAt(ei))) boundaryEdges++;
                    }
                    row.put("watertight", boundaryEdges == 0);

                    // Inline mesh comparison against reference (if available)
                    // Uses exact same path as viewer: normalize + computeAllMetrics(scale=1.0)
                    if (normalizedRefPositions != null) {
                        MeshDistance.MeshMetrics metrics = MeshDistance.computeAllMetrics(
                                MeshDistance.normalizedPositions(mesh), normalizedRefPositions, 1.0f);
                        row.put("similarity", Math.round(metrics.similarityScore * ROUND_TO_ONE_DECIMAL) / ROUND_TO_ONE_DECIMAL);
                        row.put("hausdorff", metrics.hausdorffDistance);
                        row.put("chamfer", metrics.chamferDistance);
                    } else {
                        // No ref — still write OBJ for external comparison
                        Path objPath = outputDir.resolve(String.format("sample_%04d.obj", i));
                        writeObj(mesh, objPath);
                        row.put("objPath", objPath.toString());
                    }
                }
            } catch (Exception e) {
                row.put(OK, false);
                row.put(ERROR, e.getMessage());
            }

            results.add(row);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("sampleCount", samples.size());
        output.put("results", results);
        System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(output));
    }

    // ─── Skeleton sensitivity mode ───

    private static void skeletonSensitivity(List<PythonParser.ParsedNode> parsed,
            String refObjPath, int resolution, float epsilon) throws Exception {
        System.err.println("Computing skeleton sensitivity...");
        System.err.printf(REFERENCE_S_N, refObjPath);
        System.err.printf("  Resolution: %d%n", resolution);

        SkeletonSensitivityAnalyzer.SensitivityResult result =
                SkeletonSensitivityAnalyzer.analyze(parsed, refObjPath, resolution, epsilon);

        // Build output JSON
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("baselineScore", result.baselineScore());
        output.put("projectedScore", result.projectedScore());
        output.put("parameterCount", result.parameters().size());
        output.put("jointCount", result.jointIndices().size());

        // Parameters with sensitivity info
        List<Map<String, Object>> paramList = new ArrayList<>();
        for (int pi = 0; pi < result.parameters().size(); pi++) {
            OptimizableParameter p = result.parameters().get(pi);
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put(ID, p.overrideKey());
            pm.put(NAME, p.displayName());
            pm.put(DEFAULT, p.defaultValue());
            pm.put("literal", p.isLiteral());
            pm.put("suggestedDelta", result.suggestedDeltas().getOrDefault(p.overrideKey(), 0f));
            float totalSens = 0;
            for (int ji = 0; ji < result.jointIndices().size(); ji++) {
                float[] j = result.jacobian3D()[ji][pi];
                totalSens += (float) Math.sqrt(j[0] * j[0] + j[1] * j[1] + j[2] * j[2]);
            }
            pm.put("totalSensitivity", totalSens);
            paramList.add(pm);
        }
        output.put(PARAMETERS, paramList);

        // Suggested new values
        Map<String, Object> suggestedValues = new LinkedHashMap<>();
        for (OptimizableParameter p : result.parameters()) {
            float base = p.defaultValue();
            float delta = result.suggestedDeltas().getOrDefault(p.overrideKey(), 0f);
            suggestedValues.put(p.overrideKey(), base + delta);
        }
        output.put("suggestedValues", suggestedValues);

        // Baseline errors per branch
        List<Map<String, Object>> branchErrors = new ArrayList<>();
        String currentBranch = null;
        float branchTotalErr = 0;
        int branchJointCount = 0;
        for (int ji = 0; ji < result.jointIndices().size(); ji++) {
            SkeletonSensitivityAnalyzer.BranchJointIndex bji = result.jointIndices().get(ji);
            if (currentBranch != null && !currentBranch.equals(bji.branchLabel())) {
                Map<String, Object> be = new LinkedHashMap<>();
                be.put(BRANCH, currentBranch);
                be.put(AVGERROR, branchTotalErr / branchJointCount);
                be.put(JOINTS, branchJointCount);
                branchErrors.add(be);
                branchTotalErr = 0;
                branchJointCount = 0;
            }
            currentBranch = bji.branchLabel();
            branchTotalErr += result.baselineErrors().get(ji).distance();
            branchJointCount++;
        }
        if (currentBranch != null) {
            Map<String, Object> be = new LinkedHashMap<>();
            be.put(BRANCH, currentBranch);
            be.put(AVGERROR, branchJointCount > 0 ? branchTotalErr / branchJointCount : 0);
            be.put(JOINTS, branchJointCount);
            branchErrors.add(be);
        }
        output.put("branchErrors", branchErrors);

        if (!result.unstableParams().isEmpty()) {
            output.put("unstableParams", result.unstableParams());
        }

        System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(output));
    }

    // ─── Skeleton optimization mode ───

    private static void skeletonOptimize(List<PythonParser.ParsedNode> parsed,
            String refObjPath, int resolution, int maxIters, float targetScore) throws Exception {
        System.err.println("Running skeleton optimization...");
        System.err.printf(REFERENCE_S_N, refObjPath);
        System.err.printf("  Resolution: %d, Max iterations: %d, Target: %.1f%%%n",
                resolution, maxIters, targetScore);

        SkeletonSensitivityAnalyzer.OptimizationResult result =
                SkeletonSensitivityAnalyzer.optimize(parsed, refObjPath, resolution, maxIters, targetScore);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("initialScore", result.initialScore());
        output.put("finalScore", result.finalScore());
        output.put(IMPROVEMENT, result.finalScore() - result.initialScore());
        output.put("iterations", result.steps().size());

        // Final parameter values
        output.put("finalParams", result.finalParams());

        // Step history
        List<Map<String, Object>> stepList = new ArrayList<>();
        for (SkeletonSensitivityAnalyzer.OptimizationStep step : result.steps()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("iteration", step.iteration());
            sm.put("score", step.score());
            sm.put(IMPROVEMENT, step.improvement());
            stepList.add(sm);
        }
        output.put("steps", stepList);

        System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(output));
    }

    // ─── CLI flag parsing ───

    private static int intFlag(String[] args, String flag, int defaultVal) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) {
                try { return Integer.parseInt(args[i + 1]); } catch (NumberFormatException e) { return defaultVal; }
            }
        }
        return defaultVal;
    }

    private static float floatFlag(String[] args, String flag, float defaultVal) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) {
                try { return Float.parseFloat(args[i + 1]); } catch (NumberFormatException e) { return defaultVal; }
            }
        }
        return defaultVal;
    }

    private static Object numArg(PythonParser.ParsedNode node, String key) {
        Object v = node.arguments.get(key);
        if (v instanceof Number n) return n;
        if (v instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
        }
        return v;
    }

    private static void writeObj(MeshTopology mesh, Path out) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(out)) {
            Vector3f p = new Vector3f();
            HashMap<Integer, Integer> vidToIdx = new HashMap<>();
            for (int i = 0; i < mesh.vertexCount(); i++) {
                int vid = mesh.vertexIdAt(i);
                vidToIdx.put(vid, i + 1);
                mesh.vertexPosition(vid, p);
                w.write(String.format("v %f %f %f", p.x, p.y, p.z));
                w.newLine();
            }
            for (int i = 0; i < mesh.faceCount(); i++) {
                int fid = mesh.faceIdAt(i);
                int fc = mesh.faceVertexCount(fid);
                w.write("f");
                for (int k = 0; k < fc; k++) {
                    Integer idx = vidToIdx.get(mesh.faceVertexAt(fid, k));
                    if (idx != null) w.write(" " + idx);
                }
                w.newLine();
            }
        }
    }
}
