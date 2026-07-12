package ixdar.geometry.mesh.quadlayout.quantization;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.CoonsEvaluator;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TMeshPatch;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.Trace;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceArc;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceSegment;

/**
 * Geometric realization of the conforming quad layout: traces every live
 * {@link LayoutRectangle}'s four boundary sides as 3D polylines on the surface,
 * validates that each patch really is a four-cornered quad (the sides trace,
 * the corners of adjacent sides agree), and tessellates clean quads into Coons
 * sample grids for rendering. Failures are logged per patch with rectangle and
 * root-patch ids — a patch that cannot produce four sound corners is a pipeline
 * failure, not a rendering nuisance.
 */
public final class LayoutPatchGeometry {

    /** Coons grid resolution per patch (samples × samples points). */
    public static final int COONS_SAMPLES = 10;

    /** Side shorter than this fraction of the mesh radius counts as degenerate. */
    public static final double DEGENERATE_SIDE_EPSILON_FRACTION = 1.0e-5;

    /**
     * Corner endpoint disagreement above this fraction of the radius is a mismatch.
     */
    public static final double CORNER_MISMATCH_EPSILON_FRACTION = 1.0e-3;

    /**
     * Consecutive polyline points closer than this fraction of the radius merge.
     */
    public static final double POINT_DEDUPE_EPSILON_FRACTION = 1.0e-7;

    /** Canonical (start corner, end corner) of each side: A→B, B→C, D→C, A→D. */
    private static final int[][] SIDE_CORNERS = { { 0, 1 }, { 1, 2 }, { 3, 2 }, { 0, 3 } };

    public final TJunctionElimination conforming;
    public final MotorcycleGraph motorcycleGraph;
    public final QuantizedMeshGrid quantization;

    /** One curves record per live layout rectangle. */
    public final List<LayoutPatchCurves> patches = new ArrayList<>();

    public int cleanQuadCount;
    public int degenerateSideTotal;
    public int syntheticSegmentTotal;
    public int partialSegmentTotal;
    public int missingArcTotal;
    public int cornerMismatchCount;

    private double meshRadius;
    private double dedupeEpsilon;

    /**
     * Stores inputs for a geometric patch extraction.
     *
     * @param conforming T-junction-eliminated layout whose live rectangles get
     *                   traced
     */
    public LayoutPatchGeometry(TJunctionElimination conforming) {
        this.conforming = conforming;
        this.motorcycleGraph = conforming.motorcycleGraph;
        this.quantization = conforming.quantization;
    }

    /**
     * Trace, validate, and tessellate every live rectangle, then log the validation
     * summary.
     *
     * @return this, with {@link #patches} and all counters populated
     */
    public LayoutPatchGeometry build() {
        meshRadius = motorcycleGraph.seamless.mesh.radius();
        dedupeEpsilon = POINT_DEDUPE_EPSILON_FRACTION * meshRadius;
        for (LayoutRectangle cell : conforming.rectangles) {
            if (!cell.alive) {
                continue;
            }
            LayoutPatchCurves curves = buildPatchCurves(cell);
            patches.add(curves);
            degenerateSideTotal += curves.degenerateSideCount;
            syntheticSegmentTotal += curves.syntheticSegmentCount;
            partialSegmentTotal += curves.partialSegmentCount;
            missingArcTotal += curves.missingArcCount;
            if (curves.cleanQuad) {
                cleanQuadCount++;
                tessellate(curves);
            } else {
                if (curves.maxCornerMismatch > CORNER_MISMATCH_EPSILON_FRACTION * meshRadius) {
                    cornerMismatchCount++;
                }
                System.out.printf(
                        "[patch-geom] NOT CLEAN rectangle=%d rootPatch=%d degenerateSides=%d"
                                + " missingArcs=%d synthetic=%d partial=%d cornerMismatch=%.6f%n",
                        curves.rectangleId, curves.rootPatchId, curves.degenerateSideCount,
                        curves.missingArcCount, curves.syntheticSegmentCount,
                        curves.partialSegmentCount, curves.maxCornerMismatch);
            }
        }
        System.out.printf(
                "[patch-geom] patches=%d cleanQuads=%d degenerateSides=%d syntheticSegments=%d"
                        + " partialSegments=%d missingArcs=%d cornerMismatches=%d%n",
                patches.size(), cleanQuadCount, degenerateSideTotal, syntheticSegmentTotal,
                partialSegmentTotal, missingArcTotal, cornerMismatchCount);
        return this;
    }

