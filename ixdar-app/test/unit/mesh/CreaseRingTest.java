package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.AuthoredSplineRing;
import ixdar.geometry.mesh.data.rings.RingSegmentMode;
import ixdar.geometry.mesh.data.paths.query.SurfaceCreases;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceMetric;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSplineTracer;
import ixdar.geometry.mesh.data.paths.spline.SurfaceWaypoints;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.nodegraph.api.MapNodeContext;
import ixdar.nodegraph.nodes.selection.SplineRingNode;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.Platform;
import ixdar.platform.input.Keys;
import ixdar.scenes.ring.RingScene;
import ixdar.tools.ControlHint;
import ixdar.tools.ring.RingTool;
import unit.GlStandIn;

/**
 * Crease rings on a tube with a tilted notch: the ring runs through the click and round the notch,
 * the geodesic one cuts across it, and crease mode drafts wherever geodesic mode would.
 */
class CreaseRingTest {

    private static final int XYZ = 3;

    private static final float TUBE_RADIUS = 1f;

    private static final float TUBE_HALF_LENGTH = 2f;

    private static final int TUBE_SIDES = 72;

    private static final int TUBE_RINGS = 161;

    // The notch's centre line runs at x = NOTCH_TILT * cos(angle round the tube), a plane tilted
    // about 22 degrees off the cross-section.
    private static final double NOTCH_TILT = 0.4;

    private static final double NOTCH_WIDTH = 0.06;

    private static final double NOTCH_DEPTH = 0.12;

    // A quarter turn from the side the tilt lifts, where the notch crosses x = 0.
    private static final int CLICK_SIDE = TUBE_SIDES / 4;

    // The click lands this far along the tube off the notch's centre line.
    private static final int CLICK_RING_OFFSET = 2;

    // The stem scan clicks every this many sides round the tube, and up to this many rings
    // either side of its middle, the notch's whole tilted swing and beyond.
    private static final int SCAN_SIDE_STEP = 6;

    private static final int SCAN_RING_REACH = 30;

    // Mean distance along the tube from the notch's centre line within which a ring lies in it,
    // under the notch's half width.
    private static final double IN_NOTCH = 0.04;

    // The cross-section through the click strays NOTCH_TILT * 2 / pi from the notch on average.
    private static final double ACROSS_NOTCH = 0.2;

    private static final float[] AXIS = { 1f, 0f, 0f };

    private static final double PLANE_SLACK = 1e-6;

    @Test
    void theCreaseRingRunsThroughTheClickAndRoundTheNotch() {
        MeshTopology tube = tube(NOTCH_DEPTH);
        SurfaceGeodesics geodesics = SurfaceGeodesics.over(SurfaceMetric.of(tube));
        int clicked = vertexAt(TUBE_RINGS / 2 + CLICK_RING_OFFSET, CLICK_SIDE);

        AuthoredSplineRing crease = new AuthoredSplineRing(geodesics);
        crease.mode = RingSegmentMode.CREASE;
        crease.creases = SurfaceCreases.of(tube);
        assertTrue(crease.trace(new int[] { clicked }, 1, AXIS,
                SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH), crease.failure);
        assertArrayEquals(new int[] { clicked }, crease.authoredVertexId,
                "the crease ring moved its anchor off the click");
        assertEquals(1.0, Math.abs(AXIS[0] * crease.planeNormal[0]
                + AXIS[1] * crease.planeNormal[1] + AXIS[2] * crease.planeNormal[2]), PLANE_SLACK,
                "the crease ring left the click's girdling plane");
        double creaseOff = meanOffNotch(SurfaceSpline.of(crease.tracer).polyline);

        AuthoredSplineRing geodesic = new AuthoredSplineRing(geodesics);
        assertTrue(geodesic.trace(new int[] { clicked }, 1, AXIS,
                SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH), geodesic.failure);
        double geodesicOff = meanOffNotch(SurfaceSpline.of(geodesic.tracer).polyline);

        String measured = String.format(Locale.ROOT, "crease ring %.4f, geodesic ring %.4f off "
                + "the notch on average", creaseOff, geodesicOff);
        assertTrue(creaseOff < IN_NOTCH, measured);
        assertTrue(geodesicOff > ACROSS_NOTCH, measured);
    }

