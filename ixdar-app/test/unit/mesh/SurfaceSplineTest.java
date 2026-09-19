package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.PlaneSurfaceLoop;
import ixdar.geometry.mesh.data.paths.SplineAnchorFit;
import ixdar.geometry.mesh.data.paths.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.SurfaceSplineTracer;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.selection.RingDslWriter;
import ixdar.geometry.mesh.nodes.selection.SplineRingNode;

/**
 * Closed splines on procedural surfaces: four anchors on a sphere's equator reproduce the great
 * circle, an anchor off it bends the curve through without a corner, and the anchor fit adds
 * anchors only where a round cross-section does not.
 */
class SurfaceSplineTest {

    /** Coordinates per point in every packed position here. */
    private static final int COORDINATES = 3;

    /** Sphere radius, so the equator is {@code 2 * pi} long. */
    private static final float SPHERE_RADIUS = 1f;

    /** Meridians of the procedural sphere; a multiple of four puts a vertex at every anchor. */
    private static final int SPHERE_SIDES = 96;

    /** Latitude lines of the procedural sphere, odd so one of them is the equator. */
    private static final int SPHERE_RINGS = 49;

    /** Relative slack the traced equator may take against the true great circle. */
    private static final double GREAT_CIRCLE_TOLERANCE = 0.005;

    /** Interior angle no traced polyline point may fall below, in degrees. */
    private static final double SMOOTHEST_CORNER_DEGREES = 150.0;

    /** How far off the equator the inserted anchor is pulled, in latitude bands. */
    private static final int OFF_CIRCLE_BANDS = 4;

    /** Radius of the procedural straight tube. */
    private static final float TUBE_RADIUS = 0.4f;

    /** Sides the procedural tubes are swept with. */
    private static final int TUBE_SIDES = 64;

    /** Cross-sections along the procedural tubes. */
    private static final int TUBE_RINGS = 65;

    /** Half the length of the procedural tubes, along x. */
    private static final float TUBE_HALF_LENGTH = 2f;

    /** Radius the tapered tube grows to at its far end, as a multiple of {@link #TUBE_RADIUS}. */
    private static final float TAPER_GROWTH = 3f;

    /** Tilt of the tapered tube's cut plane away from the axis, in radians. */
    private static final double TAPER_CUT_TILT = 0.6;

    @Test
    void fourAnchorsOnASphereReproduceTheGreatCircle() {
        MeshTopology sphere = sphere();
        float[] anchors = new float[COORDINATES * SplineAnchorFit.STARTING_ANCHORS];
        for (int anchor = 0; anchor < SplineAnchorFit.STARTING_ANCHORS; anchor++) {
            double angle = 2.0 * Math.PI * anchor / SplineAnchorFit.STARTING_ANCHORS;
            anchors[COORDINATES * anchor] = (float) (SPHERE_RADIUS * Math.cos(angle));
            anchors[COORDINATES * anchor + 1] = 0f;
            anchors[COORDINATES * anchor + 2] = (float) (SPHERE_RADIUS * Math.sin(angle));
        }
        SurfaceSpline spline =
                SurfaceSpline.through(sphere, anchors, SplineAnchorFit.STARTING_ANCHORS);

        double greatCircle = 2.0 * Math.PI * SPHERE_RADIUS;
        assertTrue(Math.abs(spline.length - greatCircle) <= GREAT_CIRCLE_TOLERANCE * greatCircle,
                "traced length " + spline.length + " is not within "
                        + 100.0 * GREAT_CIRCLE_TOLERANCE + "% of the great circle " + greatCircle);
        assertTrue(spline.minimumInteriorAngleDegrees > SMOOTHEST_CORNER_DEGREES,
                "sharpest corner " + spline.minimumInteriorAngleDegrees + " degrees");
        assertTrue(spline.markedEdgeCount > 0, "the spline snapped to no edge cycle");
        assertEquals(0, spline.unresolvedGaps);
    }

