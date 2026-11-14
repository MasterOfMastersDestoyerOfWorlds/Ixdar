package ixdar.tools;

import ixdar.gui.ui.actions.Action;
import ixdar.platform.input.Keys;

/**
 * One row in the model menu's Controls section: a {@link #key} with its modifiers, a
 * {@link #description}, and an optional {@link #action} its key or a click fires; automation fires
 * it by {@link #actionName}.
 */
public final class ControlHint {

    public static final String KEY_DESCRIPTION_GAP = "  ";

    /**
     * Stable id automation fires the row by, given where the row is made and kept however its
     * description changes with state; empty for a display-only row.
     */
    public final String actionName;

    /** The key that fires {@link #action}, or the mouse gesture a display-only row describes. */
    public final Keys key;

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
     * Build a clickable control hint.
     *
     * @param actionName  stable id automation fires the row by
     * @param key         key that fires {@code action}
     * @param description short description of the effect
     * @param action      effect invoked on click or key press
     */
    public ControlHint(String actionName, Keys key, String description, Action action) {
        this(actionName, key, false, description, action);
    }

    /**
     * Build a clickable control hint whose key needs Control held, such as Ctrl+S.
     *
     * @param actionName  stable id automation fires the row by
     * @param key         key that fires {@code action} with Control held
     * @param controlHeld whether the key fires only with Control held
     * @param description short description of the effect
     * @param action      effect invoked on click or key press
     */
    public ControlHint(String actionName, Keys key, boolean controlHeld, String description, Action action) {
        this(actionName, key, controlHeld, false, description, action);
    }

    /**
     * Build a clickable control hint whose key needs Control and Shift held, such as Ctrl+Shift+Z.
     *
     * @param actionName  stable id automation fires the row by
     * @param key         key that fires {@code action} with the modifiers held
     * @param controlHeld whether the key fires only with Control held
     * @param shiftHeld   whether the key fires only with Shift held
     * @param description short description of the effect
     * @param action      effect invoked on click or key press
     */
    public ControlHint(String actionName, Keys key, boolean controlHeld, boolean shiftHeld, String description,
            Action action) {
        this(actionName, key, controlHeld, shiftHeld, false, description, action);
    }

    /**
     * Build a display-only control hint, which describes a key or gesture and fires nothing.
     *
     * @param key         the key or mouse gesture, such as {@link Keys#DRAG}
     * @param description short description of the effect
     */
    public ControlHint(Keys key, String description) {
        this(key, false, false, description);
    }

    /**
     * Build a display-only control hint for a key or gesture made with modifiers held, such as
     * Shift+drag.
     *
     * @param key         the key or mouse gesture
     * @param controlHeld whether Control is held through it
     * @param shiftHeld   whether Shift is held through it
     * @param description short description of the effect
     */
    public ControlHint(Keys key, boolean controlHeld, boolean shiftHeld, String description) {
        this("", key, controlHeld, shiftHeld, false, description, null);
    }

    private ControlHint(String actionName, Keys key, boolean controlHeld, boolean shiftHeld,
            boolean repeatsWhileHeld, String description, Action action) {
        this.actionName = actionName;
        this.key = key;
        this.controlHeld = controlHeld;
        this.shiftHeld = shiftHeld;
        this.repeatsWhileHeld = repeatsWhileHeld;
        this.description = description;
        this.action = action;
    }

    /**
     * Build a key-bound hint whose action also repeats while the key is held, such as stepping
     * through a list.
     *
     * @param actionName  stable id automation fires the row by
     * @param key         key that fires {@code action}
     * @param description short description of the effect
     * @param action      effect invoked on click, key press and each repeat
     * @return the hint
     */
    public static ControlHint repeating(String actionName, Keys key, String description, Action action) {
        return new ControlHint(actionName, key, false, false, true, description, action);
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
     * The binding in text, such as {@code ctrl+shift+Z}, as automation and {@code ls keys} name it.
     *
     * @return the modifiers and the key's label
     */
    public String keyLabel() {
        return (controlHeld ? Keys.LEFT_CONTROL.label + "+" : "") + (shiftHeld ? Keys.LEFT_SHIFT.label + "+" : "")
                + key.label;
    }

    /**
     * The binding as key glyphs: the Control and Shift keycaps when held, then the key's; its
     * {@link #keyLabel} when the key has no glyph.
     *
     * @return the label to draw
     */
    public String glyphLabel() {
        if (key.glyph == 0) {
            return keyLabel();
        }
        return (controlHeld ? Keys.LEFT_CONTROL.glyph + "+" : "") + (shiftHeld ? Keys.LEFT_SHIFT.glyph + "+" : "")
                + key.glyph;
    }

    /**
     * The hint as one line: the {@link #keyLabel}, {@link #KEY_DESCRIPTION_GAP}, then the
     * description, as {@code ls keys} lists it.
     *
     * @return the key label and description
     */
    @Override
    public String toString() {
        return keyLabel() + KEY_DESCRIPTION_GAP + description;
    }
}
