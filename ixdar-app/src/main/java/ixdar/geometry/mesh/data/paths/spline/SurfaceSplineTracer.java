package ixdar.geometry.mesh.data.paths.spline;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.paths.geodesic.SurfaceGeodesics;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.data.paths.geodesic.TracedSurfacePath;
import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * Traces a closed cubic spline through anchor vertices by recursive De Casteljau bisection with
 * geodesic midpoints (Mancinelli, Nazzaro, Pellacini and Puppo 2021, RDC, §3.4 and §4.1.1).
 *
 * <p>
 * Each segment keeps its own traced points, so inserting an anchor re-traces only the segments
 * whose control polygon moved.
 */
public final class SurfaceSplineTracer {

    public static final int MINIMUM_ANCHORS = 3;

    public static final int CONTROL_POINTS = 4;

    public static final int MIDPOINTS_PER_BISECTION = 6;

    public static final int DEFAULT_MAXIMUM_DEPTH = 3;

    public static final double DEFAULT_SHORTEST_LEAF_EDGE_LENGTHS = 2.0;

    public static final double STRAIGHT_HANDLE_FRACTION = 1.0 / 3.0;

    public static final double CIRCLE_HANDLE_SCALE = 4.0 / 3.0;

    public static final double SMALL_TURN_RADIANS = 1e-4;

    public static final double LARGEST_TURN_RADIANS = 1.4;

    /** Geodesic engine every average and every leaf is computed with. */
    public SurfaceGeodesics geodesics;

    /**
     * Whether the spline runs from its first anchor to its last without closing: each end's
     * tangent follows its one chord, and no segment returns to the first anchor.
     */
    public boolean open;

    /**
     * For an {@link #open} spline, the vertex its first anchor's tangent takes as the anchor
     * before it, as a closed ring's would, or -1 to follow the first chord alone.
     */
    public int leadInVertexId = -1;

    /** For an {@link #open} spline, the vertex after its last anchor, as {@link #leadInVertexId}. */
    public int leadOutVertexId = -1;

    /** Bisections a segment may reach. */
    public int maximumDepth = DEFAULT_MAXIMUM_DEPTH;

    /** Mean edge lengths a leaf must keep, the floor bisection may not go under. */
    public double shortestLeafInEdges = DEFAULT_SHORTEST_LEAF_EDGE_LENGTHS;

    /** Mesh vertex the spline passes through, per anchor, in ring order. */
    public int[] anchorVertexId = new int[0];

    /** Anchors held in the front of {@link #anchorVertexId}. */
    public int anchorCount;

    /**
     * Whether each anchor was placed by the user rather than by the fit, parallel to
     * {@link #anchorVertexId}; only authored anchors are selectable and saved.
     */
    public boolean[] anchorAuthored = new boolean[0];

    /** Unit tangent of the spline at each anchor, the two handles lie along it. */
    public PackedPoints anchorTangent = new PackedPoints(0);

    /** Vertex the outgoing handle of each segment's first anchor sits on. */
    public int[] outgoingHandleVertexId = new int[0];

    /** Vertex the incoming handle of each segment's last anchor sits on. */
    public int[] incomingHandleVertexId = new int[0];

    /** Each segment's traced points, its last anchor excluded. */
    public PackedPoints[] segmentXyz = new PackedPoints[0];

    /** Each segment's traced points on the surface, under {@link #segmentXyz}. */
    public PackedPoints[] segmentSurfaceXyz = new PackedPoints[0];

    /** Mesh vertex id per traced point of each segment, -1 elsewhere. */
    public int[][] segmentVertexId = new int[0][];

    /** Crossed mesh edge id per traced point of each segment, -1 elsewhere. */
    public int[][] segmentEdgeId = new int[0][];

    /** Mesh face id per traced point of each segment strictly inside a polygon, -1 elsewhere. */
    public int[][] segmentFaceId = new int[0][];

