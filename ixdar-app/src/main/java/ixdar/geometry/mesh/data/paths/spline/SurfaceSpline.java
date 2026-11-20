package ixdar.geometry.mesh.data.paths.spline;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.IntStream;

import org.joml.Vector3d;
import org.joml.Vector3f;

import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.data.representation.TriangleGeometry;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.bundle.RingBundle;
import ixdar.geometry.mesh.data.paths.geodesic.ConformingLoopSnap;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceMetric;
import ixdar.geometry.mesh.data.paths.geodesic.TracedSurfacePath;
import ixdar.geometry.mesh.data.paths.query.SurfaceCreases;
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

    /** Position of each anchor, in ring order. */
    public PackedPoints anchorPositions = new PackedPoints(0);

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
    public TracedSurfacePath path = new TracedSurfacePath(new PackedPoints(0), new int[0], new int[0],
            new double[0], true);

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

    /** The path point {@link #minimumInteriorAngleDegrees} was measured at. */
    public final Vector3f sharpestCornerXyz = new Vector3f();

    private SurfaceSpline() {
    }

    /**
     * Traces the spline through authored surface points on a mesh, building its own triangulation.
     * Callers that trace repeatedly should keep a {@link SurfaceSplineTracer} instead.
     *
     * @param mesh        surface the spline lies on
     * @param anchors     authored anchor points
     * @param anchorCount anchors to use from the front of {@code anchors}
     * @throws IllegalArgumentException when fewer than three anchors are given
     * @return the traced spline
     */
    public static SurfaceSpline through(MeshTopology mesh, PackedPoints anchors, int anchorCount) {
        if (anchorCount < SurfaceSplineTracer.MINIMUM_ANCHORS) {
            throw new IllegalArgumentException("a closed spline needs at least "
                    + SurfaceSplineTracer.MINIMUM_ANCHORS + " anchors, got " + anchorCount);
        }
        SurfaceMetric metric = SurfaceMetric.of(mesh);
        int[] vertexIds = SurfaceWaypoints.snap(metric.nearestVertex, anchors, anchorCount);
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
     * A bisector through anchors whose ends sit on walls, the open geodesic spline or the cheapest
     * crease path: the longest run of its edge path touching those walls only at its ends.
     *
     * @param geodesics       engine over the surface the bisector lies on
     * @param creases         groove cost of that surface the crease mode follows, or {@code null}
     *                        for the geodesic spline
     * @param anchorVertexIds mesh vertices it passes through in order, both ends on walls
     * @param startWall       edge-id-indexed wall the first anchor sits on
     * @param endWall         edge-id-indexed wall the last anchor sits on, possibly the same
     * @return the bisector, with no marked edge when its edge path does not join its two ends
     */
    public static SurfaceSpline bisector(SurfaceGeodesics geodesics, SurfaceCreases creases,
            int[] anchorVertexIds, boolean[] startWall, boolean[] endWall) {
        MeshTopology mesh = geodesics.mesh;
        SurfaceSplineTracer tracer = new SurfaceSplineTracer(geodesics);
        tracer.open = true;
        boolean[] authored = new boolean[anchorVertexIds.length];
        Arrays.fill(authored, true);
        boolean[] marks = new boolean[RingBundle.edgeIdCeiling(mesh)];
        boolean[] snapped = new boolean[0];
        int unresolvedGaps = 0;
        boolean traced;
        SurfaceSpline spline;
        if (creases == null) {
            traced = tracer.setAnchors(anchorVertexIds, authored, anchorVertexIds.length);
            spline = unsnapped(tracer);
            if (traced) {
                ConformingLoopSnap snap = new ConformingLoopSnap();
                snapped = snap.snap(mesh, spline.path);
                unresolvedGaps = snap.unresolvedGaps;
            }
        } else {
            int[] walk = { anchorVertexIds[0] };
            for (int anchor = 0; anchor + 1 < anchorVertexIds.length && walk.length > 0; anchor++) {
                int[] span = creases.path(anchorVertexIds[anchor], anchorVertexIds[anchor + 1]);
                if (span.length == 0) {
                    walk = new int[0];
                    continue;
                }
                int[] grown = Arrays.copyOf(walk, walk.length + span.length - 1);
                System.arraycopy(span, 1, grown, walk.length, span.length - 1);
                walk = grown;
            }
            traced = walk.length > 1;
            spline = new SurfaceSpline();
            spline.mesh = mesh;
            spline.anchorCount = anchorVertexIds.length;
            spline.anchorAuthored = authored;
            spline.anchorVertexId = Arrays.copyOf(anchorVertexIds, anchorVertexIds.length);
            spline.anchorPositions = new PackedPoints(anchorVertexIds.length);
            PackedPoints walkXyz = new PackedPoints(walk.length);
            int[] noEdge = new int[walk.length];
            double[] noFraction = new double[walk.length];
            Arrays.fill(noEdge, -1);
            Arrays.fill(noFraction, -1.0);
            snapped = new boolean[marks.length];
            Vector3f position = new Vector3f();
            for (int point = 0; point < walk.length; point++) {
                walkXyz.set(point, mesh.vertexPosition(walk[point], position));
                if (point > 0) {
                    snapped[mesh.edgeBetween(walk[point - 1], walk[point])] = true;
                }
            }
            for (int anchor = 0; anchor < anchorVertexIds.length; anchor++) {
                spline.anchorPositions.set(anchor, mesh.vertexPosition(anchorVertexIds[anchor], position));
            }
            spline.path = new TracedSurfacePath(walkXyz, walk, noEdge, noFraction, false).measure();
        }
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
     * A stretch of a closed ring as a bisector, every segment traced as the ring traced it, its
     * walk kept between the touches of its walls nearest the two points where it meets them.
     *
     * @param geodesics       engine over the surface the ring lay on
     * @param anchorVertexIds the ring's anchors in its order, the first and last past the walls
     * @param stretch         the ring's anchor before the first, the vertex where the stretch
     *                        meets its start wall, where it meets its end wall, and the ring's
     *                        anchor after the last
     * @param startWall       edge-id-indexed wall the stretch starts on
     * @param endWall         edge-id-indexed wall it ends on, possibly the same
     * @return the bisector, with no marked edge when its anchors trace nothing
     */
    public static SurfaceSpline ringStretch(SurfaceGeodesics geodesics, int[] anchorVertexIds,
            int[] stretch, boolean[] startWall, boolean[] endWall) {
        MeshTopology mesh = geodesics.mesh;
        SurfaceSplineTracer tracer = new SurfaceSplineTracer(geodesics);
        tracer.open = true;
        tracer.leadInVertexId = stretch[0];
        tracer.leadOutVertexId = stretch[stretch.length - 1];
        boolean traced = tracer.setAnchors(anchorVertexIds, anchorVertexIds.length);
        SurfaceSpline spline = unsnapped(tracer);
        boolean[] marks = new boolean[RingBundle.edgeIdCeiling(mesh)];
        ConformingLoopSnap snap = new ConformingLoopSnap();
        int[] walk = traced ? snap.orderedWalk(mesh, spline.path) : new int[0];
        int steps = walk.length;
        int first = steps - 1;
        int last = 0;
        int inner = anchorVertexIds.length - 1;
        for (int step = steps - 1; step >= 0 && inner > 1; step--) {
            first = walk[step] == anchorVertexIds[1] ? step : first;
        }
        for (int step = first; step < steps && inner > 1; step++) {
            last = walk[step] == anchorVertexIds[inner - 1] ? step : last;
        }
        int from = 0;
        int to = steps - 1;
        Vector3f meeting = new Vector3f();
        Vector3f position = new Vector3f();
        for (int end = 0; end < 2; end++) {
            boolean[] wall = end == 0 ? startWall : endWall;
            mesh.vertexPosition(stretch[1 + end], meeting);
            double nearest = Double.POSITIVE_INFINITY;
            for (int step = end == 0 ? 0 : steps - 1; end == 0 ? step <= first
                    : step >= Math.max(last, from); step += end == 0 ? 1 : -1) {
                double away = walk[step] == stretch[1 + end] ? 0.0
                        : mesh.vertexPosition(walk[step], position).distance(meeting);
                if (away < nearest && EdgeMarks.markedAt(mesh, walk[step], wall, null) > 0) {
                    nearest = away;
                    from = end == 0 ? step : from;
                    to = end == 0 ? to : step;
                }
            }
        }
        int[] kept = new int[Math.max(0, to - from + 1)];
        int keptCount = 0;
        for (int step = from; step <= to && steps > 0; step++) {
            int again = keptCount - 1;
            while (again >= 0 && kept[again] != walk[step]) {
                again--;
            }
            keptCount = again >= 0 ? again : keptCount;
            kept[keptCount++] = walk[step];
        }
        for (int step = 0; step + 1 < keptCount; step++) {
            int edgeId = mesh.edgeBetween(kept[step], kept[step + 1]);
            if (edgeId != MeshTopology.NONE) {
                marks[edgeId] = true;
            }
        }
        if (steps > 0) {
            TracedSurfacePath whole = spline.path;
            int fromPoint = snap.walkPoint[from];
            int points = snap.walkPoint[to] - fromPoint + 1;
            int[] trimmed = IntStream.range(fromPoint, fromPoint + points).toArray();
            spline.path = new TracedSurfacePath(whole.positions.gather(trimmed), whole.surfacePositions.gather(trimmed),
                    Arrays.copyOfRange(whole.vertexId, fromPoint, fromPoint + points),
                    Arrays.copyOfRange(whole.edgeId, fromPoint, fromPoint + points),
                    Arrays.copyOfRange(whole.faceId, fromPoint, fromPoint + points),
                    Arrays.copyOfRange(whole.fraction, fromPoint, fromPoint + points),
                    false).measure();
        }
        spline.marks = new EdgeMarks(marks, snap.unresolvedGaps);
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
        spline.anchorPositions = new PackedPoints(tracer.anchorCount);
        Vector3f position = new Vector3f();
        for (int anchor = 0; anchor < tracer.anchorCount; anchor++) {
            spline.anchorVertexId[anchor] = tracer.anchorVertexId[anchor];
            spline.anchorPositions.set(anchor, mesh.vertexPosition(tracer.anchorVertexId[anchor], position));
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
     * @param polyline   the polyline's points
     * @param pointCount points to read from the front of {@code polyline}
     * @param position   the point
     * @return the shortest distance, or infinity when the polyline is empty
     */
    public static double distanceToPolyline(PackedPoints polyline, int pointCount, Vector3f position) {
        double best = Double.POSITIVE_INFINITY;
        for (int point = 0; point < pointCount; point++) {
            best = Math.min(best, TriangleGeometry.segmentDistanceSquared(polyline, point, (point + 1) % pointCount,
                    position, best));
        }
        return Math.sqrt(best);
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
        PackedPoints xyz = path.positions;
        double floor = smallestMeasuredSpan;
        for (int point = 0; point < points; point++) {
            int previous = stepBack(point, points, floor);
            int following = stepForward(point, points, floor);
            if (previous < 0 || following < 0) {
                continue;
            }
            Vector3d at = new Vector3d(xyz.at(point));
            Vector3d back = new Vector3d(xyz.at(previous)).sub(at);
            Vector3d ahead = new Vector3d(xyz.at(following)).sub(at);
            if (back.lengthSquared() <= 0.0 || ahead.lengthSquared() <= 0.0) {
                continue;
            }
            double angle = Math.toDegrees(back.angle(ahead));
            if (angle < minimumInteriorAngleDegrees) {
                minimumInteriorAngleDegrees = angle;
                sharpestCornerXyz.set(xyz.at(point));
            }
        }
    }

    private int stepBack(int point, int points, double floor) {
        for (int step = 1; step < points; step++) {
            int candidate = Math.floorMod(point - step, points);
            if (Math.sqrt(path.positions.distanceSquared(point, candidate)) > floor) {
                return candidate;
            }
        }
        return -1;
    }

    private int stepForward(int point, int points, double floor) {
        for (int step = 1; step < points; step++) {
            int candidate = (point + step) % points;
            if (Math.sqrt(path.positions.distanceSquared(point, candidate)) > floor) {
                return candidate;
            }
        }
        return -1;
    }
}
