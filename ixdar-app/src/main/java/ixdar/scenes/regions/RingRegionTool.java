package ixdar.scenes.regions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.joml.Vector3f;
import org.joml.Vector4f;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.RingRegions;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.model.ControlHint;
import ixdar.scenes.ring.EditTool;
import ixdar.scenes.ring.RingScene;

/**
 * The region-select tool of the editing scene: colours the regions the ring tool's rings cut the
 * surface into, rebuilt whenever those rings change, and selects them by click and Shift+click.
 * The selection is kept as surface points, so it survives ring edits and tool switches.
 */
public final class RingRegionTool implements EditTool {

    public static final String LOG_PREFIX = "[ring-regions] ";

    public static final String TOOL_NAME = "region select";

    public static final String TAG_PREFIX = "region_";

    public static final float UNSELECTED_GREY = 0.42f;

    public static final String QUERY_JOIN = "; ";

    public static final int XYZ = SurfaceWaypoints.COORDINATES_PER_WAYPOINT;

    /** Scene the tool runs on, whose ring tool holds the rings the regions are cut by. */
    public final RingScene scene;

    /** Regions of the shown surface, or {@code null} before the tool first ran on one. */
    public RingRegions regions;

    /** Regions currently selected, one flag per region. */
    public boolean[] selectedRegions = new boolean[0];

    /** Surface points of the regions picked by click, packed xyz each. */
    public final List<float[]> pickedPoints = new ArrayList<>();

    /** The {@code ring_regions} {@code select} query the current selection is. */
    public String selectQuery = "";

    /** The last action's outcome, as logged. */
    public String lastRow = "";

    /** Why the last action failed, or empty. */
    public String lastError = "";

    /** Whether the tool is the scene's active one. */
    public boolean active;

    private int builtRingRevision = -1;

    private boolean pendingClick;

    private boolean pendingShift;

    private HalfEdgeMeshRuntime.ShaderMode shaderModeBefore;

    /**
     * Binds the tool to its scene.
     *
     * @param scene the editing scene whose surface and rings the tool reads
     */
    public RingRegionTool(RingScene scene) {
        this.scene = scene;
    }

    @Override
    public String toolName() {
        return TOOL_NAME;
    }

    /** Take clicks from the orbit and colour the regions, rebuilt if the rings changed. */
    @Override
    public void activate() {
        active = true;
        HalfEdgeMeshRuntime runtime = scene.surfaceRuntime();
        shaderModeBefore = runtime == null ? null : runtime.getShaderMode();
        if (scene.orbitMouse != null) {
            scene.orbitMouse.toolClick = button -> requestClick(shiftHeld());
            scene.orbitMouse.toolGrab = null;
            scene.orbitMouse.toolRelease = null;
        }
        Platforms.get().log(LOG_PREFIX + "region select: click selects the region under the "
                + "cursor, Shift+click adds or drops one, C clears, Esc back to orbit");
        applyOverlay();
    }

    /** Hand the mouse back and restore the surface's shading, keeping the selection. */
    @Override
    public void deactivate() {
        active = false;
        pendingClick = false;
        if (scene.orbitMouse != null) {
            scene.orbitMouse.toolClick = null;
        }
        HalfEdgeMeshRuntime runtime = scene.surfaceRuntime();
        if (runtime != null) {
            runtime.clearTags();
            runtime.clearTagColors();
            if (shaderModeBefore != null) {
                runtime.setShaderMode(shaderModeBefore);
            }
        }
    }

    @Override
    public void addControls(List<ControlHint> controls) {
        controls.add(new ControlHint("click", "select region"));
        controls.add(new ControlHint("shift+click", "add / drop region"));
        controls.add(new ControlHint(Keys.C, "C", "clear selection", this::clearSelection));
    }

    /**
     * Queue a click for the next frame, which picks against a fresh face buffer.
     *
     * @param shiftHeld whether Shift was down, which adds to the selection instead of replacing it
     */
    public void requestClick(boolean shiftHeld) {
        pendingClick = active;
        pendingShift = shiftHeld;
    }