    @Test
    void anAnchorOffTheCircleBendsTheSplineThroughItWithoutACorner() {
        MeshTopology sphere = sphere();
        int anchorCount = SplineAnchorFit.STARTING_ANCHORS + 1;
        float[] anchors = new float[COORDINATES * anchorCount];
        for (int anchor = 0; anchor < SplineAnchorFit.STARTING_ANCHORS; anchor++) {
            double angle = 2.0 * Math.PI * anchor / SplineAnchorFit.STARTING_ANCHORS;
            anchors[COORDINATES * anchor] = (float) (SPHERE_RADIUS * Math.cos(angle));
            anchors[COORDINATES * anchor + 1] = 0f;
            anchors[COORDINATES * anchor + 2] = (float) (SPHERE_RADIUS * Math.sin(angle));
        }
        double latitude = Math.PI * OFF_CIRCLE_BANDS / (SPHERE_RINGS - 1);
        double longitude = 2.0 * Math.PI * (SPHERE_SIDES / 8.0) / SPHERE_SIDES;
        int last = COORDINATES * SplineAnchorFit.STARTING_ANCHORS;
        anchors[last] = (float) (SPHERE_RADIUS * Math.cos(latitude) * Math.cos(longitude));
        anchors[last + 1] = (float) (SPHERE_RADIUS * Math.sin(latitude));
        anchors[last + 2] = (float) (SPHERE_RADIUS * Math.cos(latitude) * Math.sin(longitude));

        float[] ordered = new float[COORDINATES * anchorCount];
        System.arraycopy(anchors, 0, ordered, 0, COORDINATES);
        System.arraycopy(anchors, last, ordered, COORDINATES, COORDINATES);
        System.arraycopy(anchors, COORDINATES, ordered, 2 * COORDINATES,
                COORDINATES * (SplineAnchorFit.STARTING_ANCHORS - 1));
        SurfaceSpline spline = SurfaceSpline.through(sphere, ordered, anchorCount);

        assertTrue(spline.minimumInteriorAngleDegrees > SMOOTHEST_CORNER_DEGREES,
                "sharpest corner " + spline.minimumInteriorAngleDegrees + " degrees");
        double nearest = Double.POSITIVE_INFINITY;
        for (int point = 0; point * COORDINATES < spline.polyline.length; point++) {
            double dx = spline.polyline[COORDINATES * point] - ordered[COORDINATES];
            double dy = spline.polyline[COORDINATES * point + 1] - ordered[COORDINATES + 1];
            double dz = spline.polyline[COORDINATES * point + 2] - ordered[COORDINATES + 2];
            nearest = Math.min(nearest, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        assertTrue(nearest < 1e-3, "the spline missed its own anchor by " + nearest);
        assertTrue(spline.length > 2.0 * Math.PI * SPHERE_RADIUS,
                "bending the ring off the equator should not shorten it");
    }

    @Test
    void aRoundCrossSectionNeedsNoAnchorBeyondTheFourItStartsWith() {
        MeshTopology tube = tube(1f);
        SplineAnchorFit fit = fitTo(tube, new float[] { 1f, 0f, 0f }, new float[] { 0f, 0f, 0f });

        assertEquals(SplineAnchorFit.STARTING_ANCHORS, fit.anchorCount,
                "a circular cut needed more than four anchors, deviation " + fit.deviation
                        + " against tolerance " + fit.tolerance);
        assertTrue(fit.withinTolerance);
        assertEquals(1, fit.traceCount);
    }

    @Test
    void aTaperedCrossSectionGainsAnchorsAndStaysInsideTheTolerance() {
        MeshTopology tube = tube(TAPER_GROWTH);
        float[] normal = {
            (float) Math.cos(TAPER_CUT_TILT), (float) Math.sin(TAPER_CUT_TILT), 0f };
        SplineAnchorFit fit = fitTo(tube, normal, new float[] { 0.5f, 0f, 0f });

        assertTrue(fit.anchorCount > SplineAnchorFit.STARTING_ANCHORS,
                "the tapered cut kept four anchors, deviation " + fit.deviation);
        assertTrue(fit.withinTolerance, "the fit stopped at " + fit.anchorCount
                + " anchors with deviation " + fit.deviation + " over " + fit.tolerance);
        SurfaceSpline spline = SurfaceSpline.of(fit.tracer);
        assertTrue(spline.minimumInteriorAngleDegrees > SMOOTHEST_CORNER_DEGREES,
                "sharpest corner " + spline.minimumInteriorAngleDegrees + " degrees");
    }

    private static SplineAnchorFit fitTo(MeshTopology mesh, float[] normal, float[] point) {
        PlaneSurfaceLoop cut = new PlaneSurfaceLoop();
        int startEdgeId = cut.nearestCrossingEdge(mesh, point, normal);
        assertTrue(startEdgeId >= 0, "the plane missed the tube");
        assertTrue(cut.walk(mesh, point, normal, startEdgeId), "the cut did not close");
        int[] nearestVertexId = new int[cut.stepCount];
        for (int step = 0; step < cut.stepCount; step++) {
            int halfEdge = mesh.edgeHalfEdge(cut.edgeId[step]);
            nearestVertexId[step] = cut.crossingFraction[step] <= 0.5
                    ? mesh.halfEdgeVertex(halfEdge)
                    : mesh.halfEdgeEndVertex(halfEdge);
        }
        SplineAnchorFit fit =
                new SplineAnchorFit(new SurfaceSplineTracer(SurfaceGeodesics.over(mesh)));
        assertTrue(fit.fit(cut.polyline, cut.stepCount, nearestVertexId, 0), "the fit failed");
        return fit;
    }

    /** A sphere of latitude rings between two poles, fine enough to carry a smooth ring. */
    private static MeshTopology sphere() {
        int interiorRings = SPHERE_RINGS - 2;
        float[] positions = new float[COORDINATES * (2 + interiorRings * SPHERE_SIDES)];
        positions[1] = SPHERE_RADIUS;
        for (int ring = 0; ring < interiorRings; ring++) {
            double latitude = Math.PI * (ring + 1) / (SPHERE_RINGS - 1);
            for (int side = 0; side < SPHERE_SIDES; side++) {
                double longitude = 2.0 * Math.PI * side / SPHERE_SIDES;
                int base = COORDINATES * (1 + SPHERE_SIDES * ring + side);
                positions[base] =
                        (float) (SPHERE_RADIUS * Math.sin(latitude) * Math.cos(longitude));
                positions[base + 1] = (float) (SPHERE_RADIUS * Math.cos(latitude));
                positions[base + 2] =
                        (float) (SPHERE_RADIUS * Math.sin(latitude) * Math.sin(longitude));
            }
        }
        int south = 1 + interiorRings * SPHERE_SIDES;
        positions[COORDINATES * south + 1] = -SPHERE_RADIUS;
        int[] faces =
                new int[COORDINATES * SPHERE_SIDES * (2 * interiorRings)];
        int corner = 0;
        for (int side = 0; side < SPHERE_SIDES; side++) {
            int next = (side + 1) % SPHERE_SIDES;
            faces[corner++] = 0;
            faces[corner++] = 1 + next;
            faces[corner++] = 1 + side;
        }
        for (int ring = 0; ring + 1 < interiorRings; ring++) {
            for (int side = 0; side < SPHERE_SIDES; side++) {
                int next = (side + 1) % SPHERE_SIDES;
                int here = 1 + SPHERE_SIDES * ring + side;
                int ahead = 1 + SPHERE_SIDES * ring + next;
                int below = 1 + SPHERE_SIDES * (ring + 1) + side;
                int belowAhead = 1 + SPHERE_SIDES * (ring + 1) + next;
                faces[corner++] = here;
                faces[corner++] = ahead;
                faces[corner++] = belowAhead;
                faces[corner++] = here;
                faces[corner++] = belowAhead;
                faces[corner++] = below;
            }
        }
        for (int side = 0; side < SPHERE_SIDES; side++) {
            int next = (side + 1) % SPHERE_SIDES;
            faces[corner++] = south;
            faces[corner++] = 1 + SPHERE_SIDES * (interiorRings - 1) + side;
            faces[corner++] = 1 + SPHERE_SIDES * (interiorRings - 1) + next;
        }
        MeshTopology mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faces);
        assertNotNull(mesh);
        return mesh;
    }

    /**
     * A tube along x whose radius grows linearly from {@link #TUBE_RADIUS} to that times
     * {@code growth}, so {@code growth == 1} is the straight tube.
     */
    private static MeshTopology tube(float growth) {
        float[] positions = new float[COORDINATES * TUBE_SIDES * TUBE_RINGS];
        for (int ring = 0; ring < TUBE_RINGS; ring++) {
            float along = (float) ring / (TUBE_RINGS - 1);
            float x = -TUBE_HALF_LENGTH + 2f * TUBE_HALF_LENGTH * along;
            float radius = TUBE_RADIUS * (1f + (growth - 1f) * along);
            for (int side = 0; side < TUBE_SIDES; side++) {
                double angle = 2.0 * Math.PI * side / TUBE_SIDES;
                int base = COORDINATES * (TUBE_SIDES * ring + side);
                positions[base] = x;
                positions[base + 1] = (float) (radius * Math.cos(angle));
                positions[base + 2] = (float) (radius * Math.sin(angle));
            }
        }
        int[] faces = new int[2 * COORDINATES * TUBE_SIDES * (TUBE_RINGS - 1)];
        int corner = 0;
        for (int ring = 0; ring + 1 < TUBE_RINGS; ring++) {
            for (int side = 0; side < TUBE_SIDES; side++) {
                int next = (side + 1) % TUBE_SIDES;
                int here = TUBE_SIDES * ring + side;
                int ahead = TUBE_SIDES * ring + next;
                int over = TUBE_SIDES * (ring + 1) + side;
                int overAhead = TUBE_SIDES * (ring + 1) + next;
                faces[corner++] = here;
                faces[corner++] = over;
                faces[corner++] = overAhead;
                faces[corner++] = here;
                faces[corner++] = overAhead;
                faces[corner++] = ahead;
            }
        }
        return HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faces);
    }