    /** Crossing parameter per traced point of each segment, -1 off an edge. */
    public double[][] segmentFraction = new double[0][];

    /** Bisection depth each segment was traced at. */
    public int[] segmentDepth = new int[0];

    private final Vector3f anchorPosition = new Vector3f();
    private final Vector3f previousPosition = new Vector3f();
    private final Vector3f nextPosition = new Vector3f();
    private final Vector3f incoming = new Vector3f();
    private final Vector3f outgoing = new Vector3f();
    private int[] controlVertex = new int[0];
    private PackedPoints controls = new PackedPoints(0);
    private int[] midpointVertex = new int[0];
    private PackedPoints midpoints = new PackedPoints(0);
    private final PackedPoints emittedXyz = new PackedPoints();
    private int[] emittedVertexId = new int[0];
    private final PackedPoints emittedSurfaceXyz = new PackedPoints();
    private int[] emittedEdgeId = new int[0];
    private int[] emittedFaceId = new int[0];
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
     * Replaces the anchor cycle with anchors that are all authored and traces every segment.
     *
     * @param vertexIds mesh vertices the spline passes through, in ring order
     * @param count     anchors to take from the front of {@code vertexIds}
     * @return true when at least three anchors were accepted and traced
     */
    public boolean setAnchors(int[] vertexIds, int count) {
        boolean[] authored = new boolean[Math.max(0, count)];
        Arrays.fill(authored, true);
        return setAnchors(vertexIds, authored, count);
    }

