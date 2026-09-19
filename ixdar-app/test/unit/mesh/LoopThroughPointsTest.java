package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.SurfaceRing;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.primitives.TorusMeshNode;
import ixdar.geometry.mesh.nodes.selection.LoopThroughPointsNode;
import ixdar.geometry.mesh.nodes.selection.RingDslWriter;
import ixdar.parsing.python.PythonParser;

/**
 * Rings on a procedural torus: three tube points close and tighten to the minimal meridian, the
 * same points in another order give the same ring, two points are refused, and the statement the
 * ring saves as re-evaluates element-exact.
 */
class LoopThroughPointsTest {

    /** Torus centre-line radius. */
    private static final float MAJOR_RADIUS = 1.0f;

    /** Torus tube radius; the minimal meridian is {@code 2 * pi * MINOR_RADIUS}. */
    private static final float MINOR_RADIUS = 0.35f;

    /** Faces the long way around the torus. */
    private static final int MAJOR_SEGMENTS = 64;

    /** Faces around the torus tube. */
    private static final int MINOR_SEGMENTS = 48;

    /** The fixture's three tube points, the ones {@code torus_loop_through_points.dsl} uses. */
    private static final float[] TUBE_POINTS = {
        1.350000f, 0.000000f, 0.000000f,
        0.445749f, 0.303109f, 0.694214f,
        0.512828f, -0.303109f, -0.646245f };

    /** Waypoints in {@link #TUBE_POINTS}. */
    private static final int TUBE_POINT_COUNT = 3;

    /** Widest radius of the procedural tapered tube. */
    private static final float WAIST_TUBE_RADIUS = 0.4f;

    /** Fraction of {@link #WAIST_TUBE_RADIUS} the waist pinches away at x = 0. */
    private static final float WAIST_PINCH = 0.5f;

    /** How quickly the waist opens back out, in x. */
    private static final float WAIST_WIDTH = 0.6f;

    /** Half the length of the procedural tapered tube, along x. */
    private static final float WAIST_TUBE_HALF_LENGTH = 2f;

    /** Sides the procedural tapered tube is swept with. */
    private static final int WAIST_TUBE_SIDES = 40;

    /** Cross-sections along the procedural tapered tube. */
    private static final int WAIST_TUBE_RINGS = 41;

    /** Where along x the pinned ring is authored, well away from the waist at x = 0. */
    private static final float PIN_X = 1f;

    /** Waypoints the pinned ring is authored and re-evaluated through. */
    private static final int PINNED_WAYPOINTS = 3;

    /** The graph a saved ring statement is appended to. */
    private static final String TORUS_GRAPH =
            "carrier = torus(major_radius=1.0, minor_radius=0.35, major_segments=64, "
                    + "minor_segments=48, triangulate=true)\n";

    /** Failure message when writing the same ring twice does not give the same text. */
    private static final String NOT_BYTE_STABLE = "the writer is not byte-stable";

    @Test
    void threeTubePointsCloseAndTightenToTheMinimalMeridian() {
        MeshTopology torus = torus();

        SurfaceRing ring = SurfaceRing.through(torus, TUBE_POINTS, TUBE_POINT_COUNT, true, 0, -1);

        double analytic = 2.0 * Math.PI * MINOR_RADIUS;
        System.out.println("[ring] seed=" + ring.seedLength + " tightened=" + ring.length
                + " analytic=" + analytic + " edges=" + ring.markedEdgeCount
                + " centroid=(" + ring.centroidX + ", " + ring.centroidY + ", "
                + ring.centroidZ + ")");
        assertTrue(Math.abs(ring.length - analytic) / analytic < 0.01,
                "ring length " + ring.length + " is not within 1% of the analytic meridian "
                        + analytic);
        assertTrue(ring.seedLength > ring.length,
                "the seed walk " + ring.seedLength + " should be longer than the geodesic "
                        + ring.length);
        assertTrue(ring.markedEdgeCount >= MINOR_SEGMENTS,
                "a meridian should mark at least one edge per tube segment, marked "
                        + ring.markedEdgeCount);
        int coordinates = SurfaceWaypoints.COORDINATES_PER_WAYPOINT;
        assertArrayEquals(
                new float[] {
                    ring.polyline[0], ring.polyline[1], ring.polyline[2] },
                new float[] {
                    ring.polyline[ring.polyline.length - coordinates],
                    ring.polyline[ring.polyline.length - coordinates + 1],
                    ring.polyline[ring.polyline.length - coordinates + 2] },
                0.0f, "the ring polyline does not close on its first point");
        assertTrue(Math.abs(ring.centroidX * ring.centroidX + ring.centroidZ * ring.centroidZ
                - MAJOR_RADIUS * MAJOR_RADIUS) < 0.01,
                "the ring centroid should sit on the torus centre line, not at "
                        + ring.centroidX + ", " + ring.centroidZ);
    }

