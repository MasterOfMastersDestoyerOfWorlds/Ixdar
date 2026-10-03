package ixdar.scenes.ring;

import java.util.List;

import ixdar.scenes.model.ControlHint;

/**
 * One tool the editing {@link RingScene} hosts. Exactly one is active: it owns the scene's clicks,
 * drags and tool keys, and keeps its state when another tool takes over.
 */
public interface EditTool {

    /**
     * Name the tool-switch hint and the log call the tool by.
     *
     * @return a short lower-case name
     */
    String toolName();

    /** Become the active tool: take the mouse and start showing what the tool shows. */
    void activate();

    /** Hand the mouse back for another tool, keeping every edit and selection for later. */
    void deactivate();

    /** One frame, active or not; an inactive tool keeps only what must stay in step. */
    void perFrame();

    /**
     * Append the tool's own key and mouse hints, which the scene's key handler dispatches.
     *
     * @param controls the scene's control list, rebuilt whenever the active tool changes
     */
    void addControls(List<ControlHint> controls);
}
