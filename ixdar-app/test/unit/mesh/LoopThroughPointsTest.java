package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.data.paths.query.NearestVertex;
import ixdar.geometry.mesh.data.rings.SurfaceRing;
import ixdar.geometry.mesh.data.paths.spline.SurfaceWaypoints;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.nodegraph.api.MapNodeContext;
import ixdar.nodegraph.nodes.primitives.TorusMeshNode;
import ixdar.nodegraph.nodes.selection.LoopThroughPointsNode;
import ixdar.tools.ring.RingDslWriter;

/**
 * Rings on a procedural torus: three tube points close and tighten to the minimal meridian, the
 * same points in another order give the same ring, two points are refused, and the statement the
 * ring saves as re-evaluates element-exact.
 */
class LoopThroughPointsTest {

    private static final float MAJOR_RADIUS = 1.0f;

    private static final float MINOR_RADIUS = 0.35f;

    private static final int MAJOR_SEGMENTS = 64;

    private static final int MINOR_SEGMENTS = 48;

    private static final PackedPoints TUBE_POINTS = SurfaceWaypoints.parse(
            "1.350000,0.000000,0.000000; 0.445749,0.303109,0.694214; 0.512828,-0.303109,-0.646245");

    private static final List<Vector3f> TUBE_WAYPOINTS = SurfaceWaypoints.points(TUBE_POINTS);

    private static final int TUBE_POINT_COUNT = 3;

    private static final float WAIST_TUBE_RADIUS = 0.4f;

    private static final float WAIST_PINCH = 0.5f;

    private static final float WAIST_WIDTH = 0.6f;

    private static final float WAIST_TUBE_HALF_LENGTH = 2f;

    private static final int WAIST_TUBE_SIDES = 40;

    private static final int WAIST_TUBE_RINGS = 41;

    private static final float PIN_X = 1f;

    private static final int PINNED_WAYPOINTS = 3;

    private static final String TORUS_GRAPH =
            "carrier = torus(major_radius=1.0, minor_radius=0.35, major_segments=64, "
                    + "minor_segments=48, triangulate=true)\n";

    private static final String NOT_BYTE_STABLE = "the writer is not byte-stable";

    @Test
    void threeTubePointsCloseAndTightenToTheMinimalMeridian() {
        MeshTopology torus = torus();

        SurfaceRing ring = SurfaceRing.through(torus, TUBE_POINTS, TUBE_POINT_COUNT, true, 0, -1);

        double analytic = 2.0 * Math.PI * MINOR_RADIUS;
        Vector3f centroid = ring.path.centroid;
        assertTrue(Math.abs(ring.path.length - analytic) / analytic < 0.01,
                "ring length " + ring.path.length + " is not within 1% of the analytic meridian "
                        + analytic);
        assertTrue(ring.seed.length > ring.path.length,
                "the seed walk " + ring.seed.length + " should be longer than the geodesic "
                        + ring.path.length);
        assertTrue(ring.marks.markedEdgeCount >= MINOR_SEGMENTS,
                "a meridian should mark at least one edge per tube segment, marked "
                        + ring.marks.markedEdgeCount);
        assertTrue(ring.path.closed, "the ring path does not close on its first point");
        assertTrue(Math.abs((double) centroid.x * centroid.x + (double) centroid.z * centroid.z
                - MAJOR_RADIUS * MAJOR_RADIUS) < 0.01,
                "the ring centroid should sit on the torus centre line, not at "
                        + centroid.x + ", " + centroid.z);
    }

    @Test
    void thePointsInAnotherOrderGiveTheSameRing() {
        MeshTopology torus = torus();
        PackedPoints rotated = new PackedPoints(TUBE_POINT_COUNT);
        for (int point = 0; point < TUBE_POINT_COUNT; point++) {
            rotated.set(point, TUBE_POINTS.at((point + 1) % TUBE_POINT_COUNT));
        }

        SurfaceRing authored =
                SurfaceRing.through(torus, TUBE_POINTS, TUBE_POINT_COUNT, true, 0, -1);
        SurfaceRing reordered =
                SurfaceRing.through(torus, rotated, TUBE_POINT_COUNT, true, 0, -1);

        assertEquals(EdgeMarks.fingerprint(torus, authored.marks.markedByEdgeId),
                EdgeMarks.fingerprint(torus, reordered.marks.markedByEdgeId),
                "rotating the point list moved the ring");
        assertEquals(authored.marks.markedEdgeCount, reordered.marks.markedEdgeCount);
        assertEquals(authored.path.length, reordered.path.length, 1e-9);
    }

    @Test
    void twoPointsAreRefusedWithAReadableError() {
        MeshTopology torus = torus();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SurfaceRing.through(torus, TUBE_POINTS, 2, true, 0, -1));

