package ixdar.geometry.shell;

import ixdar.geometry.knot.Knot;

public class ShellPair {
    public int priority;
    public Shell shell;
    public Knot k;

    public ShellPair(Shell shell, Knot k, int priority) {
        this.shell = shell;
        this.priority = priority;
        this.k = k;
    }
}
