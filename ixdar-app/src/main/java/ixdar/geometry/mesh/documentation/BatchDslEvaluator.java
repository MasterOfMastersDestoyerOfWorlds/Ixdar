package ixdar.geometry.mesh.documentation;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeSchema;
import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshDistance;
import ixdar.geometry.mesh.data.MeshLoader;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.SkeletonSensitivityAnalyzer;
import ixdar.geometry.mesh.graph.InputParameterDescriptor;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.graph.OptimizableParameter;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;

/**
 * Batch DSL evaluator for parameter optimization. Two modes:
 * <ul>
 *   <li><b>Discover:</b> {@code BatchDslEvaluator <dsl-path> --discover} — outputs parameter space JSON</li>
 *   <li><b>Batch:</b> {@code BatchDslEvaluator <dsl-path> <output-dir> <params-json> [ref-obj]} — evaluates samples, exports OBJs, optionally compares against reference</li>
 * </ul>
 */
public final class BatchDslEvaluator {

    /** Function definitions from the parsed DSL (set during main). */
    private static Map<String, PythonParser.FunctionDef> batchFuncDefs = Map.of();

    private static final String[] INPUT_NODE_TYPES = {"input_float", "input_int"};

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

        String source = Files.readString(dslPath);
        PythonParser parser = new PythonParser(new PythonLexer(source));
        List<PythonParser.ParsedNode> parsed = parser.parseGraph();
        batchFuncDefs = parser.functionDefs();
        Map<String, Class<? extends MeshNode>> registry = NodeGraphRuntime.annotationRegistryClasses();

