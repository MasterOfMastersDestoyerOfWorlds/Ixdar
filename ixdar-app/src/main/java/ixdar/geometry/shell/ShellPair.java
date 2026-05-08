package ixdar.geometry.shell;

import ixdar.geometry.knot.Knot;

public class ShellPair {
    public int priority;
    public Shell shell;
    public Knot k;

    /**
     * Bind a {@link Shell} to its owning {@link Knot} with a render/scheduling priority.
     *
     * @param shell the shell geometry
     * @param k the knot the shell belongs to
     * @param priority ordering hint (higher values processed first by the consumer)
     */
    public ShellPair(Shell shell, Knot k, int priority) {
        this.shell = shell;
        this.priority = priority;
        this.k = k;
    }
}