    /**
     * Clicks round the tube either side of the notch: both halves of a ring that reach the notch
     * along one stem from the click would cross there, so every ring must stay simple.
     */
    @Test
    void creaseRingsBesideTheNotchNeverRunOutAndBackAlongAStem() {
        MeshTopology tube = tube(NOTCH_DEPTH);
        AuthoredSplineRing crease =
                new AuthoredSplineRing(SurfaceGeodesics.over(SurfaceMetric.of(tube)));
        crease.mode = RingSegmentMode.CREASE;
        crease.creases = SurfaceCreases.of(tube);
        for (int side = 0; side < TUBE_SIDES; side += SCAN_SIDE_STEP) {
            for (int offset = -SCAN_RING_REACH; offset <= SCAN_RING_REACH; offset += 2) {
                int clicked = vertexAt(TUBE_RINGS / 2 + offset, side);
                String at = " clicking side " + side + ", " + offset + " rings along";
                assertTrue(crease.trace(new int[] { clicked }, 1, AXIS,
                        SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH), crease.failure + at);
                assertTrue(crease.simple, String.format(Locale.ROOT, "the crease ring crosses "
                        + "itself near %.3f,%.3f,%.3f", crease.crossings.firstCrossingXyz[0],
                        crease.crossings.firstCrossingXyz[1], crease.crossings.firstCrossingXyz[2])
                        + at);
                assertArrayEquals(new int[] { clicked }, crease.authoredVertexId, at);
            }
        }
    }

    @Test
    void theCreaseStatementReloadsTheCreaseRing() {
        MeshTopology tube = tube(NOTCH_DEPTH);
        SurfaceMetric metric = SurfaceMetric.of(tube);
        SurfaceCreases creases = SurfaceCreases.of(tube);
        AuthoredSplineRing crease = new AuthoredSplineRing(SurfaceGeodesics.over(metric));
        crease.mode = RingSegmentMode.CREASE;
        crease.creases = creases;
        assertTrue(crease.trace(new int[] { vertexAt(TUBE_RINGS / 2 + CLICK_RING_OFFSET,
                CLICK_SIDE) }, 1, AXIS, SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH), crease.failure);
        SurfaceSpline live = SurfaceSpline.of(crease.tracer);
        float[] anchorXyz = new float[XYZ];
        Vector3f position = new Vector3f();
        tube.vertexPosition(crease.authoredVertexId[0], position);
        anchorXyz[0] = position.x;
        anchorXyz[1] = position.y;
        anchorXyz[2] = position.z;

        SplineRingNode node = new SplineRingNode();
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput(SplineRingNode.GEOMETRY.name, GeometryBundle.ofMesh(tube));
        ctx.setInput(SplineRingNode.POINTS.name, SurfaceWaypoints.format(anchorXyz, 1));
        ctx.setInput(SplineRingNode.NORMAL.name, SurfaceWaypoints.format(AXIS, 1));
        ctx.setInput(SplineRingNode.METRIC.name, metric);
        ctx.setInput(SplineRingNode.MODE.name, RingSegmentMode.CREASE.dslName);
        ctx.setInput(SplineRingNode.CREASES.name, creases);
        node.evaluate(ctx);
        boolean[] marks = EdgeMarks.bools(
                ctx.getOutput(SplineRingNode.GEOMETRY.name, GeometryBundle.class),
                SplineRingNode.DEFAULT_MARK_LABEL);
        assertArrayEquals(live.markedByEdgeId, marks, "the crease statement reloaded another "
                + "edge cycle; live " + EdgeMarks.fingerprint(tube, live.markedByEdgeId)
                + " reloaded " + EdgeMarks.fingerprint(tube, marks));
    }

