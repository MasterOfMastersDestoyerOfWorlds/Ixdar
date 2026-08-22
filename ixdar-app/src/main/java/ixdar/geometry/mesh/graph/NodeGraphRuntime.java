package ixdar.geometry.mesh.graph;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import java.util.LinkedHashMap;
import java.util.Map;

import ixdar.annotations.meshnode.FieldContext;

import java.util.function.Supplier;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeRegistry_MeshNodes;
import ixdar.annotations.meshnode.Vector3Value;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;
import ixdar.platform.Platforms;

/**
 * Executes parsed DSL graphs by dispatching each statement to a registered
 * {@link MeshNode} (or inlined {@link PythonParser.FunctionDef}), threading
 * outputs along {@link PythonParser.NodeReference} edges and tracking the
 * latest {@link MeshTopology} as the implicit field context for downstream
 * nodes.
 */
public class NodeGraphRuntime {
    public static final String MESH = "mesh";
    public static final String STR = ".";
    public static final String WAS_REFERENCED_BEFORE_IT_WAS_EVALUATED = "' was referenced before it was evaluated!";
    public static final String STR_2 = " (";
    public static final String STR_3 = ")";
    public static final String NO_MESH_NODE_SUPPLIER_FOR_TYPE = "No mesh node supplier for type: ";
    public static final String GEOMETRY = "geometry";
    public static final String RESULT = "result";
    public static final String FUNCTION = "Function '";
    public static final String IN_FUNCTION = "In function '";
    public static final int NUM_1_000_000 = 1_000_000;

    /** Per-node wall time at or above which {@link #logTimings} names the node. */
    public static final long SLOW_NODE_MS = 100;

    /**
     * Id-to-class view of the generated node registry, built once at class load. Building it
     * instantiates one probe per registered node, so it is cached rather than rebuilt per graph.
     * Desktop-only nodes merge in through {@link #desktopRegistryMap()}'s reflective firewall.
     */
    public static final Map<String, Class<? extends MeshNode>> REGISTRY_CLASSES;

    /** Desktop-only suppliers, empty in builds where their registry class cannot load. */
    public static final Map<String, Supplier<? extends MeshNode>> DESKTOP_SUPPLIERS = desktopRegistryMap();

    static {
        Map<String, Class<? extends MeshNode>> out = new HashMap<>();
        for (Map.Entry<String, Supplier<? extends MeshNode>> e : MeshNodeRegistry_MeshNodes.MAP.entrySet()) {
            MeshNode probe = e.getValue().get();
            out.put(e.getKey(), probe.getClass());
        }
        for (Map.Entry<String, Supplier<? extends MeshNode>> e : DESKTOP_SUPPLIERS.entrySet()) {
            MeshNode probe = e.getValue().get();
            out.put(e.getKey(), probe.getClass());
        }
        REGISTRY_CLASSES = Collections.unmodifiableMap(out);
    }

    private final Map<String, Class<? extends MeshNode>> nodeRegistry = new HashMap<>();
    private final Map<String, PythonParser.FunctionDef> functionDefs = new HashMap<>();

    private final Map<String, GraphNodeContext> evaluatedNodes = new HashMap<>();

    /** Per-node timing from last graph execution. Entries: "id (type) → ms". */
    private final LinkedHashMap<String, Long> lastTimingMs = new LinkedHashMap<>();
    private long lastTotalMs;

    /**
     * Returns per-node timing from the most recent {@code executeGraphResult} call.
     *
     * @return insertion-ordered map keyed by {@code "id (type)"} with milliseconds spent in
     *         each node's evaluation
     */
    public LinkedHashMap<String, Long> lastTimingMs() {
        return lastTimingMs;
    }

    /**
     * Total wall time of the most recent {@code executeGraphResult} call.
     *
     * @return total graph execution time in milliseconds
     */
    public long lastTotalMs() {
        return lastTotalMs;
    }

    /**
     * Returns the output value of a previously-executed node, or {@code null} if the node never
     * ran or the port doesn't exist. Used by viewer scenes that need to grab intermediate
     * values from the DSL graph (e.g. the dungeon viewer wiring the {@code TileGrid} produced
     * by {@code astar_corridors_3d} into the player controller's collision world).
     *
     * @param nodeId id of the previously-executed graph node
     * @param outputPortName name of the output port to read
     * @return value published on that port, or {@code null} if the node never ran or the port is missing
     */
    public Object getNodeOutput(String nodeId, String outputPortName) {
        GraphNodeContext ctx = evaluatedNodes.get(nodeId);
        if (ctx == null) return null;
        return ctx.getOutput(outputPortName);
    }

