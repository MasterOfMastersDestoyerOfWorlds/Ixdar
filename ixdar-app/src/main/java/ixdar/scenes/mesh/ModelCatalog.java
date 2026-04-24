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

    public enum Type { DSL, OBJ }

    public record ModelEntry(String displayName, Type type, Path absolutePath) {}

    private final Path root;
    private final List<ModelEntry> entries;
    private int currentIndex = 0;

    public ModelCatalog() {
        this(defaultRoot());
    }

    public ModelCatalog(Path root) {
        this.root = root;
        this.entries = scan(root);
    }

    public static Path defaultRoot() {
        String override = System.getenv("IXDAR_MODEL_DIR");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath();
        }
        return Path.of(System.getProperty("user.home"), ".ix", "ixdar-models");
    }

    public Path root() {
        return root;
    }

    public List<ModelEntry> entries() {
        return entries;
    }

    public ModelEntry current() {
        if (entries.isEmpty()) return null;
        return entries.get(currentIndex);
    }

    public int currentIndex() {
        return currentIndex;
    }

    public ModelEntry next() {
        if (entries.isEmpty()) return null;
        currentIndex = (currentIndex + 1) % entries.size();
        return current();
    }

    public ModelEntry prev() {
        if (entries.isEmpty()) return null;
        currentIndex = (currentIndex - 1 + entries.size()) % entries.size();
        return current();
    }

    public ModelEntry select(int index) {
        if (entries.isEmpty()) return null;
        if (index < 0 || index >= entries.size()) return null;
        currentIndex = index;
        return current();
    }

    /** Find the catalog index whose absolute path matches, or -1. */
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
        scanSubdir(root.resolve("obj").resolve("voyage"), Type.OBJ, ".obj", out);
        scanSubdir(root.resolve("obj").resolve("blends"), Type.OBJ, ".obj", out);
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
            case OBJ -> subdirRoot.getFileName().toString().equalsIgnoreCase("voyage") ? "OBJ voyage" : "OBJ";
        };
        String display = prefix + " • " + rel.toString();
        return new ModelEntry(display, type, file.toAbsolutePath());
    }
}
