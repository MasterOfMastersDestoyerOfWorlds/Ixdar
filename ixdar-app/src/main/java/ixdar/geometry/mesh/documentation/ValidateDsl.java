package ixdar.geometry.mesh.documentation;

import com.google.gson.GsonBuilder;
import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeSchema;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.GraphValidator;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.nodes.data.TagGeometryNode;

import ixdar.geometry.mesh.graph.SkillLibrary;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;
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

import java.util.Set;

/**
 * CLI entry point that validates a .dsl file against the mesh node registry.
 * Outputs JSON with parse errors, validation errors, and warnings.
 *
 * Usage: java ixdar.geometry.mesh.documentation.ValidateDsl {@code <dsl-path>}
 * Exit code 0 = valid, 1 = errors found, 2 = usage/IO error
 */
public final class ValidateDsl {
    public static final String VALID = "valid";
    public static final String NODECOUNT = "nodeCount";
    public static final String ERRORS = "errors";
    public static final String WARNINGS = "warnings";
    public static final String MESHPROBE = "meshProbe";
    public static final String OK = "ok";
    public static final String ATTEMPTED = "attempted";
    public static final String OUTPUTPORT = "outputPort";
    public static final String ERROR = "error";
    public static final String FACECOUNT = "faceCount";
    public static final String VERTEXCOUNT = "vertexCount";
    public static final int NUM_1000 = 1000;
    public static final int NUM_1_000_000 = 1_000_000;
    public static final float NUM_1e_10 = 1e-10f;

    /**
     * Validate DSL source text and optionally export to OBJ.
     * <p>
     * This is the core validation logic, callable from both the CLI entry point
     * and the automation server endpoint. Returns a result map matching the
     * standard JSON schema (valid, nodeCount, errors, warnings, meshProbe, ...).
     *
     * @param source DSL source text to parse and validate
     * @param skillDir optional path to a directory of skill libraries; preamble is prepended to
     *                 the parser when non-empty. Unreadable directories are silently ignored.
     * @param exportPath optional OBJ export path; when set and the mesh probe succeeds, the
     *                   resulting mesh (and a sibling {@code .tags.json} when tags are present)
     *                   is written here.
     * @return ordered map containing {@code valid}, {@code nodeCount}, {@code errors},
     *         {@code warnings}, and {@code meshProbe} (or a parse-error shape on lex/parse failure)
     */
    public static Map<String, Object> validate(
        String source,
        String skillDir,
        String exportPath
    ) {
        // Load skill library if provided (optional — validation works without it)
        SkillLibrary skillLib =
            new SkillLibrary();
        if (skillDir != null && !skillDir.isEmpty()) {
            try {
                skillLib.loadDirectory(Path.of(skillDir));
            } catch (IOException e) {
                // Skills directory unreadable — continue without skills
            }
        }

        Map<String, Class<? extends MeshNode>> registry =
            NodeGraphRuntime.annotationRegistryClasses();

        List<PythonParser.ParsedNode> parsed;
        Map<String, PythonParser.FunctionDef> funcDefs;
        try {
            PythonParser parser = new PythonParser(new PythonLexer(source));
            if (!skillLib.getSkills().isEmpty()) {
                String preamble = skillLib.toDslPreamble();
                PythonParser preambleParser = new PythonParser(
                    new PythonLexer(preamble)
                );
                preambleParser.parseGraph();
                funcDefs = new HashMap<>(
                    preambleParser.functionDefs()
                );
                parsed = parser.parseGraph();
                funcDefs.putAll(parser.functionDefs());
            } else {
                parsed = parser.parseGraph();
                funcDefs = parser.functionDefs();
            }
        } catch (RuntimeException e) {
            return Map.of(
                VALID,
                false,
                NODECOUNT,
                0,
                "parseError",
                e.getMessage(),
                ERRORS,
                List.of(),
                WARNINGS,
                List.of()
            );
        }

        Set<String> functionNames = funcDefs.keySet();
        List<String> errors = GraphValidator.validate(
            parsed,
            registry,
            functionNames
        );
        List<String> warnings = GraphValidator.validateWithRandomValueWarnings(
            parsed,
            registry
        );

        boolean valid = errors.isEmpty();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(VALID, valid);
        result.put(NODECOUNT, parsed.size());
        result.put(ERRORS, errors);
        result.put(WARNINGS, warnings);

        if (valid && !parsed.isEmpty()) {
            Map<String, Object> probe = runMeshProbe(
                parsed,
                registry,
                funcDefs
            );
            result.put(MESHPROBE, probe);

            if (
                exportPath != null &&
                !exportPath.isEmpty() &&
                Boolean.TRUE.equals(probe.get(OK))
            ) {
                try {
                    ExportResult er = executeForExport(
                        parsed,
                        registry,
                        funcDefs
                    );
                    if (er != null && er.mesh != null) {
                        Path out = Path.of(exportPath);
                        if (out.getParent() != null) Files.createDirectories(
                            out.getParent()
                        );
                        writeObj(er.mesh, out);
                        probe.put("exportedObj", exportPath);

                        if (er.tags != null && !er.tags.isEmpty()) {
                            Path tagsPath = Path.of(
                                exportPath.replaceAll("\\.obj$", "") +
                                    ".tags.json"
                            );
                            writeTagsJson(
                                er.tags,
                                er.mesh.vertexCount(),
                                tagsPath
                            );
                            probe.put("exportedTags", tagsPath.toString());
                        }
                    }
                } catch (Exception e) {
                    probe.put("exportError", e.getMessage());
                }
            }
        } else {
            result.put(
                MESHPROBE,
                meshProbeSkipped("graph has validation errors or is empty")
            );
        }

        return result;
    }