    /**
     * Registers a node implementation under the DSL id used to call it.
     *
     * @param type DSL id (e.g. {@code "cube"}, {@code "extrude_mesh"})
     * @param nodeClass {@link MeshNode} class instantiated for each call site of {@code type}
     */
    public void registerNode(String type, Class<? extends MeshNode> nodeClass) {
        nodeRegistry.put(type, nodeClass);
    }

    /**
     * Register DSL function definitions (from parser or skill library).
     *
     * @param defs function name to parsed body, merged into the runtime's function table
     */
    public void registerFunctionDefs(Map<String, PythonParser.FunctionDef> defs) {
        functionDefs.putAll(defs);
    }

    /**
     * The desktop-only registry, reached through {@code Class.forName} so the class name never
     * appears as a reference TeaVM can walk; in the browser the lookup fails and the map is empty.
     * The same firewall idiom keeps {@code AudioSystem} out of the web build.
     *
     * @return desktop-only node suppliers by id; empty when the class cannot load
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Supplier<? extends MeshNode>> desktopRegistryMap() {
        try {
            Class<?> desktop = Class.forName(String.join(STR,
                    MeshNodeRegistry_MeshNodes.class.getPackageName(),
                    MeshNodeRegistry_MeshNodes.class.getSimpleName() + "Desktop"));
            return (Map<String, Supplier<? extends MeshNode>>) desktop.getField("MAP").get(null);
        } catch (Throwable unavailable) {
            return Map.of();
        }
    }

    /**
     * Log the last execution's total wall time, plus every node that took 100ms or more. The
     * project's rule of thumb: most steps take under a second, and anything slow usually means a
     * planning or correctness problem, so slowness must be visible without extra machinery.
     *
     * @param prefix log prefix naming the caller, e.g. {@code "[mesh-viewer]"}
     */
    public void logTimings(String prefix) {
        StringBuilder line = new StringBuilder(prefix).append(" graph ").append(lastTotalMs).append("ms");
        for (Map.Entry<String, Long> timing : lastTimingMs.entrySet()) {
            if (timing.getValue() >= SLOW_NODE_MS) {
                line.append("  ").append(timing.getKey()).append('=').append(timing.getValue()).append("ms");
            }
        }
        Platforms.log(line.toString());
    }

    /**
     * The supplier for a node id, whichever registry holds it.
     *
     * @param type node id to look up
     * @return the supplier, or {@code null} when neither registry has it
     */
    public static Supplier<? extends MeshNode> supplierFor(String type) {
        Supplier<? extends MeshNode> supplier = MeshNodeRegistry_MeshNodes.MAP.get(type);
        return supplier != null ? supplier : DESKTOP_SUPPLIERS.get(type);
    }

    /**
     * Error text for a node id absent from this runtime's registry, naming the real cause when the
     * id exists but only in the desktop registry this build cannot load.
     *
     * @param type the unresolvable node id
     * @return message for the thrown {@code IllegalStateException}
     */
    public static String missingNodeMessage(String type) {
        if (MeshNodeRegistry_MeshNodes.DESKTOP_ONLY_IDS.contains(type)) {
            return "Mesh node '" + type + "' is desktop-only and unavailable in this build";
        }
        return NO_MESH_NODE_SUPPLIER_FOR_TYPE + type;
    }

