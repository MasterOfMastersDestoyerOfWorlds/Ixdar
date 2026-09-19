package ixdar.geometry.mesh.data.paths;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;

/**
 * A closed cubic spline lying on a surface: its anchors, the polyline traced through them, and the
 * mesh edge cycle nearest that polyline.
 *
 * <p>
 * Both tangent handles at an anchor lie on one line through it, so the junction is smooth where a
 * ring of geodesics would corner.
 */
public final class SurfaceSpline {

    /** Coordinates per point in every packed position here. */
    public static final int COORDINATES_PER_POINT = 3;

    /**
     * Mean edge lengths two polyline points must be apart before the turn between them counts.
     * A geodesic that grazes a vertex leaves crossings a whisker apart, and the angle there is
     * arithmetic noise, not a corner anyone can see.
     */
    public static final double SMALLEST_MEASURED_SPAN_IN_EDGES = 0.25;

    /** Packed xyz of the anchors, in ring order. */
    public float[] anchorXyz = new float[0];

    /** Mesh vertex each anchor snapped to, in ring order. */
    public int[] anchorVertexId = new int[0];

    /** Anchors the spline passes through. */
    public int anchorCount;

    /** Packed xyz of the traced spline; the first point is repeated at the end. */
    public float[] polyline = new float[0];

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
        int[] vertexIds = SurfaceWaypoints.snap(mesh, packedXyz, anchorCount);
        SurfaceSplineTracer tracer = new SurfaceSplineTracer(SurfaceGeodesics.over(mesh));
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
        SurfaceSpline spline = new SurfaceSpline();
        MeshTopology mesh = tracer.geodesics.mesh;
        spline.anchorCount = tracer.anchorCount;
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
        spline.polyline = closedPolyline(traced);
        spline.length = traced.polylineLength();
        ConformingLoopSnap snap = new ConformingLoopSnap();
        spline.markedByEdgeId = snap.snap(mesh, traced);
        spline.unresolvedGaps = snap.unresolvedGaps;
        for (boolean marked : spline.markedByEdgeId) {
            if (marked) {
                spline.markedEdgeCount++;
            }
        }
        spline.markedEdgeIds = new int[spline.markedEdgeCount];
        int next = 0;
        for (int edgeId = 0; edgeId < spline.markedByEdgeId.length; edgeId++) {
            if (spline.markedByEdgeId[edgeId]) {
                spline.markedEdgeIds[next++] = edgeId;
            }
        }
        spline.smallestMeasuredSpan =
                SMALLEST_MEASURED_SPAN_IN_EDGES * tracer.geodesics.meanEdgeLength;
        spline.setCentroid();
        spline.setMeanRadius();
        spline.setMinimumInteriorAngle();
        return spline;
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
            int here = COORDINATES_PER_POINT * point;
            int there = COORDINATES_PER_POINT * ((point + 1) % pointCount);
            double gapX = axisGap(x, packedXyz[here], packedXyz[there]);
            double gapY = axisGap(y, packedXyz[here + 1], packedXyz[there + 1]);
            double gapZ = axisGap(z, packedXyz[here + 2], packedXyz[there + 2]);
            if (gapX * gapX + gapY * gapY + gapZ * gapZ > best) {
                continue;
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
            best = Math.min(best, dx * dx + dy * dy + dz * dz);
        }
        return Math.sqrt(best);
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

    private static float[] closedPolyline(TracedSurfacePath traced) {
        int pointCount = traced.pointCount == 0 ? 0 : traced.pointCount + 1;
        float[] packed = new float[COORDINATES_PER_POINT * pointCount];
        for (int point = 0; point < pointCount; point++) {
            int source = COORDINATES_PER_POINT * (point % traced.pointCount);
            int target = COORDINATES_PER_POINT * point;
            packed[target] = (float) traced.positions[source];
            packed[target + 1] = (float) traced.positions[source + 1];
            packed[target + 2] = (float) traced.positions[source + 2];
        }
        return packed;
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
