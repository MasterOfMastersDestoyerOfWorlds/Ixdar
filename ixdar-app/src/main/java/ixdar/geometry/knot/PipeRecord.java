package ixdar.geometry.knot;

/**
 * Record of a Pipe operation for undo/redo support. Pipe connects two knots by
 * cutting edges and adding new segments.
 */
public class PipeRecord extends OperationRecord {

    /** The type of pipe operation. */
    public PipeType type;

    /** The knot that was created or modified by this pipe. */
    public Knot resultKnot;

    /** First operand knot. */
    public Knot childA;

    /** Second operand knot. */
    public Knot childB;

    /** Edge that was cut on knot A (null for singletons). */
    public Segment cutEdgeA;

    /** Edge that was cut on knot B (null for singletons). */
    public Segment cutEdgeB;

    /** First segment added by the pipe operation. */
    public Segment addedSeg1;

    /** Second segment added by the pipe operation. */
    public Segment addedSeg2;

    @Override
    public void undo() {
        switch (type) {
        case SINGLETON_TO_SINGLETON:
            undoSingletonPipe();
            break;
        case ADD_TO_EXISTING:
            undoAddToExisting();
            break;
        case NEW_HIERARCHY_LEVEL:
            undoNewHierarchyLevel();
            break;
        }
    }

    private void undoSingletonPipe() {
        // Remove the added segments
        if (addedSeg1 != null) {
            resultKnot.manifoldSegments.remove(addedSeg1);
            resultKnot.sortedSegments.remove(addedSeg1);
        }
        if (addedSeg2 != null) {
            resultKnot.manifoldSegments.remove(addedSeg2);
            resultKnot.sortedSegments.remove(addedSeg2);
        }

        // Remove match connections
        if (childA != null && childB != null) {
            childA.matchList.remove(childB);
            childB.matchList.remove(childA);
            childA.matchCount = Math.max(0, childA.matchCount - 2);
            childB.matchCount = Math.max(0, childB.matchCount - 2);
        }

        // Clear the result knot
        resultKnot.knotPoints.clear();
        resultKnot.knotPointsFlattened.clear();
        resultKnot.manifoldSegments.clear();
    }

    private void undoAddToExisting() {
        // Remove childB from the result knot
        resultKnot.knotPoints.remove(childB);
        resultKnot.knotPointsFlattened.remove(childB);

        // Remove added segments
        if (addedSeg1 != null) {
            resultKnot.manifoldSegments.remove(addedSeg1);
            resultKnot.sortedSegments.remove(addedSeg1);
        }
        if (addedSeg2 != null) {
            resultKnot.manifoldSegments.remove(addedSeg2);
            resultKnot.sortedSegments.remove(addedSeg2);
        }

        // Restore the cut edge
        if (cutEdgeA != null) {
            resultKnot.manifoldSegments.add(cutEdgeA);
            resultKnot.sortedSegments.add(cutEdgeA);
            resultKnot.sortedSegments.sort(null);

            // Restore match connections for the cut edge
            cutEdgeA.first.setMatch(cutEdgeA.last, cutEdgeA);
            cutEdgeA.last.setMatch(cutEdgeA.first, cutEdgeA);
        }

        // Remove match connections for added segments
        if (addedSeg1 != null) {
            addedSeg1.first.matchList.remove(addedSeg1.last);
            addedSeg1.last.matchList.remove(addedSeg1.first);
        }
        if (addedSeg2 != null) {
            addedSeg2.first.matchList.remove(addedSeg2.last);
            addedSeg2.last.matchList.remove(addedSeg2.first);
        }

        resultKnot.maxMatches--;
    }

    private void undoNewHierarchyLevel() {
        // Remove added segments
        if (addedSeg1 != null) {
            resultKnot.manifoldSegments.remove(addedSeg1);
            resultKnot.sortedSegments.remove(addedSeg1);
        }
        if (addedSeg2 != null) {
            resultKnot.manifoldSegments.remove(addedSeg2);
            resultKnot.sortedSegments.remove(addedSeg2);
        }

        // Restore cut edges on both children
        if (cutEdgeA != null && childA != null) {
            childA.manifoldSegments.add(cutEdgeA);
            childA.sortedSegments.add(cutEdgeA);
            childA.sortedSegments.sort(null);
            cutEdgeA.first.setMatch(cutEdgeA.last, cutEdgeA);
            cutEdgeA.last.setMatch(cutEdgeA.first, cutEdgeA);
        }
        if (cutEdgeB != null && childB != null) {
            childB.manifoldSegments.add(cutEdgeB);
            childB.sortedSegments.add(cutEdgeB);
            childB.sortedSegments.sort(null);
            cutEdgeB.first.setMatch(cutEdgeB.last, cutEdgeB);
            cutEdgeB.last.setMatch(cutEdgeB.first, cutEdgeB);
        }

        // Clear the result knot
        resultKnot.knotPoints.clear();
        resultKnot.knotPointsFlattened.clear();
        resultKnot.manifoldSegments.clear();
    }

