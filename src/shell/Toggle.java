package shell;

import shell.ui.main.PanelTypes;

public enum Toggle {

    CalculateKnot(true),
    DrawMainPath(false),
    DrawMetroDiagram(true),
    DrawKnotGradient(true),
    DrawCutMatch(true),
    DrawGridLines(false),
    DrawDisplayedKnots(true),
    Manifold(false),
    CanSwitchLayer(true),
    IsMainFocused(true),
    IsTerminalFocused(false),
    IsInfoFocused(false);

    public boolean value;

    private Toggle(boolean value) {
        this.value = value;
    }

    public void toggle() {
        value = !value;
    }

    public static void setPanelFocus(PanelTypes focusedPanel) {
        IsMainFocused.value = focusedPanel == PanelTypes.KnotView;
        IsInfoFocused.value = focusedPanel == PanelTypes.Info;
        IsTerminalFocused.value = focusedPanel == PanelTypes.Terminal;
    }
}
