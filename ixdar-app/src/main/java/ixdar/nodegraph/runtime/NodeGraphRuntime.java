package ixdar.nodegraph.runtime;

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

import ixdar.annotations.RegistryProcessor;
import ixdar.annotations.meshnode.MeshNodeRegistry_MeshNodes;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.nodegraph.field.FieldContext;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.field.Vector3Value;
import ixdar.platform.Platforms;

import ixdar.nodegraph.field.FieldContextImpl;
/**
 * Executes a program, a {@link NodeGroup} of registered {@link MeshNode}s and node groups, over a
 * node tree it builds afresh for every run under {@link #root}: each node a record wired to the
 * records it reads, group bodies included, found by its path.
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

    public static final String DEFAULT_PORT = "default";

    public static final Map<String, Supplier<? extends MeshNode>> REGISTERED_NODES;

    public static final Map<Class<? extends MeshNode>, String> REGISTRY_IDS_BY_CLASS;

    public static final Set<String> INPUT_NODE_TYPES;

    private static final String AXIS_X = "x";
    private static final String AXIS_Y = "y";
    private static final String AXIS_Z = "z";
    private static final List<String> VECTOR_COMPONENTS = List.of(AXIS_X, AXIS_Y, AXIS_Z);
    private static final String NEAREST = "; nearest: ";
    private static final String LIST_SEPARATOR = ", ";
    private static final String FUNCTION = "Function '";
    private static final String LINE_BREAK = "\n";

    // The one node lookup: the generated registry, then the desktop-only one reached through
    // Class.forName so TeaVM never walks its name (in the browser the lookup fails and it adds
    // nothing). The name is the generated class's own plus the processor's suffix, so a move
    // cannot break it. Each node is instantiated once to learn its class and its inputs.
    static {
        Map<String, Supplier<? extends MeshNode>> desktop;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Supplier<? extends MeshNode>> generated = (Map<String, Supplier<? extends MeshNode>>) Class
                    .forName(MeshNodeRegistry_MeshNodes.class.getName() + RegistryProcessor.DESKTOP_SUFFIX)
                    .getField("MAP").get(null);
            desktop = generated;
        } catch (Throwable unavailable) {
            desktop = Map.of();
        }
        Map<String, Supplier<? extends MeshNode>> nodes = new HashMap<>();
        for (Map.Entry<String, Supplier<? extends MeshNode>> e : MeshNodeRegistry_MeshNodes.MAP.entrySet()) {
            nodes.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Supplier<? extends MeshNode>> e : desktop.entrySet()) {
            nodes.put(e.getKey(), e.getValue());
        }
        Map<Class<? extends MeshNode>, String> ids = new HashMap<>();
        Set<String> inputTypes = new HashSet<>();
        for (Map.Entry<String, Supplier<? extends MeshNode>> e : nodes.entrySet()) {
            MeshNode probe = e.getValue().get();
            ids.put(probe.getClass(), e.getKey());
            for (InputPort input : probe.inputs()) {
                if (DEFAULT_PORT.equals(input.name)) {
                    inputTypes.add(e.getKey());
                }
            }
        }
        REGISTERED_NODES = Collections.unmodifiableMap(nodes);
        REGISTRY_IDS_BY_CLASS = Collections.unmodifiableMap(ids);
        INPUT_NODE_TYPES = Collections.unmodifiableSet(inputTypes);
    }

    /** Failures nodes of the current run carried on past, thrown together once it has run. */
    public final List<String> deferredFailures = new ArrayList<>();

    /** Values the host binds for scene-bound input nodes, e.g. {@code model} for {@code scene_model()}. */
    public final Map<String, Object> graphInputs = new HashMap<>();

    /**
     * Overrides of the current run as set, keyed {@code path.port} (or {@code path.port.x}); see
     * {@link #setOverrides}.
     */
    public final Map<String, Object> overrides = new LinkedHashMap<>();

    /**
     * Groups by name, looked up ahead of the registry: the program's own, those its host
     * registered, and library groups once a build has looked them up.
     */
    public final Map<String, Supplier<? extends MeshNode>> groups = new HashMap<>();

    /** The program every run builds its node tree from. */
    public final NodeGroup program;

    /**
     * The current run's report and the only view of it: its declared records are the program's
     * top level, its children the evaluated ones, its time the run's total and its peak heap the
     * run's peak.
     */
    public GraphNodeContext root = new GraphNodeContext();

    /**
     * Counts changes to {@link #root}: every reset and every evaluation entry point that ran, a
     * full run or a {@link #pull}, adds one, so a reader re-reads the tree when it moves.
     */
    public int revision;

    /** Overrides by node path, then by port (or {@code port.x}). */
    private final Map<String, Map<String, Object>> overridesByPath = new HashMap<>();

    /** Field context each record's mesh output opens, built once per record. */
    private final Map<GraphNodeContext, FieldContext> meshContexts = new IdentityHashMap<>();

    /** Samples used heap while a node runs, so the peak can be attributed to that node. */
    private final HeapSampler heapSampler = new HeapSampler();

    /**
     * Nesting depth of public evaluation entry points, so the sampler and the run's clock start
     * and stop once.
     */
    private int activeEvaluations;

    /** When the outermost running entry point started, in {@link System#nanoTime} units. */
    private long evaluationStartedNanos;

    /**
     * Whether a group call evaluates its whole body when called, as a source-order run does; a
     * {@link #pull} evaluates only the body nodes the outputs it reads depend on.
     */
    private boolean evaluateWholeGroups = true;

    /**
     * A runtime bound to a program, its node tree built.
     *
     * @param program the program, e.g. a scene's graph
     * @throws IllegalArgumentException when the program names a node type nothing provides
     */
    public NodeGraphRuntime(NodeGroup program) {
        this(program, Map.of());
    }

    /**
     * A runtime bound to a program that calls groups of its own, its node tree built.
     *
     * @param program the program
     * @param groups  groups by name, looked up ahead of the registry and the library
     * @throws IllegalArgumentException when the program names a node type nothing provides
     */
    public NodeGraphRuntime(NodeGroup program, Map<String, Supplier<? extends MeshNode>> groups) {
        this.program = program;
        this.groups.putAll(groups);
        reset();
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
     * the DSL graph (e.g. the dungeon viewer wiring {@code astar_corridors}' tile-lattice
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
     * The id of the program's last top-level node, the one a run reads its result from by
     * default.
     *
     * @return the id, or {@code null} for an empty program
     */
    public String lastId() {
        return root.declared.isEmpty() ? null
                : root.declared.get(root.declared.size() - 1).nodeAssignmentId();
    }

    /**
     * The output a port of the final top-level node produced in the most recent execution.
     *
     * @param outputPortName name of the output port to read
     * @return the port's value, or {@code null} when nothing ran or the port is
     *         missing
     */
    public Object lastOutput(String outputPortName) {
        return root.declared.isEmpty() ? null : getNodeOutput(lastId(), outputPortName);
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
     * Loads and executes a graph resource and returns the executed runtime,
     * every node's outputs readable through {@link #getNodeOutput},
     * {@link #intOutput} and {@link #lastOutput}.
     *
     * @param dslPath   classpath resource or file path of the graph
     * @param overrides path overrides; see {@link #setOverrides} for the key format
     * @throws IllegalStateException when loading or execution fails
     * @return the executed runtime
     */
    public static NodeGraphRuntime executeResource(String dslPath, Map<String, Object> overrides) {
        try {
            NodeGraphRuntime runtime = fromResource(dslPath);
            runtime.executeGraphResult(runtime.lastId(), RESULT, overrides);
            return runtime;
        } catch (Exception failure) {
            throw new IllegalStateException("graph " + dslPath + " failed", failure);
        }
    }

    /**
     * Loads a graph resource into a runtime without executing it.
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
     * Log the current run's report: its total and peak heap, plus every node that took
     * {@link #SLOW_NODE_MS} or more. Most steps take under a second, and a slow one usually means a
     * planning or correctness problem, so it must be visible.
     *
     * @param prefix log prefix naming the caller, e.g. {@code "[mesh-viewer]"}
     */
    public void logTimings(String prefix) {
        Platforms.log(prefix + " graph run report " + String.join(LINE_BREAK, treeLines(SLOW_NODE_MS)));
    }

    /**
     * The current run's report as text, the one form the log and node-inspect print: a total line
     * with the run's time, peak heap and revision, then the node tree indented by depth.
     *
     * @param minMs records faster than this are left out
     * @return the lines, the total line first
     */
    public List<String> treeLines(long minMs) {
        List<String> lines = new ArrayList<>();
        lines.add("total " + root.elapsedMs() + " ms  "
                + Math.round(root.peakHeapBytes / HeapSampler.BYTES_PER_MIB) + " MiB  revision "
                + revision);
        for (GraphNodeContext record : treeRecords()) {
            if (record.elapsedMs() < minMs) {
                continue;
            }
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
     * The supplier for a node id in {@link #REGISTERED_NODES}.
     *
     * @param type node id to look up
     * @return the supplier, or {@code null} when no node is registered under it
     */
    public static Supplier<? extends MeshNode> supplierFor(String type) {
        return REGISTERED_NODES.get(type);
    }

    /**
     * What a node type names in this runtime: one of its {@link #groups}, else a registered node,
     * else a library group, whose file's groups then join {@link #groups}.
     *
     * @param type group name or registered node id
     * @return the supplier, or {@code null} when nothing goes by that name
     */
    public Supplier<? extends MeshNode> resolve(String type) {
        Supplier<? extends MeshNode> supplier = groups.get(type);
        if (supplier != null) {
            return supplier;
        }
        supplier = supplierFor(type);
        if (supplier != null) {
            return supplier;
        }
        for (Map.Entry<String, Supplier<? extends MeshNode>> group : GroupLibrary.find(type).entrySet()) {
            groups.putIfAbsent(group.getKey(), group.getValue());
        }
        return groups.get(type);
    }

    /**
     * Error text for a node id absent from {@link #REGISTERED_NODES}, naming the real cause when
     * the id exists but only in the desktop registry this build cannot load.
     *
     * @param type the unresolvable node id
     * @return message for the thrown exception
     */
    public static String missingNodeMessage(String type) {
        if (MeshNodeRegistry_MeshNodes.DESKTOP_ONLY_IDS.contains(type)) {
            return "Mesh node '" + type + "' is desktop-only and unavailable in this build";
        }
        return "Unknown node type: " + type;
    }

    /**
     * Loads DSL source into a runtime whose program is the source's top level, calling the
     * source's own groups.
     *
     * @param source DSL program text
     * @throws RuntimeException when the source does not parse or names something undefined
     * @return a runtime ready to execute its program
     */
    public static NodeGraphRuntime fromSource(String source) {
        return GroupLibrary.load(source);
    }

    /**
     * Runs the graph and returns the final value on the given output port.
     *
     * @param finalOutputId id of the node to read the result from
     * @param outputPortName name of the output port on {@code finalOutputId}
     * @throws Exception if any node fails
     * @return the value on the requested port, or a fallback probed across common port names
     */
    public Object executeGraphResult(String finalOutputId, String outputPortName) throws Exception {
        return executeGraphResult(finalOutputId, outputPortName, Map.of());
    }

    /**
     * Runs the program's top level in declaration order, each group call's whole body included,
     * on a freshly built tree with path overrides applied, and reads the result port.
     *
     * @param finalOutputId id of the node to read the result from
     * @param outputPortName name of the output port on {@code finalOutputId}
     * @param overridesByPath path overrides as {@link #setOverrides} takes them; may be null
     * @throws Exception if any node fails internally
     * @throws IllegalArgumentException if an override names no real path or port
     * @return the value on the requested port, or a fallback probed across common port names
     */
    public Object executeGraphResult(String finalOutputId, String outputPortName,
            Map<String, Object> overridesByPath) throws Exception {
        reset();
        setOverrides(overridesByPath == null ? Map.of() : overridesByPath);
        evaluateWholeGroups = true;
        beginEvaluation();
        try {
            for (GraphNodeContext record : root.declared) {
                evaluate(record);
            }
        } finally {
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

        // Fallback: walk the evaluated top-level records from the last and probe common mesh
        // port names, for graphs whose output node isn't named as expected.
        String[] probeNames = { outputPortName, MESH, GEOMETRY, RESULT };
        for (int child = root.children.size() - 1; child >= 0; child--) {
            GraphNodeContext ctx = root.children.get(child);
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
     * Drops the node tree and builds the program's afresh for a new run, every node a new
     * instance; overrides must be set again.
     *
     * @throws IllegalArgumentException when the program names a node type nothing provides
     */
    public void reset() {
        revision++;
        root = new GraphNodeContext();
        root.graphInputs = graphInputs;
        deferredFailures.clear();
        meshContexts.clear();
        overrides.clear();
        overridesByPath.clear();
        program.build(new GroupBuilder(this, root));
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
     * Checks an override key against the current tree without running anything.
     *
     * @param key override key, see {@link #setOverrides}
     * @throws IllegalArgumentException when the path or port does not exist, naming the nearest
     *                                  real paths or the node's ports
     * @return the node path and the port spec ({@code port} or {@code port.x})
     */
    public String[] validateOverrideKey(String key) {
        int dot = key.indexOf(PORT_SEPARATOR);
        String path = dot < 0 ? key : key.substring(0, dot);
        GraphNodeContext statement = statementAt(path);
        if (statement == null) {
            throw new IllegalArgumentException("override " + key + ": " + noNodeAt(path));
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
     * The record declared at a node path of the current tree, evaluated or not, so nothing runs.
     *
     * @param path {@code /}-joined call-site ids, e.g. {@code layout/field}
     * @return the record, or {@code null} when no record is at the path ({@link #noNodeAt} says
     *         why)
     */
    public GraphNodeContext statementAt(String path) {
        GraphNodeContext statement = root;
        for (String segment : path.split(GraphNodeContext.PATH_SEPARATOR, -1)) {
            GraphNodeContext scope = statement;
            statement = null;
            for (GraphNodeContext record : scope.declared) {
                if (record.nodeAssignmentId().equals(segment)) {
                    statement = record;
                }
            }
            if (statement == null) {
                return null;
            }
        }
        return statement;
    }

    /**
     * Why {@link #statementAt} found nothing at a path: the path and the real paths nearest it by
     * Levenshtein distance.
     *
     * @param path the path no record is at
     * @return {@code no node at path '...'; nearest: ...}
     */
    public String noNodeAt(String path) {
        List<String> ranked = new ArrayList<>();
        collectPaths(root, ranked);
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
                    current[column] = Math.min(substitution, Math.min(previous[column], current[column - 1]) + 1);
                }
                int[] swap = previous;
                previous = current;
                current = swap;
            }
            distances.put(candidate, previous[candidate.length()]);
        }
        ranked.sort((left, right) -> Integer.compare(distances.get(left), distances.get(right)));
        return "no node at path '" + path + "'" + NEAREST
                + String.join(LIST_SEPARATOR, ranked.subList(0, Math.min(NEAREST_PATH_COUNT, ranked.size())));
    }

    /**
     * Adds the path of every record declared below a scope to a list, depth first in declaration
     * order, for the nearest-path suggestions of {@link #noNodeAt}.
     *
     * @param scope the record whose body is listed
     * @param paths list receiving the paths
     */
    private static void collectPaths(GraphNodeContext scope, List<String> paths) {
        for (GraphNodeContext record : scope.declared) {
            paths.add(record.path);
            collectPaths(record, paths);
        }
    }

    /**
     * Ports an override may set on a record: its node's declared inputs, a group's parameters,
     * plus any input the graph wires.
     *
     * @param statement the record at the override's path
     * @return port names, declared order first
     */
    private static List<String> staticPorts(GraphNodeContext statement) {
        List<String> ports = new ArrayList<>();
        for (InputPort input : statement.node.inputs()) {
            ports.add(input.name);
        }
        for (String argument : statement.inputSources.keySet()) {
            if (!ports.contains(argument)) {
                ports.add(argument);
            }
        }
        return ports;
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
        String path = reference.substring(0, dot);
        GraphNodeContext target = statementAt(path);
        if (target == null) {
            throw new IllegalArgumentException("pull " + reference + ": " + noNodeAt(path));
        }
        evaluateWholeGroups = false;
        beginEvaluation();
        try {
            List<GraphNodeContext> calls = new ArrayList<>();
            for (GraphNodeContext call = target; call != root; call = call.parent) {
                calls.add(call);
            }
            for (int call = calls.size() - 1; call >= 0; call--) {
                evaluate(calls.get(call));
            }
            throwDeferredFailures();
            return readOutput(target, reference.substring(dot + 1));
        } finally {
            endEvaluation();
        }
    }

    /**
     * Fails the run once its nodes have evaluated when any node reported a failure it carried on
     * past ({@link GraphNodeContext#reportFailure}).
     *
     * @throws IllegalArgumentException naming every deferred failure of the current run
     */
    private void throwDeferredFailures() {
        if (!deferredFailures.isEmpty()) {
            throw new IllegalArgumentException(deferredFailures.size()
                    + " failure(s) in the graph:\n" + String.join(LINE_BREAK, deferredFailures));
        }
    }

    /**
     * Opens an evaluation entry point; the outermost starts the heap sampler and the run's clock,
     * so a full run and a pull time the run the same way and a nested entry is not counted twice.
     */
    private void beginEvaluation() {
        if (activeEvaluations++ == 0) {
            heapSampler.start();
            evaluationStartedNanos = System.nanoTime();
        }
    }

    /**
     * Closes an evaluation entry point; the outermost adds its wall time to the run's total,
     * stops the sampler and moves {@link #revision}.
     */
    private void endEvaluation() {
        if (--activeEvaluations == 0) {
            root.elapsedNanos += System.nanoTime() - evaluationStartedNanos;
            heapSampler.stop();
            revision++;
        }
    }

    /**
     * Evaluates a record once, its wired sources first, and adds it to its scope's evaluated
     * children; a group call binds its parameters and, when whole groups run, its body and outputs.
     *
     * @param record the record, whose scope has been entered
     * @throws Exception if the node fails
     * @return the record
     */
    private GraphNodeContext evaluate(GraphNodeContext record) throws Exception {
        GraphNodeContext scope = record.parent;
        String id = record.nodeAssignmentId();
        if (scope.childrenById.get(id) == record) {
            return record;
        }
        Map<String, Object> pathOverrides = overridesByPath.getOrDefault(record.path, Map.of());
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (Map.Entry<String, Object> wire : record.inputSources.entrySet()) {
            if (!pathOverrides.containsKey(wire.getKey())) {
                arguments.put(wire.getKey(), readSource(record, wire.getValue(),
                        record.inputSourcePorts.get(wire.getKey())));
            }
        }
        // Whole-port overrides first, then vector components of the resolved (or overridden) vector.
        for (Map.Entry<String, Object> override : pathOverrides.entrySet()) {
            if (!override.getKey().contains(PORT_SEPARATOR)) {
                arguments.put(override.getKey(), override.getValue());
                record.input(override.getKey(), override.getValue());
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
            record.input(override.getKey().substring(0, dot), overridden);
        }

        FieldContext fieldContext = null;
        for (int earlier = scope.declared.indexOf(record) - 1; earlier >= 0 && fieldContext == null; earlier--) {
            GraphNodeContext previous = scope.declared.get(earlier);
            if (scope.childrenById.get(previous.nodeAssignmentId()) != previous) {
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

        // The previous mesh-producing node of the scope, as sequential execution defines it.
        record.setFieldContext(fieldContext == null ? scope.fieldContext() : fieldContext);
        for (Map.Entry<String, Object> resolved : arguments.entrySet()) {
            record.setInputValue(resolved.getKey(), resolved.getValue());
        }
        scope.children.add(record);
        scope.childrenById.put(id, record);

        if (!(record.node instanceof NodeGroup group)) {
            long started = System.nanoTime();
            heapSampler.resetPeak();
            try {
                record.node.evaluate(record);
                AutoTagHook.applyIfApplicable(record.node, record, id);
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
        // are evaluated, run its body and compute every output.
        for (InputPort param : group.inputs()) {
            if (record.getInputValue(param.name) != null) {
                continue;
            }
            if (param.defaultValue == null) {
                throw new RuntimeException(FUNCTION + record.type + "' missing argument '"
                        + param.name + "'");
            }
            record.setInputValue(param.name, param.defaultValue);
            record.input(param.name, param.defaultValue);
        }
        if (record.declared.isEmpty()) {
            throw new RuntimeException(FUNCTION + record.type + "' has empty body");
        }
        if (!evaluateWholeGroups) {
            return record;
        }
        long started = System.nanoTime();
        try {
            for (GraphNodeContext body : record.declared) {
                evaluate(body);
            }
        } finally {
            record.elapsedNanos += System.nanoTime() - started;
        }
        if (record.outputWires.isEmpty()) {
            readOutput(record, RESULT);
        }
        for (String output : record.outputWires.keySet()) {
            readOutput(record, output);
        }
        for (OutputPort output : group.outputs()) {
            readOutput(record, output.name);
        }
        return record;
    }

    /**
     * Reads an output port of a record, computing a group call's output on first read: a wired
     * output reads its source, a group wiring none exposes its last body node's outputs, and a
     * compiled group computes an unwired output from all the wired ones.
     *
     * @param record the record to read
     * @param port   output port name
     * @throws Exception if a body node the output depends on fails
     * @return the port's value, or {@code null} when the record has no such port
     */
    private Object readOutput(GraphNodeContext record, String port) throws Exception {
        if (!(record.node instanceof NodeGroup group) || record.hasOutput(port)) {
            return record.getOutput(port);
        }
        long started = System.nanoTime();
        try {
            if (record.outputWires.isEmpty()) {
                GraphNodeContext last = evaluate(record.declared.get(record.declared.size() - 1));
                for (Map.Entry<String, Object> output : last.getOutputsSnapshot().entrySet()) {
                    record.setOutput(output.getKey(), output.getValue());
                    record.outputSources.put(output.getKey(), last);
                    record.outputSourcePorts.put(output.getKey(), output.getKey());
                }
            } else if (record.outputWires.containsKey(port)) {
                Object wire = record.outputWires.get(port);
                String wirePort = record.outputWirePorts.get(port);
                record.setOutput(port, readSource(record, wire, wirePort));
                record.outputSources.put(port, wire);
                record.outputSourcePorts.put(port, wirePort);
            } else {
                for (Map.Entry<String, Object> wired : record.outputWires.entrySet()) {
                    if (!record.hasOutput(wired.getKey())) {
                        String wirePort = record.outputWirePorts.get(wired.getKey());
                        record.setOutput(wired.getKey(), readSource(record, wired.getValue(), wirePort));
                        record.outputSources.put(wired.getKey(), wired.getValue());
                        record.outputSourcePorts.put(wired.getKey(), wirePort);
                    }
                }
                group.evaluate(record);
                // A computed output's sources are the wired outputs it was computed from; one the
                // group left unset reads as null rather than evaluating again.
                List<Object> wiredSources = new ArrayList<>();
                List<Object> wiredPorts = new ArrayList<>();
                for (String wired : record.outputWires.keySet()) {
                    wiredSources.add(record.outputSources.get(wired));
                    wiredPorts.add(record.outputSourcePorts.get(wired));
                }
                for (OutputPort declared : group.outputs()) {
                    if (!record.hasOutput(declared.name)) {
                        record.setOutput(declared.name, null);
                    } else if (!record.outputWires.containsKey(declared.name)) {
                        record.outputSources.put(declared.name, Collections.unmodifiableList(wiredSources));
                        record.outputSourcePorts.put(declared.name, Collections.unmodifiableList(wiredPorts));
                    }
                }
            }
        } finally {
            record.elapsedNanos += System.nanoTime() - started;
        }
        return record.getOutput(port);
    }

    /**
     * The value a wire carries to a reader: a parameter of the reader or a call enclosing it, an
     * output of an earlier record (evaluated first), or a literal.
     *
     * @param reader the record reading the wire, or the group call reading its own output wire
     * @param source the wire's source record, or a literal
     * @param port   the source's output port, or the parameter's name
     * @throws Exception if the source node fails
     * @return the value
     */
    private Object readSource(GraphNodeContext reader, Object source, String port) throws Exception {
        if (!(source instanceof GraphNodeContext record)) {
            return source;
        }
        for (GraphNodeContext level = reader; level != null; level = level.parent) {
            if (level == record) {
                return record.getInputValue(port);
            }
        }
        return readOutput(evaluate(record), port);
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
     * @param finalOutputId id of the node to read the mesh from
     * @param outputPortName name of the output port on {@code finalOutputId}
     * @throws Exception if any node fails
     * @return mesh topology on the requested port, or {@code null} if the result isn't a mesh or bundle
     */
    public MeshTopology executeGraphToMesh(String finalOutputId, String outputPortName) throws Exception {
        return executeGraphToMesh(finalOutputId, outputPortName, Map.of());
    }

    /**
     * Variant of {@link #executeGraphToMesh(String, String)} that also accepts path overrides.
     *
     * @param finalOutputId id of the node to read the mesh from
     * @param outputPortName name of the output port on {@code finalOutputId}
     * @param overridesByPath path overrides; see {@link #setOverrides} for the key format
     * @throws Exception if any node fails
     * @return mesh topology on the requested port, or {@code null} if the result isn't a mesh or bundle
     */
    public MeshTopology executeGraphToMesh(String finalOutputId, String outputPortName,
            Map<String, Object> overridesByPath) throws Exception {
        return meshFromValue(executeGraphResult(finalOutputId, outputPortName, overridesByPath));
    }

}
