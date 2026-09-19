package ixdar.geometry.mesh.data.paths;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;

/**
 * Traces a closed cubic spline through anchor vertices by recursive De Casteljau bisection with
 * geodesic midpoints (Mancinelli, Nazzaro, Pellacini and Puppo 2021, RDC, §3.4 and §4.1.1).
 *
 * <p>
 * Each segment keeps its own traced points, so inserting an anchor re-traces only the segments
 * whose control polygon moved.
 */
public final class SurfaceSplineTracer {

    /** Coordinates per point in every packed position here. */
    public static final int COORDINATES_PER_POINT = 3;

    /** Anchors a closed spline needs before it encloses anything. */
    public static final int MINIMUM_ANCHORS = 3;

    /** Control points of one cubic segment. */
    public static final int CONTROL_POINTS = 4;

    /** Manifold averages one De Casteljau bisection of a cubic takes: three, two, then one. */
    public static final int MIDPOINTS_PER_BISECTION = 6;

    /** Bisections a segment may reach, the ceiling on one trace's cost. */
    public static final int DEFAULT_MAXIMUM_DEPTH = 3;

    /**
     * Mean edge lengths a leaf must keep. Bisecting past this puts control points closer together
     * than the mesh can place them, so the mesh, not a fixed count, sets the depth.
     */
    public static final double DEFAULT_SHORTEST_LEAF_EDGE_LENGTHS = 2.0;

    /** Handle length a straight segment takes, as a fraction of its chord. */
    public static final double STRAIGHT_HANDLE_FRACTION = 1.0 / 3.0;

    /** Numerator of the {@code 4/3 tan(alpha/2)} handle that turns a cubic into a circular arc. */
    public static final double CIRCLE_HANDLE_SCALE = 4.0 / 3.0;

    /** Half-angles below this take the straight-segment handle instead of the circular one. */
    public static final double SMALL_TURN_RADIANS = 1e-4;

    /** Largest half-angle the circular handle formula is trusted at, in radians. */
    public static final double LARGEST_TURN_RADIANS = 1.4;

    /** Geodesic engine every average and every leaf is computed with. */
    public SurfaceGeodesics geodesics;

    /** Bisections a segment may reach. */
    public int maximumDepth = DEFAULT_MAXIMUM_DEPTH;

    /** Mean edge lengths a leaf must keep, the floor bisection may not go under. */
    public double shortestLeafInEdges = DEFAULT_SHORTEST_LEAF_EDGE_LENGTHS;

    /** Mesh vertex the spline passes through, per anchor, in ring order. */
    public int[] anchorVertexId = new int[0];

    /** Anchors held in the front of {@link #anchorVertexId}. */
    public int anchorCount;

    /** Unit tangent of the spline at each anchor, packed xyz, the two handles lie along it. */
    public float[] anchorTangent = new float[0];

    /** Vertex the outgoing handle of each segment's first anchor sits on. */
    public int[] outgoingHandleVertexId = new int[0];

    /** Vertex the incoming handle of each segment's last anchor sits on. */
    public int[] incomingHandleVertexId = new int[0];

    /** Packed xyz of each segment's traced points, its last anchor excluded. */
    public double[][] segmentXyz = new double[0][];

    /** Mesh vertex id per traced point of each segment, -1 at an edge crossing. */
    public int[][] segmentVertexId = new int[0][];

    /** Crossed mesh edge id per traced point of each segment, -1 at a vertex. */
    public int[][] segmentEdgeId = new int[0][];

    /** Crossing parameter per traced point of each segment, -1 at a vertex. */
    public double[][] segmentFraction = new double[0][];

    /** Bisection depth each segment was traced at. */
    public int[] segmentDepth = new int[0];

    /** Segment the last {@link #nearestTracedVertex} landed on, or -1. */
    public int nearestSegment = -1;

    /** Distance the last {@link #nearestTracedVertex} was from the point it was given. */
    public double nearestDistance;

    private final Vector3f anchorPosition = new Vector3f();
    private final Vector3f previousPosition = new Vector3f();
    private final Vector3f nextPosition = new Vector3f();
    private final Vector3f incoming = new Vector3f();
    private final Vector3f outgoing = new Vector3f();
    private final float[] handleDirection = new float[COORDINATES_PER_POINT];
    private final float[] scratchPoint = new float[COORDINATES_PER_POINT];
    private int[] controlVertex = new int[0];
    private float[] controlXyz = new float[0];
    private int[] midpointVertex = new int[0];
    private float[] midpointXyz = new float[0];
    private double[] emittedXyz = new double[0];
    private int[] emittedVertexId = new int[0];
    private int[] emittedEdgeId = new int[0];
    private double[] emittedFraction = new double[0];
    private int emittedCount;
    private int emittedDepth;

