package ixdar.nodegraph.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.syntax.PythonLexer;
import ixdar.nodegraph.syntax.PythonParser;

/**
 * The DSL loader and the node-group library: it turns parsed DSL, a program and its {@code def}
 * groups, into {@link NodeGroup}s that build wired records, and reads the library's {@code .dsl}
 * files under {@code dsl/groups/}, one per group and named after it, on a group's first call.
 */
public final class GroupLibrary {

    public static final String RESOURCE_DIRECTORY = "dsl/groups/";

    public static final String EXTENSION = ".dsl";

    private static final String WAS_REFERENCED_BEFORE_IT_WAS_EVALUATED = "' was referenced before it was evaluated!";

    private GroupLibrary() {
    }

    /**
     * Parses DSL source into a runtime whose program is the source's top level, calling the
     * source's own groups.
     *
     * @param source DSL program text
     * @throws RuntimeException when the source does not parse or names something undefined
     * @return a runtime ready to execute its program
     */
    public static NodeGraphRuntime load(String source) {
        PythonParser parser = new PythonParser(new PythonLexer(source));
        List<PythonParser.ParsedNode> statements = parser.parseGraph();
        return load(statements, parser.functionDefs());
    }

    /**
     * A runtime for parsed top-level statements calling the given groups ahead of the registry
     * and the library.
     *
     * @param statements top-level statements in source order
     * @param defs       groups by name, e.g. the source's own
     * @throws RuntimeException when a statement names or references something undefined
     * @return a runtime ready to execute its program
     */
    public static NodeGraphRuntime load(List<PythonParser.ParsedNode> statements,
            Map<String, PythonParser.FunctionDef> defs) {
        NodeGroup program = new NodeGroup() {
            @Override
            public void build(GroupBuilder group) {
                replay(statements, Map.of(), group);
            }
        };
        return new NodeGraphRuntime(program, suppliers(defs));
    }

    /**
     * Reads the library file named after a group and returns every group it defines, the named
     * one included, so a file may carry the helper groups its main group calls.
     *
     * @param name group name, which is also the file's base name
     * @throws IllegalStateException when the file exists but does not parse or does not define
     *                               the group its name promises
     * @return the file's groups by name, empty when the library has no such file
     */
    public static Map<String, Supplier<? extends MeshNode>> find(String name) {
        return suppliers(findDefinitions(name));
    }

    /**
     * The groups a program calls from the library, following library groups into the groups
     * their bodies call, so a validator can treat every one as defined.
     *
     * @param statements program statements to scan
     * @param known      groups already defined, which the scan neither reloads nor enters
     * @param registry   registered node ids, never looked up in the library
     * @return library groups reached from the program, by name
     */
    public static Map<String, PythonParser.FunctionDef> referencedGroups(
            List<PythonParser.ParsedNode> statements, Map<String, PythonParser.FunctionDef> known,
            Set<String> registry) {
        Map<String, PythonParser.FunctionDef> found = new LinkedHashMap<>();
        Deque<List<PythonParser.ParsedNode>> pending = new ArrayDeque<>();
        pending.add(statements);
        for (PythonParser.FunctionDef def : known.values()) {
            pending.add(def.body);
        }
        while (!pending.isEmpty()) {
            for (PythonParser.ParsedNode statement : pending.poll()) {
                String type = statement.type;
                if (registry.contains(type) || known.containsKey(type) || found.containsKey(type)) {
                    continue;
                }
                for (Map.Entry<String, PythonParser.FunctionDef> def : findDefinitions(type).entrySet()) {
                    if (!found.containsKey(def.getKey()) && !known.containsKey(def.getKey())) {
                        found.put(def.getKey(), def.getValue());
                        pending.add(def.getValue().body);
                    }
                }
            }
        }
        return found;
    }

