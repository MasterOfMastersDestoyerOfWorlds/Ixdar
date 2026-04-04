package ixdar.geometry.mesh.documentation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.google.gson.GsonBuilder;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeRegistry_MeshNodes;
import ixdar.annotations.meshnode.MeshNodeSchema;
import ixdar.annotations.meshnode.ModeConstraint;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.geometry.mesh.nodes.math.RandomValueNode;

public final class MeshNodeCatalog {

    private MeshNodeCatalog() {
    }

    public static String toJsonFromAnnotationRegistry() {
        return toJson(MeshNodeRegistry_MeshNodes.MAP);
    }

    public static String toJson(Map<String, Supplier<? extends MeshNode>> registry) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Map.Entry<String, Supplier<? extends MeshNode>> e : registry.entrySet()) {
            MeshNode n = e.getValue().get();
            MeshNodeSchema schema = n.schema();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", e.getKey());
            entry.put("category", categoryFromClass(n.getClass()));
            entry.put("inputs", serializeInputs(schema.inputs()));
            entry.put("outputs", serializeOutputs(schema.outputs()));
            if (n instanceof RandomValueNode) {
                entry.put("outputActivationByMode", RandomValueNode.OUTPUT_ACTIVATION_BY_MODE);
            }
            nodes.add(entry);
        }
        return new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(Map.of("nodes", nodes));
    }

    private static String categoryFromClass(Class<?> clazz) {
        String pkg = clazz.getPackageName();
        int lastDot = pkg.lastIndexOf('.');
        if (lastDot < 0) return "Other";
        String leaf = pkg.substring(lastDot + 1);
        return switch (leaf) {
            case "primitives" -> "Primitives";
            case "modifier" -> "Modifiers";
            case "math" -> "Math & Logic";
            case "data" -> "Field & Data";
            case "geometry" -> "Geometry Operations";
            case "curve" -> "Curve Operations";
            case "control" -> "Control Flow";
            case "closure" -> "Closure";
            case "patch" -> "Patch & Surface";
            case "selection" -> "Selection";
            case "transform" -> "Transform";
            default -> "Other";
        };
    }

    private static List<Map<String, Object>> serializeInputs(List<InputPort> inputs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (InputPort p : inputs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name());
            m.put("portType", p.type().name());
            m.put("defaultValue", p.defaultValue());
            ModeConstraint mc = p.modes();
            if (mc != null) {
                m.put("canonicalModes", mc.canonicalIds());
                m.put("aliases", mc.aliasToCanonical());
                m.put("defaultMode", mc.defaultCanonicalId());
            }
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> serializeOutputs(List<OutputPort> outputs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OutputPort p : outputs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name());
            m.put("portType", p.type().name());
            out.add(m);
        }
        return out;
    }
}
