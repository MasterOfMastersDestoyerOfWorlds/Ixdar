package ixdar.scenes.ring;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.FlipGeodesics;
import ixdar.geometry.mesh.data.paths.SurfaceRing;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.graphics.render.model.MeshOverlayRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.Keys;
import ixdar.scenes.mesh.MeshNodeViewerScene;
import ixdar.scenes.model.ControlHint;

/**
 * The mesh viewer plus ring authoring: the hover-to-preview {@link RingTool}, the {@code ring}
 * terminal command's waypoints, and the graph's ring-labelled edge masks drawn as thick numbered
 * loops instead of feature edges.
 */
@SceneAnnotation(id = "ring-tool")
public class RingScene extends MeshNodeViewerScene {

    /** Edge-mark label the ring overlay draws the untightened seed walk under. */
    public static final String RING_SEED_LABEL = "ring_seed";

    /** Edge-mark label the ring overlay draws the tightened geodesic under. */
    public static final String RING_TIGHTENED_LABEL = "ring_tightened";

    /** The hover-to-preview ring tool this scene's {@code R} key starts. */
    public final RingTool ringTool = new RingTool(this);

    /** Surface points the {@code ring} command has collected, packed xyz. */
    public float[] ringWaypointsXyz = new float[0];

    /** Waypoints held in the front of {@link #ringWaypointsXyz}. */
    public int ringWaypointCount;

    /** Ring the last {@code ring close} produced, or {@code null} before one is closed. */
    public SurfaceRing ring;

    /** The ring-labelled masks the graph left, drawn thick and numbered rather than as edges. */
    public Map<String, boolean[]> ringMarksByLabel = new LinkedHashMap<>();

    @Override
    public String windowTitle() {
        return "Ixdar : Ring Tool";
    }

    /**
     * Draw the surface through a runtime that also draws general overlays, since the rings are
     * point sets, coloured line groups and labels over it.
     *
     * @return the installed overlay runtime
     */
    @Override
    public HalfEdgeMeshRuntime createRuntime() {
        meshRuntime = new MeshOverlayRuntime();
        return meshRuntime;
    }

    /** Run one tool frame, after any pending model switch and before the mesh is drawn. */
    @Override
    public void updateScene() {
        ringTool.perFrame();
    }

    /** Draw the surface, then the ring overlays and their markers over it. */
    @Override
    public void renderScene() {
        super.renderScene();
        if (surfaceRuntime() instanceof MeshOverlayRuntime overlay) {
            overlay.renderOverlays(camera);
            overlay.renderHighlights(camera);
        }
    }

    /**
     * Drop the confirmed rings before the switch lands, since they describe a surface that is
     * about to leave the screen.
     */
    @Override
    public void applyPendingModel() {
        if (pendingModelPath != null) {
            ringTool.discardConfirmedRings("loading " + pendingModelPath);
        }
        super.applyPendingModel();
    }

    /**
     * Draw each ring's number beside its centroid, in the overlay order the tool numbers rings in,
     * leaving the depth test to hide the numbers of rings the surface covers.
     */
    @Override
    public void drawSceneOverlayText() {
        if (surfaceRuntime() instanceof MeshOverlayRuntime overlay) {
            overlay.drawLabels(camera, camera2D);
        }
    }

    /**
     * Peel the graph's ring labels into the thick numbered overlay and leave every other label to
     * the viewer's feature-edge drawing.
     *
     * @param marksByLabel edge-id-indexed masks by label; empty leaves the view untouched
     */
    @Override
    public void showEdgeMarks(Map<String, boolean[]> marksByLabel) {
        HalfEdgeMeshRuntime runtime = surfaceRuntime();
        if (runtime == null || halfEdgeSurface() == null || marksByLabel.isEmpty()) {
            return;
        }
        ringMarksByLabel = new LinkedHashMap<>();
        Map<String, boolean[]> featureMarks = new LinkedHashMap<>();
        for (Map.Entry<String, boolean[]> entry : marksByLabel.entrySet()) {
            if (RingTool.isRingLabel(entry.getKey())) {
                ringMarksByLabel.put(entry.getKey(), entry.getValue());
            } else {
                featureMarks.put(entry.getKey(), entry.getValue());
            }
        }
        ringTool.invalidateRings();
        Platforms.get().log(RingTool.LOG_PREFIX + "ring overlay: " + ringMarksByLabel.keySet());
        if (featureMarks.isEmpty()) {
            runtime.setFeatureEdgeOverlay(List.of());
            return;
        }
        super.showEdgeMarks(featureMarks);
    }

