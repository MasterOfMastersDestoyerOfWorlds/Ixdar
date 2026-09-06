package ixdar.geometry.mesh.data.paths;

import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * FlipOut curve shortening (Sharp &amp; Crane 2020): straightens an edge path or loop on an
 * {@link IntrinsicTriangulation} into a locally shortest geodesic by flipping edges only.
 *
 * <p>
 * Edges inside a wedge narrower than {@code pi} are flipped away and the path re-routes along the
 * far side, smallest wedge first.
 */
public final class FlipGeodesics {

    /** Run until every wedge is straight rather than stopping after a fixed count. */
    public static final int UNBOUNDED_ITERATIONS = -1;

    /** A wedge this close to {@code pi} counts as straight and is left alone. */
    public static final double STRAIGHT_ANGLE_EPSILON = 1e-9;

    /** Wedge classification: the path already runs straight through the vertex. */
    public static final int TURN_STRAIGHT = 0;

    /** Wedge classification: the shorter wedge lies on the path's left. */
    public static final int TURN_LEFT = 1;

    /** Wedge classification: the shorter wedge lies on the path's right. */
    public static final int TURN_RIGHT = 2;

    /** Triangulation the current run mutates by flipping. */
    public IntrinsicTriangulation triangulation;

    /** Whether the path being shortened is a closed loop. */
    public boolean closed;

    /** Intrinsic half-edge each path segment runs along, indexed by segment id. */
    public int[] segmentHalfEdge = new int[0];

    /** Preceding segment id, or -1 at the start of an open path. */
    public int[] segmentPrevious = new int[0];

    /** Following segment id, or -1 at the end of an open path. */
    public int[] segmentNext = new int[0];

    /** Whether a segment id still names a live piece of the path. */
    public boolean[] segmentAlive = new boolean[0];

    /** Number of segment ids handed out so far in this run. */
    public int segmentIdCount;

    /** Edge flips performed by the last run. */
    public long flipCount;

    /** Wedges straightened by the last run. */
    public long shortenCount;

    private int[] edgeOccupancy = new int[0];
    private int[] edgeSegmentFront = new int[0];
    private int[] edgeSegmentBack = new int[0];
    private int[] newPathBuffer = new int[0];
    private final double[] sideAngles = new double[2];
    private PriorityQueue<double[]> wedgeQueue;

    /**
     * Shortens a seed path or loop in place on {@code intrinsic}, returning the tightened path.
     *
     * @param intrinsic     triangulation to flip; its edge lengths and signposts are updated
     * @param seedHalfEdges seed path as consecutive intrinsic half-edges in travel order
     * @param isClosed      true when the seed's last half-edge returns to the first one's tail
     * @param maxIterations wedge straightenings to allow, or {@link #UNBOUNDED_ITERATIONS}
     * @throws IllegalArgumentException when the seed is empty or does not form a chain
     * @return the tightened path as intrinsic half-edges in travel order
     */
    public int[] shorten(IntrinsicTriangulation intrinsic, int[] seedHalfEdges, boolean isClosed,
            int maxIterations) {
        triangulation = intrinsic;
        closed = isClosed;
        flipCount = 0;
        shortenCount = 0;
        prepareBuffers(seedHalfEdges);
        seedPath(seedHalfEdges);
        for (int segment = 0; segment < segmentIdCount; segment++) {
            enqueueWedge(segment);
        }
        int iterations = 0;
        while (true) {
            long shortenedBefore = shortenCount;
            iterations = drainWedgeQueue(maxIterations, iterations);
            if (maxIterations >= 0 && iterations >= maxIterations) {
                break;
            }
            if (shortenCount == shortenedBefore) {
                break;
            }
            requeueLiveWedges();
            if (wedgeQueue.isEmpty()) {
                break;
            }
        }
        return collect();
    }

    /**
     * Smaller of the two wedge angles at the vertex a segment starts from.
     *
     * @param segment segment id whose incoming corner is measured
     * @return the smaller wedge angle in radians, or infinity at an open path's first segment
     */
    public double smallerWedgeAngle(int segment) {
        int previous = segmentPrevious[segment];
        if (previous < 0) {
            return Double.POSITIVE_INFINITY;
        }
        triangulation.measureSideAngles(segmentHalfEdge[previous], segmentHalfEdge[segment],
                sideAngles);
        return Math.min(sideAngles[0], sideAngles[1]);
    }

