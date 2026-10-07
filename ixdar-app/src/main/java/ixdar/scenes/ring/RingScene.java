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
import ixdar.platform.input.Keys;
import ixdar.platform.input.OrbitCameraKeyGuy;
import ixdar.scenes.mesh.MeshNodeViewerScene;
import ixdar.scenes.model.ControlHint;
import ixdar.scenes.regions.RingRegionTool;

/**
 * The mesh editing scene: the mesh viewer hosting the orbit, ring and region-select
 * {@link EditTool}s over the ring tool's rings. Ctrl+R and Ctrl+T pick a tool; Esc goes to the
 * active tool, else back to orbit.
 */
@SceneAnnotation(id = "ring-tool")
public class RingScene extends MeshNodeViewerScene {

    public static final String RING_SEED_LABEL = "ring_seed";

    public static final String RING_TIGHTENED_LABEL = "ring_tightened";

    public static final String LOG_PREFIX = "[mesh-edit] ";

    /** The tool the scene opens with, which only moves the camera. */
    public final OrbitTool orbitTool = new OrbitTool(this);

    /** The hover-to-preview ring tool, which holds the rings every tool works over. */
    public final RingTool ringTool = new RingTool(this);

    /** The region-select tool over the regions the ring tool's rings enclose. */
    public final RingRegionTool regionTool = new RingRegionTool(this);

    /** Every tool the scene hosts, each run once per frame whether active or not. */
    public final List<EditTool> tools = List.of(orbitTool, ringTool, regionTool);

    /** The tool that owns clicks, drags and tool keys now. */
    public EditTool activeTool = orbitTool;

    /** Surface points the {@code ring} command has collected, packed xyz. */
    public float[] ringWaypointsXyz = new float[0];

    /** Waypoints held in the front of {@link #ringWaypointsXyz}. */
    public int ringWaypointCount;

    /** Ring the last {@code ring close} produced, or {@code null} before one is closed. */
    public SurfaceRing ring;

    /** The ring-labelled masks the graph left, drawn thick and numbered rather than as edges. */
    public Map<String, boolean[]> ringMarksByLabel = new LinkedHashMap<>();

    /** Whether each ring's number is drawn beside it; the {@code N} key flips it. */
    public boolean showRingNumbers;

    @Override
    public String windowTitle() {
        return "Ixdar : Mesh Edit";
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

    /**
     * Wire the viewer's input, leaving Ctrl+R to the ring tool rather than the orbit-centre reset,
     * then hand the mouse to the starting tool.
     */
    @Override
    public void initInput() {
        super.initInput();
        if (keys instanceof OrbitCameraKeyGuy orbitKeys) {
            orbitKeys.controlRResetsTarget = false;
        }
        activeTool.activate();
    }

    /** Run one frame of every tool, after any pending model switch and before the mesh is drawn. */
    @Override
    public void updateScene() {
        for (EditTool tool : tools) {
            tool.perFrame();
        }
    }

    /**
     * Make {@code tool} active: the current one hands back the mouse keeping its state, and the
     * control hints become the new tool's. Switching to the active tool does nothing.
     *
     * @param tool the tool to switch to
     */
    public void switchTool(EditTool tool) {
        if (tool == activeTool) {
            return;
        }
        activeTool.deactivate();
        activeTool = tool;
        activeTool.activate();
        controls.clear();
        setControls();
        Platforms.get().log(LOG_PREFIX + "active tool: " + activeTool.toolName());
    }

    /**
     * Draw the surface, then the ring overlays and their markers over it; while the region tool
     * shows an extraction, draw that mesh on its own instead.
     */
    @Override
    public void renderScene() {
        if (regionTool.showingExtraction && regionTool.extractedRuntime != null) {
            camera.resetView();
            regionTool.extractedRuntime.render(camera);
            return;
        }
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
     * Draw each ring's number beside its centroid while {@link #showRingNumbers} is set, in the
     * overlay order the tool numbers rings in, leaving the depth test to hide the numbers of
     * rings the surface covers.
     */
    @Override
    public void drawSceneOverlayText() {
        if (showRingNumbers && surfaceRuntime() instanceof MeshOverlayRuntime overlay) {
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
        if (runtime == null || halfEdgeSurface() == null) {
            return;
        }
        if (marksByLabel.isEmpty()) {
            if (!ringMarksByLabel.isEmpty()) {
                ringMarksByLabel = new LinkedHashMap<>();
                ringTool.graphRingsChanged();
            }
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
        ringTool.graphRingsChanged();
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
            ringTool.graphRingsChanged();
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
     * Write the rings the tool holds. Ctrl+S or a click on the menu row reaches here; a bare S
     * does not, and nothing else in the tool touches the working .dsl.
     */
    public void saveRingsPressed() {
        if (!ringTool.active && ringTool.unsavedRingCount() == 0) {
            return;
        }
        ringTool.saveRings();
    }

    /**
     * The tool keys, the active tool's own hints, then the hints every tool shares. Ctrl+I and
     * Esc come from the model scene, so no tool may bind either; a tool takes Esc through
     * {@link EditTool#escapePressed} instead.
     */
    @Override
    public void setControls() {
        controls.add(toolHint(Keys.R, true, "ctrl+R", ringTool));
        controls.add(toolHint(Keys.T, true, "ctrl+T", regionTool));
        activeTool.addControls(controls);
        controls.add(new ControlHint(Keys.S, true, "ctrl+S", "save rings", this::saveRingsPressed));
        controls.add(new ControlHint(Keys.N, "N", "ring numbers",
                () -> showRingNumbers = !showRingNumbers));
        super.setControls();
    }

    /** Escape with the menu closed goes to the active tool first, else returns to the orbit tool. */
    @Override
    public void escapeWithMenuClosed() {
        if (!activeTool.escapePressed()) {
            switchTool(orbitTool);
        }
    }

    /**
     * Escape closes the menu, else does the active tool's Esc, else returns to the orbit tool.
     *
     * @return the description
     */
    @Override
    public String escapeDescription() {
        String toolEscape = activeTool.escapeDescription();
        return "close menu, else " + (toolEscape.isEmpty() ? "" : toolEscape + ", else ")
                + toolLabel(orbitTool);
    }

    /**
     * A tool-switch hint, marked when its tool is the active one.
     *
     * @param keyCode     key that switches to the tool
     * @param controlHeld whether the key needs Control held
     * @param key         key label shown to the viewer
     * @param tool        tool the key switches to
     * @return the hint
     */
    private ControlHint toolHint(int keyCode, boolean controlHeld, String key, EditTool tool) {
        return new ControlHint(keyCode, controlHeld, key, toolLabel(tool), () -> switchTool(tool));
    }

    /**
     * A tool's name as its hint shows it, marked when it is the active tool.
     *
     * @param tool the tool
     * @return the label
     */
    public String toolLabel(EditTool tool) {
        return tool.toolName() + (tool == activeTool ? " (active)" : "");
    }
}
