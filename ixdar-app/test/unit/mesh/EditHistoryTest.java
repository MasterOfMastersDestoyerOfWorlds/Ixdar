package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import ixdar.scenes.ring.EditHistory;

/**
 * The ring tool's undo/redo stack: undo walks back one edit at a time, redo replays, and an edit
 * made after an undo drops the redo tail. States are integers, edit {@code n} taking state
 * {@code n - 1} to {@code n}.
 */
class EditHistoryTest {

    private static final String ADD = "anchor added";

    private static final String DRAG = "anchor dragged";

    private static final String CONFIRM = "draft confirmed";

    private static final String DELETE = "ring deleted";

    private static final int BRANCHED_STATE = 20;

    private static final int FOURTH_STATE = 4;

    /** Three edits applied in order, the states running 0 to 3. */
    private static EditHistory<Integer> threeEdits() {
        EditHistory<Integer> history = new EditHistory<>();
        history.push(ADD, 0, 1);
        history.push(DRAG, 1, 2);
        history.push(CONFIRM, 2, 3);
        return history;
    }

    /** Pushing counts undo depth up and leaves nothing to redo. */
    @Test
    void pushCountsUndoDepth() {
        EditHistory<Integer> history = threeEdits();
        assertEquals(3, history.undoDepth);
        assertEquals(0, history.redoDepth());
        assertEquals(CONFIRM, history.nextUndoName());
        assertEquals("", history.nextRedoName());
    }

    /** Undo hands back each edit's before-state, newest first, then runs dry. */
    @Test
    void undoWalksBackOneEditAtATime() {
        EditHistory<Integer> history = threeEdits();
        assertEquals(2, history.undo());
        assertEquals(1, history.undo());
        assertEquals(0, history.undo());
        assertNull(history.undo());
        assertEquals(0, history.undoDepth);
        assertEquals(3, history.redoDepth());
        assertEquals(ADD, history.nextRedoName());
    }

    /** Redo replays each after-state in order and runs dry at the newest edit. */
    @Test
    void redoReplaysUndoneEdits() {
        EditHistory<Integer> history = threeEdits();
        history.undo();
        history.undo();
        assertEquals(2, history.redo());
        assertEquals(3, history.redo());
        assertNull(history.redo());
        assertEquals(3, history.undoDepth);
    }

    /** An edit after an undo branches: the undone edits are gone and redo is empty. */
    @Test
    void pushAfterUndoDropsRedoTail() {
        EditHistory<Integer> history = threeEdits();
        history.undo();
        history.undo();
        history.push(DELETE, 1, BRANCHED_STATE);
        assertEquals(2, history.undoDepth);
        assertEquals(0, history.redoDepth());
        assertNull(history.redo());
        assertEquals(DELETE, history.nextUndoName());
        assertEquals(1, history.undo());
        assertEquals(0, history.undo());
        assertEquals(1, history.redo());
        assertEquals(BRANCHED_STATE, history.redo());
        assertNull(history.redo());
    }

    /** Past capacity the oldest edit is forgotten, the newest stay undoable. */
    @Test
    void capacityForgetsOldestEdit() {
        EditHistory<Integer> history = threeEdits();
        history.capacity = 2;
        history.push(DELETE, 3, FOURTH_STATE);
        assertEquals(2, history.undoDepth);
        assertEquals(3, history.undo());
        assertEquals(2, history.undo());
        assertNull(history.undo());
    }

    /** Clearing forgets every edit both ways. */
    @Test
    void clearForgetsEverything() {
        EditHistory<Integer> history = threeEdits();
        history.undo();
        history.clear();
        assertEquals(0, history.undoDepth);
        assertEquals(0, history.redoDepth());
        assertNull(history.undo());
        assertNull(history.redo());
    }
}
