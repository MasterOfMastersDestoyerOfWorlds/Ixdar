package ixdar.annotations.command;

/**
 * What a terminal command acts on. A scene says which of these it shows through
 * {@code Scene.dataset}; the scene-free ones are always there, so the terminal never asks for them.
 */
public enum Subsystem {
    TERMINAL("the terminal", true),
    TOGGLES("the toggles", true),
    FILES("ixdar files", true),
    KNOT("a knot", false),
    VIEW("a view", false),
    MODEL("a model", false),
    RINGS("ring regions", false);

    /** What the subsystem's data is called in a refusal: "mv acts on a knot; this scene shows none". */
    public final String noun;

    /** Whether the data lives outside every scene (the terminal itself, the toggles, files on disk). */
    public final boolean sceneFree;

    Subsystem(String noun, boolean sceneFree) {
        this.noun = noun;
        this.sceneFree = sceneFree;
    }
}
