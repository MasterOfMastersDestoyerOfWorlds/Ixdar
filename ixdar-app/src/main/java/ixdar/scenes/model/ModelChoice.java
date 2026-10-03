package ixdar.scenes.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import ixdar.platform.Platforms;

/**
 * One selectable model in a scene's model list: a human-facing {@link #displayName} shown in the
 * model menu and terminal, paired with the {@link #path} handed to the scene's loader. A choice knows
 * where it lives on disk, how to read its source and which file an edit is saved into.
 */
public final class ModelChoice {

    /** Human-facing label shown in the dropdown and matched by the {@code model} command. */
    public final String displayName;

    /** Loader argument the scene resolves when this choice is selected. */
    public final String path;

    /** What the loader should do with {@link #path}. */
    public final Kind kind;

    /** Graph's path below the packaged {@code dsl} resource folder, or {@code null} for none. */
    public final String resourceName;

    /**
     * Bind a display name to a mesh-file path.
     *
     * @param displayName label shown to the viewer
     * @param path loader argument passed to the scene when this choice is picked
     */
    public ModelChoice(String displayName, String path) {
        this(displayName, path, Kind.MESH_FILE);
    }

    /**
     * Bind a display name to a path the loader handles according to {@code kind}.
     *
     * @param displayName label shown to the viewer
     * @param path loader argument passed to the scene when this choice is picked
     * @param kind whether the path names a mesh file, a DSL graph or a collection directory
     */
    public ModelChoice(String displayName, String path, Kind kind) {
        this(displayName, path, kind, null);
    }

    /**
     * Bind a display name to a path and to the packaged resource the same graph ships as.
     *
     * @param displayName label shown to the viewer
     * @param path loader argument, or {@code null} when no copy exists on disk (the web build)
     * @param kind whether the path names a mesh file, a DSL graph or a collection directory
     * @param resourceName graph's path below the packaged {@code dsl} folder, or {@code null}
     */
    public ModelChoice(String displayName, String path, Kind kind, String resourceName) {
        this.displayName = displayName;
        this.path = path;
        this.kind = kind;
        this.resourceName = resourceName;
    }

    /**
     * The file a reload reads and a save writes: a graph named under the build output maps to its
     * tracked {@code src/main/resources} copy, so a written statement persists and reads back.
     *
     * @return the working file, {@link #path} itself when it has no tracked copy, or {@code null}
     *     when the choice has no file on disk
     */
    public String workingFile() {
        if (path == null || kind != Kind.DSL) {
            return path;
        }
        String normalized = path.replace('\\', '/');
        int build = normalized.indexOf(ModelCatalog.DSL_BUILD_DIRECTORY);
        int name = build + ModelCatalog.DSL_BUILD_DIRECTORY.length() + 1;
        if (build < 0 || name >= normalized.length()) {
            return path;
        }
        String trackedName = normalized.substring(name);
        String tracked = ModelCatalog.trackedDslFile(trackedName);
        if (tracked == null) {
            return path;
        }
        Path beside = Path.of(normalized.substring(0, build), ModelCatalog.DSL_RESOURCE_DIRECTORY,
                trackedName);
        return Files.exists(beside) ? beside.toString() : tracked;
    }

    /**
     * Read the source text of {@link #workingFile()} as UTF-8.
     *
     * @return the file's text
     * @throws IOException when the choice has no file on disk or it cannot be read
     */
    public String readSource() throws IOException {
        String file = workingFile();
        if (file == null) {
            throw new IOException("no file on disk for " + displayName);
        }
        return new String(Files.readAllBytes(Path.of(file)), StandardCharsets.UTF_8);
    }

    /**
     * Hand the packaged copy of the graph to {@code onLoaded}, the only copy the web build has.
     *
     * @param onLoaded receives the graph text, possibly later on the platform's loader
     */
    public void loadPackagedSource(Consumer<String> onLoaded) {
        Platforms.get().loadSourceAsync(ModelCatalog.DSL_DIR, resourceName,
                Platforms.gl().getPlatformID(), onLoaded);
    }

    /**
     * Open this directory of scans as a collection.
     *
     * @return the collection, with no members when the directory is absent or unreadable
     */
    public ModelCollection openCollection() {
        return ModelCatalog.collection(Path.of(path));
    }

    /** How a scene should load a choice's path. */
    public enum Kind {
        /** A mesh file for {@code MeshLoader}, or a {@code graph:} token. */
        MESH_FILE,
        /** A DSL graph to parse and execute. */
        DSL,
        /** A directory of scans to open as a {@link ModelCollection}. */
        COLLECTION
    }
}