    /**
     * Trace one rectangle's four sides, resolve synthetic sides as straight lines
     * between corners, and validate corners and side lengths.
     *
     * @param cell live layout rectangle
     * @return the validated curves record
     */
    private LayoutPatchCurves buildPatchCurves(LayoutRectangle cell) {
        int syntheticSegments = 0;
        int partialSegments = 0;
        int missingArcs = 0;
        boolean unsplit = true;
        for (int side = 0; side < LayoutRectangle.SIDES; side++) {
            for (LayoutSideSegment segment : cell.sideSegments.get(side)) {
                if (segment.arcId < 0) {
                    syntheticSegments++;
                    unsplit = false;
                } else if (segment.arcStart != 0
                        || segment.arcEnd != quantization.quantizedLengthByArc[segment.arcId]) {
                    partialSegments++;
                    unsplit = false;
                }
            }
        }

        List<List<Vector3f>> sidePolylines = unsplit ? traceSourcePatchSides(cell) : null;
        if (sidePolylines == null) {
            sidePolylines = new ArrayList<>();
            for (int side = 0; side < LayoutRectangle.SIDES; side++) {
                List<Vector3f> polyline = new ArrayList<>();
                for (LayoutSideSegment segment : cell.sideSegments.get(side)) {
                    if (segment.arcId < 0) {
                        continue;
                    }
                    missingArcs += appendSegmentPolyline(polyline, segment);
                }
                sidePolylines.add(polyline);
            }
        }

        Vector3f[] corners = new Vector3f[LayoutRectangle.SIDES];
        float maxMismatch = 0f;
        for (int corner = 0; corner < LayoutRectangle.SIDES; corner++) {
            List<Vector3f> meeting = new ArrayList<>();
            for (int side = 0; side < LayoutRectangle.SIDES; side++) {
                List<Vector3f> polyline = sidePolylines.get(side);
                if (polyline.isEmpty()) {
                    continue;
                }
                if (SIDE_CORNERS[side][0] == corner) {
                    meeting.add(polyline.get(0));
                }
                if (SIDE_CORNERS[side][1] == corner) {
                    meeting.add(polyline.get(polyline.size() - 1));
                }
            }
            if (meeting.isEmpty()) {
                continue;
            }
            Vector3f average = new Vector3f();
            for (Vector3f candidate : meeting) {
                average.add(candidate);
            }
            average.mul(1f / meeting.size());
            corners[corner] = average;
            if (meeting.size() > 1) {
                maxMismatch = Math.max(maxMismatch, meeting.get(0).distance(meeting.get(1)));
            }
        }

        int degenerateSides = 0;
        for (int side = 0; side < LayoutRectangle.SIDES; side++) {
            List<Vector3f> polyline = sidePolylines.get(side);
            if (polyline.isEmpty()) {
                Vector3f start = corners[SIDE_CORNERS[side][0]];
                Vector3f end = corners[SIDE_CORNERS[side][1]];
                if (start != null && end != null) {
                    polyline.add(new Vector3f(start));
                    polyline.add(new Vector3f(end));
                }
            }
            if (polylineLength(polyline) < DEGENERATE_SIDE_EPSILON_FRACTION * meshRadius) {
                degenerateSides++;
            }
        }

        boolean cornersComplete = corners[0] != null && corners[1] != null
                && corners[2] != null && corners[3] != null;
        boolean cleanQuad = cornersComplete && degenerateSides == 0 && missingArcs == 0
                && maxMismatch <= CORNER_MISMATCH_EPSILON_FRACTION * meshRadius;
        return new LayoutPatchCurves(cell.rectangleId, cell.rootPatchId, sidePolylines, corners,
                syntheticSegments, partialSegments, missingArcs, degenerateSides, maxMismatch,
                cleanQuad);
    }

