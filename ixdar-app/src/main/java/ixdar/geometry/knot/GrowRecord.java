package ixdar.geometry.knot;

/**
 * Record of a Grow operation for undo/redo support. Grow inserts a singleton
 * into an edge of an existing knot.
 */
public class GrowRecord extends OperationRecord {

    /** The knot that was grown. */
    public Knot modifiedKnot;

    /** The singleton point that was inserted. */
    public Knot insertedPoint;

    /** The original edge that was split. */
    public Segment originalEdge;

    /** First new edge (from original edge's first point to inserted point). */
    public Segment newEdge1;

    /** Second new edge (from inserted point to original edge's last point). */
    public Segment newEdge2;

    /** Index where the point was inserted in knotPointsFlattened. */
    public int insertionIndex;

    @Override
    public void undo() {
        if (modifiedKnot == null || insertedPoint == null) {
            return;
        }

        // Remove the new edges
        if (newEdge1 != null) {
            modifiedKnot.manifoldSegments.remove(newEdge1);
            modifiedKnot.sortedSegments.remove(newEdge1);
            // Remove match connections
            newEdge1.first.matchList.remove(newEdge1.last);
            newEdge1.last.matchList.remove(newEdge1.first);
            if (newEdge1.first.m1 == newEdge1.last) {
                newEdge1.first.m1 = null;
                newEdge1.first.s1 = null;
            } else if (newEdge1.first.m2 == newEdge1.last) {
                newEdge1.first.m2 = null;
                newEdge1.first.s2 = null;
            }
            newEdge1.first.matchCount = Math.max(0, newEdge1.first.matchCount - 1);
            newEdge1.last.matchCount = Math.max(0, newEdge1.last.matchCount - 1);
        }
        if (newEdge2 != null) {
            modifiedKnot.manifoldSegments.remove(newEdge2);
            modifiedKnot.sortedSegments.remove(newEdge2);
            // Remove match connections
            newEdge2.first.matchList.remove(newEdge2.last);
            newEdge2.last.matchList.remove(newEdge2.first);
            if (newEdge2.first.m1 == newEdge2.last) {
                newEdge2.first.m1 = null;
                newEdge2.first.s1 = null;
            } else if (newEdge2.first.m2 == newEdge2.last) {
                newEdge2.first.m2 = null;
                newEdge2.first.s2 = null;
            }
            newEdge2.first.matchCount = Math.max(0, newEdge2.first.matchCount - 1);
            newEdge2.last.matchCount = Math.max(0, newEdge2.last.matchCount - 1);
        }

        // Restore the original edge
        if (originalEdge != null) {
            modifiedKnot.manifoldSegments.add(originalEdge);
            modifiedKnot.sortedSegments.add(originalEdge);
            modifiedKnot.sortedSegments.sort(null);
            // Restore match connections
            originalEdge.first.setMatch(originalEdge.last, originalEdge);
            originalEdge.last.setMatch(originalEdge.first, originalEdge);
        }

        // Remove the inserted point from the knot
        modifiedKnot.knotPoints.remove(insertedPoint);
        modifiedKnot.knotPointsFlattened.remove(insertedPoint);

        modifiedKnot.maxMatches--;
    }

    @Override
    public void redo() {
        if (modifiedKnot == null || insertedPoint == null || originalEdge == null) {
            return;
        }

        // Remove the original edge
        modifiedKnot.manifoldSegments.remove(originalEdge);
        modifiedKnot.sortedSegments.remove(originalEdge);
        originalEdge.first.matchList.remove(originalEdge.last);
        originalEdge.last.matchList.remove(originalEdge.first);
        if (originalEdge.first.m1 == originalEdge.last) {
            originalEdge.first.m1 = null;
            originalEdge.first.s1 = null;
        } else if (originalEdge.first.m2 == originalEdge.last) {
            originalEdge.first.m2 = null;
            originalEdge.first.s2 = null;
        }
        originalEdge.first.matchCount = Math.max(0, originalEdge.first.matchCount - 1);
        originalEdge.last.matchCount = Math.max(0, originalEdge.last.matchCount - 1);

        // Add the new edges
        if (newEdge1 != null) {
            modifiedKnot.manifoldSegments.add(newEdge1);
            modifiedKnot.sortedSegments.add(newEdge1);
            newEdge1.first.setMatch(newEdge1.last, newEdge1);
            newEdge1.last.setMatch(newEdge1.first, newEdge1);
        }
        if (newEdge2 != null) {
            modifiedKnot.manifoldSegments.add(newEdge2);
            modifiedKnot.sortedSegments.add(newEdge2);
            newEdge2.first.setMatch(newEdge2.last, newEdge2);
            newEdge2.last.setMatch(newEdge2.first, newEdge2);
        }
        modifiedKnot.sortedSegments.sort(null);

        // Add the inserted point back
        modifiedKnot.knotPoints.add(insertedPoint);
        if (insertionIndex >= 0 && insertionIndex <= modifiedKnot.knotPointsFlattened.size()) {
            modifiedKnot.knotPointsFlattened.add(insertionIndex, insertedPoint);
        } else {
            modifiedKnot.knotPointsFlattened.add(insertedPoint);
        }

        modifiedKnot.maxMatches++;
    }

    @Override
    public String getDescription() {
        if (insertedPoint != null) {
            return "Grow: Insert point " + insertedPoint.id + " into route";
        }
        return "Grow: Insert point into route";
    }
}
