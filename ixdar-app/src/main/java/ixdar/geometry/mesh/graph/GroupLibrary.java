package ixdar.geometry.mesh.graph;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;

/**
 * The node-group library: {@code .dsl} files under {@code dsl/groups/}, one per group and named
 * after it, which every runtime reads on a group's first call.
 */
public final class GroupLibrary {

    public static final String RESOURCE_DIRECTORY = "dsl/groups/";

    public static final String EXTENSION = ".dsl";

    private GroupLibrary() {
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
    public static Map<String, PythonParser.FunctionDef> find(String name) {
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
                for (Map.Entry<String, PythonParser.FunctionDef> def : find(type).entrySet()) {
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
}
