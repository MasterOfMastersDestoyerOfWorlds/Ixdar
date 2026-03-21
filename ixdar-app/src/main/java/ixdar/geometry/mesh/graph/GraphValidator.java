 package ixdar.geometry.mesh.graph;

import java.util.ArrayList;
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
        List<String> errors = new ArrayList<>();
        Map<String, PythonParser.ParsedNode> byId = new HashMap<>();
        for (PythonParser.ParsedNode n : parsed) {
            if (byId.containsKey(n.id)) {
                errors.add("Duplicate node id: " + n.id);
            }
            byId.put(n.id, n);
        }

        for (PythonParser.ParsedNode n : parsed) {
            Class<? extends MeshNode> clazz = registry.get(n.type);
            if (clazz == null) {
                errors.add("Unknown node type '" + n.type + "' for node '" + n.id + "'");
                continue;
            }
            MeshNode instance;
            try {
                instance = clazz.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                errors.add("Cannot instantiate node type '" + n.type + "': " + e.getMessage());
                continue;
            }
            MeshNodeSchema schema = instance.schema();
            for (Map.Entry<String, Object> arg : n.arguments.entrySet()) {
                String portName = arg.getKey();
                Object val = arg.getValue();
                InputPort ip = findInput(schema, portName);
                if (ip == null) {
                    errors.add("Node '" + n.id + "' has unknown input port '" + portName + "'");
                    continue;
                }
                if (val instanceof PythonParser.NodeReference ref) {
                    validateEdge(errors, byId, registry, n.id, ref, ip);
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
            Map<String, Class<? extends MeshNode>> registry, String consumerId,
            PythonParser.NodeReference ref, InputPort targetInput) {
        PythonParser.ParsedNode sourceNode = byId.get(ref.nodeId);
        if (sourceNode == null) {
            errors.add("Edge to '" + consumerId + "': unknown source node '" + ref.nodeId + "'");
            return;
        }
        Class<? extends MeshNode> sourceClass = registry.get(sourceNode.type);
        if (sourceClass == null) {
            errors.add("Edge from '" + ref.nodeId + "': unknown source node type '" + sourceNode.type + "'");
            return;
        }
        MeshNode sourceInstance;
        try {
            sourceInstance = sourceClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            errors.add("Cannot instantiate source '" + sourceNode.type + "': " + e.getMessage());
            return;
        }
        OutputPort out = findOutput(sourceInstance.schema(), ref.portName);
        if (out == null) {
            errors.add("Node '" + ref.nodeId + "' has no output port '" + ref.portName + "' (used from '"
                    + consumerId + "')");
            return;
        }
        if (!portTypesCompatible(out.type(), targetInput.type())) {
            errors.add("Type mismatch " + ref.nodeId + "." + ref.portName + " (" + out.type() + ") -> "
                    + consumerId + "." + targetInput.name() + " (" + targetInput.type() + ")");
        }
    }

    private static boolean portTypesCompatible(PortType from, PortType to) {
        if (from == to) {
            return true;
        }
        if (from == PortType.FLOAT && to == PortType.INT) {
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
}
