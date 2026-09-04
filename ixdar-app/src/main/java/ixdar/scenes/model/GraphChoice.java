package ixdar.scenes.model;

import java.util.Map;

/**
 * One authored .dsl graph a model scene's menu offers: a display name, the
 * graph that builds it, and the literal overrides to run it with. Loading a
 * graph executes it fresh, so reloading is resetting.
 */
public final class GraphChoice {

    /** Name the model menu lists this graph under. */
    public final String displayName;

    /** Classpath resource or file path of the authored graph. */
    public final String dslPath;

    /** Per-node literal overrides ({@code "nodeId.argName"} keys), may be empty. */
    public final Map<String, Object> overrides;

    /**
     * Creates a graph menu entry.
     *
     * @param displayName name the model menu lists this graph under
     * @param dslPath     classpath resource or file path of the authored graph
     * @param overrides   per-node literal overrides, may be empty
     */
    public GraphChoice(String displayName, String dslPath, Map<String, Object> overrides) {
        this.displayName = displayName;
        this.dslPath = dslPath;
        this.overrides = overrides;
    }
}
