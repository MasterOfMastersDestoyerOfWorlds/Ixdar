package ixdar.geometry.mesh.documentation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import com.google.gson.GsonBuilder;

import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeSchema;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.GraphValidator;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;

/**
 * CLI entry point that validates a .dsl file against the mesh node registry.
 * Outputs JSON with parse errors, validation errors, warnings, and (when the graph
 * is valid) a mesh execution probe: vertex/face counts, axis-aligned bounds, extent, radius.
 *
 * Usage: java ixdar.geometry.mesh.documentation.ValidateDsl <dsl-path>
 * Exit code 0 = valid graph (no structural errors); mesh probe failures appear under meshProbe only.
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
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("valid", false);
            result.put("nodeCount", 0);
            result.put("parseError", e.getMessage());
            result.put("errors", List.of());
            result.put("warnings", List.of());
            result.put("meshProbe", meshProbeSkipped("parse error"));
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
            result.put("meshProbe", runMeshProbe(parsed, registry));
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
}