    /**
     * Smallest wedge angle anywhere along the current path, the run's straightness measure.
     *
     * @return the minimum wedge angle in radians, or infinity when no interior wedge exists
     */
    public double minimumWedgeAngle() {
        double smallest = Double.POSITIVE_INFINITY;
        for (int segment = 0; segment < segmentIdCount; segment++) {
            if (segmentAlive[segment]) {
                smallest = Math.min(smallest, smallerWedgeAngle(segment));
            }
        }
        return smallest;
    }

    /**
     * Total intrinsic length of the current path.
     *
     * @return summed intrinsic edge length over the live segments
     */
    public double pathLength() {
        double total = 0.0;
        for (int segment = 0; segment < segmentIdCount; segment++) {
            if (segmentAlive[segment]) {
                total += triangulation.edgeLength[segmentHalfEdge[segment] >> 1];
            }
        }
        return total;
    }

    /**
     * Straightens wedges smallest-angle-first until the queue runs dry, returning the running
     * iteration count so the caller can enforce {@code maxIterations} across several drains.
     */
    private int drainWedgeQueue(int maxIterations, int startIterations) {
        int iterations = startIterations;
        while (!wedgeQueue.isEmpty() && (maxIterations < 0 || iterations < maxIterations)) {
            double[] entry = wedgeQueue.poll();
            int segment = (int) entry[2];
            if (!segmentAlive[segment] || segmentPrevious[segment] < 0) {
                continue;
            }
            if (smallerWedgeAngle(segment) != entry[0]) {
                continue;
            }
            int turn = (int) entry[1];
            if (!wedgeIsClear(segment, turn)) {
                continue;
            }
            locallyShortenAt(segment, turn);
            iterations++;
        }
        return iterations;
    }

    /**
     * Re-measures every live wedge into a fresh queue, so a wedge whose queued angle went stale
     * while a neighbouring wedge was straightened gets another look.
     */
    private void requeueLiveWedges() {
        wedgeQueue.clear();
        for (int segment = 0; segment < segmentIdCount; segment++) {
            enqueueWedge(segment);
        }
    }

    private void prepareBuffers(int[] seedHalfEdges) {
        int capacity = Math.max(seedHalfEdges.length * 4, 64);
        segmentHalfEdge = new int[capacity];
        segmentPrevious = new int[capacity];
        segmentNext = new int[capacity];
        segmentAlive = new boolean[capacity];
        segmentIdCount = 0;
        if (edgeOccupancy.length < triangulation.edgeCount) {
            edgeOccupancy = new int[triangulation.edgeCount];
            edgeSegmentFront = new int[triangulation.edgeCount];
            edgeSegmentBack = new int[triangulation.edgeCount];
        } else {
            Arrays.fill(edgeOccupancy, 0, triangulation.edgeCount, 0);
        }
        if (newPathBuffer.length < 64) {
            newPathBuffer = new int[64];
        }
        wedgeQueue = new PriorityQueue<>(Comparator
                .<double[]>comparingDouble(entry -> entry[0])
                .thenComparingDouble(entry -> entry[1])
                .thenComparingDouble(entry -> entry[2]));
    }

    private void seedPath(int[] seedHalfEdges) {
        if (seedHalfEdges.length == 0) {
            throw new IllegalArgumentException("cannot shorten an empty path");
        }
        int previous = -1;
        for (int index = 0; index < seedHalfEdges.length; index++) {
            int halfEdge = seedHalfEdges[index];
            if (index > 0 && triangulation.halfEdgeTail[halfEdge]
                    != triangulation.halfEdgeHead(seedHalfEdges[index - 1])) {
                throw new IllegalArgumentException("seed half-edges do not form a chain at index "
                        + index);
            }
            int segment = allocateSegment(halfEdge);
            segmentPrevious[segment] = previous;
            segmentNext[segment] = -1;
            if (previous >= 0) {
                segmentNext[previous] = segment;
            }
            pushOutsideSegment(halfEdge, segment);
            previous = segment;
        }
        if (!closed) {
            return;
        }
        int last = previous;
        if (triangulation.halfEdgeHead(seedHalfEdges[seedHalfEdges.length - 1])
                != triangulation.halfEdgeTail[seedHalfEdges[0]]) {
            throw new IllegalArgumentException("closed seed does not return to its first vertex");
        }
        segmentPrevious[0] = last;
        segmentNext[last] = 0;
    }

