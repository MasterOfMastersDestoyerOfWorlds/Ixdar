package ixdar.geometry.mesh.data.paths.spline;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.bundle.RingBundle;
import ixdar.geometry.mesh.data.paths.geodesic.ConformingLoopSnap;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceMetric;
import ixdar.geometry.mesh.data.paths.geodesic.TracedSurfacePath;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.RingAttachment;

/**
 * A cubic spline on a surface, a closed ring or an open bisector: its anchors, its traced path and
 * the mesh edges nearest it. Each anchor's two tangent handles are collinear, so it turns smoothly.
 */
public final class SurfaceSpline {

    public static final int COORDINATES_PER_POINT = 3;

    public static final double SMALLEST_MEASURED_SPAN_IN_EDGES = 0.25;

    public static final int GRAZING_STEPS = 4;

    public static final String SLOT = "_surface_splines";

    /** Packed xyz of the anchors, in ring order. */
    public float[] anchorXyz = new float[0];

    /** Mesh vertex each anchor snapped to, in ring order. */
    public int[] anchorVertexId = new int[0];

    /** Anchors the spline passes through. */
    public int anchorCount;

    /** Whether the user placed each anchor rather than the fit, in ring order. */
    public boolean[] anchorAuthored = new boolean[0];

    /** Surface the spline lies on, which every point and edge id here indexes; null before. */
    public MeshTopology mesh;

    /**
     * The traced spline, measured: its positions are the Euclidean cubic, off the surface, and its
     * surface positions the vertex, geodesic edge crossing or in-face point each stands for.
     * Consecutive points share a face. Open for a bisector from its first anchor to its last.
     */
    public TracedSurfacePath path = new TracedSurfacePath(new double[0], new int[0], new int[0],
            new double[0], 0, true);

    /** The mesh edges nearest the spline: a cycle for a ring, the walled run for a bisector. */
    public EdgeMarks marks = EdgeMarks.NONE;

    /** Smallest interior angle of the traced polyline, in degrees; 180 is perfectly straight. */
    public double minimumInteriorAngleDegrees = 180.0;

    /** Deepest bisection any segment needed. */
    public int traceDepth;

    /** Geodesics the trace computed, the cost measure a hover budget is spent on. */
    public long geodesicCount;

    /** Shortest polyline span the interior angles were measured across. */
    public double smallestMeasuredSpan;

    /** Packed xyz of the path point {@link #minimumInteriorAngleDegrees} was measured at. */
    public final double[] sharpestCornerXyz = new double[COORDINATES_PER_POINT];

    private SurfaceSpline() {
    }

    /**
     * Traces the spline through authored surface points on a mesh, building its own triangulation.
     * Callers that trace repeatedly should keep a {@link SurfaceSplineTracer} instead.
     *
     * @param mesh        surface the spline lies on
     * @param packedXyz   authored anchor coordinates, three floats per anchor
     * @param anchorCount anchors to use from the front of {@code packedXyz}
     * @throws IllegalArgumentException when fewer than three anchors are given
     * @return the traced spline
     */
    public static SurfaceSpline through(MeshTopology mesh, float[] packedXyz, int anchorCount) {
        if (anchorCount < SurfaceSplineTracer.MINIMUM_ANCHORS) {
            throw new IllegalArgumentException("a closed spline needs at least "
                    + SurfaceSplineTracer.MINIMUM_ANCHORS + " anchors, got " + anchorCount);
        }
        SurfaceMetric metric = SurfaceMetric.of(mesh);
        int[] vertexIds = SurfaceWaypoints.snap(metric.nearestVertex, packedXyz, anchorCount);
        SurfaceSplineTracer tracer = new SurfaceSplineTracer(SurfaceGeodesics.over(metric));
        tracer.setAnchors(vertexIds, anchorCount);
        return of(tracer);
    }