    /**
     * Every group defined in the {@code .dsl} files of a directory outside the classpath, such
     * as a user's own group folder. A missing directory contributes nothing.
     *
     * @param directory folder of {@code .dsl} files
     * @throws IOException when the folder or one of its files cannot be read
     * @return the groups by name; a later file's group replaces an earlier one of the same name
     */
    public static Map<String, PythonParser.FunctionDef> loadDirectory(Path directory)
            throws IOException {
        Map<String, PythonParser.FunctionDef> defs = new LinkedHashMap<>();
        if (!Files.isDirectory(directory)) {
            return defs;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + EXTENSION)) {
            for (Path file : files) {
                defs.putAll(parseDefinitions(
                        new String(Files.readAllBytes(file), StandardCharsets.UTF_8)));
            }
        }
        return defs;
    }

    /**
     * Parses DSL source and returns only its {@code def} groups; top-level statements are ignored.
     *
     * @param source DSL text
     * @return the groups by name
     */
    public static Map<String, PythonParser.FunctionDef> parseDefinitions(String source) {
        PythonParser parser = new PythonParser(new PythonLexer(source));
        parser.parseGraph();
        return parser.functionDefs();
    }

    /**
     * The definitions of the library file named after a group.
     *
     * @param name group name, which is also the file's base name
     * @throws IllegalStateException when the file exists but does not parse or does not define
     *                               the group its name promises
     * @return the file's groups by name, empty when the library has no such file
     */
    private static Map<String, PythonParser.FunctionDef> findDefinitions(String name) {
        String source;
        try (InputStream stream = GroupLibrary.class.getClassLoader()
                .getResourceAsStream(RESOURCE_DIRECTORY + name + EXTENSION)) {
            if (stream == null) {
                return Map.of();
            }
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return Map.of();
        }
        Map<String, PythonParser.FunctionDef> defs;
        try {
            defs = parseDefinitions(source);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("library group " + name + ": " + failure.getMessage(),
                    failure);
        }
        if (!defs.containsKey(name)) {
            throw new IllegalStateException("library file " + name + EXTENSION
                    + " does not define a group named " + name);
        }
        return defs;
    }

    /**
     * Each definition as the constructor of its group: a {@link NodeGroup} whose parameters are
     * the definition's, typed by their {@link PortType} names, and whose build replays its body.
     *
     * @param defs groups by name
     * @return the constructors by name, each throwing IllegalArgumentException when a
     *         parameter's type names no port type or its default does not fit it
     */
    private static Map<String, Supplier<? extends MeshNode>> suppliers(
            Map<String, PythonParser.FunctionDef> defs) {
        Map<String, Supplier<? extends MeshNode>> suppliers = new LinkedHashMap<>();
        for (PythonParser.FunctionDef def : defs.values()) {
            suppliers.put(def.name, () -> {
                List<InputPort> inputs = new ArrayList<>();
                for (PythonParser.FunctionParam param : def.params) {
                    PortType type;
                    try {
                        type = PortType.valueOf(param.type);
                    } catch (IllegalArgumentException unknown) {
                        throw new IllegalArgumentException("group '" + def.name + "' parameter '"
                                + param.name + "' has unknown type '" + param.type + "'", unknown);
                    }
                    // DSL numbers parse as floats; a whole default of an INT parameter is an int.
                    Object defaultValue = type == PortType.INT && param.defaultValue instanceof Float number
                            && number == Math.rint(number) ? Integer.valueOf(Math.round(number))
                            : param.defaultValue;
                    inputs.add(new InputPort(param.name, type, defaultValue));
                }
                return new NodeGroup() {
                    @Override
                    public List<InputPort> inputs() {
                        return inputs;
                    }

                    @Override
                    public void build(GroupBuilder group) {
                        replay(def.body, def.outputs, group);
                    }
                };
            });
        }
        return suppliers;
    }

    /**
     * Declares parsed statements under a builder's scope and wires their arguments, then the
     * scope's return outputs, resolving every reference as the DSL scopes it.
     *
     * @param body    statements in source order
     * @param outputs the {@code return(...)} outputs by name, empty when there is none
     * @param group   the builder of the scope
     * @throws RuntimeException when a statement names or references something undefined
     */
    private static void replay(List<PythonParser.ParsedNode> body, Map<String, Object> outputs,
            GroupBuilder group) {
        GraphNodeContext scope = group.scope;
        for (PythonParser.ParsedNode statement : body) {
            GraphNodeContext record = group.node(statement.id, statement.type);
            for (Map.Entry<String, Object> argument : statement.arguments.entrySet()) {
                wire(scope, argument.getValue(), scope.declared.size() - 1, argument.getKey(),
                        record.inputSources, record.inputSourcePorts);
            }
        }
        for (Map.Entry<String, Object> output : outputs.entrySet()) {
            wire(scope, output.getValue(), scope.declared.size(), output.getKey(),
                    scope.outputWires, scope.outputWirePorts);
        }
    }

    /**
     * Wires one argument as the DSL scopes names: to the nearest earlier record or enclosing
     * parameter of its name, else as a literal.
     *
     * @param scope   record whose body holds the argument
     * @param raw     the parsed argument
     * @param before  index in {@code scope.declared} the reference must precede
     * @param key     port name the argument binds
     * @param sources map receiving the source record or the literal under {@code key}
     * @param ports   map receiving the source port under {@code key}
     * @throws RuntimeException when a reference names nothing declared before it
     */
    private static void wire(GraphNodeContext scope, Object raw, int before, String key,
            Map<String, Object> sources, Map<String, String> ports) {
        if (raw instanceof PythonParser.NodeReference ref) {
            GraphNodeContext below = null;
            for (GraphNodeContext level = scope; level != null; below = level, level = level.parent) {
                int bound = level == scope ? before : level.declared.indexOf(below);
                GraphNodeContext source = declaredBefore(level, ref.nodeId, bound);
                if (source != null) {
                    sources.put(key, source);
                    ports.put(key, ref.portName);
                    return;
                }
                if (level.node instanceof NodeGroup call && isParameter(call, ref.nodeId)) {
                    sources.put(key, level);
                    ports.put(key, ref.nodeId);
                    return;
                }
            }
            String where = scope.parent == null ? "Node '"
                    : GroupBuilder.IN_FUNCTION + scope.type + "': node '";
            throw new RuntimeException(where + ref.nodeId + WAS_REFERENCED_BEFORE_IT_WAS_EVALUATED);
        }
        if (raw instanceof String bareId && scope.node instanceof NodeGroup call) {
            if (isParameter(call, bareId)) {
                sources.put(key, scope);
                ports.put(key, bareId);
                return;
            }
            GraphNodeContext source = declaredBefore(scope, bareId, before);
            if (source != null) {
                sources.put(key, source);
                ports.put(key, NodeGraphRuntime.RESULT);
                return;
            }
        }
        sources.put(key, raw);
        ports.remove(key);
    }

    /**
     * The last record of an id among the first {@code bound} declared in a scope.
     *
     * @param scope the scope
     * @param id    record id
     * @param bound exclusive upper index
     * @return the record, or {@code null}
     */
    private static GraphNodeContext declaredBefore(GraphNodeContext scope, String id, int bound) {
        for (int index = Math.min(bound, scope.declared.size()) - 1; index >= 0; index--) {
            if (scope.declared.get(index).nodeAssignmentId().equals(id)) {
                return scope.declared.get(index);
            }
        }
        return null;
    }

    private static boolean isParameter(NodeGroup group, String name) {
        for (InputPort param : group.inputs()) {
            if (param.name.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