    private int allocateSegment(int halfEdge) {
        if (segmentIdCount == segmentHalfEdge.length) {
            int grown = segmentHalfEdge.length * 2;
            segmentHalfEdge = Arrays.copyOf(segmentHalfEdge, grown);
            segmentPrevious = Arrays.copyOf(segmentPrevious, grown);
            segmentNext = Arrays.copyOf(segmentNext, grown);
            segmentAlive = Arrays.copyOf(segmentAlive, grown);
        }
        int segment = segmentIdCount++;
        segmentHalfEdge[segment] = halfEdge;
        segmentAlive[segment] = true;
        return segment;
    }

    private void enqueueWedge(int segment) {
        if (segment < 0 || !segmentAlive[segment] || segmentPrevious[segment] < 0) {
            return;
        }
        triangulation.measureSideAngles(segmentHalfEdge[segmentPrevious[segment]],
                segmentHalfEdge[segment], sideAngles);
        double left = sideAngles[0];
        double right = sideAngles[1];
        double smallerAngle = Math.min(left, right);
        double largerAngle = Math.max(left, right);
        int smallerTurn = left <= right ? TURN_LEFT : TURN_RIGHT;
        int largerTurn = left <= right ? TURN_RIGHT : TURN_LEFT;
        if (smallerAngle > Math.PI - STRAIGHT_ANGLE_EPSILON) {
            return;
        }
        wedgeQueue.add(new double[] { smallerAngle, smallerTurn, segment });
        if (largerAngle > Math.PI - STRAIGHT_ANGLE_EPSILON) {
            return;
        }
        wedgeQueue.add(new double[] { largerAngle, largerTurn, segment });
    }

    private boolean wedgeIsClear(int segment, int turn) {
        int previousSegment = segmentPrevious[segment];
        int outgoingHalfEdge = segmentHalfEdge[segment];
        int incomingHalfEdge = segmentHalfEdge[previousSegment];
        int boundingIn = turn == TURN_LEFT ? incomingHalfEdge : incomingHalfEdge ^ 1;
        int boundingOut = turn == TURN_LEFT ? outgoingHalfEdge : outgoingHalfEdge ^ 1;
        if (outsideSegment(boundingIn) != previousSegment
                || outsideSegment(boundingOut) != segment) {
            return false;
        }
        int current = turn == TURN_LEFT
                ? triangulation.halfEdgeNext[incomingHalfEdge]
                : triangulation.counterClockwiseNeighbor(incomingHalfEdge ^ 1);
        int guard = 0;
        while (current != outgoingHalfEdge) {
            if (current < 0 || !triangulation.isInterior(current)
                    || guard++ > triangulation.halfEdgeCount) {
                return false;
            }
            if (edgeOccupancy[current >> 1] > 0) {
                return false;
            }
            current = turn == TURN_LEFT
                    ? triangulation.clockwiseNeighbor(current)
                    : triangulation.counterClockwiseNeighbor(current);
        }
        return true;
    }