    /**
     * The finished value of an already-traced tracer, the form the tool and the node both publish.
     *
     * @param tracer tracer whose segments are up to date
     * @return the spline the tracer currently holds
     */
    public static SurfaceSpline of(SurfaceSplineTracer tracer) {
        SurfaceSpline spline = unsnapped(tracer);
        spline.marks = new ConformingLoopSnap().edgeLoop(tracer.geodesics.mesh, spline.path);
        spline.measureCorners(tracer.geodesics.metric.meanEdgeLength);
        return spline;
    }

    /**
     * A bisector through authored anchors whose first and last sit on walls: the open geodesic
     * spline, walled by the longest run of its snapped edge path touching those walls only at its
     * ends. Walls it crosses on the way do not stop it.
     *
     * @param geodesics       engine over the surface the bisector lies on
     * @param anchorVertexIds mesh vertices it passes through in order, both ends on walls
     * @param startWall       edge-id-indexed wall the first anchor sits on
     * @param endWall         edge-id-indexed wall the last anchor sits on, possibly the same
     * @return the bisector, with no marked edge when its snapped path does not join its two ends
     */
    public static SurfaceSpline bisector(SurfaceGeodesics geodesics, int[] anchorVertexIds,
            boolean[] startWall, boolean[] endWall) {
        MeshTopology mesh = geodesics.mesh;
        SurfaceSplineTracer tracer = new SurfaceSplineTracer(geodesics);
        tracer.open = true;
        boolean[] authored = new boolean[anchorVertexIds.length];
        Arrays.fill(authored, true);
        boolean traced = tracer.setAnchors(anchorVertexIds, authored, anchorVertexIds.length);
        SurfaceSpline spline = unsnapped(tracer);
        boolean[] marks = new boolean[RingBundle.edgeIdCeiling(mesh)];
        boolean[] snapped = new boolean[0];
        int unresolvedGaps = 0;
        if (traced) {
            ConformingLoopSnap snap = new ConformingLoopSnap();
            snapped = snap.snap(mesh, spline.path);
            unresolvedGaps = snap.unresolvedGaps;
        }
        // The walk runs over the snapped edges' own vertices, sorted so a vertex finds its slot
        // by binary search, which keeps its arrays the size of the path, not of the mesh.
        int[] vertices = new int[0];
        int vertexCount = 0;
        for (int edgeId = 0; edgeId < snapped.length; edgeId++) {
            if (!snapped[edgeId]) {
                continue;
            }
            vertices = vertexCount + 2 > vertices.length
                    ? Arrays.copyOf(vertices, 2 * vertexCount + 2) : vertices;
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            vertices[vertexCount++] = mesh.halfEdgeVertex(halfEdge);
            vertices[vertexCount++] = mesh.halfEdgeEndVertex(halfEdge);
        }
        vertices = Arrays.stream(vertices, 0, vertexCount).sorted().distinct().toArray();
        int[] parent = new int[vertices.length];
        Arrays.fill(parent, -1);
        int[] queue = new int[vertices.length];
        int from = traced ? Arrays.binarySearch(vertices, anchorVertexIds[0]) : -1;
        int to = traced ? Arrays.binarySearch(vertices, anchorVertexIds[anchorVertexIds.length - 1])
                : -1;
        int tail = 0;
        if (from >= 0 && to >= 0) {
            parent[from] = from;
            queue[tail++] = from;
        }
        // The fewest-edge walk over the snapped edges drops the spurs and loops a snap of an open
        // curve leaves where consecutive points fall back onto a vertex already passed.
        for (int head = 0; head < tail && parent[to] < 0; head++) {
            int vertexId = vertices[queue[head]];
            for (int spoke = 0; spoke < mesh.vertexEdgeCount(vertexId); spoke++) {
                int edgeId = mesh.vertexEdgeAt(vertexId, spoke);
                if (edgeId >= snapped.length || !snapped[edgeId]) {
                    continue;
                }
                int other = Arrays.binarySearch(vertices, mesh.edgeOtherVertex(edgeId, vertexId));
                if (parent[other] < 0) {
                    parent[other] = queue[head];
                    queue[tail++] = other;
                }
            }
        }
        int steps = 0;
        for (int slot = to; to >= 0 && parent[to] >= 0 && slot != from; slot = parent[slot]) {
            queue[steps++] = vertices[slot];
        }
        if (to >= 0 && parent[to] >= 0) {
            queue[steps++] = vertices[from];
        }
        // The walk runs end to start in the queue. Near each end it may run along its wall, or
        // touch it again a few edges on, which would pinch a pocket of faces off: each end moves
        // in to the last such touch. A crossing, of its own wall or any other, is kept.
        int runFrom = 0;
        int runTo = steps - 1;
        for (int side = 0; side < 2 && steps >= 2; side++) {
            boolean[] wall = side == 0 ? endWall : startWall;
            int direction = side == 0 ? 1 : -1;
            int last = side == 0 ? 0 : steps - 1;
            for (int step = last + direction; step > 0 && step < steps - 1
                    && Math.abs(step - last) <= GRAZING_STEPS; step += direction) {
                int vertexId = queue[step];
                boolean onWall = false;
                for (int spoke = 0; !onWall && spoke < mesh.vertexEdgeCount(vertexId); spoke++) {
                    int edgeId = mesh.vertexEdgeAt(vertexId, spoke);
                    onWall = edgeId < wall.length && wall[edgeId];
                }
                if (onWall && RingAttachment.crossesAt(mesh, vertexId,
                        mesh.edgeBetween(queue[step - direction], vertexId),
                        mesh.edgeBetween(vertexId, queue[step + direction]), wall)) {
                    break;
                }
                last = onWall ? step : last;
            }
            runFrom = side == 0 ? last : runFrom;
            runTo = side == 1 ? last : runTo;
        }
        for (int step = runFrom; step < runTo; step++) {
            marks[mesh.edgeBetween(queue[step], queue[step + 1])] = true;
        }
        spline.marks = new EdgeMarks(marks, unresolvedGaps);
        spline.measureCorners(geodesics.metric.meanEdgeLength);
        return spline;
    }