    /**
     * Trace all four sides of an unsplit rectangle from its source T-mesh patch,
     * whose side lists include the zero-quantized arcs that still carry real
     * geometry between the positive ones.
     *
     * @param cell unsplit live rectangle
     * @return four canonical side polylines, or {@code null} when the source
     *         patch's arc cycle does not chain (the caller falls back to the
     *         segment-based path)
     */
    private List<List<Vector3f>> traceSourcePatchSides(LayoutRectangle cell) {
        TMeshPatch patch = motorcycleGraph.patches.get(cell.rootPatchId);
        List<Integer> cycleArcIds = new ArrayList<>();
        for (int side = 0; side < LayoutRectangle.SIDES; side++) {
            cycleArcIds.addAll(patch.sides.get(side));
        }
        List<Integer> cycleNodeIds = chainCycleNodes(cycleArcIds);
        if (cycleNodeIds == null) {
            return null;
        }
        List<List<Vector3f>> sidePolylines = new ArrayList<>();
        int cyclePosition = 0;
        for (int side = 0; side < LayoutRectangle.SIDES; side++) {
            List<Integer> sideArcIds = patch.sides.get(side);
            boolean reversed = side >= 2;
            List<Vector3f> polyline = new ArrayList<>();
            for (int index = 0; index < sideArcIds.size(); index++) {
                int arcPosition = reversed ? sideArcIds.size() - 1 - index : index;
                int arcId = sideArcIds.get(arcPosition);
                int fromNodeId = cycleNodeIds.get(cyclePosition + arcPosition + (reversed ? 1 : 0));
                TraceArc arc = motorcycleGraph.arcs.get(arcId);
                List<Vector3f> arcPolyline = arcPolylineForRange(arcId, 0.0, 1.0);
                if (arcPolyline == null) {
                    continue;
                }
                if (arc.startNodeId != fromNodeId) {
                    Collections.reverse(arcPolyline);
                }
                for (Vector3f point : arcPolyline) {
                    appendPoint(polyline, point);
                }
            }
            sidePolylines.add(polyline);
            cyclePosition += sideArcIds.size();
        }
        return sidePolylines;
    }

    /**
     * Append the traced sub-polyline of one (possibly partial) side segment in its
     * canonical direction.
     *
     * @param polyline polyline to extend
     * @param segment  real side segment with intrinsic quantized bounds
     * @return 1 when the backing arc's parametric range was missing, else 0
     */
    private int appendSegmentPolyline(List<Vector3f> polyline, LayoutSideSegment segment) {
        int quantizedFull = quantization.quantizedLengthByArc[segment.arcId];
        double fractionStart = quantizedFull == 0 ? 0.0 : segment.arcStart / (double) quantizedFull;
        double fractionEnd = quantizedFull == 0 ? 1.0 : segment.arcEnd / (double) quantizedFull;
        List<Vector3f> arcPolyline = arcPolylineForRange(segment.arcId, fractionStart, fractionEnd);
        if (arcPolyline == null) {
            return 1;
        }
        if (!segment.forward) {
            Collections.reverse(arcPolyline);
        }
        for (Vector3f point : arcPolyline) {
            appendPoint(polyline, point);
        }
        return 0;
    }

