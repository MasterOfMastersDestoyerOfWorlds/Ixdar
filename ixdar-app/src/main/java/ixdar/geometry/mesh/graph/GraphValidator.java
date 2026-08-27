 package ixdar.geometry.mesh.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.nodes.api.InputPort;

import java.util.Set;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.MeshNodeSchema;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.parsing.python.PythonParser;

/**
 * Static validation pass over a parsed DSL graph: duplicate ids, unknown node types
 * and input ports, unknown edge sources, and edge type mismatches.
 *
 * <p>Type compatibility is permissive: FLOAT⇄INT and MESH⇄GEOMETRY_BUNDLE are legal.
 */
public final class GraphValidator {
    public static final String LINE = "Line ";
    public static final String STR = "'";
    public static final String STR_2 = "': ";
    public static final String NODE = ": Node '";
    public static final String STR_3 = ", ";
    public static final String STR_4 = ".";
    public static final String STR_5 = " (";
    public static final int NUM_4 = 4;

    private GraphValidator() {
    }

    /**
     * Validate a parsed graph with no DSL function definitions in scope.
     *
     * @param parsed   parsed statement list (topological order)
     * @param registry node type id to {@link MeshNode} class
     * @return list of human-readable error messages (empty when valid)
     */
    public static List<String> validate(List<PythonParser.ParsedNode> parsed,
            Map<String, Class<? extends MeshNode>> registry) {
        return validate(parsed, registry, Set.of());
    }

    /**
     * Validate a parsed graph, treating any node whose type is in
     * {@code functionNames} as a DSL function call (skipped here, validated at
     * runtime when its body executes).
     *
     * @param parsed        parsed statement list
     * @param registry      node type id to class
     * @param functionNames names of DSL function definitions in scope
     * @return list of human-readable error messages (empty when valid)
     */
    public static List<String> validate(List<PythonParser.ParsedNode> parsed,
            Map<String, Class<? extends MeshNode>> registry,
            Set<String> functionNames) {
        List<String> errors = new ArrayList<>();
        Map<String, PythonParser.ParsedNode> byId = new HashMap<>();
        for (PythonParser.ParsedNode n : parsed) {
            if (byId.containsKey(n.id)) {
                errors.add(LINE + n.line + ": Duplicate node id: " + n.id);
            }
            byId.put(n.id, n);
        }

        for (PythonParser.ParsedNode n : parsed) {
            if (functionNames.contains(n.type)) {
                // Function call — skip registry validation (validated at runtime)
                continue;
            }
            Class<? extends MeshNode> clazz = registry.get(n.type);
            if (clazz == null) {
                String msg = LINE + n.line + ": Unknown node type '" + n.type + "' for node '" + n.id + STR;
                String suggestion = findClosestType(n.type, registry.keySet());
                if (suggestion != null) {
                    msg += ". Did you mean '" + suggestion + "'?";
                }
                errors.add(msg);
                continue;
            }
            MeshNode instance;
            try {
                instance = clazz.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                errors.add(LINE + n.line + ": Cannot instantiate node type '" + n.type + STR_2 + e.getMessage());
                continue;
            }
            MeshNodeSchema schema = instance.schema();
            for (Map.Entry<String, Object> arg : n.arguments.entrySet()) {
                String portName = arg.getKey();
                Object val = arg.getValue();
                InputPort ip = findInput(schema, portName);
                if (ip == null) {
                    List<String> validInputs = schema.inputs().stream().map(p -> p.name).toList();
                    errors.add(LINE + n.line + NODE + n.id + "' has unknown input port '" + portName
                            + "'. Valid inputs: " + String.join(STR_3, validInputs));
                    continue;
                }
                if (val instanceof PythonParser.NodeReference ref) {
                    validateEdge(errors, byId, registry, n.id, n.line, ref, ip);
                }
            }
        }
        return errors;
    }