    /**
     * The drawn parts of a traced tracer only, its anchors in both classes and its measured path,
     * without the edge-cycle snap and corner measure a hover frame has no use for.
     *
     * @param tracer tracer whose segments are up to date
     * @return the spline with no edge marks
     */
    public static SurfaceSpline unsnapped(SurfaceSplineTracer tracer) {
        SurfaceSpline spline = new SurfaceSpline();
        MeshTopology mesh = tracer.geodesics.mesh;
        spline.anchorCount = tracer.anchorCount;
        spline.anchorAuthored = Arrays.copyOf(tracer.anchorAuthored, tracer.anchorCount);
        spline.anchorVertexId = new int[tracer.anchorCount];
        spline.anchorXyz = new float[COORDINATES_PER_POINT * tracer.anchorCount];
        Vector3f position = new Vector3f();
        for (int anchor = 0; anchor < tracer.anchorCount; anchor++) {
            spline.anchorVertexId[anchor] = tracer.anchorVertexId[anchor];
            mesh.vertexPosition(tracer.anchorVertexId[anchor], position);
            spline.anchorXyz[COORDINATES_PER_POINT * anchor] = position.x;
            spline.anchorXyz[COORDINATES_PER_POINT * anchor + 1] = position.y;
            spline.anchorXyz[COORDINATES_PER_POINT * anchor + 2] = position.z;
        }
        for (int segment = 0; segment < tracer.anchorCount; segment++) {
            spline.traceDepth = Math.max(spline.traceDepth, tracer.segmentDepth[segment]);
        }
        spline.geodesicCount = tracer.geodesics.geodesicCount;
        spline.mesh = mesh;
        spline.path = tracer.tracedRing().measure();
        return spline;
    }

