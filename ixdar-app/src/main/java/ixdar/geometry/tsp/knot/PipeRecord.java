package ixdar.geometry.tsp.knot;

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
        if (addedSeg1 != null) {
            resultKnot.manifoldSegments.remove(addedSeg1);
            resultKnot.sortedSegments.remove(addedSeg1);
        }
        if (addedSeg2 != null) {
            resultKnot.manifoldSegments.remove(addedSeg2);
            resultKnot.sortedSegments.remove(addedSeg2);
        }

        if (childA != null && childB != null) {
            childA.matchList.remove(childB);
            childB.matchList.remove(childA);
            childA.matchCount = Math.max(0, childA.matchCount - 2);
            childB.matchCount = Math.max(0, childB.matchCount - 2);
        }

        resultKnot.knotPoints.clear();
        resultKnot.knotPointsFlattened.clear();
        resultKnot.manifoldSegments.clear();
    }

    private void undoAddToExisting() {
        resultKnot.knotPoints.remove(childB);
        resultKnot.knotPointsFlattened.remove(childB);

        if (addedSeg1 != null) {
            resultKnot.manifoldSegments.remove(addedSeg1);
            resultKnot.sortedSegments.remove(addedSeg1);
        }
        if (addedSeg2 != null) {
            resultKnot.manifoldSegments.remove(addedSeg2);
            resultKnot.sortedSegments.remove(addedSeg2);
        }

        if (cutEdgeA != null) {
            resultKnot.manifoldSegments.add(cutEdgeA);
            resultKnot.sortedSegments.add(cutEdgeA);
            resultKnot.sortedSegments.sort(null);

            cutEdgeA.first.setMatch(cutEdgeA.last, cutEdgeA);
            cutEdgeA.last.setMatch(cutEdgeA.first, cutEdgeA);
        }

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
        if (addedSeg1 != null) {
            resultKnot.manifoldSegments.remove(addedSeg1);
            resultKnot.sortedSegments.remove(addedSeg1);
        }
        if (addedSeg2 != null) {
            resultKnot.manifoldSegments.remove(addedSeg2);
            resultKnot.sortedSegments.remove(addedSeg2);
        }

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

        resultKnot.knotPoints.clear();
        resultKnot.knotPointsFlattened.clear();
        resultKnot.manifoldSegments.clear();
    }

    @Override
    public void redo() {
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
        resultKnot.knotPoints.add(childA);
        resultKnot.knotPoints.add(childB);
        resultKnot.knotPointsFlattened.add(childA);
        resultKnot.knotPointsFlattened.add(childB);

        if (addedSeg1 != null) {
            resultKnot.manifoldSegments.add(addedSeg1);
            resultKnot.sortedSegments.add(addedSeg1);
        }
        if (addedSeg2 != null) {
            resultKnot.manifoldSegments.add(addedSeg2);
            resultKnot.sortedSegments.add(addedSeg2);
        }
        resultKnot.sortedSegments.sort(null);

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
        if (cutEdgeA != null) {
            resultKnot.manifoldSegments.remove(cutEdgeA);
            resultKnot.sortedSegments.remove(cutEdgeA);
            cutEdgeA.first.matchList.remove(cutEdgeA.last);
            cutEdgeA.last.matchList.remove(cutEdgeA.first);
        }

        resultKnot.knotPoints.add(childB);
        resultKnot.knotPointsFlattened.add(childB);

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

        resultKnot.knotPoints.add(childA);
        resultKnot.knotPoints.add(childB);

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

    /**
     * The knot the pipe made or added to.
     *
     * @return the result knot
     */
    @Override
    public Knot route() {
        return resultKnot;
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
