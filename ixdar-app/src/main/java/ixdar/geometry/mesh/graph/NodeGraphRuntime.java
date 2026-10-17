package ixdar.geometry.mesh.graph;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import ixdar.annotations.meshnode.MeshNodeRegistry_MeshNodes;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.nodes.api.FieldContext;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.Vector3Value;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;
import ixdar.platform.Platforms;

/**
 * Executes parsed DSL graphs over registered {@link MeshNode}s and node groups, keeping every
 * evaluated node, group bodies included, in the node tree under {@link #root} by its path.
 */
public class NodeGraphRuntime {
    public static final String MESH = "mesh";
    public static final String ID = "id";
    public static final String GEOMETRY = "geometry";
    public static final String RESULT = "result";
    public static final long NANOS_PER_MILLI = 1_000_000L;

    public static final long SLOW_NODE_MS = 100;

    public static final String PORT_SEPARATOR = ".";

    public static final int NEAREST_PATH_COUNT = 5;

    public static final int MAX_GROUP_DEPTH = 32;

    public static final Set<String> INPUT_NODE_TYPES = Set.of("input_float", "input_int",
            "input_boolean");

    public static final Map<String, Class<? extends MeshNode>> REGISTRY_CLASSES;

    public static final Map<String, Supplier<? extends MeshNode>> DESKTOP_SUPPLIERS = desktopRegistryMap();

    public static final Map<Class<? extends MeshNode>, String> REGISTRY_IDS_BY_CLASS;

