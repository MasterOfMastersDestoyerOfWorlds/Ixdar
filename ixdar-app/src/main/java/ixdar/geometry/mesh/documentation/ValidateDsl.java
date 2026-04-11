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
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;

/**
 * CLI entry point that validates a .dsl file against the mesh node registry.
 * Outputs JSON with parse errors, validation errors, and warnings.
 *
 * Usage: java ixdar.geometry.mesh.documentation.ValidateDsl <dsl-path>
 * Exit code 0 = valid, 1 = errors found, 2 = usage/IO error
 */
public final class ValidateDsl {

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

        String source = Files.readString(dslPath);
        Map<String, Class<? extends MeshNode>> registry = NodeGraphRuntime.annotationRegistryClasses();

        List<PythonParser.ParsedNode> parsed;
        try {
            parsed = new PythonParser(new PythonLexer(source)).parseGraph();
        } catch (RuntimeException e) {
            Map<String, Object> result = Map.of(
                    "valid", false,
                    "nodeCount", 0,
                    "parseError", e.getMessage(),
                    "errors", List.of(),
                    "warnings", List.of());
            System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(result));
            System.exit(1);
            return;
        }

        List<String> errors = GraphValidator.validate(parsed, registry);
        List<String> warnings = GraphValidator.validateWithRandomValueWarnings(parsed, registry);

        boolean valid = errors.isEmpty();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", valid);
        result.put("nodeCount", parsed.size());
        result.put("errors", errors);
        result.put("warnings", warnings);

        if (valid && !parsed.isEmpty()) {
            Map<String, Object> probe = runMeshProbe(parsed, registry);
            result.put("meshProbe", probe);

            // OBJ export: if -Ddsl.export=<path> is set and mesh probe succeeded
            String exportPath = System.getProperty("dsl.export");
            if (exportPath != null && !exportPath.isEmpty()
                    && Boolean.TRUE.equals(probe.get("ok"))) {
                try {
                    ExportResult er = executeForExport(parsed, registry);
                    if (er != null && er.mesh != null) {
                        Path out = Path.of(exportPath);
                        if (out.getParent() != null) Files.createDirectories(out.getParent());
                        writeObj(er.mesh, out);
                        probe.put("exportedObj", exportPath);

                        // Write .tags.json sidecar if tags are present
                        if (er.tags != null && !er.tags.isEmpty()) {
                            Path tagsPath = Path.of(exportPath.replaceAll("\\.obj$", "") + ".tags.json");
                            writeTagsJson(er.tags, er.mesh.vertexCount(), tagsPath);
                            probe.put("exportedTags", tagsPath.toString());
                        }
                    }
                } catch (Exception e) {
                    probe.put("exportError", e.getMessage());
                }
            }
        } else {
            result.put("meshProbe", meshProbeSkipped("graph has validation errors or is empty"));
        }

        System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(result));
        System.exit(valid ? 0 : 1);
    }

    private static Map<String, Object> meshProbeSkipped(String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("attempted", false);
        m.put("skipReason", reason);
        return m;
    }

    private static List<String> candidateMeshPorts(PythonParser.ParsedNode last,
            Map<String, Class<? extends MeshNode>> registry) {
        Class<? extends MeshNode> clazz = registry.get(last.type);
        if (clazz != null) {
            try {
                MeshNode instance = clazz.getDeclaredConstructor().newInstance();
                MeshNodeSchema schema = MeshNodeSchema.from(instance);
                List<String> names = new ArrayList<>();
                for (OutputPort op : schema.outputs()) {
                    if (op.type() == PortType.MESH || op.type() == PortType.GEOMETRY_BUNDLE) {
                        names.add(op.name());
                    }
                }
                if (!names.isEmpty()) {
                    return names;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return List.of("geometry", "mesh");
    }

    private static Map<String, Object> runMeshProbe(List<PythonParser.ParsedNode> parsed,
            Map<String, Class<? extends MeshNode>> registry) {
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("attempted", true);

        PythonParser.ParsedNode last = parsed.get(parsed.size() - 1);
        probe.put("outputNodeId", last.id);
        List<String> ports = candidateMeshPorts(last, registry);
        probe.put("portsTried", ports);

        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();

        MeshTopology mesh = null;
        String usedPort = null;
        for (String port : ports) {
            try {
                mesh = runtime.executeGraphToMesh(parsed, last.id, port);
            } catch (Exception e) {
                probe.put("ok", false);
                probe.put("outputPort", port);
                probe.put("error", "Execution failed on port " + port + ": " + e.getMessage());
                return probe;
            }
            if (mesh != null && mesh.vertexCount() > 0) {
                usedPort = port;
                break;
            }
        }

        if (mesh == null || mesh.vertexCount() <= 0) {
            probe.put("ok", false);
            probe.put("error", "Last node '" + last.id + "' produced no mesh with positive vertex count.");
            return probe;
        }

        probe.put("outputPort", usedPort);
        probe.put("vertexCount", mesh.vertexCount());
        probe.put("faceCount", mesh.faceCount());
        probe.put("edgeCount", mesh.edgeCount());

        int boundaryEdges = 0;
        for (int i = 0; i < mesh.edgeCount(); i++) {
            if (mesh.isBoundaryEdge(mesh.edgeIdAt(i))) {
                boundaryEdges++;
            }
        }
        probe.put("boundaryEdges", boundaryEdges);
        probe.put("watertight", boundaryEdges == 0);

        Vector3f mn = mesh.boundsMin(new Vector3f());
        Vector3f mx = mesh.boundsMax(new Vector3f());
        probe.put("boundsMin", vec3Json(mn));
        probe.put("boundsMax", vec3Json(mx));

        float extent = mn.distance(mx);
        probe.put("extent", floatJson(extent));

        float rad = mesh.radius();
        probe.put("radius", floatJson(rad));

        boolean positionsOk = vec3Finite(mn) && vec3Finite(mx);
        boolean extentOk = Float.isFinite(extent) && extent > 1e-10f;
        boolean radiusOk = Float.isFinite(rad) && rad > 1e-10f;

        boolean ok = positionsOk && extentOk && radiusOk;
        probe.put("ok", ok);
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
            probe.put("error", String.join("; ", issues));
        }

        return probe;
    }

    private static boolean vec3Finite(Vector3f v) {
        return Float.isFinite(v.x) && Float.isFinite(v.y) && Float.isFinite(v.z);
    }

    private static List<Double> vec3Json(Vector3f v) {
        return List.of((double) v.x, (double) v.y, (double) v.z);
    }

    private static Double floatJson(float f) {
        return Float.isFinite(f) ? Double.valueOf(f) : null;
    }

    private record ExportResult(MeshTopology mesh, Map<String, boolean[]> tags) {}

    private static ExportResult executeForExport(List<PythonParser.ParsedNode> parsed,
            Map<String, Class<? extends MeshNode>> registry) {
        PythonParser.ParsedNode last = parsed.get(parsed.size() - 1);
        List<String> ports = candidateMeshPorts(last, registry);
        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
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
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static void writeTagsJson(Map<String, boolean[]> tags, int vertexCount, Path out) throws IOException {
        Map<String, Object> doc = new LinkedHashMap<>();
        Map<String, List<Integer>> tagIndices = new LinkedHashMap<>();
        for (Map.Entry<String, boolean[]> e : tags.entrySet()) {
            boolean[] mask = e.getValue();
            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < mask.length && i < vertexCount; i++) {
                if (mask[i]) {
                    indices.add(i);
                }
            }
            tagIndices.put(e.getKey(), indices);
        }
        doc.put("tags", tagIndices);
        doc.put("vertex_count", vertexCount);
        Files.writeString(out, new GsonBuilder().setPrettyPrinting().create().toJson(doc));
    }

    private static void writeObj(MeshTopology mesh, Path out) throws IOException {
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
}
