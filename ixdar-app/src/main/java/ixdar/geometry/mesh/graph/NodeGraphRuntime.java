package ixdar.geometry.mesh.graph;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.FieldContext;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeRegistry_MeshNodes;
import ixdar.annotations.meshnode.Vector3Value;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.parsing.python.PythonParser;

public class NodeGraphRuntime {
    private final Map<String, Class<? extends MeshNode>> nodeRegistry = new HashMap<>();
    private final Map<String, PythonParser.FunctionDef> functionDefs = new HashMap<>();

    private final Map<String, GraphNodeContext> evaluatedNodes = new HashMap<>();

    /** Per-node timing from last graph execution. Entries: "id (type) → ms". */
    private final java.util.LinkedHashMap<String, Long> lastTimingMs = new java.util.LinkedHashMap<>();
    private long lastTotalMs;

    /** Returns per-node timing from the most recent {@code executeGraphResult} call. */
    public java.util.LinkedHashMap<String, Long> lastTimingMs() {
        return lastTimingMs;
    }

    public long lastTotalMs() {
        return lastTotalMs;
    }

    public void registerNode(String type, Class<? extends MeshNode> nodeClass) {
        nodeRegistry.put(type, nodeClass);
    }

    /** Register DSL function definitions (from parser or skill library). */
    public void registerFunctionDefs(Map<String, PythonParser.FunctionDef> defs) {
        functionDefs.putAll(defs);
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
     * Runs the graph with optional parameter overrides.
     * <p>Keys in {@code overridesByNodeId}:
     * <ul>
     *   <li>{@code "nodeId"} — overrides the {@code default} port on input_float/int/boolean nodes
     *   <li>{@code "nodeId.argName"} — overrides a literal argument on any node
     *   <li>{@code "nodeId.argName.x/y/z"} — overrides a single component of a Vector3Value argument
     * </ul>
     */
    public Object executeGraphResult(List<PythonParser.ParsedNode> parsedStatements, String finalOutputId,
            String outputPortName, Map<String, Object> overridesByNodeId) throws Exception {
        evaluatedNodes.clear();
        lastTimingMs.clear();
        long graphStart = System.nanoTime();

        FieldContext currentFieldContext = null;
        Map<String, Object> overrides = overridesByNodeId == null ? Map.of() : overridesByNodeId;

        for (PythonParser.ParsedNode parsedData : parsedStatements) {
            // Resolve argument values (shared between node and function calls)
            Map<String, Object> resolvedArgs = new HashMap<>();
            for (Map.Entry<String, Object> arg : parsedData.arguments.entrySet()) {
                String portName = arg.getKey();
                Object rawValue = arg.getValue();

                // Check for dot-notation literal override (e.g. "thumb_attach.theta")
                String literalKey = parsedData.id + "." + portName;
                if (overrides.containsKey(literalKey)) {
                    resolvedArgs.put(portName, overrides.get(literalKey));
                } else if (rawValue instanceof PythonParser.NodeReference ref) {
                    GraphNodeContext sourceContext = evaluatedNodes.get(ref.nodeId);
                    if (sourceContext == null) {
                        throw new RuntimeException("Node '" + ref.nodeId + "' was referenced before it was evaluated!");
                    }
                    resolvedArgs.put(portName, sourceContext.getOutput(ref.portName));
                } else {
                    resolvedArgs.put(portName, rawValue);
                }
            }

            // Vec3 component overrides (e.g. "node.translation.x")
            for (Map.Entry<String, Object> arg : parsedData.arguments.entrySet()) {
                String portName = arg.getKey();
                if (!(arg.getValue() instanceof Vector3Value v3)) continue;
                String base = parsedData.id + "." + portName;
                Object oxObj = overrides.get(base + ".x");
                Object oyObj = overrides.get(base + ".y");
                Object ozObj = overrides.get(base + ".z");
                if (oxObj != null || oyObj != null || ozObj != null) {
                    float x = oxObj instanceof Number n ? n.floatValue() : v3.x();
                    float y = oyObj instanceof Number n ? n.floatValue() : v3.y();
                    float z = ozObj instanceof Number n ? n.floatValue() : v3.z();
                    resolvedArgs.put(portName, new Vector3Value(x, y, z));
                }
            }

            PythonParser.FunctionDef funcDef = functionDefs.get(parsedData.type);
            if (funcDef != null) {
                // Function call: execute body with parameter binding
                long nodeStart = System.nanoTime();
                GraphNodeContext resultCtx = executeFunctionCall(funcDef, resolvedArgs, currentFieldContext, overrides);
                long nodeMs = (System.nanoTime() - nodeStart) / 1_000_000;
                lastTimingMs.put(parsedData.id + " (" + parsedData.type + ")", nodeMs);

                MeshTopology meshOut = meshFromNodeOutputs(resultCtx);
                if (meshOut != null && meshOut.vertexCount() > 0) {
                    currentFieldContext = new FieldContextImpl(meshOut);
                }
                evaluatedNodes.put(parsedData.id, resultCtx);
            } else {
                // Regular node call
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

                for (Map.Entry<String, Object> resolved : resolvedArgs.entrySet()) {
                    context.setInputValue(resolved.getKey(), resolved.getValue());
                }

                if (overrides.containsKey(parsedData.id) && isInputParameterNode(parsedData.type)) {
                    context.setInputValue("default", overrides.get(parsedData.id));
                }

                long nodeStart = System.nanoTime();
                activeNode.evaluate(context);
                long nodeMs = (System.nanoTime() - nodeStart) / 1_000_000;
                lastTimingMs.put(parsedData.id + " (" + parsedData.type + ")", nodeMs);

                MeshTopology meshOut = meshFromNodeOutputs(context);
                if (meshOut != null && meshOut.vertexCount() > 0) {
                    currentFieldContext = new FieldContextImpl(meshOut);
                }
                evaluatedNodes.put(parsedData.id, context);
            }
        }

        lastTotalMs = (System.nanoTime() - graphStart) / 1_000_000;

        GraphNodeContext finalContext = evaluatedNodes.get(finalOutputId);
        if (finalContext != null) {
            return finalContext.getOutput(outputPortName);
        }
        return null;
    }

    /**
     * Executes a function definition's body with the given arguments bound to parameters.
     * Parameters become synthetic nodes whose outputs carry the bound values.
     * Returns the context of the last node in the body (the function's return value).
     */
    private GraphNodeContext executeFunctionCall(PythonParser.FunctionDef funcDef,
            Map<String, Object> callArgs, FieldContext callerFieldContext,
            Map<String, Object> overrides) throws Exception {
        Map<String, GraphNodeContext> localNodes = new HashMap<>();
        FieldContext localFieldContext = callerFieldContext;

        // Bind parameters: create synthetic contexts for each parameter name
        for (PythonParser.FunctionParam param : funcDef.params) {
            Object value = callArgs.get(param.name);
            if (value == null) {
                throw new RuntimeException("Function '" + funcDef.name + "' missing argument '" + param.name + "'");
            }
            GraphNodeContext paramCtx = new GraphNodeContext();
            // Parameter nodes expose their value on a "result" port and also
            // on the conventional port name for their type (mesh, geometry, etc.)
            paramCtx.setOutput("result", value);
            if (value instanceof MeshTopology) {
                paramCtx.setOutput("mesh", value);
            } else if (value instanceof GeometryBundle) {
                paramCtx.setOutput("geometry", value);
                paramCtx.setOutput("mesh", value);
            }
            localNodes.put(param.name, paramCtx);
        }

        GraphNodeContext lastContext = null;

        for (PythonParser.ParsedNode bodyNode : funcDef.body) {
            // Resolve body node arguments from local scope
            Map<String, Object> resolvedArgs = new HashMap<>();
            for (Map.Entry<String, Object> arg : bodyNode.arguments.entrySet()) {
                Object rawValue = arg.getValue();
                if (rawValue instanceof PythonParser.NodeReference ref) {
                    GraphNodeContext sourceContext = localNodes.get(ref.nodeId);
                    if (sourceContext == null) {
                        // Also check caller scope for nodes declared outside the function
                        sourceContext = evaluatedNodes.get(ref.nodeId);
                    }
                    if (sourceContext == null) {
                        throw new RuntimeException("In function '" + funcDef.name + "': node '"
                                + ref.nodeId + "' was referenced before it was evaluated!");
                    }
                    resolvedArgs.put(arg.getKey(), sourceContext.getOutput(ref.portName));
                } else if (rawValue instanceof String bareId) {
                    // Bare identifier in function body: check if it matches a parameter
                    // or local node name (e.g., `cube(size=size)` where `size` is a param)
                    GraphNodeContext paramCtx = localNodes.get(bareId);
                    if (paramCtx != null) {
                        resolvedArgs.put(arg.getKey(), paramCtx.getOutput("result"));
                    } else {
                        resolvedArgs.put(arg.getKey(), rawValue);
                    }
                } else {
                    resolvedArgs.put(arg.getKey(), rawValue);
                }
            }

            // Check if this is a nested function call
            PythonParser.FunctionDef nestedFunc = functionDefs.get(bodyNode.type);
            if (nestedFunc != null) {
                lastContext = executeFunctionCall(nestedFunc, resolvedArgs, localFieldContext, overrides);
            } else {
                if (nodeRegistry.get(bodyNode.type) == null) {
                    throw new IllegalArgumentException("In function '" + funcDef.name
                            + "': unknown node type: " + bodyNode.type);
                }
                java.util.function.Supplier<? extends MeshNode> supplier =
                        MeshNodeRegistry_MeshNodes.MAP.get(bodyNode.type);
                if (supplier == null) {
                    throw new IllegalStateException("No mesh node supplier for type: " + bodyNode.type);
                }
                MeshNode activeNode = supplier.get();

                lastContext = new GraphNodeContext();
                lastContext.setFieldContext(localFieldContext);

                for (Map.Entry<String, Object> resolved : resolvedArgs.entrySet()) {
                    lastContext.setInputValue(resolved.getKey(), resolved.getValue());
                }

                activeNode.evaluate(lastContext);
            }

            MeshTopology meshOut = meshFromNodeOutputs(lastContext);
            if (meshOut != null && meshOut.vertexCount() > 0) {
                localFieldContext = new FieldContextImpl(meshOut);
            }

            localNodes.put(bodyNode.id, lastContext);
        }

        if (lastContext == null) {
            throw new RuntimeException("Function '" + funcDef.name + "' has empty body");
        }
        return lastContext;
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
