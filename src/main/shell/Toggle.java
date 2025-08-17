package shell;

import shell.ui.main.PanelTypes;

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
    ScalePath(true, "scalePath");

    private boolean initialValue;
    public boolean value;
    public String shortName;
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

    public void toggle() {
        value = !value;
    }

    public static void setPanelFocus(PanelTypes focusedPanel) {
        IsMainFocused.value = focusedPanel == PanelTypes.KnotView;
        IsInfoFocused.value = focusedPanel == PanelTypes.Info;
        IsTerminalFocused.value = focusedPanel == PanelTypes.Terminal;
    }

    public String shortName() {
        return shortName;
    }

    public static void resetAll() {
        for (Toggle toggle : Toggle.values()) {
            if (toggle.shouldReset) {
                toggle.value = toggle.initialValue;
            }
        }
    }
}
