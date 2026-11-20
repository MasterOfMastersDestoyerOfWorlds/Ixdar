package ixdar.nodegraph.api;

import java.util.List;

/**
 * A node the graph debugger can step inside, holding the stepped state in its own fields (each run
 * builds fresh nodes); level 0 is its outermost step.
 */
public interface SteppableNode extends MeshNode {

    /**
     * What one step at each level is, outermost first, e.g. {@code [operator, drag]}.
     *
     * @return the level names, at least one
     */
    List<String> stepLevels();

    /**
     * Prepares to run the node a step at a time over its bound inputs; nothing is published until
     * {@link #finishSteps}.
     *
     * @param ctx the node's context, inputs bound
     */
    void beginSteps(NodeContext ctx);

    /**
     * Takes one step at a level, after {@link #beginSteps}.
     *
     * @param ctx   the node's context
     * @param level index into {@link #stepLevels}
     * @throws RuntimeException when the step fails; the debugger shows the failure
     * @return what the step did, or {@code null} when no step remains at that level, at level 0
     *         meaning the node has nothing left but {@link #finishSteps}
     */
    String step(NodeContext ctx, int level);

    /**
     * Runs every remaining step and publishes the node's outputs, as {@link #evaluate} would.
     *
     * @param ctx the node's context
     * @throws RuntimeException when a remaining step fails
     */
    void finishSteps(NodeContext ctx);

    /**
     * The value the node is working on, for the debugger to draw while it steps.
     *
     * @return the in-flight value, e.g. the T-mesh being contracted
     */
    Object stepShown();

    /**
     * What the last step changed, drawn over {@link #stepShown}.
     *
     * @return a value the host scene knows how to draw, or {@code null} for none
     */
    Object stepOverlay();
}