    /**
     * Replaces the anchor cycle and traces every segment.
     *
     * @param vertexIds mesh vertices the spline passes through, in ring order
     * @param authored  whether each anchor was placed by the user, in the same order
     * @param count     anchors to take from the front of {@code vertexIds}
     * @return true when at least three anchors, or two for an {@link #open} spline, were
     *         accepted and traced
     */
    public boolean setAnchors(int[] vertexIds, boolean[] authored, int count) {
        if (count < (open ? 2 : MINIMUM_ANCHORS)) {
            anchorCount = 0;
            return false;
        }
        anchorVertexId = Arrays.copyOf(vertexIds, count);
        anchorAuthored = Arrays.copyOf(authored, count);
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
     * @param authored     whether the user placed the anchor rather than the fit
     * @return true when the anchor was inserted
     */
    public boolean insertAnchor(int beforeAnchor, int vertexId, boolean authored) {
        if (anchorCount < MINIMUM_ANCHORS || beforeAnchor < 0 || beforeAnchor > anchorCount) {
            return false;
        }
        int[] grown = new int[anchorCount + 1];
        System.arraycopy(anchorVertexId, 0, grown, 0, beforeAnchor);
        grown[beforeAnchor] = vertexId;
        System.arraycopy(anchorVertexId, beforeAnchor, grown, beforeAnchor + 1,
                anchorCount - beforeAnchor);
        anchorVertexId = grown;
        boolean[] grownAuthored = new boolean[anchorCount + 1];
        System.arraycopy(anchorAuthored, 0, grownAuthored, 0, beforeAnchor);
        grownAuthored[beforeAnchor] = authored;
        System.arraycopy(anchorAuthored, beforeAnchor, grownAuthored, beforeAnchor + 1,
                anchorCount - beforeAnchor);
        anchorAuthored = grownAuthored;
        anchorCount++;
        int[] keptDepth = segmentDepth;
        PackedPoints[] keptXyz = segmentXyz;
        int[][] keptVertexId = segmentVertexId;
        PackedPoints[] keptSurfaceXyz = segmentSurfaceXyz;
        int[][] keptEdgeId = segmentEdgeId;
        int[][] keptFaceId = segmentFaceId;
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
            segmentSurfaceXyz[segment] = keptSurfaceXyz[source];
            segmentEdgeId[segment] = keptEdgeId[source];
            segmentFaceId[segment] = keptFaceId[source];
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
        if (open && segment == anchorCount - 1) {
            segmentXyz[segment] = new PackedPoints(0);
            segmentVertexId[segment] = new int[0];
            segmentSurfaceXyz[segment] = new PackedPoints(0);
            segmentEdgeId[segment] = new int[0];
            segmentFaceId[segment] = new int[0];
            segmentFraction[segment] = new double[0];
            segmentDepth[segment] = 0;
            return;
        }
        setHandles(segment);
        prepareRecursionStack();
        int[] seed = {
            anchorVertexId[segment], outgoingHandleVertexId[segment],
            incomingHandleVertexId[segment], anchorVertexId[(segment + 1) % anchorCount] };
        for (int control = 0; control < CONTROL_POINTS; control++) {
            controlVertex[control] = seed[control];
            controls.set(control, geodesics.mesh.vertexPosition(seed[control], anchorPosition));
        }
        emittedCount = 0;
        emittedXyz.resize(0);
        emittedSurfaceXyz.resize(0);
        emittedDepth = 0;
        subdivide(0, depthFor(segment));
        segmentXyz[segment] = emittedXyz.copy();
        segmentVertexId[segment] = Arrays.copyOf(emittedVertexId, emittedCount);
        segmentSurfaceXyz[segment] = emittedSurfaceXyz.copy();
        segmentEdgeId[segment] = Arrays.copyOf(emittedEdgeId, emittedCount);
        segmentFaceId[segment] = Arrays.copyOf(emittedFaceId, emittedCount);
        segmentFraction[segment] = Arrays.copyOf(emittedFraction, emittedCount);
        segmentDepth[segment] = emittedDepth;
    }

    /**
     * The whole spline as one traced path, the form the conforming edge-cycle snap and the ring
     * overlay both read: closed, or for an {@link #open} spline ending on its last anchor.
     *
     * @return the path, or an empty one before any anchors are set
     */
    public TracedSurfacePath tracedRing() {
        int total = open && anchorCount > 0 ? 1 : 0;
        for (int segment = 0; segment < anchorCount; segment++) {
            total += segmentVertexId[segment].length;
        }
        PackedPoints positions = new PackedPoints(total);
        PackedPoints surfacePositions = new PackedPoints(total);
        int[] vertexId = new int[total];
        int[] edgeId = new int[total];
        int[] faceId = new int[total];
        double[] fraction = new double[total];
        int cursor = 0;
        for (int segment = 0; segment < anchorCount; segment++) {
            int points = segmentVertexId[segment].length;
            positions.slice(cursor, points).copyFrom(segmentXyz[segment]);
            surfacePositions.slice(cursor, points).copyFrom(segmentSurfaceXyz[segment]);
            System.arraycopy(segmentVertexId[segment], 0, vertexId, cursor, points);
            System.arraycopy(segmentEdgeId[segment], 0, edgeId, cursor, points);
            System.arraycopy(segmentFaceId[segment], 0, faceId, cursor, points);
            System.arraycopy(segmentFraction[segment], 0, fraction, cursor, points);
            cursor += points;
        }
        if (open && anchorCount > 0) {
            geodesics.mesh.vertexPosition(anchorVertexId[anchorCount - 1], anchorPosition);
            positions.set(cursor, anchorPosition);
            surfacePositions.set(cursor, anchorPosition);
            vertexId[cursor] = anchorVertexId[anchorCount - 1];
            edgeId[cursor] = -1;
            faceId[cursor] = -1;
            fraction[cursor] = -1.0;
        }
        return new TracedSurfacePath(positions, surfacePositions, vertexId, edgeId, faceId,
                fraction, !open);
    }

    private void prepareRecursionStack() {
        int levels = Math.max(1, maximumDepth) + 2;
        if (controlVertex.length >= CONTROL_POINTS * levels) {
            return;
        }
        controlVertex = new int[CONTROL_POINTS * levels];
        controls = new PackedPoints(CONTROL_POINTS * levels);
        midpointVertex = new int[MIDPOINTS_PER_BISECTION * levels];
        midpoints = new PackedPoints(MIDPOINTS_PER_BISECTION * levels);
    }

    private void resizeSegments() {
        segmentXyz = Arrays.copyOf(segmentXyz, anchorCount);
        segmentVertexId = Arrays.copyOf(segmentVertexId, anchorCount);
        segmentSurfaceXyz = Arrays.copyOf(segmentSurfaceXyz, anchorCount);
        segmentEdgeId = Arrays.copyOf(segmentEdgeId, anchorCount);
        segmentFaceId = Arrays.copyOf(segmentFaceId, anchorCount);
        segmentFraction = Arrays.copyOf(segmentFraction, anchorCount);
        segmentDepth = Arrays.copyOf(segmentDepth, anchorCount);
        for (int segment = 0; segment < anchorCount; segment++) {
            if (segmentXyz[segment] == null) {
                segmentXyz[segment] = new PackedPoints(0);
                segmentSurfaceXyz[segment] = new PackedPoints(0);
                segmentVertexId[segment] = new int[0];
                segmentEdgeId[segment] = new int[0];
                segmentFaceId[segment] = new int[0];
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
        anchorTangent = new PackedPoints(anchorCount);
        MeshTopology mesh = geodesics.mesh;
        for (int anchor = 0; anchor < anchorCount; anchor++) {
            mesh.vertexPosition(anchorVertexId[anchor], anchorPosition);
            mesh.vertexPosition(anchorVertexId[Math.floorMod(anchor - 1, anchorCount)],
                    previousPosition);
            mesh.vertexPosition(anchorVertexId[(anchor + 1) % anchorCount], nextPosition);
            if (open && anchor == 0) {
                previousPosition.set(anchorPosition);
                if (leadInVertexId >= 0) {
                    mesh.vertexPosition(leadInVertexId, previousPosition);
                }
            }
            if (open && anchor == anchorCount - 1) {
                nextPosition.set(anchorPosition);
                if (leadOutVertexId >= 0) {
                    mesh.vertexPosition(leadOutVertexId, nextPosition);
                }
            }
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
            anchorTangent.set(anchor, incoming);
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
        Vector3f tangent = anchorTangent.at(anchor);
        double turn = tangent.angle(outgoing);
        double length = turn < SMALL_TURN_RADIANS
                ? STRAIGHT_HANDLE_FRACTION * chord
                : CIRCLE_HANDLE_SCALE * Math.tan(Math.min(turn, LARGEST_TURN_RADIANS) / 2.0)
                        * chord / (2.0 * Math.sin(Math.min(turn, LARGEST_TURN_RADIANS)));
        return geodesics.walkFrom(anchorVertexId[anchor], tangent.mul(sign), length);
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
                Math.max(shortestLeafInEdges * geodesics.metric.meanEdgeLength,
                        Double.MIN_NORMAL);
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
            average(controlVertex[control + pair], controls.at(control + pair),
                    controlVertex[control + pair + 1], controls.at(control + pair + 1), middle + pair);
        }
        for (int pair = 0; pair < CONTROL_POINTS - 2; pair++) {
            average(midpointVertex[middle + pair], midpoints.at(middle + pair),
                    midpointVertex[middle + pair + 1], midpoints.at(middle + pair + 1),
                    middle + CONTROL_POINTS - 1 + pair);
        }
        average(midpointVertex[middle + CONTROL_POINTS - 1], midpoints.at(middle + CONTROL_POINTS - 1),
                midpointVertex[middle + CONTROL_POINTS], midpoints.at(middle + CONTROL_POINTS),
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
    private void average(int fromVertexId, Vector3f from, int toVertexId, Vector3f to, int slot) {
        if (fromVertexId == toVertexId || !geodesics.geodesic(fromVertexId, toVertexId)) {
            midpointVertex[slot] = fromVertexId;
            midpoints.set(slot, from);
            return;
        }
        Vector3f middle = geodesics.pointAtFraction(0.5);
        int vertexId = geodesics.vertexAtFraction(0.5);
        midpointVertex[slot] = vertexId < 0 ? fromVertexId : vertexId;
        geodesics.mesh.vertexPosition(fromVertexId, previousPosition);
        geodesics.mesh.vertexPosition(toVertexId, nextPosition);
        Vector3f offset = from.sub(previousPosition).add(to).sub(nextPosition);
        midpoints.set(slot, middle.fma(0.5f, offset));
    }

    private void copyControl(int fromSlot, int toSlot) {
        controlVertex[toSlot] = controlVertex[fromSlot];
        controls.set(toSlot, controls.at(fromSlot));
    }

    private void copyMidpoint(int fromSlot, int toSlot) {
        controlVertex[toSlot] = midpointVertex[fromSlot];
        controls.set(toSlot, midpoints.at(fromSlot));
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
        Vector3f start = controls.at(control);
        if (fromVertexId == toVertexId || !geodesics.geodesic(fromVertexId, toVertexId)) {
            appendPoint(start, fromVertexId, -1);
            return;
        }
        Vector3f outHandle = controls.at(control + 1);
        Vector3f inHandle = controls.at(control + 2);
        Vector3f end = controls.at(control + 3);
        for (int point = 0; point < geodesics.tracedPointCount - 1; point++) {
            double along = geodesics.pathLength <= 0.0 ? 0.0
                    : geodesics.tracedArcLength[point] / geodesics.pathLength;
            double first = (1.0 - along) * (1.0 - along) * (1.0 - along);
            double second = 3.0 * along * (1.0 - along) * (1.0 - along);
            double third = 3.0 * along * along * (1.0 - along);
            double fourth = along * along * along;
            appendPoint(new Vector3f(
                    (float) (first * start.x + second * outHandle.x + third * inHandle.x + fourth * end.x),
                    (float) (first * start.y + second * outHandle.y + third * inHandle.y + fourth * end.y),
                    (float) (first * start.z + second * outHandle.z + third * inHandle.z + fourth * end.z)),
                    geodesics.tracedVertexId[point], point);
        }
    }

    /**
     * Appends one sample of the cubic over the point of the last geodesic it stands for, or over
     * a bare vertex when {@code tracedPoint} is -1.
     */
    private void appendPoint(Vector3f position, int vertexId, int tracedPoint) {
        ensureEmitCapacity();
        emittedXyz.append(position);
        emittedVertexId[emittedCount] = vertexId;
        if (tracedPoint < 0) {
            emittedSurfaceXyz.append(geodesics.mesh.vertexPosition(vertexId, anchorPosition));
            emittedEdgeId[emittedCount] = -1;
            emittedFaceId[emittedCount] = -1;
            emittedFraction[emittedCount] = -1.0;
        } else {
            emittedSurfaceXyz.append(geodesics.tracedXyz.at(tracedPoint));
            emittedEdgeId[emittedCount] = geodesics.tracedEdgeId[tracedPoint];
            emittedFaceId[emittedCount] = geodesics.tracedFaceId[tracedPoint];
            emittedFraction[emittedCount] = geodesics.tracedFraction[tracedPoint];
        }
        emittedCount++;
    }

    private void ensureEmitCapacity() {
        if (emittedCount < emittedVertexId.length) {
            return;
        }
        int grown = Math.max(256, 2 * emittedVertexId.length);
        emittedVertexId = Arrays.copyOf(emittedVertexId, grown);
        emittedEdgeId = Arrays.copyOf(emittedEdgeId, grown);
        emittedFaceId = Arrays.copyOf(emittedFaceId, grown);
        emittedFraction = Arrays.copyOf(emittedFraction, grown);
    }
}
