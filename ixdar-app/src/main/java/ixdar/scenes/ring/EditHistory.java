package ixdar.scenes.ring;

import java.util.ArrayList;
import java.util.List;

/**
 * An undo/redo stack of whole-state snapshots: each edit is the state before it and the state
 * after it, so undoing restores the one and redoing the other. A push after an undo drops the
 * redo tail.
 *
 * @param <S> the snapshot type, which the stack never looks inside
 */
public final class EditHistory<S> {

    public static final int DEFAULT_CAPACITY = 256;

    /** Name of each edit, oldest first, parallel to {@link #statesBefore}. */
    public final List<String> editNames = new ArrayList<>();

    /** State each edit started from, what undoing it restores. */
    public final List<S> statesBefore = new ArrayList<>();

    /** State each edit left, what redoing it restores. */
    public final List<S> statesAfter = new ArrayList<>();

    /** Edits currently applied: the first this many are undoable, the rest redoable. */
    public int undoDepth;

    /** Most edits kept; pushing past it forgets the oldest. */
    public int capacity = DEFAULT_CAPACITY;

    /**
     * Record one edit on top of the applied ones, dropping every edit an undo had stepped back
     * over.
     *
     * @param name   what the edit did, as the status row reports it
     * @param before state the edit started from
     * @param after  state the edit left
     */
    public void push(String name, S before, S after) {
        for (int edit = editNames.size() - 1; edit >= undoDepth; edit--) {
            editNames.remove(edit);
            statesBefore.remove(edit);
            statesAfter.remove(edit);
        }
        editNames.add(name);
        statesBefore.add(before);
        statesAfter.add(after);
        undoDepth++;
        while (undoDepth > Math.max(1, capacity)) {
            editNames.remove(0);
            statesBefore.remove(0);
            statesAfter.remove(0);
            undoDepth--;
        }
    }

    /**
     * Step back over the last applied edit.
     *
     * @return the state to restore, or null when nothing is left to undo
     */
    public S undo() {
        if (undoDepth == 0) {
            return null;
        }
        undoDepth--;
        return statesBefore.get(undoDepth);
    }

    /**
     * Re-apply the edit the last undo stepped back over.
     *
     * @return the state to restore, or null when nothing is left to redo
     */
    public S redo() {
        if (redoDepth() == 0) {
            return null;
        }
        S after = statesAfter.get(undoDepth);
        undoDepth++;
        return after;
    }

    /**
     * Edits an undo has stepped back over that a redo can re-apply.
     *
     * @return the redo depth, zero after any push
     */
    public int redoDepth() {
        return editNames.size() - undoDepth;
    }

    /**
     * Name of the edit the next undo steps back over.
     *
     * @return its name, or empty when nothing is left to undo
     */
    public String nextUndoName() {
        return undoDepth == 0 ? "" : editNames.get(undoDepth - 1);
    }

    /**
     * Name of the edit the next redo re-applies.
     *
     * @return its name, or empty when nothing is left to redo
     */
    public String nextRedoName() {
        return redoDepth() == 0 ? "" : editNames.get(undoDepth);
    }

    /** Forget every edit, for a change the snapshots no longer describe. */
    public void clear() {
        editNames.clear();
        statesBefore.clear();
        statesAfter.clear();
        undoDepth = 0;
    }
}
