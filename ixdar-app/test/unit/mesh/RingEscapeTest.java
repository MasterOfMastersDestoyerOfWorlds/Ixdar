package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.AuthoredSplineRing;
import ixdar.geometry.mesh.data.paths.NearestVertex;
import ixdar.geometry.mesh.data.paths.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.SurfaceMetric;
import ixdar.geometry.mesh.data.paths.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.SurfaceSplineTracer;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.platform.input.Keys;
import ixdar.scenes.model.ControlHint;
import ixdar.scenes.ring.RingScene;
import ixdar.scenes.ring.RingTool;

/**
 * Esc in the ring editing scene: an open ring nobody edited closes and the ring tool stays, an
 * edited one stays open while the scene returns to orbit, and with nothing open Esc goes to orbit.
 */
class RingEscapeTest {

    private static final int XYZ = 3;

    private static final float RADIUS = 0.4f;

    private static final int QUARTERS = 4;

    private static final float[] TUBE_AXIS = { 0.998f, 0.05f, 0.03f };

    private static final double ADDED_ANCHOR_DEGREES = 45.0;

    private static final String CONFIRMED = "draft confirmed";

    @Test
    public void escOnAnUneditedRingClosesItAndKeepsTheRingTool() {
        MeshTopology tube = RingAnchorOrderTest.tube(0f, RADIUS);
        RingScene scene = sceneOn(tube);
        RingTool tool = scene.ringTool;
        SurfaceSpline confirmed = tool.confirmedRings.get(0);
        int unsaved = tool.unsavedRingCount();
        assertTrue(tool.reopenRing(0), tool.lastError);
        assertEquals(2, tool.history.undoDepth);

        scene.escapePressed();

        assertSame(tool, scene.activeTool, "Esc on an unedited ring left the ring tool");
        assertNull(tool.draft, "Esc left the unedited ring open");
        assertEquals(1, tool.history.undoDepth, "closing the unedited ring left an undo entry");
        assertEquals(0, tool.history.redoDepth());
        assertEquals(CONFIRMED, tool.history.nextUndoName());
        assertSame(confirmed, tool.confirmedRings.get(0));
        assertEquals(unsaved, tool.unsavedRingCount(), "closing changed the unsaved count");

        scene.escapePressed();

        assertSame(scene.orbitTool, scene.activeTool, "Esc with nothing open did not go to orbit");
        assertEquals(1, tool.history.undoDepth, "Esc with nothing open recorded an edit");
    }

    @Test
    public void escOnAnEditedRingKeepsTheDraftAndGoesToOrbit() {
        MeshTopology tube = RingAnchorOrderTest.tube(0f, RADIUS);
        RingScene scene = sceneOn(tube);
        RingTool tool = scene.ringTool;
        assertTrue(tool.reopenRing(0), tool.lastError);
        double angle = Math.toRadians(ADDED_ANCHOR_DEGREES);
        int added = SurfaceWaypoints.snap(NearestVertex.over(tube),new float[] { 0f,
            (float) (RADIUS * Math.cos(angle)), (float) (RADIUS * Math.sin(angle)) }, 1)[0];
        assertTrue(tool.addAuthoredAnchor(added), tool.lastError);
        int[] edited = tool.draftAuthoredVertexId;
        SurfaceSpline draft = tool.draft;
        int undoDepth = tool.history.undoDepth;

        scene.escapePressed();

        assertSame(scene.orbitTool, scene.activeTool, "Esc on an edited ring kept the ring tool");
        assertSame(draft, tool.draft, "Esc dropped the edited draft");
        assertArrayEquals(edited, tool.draftAuthoredVertexId);
        assertEquals(undoDepth, tool.history.undoDepth, "Esc on an edited ring changed the history");
        assertTrue(tool.lastRow.startsWith("draft kept"), tool.lastRow);
        assertEquals(0, tool.draftSourceRing);
    }

    @Test
    public void theControlsMenuSaysWhatEscDoesWithAnOpenRing() {
        RingScene scene = sceneOn(RingAnchorOrderTest.tube(0f, RADIUS));
        ControlHint escape = null;
        for (ControlHint hint : scene.controls()) {
            escape = hint.keyCode == Keys.ESCAPE ? hint : escape;
        }
        assertNotNull(escape, "no Esc row in the controls");
        assertTrue(escape.description.contains("close an unedited ring (an edited one stays open)")
                && escape.description.endsWith(scene.orbitTool.toolName()), escape.description);
    }

    /**
     * A ring scene over a round tube with one confirmed ring around it and the ring tool active,
     * no draft open.
     *
     * @param tube the surface
     * @return the scene
     */
    private static RingScene sceneOn(MeshTopology tube) {
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
        float[] xyz = new float[XYZ * QUARTERS];
        for (int quarter = 0; quarter < QUARTERS; quarter++) {
            double angle = 2.0 * Math.PI * quarter / QUARTERS;
            xyz[XYZ * quarter + 1] = (float) (RADIUS * Math.cos(angle));
            xyz[XYZ * quarter + 2] = (float) (RADIUS * Math.sin(angle));
        }
        int[] quarters = SurfaceWaypoints.snap(NearestVertex.over(tube),xyz, QUARTERS);
        AuthoredSplineRing ring = new AuthoredSplineRing(tool.geodesics);
        assertTrue(ring.trace(quarters, QUARTERS, TUBE_AXIS,
                SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH), ring.failure);
        System.arraycopy(TUBE_AXIS, 0, tool.draftBaseNormal, 0, XYZ);
        tool.draftAuthoredVertexId = ring.authoredVertexId;
        tool.draftDepth = SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH;
        tool.draft = SurfaceSpline.of(ring.tracer);
        scene.switchTool(tool);
        assertTrue(tool.confirmDraft(), tool.lastError);
        assertEquals(CONFIRMED, tool.history.nextUndoName());
        return scene;
    }
}