    @Test
    void inCreaseModeTheHoverAndTheClickRingThroughTheCursor() {
        Platform suitePlatform = Platforms.get();
        GL suiteGl = Platforms.gl();
        Platforms.init(suitePlatform, GlStandIn.create());
        try {
            MeshTopology tube = tube(NOTCH_DEPTH);
            int hovered = vertexAt(TUBE_RINGS / 2 + CLICK_RING_OFFSET, CLICK_SIDE);
            RingTool tool = toolOver(tube, hovered);
            List<ControlHint> controls = new ArrayList<>();
            tool.addControls(controls);

            press(controls, Keys.T);
            assertEquals(RingSegmentMode.CREASE, tool.newDraftMode, tool.lastError);
            assertTrue(tool.draft == null, "T opened a draft");
            tool.perFrame();
            assertTrue(tool.previewValid, "no hover preview in crease mode: " + tool.lastError);
            // The hover previews the crease ring itself, anchored at the cursor.
            assertTrue(meanOffNotch(tool.previewSpline.polyline) < IN_NOTCH,
                    "the crease-mode preview left the notch");
            assertEquals(hovered, tool.previewAuthoredVertexId);

            tool.requestClick();
            tool.perFrame();
            assertTrue(tool.draft != null, "the click drafted no ring: " + tool.lastError);
            assertEquals("", tool.lastError);
            assertArrayEquals(new int[] { hovered }, tool.draftAuthoredVertexId,
                    "the click anchored the ring off the cursor");
            assertArrayEquals(tool.previewPlaneNormal, tool.draftBaseNormal,
                    "the click drafted on another plane than the hover");
            assertEquals(RingSegmentMode.CREASE, tool.draftMode);
            assertTrue(meanOffNotch(tool.draft.polyline) < IN_NOTCH,
                    "the drafted ring left the notch");
            SurfaceSpline groove = tool.draft;
            press(controls, Keys.T);
            assertEquals(RingSegmentMode.GEODESIC, tool.draftMode, tool.lastError);
            assertEquals(RingSegmentMode.GEODESIC, tool.newDraftMode, "T left the next mode");
            press(controls, Keys.T);
            assertEquals(RingSegmentMode.CREASE, tool.draftMode, tool.lastError);
            assertArrayEquals(groove.markedByEdgeId, tool.draft.markedByEdgeId,
                    "switching the mode back did not restore the groove ring");

            assertTrue(tool.confirmDraft(), tool.lastError);
            assertEquals(RingSegmentMode.CREASE, tool.confirmedMode.get(0));
            assertEquals(RingSegmentMode.CREASE, tool.newDraftMode, "confirm lost the mode");
            assertTrue(tool.reopenRing(0), tool.lastError);
            assertEquals(RingSegmentMode.CREASE, tool.draftMode, "re-open lost the ring's mode");
            assertArrayEquals(groove.markedByEdgeId, tool.draft.markedByEdgeId);
            assertTrue(tool.undo(), tool.lastError);
            assertTrue(tool.undo(), tool.lastError);
            assertEquals(RingSegmentMode.CREASE, tool.draftMode, "undo lost the draft's mode");
            for (String edit : new String[] { "segment mode crease", "segment mode geodesic",
                "draft opened" }) {
                assertEquals(edit, tool.history.nextUndoName());
                assertTrue(tool.undo(), tool.lastError);
            }
            assertTrue(tool.draft == null, "undo kept the click's draft");
            assertEquals(RingSegmentMode.CREASE, tool.newDraftMode, "undo lost the mode");
        } finally {
            Platforms.init(suitePlatform, suiteGl);
        }
    }

    @Test
    void onASmoothStretchCreaseModeStillDraftsThroughTheCursor() {
        Platform suitePlatform = Platforms.get();
        GL suiteGl = Platforms.gl();
        Platforms.init(suitePlatform, GlStandIn.create());
        try {
            int hovered = vertexAt(TUBE_RINGS / 2, CLICK_SIDE);
            RingTool tool = toolOver(tube(0.0), hovered);
            List<ControlHint> controls = new ArrayList<>();
            tool.perFrame();
            assertTrue(tool.previewValid, "no geodesic preview on the plain tube");
            tool.addControls(controls);
            press(controls, Keys.T);
            tool.perFrame();
            assertTrue(tool.previewValid, "crease mode previewed nothing where geodesic mode "
                    + "does: " + tool.lastError);

            tool.requestClick();
            tool.perFrame();
            assertTrue(tool.draft != null, "the click drafted no ring: " + tool.lastError);
            assertArrayEquals(new int[] { hovered }, tool.draftAuthoredVertexId);
            assertEquals(RingSegmentMode.CREASE, tool.draftMode,
                    "crease mode drafted a geodesic ring");
        } finally {
            Platforms.init(suitePlatform, suiteGl);
        }
    }