    /**
     * A copy of a bundle carrying a spline under a label in {@link #SLOT}, beside the edge marks
     * of the same label, so a consumer can cut along the spline rather than its snapped loop.
     *
     * @param bundle bundle to copy
     * @param label  ring label, the spline's edge-marks label
     * @param spline traced spline on the bundle's surface
     * @return the bundle copy
     */
    public static GeometryBundle with(GeometryBundle bundle, String label, SurfaceSpline spline) {
        Map<String, SurfaceSpline> all = new LinkedHashMap<>();
        if (bundle.slots().get(SLOT) instanceof Map<?, ?> existing) {
            for (Map.Entry<?, ?> entry : existing.entrySet()) {
                if (entry.getValue() instanceof SurfaceSpline kept) {
                    all.put(String.valueOf(entry.getKey()), kept);
                }
            }
        }
        all.put(label, spline);
        return bundle.withSlot(SLOT, all);
    }

    /**
     * The spline a bundle carries under a label, when it was traced on the bundle's own surface.
     *
     * @param bundle bundle to read
     * @param label  ring label
     * @return the spline, or null when absent or traced on another surface
     */
    public static SurfaceSpline inBundle(GeometryBundle bundle, String label) {
        if (bundle.slots().get(SLOT) instanceof Map<?, ?> splines
                && splines.get(label) instanceof SurfaceSpline spline
                && spline.mesh == bundle.mesh()) {
            return spline;
        }
        return null;
    }

    /**
     * How many of the anchors the user placed, the ones a save writes.
     *
     * @return the authored anchors among {@link #anchorAuthored}
     */
    public int authoredCount() {
        int authored = 0;
        for (int anchor = 0; anchor < anchorCount; anchor++) {
            authored += anchorAuthored[anchor] ? 1 : 0;
        }
        return authored;
    }

    /**
     * Distance from a point to a closed polyline, measured against its spans so a coarse reference
     * does not overstate the gap. Spans whose bounding box is already further off are skipped,
     * which leaves the minimum exactly where scanning all of them would.
     *
     * @param packedXyz  packed xyz of the polyline
     * @param pointCount points to read from the front of {@code packedXyz}
     * @param x          point x
     * @param y          point y
     * @param z          point z
     * @return the shortest distance, or infinity when the polyline is empty
     */
    public static double distanceToPolyline(double[] packedXyz, int pointCount, double x,
            double y, double z) {
        double best = Double.POSITIVE_INFINITY;
        for (int point = 0; point < pointCount; point++) {
            best = Math.min(best, spanDistanceSquared(packedXyz, pointCount, point, x, y, z, best));
        }
        return Math.sqrt(best);
    }

    /**
     * Squared distance from a point to one span of a closed polyline, the span from point
     * {@code point} to the next, or infinity when an axis gap alone already exceeds {@code bound}.
     *
     * @param packedXyz  packed xyz of the polyline, treated as closed
     * @param pointCount points in the polyline
     * @param point      the span's first point
     * @param x          query x
     * @param y          query y
     * @param z          query z
     * @param bound      squared distance past which the span is not measured
     * @return the squared distance, or positive infinity when the span was culled
     */
    public static double spanDistanceSquared(double[] packedXyz, int pointCount, int point,
            double x, double y, double z, double bound) {
        int here = COORDINATES_PER_POINT * point;
        int there = COORDINATES_PER_POINT * ((point + 1) % pointCount);
        double gapX = axisGap(x, packedXyz[here], packedXyz[there]);
        double gapY = axisGap(y, packedXyz[here + 1], packedXyz[there + 1]);
        double gapZ = axisGap(z, packedXyz[here + 2], packedXyz[there + 2]);
        if (gapX * gapX + gapY * gapY + gapZ * gapZ > bound) {
            return Double.POSITIVE_INFINITY;
        }
        double spanX = packedXyz[there] - packedXyz[here];
        double spanY = packedXyz[there + 1] - packedXyz[here + 1];
        double spanZ = packedXyz[there + 2] - packedXyz[here + 2];
        double squared = spanX * spanX + spanY * spanY + spanZ * spanZ;
        double along = squared <= 0.0 ? 0.0
                : ((x - packedXyz[here]) * spanX + (y - packedXyz[here + 1]) * spanY
                        + (z - packedXyz[here + 2]) * spanZ) / squared;
        along = Math.max(0.0, Math.min(1.0, along));
        double dx = x - (packedXyz[here] + along * spanX);
        double dy = y - (packedXyz[here + 1] + along * spanY);
        double dz = z - (packedXyz[here + 2] + along * spanZ);
        return dx * dx + dy * dy + dz * dz;
    }

