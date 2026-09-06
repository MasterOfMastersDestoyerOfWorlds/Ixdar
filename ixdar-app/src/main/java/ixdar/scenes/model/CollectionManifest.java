package ixdar.scenes.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.nodes.data.LoadMeshNode;
import ixdar.geometry.mesh.nodes.math.InputBooleanNode;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;
import ixdar.platform.Platforms;
import ixdar.platform.json.JsonValue;

/**
 * Reads and writes a collection's {@code collection.dsl}: one {@code input_boolean} keep flag and
 * one {@code load_mesh} per member, sorted by member name so the same collection always renders to
 * the same bytes.
 */
public final class CollectionManifest {

    /** Node type emitted for each member's mesh file. */
    public static final String LOAD_MESH = "load_mesh";

    /** Node type emitted for each member's keep flag. */
    public static final String INPUT_BOOLEAN = "input_boolean";

    /** Prefix distinguishing a keep flag's parameter name from anything else in the graph. */
    public static final String KEEP_PREFIX = "keep:";

    /** Statement-id prefix of a keep flag, mirroring {@link #KEEP_PREFIX}. */
    public static final String KEEP_ID_PREFIX = "keep_";

    /** Statement-id prefix used when a member's stem cannot start a DSL identifier. */
    public static final String MEMBER_ID_PREFIX = "member_";

    /** Line separator written regardless of platform, so the bytes do not depend on the host. */
    public static final String NEWLINE = "\n";

    /** Suffix of the Trellis settings sidecar beside a member's mesh file. */
    public static final String SETTINGS_SUFFIX = ".settings.json";

    private CollectionManifest() {
    }

    /**
     * Render the manifest text: a header comment, every keep flag, then every {@code load_mesh},
     * each group sorted by member name. The last statement is a {@code load_mesh}, so executing
     * the graph yields geometry rather than a boolean.
     *
     * @param collection collection to render
     * @return the manifest source text, ending in a newline
     */
    public static String render(ModelCollection collection) {
        Integer[] order = new Integer[collection.memberCount()];
        for (int member = 0; member < order.length; member++) {
            order[member] = member;
        }
        Arrays.sort(order, Comparator.comparing(member -> collection.memberNames[member]));

        StringBuilder text = new StringBuilder();
        text.append("# Ixdar model collection \"").append(collection.name).append('"')
                .append(" - ").append(order.length).append(" members, ")
                .append(collection.keptCount()).append(" kept").append(NEWLINE);
        text.append("# Scans in ").append(collection.directory.toAbsolutePath()).append(NEWLINE);
        text.append("# Membership and keep/reject decisions only; members are merged nowhere yet.")
                .append(NEWLINE);
        text.append(NEWLINE);
        for (int member : order) {
            text.append(keepStatementId(collection.memberNames[member])).append(" = ")
                    .append(INPUT_BOOLEAN).append("(name=\"").append(KEEP_PREFIX)
                    .append(collection.memberNames[member]).append("\", default=")
                    .append(collection.memberKeep[member]).append(')').append(NEWLINE);
        }
        text.append(NEWLINE);
        for (int member : order) {
            text.append(memberStatementId(collection.memberNames[member])).append(" = ")
                    .append(LOAD_MESH).append("(path=\"").append(collection.memberPaths[member])
                    .append("\")").append(NEWLINE);
        }
        return text.toString();
    }