    /**
     * A ring tool over a surface whose cursor rests on one vertex, looking straight down at it
     * along the tube's radius; the GPU pick and the camera are stood in for.
     */
    private static RingTool toolOver(MeshTopology tube, int hoveredVertexId) {
        Vector3f position = new Vector3f();
        tube.vertexPosition(hoveredVertexId, position);
        float radial = (float) Math.hypot(position.y, position.z);
        RingScene scene = new RingScene() {
            @Override
            public MeshTopology getMesh() {
                return tube;
            }

            @Override
            public MeshTopology halfEdgeSurface() {
                return tube;
            }

            @Override
            public boolean pickCursor(HalfEdgeMeshRuntime pickRuntime, MeshTopology surface) {
                cursorVertexId = hoveredVertexId;
                cursorFaceId = surface.vertexFaceAt(hoveredVertexId, 0);
                cursorPoint[0] = position.x;
                cursorPoint[1] = position.y;
                cursorPoint[2] = position.z;
                cursorRayDirection[0] = 0f;
                cursorRayDirection[1] = -position.y / radial;
                cursorRayDirection[2] = -position.z / radial;
                return true;
            }

            @Override
            public double worldPerPixel() {
                return 0.0;
            }
        };
        scene.meshRuntime = new HalfEdgeMeshRuntime();
        RingTool tool = scene.ringTool;
        tool.geodesics = SurfaceGeodesics.over(SurfaceMetric.of(tube));
        tool.active = true;
        return tool;
    }

    @Test
    void aRidgeDoesNotAttractTheCreaseRing() {
        MeshTopology tube = tube(-NOTCH_DEPTH);
        AuthoredSplineRing crease =
                new AuthoredSplineRing(SurfaceGeodesics.over(SurfaceMetric.of(tube)));
        crease.mode = RingSegmentMode.CREASE;
        crease.creases = SurfaceCreases.of(tube);
        assertTrue(crease.trace(new int[] { vertexAt(TUBE_RINGS / 2, CLICK_SIDE) }, 1, AXIS,
                SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH), crease.failure);
        assertTrue(meanOffNotch(SurfaceSpline.of(crease.tracer).polyline) > IN_NOTCH,
                "the crease ring followed the ridge");
    }

    /**
     * An open tube along x, wound with outward normals as a scan is, with a Gaussian notch of
     * {@code depth} round its tilted centre line; a negative depth raises a ridge instead.
     */
    private static MeshTopology tube(double depth) {
        float[] positions = new float[XYZ * TUBE_SIDES * TUBE_RINGS];
        for (int ring = 0; ring < TUBE_RINGS; ring++) {
            double x = -TUBE_HALF_LENGTH + 2.0 * TUBE_HALF_LENGTH * ring / (TUBE_RINGS - 1);
            for (int side = 0; side < TUBE_SIDES; side++) {
                double angle = 2.0 * Math.PI * side / TUBE_SIDES;
                double fromNotch = (x - NOTCH_TILT * Math.cos(angle)) / NOTCH_WIDTH;
                double radius = TUBE_RADIUS - depth * Math.exp(-fromNotch * fromNotch);
                int base = XYZ * (TUBE_SIDES * ring + side);
                positions[base] = (float) x;
                positions[base + 1] = (float) (radius * Math.cos(angle));
                positions[base + 2] = (float) (radius * Math.sin(angle));
            }
        }
        int[] faces = new int[2 * XYZ * TUBE_SIDES * (TUBE_RINGS - 1)];
        int corner = 0;
        for (int ring = 0; ring + 1 < TUBE_RINGS; ring++) {
            for (int side = 0; side < TUBE_SIDES; side++) {
                int here = vertexAt(ring, side);
                int ahead = vertexAt(ring, (side + 1) % TUBE_SIDES);
                int over = vertexAt(ring + 1, side);
                int overAhead = vertexAt(ring + 1, (side + 1) % TUBE_SIDES);
                faces[corner++] = here;
                faces[corner++] = overAhead;
                faces[corner++] = over;
                faces[corner++] = here;
                faces[corner++] = ahead;
                faces[corner++] = overAhead;
            }
        }
        return HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faces);
    }

    private static void press(List<ControlHint> controls, int keyCode) {
        for (ControlHint hint : controls) {
            if (hint.keyCode == keyCode && !hint.controlHeld) {
                hint.action.perform();
                return;
            }
        }
        throw new AssertionError("no control on key " + keyCode);
    }

    private static int vertexAt(int ring, int side) {
        return TUBE_SIDES * ring + side;
    }

    /** Mean distance along the tube from each polyline point to the notch's centre line. */
    private static double meanOffNotch(float[] polyline) {
        int points = polyline.length / XYZ;
        double total = 0.0;
        for (int point = 0; point < points; point++) {
            double angle = Math.atan2(polyline[XYZ * point + 2], polyline[XYZ * point + 1]);
            total += Math.abs(polyline[XYZ * point] - NOTCH_TILT * Math.cos(angle));
        }
        return total / points;
    }
}