    private static final String WAS_REFERENCED_BEFORE_IT_WAS_EVALUATED = "' was referenced before it was evaluated!";
    private static final String IN_FUNCTION = "In function '";
    private static final String DEFAULT_PORT = "default";
    private static final String AXIS_X = "x";
    private static final String AXIS_Y = "y";
    private static final String AXIS_Z = "z";
    private static final List<String> VECTOR_COMPONENTS = List.of(AXIS_X, AXIS_Y, AXIS_Z);
    private static final String NEAREST = "; nearest: ";
    private static final String LIST_SEPARATOR = ", ";
    private static final String FUNCTION = "Function '";

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
        Map<Class<? extends MeshNode>, String> ids = new HashMap<>();
        for (Map.Entry<String, Class<? extends MeshNode>> e : out.entrySet()) {
            ids.put(e.getValue(), e.getKey());
        }
        REGISTRY_IDS_BY_CLASS = Collections.unmodifiableMap(ids);
    }

    /** Top-level statements of the program this runtime was parsed from, in source order. */
    public final List<PythonParser.ParsedNode> statements;

    /** Failures nodes of the current run carried on past, thrown together once it has run. */
    public final List<String> deferredFailures = new ArrayList<>();

    /** Values the host binds for scene-bound input nodes, e.g. {@code model} for {@code scene_model()}. */
    public final Map<String, Object> graphInputs = new HashMap<>();

    /**
     * Overrides of the current run as set, keyed {@code path.port} (or {@code path.port.x}); see
     * {@link #setOverrides}.
     */
    public final Map<String, Object> overrides = new LinkedHashMap<>();

    /** Root of the node tree of the current run; its children are the evaluated top-level statements. */
    public GraphNodeContext root = new GraphNodeContext();

    private final Map<String, Class<? extends MeshNode>> nodeRegistry = new HashMap<>();
    private final Map<String, PythonParser.FunctionDef> functionDefs = new HashMap<>();

    /** Types known to call no group (leaf nodes, types the library lacks), each looked up once. */
    private final Set<String> nonGroupTypes = new HashSet<>();

    /** Overrides by node path, then by port (or {@code port.x}). */
    private final Map<String, Map<String, Object>> overridesByPath = new HashMap<>();

    /** Field context each record's mesh output opens, built once per record. */
    private final Map<GraphNodeContext, FieldContext> meshContexts = new IdentityHashMap<>();

    /** Samples used heap while a node runs, so the peak can be attributed to that node. */
    private final HeapSampler heapSampler = new HeapSampler();

    /** Nesting depth of public evaluation entry points, so the sampler starts and stops once. */
    private int activeEvaluations;

    /**
     * Whether a group call evaluates its whole body when called, as a source-order run does; a
     * {@link #pull} evaluates only the body nodes the outputs it reads depend on.
     */
    private boolean evaluateWholeGroups = true;

    /** A runtime with no program of its own; callers hand statements to the execute methods. */
    public NodeGraphRuntime() {
        this(List.of());
    }

    /**
     * A runtime bound to a parsed program.
     *
     * @param statements top-level statements in source order
     */
    public NodeGraphRuntime(List<PythonParser.ParsedNode> statements) {
        this.statements = statements;
        reset(statements);
    }

    /**
     * Per-node timing of the current run, every node of the tree included, depth first.
     *
     * @return insertion-ordered map keyed by {@code "path (type)"} with milliseconds spent in
     *         each node, children included
     */
    public LinkedHashMap<String, Long> lastTimingMs() {
        LinkedHashMap<String, Long> timing = new LinkedHashMap<>();
        for (GraphNodeContext record : treeRecords()) {
            timing.put(timingKey(record), record.elapsedMs());
        }
        return timing;
    }

    /**
     * Total wall time of the current run.
     *
     * @return total graph execution time in milliseconds
     */
    public long lastTotalMs() {
        return root.elapsedMs();
    }

    /**
     * Peak used heap observed while each node of the current run ran, keyed exactly like
     * {@link #lastTimingMs}. Process-wide, so it includes whatever earlier nodes still hold.
     *
     * @return insertion-ordered map keyed by {@code "path (type)"} with peak used heap in bytes
     */
    public LinkedHashMap<String, Long> lastPeakHeapBytes() {
        LinkedHashMap<String, Long> peaks = new LinkedHashMap<>();
        for (GraphNodeContext record : treeRecords()) {
            peaks.put(timingKey(record), record.peakHeapBytes);
        }
        return peaks;
    }

    /**
     * Every evaluated record of the current run below the root, depth first in evaluation order.
     *
     * @return the tree's records, the root excluded
     */
    public List<GraphNodeContext> treeRecords() {
        List<GraphNodeContext> records = root.subtree();
        return records.subList(1, records.size());
    }

    /**
     * The evaluated record at a path, without evaluating anything.
     *
     * @param path {@code /}-joined call-site ids, e.g. {@code layout/motorcycle}
     * @return the record, or {@code null} when nothing at that path has evaluated
     */
    public GraphNodeContext node(String path) {
        GraphNodeContext record = root;
        for (String segment : path.split(GraphNodeContext.PATH_SEPARATOR, -1)) {
            record = record.childrenById.get(segment);
            if (record == null) {
                return null;
            }
        }
        return record;
    }

    /**
     * Returns the output value of a previously-executed node, or {@code null} if the node never
     * ran or the port doesn't exist. Used by viewer scenes that grab intermediate values from
     * the DSL graph (e.g. the dungeon viewer wiring {@code astar_corridors_3d}'s tile-lattice
     * geometry into the player controller's collision world).
     *
     * @param nodeId id or {@code /}-joined path of the previously-executed graph node
     * @param outputPortName name of the output port to read
     * @return value published on that port, or {@code null} if the node never ran or the port is missing
     */
    public Object getNodeOutput(String nodeId, String outputPortName) {
        GraphNodeContext ctx = node(nodeId);
        if (ctx == null) {
            return null;
        }
        return ctx.getOutput(outputPortName);
    }

    /**
     * The output a port of the final top-level statement produced in the most
     * recent execution.
     *
     * @param outputPortName name of the output port to read
     * @return the port's value, or {@code null} when nothing ran or the port is
     *         missing
     */
    public Object lastOutput(String outputPortName) {
        return root.body.isEmpty() ? null
                : getNodeOutput(root.body.get(root.body.size() - 1).id, outputPortName);
    }

    /**
     * The int a previously-executed statement published on its {@code id}
     * output port, the convention id-producing nodes share.
     *
     * @param nodeId id of the previously-executed graph node
     * @throws IllegalArgumentException when no statement of that name produced
     *                                  an int {@code id} output
     * @return the statement's id output
     */
    public int intOutput(String nodeId) {
        Object value = getNodeOutput(nodeId, ID);
        if (!(value instanceof Integer intValue)) {
            throw new IllegalArgumentException("no statement named " + nodeId
                    + " with an int id output");
        }
        return intValue;
    }

    /**
     * Parses and executes a graph resource and returns the executed runtime,
     * every statement's outputs readable through {@link #getNodeOutput},
     * {@link #intOutput} and {@link #lastOutput}.
     *
     * @param dslPath   classpath resource or file path of the graph
     * @param overrides path overrides; see {@link #setOverrides} for the key format
     * @throws IllegalStateException when parsing or execution fails
     * @return the executed runtime
     */
    public static NodeGraphRuntime executeResource(String dslPath, Map<String, Object> overrides) {
        try {
            NodeGraphRuntime runtime = fromResource(dslPath);
            String last = runtime.statements.get(runtime.statements.size() - 1).id;
            runtime.executeGraphResult(runtime.statements, last, RESULT, overrides);
            return runtime;
        } catch (Exception failure) {
            throw new IllegalStateException("graph " + dslPath + " failed", failure);
        }
    }

    /**
     * Parses a graph resource into a runtime without executing it.
     *
     * @param dslPath classpath resource (e.g. {@code dsl/fertility_quad_layout.dsl}) or file path
     * @return a runtime ready to execute or pull from
     */
    public static NodeGraphRuntime fromResource(String dslPath) {
        return fromSource(readText(dslPath));
    }

    /**
     * Reads a graph source, trying the classpath before the filesystem.
     *
     * @param path resource path (e.g. {@code dsl/fixtures/x.dsl}) or file path
     * @throws IllegalArgumentException when neither location has the file
     * @return the file's text
     */
    private static String readText(String path) {
        try {
            InputStream stream = NodeGraphRuntime.class.getClassLoader().getResourceAsStream(path);
            if (stream != null) {
                try (stream) {
                    return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            return new String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalArgumentException("cannot read graph " + path, failure);
        }
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
     * Register DSL group definitions, merged into the runtime's group table ahead of the library.
     *
     * @param defs group name to parsed body
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
            Class<?> desktop = Class.forName(
                    MeshNodeRegistry_MeshNodes.class.getName() + "Desktop");
            return (Map<String, Supplier<? extends MeshNode>>) desktop.getField("MAP").get(null);
        } catch (Throwable unavailable) {
            return Map.of();
        }
    }

    /**
     * Log the current run's total wall time, plus the time and peak heap of every node that
     * took 100ms or more. The project's rule of thumb: most steps take under a second, and
     * anything slow usually means a planning or correctness problem, so it must be visible.
     *
     * @param prefix log prefix naming the caller, e.g. {@code "[mesh-viewer]"}
     */
    public void logTimings(String prefix) {
        StringBuilder line = new StringBuilder(prefix).append(" graph ").append(lastTotalMs()).append("ms");
        for (GraphNodeContext record : treeRecords()) {
            if (record.elapsedMs() >= SLOW_NODE_MS) {
                line.append("  ").append(timingKey(record)).append('=').append(record.elapsedMs())
                        .append("ms/")
                        .append(Math.round(record.peakHeapBytes / HeapSampler.BYTES_PER_MIB))
                        .append("MiB");
            }
        }
        Platforms.log(line.toString());
    }

    /**
     * The node tree of the current run as indented lines: one per record with its path, type,
     * wall time and peak heap.
     *
     * @return the lines, a total line first
     */
    public List<String> treeLines() {
        List<String> lines = new ArrayList<>();
        lines.add("total " + lastTotalMs() + " ms");
        for (GraphNodeContext record : treeRecords()) {
            int depth = record.path.split(GraphNodeContext.PATH_SEPARATOR).length;
            lines.add("  ".repeat(depth) + record.path + "  " + record.type + "  "
                    + record.elapsedMs() + " ms  "
                    + Math.round(record.peakHeapBytes / HeapSampler.BYTES_PER_MIB) + " MiB");
        }
        return lines;
    }

    /**
     * The type a node's records carry: its registered id, or the class name of an unregistered
     * node a Java-built graph instantiates.
     *
     * @param node an instance of the node
     * @return the type
     */
    public static String typeOf(MeshNode node) {
        String id = REGISTRY_IDS_BY_CLASS.get(node.getClass());
        return id != null ? id : node.getClass().getSimpleName();
    }

    /**
     * The supplier for a statement: a Java-built statement's own, else the registered one of its
     * type.
     *
     * @param statement the statement
     * @return the supplier, or {@code null} when neither exists
     */
    private static Supplier<? extends MeshNode> supplierFor(PythonParser.ParsedNode statement) {
        return statement.supplier != null ? statement.supplier : supplierFor(statement.type);
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
        return "No mesh node supplier for type: " + type;
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
     * Parses DSL source into a runtime holding the parsed {@link #statements}, every
     * registered node and the program's own function definitions.
     *
     * @param source DSL program text
     * @return a runtime ready to execute its statements
     */
    public static NodeGraphRuntime fromSource(String source) {
        PythonParser parser = new PythonParser(new PythonLexer(source));
        NodeGraphRuntime runtime = new NodeGraphRuntime(parser.parseGraph());
        runtime.registerAllFromAnnotationRegistry();
        runtime.registerFunctionDefs(parser.functionDefs());
        return runtime;
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
     * Runs every statement in source order, each group call's whole body included, with path
     * overrides applied, and reads the result port.
     *
     * @param parsedStatements top-level DSL statements to execute in order
     * @param finalOutputId id of the node to read the result from
     * @param outputPortName name of the output port on {@code finalOutputId}
     * @param overridesByPath path overrides as {@link #setOverrides} takes them; may be null
     * @throws Exception if any node fails internally
     * @throws IllegalArgumentException if an override names no real path or port, or a node type
     *                                  isn't registered
     * @return the value on the requested port, or a fallback probed across common port names
     */
    public Object executeGraphResult(List<PythonParser.ParsedNode> parsedStatements, String finalOutputId,
            String outputPortName, Map<String, Object> overridesByPath) throws Exception {
        reset(parsedStatements);
        setOverrides(overridesByPath == null ? Map.of() : overridesByPath);
        evaluateWholeGroups = true;
        beginEvaluation();
        long started = System.nanoTime();
        try {
            for (int index = 0; index < parsedStatements.size(); index++) {
                evaluateStatement(root, index);
            }
        } finally {
            root.elapsedNanos += System.nanoTime() - started;
            endEvaluation();
        }

        throwDeferredFailures();

        GraphNodeContext finalContext = root.childrenById.get(finalOutputId);
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
            GraphNodeContext ctx = root.childrenById.get(parsedStatements.get(i).id);
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
     * Drops the node tree and starts a fresh run of a program; overrides must be set again.
     *
     * @param program top-level statements the next evaluation runs
     */
    public void reset(List<PythonParser.ParsedNode> program) {
        root = new GraphNodeContext();
        root.body = program;
        root.graphInputs = graphInputs;
        deferredFailures.clear();
        meshContexts.clear();
        overrides.clear();
        overridesByPath.clear();
    }

    /**
     * Replaces the current run's overrides, validating every key against the program. A key is a
     * node path, a {@code .}, a port and optionally a vector component: {@code layout/field.curvature_bias},
     * {@code thumb.translation.x}. A bare {@code input_float}/{@code input_int}/{@code input_boolean}
     * id sets its {@code default}.
     *
     * @param byPath override values by key
     * @throws IllegalArgumentException when a key names no node, or a port the node lacks,
     *                                  naming the nearest real paths or ports
     */
    public void setOverrides(Map<String, Object> byPath) {
        overrides.clear();
        overridesByPath.clear();
        for (Map.Entry<String, Object> override : byPath.entrySet()) {
            setOverride(override.getKey(), override.getValue());
        }
    }

    /**
     * Adds or replaces one override of the current run; it takes effect on nodes that have not
     * evaluated yet.
     *
     * @param key   override key, see {@link #setOverrides}
     * @param value the port's value
     * @throws IllegalArgumentException when the key names no node or port
     */
    public void setOverride(String key, Object value) {
        String[] pathAndPort = validateOverrideKey(key);
        overridesByPath.computeIfAbsent(pathAndPort[0], unused -> new LinkedHashMap<>())
                .put(pathAndPort[1], value);
        overrides.put(key, value);
    }

    /**
     * Checks an override key against the current program without running anything.
     *
     * @param key override key, see {@link #setOverrides}
     * @throws IllegalArgumentException when the path or port does not exist, naming the nearest
     *                                  real paths or the node's ports
     * @return the node path and the port spec ({@code port} or {@code port.x})
     */
    public String[] validateOverrideKey(String key) {
        int dot = key.indexOf(PORT_SEPARATOR);
        String path = dot < 0 ? key : key.substring(0, dot);
        Map<String, PythonParser.ParsedNode> paths = staticPaths();
        PythonParser.ParsedNode statement = paths.get(path);
        if (statement == null) {
            throw new IllegalArgumentException("override " + key + ": no node at path '" + path
                    + "'" + NEAREST + String.join(LIST_SEPARATOR, nearest(path, paths.keySet())));
        }
        if (dot < 0) {
            if (INPUT_NODE_TYPES.contains(statement.type)) {
                return new String[] { path, DEFAULT_PORT };
            }
            throw new IllegalArgumentException("override " + key + " names no port; " + path
                    + " (" + statement.type + ") has ports " + staticPorts(statement));
        }
        String portSpec = key.substring(dot + 1);
        int componentDot = portSpec.indexOf(PORT_SEPARATOR);
        String port = componentDot < 0 ? portSpec : portSpec.substring(0, componentDot);
        List<String> ports = staticPorts(statement);
        if (!ports.contains(port)) {
            throw new IllegalArgumentException("override " + key + ": " + path + " ("
                    + statement.type + ") has no port '" + port + "'; ports: " + ports);
        }
        if (componentDot >= 0 && !VECTOR_COMPONENTS.contains(portSpec.substring(componentDot + 1))) {
            throw new IllegalArgumentException("override " + key + ": a vector component is one of "
                    + VECTOR_COMPONENTS);
        }
        return new String[] { path, portSpec };
    }

    /**
     * Every node path the current program can evaluate, group bodies included, with the
     * statement at each. Walks the source, so nothing runs.
     *
     * @return statements by path, in source order
     */
    public Map<String, PythonParser.ParsedNode> staticPaths() {
        Map<String, PythonParser.ParsedNode> paths = new LinkedHashMap<>();
        collectPaths(root.body, "", 0, paths);
        return paths;
    }

    /**
     * Adds a scope's statements to the path map, descending into each group call.
     *
     * @param body   statements of the scope
     * @param prefix path of the scope with a trailing separator, empty at the top level
     * @param depth  group nesting depth of the scope
     * @param paths  map receiving statements by path
     */
    private void collectPaths(List<PythonParser.ParsedNode> body, String prefix, int depth,
            Map<String, PythonParser.ParsedNode> paths) {
        for (PythonParser.ParsedNode statement : body) {
            String path = prefix + statement.id;
            paths.put(path, statement);
            PythonParser.FunctionDef group = lookupGroup(statement);
            if (group != null && depth < MAX_GROUP_DEPTH) {
                collectPaths(group.body, path + GraphNodeContext.PATH_SEPARATOR, depth + 1, paths);
            }
        }
    }

    /**
     * Ports an override may set on a statement: a group's parameters or a node's declared
     * inputs, plus any argument the call passes.
     *
     * @param statement the statement at the override's path
     * @return port names, declared order first
     */
    private List<String> staticPorts(PythonParser.ParsedNode statement) {
        List<String> ports = new ArrayList<>();
        PythonParser.FunctionDef group = lookupGroup(statement);
        if (group != null) {
            for (PythonParser.FunctionParam param : group.params) {
                ports.add(param.name);
            }
        } else {
            Supplier<? extends MeshNode> supplier = supplierFor(statement);
            if (supplier != null) {
                for (InputPort input : supplier.get().inputs()) {
                    ports.add(input.name);
                }
            }
        }
        for (String argument : statement.arguments.keySet()) {
            if (!ports.contains(argument)) {
                ports.add(argument);
            }
        }
        return ports;
    }

    /**
     * The candidates closest to a mistyped path by Levenshtein distance.
     *
     * @param path       the path that matched nothing
     * @param candidates real paths
     * @return up to {@link #NEAREST_PATH_COUNT} paths, nearest first
     */
    public static List<String> nearest(String path, Set<String> candidates) {
        List<String> ranked = new ArrayList<>(candidates);
        Map<String, Integer> distances = new HashMap<>();
        for (String candidate : ranked) {
            int[] previous = new int[candidate.length() + 1];
            int[] current = new int[candidate.length() + 1];
            for (int column = 0; column <= candidate.length(); column++) {
                previous[column] = column;
            }
            for (int row = 1; row <= path.length(); row++) {
                current[0] = row;
                for (int column = 1; column <= candidate.length(); column++) {
                    int substitution = previous[column - 1]
                            + (path.charAt(row - 1) == candidate.charAt(column - 1) ? 0 : 1);
                    current[column] = Math.min(substitution,
                            Math.min(previous[column], current[column - 1]) + 1);
                }
                int[] swap = previous;
                previous = current;
                current = swap;
            }
            distances.put(candidate, previous[candidate.length()]);
        }
        ranked.sort((left, right) -> Integer.compare(distances.get(left), distances.get(right)));
        return ranked.subList(0, Math.min(NEAREST_PATH_COUNT, ranked.size()));
    }

    /**
     * Evaluates only what one output depends on, keeping everything already evaluated in the
     * current run, so a scene asking for a pipeline prefix never runs the rest of it.
     *
     * @param reference {@code path.port}, e.g. {@code layout.motorcycle} or
     *                  {@code layout/field.singularities}
     * @throws Exception if a node fails
     * @throws IllegalArgumentException if the path or port does not exist
     * @return the output's value
     */
    public Object pull(String reference) throws Exception {
        int dot = reference.lastIndexOf(PORT_SEPARATOR);
        int lastSegment = reference.lastIndexOf(GraphNodeContext.PATH_SEPARATOR);
        if (dot < 0 || dot < lastSegment) {
            throw new IllegalArgumentException("pull " + reference + ": expected path.port");
        }
        evaluateWholeGroups = false;
        beginEvaluation();
        long started = System.nanoTime();
        try {
            GraphNodeContext record = root;
            for (String segment : reference.substring(0, dot).split(GraphNodeContext.PATH_SEPARATOR)) {
                int index = indexOf(record.body, segment, record.body.size());
                if (index < 0) {
                    throw new IllegalArgumentException("pull " + reference + ": no node '" + segment
                            + "' under " + (record.path.isEmpty() ? "the top level" : record.path)
                            + NEAREST + String.join(LIST_SEPARATOR,nearest(reference.substring(0, dot),
                                    staticPaths().keySet())));
                }
                record = evaluateStatement(record, index);
            }
            throwDeferredFailures();
            return readOutput(record, reference.substring(dot + 1));
        } finally {
            root.elapsedNanos += System.nanoTime() - started;
            endEvaluation();
        }
    }

    /**
     * Fails the run once its statements have evaluated when any node reported a failure it
     * carried on past ({@link GraphNodeContext#reportFailure}).
     *
     * @throws IllegalArgumentException naming every deferred failure of the current run
     */
    private void throwDeferredFailures() {
        if (!deferredFailures.isEmpty()) {
            throw new IllegalArgumentException(deferredFailures.size()
                    + " failure(s) in the graph:\n" + String.join("\n", deferredFailures));
        }
    }

    private void beginEvaluation() {
        if (activeEvaluations++ == 0) {
            heapSampler.start();
        }
    }

    private void endEvaluation() {
        if (--activeEvaluations == 0) {
            heapSampler.stop();
        }
    }

    /**
     * Evaluates statement {@code index} of a scope once, pulling the statements its arguments
     * reference first, and records it in the tree.
     *
     * @param scope record whose body holds the statement
     * @param index statement index in {@code scope.body}
     * @throws Exception if the node fails
     * @return the statement's record
     */
    private GraphNodeContext evaluateStatement(GraphNodeContext scope, int index) throws Exception {
        PythonParser.ParsedNode statement = scope.body.get(index);
        GraphNodeContext existing = scope.childrenById.get(statement.id);
        if (existing != null) {
            return existing;
        }
        String path = scope.path.isEmpty() ? statement.id
                : scope.path + GraphNodeContext.PATH_SEPARATOR + statement.id;
        Map<String, Object> pathOverrides = overridesByPath.getOrDefault(path, Map.of());
        GraphNodeContext record = new GraphNodeContext();
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (Map.Entry<String, Object> argument : statement.arguments.entrySet()) {
            if (!pathOverrides.containsKey(argument.getKey())) {
                arguments.put(argument.getKey(), resolveValue(scope, argument.getValue(), index,
                        record.inputSources, argument.getKey()));
            }
        }
        // Whole-port overrides first, then vector components of the resolved (or overridden) vector.
        for (Map.Entry<String, Object> override : pathOverrides.entrySet()) {
            if (!override.getKey().contains(PORT_SEPARATOR)) {
                arguments.put(override.getKey(), override.getValue());
                record.inputSources.put(override.getKey(), override.getValue());
            }
        }
        for (Map.Entry<String, Object> override : pathOverrides.entrySet()) {
            int dot = override.getKey().indexOf(PORT_SEPARATOR);
            if (dot < 0 || !(arguments.get(override.getKey().substring(0, dot)) instanceof Vector3Value vector)
                    || !(override.getValue() instanceof Number component)) {
                continue;
            }
            String axis = override.getKey().substring(dot + 1);
            float value = component.floatValue();
            Vector3Value overridden = new Vector3Value(
                    AXIS_X.equals(axis) ? value : vector.x(),
                    AXIS_Y.equals(axis) ? value : vector.y(),
                    AXIS_Z.equals(axis) ? value : vector.z());
            arguments.put(override.getKey().substring(0, dot), overridden);
            record.inputSources.put(override.getKey().substring(0, dot), overridden);
        }

        FieldContext fieldContext = null;
        for (int earlier = index - 1; earlier >= 0 && fieldContext == null; earlier--) {
            GraphNodeContext previous = scope.childrenById.get(scope.body.get(earlier).id);
            if (previous == null) {
                continue;
            }
            fieldContext = meshContexts.get(previous);
            if (fieldContext == null) {
                MeshTopology mesh = meshFromNodeOutputs(previous);
                if (mesh != null && mesh.vertexCount() > 0) {
                    fieldContext = new FieldContextImpl(mesh);
                    meshContexts.put(previous, fieldContext);
                }
            }
        }

        record.path = path;
        record.type = statement.type;
        record.parent = scope;
        record.graphInputs = graphInputs;
        record.deferredFailures = deferredFailures;
        // The previous mesh-producing statement of the scope, as sequential execution defines it.
        record.setFieldContext(fieldContext == null ? scope.fieldContext() : fieldContext);
        record.setNodeAssignmentId(statement.id);
        for (Map.Entry<String, Object> resolved : arguments.entrySet()) {
            record.setInputValue(resolved.getKey(), resolved.getValue());
        }
        scope.children.add(record);
        scope.childrenById.put(statement.id, record);

        PythonParser.FunctionDef group = lookupGroup(statement);
        Supplier<? extends MeshNode> supplier = supplierFor(statement);
        if (group == null) {
            String where = scope.group == null ? "" : IN_FUNCTION + scope.group.name + "': ";
            if (statement.supplier == null && nodeRegistry.get(record.type) == null) {
                throw new IllegalArgumentException(where + "Unknown node type: " + record.type);
            }
            if (supplier == null) {
                throw new IllegalStateException(missingNodeMessage(record.type));
            }
            record.node = supplier.get();
            long started = System.nanoTime();
            heapSampler.resetPeak();
            try {
                record.node.evaluate(record);
                AutoTagHook.applyIfApplicable(record.node, record, record.nodeAssignmentId());
            } finally {
                record.elapsedNanos = System.nanoTime() - started;
                record.peakHeapBytes = heapSampler.peakBytes();
                for (GraphNodeContext ancestor = record.parent; ancestor != null; ancestor = ancestor.parent) {
                    ancestor.peakHeapBytes = Math.max(ancestor.peakHeapBytes, record.peakHeapBytes);
                }
            }
            return record;
        }

        // A group call: bind its parameters (defaults for omitted ones) and, when whole groups
        // are evaluated, run its body and compute every output. A compiled group keeps its
        // instance, which computes the outputs its body does not wire.
        record.group = group;
        record.body = group.body;
        if (supplier != null && supplier.get() instanceof NodeGroup compiled) {
            record.node = compiled;
        }
        for (PythonParser.FunctionParam param : group.params) {
            if (record.getInputValue(param.name) != null) {
                continue;
            }
            if (param.defaultValue == null) {
                throw new RuntimeException(FUNCTION + group.name + "' missing argument '"
                        + param.name + "'");
            }
            record.setInputValue(param.name, param.defaultValue);
            record.inputSources.put(param.name, param.defaultValue);
        }
        if (group.body.isEmpty()) {
            throw new RuntimeException(FUNCTION + group.name + "' has empty body");
        }
        if (!evaluateWholeGroups) {
            return record;
        }
        long started = System.nanoTime();
        try {
            for (int bodyIndex = 0; bodyIndex < group.body.size(); bodyIndex++) {
                evaluateStatement(record, bodyIndex);
            }
        } finally {
            record.elapsedNanos += System.nanoTime() - started;
        }
        if (group.outputs.isEmpty()) {
            readOutput(record, RESULT);
        }
        for (String output : group.outputs.keySet()) {
            readOutput(record, output);
        }
        if (record.node != null) {
            for (OutputPort output : record.node.outputs()) {
                readOutput(record, output.name);
            }
        }
        return record;
    }

    /**
     * Reads an output port of a record, computing a group call's output on first read: a named
     * output resolves its return expression, a group without a return exposes its last body
     * node's outputs, and a compiled group computes an unwired output from all the wired ones.
     *
     * @param record the record to read
     * @param port   output port name
     * @throws Exception if a body node the output depends on fails
     * @return the port's value, or {@code null} when the record has no such port
     */
    private Object readOutput(GraphNodeContext record, String port) throws Exception {
        PythonParser.FunctionDef group = record.group;
        if (group == null || record.hasOutput(port)) {
            return record.getOutput(port);
        }
        long started = System.nanoTime();
        try {
            if (group.outputs.isEmpty()) {
                GraphNodeContext last = evaluateStatement(record, group.body.size() - 1);
                for (Map.Entry<String, Object> output : last.getOutputsSnapshot().entrySet()) {
                    record.setOutput(output.getKey(), output.getValue());
                    record.outputSources.put(output.getKey(),
                            new PythonParser.NodeReference(last.path, output.getKey()));
                }
            } else if (group.outputs.containsKey(port)) {
                record.setOutput(port, resolveValue(record, group.outputs.get(port), group.body.size(),
                        record.outputSources, port));
            } else if (record.node != null) {
                for (Map.Entry<String, Object> wired : group.outputs.entrySet()) {
                    if (!record.hasOutput(wired.getKey())) {
                        record.setOutput(wired.getKey(), resolveValue(record, wired.getValue(),
                                group.body.size(), record.outputSources, wired.getKey()));
                    }
                }
                record.node.evaluate(record);
                // An output the group left unset reads as null rather than evaluating again.
                for (OutputPort declared : record.node.outputs()) {
                    if (!record.hasOutput(declared.name)) {
                        record.setOutput(declared.name, null);
                    }
                }
            }
        } finally {
            record.elapsedNanos += System.nanoTime() - started;
        }
        return record.getOutput(port);
    }

    /**
     * Resolves an argument in a scope and records its source: a reference pulls its source
     * statement, a bare name in a group body reads a parameter (or an earlier body node's
     * {@code result}), anything else is a literal.
     *
     * @param scope   record whose body the argument appears in
     * @param raw     the parsed argument
     * @param before  index of the statement holding the argument; only earlier statements resolve
     * @param sources map receiving the source under {@code key}: a reference to the source
     *                record's path and port (a group parameter being the group's own path and the
     *                parameter's name), or the literal itself
     * @param key     port name the argument binds
     * @throws Exception if a pulled node fails
     * @throws RuntimeException if a reference names nothing evaluated before it
     * @return the argument's value
     */
    private Object resolveValue(GraphNodeContext scope, Object raw, int before,
            Map<String, Object> sources, String key) throws Exception {
        if (raw instanceof PythonParser.NodeReference ref) {
            // Below the scope itself, a level resolves only statements before the call-site
            // statement that leads down to the scope.
            GraphNodeContext below = null;
            for (GraphNodeContext level = scope; level != null; below = level, level = level.parent) {
                int bound = level == scope ? before : indexOf(level.body,
                        below.path.substring(below.path.lastIndexOf(GraphNodeContext.PATH_SEPARATOR) + 1),
                        level.body.size());
                int index = indexOf(level.body, ref.nodeId, bound);
                if (index >= 0) {
                    GraphNodeContext source = evaluateStatement(level, index);
                    sources.put(key, new PythonParser.NodeReference(source.path, ref.portName));
                    return readOutput(source, ref.portName);
                }
                if (level.group != null && isParameter(level.group, ref.nodeId)) {
                    // A parameter reads as result, and a geometry bundle also as geometry and mesh.
                    sources.put(key, new PythonParser.NodeReference(level.path, ref.nodeId));
                    Object value = level.getInputValue(ref.nodeId);
                    boolean bundlePort = value instanceof GeometryBundle
                            && (GEOMETRY.equals(ref.portName) || MESH.equals(ref.portName));
                    return RESULT.equals(ref.portName) || bundlePort ? value : null;
                }
            }
            String where = scope.group == null ? "" : IN_FUNCTION + scope.group.name + "': node '";
            throw new RuntimeException((where.isEmpty() ? "Node '" : where) + ref.nodeId
                    + WAS_REFERENCED_BEFORE_IT_WAS_EVALUATED);
        }
        if (raw instanceof String bareId && scope.group != null) {
            if (isParameter(scope.group, bareId)) {
                sources.put(key, new PythonParser.NodeReference(scope.path, bareId));
                return scope.getInputValue(bareId);
            }
            int index = indexOf(scope.body, bareId, before);
            if (index >= 0) {
                GraphNodeContext source = evaluateStatement(scope, index);
                sources.put(key, new PythonParser.NodeReference(source.path, RESULT));
                return source.getOutput(RESULT);
            }
        }
        sources.put(key, raw);
        return raw;
    }

    private static boolean isParameter(PythonParser.FunctionDef group, String name) {
        for (PythonParser.FunctionParam param : group.params) {
            if (param.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Index of the last statement with an id among the first {@code bound} of a body.
     *
     * @param body  statements to search
     * @param id    statement id
     * @param bound exclusive upper index
     * @return the index, or {@code -1}
     */
    private static int indexOf(List<PythonParser.ParsedNode> body, String id, int bound) {
        for (int index = Math.min(bound, body.size()) - 1; index >= 0; index--) {
            if (body.get(index).id.equals(id)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The group a statement calls: a DSL group from the program, registered definitions or the
     * library, or a compiled {@link NodeGroup}'s body, built once per run and shared by its calls.
     * A registered or Java-built node is never looked up in the library.
     *
     * @param statement the statement
     * @return the group, or {@code null} when the statement calls a leaf node
     */
    private PythonParser.FunctionDef lookupGroup(PythonParser.ParsedNode statement) {
        String type = statement.type;
        PythonParser.FunctionDef group = functionDefs.get(type);
        if (group != null || nonGroupTypes.contains(type)) {
            return group;
        }
        if (statement.supplier != null || nodeRegistry.containsKey(type)) {
            Supplier<? extends MeshNode> supplier = supplierFor(statement);
            if (supplier == null || !(supplier.get() instanceof NodeGroup compiled)) {
                nonGroupTypes.add(type);
                return null;
            }
            GroupBuilder body = new GroupBuilder();
            compiled.build(body);
            List<PythonParser.FunctionParam> params = new ArrayList<>();
            for (InputPort input : compiled.inputs()) {
                PythonParser.FunctionParam param = new PythonParser.FunctionParam(input.name,
                        input.type.name());
                param.defaultValue = input.defaultValue;
                params.add(param);
            }
            group = new PythonParser.FunctionDef(type, params,
                    compiled.outputs().isEmpty() ? null : compiled.outputs().get(0).type.name(),
                    body.statements);
            group.outputs.putAll(body.outputs);
            functionDefs.put(type, group);
            return group;
        }
        Map<String, PythonParser.FunctionDef> library = GroupLibrary.find(type);
        if (library.isEmpty()) {
            nonGroupTypes.add(type);
            return null;
        }
        for (Map.Entry<String, PythonParser.FunctionDef> def : library.entrySet()) {
            functionDefs.putIfAbsent(def.getKey(), def.getValue());
        }
        return functionDefs.get(type);
    }

    private static String timingKey(GraphNodeContext record) {
        return record.path + " (" + record.type + ")";
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
        return v instanceof GeometryBundle g ? g.mesh() : null;
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
     * Variant of {@link #executeGraphToMesh(List, String, String)} that also accepts path overrides.
     *
     * @param parsedStatements top-level DSL statements to execute in order
     * @param finalOutputId id of the node to read the mesh from
     * @param outputPortName name of the output port on {@code finalOutputId}
     * @param overridesByPath path overrides; see {@link #setOverrides} for the key format
     * @throws Exception if any node fails or a referenced node isn't yet evaluated
     * @return mesh topology on the requested port, or {@code null} if the result isn't a mesh or bundle
     */
    public MeshTopology executeGraphToMesh(List<PythonParser.ParsedNode> parsedStatements, String finalOutputId,
            String outputPortName, Map<String, Object> overridesByPath) throws Exception {
        return meshFromValue(executeGraphResult(parsedStatements, finalOutputId, outputPortName, overridesByPath));
    }

}
