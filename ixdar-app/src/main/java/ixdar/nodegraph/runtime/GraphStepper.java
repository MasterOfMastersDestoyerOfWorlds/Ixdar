package ixdar.nodegraph.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ixdar.nodegraph.api.SteppableNode;
import ixdar.nodegraph.syntax.PythonParser;

/**
 * Runs a program a statement at a time, in written order, for the graph debugger: over, into
 * groups and {@link SteppableNode}s, to the next {@code breakpoint}, and back by replay.
 */
public final class GraphStepper {

    /** What one move does. */
    public enum Move {
        /** Run the next statement whole, or finish the node being stepped inside. */
        OVER,
        /** Enter the next group call's body, or open the next steppable node's inner steps. */
        INTO,
        /** Run the rest of the group being stepped, or finish the node being stepped inside. */
        OUT,
        /** One inner step of the node being stepped inside, at {@link Action#level}. */
        INNER
    }

    /**
     * One move the debugger made, kept so stepping back can replay it.
     *
     * @param move  what the move did
     * @param level the inner-step level of an {@link Move#INNER} move, else 0
     */
    public record Action(Move move, int level) {
    }

    /** The run being stepped. */
    public final NodeGraphRuntime runtime;

    /** Overrides every restart applies, as {@link NodeGraphRuntime#setOverrides} takes them. */
    public final Map<String, Object> overrides;

    /** The moves made since the start of the run, the last one last. */
    public final List<Action> history = new ArrayList<>();

    /** The node being stepped inside, or {@code null}. */
    public SteppableNode inner;

    /** The statement whose node {@link #inner} steps, or {@code null}. */
    public GraphNodeContext innerRecord;

    /** The statement the last move evaluated, entered or left; {@code null} at the start. */
    public GraphNodeContext last;

    /** What the last move did, one line. */
    public String lastStep = "";

    /** Why the last move failed, or {@code null}; the run's state is that move's partial one. */
    public Exception failure;

    /** Whether the run has evaluated its last statement. */
    public boolean finished;

    /** Whether the last move evaluated a {@code breakpoint} statement. */
    public boolean atBreakpoint;

    /** Scopes being stepped, the root first: each its call record and next statement's index. */
    private final List<GraphNodeContext> scopes = new ArrayList<>();

    /** Index of the next statement in each of {@link #scopes}. */
    private final List<Integer> nextIndices = new ArrayList<>();

    /**
     * A stepper at the start of a run, nothing evaluated.
     *
     * @param runtime   the run to step, built but not executed
     * @param overrides path overrides every restart applies
     * @throws IllegalArgumentException when an override names no path or port
     */
    public GraphStepper(NodeGraphRuntime runtime, Map<String, Object> overrides) {
        this.runtime = runtime;
        this.overrides = Map.copyOf(overrides);
        replay();
    }

    /**
     * The statement the next move evaluates in the scope being stepped.
     *
     * @return the statement, or {@code null} when the scope has none left (or a node is being
     *         stepped inside)
     */
    public GraphNodeContext next() {
        if (inner != null) {
            return null;
        }
        int top = scopes.size() - 1;
        List<GraphNodeContext> declared = scopes.get(top).declared;
        int index = nextIndices.get(top);
        return index < declared.size() ? declared.get(index) : null;
    }

    /**
     * How deep the stepping is: 0 at the top level, one more per group call entered.
     *
     * @return the number of entered group calls
     */
    public int depth() {
        return scopes.size() - 1;
    }

    /**
     * Makes one move; a failure is caught and kept in {@link #failure}, and the move is not kept.
     *
     * @param action the move
     * @return true when the move succeeded
     */
    public boolean apply(Action action) {
        if (failure != null) {
            replay();
            if (failure != null) {
                return false;
            }
        }
        try {
            perform(action);
            history.add(action);
            return true;
        } catch (Exception thrown) {
            failure = thrown;
            atBreakpoint = false;
            lastStep = "failed: " + thrown.getMessage();
            return false;
        }
    }

    /**
     * Steps back: after a failure, to just before the move that failed; otherwise undoes the last
     * move. Either way by replaying the run from its start: nodes change their inputs in place
     * (the contraction rewrites its network), so no evaluated value can be reused.
     *
     * @return false when there was nothing to step back over
     */
    public boolean back() {
        if (failure == null) {
            if (history.isEmpty()) {
                return false;
            }
            history.remove(history.size() - 1);
        }
        replay();
        lastStep = "back to move " + history.size()
                + (failure == null ? "" : ", where replay failed: " + failure.getMessage());
        return true;
    }

    /**
     * Moves on until a {@code breakpoint} statement has run or the program ends, entering a group
     * call only when its body holds a breakpoint.
     *
     * @return false when a move failed on the way
     */
    public boolean continueToBreakpoint() {
        do {
            GraphNodeContext statement = next();
            boolean enter = statement != null && statement.node instanceof NodeGroup
                    && containsBreakpoint(statement);
            if (!apply(new Action(enter ? Move.INTO : Move.OVER, 0))) {
                return false;
            }
        } while (!finished && !atBreakpoint);
        return true;
    }

