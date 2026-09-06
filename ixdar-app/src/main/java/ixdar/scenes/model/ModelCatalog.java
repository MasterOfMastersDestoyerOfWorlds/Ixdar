package ixdar.scenes.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import ixdar.geometry.mesh.data.load.MeshLoader;

/**
 * A scene's list of selectable models, scanned from a directory. Three scans exist: the quad-layout
 * input meshes checked into the repo, the staging directory the {@code sync-models} CLI fills, and
 * a plain directory of mesh files such as the crawfish scans.
 */
public final class ModelCatalog {

    /** Directory holding quad-layout inputs, relative to the app working directory. */
    public static final String QUADLAYOUT_DIR = "test/resources/quadlayout";

    /** Suffix identifying a quad-layout input triangle mesh. */
    public static final String IN_TRI_SUFFIX = "_in_tri.off";

    /** Staging subdirectory holding OBJ corpora. */
    public static final String OBJ_DIR = "obj";

    /** Staging subdirectory of voyage OBJs, labelled apart from user blends. */
    public static final String VOYAGE_DIR = "voyage";

    /** Extension of the OBJ corpora in the staging directory. */
    public static final String OBJ_EXTENSION = ".obj";

    /** Staging subdirectory holding DSL graphs. */
    public static final String DSL_DIR = "dsl";

    /** Display-name prefix of glTF scans found anywhere under the staging directory. */
    public static final String GLTF_PREFIX = "glTF";

    /** Directory the catalog was scanned from. */
    public final Path root;

    /** Discovered models, sorted by display name. */
    public final List<ModelChoice> choices;

    private int index;

    private ModelCatalog(Path root, List<ModelChoice> choices) {
        this.root = root;
        this.choices = List.copyOf(choices);
    }

