package ixdar.geometry.mesh.data.paths;

/**
 * Fits the fewest anchors whose spline stays on a reference loop: four spread evenly by arc
 * length, then one more at the worst deviation until the tolerance is met.
 *
 * <p>
 * That tolerance is {@code max(5% of the mean radius, one mean edge length)}, never finer than the
 * mesh can carry.
 */
public final class SplineAnchorFit {

    /** Coordinates per point in every packed position here. */
    public static final int COORDINATES_PER_POINT = 3;

    /** Anchors the fit starts from, spread evenly by arc length. */
    public static final int STARTING_ANCHORS = 4;

    /** Anchors the fit will not go past, however far the spline still sits from the loop. */
    public static final int DEFAULT_MAXIMUM_ANCHORS = 24;

    /** Fraction of the loop's mean radius the tolerance takes when the mesh is fine. */
    public static final double DEFAULT_RADIUS_FRACTION = 0.05;

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

    private int[] anchorAtPoint = new int[0];
    private double[][] scannedXyz = new double[0][];
    private double[] scannedDeviation = new double[0];
    private double[][] alignedXyz = new double[0][];
    private double[] alignedDeviation = new double[0];
    private final float[] worstPoint = new float[COORDINATES_PER_POINT];

    /**
     * Binds the fit to the tracer whose anchors it will set.
     *
     * @param splineTracer tracer to drive
     */
    public SplineAnchorFit(SurfaceSplineTracer splineTracer) {
        this.tracer = splineTracer;
    }