    @Test
    void thePointsInAnotherOrderGiveTheSameRing() {
        MeshTopology torus = torus();
        float[] rotated = {
            TUBE_POINTS[3], TUBE_POINTS[4], TUBE_POINTS[5],
            TUBE_POINTS[6], TUBE_POINTS[7], TUBE_POINTS[8],
            TUBE_POINTS[0], TUBE_POINTS[1], TUBE_POINTS[2] };

        SurfaceRing authored =
                SurfaceRing.through(torus, TUBE_POINTS, TUBE_POINT_COUNT, true, 0, -1);
        SurfaceRing reordered =
                SurfaceRing.through(torus, rotated, TUBE_POINT_COUNT, true, 0, -1);

        assertEquals(EdgeMarks.fingerprint(torus, authored.markedByEdgeId),
                EdgeMarks.fingerprint(torus, reordered.markedByEdgeId),
                "rotating the point list moved the ring");
        assertEquals(authored.markedEdgeCount, reordered.markedEdgeCount);
        assertEquals(authored.length, reordered.length, 1e-9);
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
                RingDslWriter.append(TORUS_GRAPH, TUBE_POINTS, TUBE_POINT_COUNT, true, false);
        System.out.println("[ring] saved statement: " + saved.substring(TORUS_GRAPH.length()));
        NodeGraphRuntime runtime = NodeGraphRuntime.fromSource(saved);
        List<PythonParser.ParsedNode> statements = runtime.statements;
        runtime.executeGraphResult(statements, statements.get(statements.size() - 1).id,
                LoopThroughPointsNode.GEOMETRY.name);
        GeometryBundle reloaded = (GeometryBundle) runtime.lastOutput(
                LoopThroughPointsNode.GEOMETRY.name);

        boolean[] marks = EdgeMarks.bools(reloaded, RingDslWriter.nextRingId(TORUS_GRAPH));
        assertArrayEquals(live.markedByEdgeId, marks,
                "the reloaded ring marks different edges than the live one");
        assertEquals(EdgeMarks.fingerprint(torus, live.markedByEdgeId),
                EdgeMarks.fingerprint(reloaded.mesh(), marks));
        assertEquals(saved,
                RingDslWriter.append(TORUS_GRAPH, TUBE_POINTS, TUBE_POINT_COUNT, true, false),
                NOT_BYTE_STABLE);
    }

    @Test
    void aPinnedRingStaysAtThePinWhileAFreeOneSlidesToTheWaist() {
        MeshTopology tube = waistedTube();
        float[] points = ringAround(PIN_X, PINNED_WAYPOINTS);

        SurfaceRing free = SurfaceRing.through(tube, points, PINNED_WAYPOINTS, true, 0, -1);
        SurfaceRing pinned =
                SurfaceRing.through(tube, points, PINNED_WAYPOINTS, true, PINNED_WAYPOINTS, -1);

        System.out.println("[pin] free centroid x=" + free.centroidX + " length=" + free.length
                + "; pinned centroid x=" + pinned.centroidX + " length=" + pinned.length);
        assertTrue(Math.abs(free.centroidX) < 0.2f,
                "the unpinned ring should slide to the waist at x = 0, not sit at "
                        + free.centroidX);
        assertTrue(Math.abs(pinned.centroidX - PIN_X) < 0.1f,
                "the pinned ring left its pin at x = " + PIN_X + " for " + pinned.centroidX);
        assertTrue(pinned.length > free.length,
                "the pinned ring " + pinned.length + " should be longer than the waist geodesic "
                        + free.length);
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
        float[] written = onRingWaypoints(tube, live, PINNED_WAYPOINTS);

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
        assertArrayEquals(live.markedByEdgeId, marks,
                "the pinned statement reloaded a different ring than the live one");
    }