    /**
     * Scan for quad-layout inputs ({@code *_in_tri.off}). The matching {@code _out_quad} outputs are
     * skipped: they are pipeline results, not valid inputs. Paths stay relative to {@code root} so
     * they feed {@link MeshLoader#load} unchanged wherever the corpus was found.
     *
     * @param root directory to scan, falling back to the same module prefix {@link MeshLoader} uses
     * @return the catalog, empty when the corpus is absent
     */
    public static ModelCatalog quadLayout(Path root) {
        Path scanRoot = Files.isDirectory(root)
                ? root
                : Path.of(MeshLoader.MODULE_DIRECTORY, root.toString());
        List<ModelChoice> found = new ArrayList<>();
        if (Files.isDirectory(scanRoot)) {
            try (Stream<Path> stream = Files.walk(scanRoot)) {
                stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(IN_TRI_SUFFIX))
                    .forEach(path -> {
                        Path bare = root.resolve(scanRoot.relativize(path));
                        String fileName = bare.getFileName().toString();
                        String baseName = fileName.substring(0, fileName.length() - IN_TRI_SUFFIX.length());
                        Path parent = bare.getParent();
                        String figure = parent == null ? "" : parent.getFileName().toString();
                        String display = figure.isEmpty() ? baseName : baseName + " (" + figure + ")";
                        found.add(new ModelChoice(display, bare.toString().replace('\\', '/')));
                    });
            } catch (IOException ignored) {
                found.clear();
            }
        }
        return new ModelCatalog(root, sorted(found));
    }

    /**
     * Scan the staging directory: DSL graphs under {@code dsl/}, OBJs under {@code obj/voyage/}
     * and {@code obj/blends/}, glTF scans anywhere below the root. A root with neither
     * {@code dsl/} nor {@code obj/} is scanned as a plain {@link #directory} instead.
     *
     * @param root staging directory to scan
     * @return the catalog, empty when the directory is absent
     */
    public static ModelCatalog staging(Path root) {
        if (!Files.isDirectory(root.resolve(DSL_DIR)) && !Files.isDirectory(root.resolve(OBJ_DIR))) {
            return directory(root);
        }
        List<ModelChoice> found = new ArrayList<>();
        collect(root.resolve(DSL_DIR), ".dsl", ModelChoice.Kind.DSL, "DSL", found);
        collect(root.resolve(OBJ_DIR).resolve(VOYAGE_DIR), OBJ_EXTENSION, ModelChoice.Kind.MESH_FILE,
                "OBJ voyage", found);
        collect(root.resolve(OBJ_DIR).resolve("blends"), OBJ_EXTENSION, ModelChoice.Kind.MESH_FILE,
                "OBJ", found);
        collect(root, MeshLoader.GLB_EXTENSION, ModelChoice.Kind.MESH_FILE, GLTF_PREFIX, found);
        collect(root, MeshLoader.GLTF_EXTENSION, ModelChoice.Kind.MESH_FILE, GLTF_PREFIX, found);
        return new ModelCatalog(root, sorted(found));
    }

    /**
     * Scan a plain directory for every file {@link MeshLoader} can read, recursively. Display names
     * are the paths relative to {@code root}; loader paths are absolute.
     *
     * @param root directory to walk
     * @return the catalog, empty when the directory is absent or unreadable
     */
    public static ModelCatalog directory(Path root) {
        List<ModelChoice> found = new ArrayList<>();
        if (Files.isDirectory(root)) {
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                    .filter(path -> MeshLoader.isSupported(path.getFileName().toString()))
                    .forEach(path -> found.add(new ModelChoice(
                            root.relativize(path).toString().replace('\\', '/'),
                            path.toAbsolutePath().toString())));
            } catch (IOException ignored) {
                found.clear();
            }
        }
        return new ModelCatalog(root, sorted(found));
    }

    /**
     * Resolve the staging directory: {@code IXDAR_MODEL_DIR} if set, else {@code ~/.ix/ixdar-models}.
     *
     * @return absolute path to the staging directory
     */
    public static Path stagingRoot() {
        String override = System.getenv("IXDAR_MODEL_DIR");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath();
        }
        return Path.of(System.getProperty("user.home"), ".ix", "ixdar-models");
    }

    /**
     * Resolve a user-typed token: an exact display-name match first, then a case-insensitive
     * substring of the display name or path.
     *
     * @param choices list to search, which may hold graphs the catalog itself never scanned
     * @param token text typed at the terminal, e.g. {@code "fertility"}
     * @return the matching choice, or {@code null} when none matches
     */
    public static ModelChoice resolve(List<ModelChoice> choices, String token) {
        for (ModelChoice choice : choices) {
            if (choice.displayName.equalsIgnoreCase(token)) {
                return choice;
            }
        }
        String lower = token.toLowerCase();
        for (ModelChoice choice : choices) {
            if (choice.displayName.toLowerCase().contains(lower)
                    || choice.path.toLowerCase().contains(lower)) {
                return choice;
            }
        }
        return null;
    }

    /**
     * Entry at the cursor, for scenes that step through models with keys.
     *
     * @return the current choice, or {@code null} when the catalog is empty
     */
    public ModelChoice current() {
        return choices.isEmpty() ? null : choices.get(index);
    }

    /**
     * Cursor position.
     *
     * @return zero-based index into {@link #choices}
     */
    public int index() {
        return index;
    }

    /**
     * Step the cursor forward one, wrapping at the end.
     *
     * @return the choice now under the cursor, or {@code null} when the catalog is empty
     */
    public ModelChoice next() {
        return choices.isEmpty() ? null : select((index + 1) % choices.size());
    }

    /**
     * Step the cursor back one, wrapping at the start.
     *
     * @return the choice now under the cursor, or {@code null} when the catalog is empty
     */
    public ModelChoice prev() {
        return choices.isEmpty() ? null : select((index - 1 + choices.size()) % choices.size());
    }

    /**
     * Move the cursor to {@code target} if it is in range.
     *
     * @param target index to move to
     * @return the choice now under the cursor, or {@code null} if out of range or the catalog is empty
     */
    public ModelChoice select(int target) {
        if (choices.isEmpty() || target < 0 || target >= choices.size()) {
            return null;
        }
        index = target;
        return current();
    }

    /**
     * Find the entry with this loader path.
     *
     * @param path loader path to look up
     * @return matching index, or {@code -1} when no entry has that path
     */
    public int indexOfPath(String path) {
        for (int candidate = 0; candidate < choices.size(); candidate++) {
            if (choices.get(candidate).path.equals(path)) {
                return candidate;
            }
        }
        return -1;
    }

    /**
     * Resolve a token against this catalog alone.
     *
     * @param token text typed at the terminal
     * @return the matching choice, or {@code null} when none matches
     */
    public ModelChoice resolve(String token) {
        return resolve(choices, token);
    }

    /**
     * Add every file under {@code dir} with this extension, labelled by {@code prefix} and the path
     * relative to {@code dir}. A missing directory or read error contributes nothing.
     *
     * @param dir directory to walk
     * @param extension file extension to accept, lower case and dot-prefixed
     * @param kind how the loader should treat the discovered paths
     * @param prefix display-name prefix marking the corpus
     * @param found list the discovered choices are added to
     */
    private static void collect(Path dir, String extension, ModelChoice.Kind kind, String prefix,
            List<ModelChoice> found) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase().endsWith(extension))
                .forEach(path -> found.add(new ModelChoice(
                        prefix + " • " + dir.relativize(path),
                        path.toAbsolutePath().toString(),
                        kind)));
        } catch (IOException ignored) {
            found.clear();
        }
    }

    /**
     * Order a scan's results the way both the menu and the terminal list them.
     *
     * @param found choices to order in place
     * @return the same list, sorted by display name
     */
    private static List<ModelChoice> sorted(List<ModelChoice> found) {
        found.sort(Comparator.comparing(choice -> choice.displayName));
        return found;
    }
}
