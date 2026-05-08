package ixdar.platform;

import ixdar.scenes.main.PaneTypes;

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
     * Set the {@code IsMainFocused} / {@code IsInfoFocused} / {@code IsTerminalFocused} toggles
     * so exactly one is true based on which pane just took focus.
     *
     * @param focusedPanel pane that should become focused; others become unfocused
     */
    public static void setPanelFocus(PaneTypes focusedPanel) {
        IsMainFocused.value = focusedPanel == PaneTypes.KnotView;
        IsInfoFocused.value = focusedPanel == PaneTypes.Info;
        IsTerminalFocused.value = focusedPanel == PaneTypes.Terminal;
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
