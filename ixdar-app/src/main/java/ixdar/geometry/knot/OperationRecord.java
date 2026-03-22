package ixdar.geometry.knot;

/**
 * Base class for all knot operation records. Used for undo/redo functionality
 * in route planning.
 */
public abstract class OperationRecord {

    /**
     * Undo this operation, reverting the knot to its previous state.
     */
    public abstract void undo();

    /**
     * Redo this operation, reapplying the changes.
     */
    public abstract void redo();

    /**
     * Get a human-readable description of this operation.
     * 
     * @return description string for UI display
     */
    public abstract String getDescription();
}
