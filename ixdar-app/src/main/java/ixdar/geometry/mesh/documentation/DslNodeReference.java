package ixdar.geometry.mesh.documentation;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import com.google.gson.GsonBuilder;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeRegistry_MeshNodes;
import ixdar.annotations.meshnode.MeshNodeSchema;
import ixdar.annotations.meshnode.ModeConstraint;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;

/**
 * CLI entry point that emits a structured JSON schema of all registered DSL mesh nodes.
 * Each node includes: id, sourceFile, inputs (with name, type, default, mode constraints),
 * and outputs (with name, type).
 *
 * Usage: java ixdar.geometry.mesh.documentation.DslNodeReference
 *
 * Output is sorted by node id for stable diffing.
 */
public final class DslNodeReference {

    private DslNodeReference() {
    }

    public static void main(String[] args) {
        Map<String, NodeReference> referenceMap = buildReferenceMap();
        String json = toJson(referenceMap);
        System.out.println(json);
    }

    private static Map<String, NodeReference> buildReferenceMap() {
        Map<String, NodeReference> out = new TreeMap<>();
        Map<String, Supplier<? extends MeshNode>> registry = MeshNodeRegistry_MeshNodes.MAP;

        for (Map.Entry<String, Supplier<? extends MeshNode>> e : registry.entrySet()) {
            String nodeId = e.getKey();
            Supplier<? extends MeshNode> supplier = e.getValue();
            MeshNode node = supplier.get();
            MeshNodeSchema schema = node.schema();

            String sourceFile = getSourceFilePath(node.getClass());
            List<Map<String, Object>> inputs = serializeInputs(schema.inputs());
            List<Map<String, Object>> outputs = serializeOutputs(schema.outputs());

            out.put(nodeId, new NodeReference(nodeId, sourceFile, inputs, outputs));
        }

        return out;
    }

    private static String getSourceFilePath(Class<?> clazz) {
        String moduleName = clazz.getModule().getName();
        String className = clazz.getName();
        String relativePath = className.replace('.', '/') + ".java";

        try {
            // Try to find the source file in standard Maven/Gradle locations
            Path projectRoot = Path.of(".").toAbsolutePath().normalize();
            Path[] possiblePaths = {
                projectRoot.resolve("ixdar-app/src/main/java").resolve(relativePath),
                projectRoot.resolve("ixdar-app/src/main/java").resolve(clazz.getSimpleName() + ".java"),
                projectRoot.resolve(relativePath),
                Path.of("/Users/acw28/Code/Ixdar/ixdar-app/src/main/java").resolve(relativePath),
                Path.of("/Users/acw28/Code/Ixdar/ixdar-app/src/main/java").resolve(clazz.getSimpleName() + ".java"),
            };

            for (Path p : possiblePaths) {
                if (Files.exists(p)) {
                    return p.toString();
                }
            }

            // Fallback to just the relative path
            return relativePath.replace('/', '.');
        } catch (Exception ex) {
            return relativePath.replace('/', '.');
        }
    }

    private static String toJson(Map<String, NodeReference> referenceMap) {
        List<Map<String, Object>> nodes = new ArrayList<>();

        for (Map.Entry<String, NodeReference> e : referenceMap.entrySet()) {
            NodeReference ref = e.getValue();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", ref.id);
            entry.put("sourceFile", ref.sourceFile);
            entry.put("inputs", ref.inputs);
            entry.put("outputs", ref.outputs);
            nodes.add(entry);
        }

        return new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(nodes);
    }

    private static List<Map<String, Object>> serializeInputs(List<InputPort> inputs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (InputPort p : inputs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name());
            m.put("type", p.type().name());
            m.put("default", serializeDefaultValue(p.defaultValue(), p.type()));
            ModeConstraint mc = p.modes();
            if (mc != null) {
                Map<String, Object> modeInfo = new LinkedHashMap<>();
                modeInfo.put("canonicalIds", mc.canonicalIds());
                modeInfo.put("aliases", new LinkedHashMap<>(mc.aliasToCanonical()));
                modeInfo.put("defaultId", mc.defaultCanonicalId());
                m.put("modeConstraint", modeInfo);
            }
            out.add(m);
        }
        return out;
    }

    private static Object serializeDefaultValue(Object defaultValue, PortType type) {
        if (defaultValue == null) {
            return null;
        }
        if (type == PortType.VECTOR3) {
            // Vector3Value.toString() returns "(x, y, z)" format
            return defaultValue.toString();
        }
        if (type == PortType.ROTATION) {
            // RotationValue.toString() returns "(x, y, z, w)" format
            return defaultValue.toString();
        }
        return defaultValue;
    }

    private static List<Map<String, Object>> serializeOutputs(List<OutputPort> outputs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OutputPort p : outputs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name());
            m.put("type", p.type().name());
            out.add(m);
        }
        return out;
    }

    /**
     * Schema representation for a single node.
     */
    private record NodeReference(
            String id,
            String sourceFile,
            List<Map<String, Object>> inputs,
            List<Map<String, Object>> outputs
    ) {
    }
}
