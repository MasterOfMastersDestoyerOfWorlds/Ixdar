package ixdar.geometry.mesh.documentation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import com.google.gson.GsonBuilder;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.MeshNodeRegistry_MeshNodes;
import ixdar.geometry.mesh.nodes.api.MeshNodeSchema;
import ixdar.geometry.mesh.nodes.api.ModeConstraint;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.nodes.math.RandomValueNode;

public final class MeshNodeCatalog {
    public static final String DESCRIPTION = "description";
    public static final String OTHER = "Other";
    public static final String NAME = "name";
    public static final String PORTTYPE = "portType";

    private MeshNodeCatalog() {
    }

    /**
     * Serialize every node in the annotation-generated registry to a pretty-printed JSON catalog.
     *
     * @return JSON document with a top-level {@code "nodes"} array
     */
    public static String toJsonFromAnnotationRegistry() {
        return toJsonFromAnnotationRegistry(null);
    }

    /**
     * Serialize annotation-registered nodes whose {@link MeshNodeAnnotation#scopes() scopes}
     * include {@code scope}.
     *
     * @param scope scope id to filter by (e.g. {@code "mesh"} or {@code "dungeon"});
     *              {@code null} disables filtering
     * @return JSON document with a top-level {@code "nodes"} array
     */
    public static String toJsonFromAnnotationRegistry(String scope) {
        Map<String, Supplier<? extends MeshNode>> all = new TreeMap<>(MeshNodeRegistry_MeshNodes.MAP);
        all.putAll(NodeGraphRuntime.DESKTOP_SUPPLIERS);
        return toJson(all, scope);
    }

    /**
     * Serialize an arbitrary node registry to JSON without scope filtering.
     *
     * @param registry id-to-supplier map of nodes to include
     * @return JSON document with a top-level {@code "nodes"} array
     */
    public static String toJson(Map<String, Supplier<? extends MeshNode>> registry) {
        return toJson(registry, null);
    }

    /**
     * Serialize a node registry to JSON, optionally filtering by scope. Each entry includes the
     * id, package-derived category, scopes, input/output schema with socket docs, optional node
     * description, destructive flag with consumed inputs, and (for {@link RandomValueNode})
     * the per-mode output activation map.
     *
     * @param registry id-to-supplier map of nodes to include
     * @param scope scope id to filter by; {@code null} disables filtering
     * @return JSON document with a top-level {@code "nodes"} array
     */
    public static String toJson(Map<String, Supplier<? extends MeshNode>> registry, String scope) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Map.Entry<String, Supplier<? extends MeshNode>> e : new TreeMap<>(registry).entrySet()) {
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
            MeshNodeAnnotation ann = n.getClass().getAnnotation(MeshNodeAnnotation.class);
            if (ann != null && ann.desktopOnly()) {
                entry.put("desktopOnly", true);
            }
            entry.put("inputs", serializeInputs(schema.inputs(), schema.socketDocs()));
            entry.put("outputs", serializeOutputs(schema.outputs(), schema.socketDocs()));
            String desc = n.description();
            if (desc != null && !desc.isEmpty()) {
                entry.put(DESCRIPTION, desc);
            }
            if (schema.destructive()) {
                entry.put("destructive", true);
                entry.put("consumes", schema.consumes());
            }
            if (n instanceof RandomValueNode) {
                entry.put("outputActivationByMode", new TreeMap<>(RandomValueNode.OUTPUT_ACTIVATION_BY_MODE));
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
        if (lastDot < 0) return OTHER;
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
            default -> OTHER;
        };
    }

    private static List<Map<String, Object>> serializeInputs(
            List<InputPort> inputs, Map<String, String> socketDocs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (InputPort p : inputs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(NAME, p.name);
            m.put(PORTTYPE, p.type.name());
            String doc = socketDocs.get(p.name);
            if (doc != null && !doc.isEmpty()) {
                m.put(DESCRIPTION, doc);
            }
            m.put("defaultValue", p.defaultValue);
            ModeConstraint mc = p.modes;
            if (mc != null) {
                m.put("canonicalModes", mc.canonicalIds());
                m.put("aliases", new TreeMap<>(mc.aliasToCanonical()));
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
            m.put(NAME, p.name);
            m.put(PORTTYPE, p.type.name());
            String doc = socketDocs.get(p.name);
            if (doc != null && !doc.isEmpty()) {
                m.put(DESCRIPTION, doc);
            }
            out.add(m);
        }
        return out;
    }
}