    /**
     * Returns non-fatal warnings about edges sourced from {@code random_value}
     * nodes. {@code random_value} populates only one of {@code float_out},
     * {@code int_out}, {@code vector_out} depending on its mode; the others are
     * null at runtime, which is rarely what the author intends.
     *
     * @param parsed   parsed statement list
     * @param registry node type id to class
     * @return human-readable warning strings (empty when no random_value edges)
     */
    public static List<String> validateWithRandomValueWarnings(List<PythonParser.ParsedNode> parsed,
            Map<String, Class<? extends MeshNode>> registry) {
        List<String> warnings = new ArrayList<>();
        Map<String, PythonParser.ParsedNode> byId = new HashMap<>();
        for (PythonParser.ParsedNode n : parsed) {
            byId.put(n.id, n);
        }
        for (PythonParser.ParsedNode n : parsed) {
            if (registry.get(n.type) == null) {
                continue;
            }
            for (Map.Entry<String, Object> arg : n.arguments.entrySet()) {
                Object val = arg.getValue();
                if (val instanceof PythonParser.NodeReference ref) {
                    PythonParser.ParsedNode src = byId.get(ref.nodeId);
                    if (src != null && "random_value".equals(src.type)) {
                        warnings.add("Link " + ref.nodeId + STR_4 + ref.portName + " -> " + n.id + STR_4
                                + arg.getKey()
                                + ": random_value fills only one of float_out/int_out/vector_out depending on mode; "
                                + "other outputs are null at runtime.");
                    }
                }
            }
        }
        return warnings;
    }

    private static void validateEdge(List<String> errors, Map<String, PythonParser.ParsedNode> byId,
            Map<String, Class<? extends MeshNode>> registry, String consumerId, int consumerLine,
            PythonParser.NodeReference ref, InputPort targetInput) {
        PythonParser.ParsedNode sourceNode = byId.get(ref.nodeId);
        if (sourceNode == null) {
            errors.add(LINE + consumerLine + ": Edge to '" + consumerId + "': unknown source node '" + ref.nodeId + STR);
            return;
        }
        Class<? extends MeshNode> sourceClass = registry.get(sourceNode.type);
        if (sourceClass == null) {
            errors.add(LINE + consumerLine + ": Edge from '" + ref.nodeId + "': unknown source node type '" + sourceNode.type + STR);
            return;
        }
        MeshNode sourceInstance;
        try {
            sourceInstance = sourceClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            errors.add(LINE + consumerLine + ": Cannot instantiate source '" + sourceNode.type + STR_2 + e.getMessage());
            return;
        }
        OutputPort out = findOutput(sourceInstance.schema(), ref.portName);
        if (out == null) {
            List<String> validOutputs = sourceInstance.schema().outputs().stream().map(p -> p.name).toList();
            errors.add(LINE + consumerLine + NODE + ref.nodeId + "' has no output port '" + ref.portName + "' (used from '"
                    + consumerId + "'). Valid outputs: " + String.join(STR_3, validOutputs));
            return;
        }
        if (!portTypesCompatible(out.type, targetInput.type)) {
            errors.add(LINE + consumerLine + ": Type mismatch " + ref.nodeId + STR_4 + ref.portName + STR_5 + out.type + ") -> "
                    + consumerId + STR_4 + targetInput.name + STR_5 + targetInput.type + ")");
        }
    }

    private static boolean portTypesCompatible(PortType from, PortType to) {
        if (from == to) {
            return true;
        }
        if ((from == PortType.FLOAT && to == PortType.INT)
                || (from == PortType.INT && to == PortType.FLOAT)) {
            return true;
        }
        if (from == PortType.MESH && to == PortType.GEOMETRY_BUNDLE) {
            return true;
        }
        if (from == PortType.GEOMETRY_BUNDLE && to == PortType.MESH) {
            return true;
        }
        if (from == PortType.CLOSURE && to == PortType.CLOSURE) {
            return true;
        }
        return false;
    }

    private static InputPort findInput(MeshNodeSchema schema, String name) {
        for (InputPort p : schema.inputs()) {
            if (p.name.equals(name)) {
                return p;
            }
        }
        return null;
    }

    private static OutputPort findOutput(MeshNodeSchema schema, String name) {
        for (OutputPort p : schema.outputs()) {
            if (p.name.equals(name)) {
                return p;
            }
        }
        return null;
    }

    /** Returns the closest matching type name, or null if none within edit distance 3. */
    static String findClosestType(String unknown, Collection<String> knownTypes) {
        String best = null;
        int bestDist = NUM_4; // threshold: only suggest if distance ≤ 3
        for (String known : knownTypes) {
            int d = editDistance(unknown, known);
            if (d < bestDist) {
                bestDist = d;
                best = known;
            }
        }
        return best;
    }

    private static int editDistance(String a, String b) {
        int m = a.length(), n = b.length();
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[n];
    }
}
