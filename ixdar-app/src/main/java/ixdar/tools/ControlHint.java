package ixdar.tools;

import ixdar.gui.ui.actions.Action;

/**
 * One row in the model menu's Controls section: a {@link #key} label, a {@link #description}, and
 * an optional {@link #action} its key or a click fires; automation fires it by {@link #actionName}.
 */
public final class ControlHint {

    public static final int NO_KEY = -1;

    public static final String KEY_DESCRIPTION_GAP = "  ";

    /**
     * Stable id automation fires the row by, given where the row is made and kept however its key
     * label or description changes with state; empty for a display-only row.
     */
    public final String actionName;

    /** Key code that fires {@link #action}, or {@link #NO_KEY} for a row no key fires. */
    public final int keyCode;

    /** Key label shown to the viewer. */
    public final String key;

    /** Short description of the control's effect. */
    public final String description;

    /** Effect invoked on click or key press, or {@code null} for a display-only hint. */
    public final Action action;

    /**
     * Whether the key fires only with Control held; a click on the row needs no modifier.
     */
    public final boolean controlHeld;

    /**
     * Whether the key fires only with Shift held; a hint without it also fires with Shift held,
     * so a Shift variant is listed before its plain one.
     */
    public final boolean shiftHeld;

    /**
     * Whether the key's action fires again at the key handler's repeat interval while it is held,
     * as well as on the press.
     */
    public final boolean repeatsWhileHeld;

    /**
     * Build a clickable control hint, key-bound unless {@code keyCode} is {@link #NO_KEY}.
     *
     * @param actionName  stable id automation fires the row by
     * @param keyCode     key code that fires {@code action} (see {@link ixdar.platform.input.Keys}),
     *                    or {@link #NO_KEY}
     * @param key         key label shown to the viewer
     * @param description short description of the effect
     * @param action      effect invoked on click or key press
     */
    public ControlHint(String actionName, int keyCode, String key, String description, Action action) {
        this(actionName, keyCode, false, key, description, action);
    }

    /**
     * Build a clickable control hint whose key needs Control held, such as Ctrl+S.
     *
     * @param actionName  stable id automation fires the row by
     * @param keyCode     key code that fires {@code action} with Control held
     * @param controlHeld whether the key fires only with Control held
     * @param key         key label shown to the viewer
     * @param description short description of the effect
     * @param action      effect invoked on click or key press
     */
    public ControlHint(String actionName, int keyCode, boolean controlHeld, String key, String description,
            Action action) {
        this(actionName, keyCode, controlHeld, false, key, description, action);
    }

    /**
     * Build a clickable control hint whose key needs Control and Shift held, such as Ctrl+Shift+Z.
     *
     * @param actionName  stable id automation fires the row by
     * @param keyCode     key code that fires {@code action} with the modifiers held
     * @param controlHeld whether the key fires only with Control held
     * @param shiftHeld   whether the key fires only with Shift held
     * @param key         key label shown to the viewer
     * @param description short description of the effect
     * @param action      effect invoked on click or key press
     */
    public ControlHint(String actionName, int keyCode, boolean controlHeld, boolean shiftHeld, String key,
            String description, Action action) {
        this(actionName, keyCode, controlHeld, shiftHeld, false, key, description, action);
    }

    /**
     * Build a display-only control hint, which describes a gesture and fires nothing.
     *
     * @param keyCode     key code the gesture uses, usually {@link #NO_KEY}
     * @param key         key label shown to the viewer
     * @param description short description of the effect
     */
    public ControlHint(int keyCode, String key, String description) {
        this("", keyCode, false, false, false, key, description, null);
    }

    private ControlHint(String actionName, int keyCode, boolean controlHeld, boolean shiftHeld,
            boolean repeatsWhileHeld, String key, String description, Action action) {
        this.actionName = actionName;
        this.keyCode = keyCode;
        this.controlHeld = controlHeld;
        this.shiftHeld = shiftHeld;
        this.repeatsWhileHeld = repeatsWhileHeld;
        this.key = key;
        this.description = description;
        this.action = action;
    }

    /**
     * Build a key-bound hint whose action also repeats while the key is held, such as stepping
     * through a list.
     *
     * @param actionName  stable id automation fires the row by
     * @param keyCode     key code that fires {@code action}
     * @param key         key label shown to the viewer
     * @param description short description of the effect
     * @param action      effect invoked on click, key press and each repeat
     * @return the hint
     */
    public static ControlHint repeating(String actionName, int keyCode, String key, String description,
            Action action) {
        return new ControlHint(actionName, keyCode, false, false, true, key, description, action);
    }

    /**
     * What a toggle row reports once fired, so its state can be read from the reply.
     *
     * @param what the thing the row switches, as its description names it
     * @param on   whether it is now on
     * @return e.g. {@code part names on screen: on}
     */
    public static String toggled(String what, boolean on) {
        return what + (on ? ": on" : ": off");
    }

    /**
     * The hint as one line: the key label, {@link #KEY_DESCRIPTION_GAP}, then the description, as
     * the controls menu and {@code ls keys} list it.
     *
     * @return the key label and description
     */
    @Override
    public String toString() {
        return new StringBuilder(key).append(KEY_DESCRIPTION_GAP).append(description).toString();
    }
}
