package ixdar.scenes.model;

/**
 * One selectable model in a scene's model list: a human-facing {@link #displayName} shown in the
 * ESC menu and terminal, paired with the {@link #path} handed to the scene's loader.
 */
public final class ModelChoice {

    /** Human-facing label shown in the dropdown and matched by the {@code model} command. */
    public final String displayName;

    /** Loader argument the scene resolves when this choice is selected. */
    public final String path;

    /** What the loader should do with {@link #path}. */
    public final Kind kind;

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
     * @param kind whether the path names a mesh file or a DSL graph
     */
    public ModelChoice(String displayName, String path, Kind kind) {
        this.displayName = displayName;
        this.path = path;
        this.kind = kind;
    }

    /** How a scene should load a choice's path. */
    public enum Kind {
        /** A mesh file for {@code MeshLoader}, or a {@code fixture:} token. */
        MESH_FILE,
        /** A DSL graph to parse and execute. */
        DSL
    }
}
