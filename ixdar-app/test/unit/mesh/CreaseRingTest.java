package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.AuthoredSplineRing;
import ixdar.geometry.mesh.data.paths.RingSegmentMode;
import ixdar.geometry.mesh.data.paths.SurfaceCreases;
import ixdar.geometry.mesh.data.paths.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.SurfaceMetric;
import ixdar.geometry.mesh.data.paths.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.SurfaceSplineTracer;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.selection.SplineRingNode;
import ixdar.platform.input.Keys;
import ixdar.scenes.model.ControlHint;
import ixdar.scenes.ring.RingScene;
import ixdar.scenes.ring.RingTool;

/**
 * Crease rings on a tube with a tilted circumferential notch, a groove that no plane through the
 * click girdles: the groove ring lies in the notch, the geodesic one through the same click and
 * plane cuts across it, and a ring on a plain tube or a ridge is refused.
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

    // Mean distance along the tube from the notch's centre line within which a ring lies in it,
    // under the notch's half width.
    private static final double IN_NOTCH = 0.04;

    // The cross-section through the click strays NOTCH_TILT * 2 / pi from the notch on average.
    private static final double ACROSS_NOTCH = 0.2;

    private static final float[] AXIS = { 1f, 0f, 0f };

    @Test
    void theGrooveRingLiesInTheNotchAndTheGeodesicOneDoesNot() {
        MeshTopology tube = tube(NOTCH_DEPTH);
        SurfaceGeodesics geodesics = SurfaceGeodesics.over(SurfaceMetric.of(tube));
        int clicked = vertexAt(TUBE_RINGS / 2 + CLICK_RING_OFFSET, CLICK_SIDE);

        AuthoredSplineRing groove = new AuthoredSplineRing(geodesics);
        groove.creases = SurfaceCreases.of(tube);
        assertTrue(groove.traceGroove(clicked, AXIS, SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH),
                groove.failure);
        double grooveOff = meanOffNotch(SurfaceSpline.of(groove.tracer).polyline);

        AuthoredSplineRing geodesic = new AuthoredSplineRing(geodesics);
        assertTrue(geodesic.trace(new int[] { clicked }, 1, AXIS,
                SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH), geodesic.failure);
        double geodesicOff = meanOffNotch(SurfaceSpline.of(geodesic.tracer).polyline);

        String measured = String.format(Locale.ROOT, "groove ring %.4f, geodesic ring %.4f off "
                + "the notch on average, groove fraction %.2f", grooveOff, geodesicOff,
                groove.grooveFraction);
        assertTrue(grooveOff < IN_NOTCH, measured);
        assertTrue(geodesicOff > ACROSS_NOTCH, measured);
    }

    @Test
    void theCreaseStatementReloadsTheGrooveRing() {
        MeshTopology tube = tube(NOTCH_DEPTH);
        SurfaceMetric metric = SurfaceMetric.of(tube);
        SurfaceCreases creases = SurfaceCreases.of(tube);
        AuthoredSplineRing groove = new AuthoredSplineRing(SurfaceGeodesics.over(metric));
        groove.creases = creases;
        assertTrue(groove.traceGroove(vertexAt(TUBE_RINGS / 2 + CLICK_RING_OFFSET, CLICK_SIDE),
                AXIS, SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH), groove.failure);
        SurfaceSpline live = SurfaceSpline.of(groove.tracer);
        float[] anchorXyz = new float[XYZ];
        Vector3f position = new Vector3f();
        tube.vertexPosition(groove.authoredVertexId[0], position);
        anchorXyz[0] = position.x;
        anchorXyz[1] = position.y;
        anchorXyz[2] = position.z;

        SplineRingNode node = new SplineRingNode();
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput(SplineRingNode.GEOMETRY.name, GeometryBundle.ofMesh(tube));
        ctx.setInput(SplineRingNode.POINTS.name, SurfaceWaypoints.format(anchorXyz, 1));
        ctx.setInput(SplineRingNode.NORMAL.name, SurfaceWaypoints.format(groove.grooveNormal, 1));
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
    void vDraftsTheGrooveRingAndTSwitchesItsMode() {
        MeshTopology tube = tube(NOTCH_DEPTH);
        RingScene scene = new RingScene() {
            @Override
            public MeshTopology getMesh() {
                return tube;
            }

            @Override
            public MeshTopology halfEdgeSurface() {
                return tube;
            }
        };
        RingTool tool = scene.ringTool;
        tool.geodesics = SurfaceGeodesics.over(SurfaceMetric.of(tube));
        tool.active = true;
        tool.previewValid = true;
        tool.previewAuthoredVertexId = vertexAt(TUBE_RINGS / 2 + CLICK_RING_OFFSET, CLICK_SIDE);
        System.arraycopy(AXIS, 0, tool.previewPlaneNormal, 0, XYZ);
        List<ControlHint> controls = new ArrayList<>();
        tool.addControls(controls);

        press(controls, Keys.V);
        assertTrue(tool.draft != null, "V drafted no ring: " + tool.lastError);
        assertEquals(RingSegmentMode.CREASE, tool.draftMode);
        assertTrue(meanOffNotch(tool.draft.polyline) < IN_NOTCH, "the drafted ring left the notch");
        SurfaceSpline groove = tool.draft;
        press(controls, Keys.T);
        assertEquals(RingSegmentMode.GEODESIC, tool.draftMode, tool.lastError);
        press(controls, Keys.T);
        assertEquals(RingSegmentMode.CREASE, tool.draftMode, tool.lastError);
        assertArrayEquals(groove.markedByEdgeId, tool.draft.markedByEdgeId,
                "switching the mode back did not restore the groove ring");
        assertTrue(tool.confirmDraft(), tool.lastError);
        assertEquals(RingSegmentMode.CREASE, tool.confirmedMode.get(0));
        assertTrue(tool.undo(), tool.lastError);
        assertEquals(RingSegmentMode.CREASE, tool.draftMode, "undo lost the draft's mode");
    }

    @Test
    void aPlainTubeHasNoGrooveToRing() {
        MeshTopology tube = tube(0.0);
        AuthoredSplineRing groove =
                new AuthoredSplineRing(SurfaceGeodesics.over(SurfaceMetric.of(tube)));
        groove.creases = SurfaceCreases.of(tube);
        assertFalse(groove.traceGroove(vertexAt(TUBE_RINGS / 2, CLICK_SIDE), AXIS,
                SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH), "a groove ring was traced on a plain "
                        + "tube");
        assertTrue(groove.failure.startsWith("no "), groove.failure);
    }

    @Test
    void aRidgeDoesNotAttractTheGrooveRing() {
        MeshTopology tube = tube(-NOTCH_DEPTH);
        AuthoredSplineRing groove =
                new AuthoredSplineRing(SurfaceGeodesics.over(SurfaceMetric.of(tube)));
        groove.creases = SurfaceCreases.of(tube);
        boolean traced = groove.traceGroove(vertexAt(TUBE_RINGS / 2, CLICK_SIDE), AXIS,
                SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH);
        assertTrue(!traced || meanOffNotch(SurfaceSpline.of(groove.tracer).polyline) > IN_NOTCH,
                "a groove ring followed the ridge");
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
