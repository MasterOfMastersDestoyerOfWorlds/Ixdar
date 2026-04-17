 package ixdar.geometry.mesh.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeSchema;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.parsing.python.PythonParser;

public final class GraphValidator {

    private GraphValidator() {
    }

    public static List<String> validate(List<PythonParser.ParsedNode> parsed,
            Map<String, Class<? extends MeshNode>> registry) {
        return validate(parsed, registry, java.util.Set.of());
    }

    public static List<String> validate(List<PythonParser.ParsedNode> parsed,
            Map<String, Class<? extends MeshNode>> registry,
            java.util.Set<String> functionNames) {
        List<String> errors = new ArrayList<>();
        Map<String, PythonParser.ParsedNode> byId = new HashMap<>();
        for (PythonParser.ParsedNode n : parsed) {
            if (byId.containsKey(n.id)) {
                errors.add("Line " + n.line + ": Duplicate node id: " + n.id);
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
                String msg = "Line " + n.line + ": Unknown node type '" + n.type + "' for node '" + n.id + "'";
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
                errors.add("Line " + n.line + ": Cannot instantiate node type '" + n.type + "': " + e.getMessage());
                continue;
            }
            MeshNodeSchema schema = instance.schema();
            for (Map.Entry<String, Object> arg : n.arguments.entrySet()) {
                String portName = arg.getKey();
                Object val = arg.getValue();
                InputPort ip = findInput(schema, portName);
                if (ip == null) {
                    List<String> validInputs = schema.inputs().stream().map(InputPort::name).toList();
                    errors.add("Line " + n.line + ": Node '" + n.id + "' has unknown input port '" + portName
                            + "'. Valid inputs: " + String.join(", ", validInputs));
                    continue;
                }
                if (val instanceof PythonParser.NodeReference ref) {
                    validateEdge(errors, byId, registry, n.id, n.line, ref, ip);
                }
            }
        }
        return errors;
    }

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
                        warnings.add("Link " + ref.nodeId + "." + ref.portName + " -> " + n.id + "."
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
            errors.add("Line " + consumerLine + ": Edge to '" + consumerId + "': unknown source node '" + ref.nodeId + "'");
            return;
        }
        Class<? extends MeshNode> sourceClass = registry.get(sourceNode.type);
        if (sourceClass == null) {
            errors.add("Line " + consumerLine + ": Edge from '" + ref.nodeId + "': unknown source node type '" + sourceNode.type + "'");
            return;
        }
        MeshNode sourceInstance;
        try {
            sourceInstance = sourceClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            errors.add("Line " + consumerLine + ": Cannot instantiate source '" + sourceNode.type + "': " + e.getMessage());
            return;
        }
        OutputPort out = findOutput(sourceInstance.schema(), ref.portName);
        if (out == null) {
            List<String> validOutputs = sourceInstance.schema().outputs().stream().map(OutputPort::name).toList();
            errors.add("Line " + consumerLine + ": Node '" + ref.nodeId + "' has no output port '" + ref.portName + "' (used from '"
                    + consumerId + "'). Valid outputs: " + String.join(", ", validOutputs));
            return;
        }
        if (!portTypesCompatible(out.type(), targetInput.type())) {
            errors.add("Line " + consumerLine + ": Type mismatch " + ref.nodeId + "." + ref.portName + " (" + out.type() + ") -> "
                    + consumerId + "." + targetInput.name() + " (" + targetInput.type() + ")");
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
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    private static OutputPort findOutput(MeshNodeSchema schema, String name) {
        for (OutputPort p : schema.outputs()) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    /** Returns the closest matching type name, or null if none within edit distance 3. */
    static String findClosestType(String unknown, Collection<String> knownTypes) {
        String best = null;
        int bestDist = 4; // threshold: only suggest if distance ≤ 3
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
