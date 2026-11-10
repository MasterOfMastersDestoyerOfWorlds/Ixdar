package ixdar.geometry.mesh.data.rings;

import java.util.Arrays;
import java.util.Locale;

import ixdar.geometry.mesh.data.paths.geodesic.TracedSurfacePath;
import ixdar.geometry.mesh.data.paths.spline.SplineAnchorFit;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSpline;

/**
 * Re-anchors a crease curve as a geodesic spline: anchors on the curve whose geodesic spline stays
 * within {@link SplineAnchorFit}'s tolerance of it, one added where the spline strays furthest and
 * any dropped that the spline holds without, so the result is as easy to drag as a hand-made ring.
 */
public final class GeodesicRefit {

    public static final int COORDINATES_PER_POINT = 3;

    // Anchors the refit may add before it gives up on reaching the tolerance.
    public static final int MAXIMUM_ADDED_ANCHORS = 64;

    // Sweeps of the drop pass; one usually settles it, a second catches anchors a drop freed.
    public static final int DROP_SWEEPS = 2;

    /** Traces a geodesic spline through anchors for the refit to measure. */
    @FunctionalInterface
    public interface Trace {

        /**
         * The geodesic spline through anchors in the curve's order.
         *
         * @param anchors mesh vertices, in the curve's order
         * @return the traced path, or {@code null} when the anchors make no acceptable spline
         */
        TracedSurfacePath through(int[] anchors);
    }

    /** Distance the spline may keep from the curve: SplineAnchorFit's, over the curve. */
    public double tolerance;

    /** Largest distance between the last accepted spline and the curve, either way. */
    public double deviation;

    /** The anchors the last refit settled on, in the curve's order. */
    public int[] anchors = new int[0];

    /** Traces the last refit ran. */
    public int traces;

    /** Why the last refit failed, or empty when it did not. */
    public String failure = "";

    private double[] referenceXyz = new double[0];
    private int referencePoints;
    private boolean closed;
    private int worstReferencePoint;

    /**
     * Fit anchors on a crease curve so the geodesic spline through them follows it.
     *
     * @param curveXyz       packed xyz of the curve's points
     * @param curveVertexId  mesh vertex at each curve point, the places an anchor may sit
     * @param closedCurve    whether the curve closes on itself, a ring rather than a bisector
     * @param startAnchors   anchors to start from, in the curve's order: the crease spline's own;
     *                       one not on the curve is left out unless it is held
     * @param meanEdgeLength the surface's mean edge length, the tolerance's floor
     * @param trace          traces the geodesic spline through a set of anchors
     * @return true when {@link #anchors} hold a spline within {@link #tolerance}; otherwise
     *         {@link #failure} says why
     */
    public boolean refit(double[] curveXyz, int[] curveVertexId, boolean closedCurve,
            int[] startAnchors, double meanEdgeLength, Trace trace) {
        failure = "";
        traces = 0;
        anchors = new int[0];
        deviation = Double.POSITIVE_INFINITY;
        referenceXyz = curveXyz;
        referencePoints = curveVertexId.length;
        closed = closedCurve;
        tolerance = Math.max(SplineAnchorFit.DEFAULT_RADIUS_FRACTION
                * SplineAnchorFit.meanRadiusOf(curveXyz, referencePoints), meanEdgeLength);
        // Each anchor by its point on the curve; the first, and for an open curve the last, are
        // held where they are, as the ring's lead anchor and the bisector's wall ends.
        int[] points = new int[startAnchors.length];
        int count = 0;
        for (int anchor = 0; anchor < startAnchors.length; anchor++) {
            boolean held = anchor == 0 || !closed && anchor == startAnchors.length - 1;
            int point = held ? (anchor == 0 ? 0 : referencePoints - 1) : -1;
            for (int search = 0; point < 0 && search < referencePoints; search++) {
                point = curveVertexId[search] == startAnchors[anchor] ? search : -1;
            }
            if (point >= 0 && (count == 0 || point > points[count - 1])) {
                points[count++] = point;
            }
        }
        int[] at = Arrays.copyOf(points, count);
        int held = closed ? 1 : 2;
        TracedSurfacePath path = traced(trace, curveVertexId, at);
        if (path == null) {
            failure = "the geodesic spline through the crease ring's own anchors is refused";
            return false;
        }
        double measured = deviationOf(path);
        String stopped = "after " + MAXIMUM_ADDED_ANCHORS + " added anchors";
        for (int added = 0; measured > tolerance && added < MAXIMUM_ADDED_ANCHORS; added++) {
            int insertAt = Arrays.binarySearch(at, worstReferencePoint);
            if (insertAt >= 0) {
                // The spline strays beside an anchor it already has: halve the wider of the two
                // stretches of curve that anchor ends, the one the spline has most room to stray in.
                int last = at.length - 1;
                int before = insertAt > 0 ? at[insertAt] - at[insertAt - 1]
                        : closed ? at[0] + referencePoints - at[last] : 0;
                int after = insertAt < last ? at[insertAt + 1] - at[insertAt]
                        : closed ? at[0] + referencePoints - at[last] : 0;
                if (Math.max(before, after) < 2) {
                    stopped = "with no curve point left beside the anchor at the worst point, "
                            + at.length + " anchors in";
                    break;
                }
                int from = after >= before ? at[insertAt] : at[insertAt > 0 ? insertAt - 1 : last];
                worstReferencePoint = (from + Math.max(before, after) / 2) % referencePoints;
                insertAt = Arrays.binarySearch(at, worstReferencePoint);
            }
            insertAt = -insertAt - 1;
            int[] grown = new int[at.length + 1];
            System.arraycopy(at, 0, grown, 0, insertAt);
            grown[insertAt] = worstReferencePoint;
            System.arraycopy(at, insertAt, grown, insertAt + 1, at.length - insertAt);
            TracedSurfacePath grownPath = traced(trace, curveVertexId, grown);
            if (grownPath == null) {
                stopped = "with the spline through one more anchor refused, " + at.length
                        + " anchors in";
                break;
            }
            at = grown;
            measured = deviationOf(grownPath);
        }
        if (measured > tolerance) {
            failure = String.format(Locale.ROOT, "no geodesic spline comes within %.4f of the "
                    + "crease curve; the closest stays %.4f off it, %s", tolerance, measured,
                    stopped);
            return false;
        }
        for (int sweep = 0; sweep < DROP_SWEEPS; sweep++) {
            boolean droppedAny = false;
            for (int anchor = 1; anchor < at.length - (held - 1) && at.length > held;) {
                int[] kept = new int[at.length - 1];
                System.arraycopy(at, 0, kept, 0, anchor);
                System.arraycopy(at, anchor + 1, kept, anchor, kept.length - anchor);
                TracedSurfacePath keptPath = traced(trace, curveVertexId, kept);
                double keptDeviation = keptPath == null ? Double.POSITIVE_INFINITY
                        : deviationOf(keptPath);
                if (keptDeviation <= tolerance) {
                    at = kept;
                    measured = keptDeviation;
                    droppedAny = true;
                } else {
                    anchor++;
                }
            }
            if (!droppedAny) {
                break;
            }
        }
        deviation = measured;
        anchors = new int[at.length];
        for (int anchor = 0; anchor < at.length; anchor++) {
            anchors[anchor] = curveVertexId[at[anchor]];
        }
        return true;
    }