    /**
     * Fits anchors to a closed reference loop and leaves the tracer holding the result.
     *
     * @param referenceXyz      packed xyz of the loop, treated as closed
     * @param pointCount        points to read from the front of {@code referenceXyz}
     * @param referenceVertexId mesh vertex nearest each loop point, in the same order
     * @param firstPoint        loop point the first anchor is placed on, so a click can hold it
     * @return true when a spline was traced, whether or not it met the tolerance
     */
    public boolean fit(float[] referenceXyz, int pointCount, int[] referenceVertexId,
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
                tracer.geodesics.meanEdgeLength);
        anchorAtPoint = new int[Math.max(STARTING_ANCHORS, maximumAnchors)];
        scannedXyz = new double[0][];
        scannedDeviation = new double[0];
        int[] vertexIds = new int[anchorAtPoint.length];
        double[] arcLength = arcLengthsOf(referenceXyz, pointCount);
        double total = arcLength[pointCount];
        for (int anchor = 0; anchor < STARTING_ANCHORS; anchor++) {
            int point = pointAtArcLength(arcLength, pointCount, firstPoint,
                    total * anchor / STARTING_ANCHORS);
            if (!accept(referenceVertexId, vertexIds, point, firstPoint, pointCount)) {
                return false;
            }
        }
        if (!tracer.setAnchors(vertexIds, anchorCount)) {
            return false;
        }
        traceCount++;
        while (true) {
            deviation = scanDeviation(referenceXyz, pointCount);
            if (deviation <= tolerance) {
                withinTolerance = true;
                return true;
            }
            if (anchorCount >= maximumAnchors) {
                anchorCapReached = true;
                return true;
            }
            int point = nearestReferencePoint(referenceXyz, pointCount);
            int slot = insertionSlot(point, firstPoint, pointCount);
            if (slot < 0 || !accept(referenceVertexId, vertexIds, point, firstPoint, pointCount)
                    || !tracer.insertAnchor(slot, referenceVertexId[point])) {
                return true;
            }
            traceCount++;
        }
    }

    /**
     * Mean distance from a closed loop's centroid to its points, the radius a tolerance is a
     * fraction of.
     *
     * @param packedXyz  packed xyz of the loop
     * @param pointCount points to read from the front of {@code packedXyz}
     * @return the mean radius, or zero when the loop is empty
     */
    public static double meanRadiusOf(float[] packedXyz, int pointCount) {
        if (pointCount == 0) {
            return 0.0;
        }
        double centreX = 0.0;
        double centreY = 0.0;
        double centreZ = 0.0;
        for (int point = 0; point < pointCount; point++) {
            centreX += packedXyz[COORDINATES_PER_POINT * point];
            centreY += packedXyz[COORDINATES_PER_POINT * point + 1];
            centreZ += packedXyz[COORDINATES_PER_POINT * point + 2];
        }
        centreX /= pointCount;
        centreY /= pointCount;
        centreZ /= pointCount;
        double radiusSum = 0.0;
        for (int point = 0; point < pointCount; point++) {
            double dx = packedXyz[COORDINATES_PER_POINT * point] - centreX;
            double dy = packedXyz[COORDINATES_PER_POINT * point + 1] - centreY;
            double dz = packedXyz[COORDINATES_PER_POINT * point + 2] - centreZ;
            radiusSum += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return radiusSum / pointCount;
    }

    /**
     * Adds one anchor at a loop point, keeping the cycle ordered from {@code firstPoint} and
     * refusing a point that would repeat an anchor already held.
     */
    private boolean accept(int[] referenceVertexId, int[] vertexIds, int point, int firstPoint,
            int pointCount) {
        int slot = insertionSlot(point, firstPoint, pointCount);
        if (slot < 0) {
            return false;
        }
        int vertexId = referenceVertexId[point];
        for (int anchor = 0; anchor < anchorCount; anchor++) {
            if (vertexIds[anchor] == vertexId) {
                return false;
            }
        }
        for (int anchor = anchorCount; anchor > slot; anchor--) {
            anchorAtPoint[anchor] = anchorAtPoint[anchor - 1];
            vertexIds[anchor] = vertexIds[anchor - 1];
        }
        anchorAtPoint[slot] = point;
        vertexIds[slot] = vertexId;
        anchorCount++;
        return true;
    }

    /**
     * Where an anchor at a loop point belongs in the anchor cycle, which the tracer needs so it
     * re-traces the two segments the new anchor splits.
     */
    private int insertionSlot(int point, int firstPoint, int pointCount) {
        if (point < 0) {
            return -1;
        }
        int offset = Math.floorMod(point - firstPoint, pointCount);
        for (int anchor = 0; anchor < anchorCount; anchor++) {
            int held = Math.floorMod(anchorAtPoint[anchor] - firstPoint, pointCount);
            if (held == offset) {
                return -1;
            }
            if (held > offset) {
                return anchor;
            }
        }
        return anchorCount;
    }

    private static double[] arcLengthsOf(float[] packedXyz, int pointCount) {
        double[] arcLength = new double[pointCount + 1];
        for (int point = 0; point < pointCount; point++) {
            int here = COORDINATES_PER_POINT * point;
            int there = COORDINATES_PER_POINT * ((point + 1) % pointCount);
            double dx = packedXyz[there] - packedXyz[here];
            double dy = packedXyz[there + 1] - packedXyz[here + 1];
            double dz = packedXyz[there + 2] - packedXyz[here + 2];
            arcLength[point + 1] = arcLength[point] + Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return arcLength;
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
     * The largest distance from any traced spline point to the reference loop, recording the point
     * that sits furthest out so an anchor can be inserted opposite it.
     *
     * <p>
     * A segment still holding the points array already measured keeps its distance rather than
     * being walked against the whole reference again.
     */
    private double scanDeviation(float[] referenceXyz, int pointCount) {
        if (alignedXyz.length < tracer.anchorCount) {
            alignedXyz = new double[tracer.anchorCount][];
            alignedDeviation = new double[tracer.anchorCount];
        }
        double worst = 0.0;
        int worstSegment = -1;
        for (int segment = 0; segment < tracer.anchorCount; segment++) {
            double[] points = tracer.segmentXyz[segment];
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
                for (int base = 0; base < points.length; base += COORDINATES_PER_POINT) {
                    segmentWorst = Math.max(segmentWorst,
                            SurfaceSpline.distanceToPolyline(referenceXyz, pointCount,
                                    (float) points[base], (float) points[base + 1],
                                    (float) points[base + 2]));
                }
                alignedDeviation[segment] = segmentWorst;
            }
            if (alignedDeviation[segment] > worst) {
                worst = alignedDeviation[segment];
                worstSegment = segment;
            }
        }
        double[][] keptXyz = scannedXyz;
        double[] keptDeviation = scannedDeviation;
        scannedXyz = alignedXyz;
        scannedDeviation = alignedDeviation;
        alignedXyz = keptXyz;
        alignedDeviation = keptDeviation;
        if (worstSegment >= 0) {
            double[] points = tracer.segmentXyz[worstSegment];
            for (int base = 0; base < points.length; base += COORDINATES_PER_POINT) {
                double gap = SurfaceSpline.distanceToPolyline(referenceXyz, pointCount,
                        (float) points[base], (float) points[base + 1], (float) points[base + 2]);
                if (gap >= worst) {
                    worstPoint[0] = (float) points[base];
                    worstPoint[1] = (float) points[base + 1];
                    worstPoint[2] = (float) points[base + 2];
                    break;
                }
            }
        }
        return worst;
    }

    private int nearestReferencePoint(float[] referenceXyz, int pointCount) {
        int best = -1;
        double bestSquared = Double.POSITIVE_INFINITY;
        for (int point = 0; point < pointCount; point++) {
            double dx = referenceXyz[COORDINATES_PER_POINT * point] - worstPoint[0];
            double dy = referenceXyz[COORDINATES_PER_POINT * point + 1] - worstPoint[1];
            double dz = referenceXyz[COORDINATES_PER_POINT * point + 2] - worstPoint[2];
            double squared = dx * dx + dy * dy + dz * dz;
            if (squared < bestSquared) {
                bestSquared = squared;
                best = point;
            }
        }
        return best;
    }
}
