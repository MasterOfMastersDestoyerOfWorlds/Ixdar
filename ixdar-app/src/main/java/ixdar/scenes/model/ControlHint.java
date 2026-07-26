package ixdar.scenes.model;

import ixdar.gui.ui.actions.Action;

/**
 * One row in the ESC menu's Controls section: a {@link #key} label, a short
 * {@link #description}, an optional {@link #action} (clickable in the menu, and fired by the
 * scene key handler when a key press matches {@link #keyCode}). A {@link #NO_KEY} keycode marks a
 * display-only row (orbit, scroll) with no keyboard trigger.
 */
public final class ControlHint {

    /** {@link #keyCode} value for a row with no keyboard trigger (display-only). */
    public static final int NO_KEY = -1;

    /** Key code that fires {@link #action}, or {@link #NO_KEY} for a display-only row. */
    public final int keyCode;

    /** Key label shown to the viewer. */
    public final String key;

    /** Short description of the control's effect. */
    public final String description;

    /** Effect invoked on click or key press, or {@code null} for a display-only hint. */
    public final Action action;

    /**
     * Build a key-bound, clickable control hint.
     *
     * @param keyCode key code that fires {@code action} (see {@link ixdar.platform.input.Keys})
     * @param key key label shown to the viewer
     * @param description short description of the effect
     * @param action effect invoked on click or key press
     */
    public ControlHint(int keyCode, String key, String description, Action action) {
        this.keyCode = keyCode;
        this.key = key;
        this.description = description;
        this.action = action;
    }

    /**
     * Build a clickable control hint with no keyboard trigger.
     *
     * @param key key label shown to the viewer
     * @param description short description of the effect
     * @param action effect invoked on click, or {@code null} for display-only
     */
    public ControlHint(String key, String description, Action action) {
        this(NO_KEY, key, description, action);
    }

    /**
     * Build a display-only control hint (no click action, no keyboard trigger).
     *
     * @param key key label shown to the viewer
     * @param description short description of the effect
     */
    public ControlHint(String key, String description) {
        this(NO_KEY, key, description, null);
    }
}
