package ixdar.geometry.mesh.data.paths;

import java.util.Arrays;

/**
 * A signpost intrinsic triangulation kept current under edge flip, over a shared
 * {@link SurfaceMetric} it never writes: each flip lands in a private overlay of the elements it
 * changed.
 *
 * <p>
 * Reads fall through to the metric wherever the overlay holds nothing, and
 * {@link #discardFlips} empties the overlay in time proportional to the flips.
 */
public final class IntrinsicTriangulation {

    public static final double TRIANGLE_TEST_EPSILON = 1e-6;

    public static final int ELEMENT_KINDS = 3;

    public static final int HALF_EDGE_KIND = 0;

    public static final int EDGE_KIND = 1;

    public static final int VERTEX_KIND = 2;

    public static final int INITIAL_OVERLAY_CAPACITY = 256;

    public static final int EMPTY_SLOT = -1;

    public static final int FIBONACCI_HASH = 0x9E3779B9;

    /** Metric every unflipped element is read from. */
    public final SurfaceMetric metric;

    /**
     * Overlay key per slot, {@code element * ELEMENT_KINDS + kind}, or {@link #EMPTY_SLOT}. Columns:
     * a half-edge's next, tail and signpost; an edge's length; a vertex's reference half-edge.
     * Faces are not tracked: nothing reads them once an edge has flipped.
     */
    private int[] slotKey = new int[0];
    private int[] slotFirst = new int[0];
    private int[] slotSecond = new int[0];
    private double[] slotValue = new double[0];
    private int[] occupiedSlot = new int[0];
    private int occupiedCount;
    private int hashShift;
    private final double[] diamondX = new double[4];
    private final double[] diamondY = new double[4];

    private IntrinsicTriangulation(SurfaceMetric metric) {
        this.metric = metric;
    }

    /**
     * A flippable triangulation that starts as the metric's own.
     *
     * @param metric shared metric, read and never written
     * @return a triangulation with no flips
     */
    public static IntrinsicTriangulation over(SurfaceMetric metric) {
        return new IntrinsicTriangulation(metric);
    }

    /**
     * Dense intrinsic vertex index a half-edge leaves from.
     *
     * @param halfEdge half-edge to query
     * @return its tail vertex
     */
    public int halfEdgeTail(int halfEdge) {
        int slot = find(halfEdge, HALF_EDGE_KIND);
        return slot < 0 ? metric.halfEdgeTail[halfEdge] : slotSecond[slot];
    }

    /**
     * Next half-edge around the same face.
     *
     * @param halfEdge half-edge to query
     * @return the next half-edge, or -1 when the half-edge is exterior
     */
    public int halfEdgeNext(int halfEdge) {
        int slot = find(halfEdge, HALF_EDGE_KIND);
        return slot < 0 ? metric.halfEdgeNext[halfEdge] : slotFirst[slot];
    }

    /**
     * Counter-clockwise angular coordinate of a half-edge at its tail vertex.
     *
     * @param halfEdge half-edge to query
     * @return the signpost angle in {@code [0, vertexAngleSum)}
     */
    public double signpostAngle(int halfEdge) {
        int slot = find(halfEdge, HALF_EDGE_KIND);
        return slot < 0 ? metric.signpostAngle[halfEdge] : slotValue[slot];
    }

    /**
     * Intrinsic length of an edge.
     *
     * @param edge dense intrinsic edge index
     * @return its length, shared by both half-edges
     */
    public double edgeLength(int edge) {
        int slot = find(edge, EDGE_KIND);
        return slot < 0 ? metric.edgeLength[edge] : slotValue[slot];
    }

    /**
     * Whether an edge is still the edge it was built as, a source edge or a straight split inside
     * one source polygon, rather than a flipped one that crosses source edges.
     *
     * @param edge dense intrinsic edge index
     * @return true when the edge has not been flipped
     */
    public boolean edgeIsOriginal(int edge) {
        return find(edge, EDGE_KIND) < 0;
    }

