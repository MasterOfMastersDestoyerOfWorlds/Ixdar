package ixdar.geometry.mesh.data.paths;

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

    private double[] chunkBounds = new double[0];
    private int chunkCount;
    private int warmChunk;
    private int[] anchorAtPoint = new int[0];
    private boolean[] anchorIsAuthored = new boolean[0];
    private double[] anchorAllowance = new double[0];
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
     * Fits supporting anchors to a closed reference loop around the {@link #authoredVertexId}
     * authored anchors and leaves the tracer holding the result, the authored anchors flagged.
     *
     * @param referenceXyz      packed xyz of the loop, treated as closed
     * @param pointCount        points to read from the front of {@code referenceXyz}
     * @param referenceVertexId mesh vertex nearest each loop point, in the same order
     * @param firstPoint        loop point the anchor cycle starts from; the first authored
     *                          anchor's point when there is one
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
        int authoredAnchors = authoredVertexId.length;
        int capacity = Math.max(STARTING_ANCHORS, maximumAnchors) + authoredAnchors;
        anchorAtPoint = new int[capacity];
        anchorIsAuthored = new boolean[capacity];
        anchorAllowance = new double[capacity];
        scannedXyz = new double[0][];
        scannedDeviation = new double[0];
        // Bound each run of reference spans by a box, so a distance query skips every run whose
        // box is already farther than its best span.
        chunkCount = (pointCount + REFERENCE_SPANS_PER_CULLING_BOX - 1)
                / REFERENCE_SPANS_PER_CULLING_BOX;
        chunkBounds = new double[2 * COORDINATES_PER_POINT * chunkCount];
        warmChunk = 0;
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            int bounds = 2 * COORDINATES_PER_POINT * chunk;
            int first = REFERENCE_SPANS_PER_CULLING_BOX * chunk;
            int last = Math.min(pointCount, first + REFERENCE_SPANS_PER_CULLING_BOX);
            for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
                chunkBounds[bounds + axis] = Double.POSITIVE_INFINITY;
                chunkBounds[bounds + COORDINATES_PER_POINT + axis] = Double.NEGATIVE_INFINITY;
            }
            for (int point = first; point <= last; point++) {
                int base = COORDINATES_PER_POINT * (point % pointCount);
                for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
                    chunkBounds[bounds + axis] =
                            Math.min(chunkBounds[bounds + axis], referenceXyz[base + axis]);
                    chunkBounds[bounds + COORDINATES_PER_POINT + axis] = Math.max(
                            chunkBounds[bounds + COORDINATES_PER_POINT + axis],
                            referenceXyz[base + axis]);
                }
            }
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
        double[] arcLength = arcLengthsOf(referenceXyz, pointCount);
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
            deviation = scanDeviation(referenceXyz, pointCount);
            if (deviation <= tolerance) {
                withinTolerance = true;
                return true;
            }
            if (anchorCount >= anchorCeiling) {
                anchorCapReached = true;
                return true;
            }
            int point = nearestReferencePoint(referenceXyz, pointCount);
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
                            gapBeyondAllowance(referenceXyz, pointCount, points, base, segment));
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
                double gap = gapBeyondAllowance(referenceXyz, pointCount, points, base,
                        worstSegment);
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

    /**
     * How far one traced point sits from the reference loop beyond what its segment's authored
     * ends excuse, blended linearly along the segment. Box culling keeps the distance equal to
     * {@link SurfaceSpline#distanceToPolyline} to the bit.
     */
    private double gapBeyondAllowance(float[] referenceXyz, int pointCount, double[] points,
            int base, int segment) {
        float x = (float) points[base];
        float y = (float) points[base + 1];
        float z = (float) points[base + 2];
        double best = Double.POSITIVE_INFINITY;
        int nearestChunk = warmChunk;
        for (int step = 0; step < chunkCount; step++) {
            int chunk = (warmChunk + step) % chunkCount;
            int bounds = 2 * COORDINATES_PER_POINT * chunk;
            double gapX = Math.max(0.0, Math.max(chunkBounds[bounds] - x,
                    x - chunkBounds[bounds + COORDINATES_PER_POINT]));
            double gapY = Math.max(0.0, Math.max(chunkBounds[bounds + 1] - y,
                    y - chunkBounds[bounds + COORDINATES_PER_POINT + 1]));
            double gapZ = Math.max(0.0, Math.max(chunkBounds[bounds + 2] - z,
                    z - chunkBounds[bounds + COORDINATES_PER_POINT + 2]));
            if (gapX * gapX + gapY * gapY + gapZ * gapZ > best) {
                continue;
            }
            int first = REFERENCE_SPANS_PER_CULLING_BOX * chunk;
            int last = Math.min(pointCount, first + REFERENCE_SPANS_PER_CULLING_BOX);
            for (int point = first; point < last; point++) {
                double squared = SurfaceSpline.spanDistanceSquared(referenceXyz, pointCount,
                        point, x, y, z, best);
                if (squared < best) {
                    best = squared;
                    nearestChunk = chunk;
                }
            }
        }
        warmChunk = nearestChunk;
        double along = (double) base / points.length;
        double allowance = (1.0 - along) * anchorAllowance[segment]
                + along * anchorAllowance[(segment + 1) % tracer.anchorCount];
        return Math.sqrt(best) - allowance;
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