    /**
     * Trace the 3D polyline of one arc's parametric sub-range by clipping the range
     * against the owning trace's segments and lifting the clipped chord endpoints
     * (same clipping as {@code LayoutExtraction.buildLayoutRecords}). Points come
     * out in trace travel order, i.e. from the arc's start node toward its end
     * node.
     *
     * @param arcId         arc to trace
     * @param fractionStart sub-range start as a fraction of the arc, 0..1
     * @param fractionEnd   sub-range end as a fraction of the arc, 0..1
     * @return polyline points in travel order, or {@code null} when the arc is
     *         missing from its trace's rebuilt chain
     */
    private List<Vector3f> arcPolylineForRange(int arcId, double fractionStart, double fractionEnd) {
        TraceArc arc = motorcycleGraph.arcs.get(arcId);
        Trace trace = motorcycleGraph.traces.get(arc.traceId);
        int chainPosition = trace.chainArcIds.indexOf(arcId);
        if (chainPosition < 0) {
            return null;
        }
        double arcStartLength = trace.chainNodeLengths.get(chainPosition);
        double arcEndLength = trace.chainNodeLengths.get(chainPosition + 1);
        double rangeStart = arcStartLength + fractionStart * (arcEndLength - arcStartLength);
        double rangeEnd = arcStartLength + fractionEnd * (arcEndLength - arcStartLength);
        List<Vector3f> points = new ArrayList<>();
        for (TraceSegment segment : trace.segments) {
            double segmentStart = segment.parametricLengthAtEntry;
            double segmentEnd = segmentStart + segment.parametricLength();
            double clipStart = Math.max(segmentStart, rangeStart);
            double clipEnd = Math.min(segmentEnd, rangeEnd);
            if (clipEnd <= clipStart) {
                continue;
            }
            double entrySpan = segment.axis.holdsUConstant() ? segment.entryV : segment.entryU;
            double spanA = entrySpan + segment.sign * (clipStart - segmentStart);
            double spanB = entrySpan + segment.sign * (clipEnd - segmentStart);
            appendLiftedPoint(points, segment, spanA);
            appendLiftedPoint(points, segment, spanB);
        }
        return points;
    }

    /**
     * Lift one span coordinate on a trace segment's iso-line and append it.
     *
     * @param points  polyline to extend
     * @param segment trace segment supplying the face chart and iso value
     * @param span    coordinate along the varying axis
     */
    private void appendLiftedPoint(List<Vector3f> points, TraceSegment segment, double span) {
        double u = segment.axis.holdsUConstant() ? segment.isoValue : span;
        double v = segment.axis.holdsUConstant() ? span : segment.isoValue;
        appendPoint(points,
                MotorcycleGraph.liftToPosition(motorcycleGraph.mesh, motorcycleGraph.walker, segment.activeFace, u, v));
    }

    /**
     * Append a point unless it coincides with the polyline's current end.
     *
     * @param polyline polyline to extend
     * @param point    candidate point
     */
    private void appendPoint(List<Vector3f> polyline, Vector3f point) {
        if (!polyline.isEmpty()
                && polyline.get(polyline.size() - 1).distance(point) <= dedupeEpsilon) {
            return;
        }
        polyline.add(point);
    }

    /**
     * Total chord length of a polyline.
     *
     * @param polyline polyline to measure
     * @return summed segment lengths; 0 for fewer than two points
     */
    private double polylineLength(List<Vector3f> polyline) {
        double total = 0.0;
        for (int index = 1; index < polyline.size(); index++) {
            total += polyline.get(index - 1).distance(polyline.get(index));
        }
        return total;
    }

    /**
     * Resample a clean quad's four side polylines and blend them into a discrete
     * Coons grid. The exact polylines are the fill boundary — two patches sharing a
     * side resample the same lifted points, so their grids coincide along it.
     * Side-to-Coons convention: side 0 (A→B) is u at v=0, side 2 canonical (D→C) is
     * u at v=1, side 3 canonical (A→D) is v at u=0, and side 1 (B→C) is v at u=1;
     * snapping each sampled side's endpoints to the averaged corner positions makes
     * the four corner identities required by {@link CoonsEvaluator} exact.
     *
     * @param curves clean patch whose {@code coonsGrid} gets filled
     */
    private void tessellate(LayoutPatchCurves curves) {
        Vector3f[][] sampledSides = new Vector3f[LayoutRectangle.SIDES][];
        for (int side = 0; side < LayoutRectangle.SIDES; side++) {
            Vector3f[] sampled = resampleSide(curves.sidePolylines.get(side), COONS_SAMPLES);
            sampled[0] = new Vector3f(curves.cornerPositions[SIDE_CORNERS[side][0]]);
            sampled[COONS_SAMPLES - 1] = new Vector3f(curves.cornerPositions[SIDE_CORNERS[side][1]]);
            sampledSides[side] = sampled;
        }
        curves.coonsGrid = CoonsEvaluator.blendGrid(
                sampledSides[0], sampledSides[2], sampledSides[3], sampledSides[1]);
    }

