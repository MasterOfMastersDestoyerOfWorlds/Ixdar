package ixdar.scenes.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.platform.json.JsonValue;

/**
 * A scene's list of selectable models, scanned from a directory: the quad-layout input meshes
 * checked into the repo, the staging directory the {@code sync-models} CLI fills, a plain directory
 * of mesh files, or a directory of scans read as a {@link ModelCollection}.
 */
public final class ModelCatalog {

    public static final String QUADLAYOUT_DIR = "test/resources/quadlayout";

    public static final String IN_TRI_SUFFIX = "_in_tri.off";

    public static final String OBJ_DIR = "obj";

    public static final String VOYAGE_DIR = "voyage";

    public static final String OBJ_EXTENSION = ".obj";

    public static final String DSL_DIR = "dsl";

    public static final String GLTF_PREFIX = "glTF";

    public static final String DSL_EXTENSION = ".dsl";

    public static final String REPO_PREFIX = "repo";

    public static final String STAGED_PREFIX = "staged ";

    public static final String DSL_RESOURCE_DIRECTORY = "src/main/resources/dsl";

    public static final String DSL_BUILD_DIRECTORY = "target/classes/dsl";

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
        collect(root.resolve(DSL_DIR), DSL_EXTENSION, ModelChoice.Kind.DSL, "DSL", found);
        collect(root.resolve(OBJ_DIR).resolve(VOYAGE_DIR), OBJ_EXTENSION, ModelChoice.Kind.MESH_FILE,
                "OBJ voyage", found);
        collect(root.resolve(OBJ_DIR).resolve("blends"), OBJ_EXTENSION, ModelChoice.Kind.MESH_FILE,
                "OBJ", found);
        collect(root, MeshLoader.GLB_EXTENSION, ModelChoice.Kind.MESH_FILE, GLTF_PREFIX, found);
        collect(root, MeshLoader.GLTF_EXTENSION, ModelChoice.Kind.MESH_FILE, GLTF_PREFIX, found);
        return new ModelCatalog(root, sorted(found));
    }

    /**
     * The mesh-node scenes' model list: every repo graph below {@code repoDslRoot}, named
     * {@code repo: <relative path>}, then the staging directory's entries prefixed {@code staged},
     * minus any staged file that resolves to a repo graph. Each group is sorted, so the order is
     * stable.
     *
     * @param repoDslRoot the repo's DSL resource folder; its graphs load and save as tracked files
     * @param stagingRoot staging directory, contributing nothing when absent
     * @return the catalog rooted at {@code repoDslRoot}, empty when neither folder is readable
     */
    public static ModelCatalog repoGraphsAndStaging(Path repoDslRoot, Path stagingRoot) {
        List<ModelChoice> found = new ArrayList<>();
        collect(repoDslRoot, DSL_EXTENSION, ModelChoice.Kind.DSL, REPO_PREFIX, found);
        sorted(found);
        Set<Path> repoFiles = new HashSet<>();
        for (ModelChoice repoGraph : found) {
            repoFiles.add(realPathOf(repoGraph.path));
        }
        for (ModelChoice staged : staging(stagingRoot).choices) {
            if (!repoFiles.contains(realPathOf(staged.path))) {
                found.add(new ModelChoice(STAGED_PREFIX + staged.displayName, staged.path,
                        staged.kind));
            }
        }
        return new ModelCatalog(repoDslRoot, found);
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
     * Scan a directory of scans as one named collection: every mesh file directly inside it becomes
     * a member named by its file stem, sorted, carrying the settings of its {@code *.settings.json}
     * sidecar. Keep flags come from the directory's manifest when one exists; a member the manifest
     * never mentioned starts out kept.
     *
     * @param directory directory of scans
     * @return the collection, with no members when the directory is absent or unreadable
     */
    public static ModelCollection collection(Path directory) {
        Path absolute = directory.toAbsolutePath();
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(absolute)) {
            try (Stream<Path> stream = Files.list(absolute)) {
                stream.filter(Files::isRegularFile)
                    .filter(path -> MeshLoader.isSupported(path.getFileName().toString()))
                    .forEach(files::add);
            } catch (IOException ignored) {
                files.clear();
            }
        }
        files.sort(Comparator.comparing(CollectionManifest::stemOf));
        String[] names = new String[files.size()];
        String[] paths = new String[files.size()];
        JsonValue[] settings = new JsonValue[files.size()];
        for (int member = 0; member < files.size(); member++) {
            Path file = files.get(member);
            names[member] = CollectionManifest.stemOf(file);
            paths[member] = file.toAbsolutePath().toString();
            settings[member] = CollectionManifest.settingsBesideMember(file);
        }
        Path manifest = ModelCollection.manifestFor(absolute);
        Path fileName = absolute.getFileName();
        String name = fileName == null ? ModelCollection.MANIFEST_FALLBACK_DIR : fileName.toString();
        ModelCollection collection =
                new ModelCollection(name, absolute, manifest, names, paths, settings);
        Map<String, Boolean> flags = CollectionManifest.keepFlags(manifest);
        for (int member = 0; member < collection.memberCount(); member++) {
            Boolean keep = flags.get(collection.memberNames[member]);
            collection.memberKeep[member] = keep == null || keep;
        }
        return collection;
    }

    /**
     * View a collection as a model catalog, so the model menu, the {@code model} command and the
     * {@code [} / {@code ]} keys reach its members the way they reach any other model list.
     *
     * @param collection collection whose members become the catalog's choices
     * @return a catalog rooted at the collection's directory
     */
    public static ModelCatalog ofCollection(ModelCollection collection) {
        List<ModelChoice> found = new ArrayList<>();
        for (int member = 0; member < collection.memberCount(); member++) {
            found.add(new ModelChoice(collection.memberNames[member],
                    collection.memberPaths[member]));
        }
        return new ModelCatalog(collection.directory, sorted(found));
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
     * The repo's DSL resource folder: below the working directory when the scene runs from the
     * module, else below the module directory when it runs from the repo root.
     *
     * @return absolute path of the folder, which need not exist (the web build has none)
     */
    public static Path repoDslRoot() {
        Path inWorkingDirectory = Path.of(DSL_RESOURCE_DIRECTORY);
        Path root = Files.isDirectory(inWorkingDirectory) ? inWorkingDirectory
                : Path.of(MeshLoader.MODULE_DIRECTORY, DSL_RESOURCE_DIRECTORY);
        return root.toAbsolutePath();
    }

    /**
     * The tracked file a DSL resource name lives in, preferred over the build output because that
     * is the copy a written statement persists into.
     *
     * @param resourceName graph's path below the {@code dsl} resource folder
     * @return the file's path, relative to the working directory, or {@code null} when absent
     */
    public static String trackedDslFile(String resourceName) {
        Path inWorkingDirectory = Path.of(DSL_RESOURCE_DIRECTORY, resourceName);
        if (Files.exists(inWorkingDirectory)) {
            return inWorkingDirectory.toString();
        }
        Path inModule = Path.of(MeshLoader.MODULE_DIRECTORY, DSL_RESOURCE_DIRECTORY, resourceName);
        return Files.exists(inModule) ? inModule.toString() : null;
    }

    /**
     * A DSL resource as a choice: its tracked file when one exists on disk, and the packaged
     * resource otherwise, which is the only copy the web build has.
     *
     * @param resourceName graph's path below the {@code dsl} resource folder
     * @return the graph's choice, whose {@link ModelChoice#path} is {@code null} without a file
     */
    public static ModelChoice packagedGraph(String resourceName) {
        return new ModelChoice(resourceName, trackedDslFile(resourceName), ModelChoice.Kind.DSL,
                resourceName);
    }

    /**
     * Resolve a launch property such as {@code -Dixdar.model}: a directory opens as a collection,
     * a catalog token resolves through {@code catalog}, a {@code .dsl} path is a graph, and anything
     * else is a mesh file path, tried below the module directory when absent here.
     *
     * @param token property value
     * @param catalog catalog to resolve tokens against, or {@code null}
     * @return the choice the property names
     */
    public static ModelChoice launchChoice(String token, ModelCatalog catalog) {
        Path directory = Path.of(token);
        if (Files.isDirectory(directory)) {
            Path absolute = directory.toAbsolutePath();
            return new ModelChoice(absolute.getFileName().toString(), absolute.toString(),
                    ModelChoice.Kind.COLLECTION);
        }
        ModelChoice match = catalog == null ? null : catalog.resolve(token);
        if (match != null) {
            return match;
        }
        Path file = Path.of(token);
        if (!Files.exists(file) && Files.exists(Path.of(MeshLoader.MODULE_DIRECTORY, token))) {
            file = Path.of(MeshLoader.MODULE_DIRECTORY, token);
        }
        ModelChoice.Kind kind = token.endsWith(DSL_EXTENSION) ? ModelChoice.Kind.DSL
                : ModelChoice.Kind.MESH_FILE;
        return new ModelChoice(file.getFileName().toString(), file.toAbsolutePath().toString(),
                kind);
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
     * Find the entry whose loader path is the file {@code file} names, relative or absolute.
     *
     * @param file path of the file, or {@code null}
     * @return matching index, or {@code -1} when none matches or {@code file} is {@code null}
     */
    public int indexOfFile(String file) {
        return file == null ? -1 : indexOfPath(Path.of(file).toAbsolutePath().toString());
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
                        prefix + ": " + dir.relativize(path),
                        path.toAbsolutePath().toString(),
                        kind)));
        } catch (IOException ignored) {
            found.clear();
        }
    }

    /**
     * The file a path finally names, following symlinks, so a staged link to a repo graph compares
     * equal to the graph itself.
     *
     * @param path loader path of a choice
     * @return the real path, or the normalized absolute path when it cannot be resolved
     */
    private static Path realPathOf(String path) {
        Path file = Path.of(path);
        try {
            return file.toRealPath();
        } catch (IOException unresolvable) {
            return file.toAbsolutePath().normalize();
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