    private void locallyShortenAt(int segment, int turn) {
        int previousSegment = segmentPrevious[segment];
        int outgoingHalfEdge = segmentHalfEdge[segment];
        int incomingHalfEdge = segmentHalfEdge[previousSegment];
        if (previousSegment == segment) {
            shortenCount++;
            shortenSingleEdgeLoop(segment, turn);
            return;
        }
        double initialLength = triangulation.edgeLength[incomingHalfEdge >> 1]
                + triangulation.edgeLength[outgoingHalfEdge >> 1];

        boolean reversed = turn == TURN_RIGHT;
        int wedgeStart = reversed ? outgoingHalfEdge ^ 1 : incomingHalfEdge;
        int wedgeEnd = reversed ? incomingHalfEdge ^ 1 : outgoingHalfEdge;
        int startTwin = wedgeStart ^ 1;

        int current = triangulation.halfEdgeNext[wedgeStart];
        int guard = 0;
        while (current != wedgeEnd) {
            if (current < 0 || !triangulation.isInterior(current)
                    || guard++ > triangulation.halfEdgeCount) {
                return;
            }
            if (current == startTwin) {
                current = triangulation.clockwiseNeighbor(current);
                continue;
            }
            if (triangulation.flipIfPossible(current >> 1)) {
                flipCount++;
                current = triangulation.halfEdgeNext[current ^ 1] ^ 1;
            } else {
                current = triangulation.clockwiseNeighbor(current);
            }
        }

        int replacementCount = 0;
        double replacementLength = 0.0;
        current = triangulation.halfEdgeNext[wedgeStart];
        while (true) {
            int far = triangulation.halfEdgeNext[current];
            if (replacementCount == newPathBuffer.length) {
                newPathBuffer = Arrays.copyOf(newPathBuffer, newPathBuffer.length * 2);
            }
            newPathBuffer[replacementCount++] = far ^ 1;
            replacementLength += triangulation.edgeLength[far >> 1];
            if (current == wedgeEnd) {
                break;
            }
            current = triangulation.clockwiseNeighbor(current);
        }
        if (replacementLength >= initialLength) {
            return;
        }
        shortenCount++;
        if (reversed) {
            for (int low = 0, high = replacementCount - 1; low < high; low++, high--) {
                int swap = newPathBuffer[low];
                newPathBuffer[low] = newPathBuffer[high];
                newPathBuffer[high] = swap;
            }
            for (int index = 0; index < replacementCount; index++) {
                newPathBuffer[index] ^= 1;
            }
        }
        replacePathSegment(segment, turn, replacementCount);
    }

    private void replacePathSegment(int segment, int turn, int replacementCount) {
        int outgoingHalfEdge = segmentHalfEdge[segment];
        int previousSegment = segmentPrevious[segment];
        int followingSegment = segmentNext[segment];
        int incomingHalfEdge = segmentHalfEdge[previousSegment];
        int precedingSegment = segmentPrevious[previousSegment];

        popOutsideSegment(turn == TURN_LEFT ? incomingHalfEdge : incomingHalfEdge ^ 1);
        popOutsideSegment(turn == TURN_LEFT ? outgoingHalfEdge : outgoingHalfEdge ^ 1);
        segmentAlive[previousSegment] = false;
        segmentAlive[segment] = false;

        boolean replacedTwoSegmentLoop = precedingSegment == segment;
        if (replacedTwoSegmentLoop) {
            precedingSegment = -1;
            followingSegment = -1;
        }

        int chainPrevious = precedingSegment;
        int firstAdded = -1;
        for (int index = 0; index < replacementCount; index++) {
            int halfEdge = newPathBuffer[index];
            int added = allocateSegment(halfEdge);
            segmentPrevious[added] = chainPrevious;
            segmentNext[added] = -1;
            pushOutsideSegment(turn == TURN_LEFT ? halfEdge ^ 1 : halfEdge, added);
            if (chainPrevious >= 0) {
                segmentNext[chainPrevious] = added;
            }
            enqueueWedge(added);
            if (firstAdded < 0) {
                firstAdded = added;
            }
            chainPrevious = added;
        }
        if (chainPrevious >= 0) {
            segmentNext[chainPrevious] = followingSegment;
        }
        if (followingSegment >= 0) {
            segmentPrevious[followingSegment] = chainPrevious;
            enqueueWedge(followingSegment);
        }
        if (replacedTwoSegmentLoop && firstAdded >= 0) {
            segmentPrevious[firstAdded] = chainPrevious;
            segmentNext[chainPrevious] = firstAdded;
            enqueueWedge(firstAdded);
        }
        enqueueWedge(outsideSegment(turn == TURN_LEFT ? outgoingHalfEdge : outgoingHalfEdge ^ 1));
        enqueueWedge(outsideSegment(turn == TURN_LEFT ? incomingHalfEdge : incomingHalfEdge ^ 1));
    }