    /**
     * Outgoing half-edge whose signpost angle is zero.
     *
     * @param vertex dense intrinsic vertex index
     * @return the reference half-edge, or -1 for an isolated vertex
     */
    public int vertexReferenceHalfEdge(int vertex) {
        int slot = find(vertex, VERTEX_KIND);
        return slot < 0 ? metric.vertexReferenceHalfEdge[vertex] : slotFirst[slot];
    }

    /**
     * The vertex a half-edge points at, which is its twin's tail.
     *
     * @param halfEdge half-edge to query
     * @return dense intrinsic vertex index of the head
     */
    public int halfEdgeHead(int halfEdge) {
        return halfEdgeTail(halfEdge ^ 1);
    }

    /**
     * The previous half-edge around the same triangle.
     *
     * @param halfEdge interior half-edge to query
     * @return the half-edge whose {@code next} is {@code halfEdge}
     */
    public int halfEdgePrevious(int halfEdge) {
        return halfEdgeNext(halfEdgeNext(halfEdge));
    }

    /**
     * Whether the half-edge bounds a face rather than the outside of a boundary edge.
     *
     * @param halfEdge half-edge to query
     * @return true when the half-edge carries a face
     */
    public boolean isInterior(int halfEdge) {
        return metric.halfEdgeNext[halfEdge] >= 0;
    }

    /**
     * Whether the edge lies on the mesh boundary, which also makes it unflippable.
     *
     * @param edge dense intrinsic edge index
     * @return true when either side of the edge is exterior
     */
    public boolean isBoundaryEdge(int edge) {
        return metric.halfEdgeNext[edge << 1] < 0 || metric.halfEdgeNext[(edge << 1) | 1] < 0;
    }

    /**
     * The next outgoing half-edge counter-clockwise around a half-edge's tail vertex.
     *
     * @param halfEdge interior outgoing half-edge
     * @return the outgoing half-edge one corner counter-clockwise
     */
    public int counterClockwiseNeighbor(int halfEdge) {
        return halfEdgePrevious(halfEdge) ^ 1;
    }

    /**
     * The next outgoing half-edge clockwise around a half-edge's tail vertex.
     *
     * @param halfEdge outgoing half-edge whose twin is interior
     * @return the outgoing half-edge one corner clockwise
     */
    public int clockwiseNeighbor(int halfEdge) {
        return halfEdgeNext(halfEdge ^ 1);
    }

    /**
     * The outgoing half-edge joining two vertices, found by orbiting the first vertex's fan.
     *
     * @param fromVertex dense intrinsic vertex index the half-edge leaves
     * @param toVertex   dense intrinsic vertex index the half-edge points at
     * @return the half-edge index, or -1 when the two vertices share no intrinsic edge
     */
    public int halfEdgeBetween(int fromVertex, int toVertex) {
        int reference = vertexReferenceHalfEdge(fromVertex);
        if (reference < 0) {
            return -1;
        }
        int current = reference;
        do {
            if (halfEdgeHead(current) == toVertex) {
                return current;
            }
            if (!isInterior(current)) {
                break;
            }
            current = counterClockwiseNeighbor(current);
        } while (current != reference);
        return -1;
    }

    /**
     * Interior angle at a half-edge's tail, between it and the previous half-edge of its face.
     *
     * @param halfEdge interior half-edge whose corner is measured
     * @return the corner angle in radians, in {@code [0, pi]}
     */
    public double cornerAngle(int halfEdge) {
        double adjacent = edgeLength(halfEdge >> 1);
        double other = edgeLength(halfEdgePrevious(halfEdge) >> 1);
        double opposite = edgeLength(halfEdgeNext(halfEdge) >> 1);
        double denominator = 2.0 * adjacent * other;
        if (denominator <= 0.0) {
            return 0.0;
        }
        double cosine = (adjacent * adjacent + other * other - opposite * opposite) / denominator;
        return Math.acos(Math.max(-1.0, Math.min(1.0, cosine)));
    }

