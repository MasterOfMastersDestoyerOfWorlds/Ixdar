package shell;

public class Toggle {
    public boolean value;
    ToggleType type;

    public Toggle(boolean value, ToggleType type){
        this.type = type;
        this.value = value;
    }

    public void toggle() {
        value = !value;
    }
}