    /**
     * The largest distance between a traced spline and the reference curve, both ways: each curve
     * point to the spline, each spline point to the curve. Leaves the curve point nearest the worst
     * gap in {@link #worstReferencePoint}, where the next anchor goes.
     *
     * @param path the traced spline
     * @return the distance
     */
    public double deviationOf(TracedSurfacePath path) {
        double worst = -1.0;
        worstReferencePoint = 0;
        for (int point = 0; point < referencePoints; point++) {
            int base = COORDINATES_PER_POINT * point;
            double gap = distanceToCurve(path.positions, path.pointCount, path.closed,
                    referenceXyz[base], referenceXyz[base + 1], referenceXyz[base + 2]);
            if (gap > worst) {
                worst = gap;
                worstReferencePoint = point;
            }
        }
        double worstOut = -1.0;
        int worstOutPoint = -1;
        for (int point = 0; point < path.pointCount; point++) {
            int base = COORDINATES_PER_POINT * point;
            double gap = distanceToCurve(referenceXyz, referencePoints, closed,
                    path.positions[base], path.positions[base + 1], path.positions[base + 2]);
            if (gap > worstOut) {
                worstOut = gap;
                worstOutPoint = point;
            }
        }
        if (worstOut > worst) {
            // The spline strays out where no curve point is far from it: the anchor goes at the
            // curve point nearest that stray.
            int base = COORDINATES_PER_POINT * worstOutPoint;
            double nearest = Double.POSITIVE_INFINITY;
            for (int point = 0; point < referencePoints; point++) {
                double dx = referenceXyz[COORDINATES_PER_POINT * point] - path.positions[base];
                double dy = referenceXyz[COORDINATES_PER_POINT * point + 1]
                        - path.positions[base + 1];
                double dz = referenceXyz[COORDINATES_PER_POINT * point + 2]
                        - path.positions[base + 2];
                double squared = dx * dx + dy * dy + dz * dz;
                if (squared < nearest) {
                    nearest = squared;
                    worstReferencePoint = point;
                }
            }
        }
        return Math.max(worst, worstOut);
    }

    private TracedSurfacePath traced(Trace trace, int[] curveVertexId, int[] at) {
        int[] vertexIds = new int[at.length];
        for (int anchor = 0; anchor < at.length; anchor++) {
            vertexIds[anchor] = curveVertexId[at[anchor]];
        }
        traces++;
        return trace.through(vertexIds);
    }

    /** Distance from a point to a polyline, closed or open. */
    private static double distanceToCurve(double[] packedXyz, int pointCount, boolean closedCurve,
            double x, double y, double z) {
        double best = Double.POSITIVE_INFINITY;
        int spans = closedCurve ? pointCount : pointCount - 1;
        for (int point = 0; point < spans; point++) {
            best = Math.min(best,
                    SurfaceSpline.spanDistanceSquared(packedXyz, pointCount, point, x, y, z, best));
        }
        if (spans <= 0 && pointCount > 0) {
            double dx = packedXyz[0] - x;
            double dy = packedXyz[1] - y;
            double dz = packedXyz[2] - z;
            best = dx * dx + dy * dy + dz * dz;
        }
        return Math.sqrt(best);
    }
}