    /**
     * Angle folded into the vertex's own angular period {@code [0, vertexAngleSum)}.
     *
     * @param vertex dense intrinsic vertex index
     * @param angle  raw angle in radians
     * @return the equivalent angle inside one turn around {@code vertex}
     */
    public double standardizeAngle(int vertex, double angle) {
        double period = metric.vertexAngleSum[vertex];
        if (period <= 0.0) {
            return 0.0;
        }
        double folded = angle % period;
        return folded < 0.0 ? folded + period : folded;
    }

    /**
     * Angles of the two wedges a path turn cuts the vertex into, left side first.
     *
     * <p>
     * A boundary vertex has no wedge on its outside, so that side reports
     * {@link Double#POSITIVE_INFINITY} and is never chosen for shortening.
     *
     * @param incomingHalfEdge half-edge of the path arriving at the middle vertex
     * @param outgoingHalfEdge half-edge of the path leaving the middle vertex
     * @param sideAngles       two-element buffer filled with {left, right}
     */
    public void measureSideAngles(int incomingHalfEdge, int outgoingHalfEdge, double[] sideAngles) {
        int middleVertex = halfEdgeTail(outgoingHalfEdge);
        double period = metric.vertexAngleSum[middleVertex];
        double angleIn = signpostAngle(incomingHalfEdge ^ 1);
        double angleOut = signpostAngle(outgoingHalfEdge);
        boolean boundary = metric.vertexIsBoundary[middleVertex];

        double right;
        if (angleIn < angleOut) {
            right = angleOut - angleIn;
        } else {
            right = boundary ? Double.POSITIVE_INFINITY : (period - angleIn) + angleOut;
        }
        double left;
        if (angleOut < angleIn) {
            left = angleIn - angleOut;
        } else {
            left = boundary ? Double.POSITIVE_INFINITY : (period - angleOut) + angleIn;
        }
        sideAngles[0] = left;
        sideAngles[1] = right;
    }

