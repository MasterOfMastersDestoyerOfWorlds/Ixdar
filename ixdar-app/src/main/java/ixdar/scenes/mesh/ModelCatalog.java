package ixdar.scenes.mesh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * VIEW-7 supporting class. Scans the model staging directory (default
 * {@code ~/.ix/ixdar-models/}, overridable via the {@code IXDAR_MODEL_DIR}
 * environment variable) and returns a list of available mesh entries.
 *
 * The staging directory is populated by the Daud {@code sync-models} CLI
 * from multiple source locations (DSL examples, voyage OBJs, user OBJs in
 * {@code ~/Blends}). This class does not care about the sources — only the
 * aggregated view — so the future S3-backed resources ticket only changes
 * how the staging dir gets populated.
 */
public final class ModelCatalog {
    public static final String OBJ = "obj";
    public static final String VOYAGE = "voyage";
    public static final String OBJ_2 = ".obj";

    private final Path root;
    private final List<ModelEntry> entries;
    private int currentIndex = 0;

    /**
     * Build a catalog from {@link #defaultRoot()}.
     */
    public ModelCatalog() {
        this(defaultRoot());
    }

    /**
     * Build a catalog by scanning {@code root} for DSL files under
     * {@code dsl/}, voyage OBJs under {@code obj/voyage/}, and user
     * blends under {@code obj/blends/}.
     *
     * @param root staging directory to scan
     */
    public ModelCatalog(Path root) {
        this.root = root;
        this.entries = scan(root);
    }

    /**
     * Resolve the default staging directory: the {@code IXDAR_MODEL_DIR}
     * environment variable if set, else {@code ~/.ix/ixdar-models}.
     *
     * @return absolute path to the staging directory
     */
    public static Path defaultRoot() {
        String override = System.getenv("IXDAR_MODEL_DIR");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath();
        }
        return Path.of(System.getProperty("user.home"), ".ix", "ixdar-models");
    }

    /**
     * Staging directory the catalog was built from.
     *
     * @return scan root
     */
    public Path root() {
        return root;
    }

    /**
     * Immutable list of discovered model entries, sorted by display name.
     *
     * @return all model entries
     */
    public List<ModelEntry> entries() {
        return entries;
    }

    /**
     * Entry at the current cursor position.
     *
     * @return the current entry, or {@code null} if the catalog is empty
     */
    public ModelEntry current() {
        if (entries.isEmpty()) return null;
        return entries.get(currentIndex);
    }

    /**
     * Index of the current entry within {@link #entries()}.
     *
     * @return zero-based current index
     */
    public int currentIndex() {
        return currentIndex;
    }

    /**
     * Advance the cursor by one (wrapping at the end).
     *
     * @return the entry at the new cursor, or {@code null} if the catalog is empty
     */
    public ModelEntry next() {
        if (entries.isEmpty()) return null;
        currentIndex = (currentIndex + 1) % entries.size();
        return current();
    }

    /**
     * Step the cursor back by one (wrapping at the start).
     *
     * @return the entry at the new cursor, or {@code null} if the catalog is empty
     */
    public ModelEntry prev() {
        if (entries.isEmpty()) return null;
        currentIndex = (currentIndex - 1 + entries.size()) % entries.size();
        return current();
    }

    /**
     * Move the cursor to {@code index} if it is in range.
     *
     * @param index target index in {@link #entries()}
     * @return the entry at {@code index}, or {@code null} if out of range or the catalog is empty
     */
    public ModelEntry select(int index) {
        if (entries.isEmpty()) return null;
        if (index < 0 || index >= entries.size()) return null;
        currentIndex = index;
        return current();
    }

    /**
     * Find the catalog index whose absolute path matches, or -1.
     *
     * @param absolutePath path to look up against {@link ModelEntry#absolutePath()}
     * @return matching index, or {@code -1} if no entry has that path
     */
    public int indexOfPath(String absolutePath) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).absolutePath().toString().equals(absolutePath)) return i;
        }
        return -1;
    }

    private static List<ModelEntry> scan(Path root) {
        List<ModelEntry> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        scanSubdir(root.resolve("dsl"), Type.DSL, ".dsl", out);
        scanSubdir(root.resolve(OBJ).resolve(VOYAGE), Type.OBJ, OBJ_2, out);
        scanSubdir(root.resolve(OBJ).resolve("blends"), Type.OBJ, OBJ_2, out);
        out.sort(Comparator.comparing(ModelEntry::displayName));
        return List.copyOf(out);
    }

    private static void scanSubdir(Path dir, Type type, String extension, List<ModelEntry> out) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> stream = Files.walk(dir)) {
            stream
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase().endsWith(extension))
                .forEach(p -> out.add(buildEntry(dir, p, type)));
        } catch (IOException ignored) {
            // Missing dir or transient I/O error — just skip.
        }
    }

    private static ModelEntry buildEntry(Path subdirRoot, Path file, Type type) {
        Path rel = subdirRoot.relativize(file);
        String prefix = switch (type) {
            case DSL -> "DSL";
            case OBJ -> subdirRoot.getFileName().toString().equalsIgnoreCase(VOYAGE) ? "OBJ voyage" : "OBJ";
        };
        String display = prefix + " • " + rel.toString();
        return new ModelEntry(display, type, file.toAbsolutePath());
    }

    public enum Type { DSL, OBJ }

    public record ModelEntry(String displayName, Type type, Path absolutePath) {}
}
