package ixdar.geometry.shell;

import ixdar.geometry.knot.Knot;

public class ShellPair {
    public int priority;
    public Shell shell;
    public Knot k;

    /**
     * TODO: document {@code ShellPair}.
     *
     * @param shell TODO: describe
     * @param k TODO: describe
     * @param priority TODO: describe
     */
    public ShellPair(Shell shell, Knot k, int priority) {
        this.shell = shell;
        this.priority = priority;
        this.k = k;
    }
}