    /**
     * Flips an intrinsic edge when the two triangles around it lay out as a convex diamond.
     *
     * <p>
     * Refusing an inverted or degenerate result is exactly the {@code beta &lt; pi} condition of
     * Sharp &amp; Crane 2020 §3.
     *
     * @param edge dense intrinsic edge index to flip
     * @return true when the edge was flipped, false when it was left alone
     */
    public boolean flipIfPossible(int edge) {
        if (isBoundaryEdge(edge)) {
            return false;
        }
        int frontHalfEdge = edge << 1;
        int backHalfEdge = frontHalfEdge | 1;
        int frontNext = halfEdgeNext(frontHalfEdge);
        int frontPrevious = halfEdgeNext(frontNext);
        int backNext = halfEdgeNext(backHalfEdge);
        int backPrevious = halfEdgeNext(backNext);
        if (halfEdgeNext(frontPrevious) != frontHalfEdge
                || halfEdgeNext(backPrevious) != backHalfEdge) {
            return false;
        }
        int tailVertex = halfEdgeTail(frontHalfEdge);
        int headVertex = halfEdgeTail(backHalfEdge);
        int frontApex = halfEdgeTail(frontPrevious);
        int backApex = halfEdgeTail(backPrevious);
        if (frontApex == backApex || frontNext == backHalfEdge || backNext == frontHalfEdge) {
            return false;
        }

        // Lay the two triangles flat: the edge runs from corner 2 to corner 0, corner 3 sits at the
        // origin and edge 3-0 lies along the x axis.
        diamondX[3] = 0.0;
        diamondY[3] = 0.0;
        diamondX[0] = edgeLength(backPrevious >> 1);
        diamondY[0] = 0.0;
        layOutTriangleVertex(3, 0, edgeLength(edge), edgeLength(backNext >> 1), 2);
        layOutTriangleVertex(2, 0, edgeLength(frontNext >> 1), edgeLength(frontPrevious >> 1), 1);
        double firstArea = cross(diamondX[1] - diamondX[0], diamondY[1] - diamondY[0],
                diamondX[3] - diamondX[0], diamondY[3] - diamondY[0]);
        double secondArea = cross(diamondX[3] - diamondX[2], diamondY[3] - diamondY[2],
                diamondX[1] - diamondX[2], diamondY[1] - diamondY[2]);
        double areaFloor = TRIANGLE_TEST_EPSILON * (firstArea + secondArea);
        if (firstArea < areaFloor || secondArea < areaFloor) {
            return false;
        }
        double newLength = Math.hypot(diamondX[1] - diamondX[3], diamondY[1] - diamondY[3]);
        if (!Double.isFinite(newLength) || newLength <= 0.0) {
            return false;
        }

        // Each slot is written right after it is touched: a later touch may rehash the overlay.
        int slot = touch(frontHalfEdge, HALF_EDGE_KIND);
        slotFirst[slot] = backPrevious;
        slotSecond[slot] = frontApex;
        slot = touch(backPrevious, HALF_EDGE_KIND);
        slotFirst[slot] = frontNext;
        slot = touch(frontNext, HALF_EDGE_KIND);
        slotFirst[slot] = frontHalfEdge;
        slot = touch(backHalfEdge, HALF_EDGE_KIND);
        slotFirst[slot] = frontPrevious;
        slotSecond[slot] = backApex;
        slot = touch(frontPrevious, HALF_EDGE_KIND);
        slotFirst[slot] = backNext;
        slot = touch(backNext, HALF_EDGE_KIND);
        slotFirst[slot] = backHalfEdge;
        if (vertexReferenceHalfEdge(tailVertex) == frontHalfEdge) {
            slot = touch(tailVertex, VERTEX_KIND);
            slotFirst[slot] = backNext;
        }
        if (vertexReferenceHalfEdge(headVertex) == backHalfEdge) {
            slot = touch(headVertex, VERTEX_KIND);
            slotFirst[slot] = frontNext;
        }
        slot = touch(edge, EDGE_KIND);
        slotValue[slot] = newLength;
        double frontSignpost = clockwiseSignpost(frontHalfEdge);
        slot = touch(frontHalfEdge, HALF_EDGE_KIND);
        slotValue[slot] = frontSignpost;
        double backSignpost = clockwiseSignpost(backHalfEdge);
        slot = touch(backHalfEdge, HALF_EDGE_KIND);
        slotValue[slot] = backSignpost;
        return true;
    }

    /**
     * Forgets every flip, so the triangulation reads as the metric again; the cost is one slot
     * clear per element the flips touched.
     */
    public void discardFlips() {
        for (int index = 0; index < occupiedCount; index++) {
            slotKey[occupiedSlot[index]] = EMPTY_SLOT;
        }
        occupiedCount = 0;
    }

    /**
     * Total length of a half-edge chain, used to compare a path against its replacement.
     *
     * @param halfEdges half-edge indices to measure
     * @param count     number of leading entries of {@code halfEdges} to read
     * @return summed intrinsic edge length
     */
    public double chainLength(int[] halfEdges, int count) {
        double total = 0.0;
        for (int index = 0; index < count; index++) {
            total += edgeLength(halfEdges[index] >> 1);
        }
        return total;
    }

    /**
     * The signpost angle a just-flipped half-edge takes: its clockwise neighbour's angle plus the
     * corner between them, or the fixed angle at either end of a boundary fan.
     */
    private double clockwiseSignpost(int halfEdge) {
        int tail = halfEdgeTail(halfEdge);
        if (halfEdgeNext(halfEdge) < 0) {
            return metric.vertexAngleSum[tail];
        }
        if (halfEdgeNext(halfEdge ^ 1) < 0) {
            return 0.0;
        }
        int clockwise = clockwiseNeighbor(halfEdge);
        return standardizeAngle(tail, signpostAngle(clockwise) + cornerAngle(clockwise));
    }