    @Override
    public void redo() {
        // Re-execute the pipe operation based on stored data
        switch (type) {
        case SINGLETON_TO_SINGLETON:
            redoSingletonPipe();
            break;
        case ADD_TO_EXISTING:
            redoAddToExisting();
            break;
        case NEW_HIERARCHY_LEVEL:
            redoNewHierarchyLevel();
            break;
        }
    }

    private void redoSingletonPipe() {
        // Re-add the children
        resultKnot.knotPoints.add(childA);
        resultKnot.knotPoints.add(childB);
        resultKnot.knotPointsFlattened.add(childA);
        resultKnot.knotPointsFlattened.add(childB);

        // Re-add segments
        if (addedSeg1 != null) {
            resultKnot.manifoldSegments.add(addedSeg1);
            resultKnot.sortedSegments.add(addedSeg1);
        }
        if (addedSeg2 != null) {
            resultKnot.manifoldSegments.add(addedSeg2);
            resultKnot.sortedSegments.add(addedSeg2);
        }
        resultKnot.sortedSegments.sort(null);

        // Re-establish match connections
        if (addedSeg1 != null) {
            addedSeg1.first.setMatch(addedSeg1.last, addedSeg1);
            addedSeg1.last.setMatch(addedSeg1.first, addedSeg1);
        }
        if (addedSeg2 != null) {
            addedSeg2.first.setMatch(addedSeg2.last, addedSeg2);
            addedSeg2.last.setMatch(addedSeg2.first, addedSeg2);
        }
    }

    private void redoAddToExisting() {
        // Remove the cut edge
        if (cutEdgeA != null) {
            resultKnot.manifoldSegments.remove(cutEdgeA);
            resultKnot.sortedSegments.remove(cutEdgeA);
            cutEdgeA.first.matchList.remove(cutEdgeA.last);
            cutEdgeA.last.matchList.remove(cutEdgeA.first);
        }

        // Add childB back
        resultKnot.knotPoints.add(childB);
        resultKnot.knotPointsFlattened.add(childB);

        // Re-add segments
        if (addedSeg1 != null) {
            resultKnot.manifoldSegments.add(addedSeg1);
            resultKnot.sortedSegments.add(addedSeg1);
            addedSeg1.first.setMatch(addedSeg1.last, addedSeg1);
            addedSeg1.last.setMatch(addedSeg1.first, addedSeg1);
        }
        if (addedSeg2 != null) {
            resultKnot.manifoldSegments.add(addedSeg2);
            resultKnot.sortedSegments.add(addedSeg2);
            addedSeg2.first.setMatch(addedSeg2.last, addedSeg2);
            addedSeg2.last.setMatch(addedSeg2.first, addedSeg2);
        }
        resultKnot.sortedSegments.sort(null);
        resultKnot.maxMatches++;
    }

    private void redoNewHierarchyLevel() {
        // Remove cut edges from children
        if (cutEdgeA != null && childA != null) {
            childA.manifoldSegments.remove(cutEdgeA);
            childA.sortedSegments.remove(cutEdgeA);
            cutEdgeA.first.matchList.remove(cutEdgeA.last);
            cutEdgeA.last.matchList.remove(cutEdgeA.first);
        }
        if (cutEdgeB != null && childB != null) {
            childB.manifoldSegments.remove(cutEdgeB);
            childB.sortedSegments.remove(cutEdgeB);
            cutEdgeB.first.matchList.remove(cutEdgeB.last);
            cutEdgeB.last.matchList.remove(cutEdgeB.first);
        }

        // Re-add children to result
        resultKnot.knotPoints.add(childA);
        resultKnot.knotPoints.add(childB);

        // Re-add segments
        if (addedSeg1 != null) {
            resultKnot.manifoldSegments.add(addedSeg1);
            resultKnot.sortedSegments.add(addedSeg1);
            addedSeg1.first.setMatch(addedSeg1.last, addedSeg1);
            addedSeg1.last.setMatch(addedSeg1.first, addedSeg1);
        }
        if (addedSeg2 != null) {
            resultKnot.manifoldSegments.add(addedSeg2);
            resultKnot.sortedSegments.add(addedSeg2);
            addedSeg2.first.setMatch(addedSeg2.last, addedSeg2);
            addedSeg2.last.setMatch(addedSeg2.first, addedSeg2);
        }
        resultKnot.sortedSegments.sort(null);
    }

    @Override
    public String getDescription() {
        switch (type) {
        case SINGLETON_TO_SINGLETON:
            return "Pipe: Create loop from two points";
        case ADD_TO_EXISTING:
            return "Pipe: Add point to route";
        case NEW_HIERARCHY_LEVEL:
            return "Pipe: Connect two routes";
        default:
            return "Pipe operation";
        }
    }

    /**
     * Type of pipe operation performed.
     */
    public enum PipeType {
        /** Two singletons piped to create a new order-2 knot. */
        SINGLETON_TO_SINGLETON,
        /** Singleton added to existing knot (same hierarchy level). */
        ADD_TO_EXISTING,
        /** Two knots piped via pipe-edge, creating new hierarchy level. */
        NEW_HIERARCHY_LEVEL
    }
}
