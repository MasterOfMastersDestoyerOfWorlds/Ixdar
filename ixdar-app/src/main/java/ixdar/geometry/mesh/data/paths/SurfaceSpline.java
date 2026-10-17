package ixdar.geometry.mesh.data.paths;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.RingBundle;

/**
 * A cubic spline on a surface, a closed ring or an open bisector: its anchors, its traced polyline and
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

    /** Packed xyz of the traced spline; a closed one repeats its first point at the end. */
    public float[] polyline = new float[0];

    /** Whether the spline is an open bisector from its first anchor to its last, not a ring. */
    public boolean open;

    /** Mesh vertex each {@link #polyline} point sits on, or -1 elsewhere. */
    public int[] pointVertexId = new int[0];

    /** Mesh edge each {@link #polyline} point crosses, or -1 elsewhere. */
    public int[] pointEdgeId = new int[0];

    /**
     * Mesh face each {@link #polyline} point lies strictly inside, or -1 elsewhere: where the
     * trace crossed an intrinsic split of a quad or larger polygon. Its place is in
     * {@link #surfacePolyline}.
     */
    public int[] pointFaceId = new int[0];

    /** The trace's crossing parameter along {@link #pointEdgeId}, or -1 off an edge. */
    public double[] pointFraction = new double[0];

    /**
     * Packed xyz of the surface point each {@link #polyline} point stands for: its vertex, where
     * the trace's geodesic crosses its edge, or its place inside its face. Consecutive points
     * share a face; the polyline itself is the Euclidean cubic, off the surface.
     */
    public float[] surfacePolyline = new float[0];

    /** Edge-id-indexed mask, true on every mesh edge of the cycle nearest the spline. */
    public boolean[] markedByEdgeId = new boolean[0];

    /** Ascending mesh edge ids of that cycle. */
    public int[] markedEdgeIds = new int[0];

    /** Mesh edges marked by {@link #markedByEdgeId}. */
    public int markedEdgeCount;

    /** Gaps {@link ConformingLoopSnap} could not close. */
    public int unresolvedGaps;

    /** Euclidean length of the traced spline. */
    public double length;

    /** Mean distance from the centroid to the traced points. */
    public double meanRadius;

    /** Length-weighted centroid x, the position a joint would sit at. */
    public float centroidX;

    /** Length-weighted centroid y. */
    public float centroidY;

    /** Length-weighted centroid z. */
    public float centroidZ;

    /** Smallest interior angle of the traced polyline, in degrees; 180 is perfectly straight. */
    public double minimumInteriorAngleDegrees = 180.0;

    /** Deepest bisection any segment needed. */
    public int traceDepth;

    /** Geodesics the trace computed, the cost measure a hover budget is spent on. */
    public long geodesicCount;

    /** Shortest polyline span the interior angles were measured across. */
    public double smallestMeasuredSpan;

    /** Packed xyz of the polyline point {@link #minimumInteriorAngleDegrees} was measured at. */
    public final float[] sharpestCornerXyz = new float[COORDINATES_PER_POINT];

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
        ConformingLoopSnap snap = new ConformingLoopSnap();
        boolean[] marks = snap.snap(tracer.geodesics.mesh, tracer.tracedRing());
        spline.unresolvedGaps = snap.unresolvedGaps;
        spline.measure(marks, tracer.geodesics.metric.meanEdgeLength);
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
        if (traced) {
            ConformingLoopSnap snap = new ConformingLoopSnap();
            snapped = snap.snap(mesh, tracer.tracedRing());
            spline.unresolvedGaps = snap.unresolvedGaps;
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
        spline.measure(marks, geodesics.metric.meanEdgeLength);
        return spline;
    }

    /**
     * Take an edge mask as this spline's walls and measure the traced points: centroid, mean
     * radius and sharpest corner.
     */
    private void measure(boolean[] marks, double meanEdgeLength) {
        markedByEdgeId = marks;
        markedEdgeCount = 0;
        for (boolean marked : marks) {
            if (marked) {
                markedEdgeCount++;
            }
        }
        markedEdgeIds = new int[markedEdgeCount];
        int next = 0;
        for (int edgeId = 0; edgeId < marks.length; edgeId++) {
            if (marks[edgeId]) {
                markedEdgeIds[next++] = edgeId;
            }
        }
        smallestMeasuredSpan = SMALLEST_MEASURED_SPAN_IN_EDGES * meanEdgeLength;
        setCentroid();
        setMeanRadius();
        setMinimumInteriorAngle();
    }

    /**
     * The drawn parts of a traced tracer only, its anchors in both classes, closed polyline and
     * length, without the edge-cycle snap and measures a hover frame has no use for.
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

        TracedSurfacePath traced = tracer.tracedRing();
        spline.followPath(mesh, traced);
        spline.length = traced.polylineLength();
        return spline;
    }

    /**
     * Take a traced path as this spline's points: {@link #polyline} and the per-point vertex, edge
     * or face it lies on, a closed path's first point repeated at the end, and their surface
     * positions.
     *
     * @param mesh   surface the path's vertex, edge and face ids index
     * @param traced path whose points each sit on a vertex, cross an edge or lie in a face
     */
    public void followPath(MeshTopology mesh, TracedSurfacePath traced) {
        this.mesh = mesh;
        open = !traced.closed;
        int pointCount = traced.pointCount == 0 || open ? traced.pointCount
                : traced.pointCount + 1;
        polyline = new float[COORDINATES_PER_POINT * pointCount];
        surfacePolyline = new float[COORDINATES_PER_POINT * pointCount];
        pointVertexId = new int[pointCount];
        pointEdgeId = new int[pointCount];
        pointFaceId = new int[pointCount];
        pointFraction = new double[pointCount];
        Vector3f point = new Vector3f();
        Vector3f head = new Vector3f();
        for (int target = 0; target < pointCount; target++) {
            int source = target % traced.pointCount;
            int base = COORDINATES_PER_POINT * target;
            for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
                polyline[base + axis] =
                        (float) traced.positions[COORDINATES_PER_POINT * source + axis];
            }
            pointVertexId[target] = traced.vertexId[source];
            pointEdgeId[target] = traced.edgeId[source];
            pointFaceId[target] = traced.faceId[source];
            pointFraction[target] = traced.fraction[source];
            if (traced.vertexId[source] >= 0) {
                mesh.vertexPosition(traced.vertexId[source], point);
            } else if (traced.faceId[source] >= 0) {
                int surface = COORDINATES_PER_POINT * source;
                point.set(traced.surfacePositions[surface], traced.surfacePositions[surface + 1],
                        traced.surfacePositions[surface + 2]);
            } else {
                int halfEdge = mesh.edgeHalfEdge(traced.edgeId[source]);
                mesh.vertexPosition(mesh.halfEdgeVertex(halfEdge), point);
                mesh.vertexPosition(mesh.halfEdgeEndVertex(halfEdge), head);
                point.lerp(head, (float) traced.fraction[source]);
            }
            surfacePolyline[base] = point.x;
            surfacePolyline[base + 1] = point.y;
            surfacePolyline[base + 2] = point.z;
        }
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
     * The spline's path on the surface as one trace, its points on the vertices, edges and faces
     * they lie on, the form {@link SurfacePathCrossings} reads.
     *
     * @return the path, closed without the repeated first point unless the spline is open
     */
    public TracedSurfacePath surfacePath() {
        int points = open ? pointVertexId.length : Math.max(0, pointVertexId.length - 1);
        double[] xyz = new double[COORDINATES_PER_POINT * points];
        for (int coordinate = 0; coordinate < xyz.length; coordinate++) {
            xyz[coordinate] = surfacePolyline[coordinate];
        }
        return new TracedSurfacePath(xyz, xyz, pointVertexId, pointEdgeId, pointFaceId,
                pointFraction, points, !open);
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
     * The largest distance from a point of this spline to a reference polyline, the measure the
     * anchor fit drives down.
     *
     * @param referenceXyz packed xyz of the polyline to compare against, treated as closed
     * @param pointCount   points to read from the front of {@code referenceXyz}
     * @return the largest distance, or zero when either curve is empty
     */
    public double largestDeviationFrom(float[] referenceXyz, int pointCount) {
        double worst = 0.0;
        int points = polyline.length / COORDINATES_PER_POINT;
        for (int point = 0; point < points; point++) {
            worst = Math.max(worst, distanceToPolyline(referenceXyz, pointCount,
                    polyline[COORDINATES_PER_POINT * point],
                    polyline[COORDINATES_PER_POINT * point + 1],
                    polyline[COORDINATES_PER_POINT * point + 2]));
        }
        return worst;
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
    public static double distanceToPolyline(float[] packedXyz, int pointCount, float x, float y,
            float z) {
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
    public static double spanDistanceSquared(float[] packedXyz, int pointCount, int point,
            float x, float y, float z, double bound) {
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

    private void setCentroid() {
        int points = polyline.length / COORDINATES_PER_POINT;
        double weightedX = 0.0;
        double weightedY = 0.0;
        double weightedZ = 0.0;
        double totalWeight = 0.0;
        for (int span = 0; span + 1 < points; span++) {
            int here = COORDINATES_PER_POINT * span;
            int there = COORDINATES_PER_POINT * (span + 1);
            double dx = polyline[there] - polyline[here];
            double dy = polyline[there + 1] - polyline[here + 1];
            double dz = polyline[there + 2] - polyline[here + 2];
            double weight = Math.sqrt(dx * dx + dy * dy + dz * dz);
            weightedX += weight * 0.5 * (polyline[here] + polyline[there]);
            weightedY += weight * 0.5 * (polyline[here + 1] + polyline[there + 1]);
            weightedZ += weight * 0.5 * (polyline[here + 2] + polyline[there + 2]);
            totalWeight += weight;
        }
        if (totalWeight <= 0.0) {
            return;
        }
        centroidX = (float) (weightedX / totalWeight);
        centroidY = (float) (weightedY / totalWeight);
        centroidZ = (float) (weightedZ / totalWeight);
    }

    private void setMeanRadius() {
        int points = polyline.length / COORDINATES_PER_POINT;
        if (points == 0) {
            return;
        }
        double radiusSum = 0.0;
        for (int point = 0; point < points; point++) {
            int base = COORDINATES_PER_POINT * point;
            double dx = polyline[base] - centroidX;
            double dy = polyline[base + 1] - centroidY;
            double dz = polyline[base + 2] - centroidZ;
            radiusSum += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        meanRadius = radiusSum / points;
    }

    /**
     * The sharpest turn anywhere on the traced polyline. Points closer together than a whisker of
     * the ring are skipped, so a pair of coincident edge crossings does not read as a corner.
     */
    private void setMinimumInteriorAngle() {
        int points = polyline.length / COORDINATES_PER_POINT - 1;
        if (points < COORDINATES_PER_POINT) {
            return;
        }
        double floor = smallestMeasuredSpan;
        for (int point = 0; point < points; point++) {
            int previous = stepBack(point, points, floor);
            int following = stepForward(point, points, floor);
            if (previous < 0 || following < 0) {
                continue;
            }
            int here = COORDINATES_PER_POINT * point;
            double backX = polyline[COORDINATES_PER_POINT * previous] - polyline[here];
            double backY = polyline[COORDINATES_PER_POINT * previous + 1] - polyline[here + 1];
            double backZ = polyline[COORDINATES_PER_POINT * previous + 2] - polyline[here + 2];
            double aheadX = polyline[COORDINATES_PER_POINT * following] - polyline[here];
            double aheadY = polyline[COORDINATES_PER_POINT * following + 1] - polyline[here + 1];
            double aheadZ = polyline[COORDINATES_PER_POINT * following + 2] - polyline[here + 2];
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
                sharpestCornerXyz[0] = polyline[here];
                sharpestCornerXyz[1] = polyline[here + 1];
                sharpestCornerXyz[2] = polyline[here + 2];
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
        double dx = polyline[COORDINATES_PER_POINT * firstPoint]
                - polyline[COORDINATES_PER_POINT * secondPoint];
        double dy = polyline[COORDINATES_PER_POINT * firstPoint + 1]
                - polyline[COORDINATES_PER_POINT * secondPoint + 1];
        double dz = polyline[COORDINATES_PER_POINT * firstPoint + 2]
                - polyline[COORDINATES_PER_POINT * secondPoint + 2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