    /** How far a coordinate lies outside the range two endpoints span on one axis. */
    private static double axisGap(double at, double from, double to) {
        double low = Math.min(from, to);
        double high = Math.max(from, to);
        if (at < low) {
            return low - at;
        }
        return at > high ? at - high : 0.0;
    }

    /**
     * The sharpest turn anywhere on the traced polyline, its first point standing in for a closed
     * path's last. Points closer together than a whisker of the ring are skipped, so a pair of
     * coincident edge crossings does not read as a corner.
     */
    private void measureCorners(double meanEdgeLength) {
        smallestMeasuredSpan = SMALLEST_MEASURED_SPAN_IN_EDGES * meanEdgeLength;
        int points = path.closed ? path.pointCount : path.pointCount - 1;
        if (points < COORDINATES_PER_POINT) {
            return;
        }
        double[] xyz = path.positions;
        double floor = smallestMeasuredSpan;
        for (int point = 0; point < points; point++) {
            int previous = stepBack(point, points, floor);
            int following = stepForward(point, points, floor);
            if (previous < 0 || following < 0) {
                continue;
            }
            int here = COORDINATES_PER_POINT * point;
            double backX = xyz[COORDINATES_PER_POINT * previous] - xyz[here];
            double backY = xyz[COORDINATES_PER_POINT * previous + 1] - xyz[here + 1];
            double backZ = xyz[COORDINATES_PER_POINT * previous + 2] - xyz[here + 2];
            double aheadX = xyz[COORDINATES_PER_POINT * following] - xyz[here];
            double aheadY = xyz[COORDINATES_PER_POINT * following + 1] - xyz[here + 1];
            double aheadZ = xyz[COORDINATES_PER_POINT * following + 2] - xyz[here + 2];
            double backLength = Math.sqrt(backX * backX + backY * backY + backZ * backZ);
            double aheadLength = Math.sqrt(aheadX * aheadX + aheadY * aheadY + aheadZ * aheadZ);
            if (backLength <= 0.0 || aheadLength <= 0.0) {
                continue;
            }
            double cosine = (backX * aheadX + backY * aheadY + backZ * aheadZ)
                    / (backLength * aheadLength);
            double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cosine))));
            if (angle < minimumInteriorAngleDegrees) {
                minimumInteriorAngleDegrees = angle;
                System.arraycopy(xyz, here, sharpestCornerXyz, 0, COORDINATES_PER_POINT);
            }
        }
    }

    private int stepBack(int point, int points, double floor) {
        for (int step = 1; step < points; step++) {
            int candidate = Math.floorMod(point - step, points);
            if (separation(point, candidate) > floor) {
                return candidate;
            }
        }
        return -1;
    }

    private int stepForward(int point, int points, double floor) {
        for (int step = 1; step < points; step++) {
            int candidate = (point + step) % points;
            if (separation(point, candidate) > floor) {
                return candidate;
            }
        }
        return -1;
    }

    private double separation(int firstPoint, int secondPoint) {
        double[] xyz = path.positions;
        double dx = xyz[COORDINATES_PER_POINT * firstPoint] - xyz[COORDINATES_PER_POINT * secondPoint];
        double dy = xyz[COORDINATES_PER_POINT * firstPoint + 1]
                - xyz[COORDINATES_PER_POINT * secondPoint + 1];
        double dz = xyz[COORDINATES_PER_POINT * firstPoint + 2]
                - xyz[COORDINATES_PER_POINT * secondPoint + 2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
