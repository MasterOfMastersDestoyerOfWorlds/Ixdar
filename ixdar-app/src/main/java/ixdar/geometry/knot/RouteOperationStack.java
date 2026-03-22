package ixdar.geometry.knot;

import java.util.Stack;

/**
 * Manages undo/redo stack for route operations (Pipe, Grow, Collapse). Each
 * operation pushes a record that can be undone/redone.
 */
public class RouteOperationStack {

    /** Stack of operations that can be undone */
    private Stack<OperationRecord> undoStack;

    /** Stack of operations that can be redone */
    private Stack<OperationRecord> redoStack;

    /** Maximum size of undo history (0 = unlimited) */
    private int maxHistorySize;

    /**
     * Create a new operation stack with unlimited history.
     */
    public RouteOperationStack() {
        this(0);
    }

    /**
     * Create a new operation stack with specified history limit.
     * 
     * @param maxHistorySize maximum number of operations to keep (0 = unlimited)
     */
    public RouteOperationStack(int maxHistorySize) {
        this.undoStack = new Stack<>();
        this.redoStack = new Stack<>();
        this.maxHistorySize = maxHistorySize;
    }

    /**
     * Push an operation record onto the undo stack. Clears the redo stack since
     * we've branched history.
     * 
     * @param record the operation record to push
     */
    public void push(OperationRecord record) {
        if (record == null) {
            return;
        }

        undoStack.push(record);
        redoStack.clear(); // Clear redo on new operation

        // Trim history if needed
        if (maxHistorySize > 0 && undoStack.size() > maxHistorySize) {
            // Remove oldest entry (bottom of stack)
            Stack<OperationRecord> temp = new Stack<>();
            while (undoStack.size() > 1) {
                temp.push(undoStack.pop());
            }
            undoStack.pop(); // Remove the oldest
            while (!temp.isEmpty()) {
                undoStack.push(temp.pop());
            }
        }
    }

    /**
     * Undo the most recent operation.
     * 
     * @return the operation that was undone, or null if nothing to undo
     */
    public OperationRecord undo() {
        if (undoStack.isEmpty()) {
            return null;
        }

        OperationRecord record = undoStack.pop();
        record.undo();
        redoStack.push(record);

        return record;
    }

    /**
     * Redo the most recently undone operation.
     * 
     * @return the operation that was redone, or null if nothing to redo
     */
    public OperationRecord redo() {
        if (redoStack.isEmpty()) {
            return null;
        }

        OperationRecord record = redoStack.pop();
        record.redo();
        undoStack.push(record);

        return record;
    }

    /**
     * Check if there are operations that can be undone.
     * 
     * @return true if undo is available
     */
    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    /**
     * Check if there are operations that can be redone.
     * 
     * @return true if redo is available
     */
    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /**
     * Get the description of the next operation to undo.
     * 
     * @return description string, or null if nothing to undo
     */
    public String getUndoDescription() {
        if (undoStack.isEmpty()) {
            return null;
        }
        return undoStack.peek().getDescription();
    }

    /**
     * Get the description of the next operation to redo.
     * 
     * @return description string, or null if nothing to redo
     */
    public String getRedoDescription() {
        if (redoStack.isEmpty()) {
            return null;
        }
        return redoStack.peek().getDescription();
    }

    /**
     * Get the number of operations that can be undone.
     * 
     * @return undo stack size
     */
    public int getUndoCount() {
        return undoStack.size();
    }

    /**
     * Get the number of operations that can be redone.
     * 
     * @return redo stack size
     */
    public int getRedoCount() {
        return redoStack.size();
    }

    /**
     * Clear all history (both undo and redo).
     */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }

    /**
     * Peek at the most recent operation without removing it.
     * 
     * @return the most recent operation, or null if empty
     */
    public OperationRecord peek() {
        if (undoStack.isEmpty()) {
            return null;
        }
        return undoStack.peek();
    }
}
