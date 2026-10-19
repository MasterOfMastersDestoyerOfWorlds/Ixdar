package ixdar.geometry.tsp.knot;

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
     * The route knot this operation built or edited, the route while it is the last one applied.
     *
     * @return the knot holding the operation's segments
     */
    public abstract Knot route();

    /**
     * Get a human-readable description of this operation.
     *
     * @return description string for UI display
     */
    public abstract String getDescription();
}