    /**
     * Binds the tracer to the surface engine every geodesic will run on.
     *
     * @param surfaceGeodesics engine holding the cached triangulation
     */
    public SurfaceSplineTracer(SurfaceGeodesics surfaceGeodesics) {
        this.geodesics = surfaceGeodesics;
    }

    /**
     * Replaces the anchor cycle and traces every segment.
     *
     * @param vertexIds mesh vertices the spline passes through, in ring order
     * @param count     anchors to take from the front of {@code vertexIds}
     * @return true when at least three anchors were accepted and traced
     */
    public boolean setAnchors(int[] vertexIds, int count) {
        if (count < MINIMUM_ANCHORS) {
            anchorCount = 0;
            return false;
        }
        anchorVertexId = Arrays.copyOf(vertexIds, count);
        anchorCount = count;
        resizeSegments();
        retraceAll();
        return true;
    }

    /**
     * Inserts one anchor into the cycle and re-traces only what its control polygons touch: the
     * two segments it splits and the two whose end tangent it moved.
     *
     * @param beforeAnchor index the new anchor takes, so it lands between {@code beforeAnchor - 1}
     *                     and the anchor that held that index
     * @param vertexId     mesh vertex the new anchor sits on
     * @return true when the anchor was inserted
     */
    public boolean insertAnchor(int beforeAnchor, int vertexId) {
        if (anchorCount < MINIMUM_ANCHORS || beforeAnchor < 0 || beforeAnchor > anchorCount) {
            return false;
        }
        int[] grown = new int[anchorCount + 1];
        System.arraycopy(anchorVertexId, 0, grown, 0, beforeAnchor);
        grown[beforeAnchor] = vertexId;
        System.arraycopy(anchorVertexId, beforeAnchor, grown, beforeAnchor + 1,
                anchorCount - beforeAnchor);
        anchorVertexId = grown;
        anchorCount++;
        int[] keptDepth = segmentDepth;
        double[][] keptXyz = segmentXyz;
        int[][] keptVertexId = segmentVertexId;
        int[][] keptEdgeId = segmentEdgeId;
        double[][] keptFraction = segmentFraction;
        resizeSegments();
        for (int segment = 0; segment < anchorCount; segment++) {
            int source = segment < beforeAnchor - 1 ? segment
                    : segment > beforeAnchor ? segment - 1 : -1;
            if (source < 0 || source >= keptDepth.length) {
                continue;
            }
            segmentXyz[segment] = keptXyz[source];
            segmentVertexId[segment] = keptVertexId[source];
            segmentEdgeId[segment] = keptEdgeId[source];
            segmentFraction[segment] = keptFraction[source];
            segmentDepth[segment] = keptDepth[source];
        }
        setTangents();
        for (int step = -2; step <= 1; step++) {
            retraceSegment(Math.floorMod(beforeAnchor + step, anchorCount));
        }
        return true;
    }

    /** Re-computes every tangent handle and re-traces every segment. */
    public void retraceAll() {
        setTangents();
        for (int segment = 0; segment < anchorCount; segment++) {
            retraceSegment(segment);
        }
    }

    /**
     * Traces one segment's cubic by recursive bisection and keeps its points.
     *
     * @param segment segment index, running from anchor {@code segment} to the next one
     */
    public void retraceSegment(int segment) {
        setHandles(segment);
        prepareRecursionStack();
        int[] seed = {
            anchorVertexId[segment], outgoingHandleVertexId[segment],
            incomingHandleVertexId[segment], anchorVertexId[(segment + 1) % anchorCount] };
        for (int control = 0; control < CONTROL_POINTS; control++) {
            controlVertex[control] = seed[control];
            geodesics.mesh.vertexPosition(seed[control], anchorPosition);
            controlXyz[COORDINATES_PER_POINT * control] = anchorPosition.x;
            controlXyz[COORDINATES_PER_POINT * control + 1] = anchorPosition.y;
            controlXyz[COORDINATES_PER_POINT * control + 2] = anchorPosition.z;
        }
        emittedCount = 0;
        emittedDepth = 0;
        subdivide(0, depthFor(segment));
        segmentXyz[segment] = Arrays.copyOf(emittedXyz, COORDINATES_PER_POINT * emittedCount);
        segmentVertexId[segment] = Arrays.copyOf(emittedVertexId, emittedCount);
        segmentEdgeId[segment] = Arrays.copyOf(emittedEdgeId, emittedCount);
        segmentFraction[segment] = Arrays.copyOf(emittedFraction, emittedCount);
        segmentDepth[segment] = emittedDepth;
    }