    /**
     * Write {@link #render} to the collection's manifest path, creating parent directories.
     *
     * @param collection collection to persist
     * @throws IOException if the manifest cannot be written
     */
    public static void write(ModelCollection collection) throws IOException {
        Path manifest = collection.manifestPath;
        Path parent = manifest.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(manifest, render(collection).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Read a manifest back into a collection: members from the {@code load_mesh} paths, keep flags
     * from the matching {@code input_boolean} defaults. No mesh file is opened.
     *
     * @param manifest path of a manifest written by {@link #write}
     * @return the collection the manifest describes
     * @throws IOException if the manifest cannot be read
     */
    public static ModelCollection read(Path manifest) throws IOException {
        return parse(manifest, readUtf8(manifest));
    }

    /**
     * Keep flags a manifest records, keyed by member name. Members the manifest never mentioned
     * are simply absent, so a rescan can default them to kept.
     *
     * @param manifest path of a manifest, which need not exist
     * @return member name to keep flag; empty when the manifest is absent or unreadable
     */
    public static Map<String, Boolean> keepFlags(Path manifest) {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        if (!Files.isRegularFile(manifest)) {
            return flags;
        }
        try {
            collectKeepFlags(statements(readUtf8(manifest)), flags);
        } catch (IOException | RuntimeException ignored) {
            flags.clear();
        }
        return flags;
    }

    /**
     * Parse manifest text into a collection, naming it after the manifest's parent directory.
     *
     * @param manifest path the text came from, used for the name and the fallback directory
     * @param source manifest text
     * @return the collection the text describes
     */
    public static ModelCollection parse(Path manifest, String source) {
        List<PythonParser.ParsedNode> statements = statements(source);
        Map<String, Boolean> flags = new LinkedHashMap<>();
        collectKeepFlags(statements, flags);

        List<Path> files = new ArrayList<>();
        Path directory = null;
        for (PythonParser.ParsedNode statement : statements) {
            if (!LOAD_MESH.equals(statement.type)) {
                continue;
            }
            Object path = statement.arguments.get(LoadMeshNode.PATH.name);
            if (!(path instanceof String text) || text.isEmpty()) {
                continue;
            }
            Path file = Path.of(text);
            if (directory == null) {
                directory = file.getParent();
            }
            files.add(file);
        }
        files.sort(Comparator.comparing(CollectionManifest::stemOf));

        Path manifestParent = manifest.toAbsolutePath().getParent();
        if (directory == null) {
            directory = manifestParent == null ? manifest.toAbsolutePath() : manifestParent;
        }
        Path nameSource = manifestParent == null ? directory : manifestParent;
        Path fileName = nameSource.getFileName();
        String name = fileName == null ? ModelCollection.MANIFEST_FALLBACK_DIR : fileName.toString();

        String[] names = new String[files.size()];
        String[] paths = new String[files.size()];
        JsonValue[] settings = new JsonValue[files.size()];
        for (int member = 0; member < files.size(); member++) {
            Path file = files.get(member);
            names[member] = stemOf(file);
            paths[member] = file.toString();
            settings[member] = settingsBesideMember(file);
        }
        ModelCollection collection = new ModelCollection(name, directory,
                manifest.toAbsolutePath(), names, paths, settings);
        for (int member = 0; member < collection.memberCount(); member++) {
            Boolean keep = flags.get(names[member]);
            collection.memberKeep[member] = keep == null || keep;
        }
        return collection;
    }

    /**
     * Read the {@code *.settings.json} sidecar beside a member's mesh file through the platform's
     * JSON parser. Trellis writes these; Ixdar reads them and never writes one.
     *
     * @param memberFile the mesh file whose stem names the sidecar
     * @return the parsed sidecar, or {@code null} when it is absent or unreadable
     */
    public static JsonValue settingsBesideMember(Path memberFile) {
        Path parent = memberFile.getParent();
        String sidecarName = stemOf(memberFile) + SETTINGS_SUFFIX;
        Path sidecar = parent == null ? Path.of(sidecarName) : parent.resolve(sidecarName);
        if (!Files.isRegularFile(sidecar)) {
            return null;
        }
        try {
            JsonValue parsed = Platforms.get().parseJson(readUtf8(sidecar));
            return parsed.isObject() ? parsed : null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * The file stem naming a member.
     *
     * @param file mesh file of a member
     * @return the file name with its extension removed
     */
    public static String stemOf(Path file) {
        String fileName = file.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    /**
     * Statement id of a member's keep flag.
     *
     * @param memberName file stem naming the member
     * @return a DSL identifier unique to that member's flag
     */
    public static String keepStatementId(String memberName) {
        return KEEP_ID_PREFIX + identifier(memberName);
    }

    /**
     * Statement id of a member's {@code load_mesh}.
     *
     * @param memberName file stem naming the member
     * @return a DSL identifier for that member
     */
    public static String memberStatementId(String memberName) {
        String id = identifier(memberName);
        char first = id.isEmpty() ? '0' : id.charAt(0);
        return Character.isDigit(first) ? MEMBER_ID_PREFIX + id : id;
    }

    /**
     * Read a file as UTF-8 text. {@code Files.readString} is avoided because TeaVM's class library
     * cannot compile it, and this class is reachable from the web entry point.
     *
     * @param file file to read
     * @return the file's text
     * @throws IOException if the file cannot be read
     */
    private static String readUtf8(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /**
     * Parse manifest text into top-level statements.
     *
     * @param source manifest text
     * @return the parsed statements in source order
     */
    private static List<PythonParser.ParsedNode> statements(String source) {
        return new PythonParser(new PythonLexer(source)).parseGraph();
    }

    /**
     * Collect every {@code input_boolean} whose parameter name carries {@link #KEEP_PREFIX}.
     *
     * @param statements parsed manifest statements
     * @param flags map the member-name to keep-flag pairs are added to
     */
    private static void collectKeepFlags(List<PythonParser.ParsedNode> statements,
            Map<String, Boolean> flags) {
        for (PythonParser.ParsedNode statement : statements) {
            if (!INPUT_BOOLEAN.equals(statement.type)) {
                continue;
            }
            Object name = statement.arguments.get(InputBooleanNode.NAME.name);
            if (!(name instanceof String text) || !text.startsWith(KEEP_PREFIX)) {
                continue;
            }
            Object value = statement.arguments.get(InputBooleanNode.DEFAULT.name);
            flags.put(text.substring(KEEP_PREFIX.length()), Boolean.TRUE.equals(value));
        }
    }

    /**
     * Fold a member name into the letters, digits and underscores a DSL identifier allows.
     *
     * @param memberName file stem naming the member
     * @return the folded identifier text
     */
    private static String identifier(String memberName) {
        StringBuilder id = new StringBuilder(memberName.length());
        for (int position = 0; position < memberName.length(); position++) {
            char character = memberName.charAt(position);
            id.append(Character.isLetterOrDigit(character) || character == '_' ? character : '_');
        }
        return id.toString();
    }
}