    /**
     * Show a closed ring as two overlays: its shortest-edge seed walk and the geodesic FlipOut
     * tightened it to, in the contrasting colours the edge-mark overlay hands out.
     *
     * @param closedRing the ring to show, or {@code null} to drop the ring overlay
     */
    public void showRing(SurfaceRing closedRing) {
        HalfEdgeMeshRuntime runtime = surfaceRuntime();
        if (runtime == null) {
            return;
        }
        if (closedRing == null) {
            ringMarksByLabel = new LinkedHashMap<>();
            ringTool.invalidateRings();
            runtime.setFeatureEdgeOverlay(List.of());
            return;
        }
        Map<String, boolean[]> marksByLabel = new LinkedHashMap<>();
        marksByLabel.put(RING_SEED_LABEL, closedRing.seedMarkedByEdgeId);
        marksByLabel.put(RING_TIGHTENED_LABEL, closedRing.markedByEdgeId);
        showEdgeMarks(marksByLabel);
    }

    /**
     * Record one more ring waypoint at an authored surface position.
     *
     * @param x waypoint x
     * @param y waypoint y
     * @param z waypoint z
     * @return the number of waypoints collected so far
     */
    public int addRingWaypoint(float x, float y, float z) {
        int coordinates = SurfaceWaypoints.COORDINATES_PER_WAYPOINT * (ringWaypointCount + 1);
        if (ringWaypointsXyz.length < coordinates) {
            float[] grown = new float[Math.max(coordinates, 2 * ringWaypointsXyz.length)];
            System.arraycopy(ringWaypointsXyz, 0, grown, 0, ringWaypointsXyz.length);
            ringWaypointsXyz = grown;
        }
        int base = SurfaceWaypoints.COORDINATES_PER_WAYPOINT * ringWaypointCount;
        ringWaypointsXyz[base] = x;
        ringWaypointsXyz[base + 1] = y;
        ringWaypointsXyz[base + 2] = z;
        ringWaypointCount++;
        return ringWaypointCount;
    }

    /**
     * Close the collected waypoints into a tightened ring and show it over the mesh.
     *
     * @throws IllegalStateException    when no mesh is loaded to ring
     * @throws IllegalArgumentException when fewer than three waypoints were collected
     * @return the closed ring, also kept in {@link #ring}
     */
    public SurfaceRing closeRing() {
        MeshTopology surface = halfEdgeSurface();
        if (surface == null || surface.faceCount() == 0) {
            throw new IllegalStateException("no mesh is loaded to ring");
        }
        ring = SurfaceRing.through(surface, ringWaypointsXyz, ringWaypointCount, true, 0,
                FlipGeodesics.UNBOUNDED_ITERATIONS);
        showRing(ring);
        return ring;
    }

    /**
     * Forget the collected waypoints and the closed ring, leaving the mesh as it was.
     */
    public void clearRing() {
        ringWaypointCount = 0;
        ring = null;
        showRing(null);
    }

    /**
     * Start the ring tool, or finish it when it is already running, taking the mouse wheel for
     * the plane tilt while it runs.
     */
    public void toggleRingTool() {
        ringTool.toggle();
        bindRingToolMouse();
    }

    /**
     * Give the mouse to the ring tool while it runs and back to the orbit when it stops, so a
     * click confirms instead of orbiting and the wheel tilts instead of zooming.
     */
    public void bindRingToolMouse() {
        if (orbitMouse == null) {
            return;
        }
        orbitMouse.toolClick = ringTool.active ? button -> ringTool.confirm() : null;
        orbitMouse.toolScroll = ringTool.active
                ? ticks -> ringTool.tilt(ticks, keys instanceof KeyGuy guy && guy.shiftMask)
                : null;
    }

    /** Escape finishes the ring tool when it is running, and toggles the model menu otherwise. */
    @Override
    public void escapePressed() {
        if (ringTool.active) {
            ringTool.finish();
            bindRingToolMouse();
            return;
        }
        super.escapePressed();
    }

    /**
     * Write the rings the tool holds. Ctrl+S or a click on the menu row reaches here; a bare S
     * does not, and nothing else in the tool touches the working .dsl.
     */
    public void saveRingsPressed() {
        if (!ringTool.active && ringTool.unsavedRingCount() == 0) {
            return;
        }
        ringTool.saveRings();
    }

    @Override
    public void setControls() {
        controls.add(new ControlHint(Keys.R, "R", "ring tool", this::toggleRingTool));
        controls.add(new ControlHint(Keys.ENTER, "enter", "confirm ring",
                () -> ringTool.confirm()));
        controls.add(new ControlHint(Keys.S, true, "ctrl+S", "save rings", this::saveRingsPressed));
        super.setControls();
    }
}
