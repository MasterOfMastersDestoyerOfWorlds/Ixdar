package ixdar.geometry.mesh.data.paths.spline;

import java.util.stream.IntStream;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.data.representation.TriangleGeometry;

/**
 * Fits the fewest supporting anchors that keep a spline through fixed authored anchors on a
 * reference loop, within {@code max(5% of the mean radius, one mean edge)}.
 *
 * <p>
 * Authored anchors are never moved or removed.
 */
public final class SplineAnchorFit {

    public static final int COORDINATES_PER_POINT = 3;

    public static final int STARTING_ANCHORS = 4;

    public static final int DEFAULT_MAXIMUM_ANCHORS = 24;

    public static final double DEFAULT_RADIUS_FRACTION = 0.05;

    public static final int REFERENCE_SPANS_PER_CULLING_BOX = 32;

    /** Tracer the fit drives; its anchors are the fit's output. */
    public SurfaceSplineTracer tracer;

    /** Anchors the fit will not go past. */
    public int maximumAnchors = DEFAULT_MAXIMUM_ANCHORS;

    /** Fraction of the mean radius the tolerance takes. */
    public double radiusFraction = DEFAULT_RADIUS_FRACTION;

    /** Tolerance the last fit ran to. */
    public double tolerance;

    /** Largest distance from the fitted spline to the reference loop. */
    public double deviation;

    /** Whether {@link #deviation} came in under {@link #tolerance}. */
    public boolean withinTolerance;

    /** Whether the fit stopped because it ran out of anchors rather than converging. */
    public boolean anchorCapReached;

    /** Anchors the fit settled on. */
    public int anchorCount;

    /** Traces the fit ran, one per anchor inserted plus the first. */
    public int traceCount;

    /**
     * Mesh vertex of each authored anchor the next fit must pass through, the first leading the
     * cycle; empty fits freely from {@code firstPoint}.
     */
    public int[] authoredVertexId = new int[0];

    /** Reference loop point each authored anchor sits nearest, parallel to the vertex ids. */
    public int[] authoredPoint = new int[0];

    /** How far each authored anchor sits off the reference loop, parallel to the vertex ids. */
    public double[] authoredAllowance = new double[0];

    private PackedPoints[] chunkBounds = new PackedPoints[0];
    private int chunkCount;
    private int warmChunk;
    private int[] anchorAtPoint = new int[0];
    private boolean[] anchorIsAuthored = new boolean[0];
    private double[] anchorAllowance = new double[0];
    private PackedPoints[] scannedXyz = new PackedPoints[0];
    private double[] scannedDeviation = new double[0];
    private PackedPoints[] alignedXyz = new PackedPoints[0];
    private double[] alignedDeviation = new double[0];

    /**
     * Binds the fit to the tracer whose anchors it will set.
     *
     * @param splineTracer tracer to drive
     */
    public SplineAnchorFit(SurfaceSplineTracer splineTracer) {
        this.tracer = splineTracer;
    }

