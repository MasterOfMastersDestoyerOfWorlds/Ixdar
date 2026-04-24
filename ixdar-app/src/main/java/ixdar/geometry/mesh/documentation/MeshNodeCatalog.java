package ixdar.geometry.mesh.documentation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.google.gson.GsonBuilder;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.MeshNodeRegistry_MeshNodes;
import ixdar.annotations.meshnode.MeshNodeSchema;
import ixdar.annotations.meshnode.ModeConstraint;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.geometry.mesh.nodes.math.RandomValueNode;

public final class MeshNodeCatalog {

    private MeshNodeCatalog() {
    }

    public static String toJsonFromAnnotationRegistry() {
        return toJson(MeshNodeRegistry_MeshNodes.MAP, null);
    }

    public static String toJsonFromAnnotationRegistry(String scope) {
        return toJson(MeshNodeRegistry_MeshNodes.MAP, scope);
    }

    public static String toJson(Map<String, Supplier<? extends MeshNode>> registry) {
        return toJson(registry, null);
    }

    public static String toJson(Map<String, Supplier<? extends MeshNode>> registry, String scope) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Map.Entry<String, Supplier<? extends MeshNode>> e : registry.entrySet()) {
            MeshNode n = e.getValue().get();
            String[] scopes = scopesOf(n.getClass());
            if (scope != null && !containsScope(scopes, scope)) {
                continue;
            }
            MeshNodeSchema schema = n.schema();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", e.getKey());
            entry.put("category", categoryFromClass(n.getClass()));
            entry.put("scopes", Arrays.asList(scopes));
            entry.put("inputs", serializeInputs(schema.inputs(), schema.socketDocs()));
            entry.put("outputs", serializeOutputs(schema.outputs(), schema.socketDocs()));
            String desc = n.description();
            if (desc != null && !desc.isEmpty()) {
                entry.put("description", desc);
            }
            if (schema.destructive()) {
                entry.put("destructive", true);
                entry.put("consumes", schema.consumes());
            }
            if (n instanceof RandomValueNode) {
                entry.put("outputActivationByMode", RandomValueNode.OUTPUT_ACTIVATION_BY_MODE);
            }
            nodes.add(entry);
        }
        return new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(Map.of("nodes", nodes));
    }

    private static String[] scopesOf(Class<?> clazz) {
        MeshNodeAnnotation ann = clazz.getAnnotation(MeshNodeAnnotation.class);
        if (ann == null) {
            return new String[] { "mesh", "dungeon" };
        }
        return ann.scopes();
    }

    private static boolean containsScope(String[] scopes, String scope) {
        for (String s : scopes) {
            if (s.equals(scope)) return true;
        }
        return false;
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

    private static List<Map<String, Object>> serializeInputs(
            List<InputPort> inputs, Map<String, String> socketDocs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (InputPort p : inputs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name());
            m.put("portType", p.type().name());
            String doc = socketDocs.get(p.name());
            if (doc != null && !doc.isEmpty()) {
                m.put("description", doc);
            }
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

    private static List<Map<String, Object>> serializeOutputs(
            List<OutputPort> outputs, Map<String, String> socketDocs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OutputPort p : outputs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name());
            m.put("portType", p.type().name());
            String doc = socketDocs.get(p.name());
            if (doc != null && !doc.isEmpty()) {
                m.put("description", doc);
            }
            out.add(m);
        }
        return out;
    }
}
