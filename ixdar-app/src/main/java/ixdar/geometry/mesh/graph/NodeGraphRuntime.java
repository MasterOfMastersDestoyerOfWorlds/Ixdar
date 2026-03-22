package ixdar.geometry.mesh.graph;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.FieldContext;
import ixdar.annotations.meshnode.MeshNode;
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
        try {
            Class<?> registryClass = Class.forName("ixdar.annotations.meshnode.MeshNodeRegistry_MeshNodes");
            @SuppressWarnings("unchecked")
            Map<String, java.util.function.Supplier<? extends MeshNode>> map = (Map<String, java.util.function.Supplier<? extends MeshNode>>) registryClass
                    .getField("MAP")
                    .get(null);
            Map<String, Class<? extends MeshNode>> out = new HashMap<>();
            for (Map.Entry<String, java.util.function.Supplier<? extends MeshNode>> e : map.entrySet()) {
                MeshNode probe = e.getValue().get();
                out.put(e.getKey(), probe.getClass());
            }
            return Collections.unmodifiableMap(out);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to load MeshNodeRegistry_MeshNodes", e);
        }
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
        evaluatedNodes.clear();

        FieldContext currentFieldContext = null;

        for (PythonParser.ParsedNode parsedData : parsedStatements) {
            Class<? extends MeshNode> clazz = nodeRegistry.get(parsedData.type);
            if (clazz == null) {
                throw new IllegalArgumentException("Unknown node type: " + parsedData.type);
            }
            MeshNode activeNode = clazz.getDeclaredConstructor().newInstance();

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
        Object result = executeGraphResult(parsedStatements, finalOutputId, outputPortName);
        if (result instanceof MeshTopology m) {
            return m;
        }
        if (result instanceof GeometryBundle g) {
            return g.mesh();
        }
        return null;
    }
}