    /**
     * CLI driver: read the DSL file at {@code args[0]}, validate it, and print the JSON result.
     * Honors {@code -Dskill.dir} for skill libraries and {@code -Ddsl.export} for OBJ export.
     * Exit code is 0 when valid, 1 when validation fails, and 2 on usage/IO errors.
     *
     * @param args single element: path to a {@code .dsl} file
     * @throws IOException if the input file cannot be read
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: ValidateDsl <dsl-path>");
            System.exit(2);
        }

        Path dslPath = Path.of(args[0]);
        if (!Files.exists(dslPath)) {
            System.err.println("File not found: " + dslPath);
            System.exit(2);
        }

        String skillDirProp = System.getProperty("skill.dir", "");
        String exportPath = System.getProperty("dsl.export");
        String source = Files.readString(dslPath);

        Map<String, Object> result = validate(source, skillDirProp, exportPath);

        System.out.println(
            new GsonBuilder().setPrettyPrinting().create().toJson(result)
        );
        boolean valid = Boolean.TRUE.equals(result.get(VALID));
        System.exit(valid ? 0 : 1);
    }

    private static Map<String, Object> meshProbeSkipped(String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(ATTEMPTED, false);
        m.put("skipReason", reason);
        return m;
    }

    private static List<String> candidateMeshPorts(
        PythonParser.ParsedNode last,
        Map<String, Class<? extends MeshNode>> registry
    ) {
        Class<? extends MeshNode> clazz = registry.get(last.type);
        if (clazz != null) {
            try {
                MeshNode instance = clazz
                    .getDeclaredConstructor()
                    .newInstance();
                MeshNodeSchema schema = MeshNodeSchema.from(instance);
                // Prefer GEOMETRY_BUNDLE ports over MESH so the export sees the
                // bundle's slots (bezier handles, auto-tags, etc.) — a plain
                // MeshTopology output strips that metadata.
                List<String> bundlePorts = new ArrayList<>();
                List<String> meshPorts = new ArrayList<>();
                for (OutputPort op : schema.outputs()) {
                    if (op.type() == PortType.GEOMETRY_BUNDLE) {
                        bundlePorts.add(op.name());
                    } else if (op.type() == PortType.MESH) {
                        meshPorts.add(op.name());
                    }
                }
                List<String> names = new ArrayList<>();
                names.addAll(bundlePorts);
                names.addAll(meshPorts);
                if (!names.isEmpty()) {
                    return names;
                }
            } catch (ReflectiveOperationException ignored) {}
        }
        return List.of("geometry", "mesh");
    }

    private static Map<String, Object> runMeshProbe(
        List<PythonParser.ParsedNode> parsed,
        Map<String, Class<? extends MeshNode>> registry,
        Map<String, PythonParser.FunctionDef> funcDefs
    ) {
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put(ATTEMPTED, true);

        PythonParser.ParsedNode last = parsed.get(parsed.size() - 1);
        probe.put("outputNodeId", last.id);
        List<String> ports = candidateMeshPorts(last, registry);
        probe.put("portsTried", ports);

        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        runtime.registerFunctionDefs(funcDefs);

        MeshTopology mesh = null;
        String usedPort = null;
        for (String port : ports) {
            try {
                mesh = runtime.executeGraphToMesh(parsed, last.id, port);
            } catch (Exception e) {
                probe.put(OK, false);
                probe.put(OUTPUTPORT, port);
                probe.put(
                    ERROR,
                    "Execution failed on port " + port + ": " + e.getMessage()
                );
                return probe;
            }
            if (mesh != null && mesh.vertexCount() > 0) {
                usedPort = port;
                break;
            }
        }

        if (mesh == null || mesh.vertexCount() <= 0) {
            probe.put(OK, false);
            probe.put(
                ERROR,
                "Last node '" +
                    last.id +
                    "' produced no mesh with positive vertex count."
            );
            return probe;
        }

        // Always report execution time for observability
        long executionMs = runtime.lastTotalMs();
        probe.put("executionMs", executionMs);

        // Performance gate: reject DSLs that take too long to evaluate
        final long MAX_EXECUTION_MS = NUM_1000;
        if (executionMs > MAX_EXECUTION_MS) {
            probe.put(OK, false);
            probe.put(FACECOUNT, mesh.faceCount());
            probe.put(VERTEXCOUNT, mesh.vertexCount());
            probe.put(
                ERROR,
                "DSL execution took " +
                    executionMs +
                    "ms (limit " +
                    MAX_EXECUTION_MS +
                    "ms)"
            );
            return probe;
        }

        // Reject meshes over 1M faces
        final int MAX_FACE_COUNT = NUM_1_000_000;
        if (mesh.faceCount() > MAX_FACE_COUNT) {
            probe.put(OK, false);
            probe.put(FACECOUNT, mesh.faceCount());
            probe.put(
                ERROR,
                "Mesh face count (" +
                    mesh.faceCount() +
                    ") exceeds limit of " +
                    MAX_FACE_COUNT
            );
            return probe;
        }

        probe.put(OUTPUTPORT, usedPort);
        probe.put(VERTEXCOUNT, mesh.vertexCount());
        probe.put(FACECOUNT, mesh.faceCount());
        probe.put("edgeCount", mesh.edgeCount());

        int boundaryEdges = 0;
        for (int i = 0; i < mesh.edgeCount(); i++) {
            if (mesh.isBoundaryEdge(mesh.edgeIdAt(i))) {
                boundaryEdges++;
            }
        }
        probe.put("boundaryEdges", boundaryEdges);
        probe.put("watertight", boundaryEdges == 0);

        boolean requireWatertight = Boolean.parseBoolean(
            System.getProperty("dsl.requireWatertight", "false")
        );
        if (requireWatertight && boundaryEdges > 0) {
            probe.put(OK, false);
            probe.put(
                ERROR,
                "Mesh has " + boundaryEdges + " boundary edges (not watertight)"
            );
            return probe;
        }

        Vector3f mn = mesh.boundsMin(new Vector3f());
        Vector3f mx = mesh.boundsMax(new Vector3f());
        probe.put("boundsMin", vec3Json(mn));
        probe.put("boundsMax", vec3Json(mx));

        float extent = mn.distance(mx);
        probe.put("extent", floatJson(extent));

        float rad = mesh.radius();
        probe.put("radius", floatJson(rad));

        boolean positionsOk = Vector3finite(mn) && Vector3finite(mx);
        boolean extentOk = Float.isFinite(extent) && extent > NUM_1e_10;
        boolean radiusOk = Float.isFinite(rad) && rad > NUM_1e_10;

        boolean ok = positionsOk && extentOk && radiusOk;
        probe.put(OK, ok);
        if (!ok) {
            List<String> issues = new ArrayList<>();
            if (!positionsOk) {
                issues.add("non-finite bounds");
            }
            if (!extentOk) {
                issues.add("collapsed or invalid AABB extent");
            }
            if (!radiusOk) {
                issues.add("non-finite or zero radius");
            }
            probe.put(ERROR, String.join("; ", issues));
        }

        return probe;
    }

    private static boolean Vector3finite(Vector3f v) {
        return (
            Float.isFinite(v.x) && Float.isFinite(v.y) && Float.isFinite(v.z)
        );
    }

    private static List<Double> vec3Json(Vector3f v) {
        return List.of((double) v.x, (double) v.y, (double) v.z);
    }

    private static Double floatJson(float f) {
        return Float.isFinite(f) ? Double.valueOf(f) : null;
    }

    private static ExportResult executeForExport(
        List<PythonParser.ParsedNode> parsed,
        Map<String, Class<? extends MeshNode>> registry,
        Map<String, PythonParser.FunctionDef> funcDefs
    ) {
        PythonParser.ParsedNode last = parsed.get(parsed.size() - 1);
        List<String> ports = candidateMeshPorts(last, registry);
        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        runtime.registerFunctionDefs(funcDefs);
        for (String port : ports) {
            try {
                Object raw = runtime.executeGraphResult(parsed, last.id, port);
                MeshTopology mesh = null;
                Map<String, boolean[]> tags = null;
                if (raw instanceof GeometryBundle gb) {
                    mesh = gb.mesh();
                    tags = TagGeometryNode.getTags(gb);
                } else if (raw instanceof MeshTopology mt) {
                    mesh = mt;
                }
                if (mesh != null && mesh.vertexCount() > 0) {
                    return new ExportResult(mesh, tags);
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static void writeTagsJson(
        Map<String, boolean[]> tags,
        int vertexCount,
        Path out
    ) throws IOException {
        Map<String, Object> doc = new LinkedHashMap<>();
        Map<String, List<Integer>> tagIndices = new LinkedHashMap<>();
        for (Map.Entry<String, boolean[]> e : tags.entrySet()) {
            boolean[] mask = e.getValue();
            if (mask.length != vertexCount) {
                // Skip tags whose per-vertex mask doesn't match the final mesh. This
                // happens when a tag set before a vertex-count-changing op (e.g.
                // coons_patch's subdivision) is carried forward; the indices would
                // be meaningless or misleading in the sidecar.
                System.err.println(
                    "[ValidateDsl] skipping tag '" +
                        e.getKey() +
                        "' (mask length " +
                        mask.length +
                        " != vertex count " +
                        vertexCount +
                        "); likely invalidated by a vertex-count-changing op upstream."
                );
                continue;
            }
            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < vertexCount; i++) {
                if (mask[i]) {
                    indices.add(i);
                }
            }
            tagIndices.put(e.getKey(), indices);
        }
        doc.put("tags", tagIndices);
        doc.put("vertex_count", vertexCount);
        Files.writeString(
            out,
            new GsonBuilder().setPrettyPrinting().create().toJson(doc)
        );
    }

    private static void writeObj(MeshTopology mesh, Path out)
        throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(out)) {
            Vector3f p = new Vector3f();
            Map<Integer, Integer> vidToIdx = new HashMap<>();
            for (int i = 0; i < mesh.vertexCount(); i++) {
                int vid = mesh.vertexIdAt(i);
                vidToIdx.put(vid, i + 1); // OBJ is 1-indexed
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

    private record ExportResult(
        MeshTopology mesh,
        Map<String, boolean[]> tags
    ) {}
}