    /**
     * Fits supporting anchors to a closed reference loop around the {@link #authoredVertexId}
     * authored anchors and leaves the tracer holding the result, the authored anchors flagged.
     *
     * @param referenceXyz      the loop's points, treated as closed
     * @param pointCount        points to read from the front of {@code referenceXyz}
     * @param referenceVertexId mesh vertex nearest each loop point, in the same order
     * @param firstPoint        loop point the anchor cycle starts from; the first authored
     *                          anchor's point when there is one
     * @return true when a spline was traced, whether or not it met the tolerance
     */
    public boolean fit(PackedPoints referenceXyz, int pointCount, int[] referenceVertexId,
            int firstPoint) {
        withinTolerance = false;
        anchorCapReached = false;
        deviation = Double.POSITIVE_INFINITY;
        traceCount = 0;
        anchorCount = 0;
        if (pointCount < STARTING_ANCHORS) {
            return false;
        }
        tolerance = Math.max(radiusFraction * meanRadiusOf(referenceXyz, pointCount),
                tracer.geodesics.metric.meanEdgeLength);
        int authoredAnchors = authoredVertexId.length;
        int capacity = Math.max(STARTING_ANCHORS, maximumAnchors) + authoredAnchors;
        anchorAtPoint = new int[capacity];
        anchorIsAuthored = new boolean[capacity];
        anchorAllowance = new double[capacity];
        scannedXyz = new PackedPoints[0];
        scannedDeviation = new double[0];
        chunkCount = (pointCount + REFERENCE_SPANS_PER_CULLING_BOX - 1)
                / REFERENCE_SPANS_PER_CULLING_BOX;
        chunkBounds = new PackedPoints[chunkCount];
        warmChunk = 0;
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            int first = REFERENCE_SPANS_PER_CULLING_BOX * chunk;
            int last = Math.min(pointCount, first + REFERENCE_SPANS_PER_CULLING_BOX);
            chunkBounds[chunk] = referenceXyz.bounds(
                    IntStream.rangeClosed(first, last).map(point -> point % pointCount).toArray());
        }
        int[] vertexIds = new int[capacity];
        for (int authored = 0; authored < authoredAnchors; authored++) {
            accept(vertexIds, authoredPoint[authored], authoredVertexId[authored], firstPoint,
                    pointCount, authoredAllowance[authored]);
        }
        if (anchorCount == 0 && !accept(vertexIds, firstPoint, referenceVertexId[firstPoint],
                firstPoint, pointCount, -1.0)) {
            return false;
        }
        double[] arcLength = new double[pointCount + 1];
        for (int point = 0; point < pointCount; point++) {
            arcLength[point + 1] = arcLength[point] + Math.sqrt(
                    referenceXyz.distanceSquared((point + 1) % pointCount, referenceXyz.at(point)));
        }
        double totalArc = arcLength[pointCount];
        double startArc = arcLength[firstPoint];
        while (anchorCount < STARTING_ANCHORS) {
            double widest = -1.0;
            double middle = 0.0;
            for (int anchor = 0; anchor < anchorCount; anchor++) {
                double from = arcOffset(arcLength, anchorAtPoint[anchor], startArc, totalArc);
                double to = anchor + 1 < anchorCount
                        ? arcOffset(arcLength, anchorAtPoint[anchor + 1], startArc, totalArc)
                        : totalArc;
                if (to - from > widest) {
                    widest = to - from;
                    middle = from + (to - from) / 2.0;
                }
            }
            int point = pointAtArcLength(arcLength, pointCount, firstPoint, middle);
            if (!accept(vertexIds, point, referenceVertexId[point], firstPoint, pointCount,
                    -1.0)) {
                break;
            }
        }
        if (!tracer.setAnchors(vertexIds, anchorIsAuthored, anchorCount)) {
            return false;
        }
        traceCount++;
        int anchorCeiling = Math.max(maximumAnchors, authoredAnchors + STARTING_ANCHORS);
        while (true) {
            if (alignedXyz.length < tracer.anchorCount) {
                alignedXyz = new PackedPoints[tracer.anchorCount];
                alignedDeviation = new double[tracer.anchorCount];
            }
            double worst = 0.0;
            int worstSegment = -1;
            for (int segment = 0; segment < tracer.anchorCount; segment++) {
                PackedPoints points = tracer.segmentXyz[segment];
                alignedXyz[segment] = points;
                alignedDeviation[segment] = -1.0;
                for (int scanned = 0; scanned < scannedXyz.length; scanned++) {
                    if (scannedXyz[scanned] == points) {
                        alignedDeviation[segment] = scannedDeviation[scanned];
                        break;
                    }
                }
                if (alignedDeviation[segment] < 0.0) {
                    double segmentWorst = 0.0;
                    for (int sample = 0; sample < points.size(); sample++) {
                        segmentWorst = Math.max(segmentWorst, gapBeyondAllowance(referenceXyz,
                                pointCount, points, sample, segment));
                    }
                    int from = anchorAtPoint[segment];
                    int stretch = Math.floorMod(anchorAtPoint[(segment + 1) % anchorCount] - from,
                            pointCount);
                    for (int step = 1; step < stretch; step++) {
                        segmentWorst = Math.max(segmentWorst, referenceGap(referenceXyz,
                                pointCount, segment, (from + step) % pointCount,
                                (double) step / stretch));
                    }
                    alignedDeviation[segment] = segmentWorst;
                }
                if (alignedDeviation[segment] > worst) {
                    worst = alignedDeviation[segment];
                    worstSegment = segment;
                }
            }
            PackedPoints[] keptXyz = scannedXyz;
            double[] keptDeviation = scannedDeviation;
            scannedXyz = alignedXyz;
            scannedDeviation = alignedDeviation;
            alignedXyz = keptXyz;
            alignedDeviation = keptDeviation;
            deviation = worst;
            if (deviation <= tolerance) {
                withinTolerance = true;
                return true;
            }
            if (anchorCount >= anchorCeiling) {
                anchorCapReached = true;
                return true;
            }
            PackedPoints worstPoints = tracer.segmentXyz[worstSegment];
            boolean onSpline = false;
            int worstSample = 0;
            for (int sample = 0; sample < worstPoints.size() && !onSpline; sample++) {
                onSpline = gapBeyondAllowance(referenceXyz, pointCount, worstPoints, sample,
                        worstSegment) >= worst;
                worstSample = sample;
            }
            int worstFrom = anchorAtPoint[worstSegment];
            int worstStretch = Math.floorMod(
                    anchorAtPoint[(worstSegment + 1) % anchorCount] - worstFrom, pointCount);
            int point = -1;
            for (int step = 1; !onSpline && step < worstStretch; step++) {
                int candidate = (worstFrom + step) % pointCount;
                if (referenceGap(referenceXyz, pointCount, worstSegment, candidate,
                        (double) step / worstStretch) >= worst) {
                    point = candidate;
                    break;
                }
            }
            double bestSquared = Double.POSITIVE_INFINITY;
            int nearest = -1;
            Vector3f worstPosition = worstPoints.size() > 0 ? worstPoints.at(worstSample) : null;
            for (int step = 1; point < 0 && worstPosition != null && step < worstStretch; step++) {
                int candidate = (worstFrom + step) % pointCount;
                double squared = referenceXyz.distanceSquared(candidate, worstPosition);
                if (squared < bestSquared) {
                    bestSquared = squared;
                    nearest = candidate;
                }
            }
            point = point >= 0 ? point : nearest;
            int slot = insertionSlot(point, firstPoint, pointCount, false);
            if (slot < 0 || !accept(vertexIds, point, referenceVertexId[point], firstPoint,
                    pointCount, -1.0) || !tracer.insertAnchor(slot, referenceVertexId[point],
                            false)) {
                return true;
            }
            traceCount++;
        }
    }

    private static double arcOffset(double[] arcLength, int point, double start, double total) {
        double here = arcLength[point];
        return here < start ? here + total - start : here - start;
    }

    /**
     * Mean distance from a closed loop's centroid to its points, the radius a tolerance is a
     * fraction of.
     *
     * @param loop       the loop's points
     * @param pointCount points to read from the front of {@code loop}
     * @return the mean radius, or zero when the loop is empty
     */
    public static double meanRadiusOf(PackedPoints loop, int pointCount) {
        if (pointCount == 0) {
            return 0.0;
        }
        Vector3f centre = loop.slice(0, pointCount).centroid();
        double radiusSum = 0.0;
        for (int point = 0; point < pointCount; point++) {
            radiusSum += Math.sqrt(loop.distanceSquared(point, centre));
        }
        return radiusSum / pointCount;
    }

    /**
     * Adds one anchor at a loop point, keeping the cycle ordered from {@code firstPoint} and
     * refusing a vertex already held. A negative allowance marks a supporting anchor, which may
     * not share a loop point with any other; an authored one goes after those at its point.
     */
    private boolean accept(int[] vertexIds, int point, int vertexId, int firstPoint,
            int pointCount, double allowance) {
        boolean authored = allowance >= 0.0;
        int slot = insertionSlot(point, firstPoint, pointCount, authored);
        if (slot < 0) {
            return false;
        }
        for (int anchor = 0; anchor < anchorCount; anchor++) {
            if (vertexIds[anchor] == vertexId) {
                return false;
            }
        }
        for (int anchor = anchorCount; anchor > slot; anchor--) {
            anchorAtPoint[anchor] = anchorAtPoint[anchor - 1];
            vertexIds[anchor] = vertexIds[anchor - 1];
            anchorIsAuthored[anchor] = anchorIsAuthored[anchor - 1];
            anchorAllowance[anchor] = anchorAllowance[anchor - 1];
        }
        anchorAtPoint[slot] = point;
        vertexIds[slot] = vertexId;
        anchorIsAuthored[slot] = authored;
        anchorAllowance[slot] = Math.max(0.0, allowance);
        anchorCount++;
        return true;
    }

    /**
     * Where an anchor at a loop point belongs in the anchor cycle, which the tracer needs so it
     * re-traces the two segments the new anchor splits.
     */
    private int insertionSlot(int point, int firstPoint, int pointCount, boolean authored) {
        if (point < 0) {
            return -1;
        }
        int offset = Math.floorMod(point - firstPoint, pointCount);
        for (int anchor = 0; anchor < anchorCount; anchor++) {
            int held = Math.floorMod(anchorAtPoint[anchor] - firstPoint, pointCount);
            if (held == offset && !authored) {
                return -1;
            }
            if (held > offset) {
                return anchor;
            }
        }
        return anchorCount;
    }

    private static int pointAtArcLength(double[] arcLength, int pointCount, int firstPoint,
            double target) {
        double start = arcLength[Math.floorMod(firstPoint, pointCount)];
        double total = arcLength[pointCount];
        double wanted = start + target;
        int best = firstPoint;
        double bestGap = Double.POSITIVE_INFINITY;
        for (int point = 0; point < pointCount; point++) {
            double here = arcLength[point] < start ? arcLength[point] + total : arcLength[point];
            double gap = Math.abs(here - wanted);
            if (gap < bestGap) {
                bestGap = gap;
                best = point;
            }
        }
        return best;
    }

    /**
     * How far one reference point between a segment's anchors sits from that segment's traced
     * points beyond what its authored ends excuse, so a spline that skips a stretch of its
     * reference, cutting across a thin part, reads as far off it.
     */
    private double referenceGap(PackedPoints referenceXyz, int pointCount, int segment, int point,
            double along) {
        PackedPoints points = tracer.segmentXyz[segment];
        PackedPoints following = tracer.segmentXyz[(segment + 1) % tracer.anchorCount];
        Vector3f reference = referenceXyz.at(point);
        double best = Double.POSITIVE_INFINITY;
        for (int sample = 0; sample <= points.size(); sample++) {
            PackedPoints xyz = sample < points.size() ? points : following;
            int at = sample < points.size() ? sample : 0;
            if (at >= xyz.size()) {
                continue;
            }
            best = Math.min(best, xyz.distanceSquared(at, reference));
        }
        double allowance = (1.0 - along) * anchorAllowance[segment]
                + along * anchorAllowance[(segment + 1) % tracer.anchorCount];
        return Math.sqrt(best) - allowance;
    }

    /**
     * How far one traced point sits from its segment's own stretch of the reference, between the
     * segment's two anchors, beyond what its authored ends excuse, blended linearly along it.
     */
    private double gapBeyondAllowance(PackedPoints referenceXyz, int pointCount, PackedPoints points,
            int sample, int segment) {
        Vector3f position = points.at(sample);
        double best = Double.POSITIVE_INFINITY;
        int nearestChunk = warmChunk;
        int from = anchorAtPoint[segment];
        int spans = Math.max(1,
                Math.floorMod(anchorAtPoint[(segment + 1) % anchorCount] - from, pointCount));
        for (int step = 0; step < chunkCount; step++) {
            int chunk = (warmChunk + step) % chunkCount;
            PackedPoints box = chunkBounds[chunk];
            double gapSquared = 0.0;
            for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
                double gap = Math.max(0.0, Math.max((double) box.get(0, axis) - position.get(axis),
                        (double) position.get(axis) - box.get(1, axis)));
                gapSquared += gap * gap;
            }
            if (gapSquared > best) {
                continue;
            }
            int first = REFERENCE_SPANS_PER_CULLING_BOX * chunk;
            int last = Math.min(pointCount, first + REFERENCE_SPANS_PER_CULLING_BOX);
            for (int point = first; point < last; point++) {
                if (Math.floorMod(point - from, pointCount) >= spans) {
                    continue;
                }
                double squared = TriangleGeometry.segmentDistanceSquared(referenceXyz, point,
                        (point + 1) % pointCount, position, best);
                if (squared < best) {
                    best = squared;
                    nearestChunk = chunk;
                }
            }
        }
        warmChunk = nearestChunk;
        double along = (double) sample / points.size();
        double allowance = (1.0 - along) * anchorAllowance[segment]
                + along * anchorAllowance[(segment + 1) % tracer.anchorCount];
        return Math.sqrt(best) - allowance;
    }
}