    @Test
    void theWrittenStatementReloadsTheSameRing() {
        MeshTopology tube = tube(TAPER_GROWTH);
        float[] normal = {
            (float) Math.cos(TAPER_CUT_TILT), (float) Math.sin(TAPER_CUT_TILT), 0f };
        SplineAnchorFit fit = fitTo(tube, normal, new float[] { 0.5f, 0f, 0f });
        SurfaceSpline live = SurfaceSpline.of(fit.tracer);
        float[] written = SurfaceWaypoints.parse(
                SurfaceWaypoints.format(live.anchorXyz, live.anchorCount));

        SplineRingNode node = new SplineRingNode();
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput(SplineRingNode.GEOMETRY.name, GeometryBundle.ofMesh(tube));
        ctx.setInput(SplineRingNode.POINTS.name,
                SurfaceWaypoints.format(written, live.anchorCount));
        node.evaluate(ctx);
        GeometryBundle reloaded =
                ctx.getOutput(SplineRingNode.GEOMETRY.name, GeometryBundle.class);

        boolean[] marks = EdgeMarks.bools(reloaded, SplineRingNode.DEFAULT_MARK_LABEL);
        assertArrayEquals(live.markedByEdgeId, marks,
                "the spline_ring statement reloaded a different edge cycle than the live ring; "
                        + "live " + EdgeMarks.fingerprint(tube, live.markedByEdgeId)
                        + " reloaded " + EdgeMarks.fingerprint(tube, marks));
    }

    @Test
    void theWriterIsByteStableAndRewritesInPlace() {
        String graph = "carrier = load_mesh(path=\"x.off\")";
        float[] anchors = { 0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f };
        String appended = RingDslWriter.appendSpline(graph, anchors, SplineAnchorFit.STARTING_ANCHORS);

        assertEquals(appended, RingDslWriter.appendSpline(graph, anchors,
                SplineAnchorFit.STARTING_ANCHORS), "the writer is not byte-stable");
        String id = RingDslWriter.nextRingId(graph);
        assertTrue(appended.contains(id + " = spline_ring(geometry=carrier.geometry"), appended);
        float[] moved = { 0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f, 0f, 0.5f, 0f };
        String rewritten = RingDslWriter.replaceSpline(appended, id, moved,
                SplineAnchorFit.STARTING_ANCHORS + 1);
        assertEquals(appended.lines().count(), rewritten.lines().count(),
                "rewriting a ring in place should not add a statement");
        assertTrue(rewritten.contains("0.000000,0.500000,0.000000"), rewritten);
    }
}
