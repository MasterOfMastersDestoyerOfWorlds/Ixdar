package ixdar.geometry.knot;

import java.util.ArrayList;
import java.util.List;

/**
 * Record of a Collapse operation for undo/redo support. Collapse converts
 * double-pipes to single-pipes with internal crossings.
 * 
 * Before collapse: Each pipe has 2 parallel segments After collapse: Each pipe
 * has 1 segment, plus internal crossing segments
 */
public class CollapseRecord extends OperationRecord {

    /** The knots in the path from start to end */
    public List<Knot> pathKnots = new ArrayList<>();

    /** Segments removed from pipes (one per pipe) */
    public List<Segment> removedPipeSegments = new ArrayList<>();

    /** Internal crossing segments added to each knot */
    public List<Segment> addedCrossings = new ArrayList<>();

    /** The direct connection segment added between start and end knots */
    public Segment addedDirect;

    /** Start knot of the collapse */
    public Knot startKnot;

    /** End knot of the collapse */
    public Knot endKnot;

    @Override
    public void undo() {
        // Remove the direct connection
        if (addedDirect != null && startKnot != null) {
            // Find the parent knot containing these
            Knot parent = findCommonParent(startKnot, endKnot);
            if (parent != null) {
                parent.manifoldSegments.remove(addedDirect);
                parent.sortedSegments.remove(addedDirect);
            }
            // Remove match connections
            addedDirect.first.matchList.remove(addedDirect.last);
            addedDirect.last.matchList.remove(addedDirect.first);
            if (addedDirect.first.m1 == addedDirect.last) {
                addedDirect.first.m1 = null;
                addedDirect.first.s1 = null;
            } else if (addedDirect.first.m2 == addedDirect.last) {
                addedDirect.first.m2 = null;
                addedDirect.first.s2 = null;
            }
            addedDirect.first.matchCount = Math.max(0, addedDirect.first.matchCount - 1);
            addedDirect.last.matchCount = Math.max(0, addedDirect.last.matchCount - 1);
        }

        // Remove all internal crossings
        for (int i = 0; i < addedCrossings.size() && i < pathKnots.size(); i++) {
            Segment crossing = addedCrossings.get(i);
            Knot knot = pathKnots.get(i);

            knot.manifoldSegments.remove(crossing);
            knot.sortedSegments.remove(crossing);

            // Remove match connections
            crossing.first.matchList.remove(crossing.last);
            crossing.last.matchList.remove(crossing.first);
            if (crossing.first.m1 == crossing.last) {
                crossing.first.m1 = null;
                crossing.first.s1 = null;
            } else if (crossing.first.m2 == crossing.last) {
                crossing.first.m2 = null;
                crossing.first.s2 = null;
            }
            crossing.first.matchCount = Math.max(0, crossing.first.matchCount - 1);
            crossing.last.matchCount = Math.max(0, crossing.last.matchCount - 1);
        }

        // Restore all removed pipe segments
        for (Segment pipeSeg : removedPipeSegments) {
            // Find which knot's manifold this belongs to
            Knot parent = findCommonParent(pipeSeg.first, pipeSeg.last);
            if (parent != null) {
                parent.manifoldSegments.add(pipeSeg);
                parent.sortedSegments.add(pipeSeg);
                parent.sortedSegments.sort(null);
            }

            // Restore match connections
            pipeSeg.first.setMatch(pipeSeg.last, pipeSeg);
            pipeSeg.last.setMatch(pipeSeg.first, pipeSeg);
        }
    }

    @Override
    public void redo() {
        // Remove pipe segments again
        for (Segment pipeSeg : removedPipeSegments) {
            Knot parent = findCommonParent(pipeSeg.first, pipeSeg.last);
            if (parent != null) {
                parent.manifoldSegments.remove(pipeSeg);
                parent.sortedSegments.remove(pipeSeg);
            }

            // Remove match connections
            pipeSeg.first.matchList.remove(pipeSeg.last);
            pipeSeg.last.matchList.remove(pipeSeg.first);
            if (pipeSeg.first.m1 == pipeSeg.last) {
                pipeSeg.first.m1 = null;
                pipeSeg.first.s1 = null;
            } else if (pipeSeg.first.m2 == pipeSeg.last) {
                pipeSeg.first.m2 = null;
                pipeSeg.first.s2 = null;
            }
            pipeSeg.first.matchCount = Math.max(0, pipeSeg.first.matchCount - 1);
            pipeSeg.last.matchCount = Math.max(0, pipeSeg.last.matchCount - 1);
        }

        // Re-add internal crossings
        for (int i = 0; i < addedCrossings.size() && i < pathKnots.size(); i++) {
            Segment crossing = addedCrossings.get(i);
            Knot knot = pathKnots.get(i);

            knot.manifoldSegments.add(crossing);
            knot.sortedSegments.add(crossing);
            knot.sortedSegments.sort(null);

            // Add match connections
            crossing.first.setMatch(crossing.last, crossing);
            crossing.last.setMatch(crossing.first, crossing);
        }

        // Re-add direct connection
        if (addedDirect != null && startKnot != null) {
            Knot parent = findCommonParent(startKnot, endKnot);
            if (parent != null) {
                parent.manifoldSegments.add(addedDirect);
                parent.sortedSegments.add(addedDirect);
                parent.sortedSegments.sort(null);
            }

            addedDirect.first.setMatch(addedDirect.last, addedDirect);
            addedDirect.last.setMatch(addedDirect.first, addedDirect);
        }
    }

    /**
     * Find the common parent knot containing both given knots.
     * 
     * @param a first knot
     * @param b second knot
     * @return the common parent, or null if not found
     */
    private Knot findCommonParent(Knot a, Knot b) {
        if (a == null || b == null) {
            return null;
        }

        // Check if they share a top group knot
        if (a.topGroupKnot != null && a.topGroupKnot == b.topGroupKnot) {
            return a.topGroupKnot;
        }

        // Check if one contains the other
        if (a.contains(b)) {
            return a;
        }
        if (b.contains(a)) {
            return b;
        }

        // Walk up a's hierarchy looking for b
        Knot current = a.topGroupKnot;
        while (current != null) {
            if (current.contains(b)) {
                return current;
            }
            current = current.topGroupKnot;
        }

        return null;
    }

    @Override
    public String getDescription() {
        int numKnots = pathKnots.size();
        if (numKnots > 0) {
            return "Collapse: Convert " + (numKnots - 1) + " double-pipes to single";
        }
        return "Collapse: Convert double-pipes to single";
    }
}