    /**
     * Map of DSL id to node class from the generated {@code MeshNodeRegistry_MeshNodes.MAP}.
     *
     * @return unmodifiable id-to-class view, cached in {@link #REGISTRY_CLASSES}
     */
    public static Map<String, Class<? extends MeshNode>> annotationRegistryClasses() {
        return REGISTRY_CLASSES;
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
     * Parses DSL source into statements and a runtime holding every registered node and the
     * program's own function definitions.
     *
     * @param source DSL program text
     * @return the parsed statements paired with a runtime ready to execute them
     */
    public static ParsedGraph fromSource(String source) {
        PythonParser parser = new PythonParser(new PythonLexer(source));
        List<PythonParser.ParsedNode> statements = parser.parseGraph();
        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        runtime.registerFunctionDefs(parser.functionDefs());
        return new ParsedGraph(runtime, statements);
    }

    /**
     * User-editable inputs and curve parameters in a parsed graph (literal metadata for UI).
     *
     * @param parsedStatements top-level statements of a parsed DSL program
     * @return descriptors for each {@code input_*} node and inline literal parameter, in source order
     */
    public static List<InputParameterDescriptor> collectInputParameters(List<PythonParser.ParsedNode> parsedStatements) {
        return InputParameterDescriptor.collect(parsedStatements);
    }

    /**
     * Runs the graph and returns the final node's {@code mesh} output (backward compatible).
     *
     * @param parsedStatements top-level DSL statements to execute in order
     * @param finalOutputId id of the node whose {@code mesh} port is the graph result
     * @throws Exception if any node fails or a referenced node isn't yet evaluated
     * @return the final mesh topology, or {@code null} if no mesh is produced
     */
    public MeshTopology executeGraph(List<PythonParser.ParsedNode> parsedStatements, String finalOutputId) throws Exception {
        return executeGraphToMesh(parsedStatements, finalOutputId, MESH);
    }

    /**
     * Runs the graph and returns the final value on the given output port.
     *
     * @param parsedStatements top-level DSL statements to execute in order
     * @param finalOutputId id of the node to read the result from
     * @param outputPortName name of the output port on {@code finalOutputId}
     * @throws Exception if any node fails or a referenced node isn't yet evaluated
     * @return the value on the requested port, or a fallback probed across common port names
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
     *
     * @param parsedStatements top-level DSL statements to execute in order
     * @param finalOutputId id of the node to read the result from
     * @param outputPortName name of the output port on {@code finalOutputId}
     * @param overridesByNodeId per-node literal overrides keyed as documented above; may be empty or null
     * @throws Exception if any node fails internally
     * @throws RuntimeException if a node is referenced before it has been evaluated, or a function is missing arguments
     * @throws IllegalArgumentException if a node type isn't registered
     * @throws IllegalStateException if a registered node has no factory in the generated MAP
     * @return the value on the requested port, or a fallback probed across common port names
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
                String literalKey = parsedData.id + STR + portName;
                if (overrides.containsKey(literalKey)) {
                    resolvedArgs.put(portName, overrides.get(literalKey));
                } else if (rawValue instanceof PythonParser.NodeReference ref) {
                    GraphNodeContext sourceContext = evaluatedNodes.get(ref.nodeId);
                    if (sourceContext == null) {
                        throw new RuntimeException("Node '" + ref.nodeId + WAS_REFERENCED_BEFORE_IT_WAS_EVALUATED);
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
                String base = parsedData.id + STR + portName;
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
                long nodeMs = (System.nanoTime() - nodeStart) / NUM_1_000_000;
                lastTimingMs.put(parsedData.id + STR_2 + parsedData.type + STR_3, nodeMs);

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
                Supplier<? extends MeshNode> supplier = supplierFor(parsedData.type);
                if (supplier == null) {
                    throw new IllegalStateException(missingNodeMessage(parsedData.type));
                }
                MeshNode activeNode = supplier.get();

                GraphNodeContext context = new GraphNodeContext();
                context.setFieldContext(currentFieldContext);
                context.setNodeAssignmentId(parsedData.id);

                for (Map.Entry<String, Object> resolved : resolvedArgs.entrySet()) {
                    context.setInputValue(resolved.getKey(), resolved.getValue());
                }

                if (overrides.containsKey(parsedData.id) && ( "input_float".equals(parsedData.type) || "input_int".equals(parsedData.type) || "input_boolean".equals(parsedData.type))) {
                    context.setInputValue("default", overrides.get(parsedData.id));
                }

                long nodeStart = System.nanoTime();
                activeNode.evaluate(context);
                AutoTagHook.applyIfApplicable(activeNode, context, parsedData.id);
                long nodeMs = (System.nanoTime() - nodeStart) / NUM_1_000_000;
                lastTimingMs.put(parsedData.id + STR_2 + parsedData.type + STR_3, nodeMs);

                MeshTopology meshOut = meshFromNodeOutputs(context);
                if (meshOut != null && meshOut.vertexCount() > 0) {
                    currentFieldContext = new FieldContextImpl(meshOut);
                }
                evaluatedNodes.put(parsedData.id, context);
            }
        }

        lastTotalMs = (System.nanoTime() - graphStart) / NUM_1_000_000;

        GraphNodeContext finalContext = evaluatedNodes.get(finalOutputId);
        if (finalContext != null) {
            Object result = finalContext.getOutput(outputPortName);
            if (result != null) {
                return result;
            }
        }

        // Fallback: walk nodes in reverse order and probe common mesh port names.
        // This handles DSLs where the output node isn't named as expected.
        String[] probeNames = { outputPortName, MESH, GEOMETRY, RESULT };
        for (int i = parsedStatements.size() - 1; i >= 0; i--) {
            GraphNodeContext ctx = evaluatedNodes.get(parsedStatements.get(i).id);
            if (ctx == null) {
                continue;
            }
            for (String port : probeNames) {
                Object out = ctx.getOutput(port);
                if (out != null) {
                    return out;
                }
            }
        }
        return null;
    }

    /**
     * Executes a function definition's body with the given arguments bound to parameters.
     * Parameters become synthetic nodes whose outputs carry the bound values.
     * Returns the context of the last node in the body (the function's return value).
     *
     * @param funcDef function definition whose body to execute
     * @param callArgs argument values resolved from the caller, keyed by parameter name
     * @param callerFieldContext implicit field context inherited from the caller
     * @param overrides literal overrides forwarded to nested calls (same keys as the public entry point)
     * @throws Exception if any node fails internally
     * @throws RuntimeException if a parameter is missing, a body reference is unresolved, or the body is empty
     * @throws IllegalArgumentException if a body node type isn't registered
     * @throws IllegalStateException if a registered node has no factory in the generated MAP
     * @return context of the last node in the body (the function's return value)
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
                throw new RuntimeException(FUNCTION + funcDef.name + "' missing argument '" + param.name + "'");
            }
            GraphNodeContext paramCtx = new GraphNodeContext();
            // Parameter nodes expose their value on a "result" port and also
            // on the conventional port name for their type (mesh, geometry, etc.)
            paramCtx.setOutput(RESULT, value);
            if (value instanceof MeshTopology) {
                paramCtx.setOutput(MESH, value);
            } else if (value instanceof GeometryBundle) {
                paramCtx.setOutput(GEOMETRY, value);
                paramCtx.setOutput(MESH, value);
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
                        throw new RuntimeException(IN_FUNCTION + funcDef.name + "': node '"
                                + ref.nodeId + WAS_REFERENCED_BEFORE_IT_WAS_EVALUATED);
                    }
                    resolvedArgs.put(arg.getKey(), sourceContext.getOutput(ref.portName));
                } else if (rawValue instanceof String bareId) {
                    // Bare identifier in function body: check if it matches a parameter
                    // or local node name (e.g., `cube(size=size)` where `size` is a param)
                    GraphNodeContext paramCtx = localNodes.get(bareId);
                    if (paramCtx != null) {
                        resolvedArgs.put(arg.getKey(), paramCtx.getOutput(RESULT));
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
                    throw new IllegalArgumentException(IN_FUNCTION + funcDef.name
                            + "': unknown node type: " + bodyNode.type);
                }
                Supplier<? extends MeshNode> supplier =
                        supplierFor(bodyNode.type);
                if (supplier == null) {
                    throw new IllegalStateException(missingNodeMessage(bodyNode.type));
                }
                MeshNode activeNode = supplier.get();

                lastContext = new GraphNodeContext();
                lastContext.setFieldContext(localFieldContext);
                lastContext.setNodeAssignmentId(bodyNode.id);

                for (Map.Entry<String, Object> resolved : resolvedArgs.entrySet()) {
                    lastContext.setInputValue(resolved.getKey(), resolved.getValue());
                }

                activeNode.evaluate(lastContext);
                AutoTagHook.applyIfApplicable(activeNode, lastContext, bodyNode.id);
            }

            MeshTopology meshOut = meshFromNodeOutputs(lastContext);
            if (meshOut != null && meshOut.vertexCount() > 0) {
                localFieldContext = new FieldContextImpl(meshOut);
            }

            localNodes.put(bodyNode.id, lastContext);
        }

        if (lastContext == null) {
            throw new RuntimeException(FUNCTION + funcDef.name + "' has empty body");
        }
        return lastContext;
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
     *
     * @param parsedStatements top-level DSL statements to execute in order
     * @param finalOutputId id of the node to read the mesh from
     * @param outputPortName name of the output port on {@code finalOutputId}
     * @throws Exception if any node fails or a referenced node isn't yet evaluated
     * @return mesh topology on the requested port, or {@code null} if the result isn't a mesh or bundle
     */
    public MeshTopology executeGraphToMesh(List<PythonParser.ParsedNode> parsedStatements, String finalOutputId,
            String outputPortName) throws Exception {
        return executeGraphToMesh(parsedStatements, finalOutputId, outputPortName, Map.of());
    }

    /**
     * Variant of {@link #executeGraphToMesh(List, String, String)} that also accepts literal overrides.
     *
     * @param parsedStatements top-level DSL statements to execute in order
     * @param finalOutputId id of the node to read the mesh from
     * @param outputPortName name of the output port on {@code finalOutputId}
     * @param overridesByNodeId per-node literal overrides; see
     *        {@link #executeGraphResult(List, String, String, Map)} for the key format
     * @throws Exception if any node fails or a referenced node isn't yet evaluated
     * @return mesh topology on the requested port, or {@code null} if the result isn't a mesh or bundle
     */
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

    /**
     * A parsed DSL program together with the runtime prepared to execute it.
     *
     * @param runtime    runtime with the annotation registry and the program's functions loaded
     * @param statements top-level statements in source order
     */
    public record ParsedGraph(NodeGraphRuntime runtime, List<PythonParser.ParsedNode> statements) {
    }
}
