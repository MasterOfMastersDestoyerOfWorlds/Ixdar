package ixdar.geometry.mesh.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeRegistry_MeshNodes;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.nodes.api.Vector3Value;
import ixdar.parsing.python.PythonParser;

/**
 * Discovers numeric literal arguments in non-input DSL nodes that can be perturbed
 * by the skeleton sensitivity optimizer. Each descriptor represents a single scalar
 * value (for vec3 arguments, three descriptors are emitted with .x/.y/.z suffixes).
 *
 * <p>Bounds come from the {@link InputPort#minValue()}/{@link InputPort#maxValue()}
 * declared on each node's port definition.
 */
public record LiteralParameterDescriptor(
        String nodeId,
        String nodeType,
        String argName,
        String overrideKey,
        float defaultValue,
        Float minValue,
        Float maxValue) {
    public static final String STR = ".";

    private static final Set<String> INPUT_NODE_TYPES = Set.of(
            "input_float", "input_int", "input_boolean", "float_curve");

    private static final Set<String> BLOCKLISTED_NODE_TYPES = Set.of("compare");

    private static final Set<String> BLOCKLISTED_PORTS = Set.of(
            "set_bone_weight.weight",
            "mark_crease.weight");

    /**
     * Scans all parsed nodes for literal numeric arguments on FLOAT and VECTOR3 ports.
     * Skips input parameter nodes (handled by {@link InputParameterDescriptor}),
     * INT-typed ports (structural), and blocklisted node/port combinations.
     */
    public static List<LiteralParameterDescriptor> collect(List<PythonParser.ParsedNode> nodes) {
        List<LiteralParameterDescriptor> out = new ArrayList<>();
        for (PythonParser.ParsedNode n : nodes) {
            if (INPUT_NODE_TYPES.contains(n.type)) continue;
            if (BLOCKLISTED_NODE_TYPES.contains(n.type)) continue;

            Map<String, InputPort> portMap = getPortMap(n.type);
            if (portMap == null) continue;

            for (Map.Entry<String, Object> arg : n.arguments.entrySet()) {
                String argName = arg.getKey();
                Object value = arg.getValue();

                if (value instanceof PythonParser.NodeReference) continue;
                if (value instanceof String) continue;
                if (value instanceof Boolean) continue;

                InputPort port = portMap.get(argName);
                if (port == null) continue;

                String blockKey = n.type + STR + argName;
                if (BLOCKLISTED_PORTS.contains(blockKey)) continue;

                if (port.type == PortType.FLOAT && value instanceof Number num) {
                    out.add(new LiteralParameterDescriptor(
                            n.id, n.type, argName,
                            n.id + STR + argName,
                            num.floatValue(),
                            port.minValue, port.maxValue));
                } else if (port.type == PortType.VECTOR3 && value instanceof Vector3Value v3) {
                    Float min = port.minValue;
                    Float max = port.maxValue;
                    out.add(new LiteralParameterDescriptor(
                            n.id, n.type, argName,
                            n.id + STR + argName + ".x",
                            v3.x(), min, max));
                    out.add(new LiteralParameterDescriptor(
                            n.id, n.type, argName,
                            n.id + STR + argName + ".y",
                            v3.y(), min, max));
                    out.add(new LiteralParameterDescriptor(
                            n.id, n.type, argName,
                            n.id + STR + argName + ".z",
                            v3.z(), min, max));
                }
                // Skip INT, STRING, BOOLEAN, MESH, GEOMETRY_BUNDLE, CLOSURE, ROTATION
            }
        }
        return out;
    }

    private static Map<String, InputPort> getPortMap(String nodeType) {
        var supplier = MeshNodeRegistry_MeshNodes.MAP.get(nodeType);
        if (supplier == null) return null;
        try {
            MeshNode node = supplier.get();
            List<InputPort> inputs = node.inputs();
            Map<String, InputPort> map = new HashMap<>(inputs.size());
            for (InputPort ip : inputs) {
                map.put(ip.name, ip);
            }
            return map;
        } catch (Exception e) {
            return null;
        }
    }
}