    @Test
    void theWrittenStatementCarriesThePinFlag() {
        String saved = RingDslWriter.append(TORUS_GRAPH, TUBE_POINTS, TUBE_POINT_COUNT, true, true);

        System.out.println("[pin] saved statement: " + saved.substring(TORUS_GRAPH.length()));
        assertTrue(saved.contains("pin=true"), "the pinned statement does not say so: " + saved);
        assertEquals(saved, RingDslWriter.append(TORUS_GRAPH, TUBE_POINTS, TUBE_POINT_COUNT, true,
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
     * Waypoints spread by arclength around the tightened ring and written as the positions of the
     * vertices they snap to, the form the ring tool writes a pinned statement in.
     */
    private static float[] onRingWaypoints(MeshTopology mesh, SurfaceRing ring,
            int waypointCount) {
        int coordinates = SurfaceWaypoints.COORDINATES_PER_WAYPOINT;
        int loopPoints = ring.polyline.length / coordinates - 1;
        float[] sampled = new float[coordinates * waypointCount];
        for (int waypoint = 0; waypoint < waypointCount; waypoint++) {
            int point = (int) Math.round((double) loopPoints * waypoint / waypointCount);
            System.arraycopy(ring.polyline, coordinates * (point % loopPoints), sampled,
                    coordinates * waypoint, coordinates);
        }
        int[] vertexIds = SurfaceWaypoints.snap(mesh, sampled, waypointCount);
        float[] chosen = new float[coordinates * waypointCount];
        Vector3f position = new Vector3f();
        for (int waypoint = 0; waypoint < waypointCount; waypoint++) {
            mesh.vertexPosition(vertexIds[waypoint], position);
            chosen[coordinates * waypoint] = position.x;
            chosen[coordinates * waypoint + 1] = position.y;
            chosen[coordinates * waypoint + 2] = position.z;
        }
        return SurfaceWaypoints.parse(SurfaceWaypoints.format(chosen, waypointCount));
    }

    /**
     * Points spread around the tapered tube's surface at one station along its axis.
     */
    private static float[] ringAround(float x, int pointCount) {
        float radius = waistRadius(x);
        float[] points = new float[SurfaceWaypoints.COORDINATES_PER_WAYPOINT * pointCount];
        for (int point = 0; point < pointCount; point++) {
            double angle = 2.0 * Math.PI * point / pointCount;
            int base = SurfaceWaypoints.COORDINATES_PER_WAYPOINT * point;
            points[base] = x;
            points[base + 1] = (float) (radius * Math.cos(angle));
            points[base + 2] = (float) (radius * Math.sin(angle));
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
        int coordinates = SurfaceWaypoints.COORDINATES_PER_WAYPOINT;
        float[] positions = new float[coordinates * WAIST_TUBE_SIDES * WAIST_TUBE_RINGS];
        for (int ring = 0; ring < WAIST_TUBE_RINGS; ring++) {
            float x = -WAIST_TUBE_HALF_LENGTH
                    + 2f * WAIST_TUBE_HALF_LENGTH * ring / (WAIST_TUBE_RINGS - 1);
            float radius = waistRadius(x);
            for (int side = 0; side < WAIST_TUBE_SIDES; side++) {
                double angle = 2.0 * Math.PI * side / WAIST_TUBE_SIDES;
                int base = coordinates * (WAIST_TUBE_SIDES * ring + side);
                positions[base] = x;
                positions[base + 1] = (float) (radius * Math.cos(angle));
                positions[base + 2] = (float) (radius * Math.sin(angle));
            }
        }
        int[] faces = new int[2 * coordinates * WAIST_TUBE_SIDES * (WAIST_TUBE_RINGS - 1)];
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
        return HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faces);
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
