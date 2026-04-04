package ixdar.geometry.mesh.graph;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.FieldContext;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeRegistry_MeshNodes;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.parsing.python.PythonParser;

public class NodeGraphRuntime {
    private final Map<String, Class<? extends MeshNode>> nodeRegistry = new HashMap<>();

    private final Map<String, GraphNodeContext> evaluatedNodes = new HashMap<>();

    public void registerNode(String type, Class<? extends MeshNode> nodeClass) {
        nodeRegistry.put(type, nodeClass);
    }

    /**
     * Map of DSL id → node class from the generated {@code MeshNodeRegistry_MeshNodes.MAP}.
     */
    public static Map<String, Class<? extends MeshNode>> annotationRegistryClasses() {
        Map<String, java.util.function.Supplier<? extends MeshNode>> map = MeshNodeRegistry_MeshNodes.MAP;
        Map<String, Class<? extends MeshNode>> out = new HashMap<>();
        for (Map.Entry<String, java.util.function.Supplier<? extends MeshNode>> e : map.entrySet()) {
            MeshNode probe = e.getValue().get();
            out.put(e.getKey(), probe.getClass());
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * Registers every {@link ixdar.annotations.meshnode.MeshNodeAnnotation} id from the generated
     * {@code MeshNodeRegistry_MeshNodes.MAP}.
     */
    public void registerAllFromAnnotationRegistry() {
        for (Map.Entry<String, Class<? extends MeshNode>> e : annotationRegistryClasses().entrySet()) {
            registerNode(e.getKey(), e.getValue());
        }
    }

    /**
     * User-editable inputs and curve parameters in a parsed graph (literal metadata for UI).
     */
    public static List<InputParameterDescriptor> collectInputParameters(List<PythonParser.ParsedNode> parsedStatements) {
        return InputParameterDescriptor.collect(parsedStatements);
    }

    /**
     * Runs the graph and returns the final node's {@code mesh} output (backward compatible).
     */
    public MeshTopology executeGraph(List<PythonParser.ParsedNode> parsedStatements, String finalOutputId) throws Exception {
        return executeGraphToMesh(parsedStatements, finalOutputId, "mesh");
    }

    /**
     * Runs the graph and returns the final value on the given output port.
     */
    public Object executeGraphResult(List<PythonParser.ParsedNode> parsedStatements, String finalOutputId,
            String outputPortName) throws Exception {
        return executeGraphResult(parsedStatements, finalOutputId, outputPortName, Map.of());
    }

    /**
     * Runs the graph; {@code overridesByNodeId} replaces the {@code default} port value for
     * {@code input_float}, {@code input_int}, and {@code input_boolean} nodes (key = DSL assignment id).
     */
    public Object executeGraphResult(List<PythonParser.ParsedNode> parsedStatements, String finalOutputId,
            String outputPortName, Map<String, Object> overridesByNodeId) throws Exception {
        evaluatedNodes.clear();

        FieldContext currentFieldContext = null;
        Map<String, Object> overrides = overridesByNodeId == null ? Map.of() : overridesByNodeId;

        for (PythonParser.ParsedNode parsedData : parsedStatements) {
            if (nodeRegistry.get(parsedData.type) == null) {
                throw new IllegalArgumentException("Unknown node type: " + parsedData.type);
            }
            java.util.function.Supplier<? extends MeshNode> supplier =
                    MeshNodeRegistry_MeshNodes.MAP.get(parsedData.type);
            if (supplier == null) {
                throw new IllegalStateException("No mesh node supplier for type: " + parsedData.type);
            }
            MeshNode activeNode = supplier.get();

            GraphNodeContext context = new GraphNodeContext();
            context.setFieldContext(currentFieldContext);

            for (Map.Entry<String, Object> arg : parsedData.arguments.entrySet()) {
                String portName = arg.getKey();
                Object rawValue = arg.getValue();

                if (rawValue instanceof PythonParser.NodeReference ref) {
                    GraphNodeContext sourceContext = evaluatedNodes.get(ref.nodeId);

                    if (sourceContext == null) {
                        throw new RuntimeException("Node '" + ref.nodeId + "' was referenced before it was evaluated!");
                    }

                    Object incomingData = sourceContext.getOutput(ref.portName);
                    context.setInputValue(portName, incomingData);
                } else {
                    context.setInputValue(portName, rawValue);
                }
            }

            if (overrides.containsKey(parsedData.id) && isInputParameterNode(parsedData.type)) {
                context.setInputValue("default", overrides.get(parsedData.id));
            }

            activeNode.evaluate(context);

            MeshTopology meshOut = meshFromNodeOutputs(context);
            if (meshOut != null && meshOut.vertexCount() > 0) {
                currentFieldContext = new FieldContextImpl(meshOut);
            }

            evaluatedNodes.put(parsedData.id, context);
        }

        GraphNodeContext finalContext = evaluatedNodes.get(finalOutputId);
        if (finalContext != null) {
            return finalContext.getOutput(outputPortName);
        }
        return null;
    }

    private static boolean isInputParameterNode(String type) {
        return "input_float".equals(type) || "input_int".equals(type) || "input_boolean".equals(type);
    }

    private static MeshTopology meshFromNodeOutputs(GraphNodeContext context) {
        for (Object v : context.getOutputsSnapshot().values()) {
            MeshTopology m = meshFromValue(v);
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    private static MeshTopology meshFromValue(Object v) {
        if (v instanceof MeshTopology m) {
            return m;
        }
        if (v instanceof GeometryBundle g) {
            return g.mesh();
        }
        return null;
    }

    /**
     * Returns a {@link MeshTopology} from the final port, unwrapping {@link GeometryBundle} if needed.
     */
    public MeshTopology executeGraphToMesh(List<PythonParser.ParsedNode> parsedStatements, String finalOutputId,
            String outputPortName) throws Exception {
        return executeGraphToMesh(parsedStatements, finalOutputId, outputPortName, Map.of());
    }

    public MeshTopology executeGraphToMesh(List<PythonParser.ParsedNode> parsedStatements, String finalOutputId,
            String outputPortName, Map<String, Object> overridesByNodeId) throws Exception {
        Object result = executeGraphResult(parsedStatements, finalOutputId, outputPortName, overridesByNodeId);
        if (result instanceof MeshTopology m) {
            return m;
        }
        if (result instanceof GeometryBundle g) {
            return g.mesh();
        }
        return null;
    }
}
