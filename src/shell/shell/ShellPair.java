package shell.shell;

public class ShellPair {
    public int priority;
    public Shell shell;

    public ShellPair(Shell shell, int priority) {
        this.shell = shell;
        this.priority = priority;
    }
}