        assertTrue(failure.getMessage().contains("at least 3 surface points"),
                "unhelpful message: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("third side point"),
                "the message should say what a third point buys: " + failure.getMessage());
    }

    @Test
    void theSavedStatementReEvaluatesToTheSameRing() throws Exception {
        MeshTopology torus = torus();
        SurfaceRing live = SurfaceRing.through(torus, TUBE_POINTS, TUBE_POINT_COUNT, true, 0, -1);

        String saved =
                RingDslWriter.append(TORUS_GRAPH, TUBE_WAYPOINTS, TUBE_POINT_COUNT, true, false);
        System.out.println("[ring] saved statement: " + saved.substring(TORUS_GRAPH.length()));
        NodeGraphRuntime runtime = NodeGraphRuntime.fromSource(saved);
        runtime.executeGraphResult(runtime.lastId(), LoopThroughPointsNode.GEOMETRY.name);
        GeometryBundle reloaded = (GeometryBundle) runtime.lastOutput(
                LoopThroughPointsNode.GEOMETRY.name);

        boolean[] marks = EdgeMarks.bools(reloaded, RingDslWriter.nextRingId(TORUS_GRAPH));
        assertArrayEquals(live.marks.markedByEdgeId, marks,
                "the reloaded ring marks different edges than the live one");
        assertEquals(EdgeMarks.fingerprint(torus, live.marks.markedByEdgeId),
                EdgeMarks.fingerprint(reloaded.mesh(), marks));
        assertEquals(saved,
                RingDslWriter.append(TORUS_GRAPH, TUBE_WAYPOINTS, TUBE_POINT_COUNT, true, false),
                NOT_BYTE_STABLE);
    }

    @Test
    void aPinnedRingStaysAtThePinWhileAFreeOneSlidesToTheWaist() {
        MeshTopology tube = waistedTube();
        PackedPoints points = ringAround(PIN_X, PINNED_WAYPOINTS);

        SurfaceRing free = SurfaceRing.through(tube, points, PINNED_WAYPOINTS, true, 0, -1);
        SurfaceRing pinned =
                SurfaceRing.through(tube, points, PINNED_WAYPOINTS, true, PINNED_WAYPOINTS, -1);

        double freeX = free.path.centroid.x;
        double pinnedX = pinned.path.centroid.x;
        assertTrue(Math.abs(freeX) < 0.2f,
                "the unpinned ring should slide to the waist at x = 0, not sit at " + freeX);
        assertTrue(Math.abs(pinnedX - PIN_X) < 0.1f,
                "the pinned ring left its pin at x = " + PIN_X + " for " + pinnedX);
        assertTrue(pinned.path.length > free.path.length,
                "the pinned ring " + pinned.path.length
                        + " should be longer than the waist geodesic " + free.path.length);
        for (int waypoint = 0; waypoint < PINNED_WAYPOINTS; waypoint++) {
            assertTrue(passesThrough(pinned, tube, pinned.waypointVertexIds[waypoint]),
                    "the pinned ring dropped waypoint " + waypoint);
        }
    }

    @Test
    void thePinnedRingReloadsElementExact() {
        MeshTopology tube = waistedTube();
        SurfaceRing live = SurfaceRing.through(tube, ringAround(PIN_X, PINNED_WAYPOINTS),
                PINNED_WAYPOINTS, true, PINNED_WAYPOINTS, -1);
        int loopPoints = live.path.pointCount;
        PackedPoints sampled = new PackedPoints(PINNED_WAYPOINTS);
        for (int waypoint = 0; waypoint < PINNED_WAYPOINTS; waypoint++) {
            int point = (int) (Math.round((double) loopPoints * waypoint / PINNED_WAYPOINTS)
                    % loopPoints);
            sampled.set(waypoint, live.path.positions.at(point));
        }
        int[] vertexIds = SurfaceWaypoints.snap(NearestVertex.over(tube), sampled,
                PINNED_WAYPOINTS);
        PackedPoints chosen = new PackedPoints(PINNED_WAYPOINTS);
        for (int waypoint = 0; waypoint < PINNED_WAYPOINTS; waypoint++) {
            chosen.set(waypoint, tube.vertexPosition(vertexIds[waypoint], new Vector3f()));
        }
        PackedPoints written = SurfaceWaypoints.parse(SurfaceWaypoints.format(chosen,
                PINNED_WAYPOINTS));

        LoopThroughPointsNode node = new LoopThroughPointsNode();
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput(LoopThroughPointsNode.GEOMETRY.name, GeometryBundle.ofMesh(tube));
        ctx.setInput(LoopThroughPointsNode.POINTS.name,
                SurfaceWaypoints.format(written, PINNED_WAYPOINTS));
        ctx.setInput(LoopThroughPointsNode.PIN.name, true);
        node.evaluate(ctx);
        GeometryBundle reloaded =
                ctx.getOutput(LoopThroughPointsNode.GEOMETRY.name, GeometryBundle.class);

        boolean[] marks = EdgeMarks.bools(reloaded, LoopThroughPointsNode.DEFAULT_MARK_LABEL);
        System.out.println("[pin] reloaded " + EdgeMarks.fingerprint(tube, marks) + " from "
                + PINNED_WAYPOINTS + " waypoints written as \"" + SurfaceWaypoints.format(written,
                PINNED_WAYPOINTS) + "\"");
        assertArrayEquals(live.marks.markedByEdgeId, marks,
                "the pinned statement reloaded a different ring than the live one");
    }

    @Test
    void theWrittenStatementCarriesThePinFlag() {
        String saved =
                RingDslWriter.append(TORUS_GRAPH, TUBE_WAYPOINTS, TUBE_POINT_COUNT, true, true);

        System.out.println("[pin] saved statement: " + saved.substring(TORUS_GRAPH.length()));
        assertTrue(saved.contains("pin=true"), "the pinned statement does not say so: " + saved);
        assertEquals(saved, RingDslWriter.append(TORUS_GRAPH, TUBE_WAYPOINTS, TUBE_POINT_COUNT, true,
                true), NOT_BYTE_STABLE);
    }

    /**
     * Whether the tightened ring turns at, or marks an edge of, one vertex.
     */
    private static boolean passesThrough(SurfaceRing ring, MeshTopology mesh, int vertexId) {
        for (int corner : ring.pathVertexIds) {
            if (corner == vertexId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Points spread around the tapered tube's surface at one station along its axis.
     */
    private static PackedPoints ringAround(float x, int pointCount) {
        float radius = waistRadius(x);
        PackedPoints points = new PackedPoints(pointCount);
        for (int point = 0; point < pointCount; point++) {
            double angle = 2.0 * Math.PI * point / pointCount;
            points.set(point, x, (float) (radius * Math.cos(angle)),
                    (float) (radius * Math.sin(angle)));
        }
        return points;
    }

    /** Radius of the tapered tube at one station along its axis. */
    private static float waistRadius(float x) {
        return (float) (WAIST_TUBE_RADIUS
                * (1.0 - WAIST_PINCH * Math.exp(-(x * x) / (WAIST_WIDTH * WAIST_WIDTH))));
    }

    /**
     * A tube along x pinched to half its radius at the origin, so the only free geodesic loop is
     * the waist and a ring anywhere else has to be pinned to stay there.
     */
    private static MeshTopology waistedTube() {
        PackedPoints positions = new PackedPoints(WAIST_TUBE_SIDES * WAIST_TUBE_RINGS);
        for (int ring = 0; ring < WAIST_TUBE_RINGS; ring++) {
            float x = -WAIST_TUBE_HALF_LENGTH
                    + 2f * WAIST_TUBE_HALF_LENGTH * ring / (WAIST_TUBE_RINGS - 1);
            float radius = waistRadius(x);
            for (int side = 0; side < WAIST_TUBE_SIDES; side++) {
                double angle = 2.0 * Math.PI * side / WAIST_TUBE_SIDES;
                positions.set(WAIST_TUBE_SIDES * ring + side, x, (float) (radius * Math.cos(angle)),
                        (float) (radius * Math.sin(angle)));
            }
        }
        int[] faces = new int[2 * 3 * WAIST_TUBE_SIDES * (WAIST_TUBE_RINGS - 1)];
        int corner = 0;
        for (int ring = 0; ring + 1 < WAIST_TUBE_RINGS; ring++) {
            for (int side = 0; side < WAIST_TUBE_SIDES; side++) {
                int next = (side + 1) % WAIST_TUBE_SIDES;
                int here = WAIST_TUBE_SIDES * ring + side;
                int ahead = WAIST_TUBE_SIDES * ring + next;
                int over = WAIST_TUBE_SIDES * (ring + 1) + side;
                int overAhead = WAIST_TUBE_SIDES * (ring + 1) + next;
                faces[corner++] = here;
                faces[corner++] = over;
                faces[corner++] = overAhead;
                faces[corner++] = here;
                faces[corner++] = overAhead;
                faces[corner++] = ahead;
            }
        }
        return HalfEdgeMeshOps.buildFromIndexedMesh(positions, faces);
    }

    private static MeshTopology torus() {
        TorusMeshNode node = new TorusMeshNode();
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput("major_radius", MAJOR_RADIUS);
        ctx.setInput("minor_radius", MINOR_RADIUS);
        ctx.setInput("major_segments", MAJOR_SEGMENTS);
        ctx.setInput("minor_segments", MINOR_SEGMENTS);
        ctx.setInput("triangulate", true);
        node.evaluate(ctx);
        return ctx.getOutput("mesh", GeometryBundle.class).mesh();
    }
}