    /**
     * Moves on in the finest steps there are, into every group call and every steppable node's
     * outermost steps, until a move fails or the program ends; a following step back lands just
     * before the move that failed.
     *
     * @return true when a move failed, false when the program ran to its end
     */
    public boolean runUntilFailure() {
        while (!finished) {
            Action action;
            if (inner != null) {
                action = new Action(Move.INNER, 0);
            } else {
                GraphNodeContext statement = next();
                boolean enter = statement != null && (statement.node instanceof NodeGroup
                        || statement.node instanceof SteppableNode);
                action = new Action(enter ? Move.INTO : Move.OVER, 0);
            }
            if (!apply(action)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a statement is a {@code breakpoint}.
     *
     * @param record the statement
     * @return true for a breakpoint
     */
    public static boolean isBreakpoint(GraphNodeContext record) {
        return PythonParser.BREAKPOINT.equals(record.type);
    }

    /**
     * Whether a group call's body, or any group call inside it, holds a breakpoint.
     *
     * @param record the statement
     * @return true when stepping the body would reach a breakpoint
     */
    public static boolean containsBreakpoint(GraphNodeContext record) {
        for (GraphNodeContext body : record.declared) {
            if (isBreakpoint(body) || containsBreakpoint(body)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Restarts the run with nothing evaluated and makes every kept move again; a move that fails
     * now is kept in {@link #failure} and it and the moves after it are dropped.
     */
    private void replay() {
        runtime.reset();
        runtime.setOverrides(overrides);
        scopes.clear();
        nextIndices.clear();
        scopes.add(runtime.root);
        nextIndices.add(0);
        inner = null;
        innerRecord = null;
        last = null;
        failure = null;
        finished = false;
        atBreakpoint = false;
        lastStep = "start";
        for (int move = 0; move < history.size(); move++) {
            try {
                perform(history.get(move));
            } catch (Exception thrown) {
                failure = thrown;
                history.subList(move, history.size()).clear();
                return;
            }
        }
    }

    /**
     * Makes one move, throwing what it throws.
     *
     * @param action the move
     * @throws Exception when the move fails
     */
    private void perform(Action action) throws Exception {
        atBreakpoint = false;
        switch (action.move()) {
            case OVER -> {
                if (inner != null) {
                    finishInner();
                } else if (next() == null) {
                    leaveScope();
                } else {
                    GraphNodeContext statement = next();
                    runtime.evaluateStatement(statement, false);
                    advancePast(statement);
                }
            }
            case INTO -> {
                GraphNodeContext statement = next();
                if (inner != null) {
                    throw new IllegalStateException("already stepping inside " + innerRecord.path);
                } else if (statement == null) {
                    leaveScope();
                } else {
                    if (runtime.evaluateStatement(statement, true)) {
                        inner = (SteppableNode) statement.node;
                        innerRecord = statement;
                        last = statement;
                        lastStep = "inside " + statement.path + " (" + statement.type + "), steps "
                                + inner.stepLevels();
                    } else {
                        advancePast(statement);
                        if (statement.node instanceof NodeGroup) {
                            scopes.add(statement);
                            nextIndices.add(0);
                            lastStep = "entered " + statement.path + " (" + statement.type + ")";
                        }
                    }
                }
            }
            case OUT -> {
                if (inner != null) {
                    finishInner();
                } else {
                    for (GraphNodeContext statement = next(); statement != null; statement = next()) {
                        runtime.evaluateStatement(statement, false);
                        advancePast(statement);
                    }
                    leaveScope();
                }
            }
            case INNER -> {
                if (inner == null) {
                    throw new IllegalStateException("not stepping inside a node");
                }
                String step = inner.step(innerRecord, action.level());
                if (step == null && action.level() == 0) {
                    finishInner();
                } else {
                    lastStep = step != null ? step
                            : "no " + inner.stepLevels().get(action.level()) + " step remains";
                }
            }
            default -> throw new IllegalArgumentException("unknown move " + action.move());
        }
    }

    /**
     * Records a statement as evaluated and moves the scope's cursor past it.
     *
     * @param statement the statement just evaluated or entered
     */
    private void advancePast(GraphNodeContext statement) {
        int top = scopes.size() - 1;
        nextIndices.set(top, nextIndices.get(top) + 1);
        last = statement;
        atBreakpoint = isBreakpoint(statement);
        lastStep = statement.path + " (" + statement.type + ")";
    }

    /**
     * Runs the rest of the node being stepped inside, publishing its outputs, and moves past it.
     *
     * @throws RuntimeException when a remaining step fails
     */
    private void finishInner() {
        GraphNodeContext finishedRecord = innerRecord;
        inner.finishSteps(finishedRecord);
        inner = null;
        innerRecord = null;
        advancePast(finishedRecord);
        lastStep = finishedRecord.path + " (" + finishedRecord.type + ") finished";
    }

    /**
     * Leaves the group call being stepped, computing its outputs, or at the top level ends the
     * run.
     *
     * @throws Exception when an output fails, or the run ends with deferred failures
     */
    private void leaveScope() throws Exception {
        int top = scopes.size() - 1;
        if (top == 0) {
            if (!finished) {
                finished = true;
                lastStep = "end of program";
                runtime.throwDeferredFailures();
            }
            return;
        }
        GraphNodeContext call = scopes.remove(top);
        nextIndices.remove(top);
        runtime.finishGroupCall(call);
        last = call;
        lastStep = "left " + call.path + " (" + call.type + ")";
    }
}
