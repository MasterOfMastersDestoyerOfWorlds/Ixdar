package shell;

import shell.ui.main.PanelTypes;

public enum Toggle {

    CalculateKnot(true, "calcKnot"),
    DrawMainPath(false, "mainPath"),
    DrawMetroDiagram(true, "metro"),
    DrawKnotGradient(true, "knotGrad"),
    DrawCutMatch(true, "cutMatch"),
    DrawGridLines(false, "grid"),
    DrawDisplayedKnots(true, "dispKnots"),
    Manifold(false, "manifold"),
    CanSwitchLayer(true, "switchLayer"),
    IsMainFocused(true, "focusMain"),
    IsTerminalFocused(false, "focusTerm"),
    IsInfoFocused(false, "focusInfo"),
    IxdarSkip(false, "ixdarSkip"),
    IxdarMirrorAnswerSharing(false, "ixdarFlip"),
    IxdarRotationalAnswerSharing(true, "ixdarRot"),
    IxdarCheckMirroredAnswerSharing(true, "checkAns"),
    SnapToGrid(true, "gridSnap");

    public boolean value;
    public String shortName;

    private Toggle(boolean value, String shortName) {
        this.value = value;
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
}