    /**
     * The whole ring as one closed traced path, the form the conforming edge-cycle snap and the
     * ring overlay both read.
     *
     * @return the closed path, or an empty one before any anchors are set
     */
    public TracedSurfacePath tracedRing() {
        int total = 0;
        for (int segment = 0; segment < anchorCount; segment++) {
            total += segmentVertexId[segment].length;
        }
        double[] positions = new double[COORDINATES_PER_POINT * total];
        int[] vertexId = new int[total];
        int[] edgeId = new int[total];
        double[] fraction = new double[total];
        int cursor = 0;
        for (int segment = 0; segment < anchorCount; segment++) {
            int points = segmentVertexId[segment].length;
            System.arraycopy(segmentXyz[segment], 0, positions,
                    COORDINATES_PER_POINT * cursor, COORDINATES_PER_POINT * points);
            System.arraycopy(segmentVertexId[segment], 0, vertexId, cursor, points);
            System.arraycopy(segmentEdgeId[segment], 0, edgeId, cursor, points);
            System.arraycopy(segmentFraction[segment], 0, fraction, cursor, points);
            cursor += points;
        }
        return new TracedSurfacePath(positions, vertexId, edgeId, fraction, total, true);
    }

    /**
     * The mesh vertex on the traced spline nearest a surface point, and the segment it lies on,
     * which is where a click on an existing ring inserts its anchor.
     *
     * @param x point x
     * @param y point y
     * @param z point z
     * @return the vertex id, or -1 when nothing is traced yet
     */
    public int nearestTracedVertex(float x, float y, float z) {
        nearestSegment = -1;
        nearestDistance = Double.POSITIVE_INFINITY;
        int nearestPoint = -1;
        for (int segment = 0; segment < anchorCount; segment++) {
            double[] points = segmentXyz[segment];
            for (int base = 0; base < points.length; base += COORDINATES_PER_POINT) {
                double dx = points[base] - x;
                double dy = points[base + 1] - y;
                double dz = points[base + 2] - z;
                double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestSegment = segment;
                    nearestPoint = base / COORDINATES_PER_POINT;
                }
            }
        }
        if (nearestSegment < 0) {
            return -1;
        }
        int vertexId = segmentVertexId[nearestSegment][nearestPoint];
        if (vertexId >= 0) {
            return vertexId;
        }
        int edgeId = segmentEdgeId[nearestSegment][nearestPoint];
        return edgeId < 0 ? anchorVertexId[nearestSegment]
                : nearerEnd(edgeId, segmentFraction[nearestSegment][nearestPoint]);
    }

    private int nearerEnd(int edgeId, double fraction) {
        int halfEdge = geodesics.mesh.edgeHalfEdge(edgeId);
        return fraction <= 0.5 ? geodesics.mesh.halfEdgeVertex(halfEdge)
                : geodesics.mesh.halfEdgeEndVertex(halfEdge);
    }

    private void prepareRecursionStack() {
        int levels = Math.max(1, maximumDepth) + 2;
        if (controlVertex.length >= CONTROL_POINTS * levels) {
            return;
        }
        controlVertex = new int[CONTROL_POINTS * levels];
        controlXyz = new float[COORDINATES_PER_POINT * CONTROL_POINTS * levels];
        midpointVertex = new int[MIDPOINTS_PER_BISECTION * levels];
        midpointXyz = new float[COORDINATES_PER_POINT * MIDPOINTS_PER_BISECTION * levels];
    }

    private void resizeSegments() {
        segmentXyz = Arrays.copyOf(segmentXyz, anchorCount);
        segmentVertexId = Arrays.copyOf(segmentVertexId, anchorCount);
        segmentEdgeId = Arrays.copyOf(segmentEdgeId, anchorCount);
        segmentFraction = Arrays.copyOf(segmentFraction, anchorCount);
        segmentDepth = Arrays.copyOf(segmentDepth, anchorCount);
        for (int segment = 0; segment < anchorCount; segment++) {
            if (segmentXyz[segment] == null) {
                segmentXyz[segment] = new double[0];
                segmentVertexId[segment] = new int[0];
                segmentEdgeId[segment] = new int[0];
                segmentFraction[segment] = new double[0];
            }
        }
    }

    /**
     * The unit tangent at every anchor, weighted by the neighbouring chord lengths (Barry and
     * Goldman's non-uniform Catmull-Rom tangent) so an anchor between a short segment and a long
     * one still points along the curve. Both handles there lie on this one line, which is what
     * keeps the junction smooth.
     */
    private void setTangents() {
        anchorTangent = new float[COORDINATES_PER_POINT * anchorCount];
        MeshTopology mesh = geodesics.mesh;
        for (int anchor = 0; anchor < anchorCount; anchor++) {
            mesh.vertexPosition(anchorVertexId[anchor], anchorPosition);
            mesh.vertexPosition(anchorVertexId[Math.floorMod(anchor - 1, anchorCount)],
                    previousPosition);
            mesh.vertexPosition(anchorVertexId[(anchor + 1) % anchorCount], nextPosition);
            incoming.set(anchorPosition).sub(previousPosition);
            outgoing.set(nextPosition).sub(anchorPosition);
            double behind = incoming.length();
            double ahead = outgoing.length();
            incoming.mul((float) (ahead * ahead)).fma((float) (behind * behind), outgoing);
            if (incoming.lengthSquared() <= 0f) {
                incoming.set(nextPosition).sub(previousPosition);
            }
            if (incoming.lengthSquared() > 0f) {
                incoming.normalize();
            }
            anchorTangent[COORDINATES_PER_POINT * anchor] = incoming.x;
            anchorTangent[COORDINATES_PER_POINT * anchor + 1] = incoming.y;
            anchorTangent[COORDINATES_PER_POINT * anchor + 2] = incoming.z;
        }
        outgoingHandleVertexId = new int[anchorCount];
        incomingHandleVertexId = new int[anchorCount];
        Arrays.fill(outgoingHandleVertexId, -1);
        Arrays.fill(incomingHandleVertexId, -1);
    }

    /**
     * The two inner control points of one segment: walk from each end along that anchor's tangent
     * by the length that makes a cubic reproduce the circular arc through the two anchors, which
     * degrades to a third of the chord as the segment straightens.
     */
    private void setHandles(int segment) {
        int next = (segment + 1) % anchorCount;
        MeshTopology mesh = geodesics.mesh;
        mesh.vertexPosition(anchorVertexId[segment], anchorPosition);
        mesh.vertexPosition(anchorVertexId[next], nextPosition);
        outgoing.set(nextPosition).sub(anchorPosition);
        double chord = outgoing.length();
        if (chord <= 0.0) {
            outgoingHandleVertexId[segment] = anchorVertexId[segment];
            incomingHandleVertexId[segment] = anchorVertexId[next];
            return;
        }
        outgoing.div((float) chord);
        outgoingHandleVertexId[segment] = handleVertex(segment, chord, 1f);
        incomingHandleVertexId[segment] = handleVertex(next, chord, -1f);
    }

    private int handleVertex(int anchor, double chord, float sign) {
        double alignment = anchorTangent[COORDINATES_PER_POINT * anchor] * outgoing.x
                + anchorTangent[COORDINATES_PER_POINT * anchor + 1] * outgoing.y
                + anchorTangent[COORDINATES_PER_POINT * anchor + 2] * outgoing.z;
        double turn = Math.acos(Math.max(0.0, Math.min(1.0, alignment)));
        double length = turn < SMALL_TURN_RADIANS
                ? STRAIGHT_HANDLE_FRACTION * chord
                : CIRCLE_HANDLE_SCALE * Math.tan(Math.min(turn, LARGEST_TURN_RADIANS) / 2.0)
                        * chord / (2.0 * Math.sin(Math.min(turn, LARGEST_TURN_RADIANS)));
        for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
            handleDirection[axis] =
                    sign * anchorTangent[COORDINATES_PER_POINT * anchor + axis];
        }
        return geodesics.walkFrom(anchorVertexId[anchor], handleDirection, length);
    }

    /**
     * Bisections one segment gets: as many as the mesh supports, since a leaf shorter than
     * {@link #shortestLeafInEdges} mean edges cannot place its control points apart, capped by
     * {@link #maximumDepth} so one trace's cost stays inside a hover frame.
     */
    private int depthFor(int segment) {
        MeshTopology mesh = geodesics.mesh;
        mesh.vertexPosition(anchorVertexId[segment], anchorPosition);
        mesh.vertexPosition(anchorVertexId[(segment + 1) % anchorCount], nextPosition);
        double chord = anchorPosition.distance(nextPosition);
        double shortest =
                Math.max(shortestLeafInEdges * geodesics.meanEdgeLength, Double.MIN_NORMAL);
        int allowed = chord <= shortest ? 0
                : (int) Math.floor(Math.log(chord / shortest) / Math.log(2.0));
        return Math.min(maximumDepth, allowed);
    }

    /**
     * One De Casteljau bisection at {@code t = 1/2} with manifold averages, recursing into the
     * two halves and emitting a leaf's geodesic when the recursion bottoms out.
     */
    /**
     * One De Casteljau bisection at {@code t = 1/2} with manifold averages, recursing into both
     * halves and emitting a leaf's geodesic at the bottom.
     *
     * <p>
     * A control point carries a vertex and the exact average, up to half an edge apart; a leaf is
     * warped by that offset.
     */
    private void subdivide(int level, int depth) {
        int control = CONTROL_POINTS * level;
        if (depth <= 0) {
            emitLeaf(level);
            return;
        }
        int middle = MIDPOINTS_PER_BISECTION * level;
        for (int pair = 0; pair < CONTROL_POINTS - 1; pair++) {
            average(controlVertex[control + pair], controlXyz, COORDINATES_PER_POINT
                    * (control + pair), controlVertex[control + pair + 1], controlXyz,
                    COORDINATES_PER_POINT * (control + pair + 1), middle + pair);
        }
        for (int pair = 0; pair < CONTROL_POINTS - 2; pair++) {
            average(midpointVertex[middle + pair], midpointXyz,
                    COORDINATES_PER_POINT * (middle + pair), midpointVertex[middle + pair + 1],
                    midpointXyz, COORDINATES_PER_POINT * (middle + pair + 1),
                    middle + CONTROL_POINTS - 1 + pair);
        }
        average(midpointVertex[middle + CONTROL_POINTS - 1], midpointXyz,
                COORDINATES_PER_POINT * (middle + CONTROL_POINTS - 1),
                midpointVertex[middle + CONTROL_POINTS], midpointXyz,
                COORDINATES_PER_POINT * (middle + CONTROL_POINTS),
                middle + MIDPOINTS_PER_BISECTION - 1);

        int child = CONTROL_POINTS * (level + 1);
        int junction = middle + MIDPOINTS_PER_BISECTION - 1;
        copyControl(control, child);
        copyMidpoint(middle, child + 1);
        copyMidpoint(middle + CONTROL_POINTS - 1, child + 2);
        copyMidpoint(junction, child + 3);
        subdivide(level + 1, depth - 1);
        copyMidpoint(junction, child);
        copyMidpoint(middle + CONTROL_POINTS, child + 1);
        copyMidpoint(middle + 2, child + 2);
        copyControl(control + 3, child + 3);
        subdivide(level + 1, depth - 1);
    }

    /**
     * The manifold average of two control points at one half: the midpoint of the geodesic between
     * their vertices, moved by the same blend of the two points' own offsets from those vertices.
     */
    private void average(int fromVertexId, float[] fromXyz, int fromBase, int toVertexId,
            float[] toXyz, int toBase, int slot) {
        int base = COORDINATES_PER_POINT * slot;
        if (fromVertexId == toVertexId || !geodesics.geodesic(fromVertexId, toVertexId)) {
            midpointVertex[slot] = fromVertexId;
            System.arraycopy(fromXyz, fromBase, midpointXyz, base, COORDINATES_PER_POINT);
            return;
        }
        int vertexId = geodesics.vertexAtFraction(0.5, scratchPoint);
        midpointVertex[slot] = vertexId < 0 ? fromVertexId : vertexId;
        geodesics.mesh.vertexPosition(fromVertexId, previousPosition);
        geodesics.mesh.vertexPosition(toVertexId, nextPosition);
        midpointXyz[base] = scratchPoint[0]
                + 0.5f * (fromXyz[fromBase] - previousPosition.x + toXyz[toBase] - nextPosition.x);
        midpointXyz[base + 1] = scratchPoint[1] + 0.5f * (fromXyz[fromBase + 1]
                - previousPosition.y + toXyz[toBase + 1] - nextPosition.y);
        midpointXyz[base + 2] = scratchPoint[2] + 0.5f * (fromXyz[fromBase + 2]
                - previousPosition.z + toXyz[toBase + 2] - nextPosition.z);
    }

    private void copyControl(int fromSlot, int toSlot) {
        controlVertex[toSlot] = controlVertex[fromSlot];
        System.arraycopy(controlXyz, COORDINATES_PER_POINT * fromSlot, controlXyz,
                COORDINATES_PER_POINT * toSlot, COORDINATES_PER_POINT);
    }

    private void copyMidpoint(int fromSlot, int toSlot) {
        controlVertex[toSlot] = midpointVertex[fromSlot];
        System.arraycopy(midpointXyz, COORDINATES_PER_POINT * fromSlot, controlXyz,
                COORDINATES_PER_POINT * toSlot, COORDINATES_PER_POINT);
    }

    /**
     * Appends one leaf: its cubic sampled where the geodesic between its end vertices crosses the
     * mesh, so every point carries the edge correspondence the conforming snap needs.
     *
     * <p>
     * Sampling the cubic spreads the ring's turning over every point instead of concentrating it
     * at the leaf joins. The last point is dropped.
     */
    private void emitLeaf(int level) {
        emittedDepth = Math.max(emittedDepth, level);
        int control = CONTROL_POINTS * level;
        int fromVertexId = controlVertex[control];
        int toVertexId = controlVertex[control + 3];
        int fromBase = COORDINATES_PER_POINT * control;
        int toBase = COORDINATES_PER_POINT * (control + 3);
        if (fromVertexId == toVertexId || !geodesics.geodesic(fromVertexId, toVertexId)) {
            appendPoint(controlXyz[fromBase], controlXyz[fromBase + 1], controlXyz[fromBase + 2],
                    fromVertexId, -1, -1.0);
            return;
        }
        for (int point = 0; point < geodesics.tracedPointCount - 1; point++) {
            double along = geodesics.pathLength <= 0.0 ? 0.0
                    : geodesics.tracedArcLength[point] / geodesics.pathLength;
            double first = (1.0 - along) * (1.0 - along) * (1.0 - along);
            double second = 3.0 * along * (1.0 - along) * (1.0 - along);
            double third = 3.0 * along * along * (1.0 - along);
            double fourth = along * along * along;
            appendPoint(
                    first * controlXyz[fromBase]
                            + second * controlXyz[fromBase + COORDINATES_PER_POINT]
                            + third * controlXyz[fromBase + 2 * COORDINATES_PER_POINT]
                            + fourth * controlXyz[toBase],
                    first * controlXyz[fromBase + 1]
                            + second * controlXyz[fromBase + COORDINATES_PER_POINT + 1]
                            + third * controlXyz[fromBase + 2 * COORDINATES_PER_POINT + 1]
                            + fourth * controlXyz[toBase + 1],
                    first * controlXyz[fromBase + 2]
                            + second * controlXyz[fromBase + COORDINATES_PER_POINT + 2]
                            + third * controlXyz[fromBase + 2 * COORDINATES_PER_POINT + 2]
                            + fourth * controlXyz[toBase + 2],
                    geodesics.tracedVertexId[point], geodesics.tracedEdgeId[point],
                    geodesics.tracedFraction[point]);
        }
    }

    private void appendPoint(double x, double y, double z, int vertexId, int edgeId,
            double fraction) {
        ensureEmitCapacity();
        int base = COORDINATES_PER_POINT * emittedCount;
        emittedXyz[base] = x;
        emittedXyz[base + 1] = y;
        emittedXyz[base + 2] = z;
        emittedVertexId[emittedCount] = vertexId;
        emittedEdgeId[emittedCount] = edgeId;
        emittedFraction[emittedCount] = fraction;
        emittedCount++;
    }

    private void ensureEmitCapacity() {
        if (emittedCount < emittedVertexId.length) {
            return;
        }
        int grown = Math.max(256, 2 * emittedVertexId.length);
        emittedXyz = Arrays.copyOf(emittedXyz, COORDINATES_PER_POINT * grown);
        emittedVertexId = Arrays.copyOf(emittedVertexId, grown);
        emittedEdgeId = Arrays.copyOf(emittedEdgeId, grown);
        emittedFraction = Arrays.copyOf(emittedFraction, grown);
    }
}