    /**
     * Resample a side polyline at arc-length-uniform stations; the first and last
     * samples are the polyline's exact endpoints.
     *
     * @param polyline side polyline with at least two points and positive length
     * @param samples  number of stations to produce
     * @return the resampled points in polyline order
     */
    private Vector3f[] resampleSide(List<Vector3f> polyline, int samples) {
        double[] cumulative = new double[polyline.size()];
        for (int index = 1; index < polyline.size(); index++) {
            cumulative[index] = cumulative[index - 1]
                    + polyline.get(index - 1).distance(polyline.get(index));
        }
        double total = cumulative[polyline.size() - 1];
        Vector3f[] sampled = new Vector3f[samples];
        int segment = 1;
        for (int station = 0; station < samples; station++) {
            double target = total * station / (samples - 1);
            while (segment < polyline.size() - 1 && cumulative[segment] < target) {
                segment++;
            }
            double segmentLength = cumulative[segment] - cumulative[segment - 1];
            double fraction = segmentLength <= 0.0 ? 0.0
                    : (target - cumulative[segment - 1]) / segmentLength;
            sampled[station] = new Vector3f(polyline.get(segment - 1))
                    .lerp(polyline.get(segment), (float) fraction);
        }
        return sampled;
    }

    /**
     * Reconstruct the node sequence around a patch cycle from its undirected arc id
     * list by chaining shared endpoints; entry {@code i} is the node before arc
     * {@code i}, with one extra closing entry equal to the first. Mirrors the
     * private walk in {@link TJunctionElimination}.
     *
     * @param cycleArcIds boundary arcs in cycle order
     * @return node ids of size {@code cycleArcIds.size() + 1}, or {@code null} when
     *         the arcs do not chain
     */
    private List<Integer> chainCycleNodes(List<Integer> cycleArcIds) {
        if (cycleArcIds.size() < 2) {
            return null;
        }
        TraceArc firstArc = motorcycleGraph.arcs.get(cycleArcIds.get(0));
        TraceArc secondArc = motorcycleGraph.arcs.get(cycleArcIds.get(1));
        int sharedNodeId;
        if (firstArc.endNodeId == secondArc.startNodeId || firstArc.endNodeId == secondArc.endNodeId) {
            sharedNodeId = firstArc.endNodeId;
        } else if (firstArc.startNodeId == secondArc.startNodeId
                || firstArc.startNodeId == secondArc.endNodeId) {
            sharedNodeId = firstArc.startNodeId;
        } else {
            return null;
        }
        List<Integer> nodeIds = new ArrayList<>();
        nodeIds.add(firstArc.startNodeId == sharedNodeId ? firstArc.endNodeId : firstArc.startNodeId);
        nodeIds.add(sharedNodeId);
        for (int position = 1; position < cycleArcIds.size(); position++) {
            TraceArc arc = motorcycleGraph.arcs.get(cycleArcIds.get(position));
            int fromNodeId = nodeIds.get(nodeIds.size() - 1);
            if (arc.startNodeId == fromNodeId) {
                nodeIds.add(arc.endNodeId);
            } else if (arc.endNodeId == fromNodeId) {
                nodeIds.add(arc.startNodeId);
            } else {
                return null;
            }
        }
        return nodeIds;
    }
}
