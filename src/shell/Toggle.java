package shell;

public class Toggle {
    public boolean value;
    public ToggleType type;

    public static Toggle calculateKnot = new Toggle(true, ToggleType.CalculateKnot);
    public static Toggle drawMainPath = new Toggle(false, ToggleType.DrawMainPath);
    public static Toggle drawMetroDiagram = new Toggle(true, ToggleType.DrawMetroDiagram);
    public static Toggle drawKnotGradient = new Toggle(true, ToggleType.DrawKnotGradient);
	public static Toggle drawCutMatch = new Toggle(true, ToggleType.DrawCutMatch);

    public Toggle(boolean value, ToggleType type) {
        this.type = type;
        this.value = value;
    }

    public void toggle() {
        value = !value;
    }
}