        switch (args[1]) {
            case "--discover" -> discover(parsed, registry);
            case "--skeleton-sensitivity" -> {
                if (args.length < 3) {
                    System.err.println("skeleton-sensitivity requires: <dsl-path> --skeleton-sensitivity <ref-obj> [--resolution N] [--epsilon F]");
                    System.exit(2);
                }
                String refObj = args[2];
                int resolution = intFlag(args, "--resolution", 128);
                float epsilon = floatFlag(args, "--epsilon", 0);
                skeletonSensitivity(parsed, refObj, resolution, epsilon);
            }
            case "--skeleton-optimize" -> {
                if (args.length < 3) {
                    System.err.println("skeleton-optimize requires: <dsl-path> --skeleton-optimize <ref-obj> [--resolution N] [--max-iters N] [--target-score F]");
                    System.exit(2);
                }
                String refObj = args[2];
                int resolution = intFlag(args, "--resolution", 128);
                int maxIters = intFlag(args, "--max-iters", 10);
                float targetScore = floatFlag(args, "--target-score", 95);
                skeletonOptimize(parsed, refObj, resolution, maxIters, targetScore);
            }
            default -> {
                if (args.length < 3) {
                    System.err.println("Batch mode requires: <dsl-path> <output-dir> <params-json> [ref-obj]");
                    System.exit(2);
                }
                String refObjPath = (args.length >= 4 && !"unused".equals(args[3])) ? args[3] : null;
                batch(parsed, registry, Path.of(args[1]), Path.of(args[2]), refObjPath);
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
            Map<String, Class<? extends MeshNode>> registry) {
        List<Map<String, Object>> params = new ArrayList<>();
        for (PythonParser.ParsedNode node : parsed) {
            if (!isInputParam(node.type)) continue;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", node.id);
            p.put("type", node.type);
            Object nameObj = node.arguments.get("name");
            p.put("name", nameObj != null ? String.valueOf(nameObj) : node.id);
            p.put("default", numArg(node, "default"));
            p.put("min", numArg(node, "min"));
            p.put("max", numArg(node, "max"));
            params.add(p);
        }

        // Find the last node for output
        String lastId = parsed.isEmpty() ? "" : parsed.get(parsed.size() - 1).id;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("parameters", params);
        result.put("outputNode", lastId);
        System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }

    private static void batch(List<PythonParser.ParsedNode> parsed,
            Map<String, Class<? extends MeshNode>> registry,
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
        ArrayMesh normalizedRefMesh = null;
        if (refObjPath != null && !refObjPath.isEmpty()) {
            try {
                normalizedRefMesh = MeshLoader.load(refObjPath);
                normalizeArrayMesh(normalizedRefMesh);
                System.err.printf("Loaded reference mesh: %d verts (normalized)%n",
                        normalizedRefMesh.vertexCount());
            } catch (Exception e) {
                System.err.println("Warning: could not load reference mesh: " + e.getMessage());
            }
        }

        // Find output node
        PythonParser.ParsedNode last = parsed.get(parsed.size() - 1);
        List<String> ports = candidatePorts(last, registry);

        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        runtime.registerFunctionDefs(batchFuncDefs);

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
                    Object raw = runtime.executeGraphResult(parsed, last.id, port, overrides);
                    if (raw instanceof GeometryBundle gb) mesh = gb.mesh();
                    else if (raw instanceof MeshTopology mt) mesh = mt;
                    if (mesh != null && mesh.vertexCount() > 0) break;
                }

                if (mesh == null || mesh.vertexCount() == 0) {
                    row.put("ok", false);
                    row.put("error", "empty mesh");
                } else {
                    row.put("ok", true);
                    row.put("vertexCount", mesh.vertexCount());
                    row.put("faceCount", mesh.faceCount());

                    int boundaryEdges = 0;
                    for (int ei = 0; ei < mesh.edgeCount(); ei++) {
                        if (mesh.isBoundaryEdge(mesh.edgeIdAt(ei))) boundaryEdges++;
                    }
                    row.put("watertight", boundaryEdges == 0);

                    // Inline mesh comparison against reference (if available)
                    // Uses exact same path as viewer: normalize + computeAllMetrics(scale=1.0)
                    if (normalizedRefMesh != null) {
                        ArrayMesh genArrayMesh = ixdar.geometry.mesh.data.ArrayMeshEngine
                                .fromUniformMeshTopology(mesh);
                        normalizeArrayMesh(genArrayMesh);
                        MeshDistance.MeshMetrics metrics = MeshDistance.computeAllMetrics(
                                genArrayMesh, normalizedRefMesh, 1.0f);
                        row.put("similarity", Math.round(metrics.similarityScore * 10.0) / 10.0);
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
                row.put("ok", false);
                row.put("error", e.getMessage());
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
        System.err.printf("  Reference: %s%n", refObjPath);
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
            pm.put("id", p.overrideKey());
            pm.put("name", p.displayName());
            pm.put("default", p.defaultValue());
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
        output.put("parameters", paramList);

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
                be.put("branch", currentBranch);
                be.put("avgError", branchTotalErr / branchJointCount);
                be.put("joints", branchJointCount);
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
            be.put("branch", currentBranch);
            be.put("avgError", branchJointCount > 0 ? branchTotalErr / branchJointCount : 0);
            be.put("joints", branchJointCount);
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
        System.err.printf("  Reference: %s%n", refObjPath);
        System.err.printf("  Resolution: %d, Max iterations: %d, Target: %.1f%%%n",
                resolution, maxIters, targetScore);

        SkeletonSensitivityAnalyzer.OptimizationResult result =
                SkeletonSensitivityAnalyzer.optimize(parsed, refObjPath, resolution, maxIters, targetScore);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("initialScore", result.initialScore());
        output.put("finalScore", result.finalScore());
        output.put("improvement", result.finalScore() - result.initialScore());
        output.put("iterations", result.steps().size());

        // Final parameter values
        output.put("finalParams", result.finalParams());

        // Step history
        List<Map<String, Object>> stepList = new ArrayList<>();
        for (SkeletonSensitivityAnalyzer.OptimizationStep step : result.steps()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("iteration", step.iteration());
            sm.put("score", step.score());
            sm.put("improvement", step.improvement());
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

    /**
     * Center and scale ArrayMesh to unit bounding-box diagonal (in-place).
     * Matches the viewer's normalize=true comparison exactly.
     */
    private static void normalizeArrayMesh(ArrayMesh mesh) {
        int n = mesh.vertexCount();
        if (n == 0) return;
        float[] pos = mesh.copyPositions();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            int o = i * 3;
            if (pos[o] < minX) minX = pos[o];     if (pos[o] > maxX) maxX = pos[o];
            if (pos[o+1] < minY) minY = pos[o+1]; if (pos[o+1] > maxY) maxY = pos[o+1];
            if (pos[o+2] < minZ) minZ = pos[o+2]; if (pos[o+2] > maxZ) maxZ = pos[o+2];
        }
        float cx = (minX + maxX) * 0.5f, cy = (minY + maxY) * 0.5f, cz = (minZ + maxZ) * 0.5f;
        float dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
        float diagonal = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float invDiag = diagonal > 1e-8f ? 1.0f / diagonal : 1.0f;
        for (int i = 0; i < n; i++) {
            mesh.setVertexPosition(i,
                    (pos[i * 3] - cx) * invDiag,
                    (pos[i * 3 + 1] - cy) * invDiag,
                    (pos[i * 3 + 2] - cz) * invDiag);
        }
    }

    private static boolean isInputParam(String type) {
        for (String t : INPUT_NODE_TYPES) {
            if (t.equals(type)) return true;
        }
        return false;
    }

    private static Object numArg(PythonParser.ParsedNode node, String key) {
        Object v = node.arguments.get(key);
        if (v instanceof Number n) return n;
        if (v instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
        }
        return v;
    }

    private static List<String> candidatePorts(PythonParser.ParsedNode last,
            Map<String, Class<? extends MeshNode>> registry) {
        Class<? extends MeshNode> clazz = registry.get(last.type);
        if (clazz != null) {
            try {
                MeshNode instance = clazz.getDeclaredConstructor().newInstance();
                MeshNodeSchema schema = MeshNodeSchema.from(instance);
                List<String> names = new ArrayList<>();
                for (var op : schema.outputs()) {
                    if (op.type() == ixdar.annotations.meshnode.PortType.MESH
                            || op.type() == ixdar.annotations.meshnode.PortType.GEOMETRY_BUNDLE) {
                        names.add(op.name());
                    }
                }
                if (!names.isEmpty()) return names;
            } catch (ReflectiveOperationException ignored) {}
        }
        return List.of("geometry", "mesh");
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