    private void layOutTriangleVertex(int corner, int otherCorner, double oppositeToCorner,
            double toCorner, int target) {
        double dx = diamondX[otherCorner] - diamondX[corner];
        double dy = diamondY[otherCorner] - diamondY[corner];
        double base = Math.hypot(dx, dy);
        if (base <= 0.0) {
            diamondX[target] = diamondX[corner];
            diamondY[target] = diamondY[corner];
            return;
        }
        double along = (toCorner * toCorner + base * base - oppositeToCorner * oppositeToCorner)
                / (2.0 * base);
        double across = Math.sqrt(Math.max(0.0, toCorner * toCorner - along * along));
        double unitX = dx / base;
        double unitY = dy / base;
        diamondX[target] = diamondX[corner] + unitX * along - unitY * across;
        diamondY[target] = diamondY[corner] + unitY * along + unitX * across;
    }

    private static double cross(double firstX, double firstY, double secondX, double secondY) {
        return firstX * secondY - firstY * secondX;
    }

    /** The overlay slot holding an element, or -1 when the element still reads as the metric. */
    private int find(int element, int kind) {
        if (occupiedCount == 0) {
            return -1;
        }
        int key = element * ELEMENT_KINDS + kind;
        int mask = slotKey.length - 1;
        for (int slot = (key * FIBONACCI_HASH) >>> hashShift;; slot = (slot + 1) & mask) {
            if (slotKey[slot] == key) {
                return slot;
            }
            if (slotKey[slot] == EMPTY_SLOT) {
                return -1;
            }
        }
    }

    /**
     * The overlay slot of an element, created holding the metric's values when the element has
     * none yet. The overlay doubles before it passes half full, which moves every slot.
     */
    private int touch(int element, int kind) {
        if (2 * (occupiedCount + 1) > slotKey.length) {
            int[] oldKey = slotKey;
            int[] oldFirst = slotFirst;
            int[] oldSecond = slotSecond;
            double[] oldValue = slotValue;
            int[] oldOccupied = occupiedSlot;
            int oldCount = occupiedCount;
            int capacity = Math.max(INITIAL_OVERLAY_CAPACITY, 2 * slotKey.length);
            slotKey = new int[capacity];
            Arrays.fill(slotKey, EMPTY_SLOT);
            slotFirst = new int[capacity];
            slotSecond = new int[capacity];
            slotValue = new double[capacity];
            occupiedSlot = new int[capacity];
            occupiedCount = 0;
            hashShift = Integer.SIZE - Integer.numberOfTrailingZeros(capacity);
            for (int index = 0; index < oldCount; index++) {
                int from = oldOccupied[index];
                int slot = (oldKey[from] * FIBONACCI_HASH) >>> hashShift;
                while (slotKey[slot] != EMPTY_SLOT) {
                    slot = (slot + 1) & (slotKey.length - 1);
                }
                slotKey[slot] = oldKey[from];
                slotFirst[slot] = oldFirst[from];
                slotSecond[slot] = oldSecond[from];
                slotValue[slot] = oldValue[from];
                occupiedSlot[occupiedCount++] = slot;
            }
        }
        int key = element * ELEMENT_KINDS + kind;
        int mask = slotKey.length - 1;
        int slot = (key * FIBONACCI_HASH) >>> hashShift;
        while (slotKey[slot] != EMPTY_SLOT) {
            if (slotKey[slot] == key) {
                return slot;
            }
            slot = (slot + 1) & mask;
        }
        slotKey[slot] = key;
        occupiedSlot[occupiedCount++] = slot;
        if (kind == HALF_EDGE_KIND) {
            slotFirst[slot] = metric.halfEdgeNext[element];
            slotSecond[slot] = metric.halfEdgeTail[element];
            slotValue[slot] = metric.signpostAngle[element];
        } else if (kind == EDGE_KIND) {
            slotValue[slot] = metric.edgeLength[element];
        } else {
            slotFirst[slot] = metric.vertexReferenceHalfEdge[element];
        }
        return slot;
    }
}