    /**
     * One frame while active: rebuild the regions when the surface or the ring tool's rings
     * changed, then run a queued click.
     */
    @Override
    public void perFrame() {
        HalfEdgeMeshRuntime runtime = scene.surfaceRuntime();
        MeshTopology surface = scene.halfEdgeSurface();
        if (!active || runtime == null || surface == null || surface.faceCount() == 0) {
            pendingClick = false;
            return;
        }
        if (regions == null || regions.mesh != surface
                || builtRingRevision != scene.ringTool.ringRevision) {
            long start = System.nanoTime();
            builtRingRevision = scene.ringTool.ringRevision;
            Map<String, boolean[]> rings = scene.ringTool.liveRingMarks();
            regions = new RingRegions(surface, rings.keySet().toArray(new String[0]),
                    rings.values().toArray(new boolean[0][])).build();
            for (String line : regions.reportLines()) {
                Platforms.get().log(LOG_PREFIX + line);
            }
            Platforms.get().log(String.format(Locale.ROOT, LOG_PREFIX + "regions built in %.0f ms",
                    (System.nanoTime() - start) / 1e6));
            reselect();
        }
        if (!runtime.facePickReady()) {
            runtime.uploadFacePickBuffer(surface);
        }
        if (!pendingClick) {
            return;
        }
        pendingClick = false;
        int width = Platforms.get().getWindowWidth();
        int height = Platforms.get().getWindowHeight();
        int framebufferX = width <= 0 ? 0
                : Math.round(scene.orbitMouse.lastX * (float) Platforms.get().getFrameBufferWidth()
                        / width);
        int framebufferY = height <= 0 ? 0
                : Math.round(scene.orbitMouse.lastY
                        * (float) Platforms.get().getFrameBufferHeight() / height);
        int activeFace = runtime.faceIndexAtPixel(scene.camera, framebufferX, framebufferY);
        if (activeFace < 0 || activeFace >= regions.regionByActiveFace.length) {
            lastError = "the click missed the surface";
            Platforms.get().log(LOG_PREFIX + lastError);
            return;
        }
        lastError = "";
        int faceId = surface.faceIdAt(activeFace);
        float[] centroid = new float[XYZ];
        Vector3f corner = new Vector3f();
        int corners = surface.faceVertexCount(faceId);
        for (int slot = 0; slot < corners; slot++) {
            surface.vertexPosition(surface.faceVertexAt(faceId, slot), corner);
            centroid[0] += corner.x / corners;
            centroid[1] += corner.y / corners;
            centroid[2] += corner.z / corners;
        }
        int region = regions.regionByActiveFace[activeFace];
        if (!pendingShift) {
            pickedPoints.clear();
        }
        boolean dropped = false;
        for (int picked = pickedPoints.size() - 1; pendingShift && picked >= 0; picked--) {
            float[] point = pickedPoints.get(picked);
            int pickedFace = regions.nearestActiveFace(point[0], point[1], point[2]);
            if (regions.regionByActiveFace[pickedFace] == region) {
                pickedPoints.remove(picked);
                dropped = true;
            }
        }
        if (!dropped) {
            pickedPoints.add(centroid);
        }
        lastRow = (dropped ? "dropped region " : "picked region ") + region + ": "
                + regions.regionFaceCount[region] + " faces, bounded by "
                + regions.boundingRingText(region);
        reselect();
        Platforms.get().log(LOG_PREFIX + lastRow + " -> " + selectedCount() + " of "
                + regions.regionCount + " selected, select=\"" + selectQuery + "\"");
    }

    /** Rebuild the query from the picked points, select its regions and recolour. */
    public void reselect() {
        if (regions == null) {
            return;
        }
        List<String> terms = new ArrayList<>();
        for (float[] point : pickedPoints) {
            terms.add(RingRegions.POINT_TERM + " " + SurfaceWaypoints.format(point, 1));
        }
        selectQuery = String.join(QUERY_JOIN, terms);
        selectedRegions = regions.select(selectQuery);
        applyOverlay();
    }

    /** Colour each region, dimming the unselected ones while anything is selected. */
    public void applyOverlay() {
        HalfEdgeMeshRuntime runtime = scene.surfaceRuntime();
        if (!active || runtime == null || regions == null) {
            return;
        }
        MeshTopology mesh = regions.mesh;
        Map<Integer, Integer> activeVertexById = new HashMap<>();
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            activeVertexById.put(mesh.vertexIdAt(activeVertex), activeVertex);
        }
        boolean anySelected = selectedCount() > 0;
        Map<String, boolean[]> tags = new HashMap<>();
        runtime.clearTagColors();
        for (int region = 0; region < regions.regionCount; region++) {
            String tag = TAG_PREFIX + region;
            tags.put(tag, new boolean[mesh.vertexCount()]);
            Vector4f colour = HalfEdgeMeshRuntime.stableTagColor("patch_" + region);
            if (anySelected && !selectedRegions[region]) {
                colour.set(UNSELECTED_GREY, UNSELECTED_GREY, UNSELECTED_GREY, 1f);
            }
            runtime.setTagColor(tag, colour);
        }
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            boolean[] mask = tags.get(TAG_PREFIX + regions.regionByActiveFace[activeFace]);
            int faceId = mesh.faceIdAt(activeFace);
            for (int slot = 0; slot < mesh.faceVertexCount(faceId); slot++) {
                mask[activeVertexById.get(mesh.faceVertexAt(faceId, slot))] = true;
            }
        }
        runtime.setShaderMode(HalfEdgeMeshRuntime.ShaderMode.STAGES);
        runtime.setTags(tags);
    }

    /** Drop the whole selection. */
    public void clearSelection() {
        pickedPoints.clear();
        lastRow = "selection cleared";
        Platforms.get().log(LOG_PREFIX + lastRow);
        reselect();
    }

    /**
     * Whether Shift is held on the scene's keyboard, which a Shift+click reads.
     *
     * @return true while either Shift key is down
     */
    public boolean shiftHeld() {
        return scene.keys != null && (scene.keys.pressedKeys.contains(Keys.LEFT_SHIFT)
                || scene.keys.pressedKeys.contains(Keys.RIGHT_SHIFT));
    }

    /**
     * Number of regions selected.
     *
     * @return the count of set flags in {@link #selectedRegions}
     */
    public int selectedCount() {
        int count = 0;
        for (boolean selected : selectedRegions) {
            count += selected ? 1 : 0;
        }
        return count;
    }
}
