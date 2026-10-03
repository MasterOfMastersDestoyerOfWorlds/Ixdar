package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.AuthoredSplineRing;
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

    private static final int COORDINATES = 3;

    // The equator is 2 pi long.
    private static final float SPHERE_RADIUS = 1f;

    // A multiple of four puts a vertex at every anchor.
    private static final int SPHERE_SIDES = 96;

    // Odd, so one latitude line is the equator.
    private static final int SPHERE_RINGS = 49;

    private static final double GREAT_CIRCLE_TOLERANCE = 0.005;

    private static final double SMOOTHEST_CORNER_DEGREES = 150.0;

    private static final int OFF_CIRCLE_BANDS = 4;

    private static final float TUBE_RADIUS = 0.4f;

    private static final int TUBE_SIDES = 64;

    private static final int TUBE_RINGS = 65;

    private static final float TUBE_HALF_LENGTH = 2f;

    // Multiple of TUBE_RADIUS the tapered tube grows to at its far end.
    private static final float TAPER_GROWTH = 3f;

    // Radians.
    private static final double TAPER_CUT_TILT = 0.6;

    private static final float[] AXIS = { 1f, 0f, 0f };

    private static final int NEAR_SIDE_RING = 30;

    private static final int FAR_SIDE_RING = 35;

    private static final int RANDOM_INSERTIONS = 20;

    private static final int RANDOM_RING_REACH = 2;

    private static final long RANDOM_SEED = 30L;

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
        float[] normal = SurfaceWaypoints.parse(SurfaceWaypoints.format(new float[] {
            (float) Math.cos(TAPER_CUT_TILT), (float) Math.sin(TAPER_CUT_TILT), 0f }, 1));
        int[] authored = tubeVertices(tube, NEAR_SIDE_RING, 0, FAR_SIDE_RING, TUBE_SIDES / 2);
        AuthoredSplineRing ring = new AuthoredSplineRing(SurfaceGeodesics.over(tube));
        assertTrue(ring.trace(authored, authored.length, normal,
                SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH), ring.failure);
        SurfaceSpline live = SurfaceSpline.of(ring.tracer);
        int authoredAnchors = ring.authoredVertexId.length;
        float[] authoredXyz = positionsOf(tube, ring.authoredVertexId, authoredAnchors);

        SplineRingNode node = new SplineRingNode();
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput(SplineRingNode.GEOMETRY.name, GeometryBundle.ofMesh(tube));
        ctx.setInput(SplineRingNode.POINTS.name,
                SurfaceWaypoints.format(authoredXyz, authoredAnchors));
        ctx.setInput(SplineRingNode.NORMAL.name, SurfaceWaypoints.format(normal, 1));
        node.evaluate(ctx);
        GeometryBundle reloaded =
                ctx.getOutput(SplineRingNode.GEOMETRY.name, GeometryBundle.class);

        assertTrue(live.anchorCount > live.authoredCount(),
                "the fit added no supporting anchor, so the reload proves nothing about re-fitting");
        boolean[] marks = EdgeMarks.bools(reloaded, SplineRingNode.DEFAULT_MARK_LABEL);
        assertArrayEquals(live.markedByEdgeId, marks,
                "the spline_ring statement reloaded a different edge cycle than the live ring; "
                        + "live " + EdgeMarks.fingerprint(tube, live.markedByEdgeId)
                        + " reloaded " + EdgeMarks.fingerprint(tube, marks));
    }

    @Test
    void aDraftWithTwoAuthoredAnchorsPassesThroughBoth() {
        MeshTopology tube = tube(TAPER_GROWTH);
        int[] authored = tubeVertices(tube, NEAR_SIDE_RING, 0, FAR_SIDE_RING, TUBE_SIDES / 2);
        AuthoredSplineRing ring = new AuthoredSplineRing(SurfaceGeodesics.over(tube));
        assertTrue(ring.trace(authored, authored.length, AXIS,
                SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH), ring.failure);
        SurfaceSpline spline = SurfaceSpline.of(ring.tracer);

        assertEquals(2, spline.authoredCount());
        float[] anchorXyz = positionsOf(tube, authored, authored.length);
        int points = spline.polyline.length / COORDINATES;
        for (int anchor = 0; anchor < authored.length; anchor++) {
            double miss = SurfaceSpline.distanceToPolyline(spline.polyline, points,
                    anchorXyz[COORDINATES * anchor], anchorXyz[COORDINATES * anchor + 1],
                    anchorXyz[COORDINATES * anchor + 2]);
            assertTrue(miss <= ring.tracer.geodesics.meanEdgeLength, "authored anchor " + anchor
                    + " is " + miss + " off the ring, over one mean edge "
                    + ring.tracer.geodesics.meanEdgeLength);
        }
        assertTrue(spline.minimumInteriorAngleDegrees > SMOOTHEST_CORNER_DEGREES,
                "sharpest corner " + spline.minimumInteriorAngleDegrees + " degrees");
    }

    @Test
    void removingAnAuthoredAnchorRestoresTheOneAnchorRingExactly() {
        MeshTopology tube = tube(TAPER_GROWTH);
        int[] authored = tubeVertices(tube, NEAR_SIDE_RING, 0, FAR_SIDE_RING, TUBE_SIDES / 2);
        AuthoredSplineRing ring = new AuthoredSplineRing(SurfaceGeodesics.over(tube));
        assertTrue(ring.trace(authored, 1, AXIS, SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH));
        SurfaceSpline before = SurfaceSpline.of(ring.tracer);
        assertTrue(ring.trace(authored, 2, AXIS, SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH));
        SurfaceSpline bent = SurfaceSpline.of(ring.tracer);
        assertTrue(ring.trace(authored, 1, AXIS, SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH));
        SurfaceSpline after = SurfaceSpline.of(ring.tracer);

        assertEquals(1, before.authoredCount());
        assertFalse(Arrays.equals(before.markedByEdgeId, bent.markedByEdgeId),
                "the second anchor did not move the ring");
        assertArrayEquals(before.anchorVertexId, after.anchorVertexId);
        assertArrayEquals(before.polyline, after.polyline);
        assertArrayEquals(before.markedByEdgeId, after.markedByEdgeId);
    }

    @Test
    void theFitNeverMovesOrRemovesAnAuthoredAnchor() {
        MeshTopology tube = tube(TAPER_GROWTH);
        AuthoredSplineRing ring = new AuthoredSplineRing(SurfaceGeodesics.over(tube));
        Random random = new Random(RANDOM_SEED);
        int[] authored = new int[RANDOM_INSERTIONS + 1];
        authored[0] = tubeVertices(tube, NEAR_SIDE_RING, 0)[0];
        int count = 1;
        for (int insertion = 0; insertion < RANDOM_INSERTIONS; insertion++) {
            int tubeRing = NEAR_SIDE_RING + random.nextInt(2 * RANDOM_RING_REACH + 1)
                    - RANDOM_RING_REACH;
            authored[count++] = tubeVertices(tube, tubeRing, random.nextInt(TUBE_SIDES))[0];
            assertTrue(ring.trace(authored, count, AXIS, AuthoredSplineRing.SUPPORTING_FIT_DEPTH),
                    "insertion " + insertion + ": " + ring.failure);
            int distinct = (int) Arrays.stream(authored, 0, count).distinct().count();
            assertEquals(distinct, ring.authoredVertexId.length, "insertion " + insertion);
            assertEquals(authored[0], ring.tracer.anchorVertexId[0],
                    "the first authored anchor no longer leads the ring");
            for (int anchor = 0; anchor < count; anchor++) {
                boolean held = false;
                for (int traced = 0; traced < ring.tracer.anchorCount; traced++) {
                    held |= ring.tracer.anchorVertexId[traced] == authored[anchor]
                            && ring.tracer.anchorAuthored[traced];
                }
                assertTrue(held, "insertion " + insertion + " lost authored anchor " + anchor);
            }
        }
    }

    /** Vertex ids of tube vertices given as (cross-section, side) pairs. */
    private static int[] tubeVertices(MeshTopology tube, int... ringAndSide) {
        float[] xyz = new float[COORDINATES * ringAndSide.length / 2];
        float step = 2f * TUBE_HALF_LENGTH / (TUBE_RINGS - 1);
        for (int pair = 0; pair < ringAndSide.length / 2; pair++) {
            float along = (float) ringAndSide[2 * pair] / (TUBE_RINGS - 1);
            float radius = TUBE_RADIUS * (1f + (TAPER_GROWTH - 1f) * along);
            double angle = 2.0 * Math.PI * ringAndSide[2 * pair + 1] / TUBE_SIDES;
            xyz[COORDINATES * pair] = -TUBE_HALF_LENGTH + step * ringAndSide[2 * pair];
            xyz[COORDINATES * pair + 1] = (float) (radius * Math.cos(angle));
            xyz[COORDINATES * pair + 2] = (float) (radius * Math.sin(angle));
        }
        return SurfaceWaypoints.snap(tube, xyz, ringAndSide.length / 2);
    }

    private static float[] positionsOf(MeshTopology mesh, int[] vertexIds, int count) {
        float[] xyz = new float[COORDINATES * count];
        Vector3f position = new Vector3f();
        for (int anchor = 0; anchor < count; anchor++) {
            mesh.vertexPosition(vertexIds[anchor], position);
            xyz[COORDINATES * anchor] = position.x;
            xyz[COORDINATES * anchor + 1] = position.y;
            xyz[COORDINATES * anchor + 2] = position.z;
        }
        return xyz;
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
