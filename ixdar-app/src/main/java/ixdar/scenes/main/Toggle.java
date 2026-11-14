package ixdar.scenes.main;

import ixdar.gui.ui.actions.Action;

public enum Toggle {

    CalculateKnot(true, "calcKnot"),
    CutKnot(false, "cutKnot"),
    DrawMainPath(false, "mainPath"),
    DrawNumberLabels(true, "numberLabels"),
    DrawMetroDiagram(true, "metro"),
    DrawKnotGradient(true, "knotGrad"),
    DrawCutMatch(true, "cutMatch"),
    DrawGridLines(false, "grid"),
    DrawDisplayedKnots(true, "dispKnots"),
    Manifold(false, "manifold"),
    CanSwitchLayer(true, "switchLayer"),
    IsMainFocused(true, false, "focusMain"),
    IsTerminalFocused(false, false, "focusTerm"),
    IsInfoFocused(false, false, "focusInfo"),
    IxdarSkip(true, "ixdarSkip"),
    IxdarMirrorAnswerSharing(false, "ixdarFlip"),
    IxdarCheckMirroredAnswerSharing(false, "checkFlip"),
    IxdarRotationalAnswerSharing(true, "ixdarRot"),
    IxdarCheckRotationalAnswerSharing(false, "checkRot"),
    IxdarKnotDistance(false, "knotDist"),
    SnapToGrid(true, "gridSnap"),
    CanSwitchTopLayer(true, "topLayer"),
    KnotSurfaceViewSimpleCut(false, "ksvSimpleCut"),
    RecordKnotAnimation(false, "recordKnotAnim"),
    ScalePath(true, "scalePath"),
    GameMode(true, false, "gameMode");
    public boolean value;
    public String shortName;

    private boolean initialValue;
    private boolean shouldReset;

    private Toggle(boolean value, String shortName) {
        this.value = value;
        this.shouldReset = true;
        this.initialValue = value;
        this.shortName = shortName;
    }

    private Toggle(boolean value, boolean shouldReset, String shortName) {
        this.value = value;
        this.shouldReset = shouldReset;
        this.initialValue = value;
        this.shortName = shortName;
    }

    /**
     * Flip {@link #value}.
     */
    public void toggle() {
        value = !value;
    }

    /**
     * Wrap {@code action} so it runs only while this toggle is on, for a key that belongs to one
     * pane.
     *
     * @param action the effect to gate
     * @return an action that performs {@code action} while {@link #value} holds
     */
    public Action gate(Action action) {
        return () -> {
            if (value) {
                action.perform();
            }
        };
    }

    /**
     * Turn on the focus toggle of the pane that just took focus and the other focus toggles off.
     *
     * @param focusedPanel {@code IsMainFocused}, {@code IsInfoFocused} or {@code IsTerminalFocused};
     *                     {@code null} leaves every pane unfocused
     */
    public static void setPanelFocus(Toggle focusedPanel) {
        IsMainFocused.value = focusedPanel == IsMainFocused;
        IsInfoFocused.value = focusedPanel == IsInfoFocused;
        IsTerminalFocused.value = focusedPanel == IsTerminalFocused;
    }

    /**
     * Compact terminal/CLI alias for this toggle (e.g. {@code calcKnot}).
     *
     * @return short name used by terminal commands and {@code TGL}/{@code TOGGLE} file directives
     */
    public String shortName() {
        return shortName;
    }

    /**
     * Restore every toggle whose {@code shouldReset} flag is true to its declared initial value.
     * Toggles flagged non-resettable (e.g. focus state, {@code GameMode}) are left untouched.
     */
    public static void resetAll() {
        for (Toggle toggle : Toggle.values()) {
            if (toggle.shouldReset) {
                toggle.value = toggle.initialValue;
            }
        }
    }
}
