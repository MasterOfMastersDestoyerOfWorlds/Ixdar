package ixdar.scenes.model;

/**
 * One selectable model in a scene's model list: a human-facing {@link #displayName}
 * shown in the ESC menu and terminal, paired with the {@link #path} handed to the
 * scene's loader (a mesh file path for the layout scenes, or an absolute catalog
 * path for the mesh viewer).
 */
public final class ModelChoice {

    /** Human-facing label shown in the dropdown and matched by the {@code model} command. */
    public final String displayName;

    /** Loader argument the scene resolves when this choice is selected. */
    public final String path;

    /**
     * Bind a display name to its loader path.
     *
     * @param displayName label shown to the viewer
     * @param path loader argument passed to the scene when this choice is picked
     */
    public ModelChoice(String displayName, String path) {
        this.displayName = displayName;
        this.path = path;
    }
}