    /**
     * Replaces a loop that has shrunk to a single self-edge with the other two sides of the
     * triangle it bounds; the wedge orbit cannot express that case because the loop's one segment
     * is both the incoming and the outgoing half-edge.
     */
    private void shortenSingleEdgeLoop(int segment, int turn) {
        int halfEdge = segmentHalfEdge[segment];
        int first;
        int second;
        if (turn == TURN_LEFT) {
            first = triangulation.halfEdgeNext[triangulation.halfEdgeNext[halfEdge]] ^ 1;
            second = triangulation.halfEdgeNext[halfEdge] ^ 1;
            popOutsideSegment(halfEdge);
        } else {
            first = triangulation.halfEdgeNext[halfEdge ^ 1];
            second = triangulation.halfEdgeNext[first];
            popOutsideSegment(halfEdge ^ 1);
        }
        segmentAlive[segment] = false;
        int firstSegment = allocateSegment(first);
        int secondSegment = allocateSegment(second);
        segmentPrevious[firstSegment] = secondSegment;
        segmentNext[firstSegment] = secondSegment;
        segmentPrevious[secondSegment] = firstSegment;
        segmentNext[secondSegment] = firstSegment;
        pushOutsideSegment(turn == TURN_LEFT ? first ^ 1 : first, firstSegment);
        pushOutsideSegment(turn == TURN_LEFT ? second ^ 1 : second, secondSegment);
        enqueueWedge(firstSegment);
        enqueueWedge(secondSegment);
    }

    private int outsideSegment(int halfEdge) {
        int edge = halfEdge >> 1;
        if (edgeOccupancy[edge] == 0) {
            return -1;
        }
        return (halfEdge & 1) == 0 ? edgeSegmentFront[edge] : edgeSegmentBack[edge];
    }

    private void pushOutsideSegment(int halfEdge, int segment) {
        int edge = halfEdge >> 1;
        if (edgeOccupancy[edge] == 0) {
            edgeSegmentFront[edge] = segment;
            edgeSegmentBack[edge] = segment;
        } else if ((halfEdge & 1) == 0) {
            edgeSegmentFront[edge] = segment;
        } else {
            edgeSegmentBack[edge] = segment;
        }
        edgeOccupancy[edge]++;
    }

    private void popOutsideSegment(int halfEdge) {
        int edge = halfEdge >> 1;
        if (edgeOccupancy[edge] == 0) {
            return;
        }
        edgeOccupancy[edge]--;
        if ((halfEdge & 1) == 0) {
            edgeSegmentFront[edge] = edgeSegmentBack[edge];
        } else {
            edgeSegmentBack[edge] = edgeSegmentFront[edge];
        }
    }

    /**
     * Walks the surviving segment chain into a half-edge array, starting a closed loop at the
     * segment whose tail carries the smallest source vertex id so the output does not depend on
     * where the seed happened to start.
     */
    private int[] collect() {
        int start = -1;
        int bestVertexId = Integer.MAX_VALUE;
        for (int segment = 0; segment < segmentIdCount; segment++) {
            if (!segmentAlive[segment]) {
                continue;
            }
            if (!closed) {
                if (segmentPrevious[segment] < 0) {
                    start = segment;
                    break;
                }
                continue;
            }
            int vertexId = triangulation.sourceVertexId[
                    triangulation.halfEdgeTail[segmentHalfEdge[segment]]];
            if (vertexId < bestVertexId) {
                bestVertexId = vertexId;
                start = segment;
            }
        }
        if (start < 0) {
            return new int[0];
        }
        int length = 0;
        int walk = start;
        while (walk >= 0) {
            length++;
            walk = segmentNext[walk];
            if (walk == start) {
                break;
            }
        }
        int[] result = new int[length];
        walk = start;
        for (int index = 0; index < length; index++) {
            result[index] = segmentHalfEdge[walk];
            walk = segmentNext[walk];
        }
        return result;
    }
}
