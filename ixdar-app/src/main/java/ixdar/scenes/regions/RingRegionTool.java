package ixdar.scenes.regions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.joml.Vector3f;
import org.joml.Vector4f;

import ixdar.geometry.mesh.data.EdgeKey;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.RegionExplosion;
import ixdar.geometry.mesh.data.RingRegionExtraction;
import ixdar.geometry.mesh.data.RingRegions;
import ixdar.geometry.mesh.data.paths.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.graphics.render.Clock;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.Toggle;
import ixdar.platform.automation.AutomationPortFile;
import ixdar.platform.input.Keys;
import ixdar.scenes.model.ControlHint;
import ixdar.scenes.ring.EditTool;
import ixdar.scenes.ring.RingScene;

/**
 * The region-select tool of the editing scene: colours the regions the ring tool's rings cut the
 * surface into, selects them by click and Shift+click, and extracts the selection as its own
 * closed mesh, cut along the ring splines. The selection is kept as surface points.
 */
public final class RingRegionTool implements EditTool {

    public static final String LOG_PREFIX = "[ring-regions] ";

    public static final String TOOL_NAME = "region select";

    public static final String TAG_PREFIX = "region_";

    public static final float UNSELECTED_GREY = 0.42f;

    public static final String QUERY_JOIN = "; ";

    public static final int XYZ = SurfaceWaypoints.COORDINATES_PER_WAYPOINT;

    public static final String EXPORT_DIRECTORY = "extracted";

    public static final String REGION_COLOUR_KEY = "patch_";

    public static final String EXPORT_PREFIX = "region";

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

    /** The shown extraction of the selection, or {@code null} while the surface is shown. */
    public RingRegionExtraction extraction;

    /** Draws {@link #extraction} in place of the surface, or {@code null} with it. */
    public HalfEdgeMeshRuntime extractedRuntime;

    /** Whether the scene shows the extracted mesh on its own instead of the surface. */
    public boolean showingExtraction;

    /** Whether the extracted mesh is shown as the open cut rather than capped. */
    public boolean showingOpenCut;

    /** Where the last extraction was exported, or empty. */
    public String exportedPath = "";

    /** Exploded view of {@link #regions}, re-measured when they are rebuilt. */
    public final RegionExplosion explosion = new RegionExplosion(RingRegionTool::regionTag);

    private int builtRingRevision = -1;

    private boolean pendingExtract;

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

    /** Hand the mouse back, drop the extraction and restore the surface's shading. */
    @Override
    public void deactivate() {
        active = false;
        pendingClick = false;
        pendingExtract = false;
        releaseExtraction();
        if (scene.orbitMouse != null) {
            scene.orbitMouse.toolClick = null;
        }
        HalfEdgeMeshRuntime runtime = scene.surfaceRuntime();
        if (runtime != null) {
            explosion.collapse(runtime.tagOffsets);
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
        controls.add(new ControlHint(Keys.E, "E", "extract selection / back to surface",
                this::extractPressed));
        controls.add(new ControlHint(Keys.O, "O", "extracted: open cut / capped",
                this::toggleOpenCut));
        controls.add(new ControlHint(Keys.X, "X", "explode / collapse regions",
                explosion::toggle));
        controls.add(new ControlHint("hold , / .", "explode less / more"));
    }

    /**
     * The {@code explode} command: animate the exploded view to an amount and hold it there.
     *
     * @param amount 0 assembled to 1 fully exploded; clamped into that range
     */
    public void explodeTo(float amount) {
        explosion.animateTo(amount);
    }

    /** E: drop a shown extraction, else extract the selection on the next frame. */
    public void extractPressed() {
        if (showingExtraction) {
            releaseExtraction();
            Platforms.get().log(LOG_PREFIX + "back to the surface");
            return;
        }
        pendingExtract = active;
    }

    /**
     * Back to the surface: free the extracted mesh's GL buffers and drop the extraction, so no
     * more than the shown one is ever held.
     */
    public void releaseExtraction() {
        showingExtraction = false;
        showingOpenCut = false;
        if (extractedRuntime != null) {
            extractedRuntime.dispose();
            extractedRuntime = null;
        }
        extraction = null;
    }

    /** O: switch a shown extraction between the open cut and the capped mesh. */
    public void toggleOpenCut() {
        if (!showingExtraction || extraction == null) {
            return;
        }
        showingOpenCut = !showingOpenCut;
        uploadExtraction();
    }

    /**
     * Hand {@link #extractedRuntime} the extraction as shown: the capped mesh, or the open cut
     * with its boundary, which is the spline cut, drawn over it.
     */
    private void uploadExtraction() {
        MeshTopology shown = showingOpenCut ? extraction.openMesh : extraction.closedMesh;
        if (extractedRuntime != null) {
            extractedRuntime.dispose();
        }
        extractedRuntime = new HalfEdgeMeshRuntime();
        extractedRuntime.upload(shown);
        int firstRegion = 0;
        while (firstRegion + 1 < selectedRegions.length && !selectedRegions[firstRegion]) {
            firstRegion++;
        }
        extractedRuntime.setSolidColor(
                HalfEdgeMeshRuntime.stableTagColor(REGION_COLOUR_KEY + firstRegion));
        if (!showingOpenCut) {
            return;
        }
        Map<Integer, Integer> activeVertexById = new HashMap<>();
        for (int activeVertex = 0; activeVertex < shown.vertexCount(); activeVertex++) {
            activeVertexById.put(shown.vertexIdAt(activeVertex), activeVertex);
        }
        List<Long> boundaryKeys = new ArrayList<>();
        for (int activeEdge = 0; activeEdge < shown.edgeCount(); activeEdge++) {
            int edgeId = shown.edgeIdAt(activeEdge);
            if (shown.isBoundaryEdge(edgeId)) {
                int halfEdge = shown.edgeHalfEdge(edgeId);
                boundaryKeys.add(EdgeKey.undirected(
                        activeVertexById.get(shown.halfEdgeVertex(halfEdge)),
                        activeVertexById.get(shown.halfEdgeEndVertex(halfEdge))));
            }
        }
        extractedRuntime.setShaderMode(HalfEdgeMeshRuntime.ShaderMode.STAGES);
        extractedRuntime.setFeatureEdgeOverlay(List.of(
                new HalfEdgeMeshRuntime.FeatureEdgeCategory(Color.EDGE_MARK_AMBER, boundaryKeys)));
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
            releaseExtraction();
            Map<String, boolean[]> rings = scene.ringTool.liveRingMarks();
            regions = new RingRegions(surface, rings.keySet().toArray(new String[0]),
                    rings.values().toArray(new boolean[0][])).build();
            for (String line : regions.reportLines()) {
                Platforms.get().log(LOG_PREFIX + line);
            }
            Platforms.get().log(String.format(Locale.ROOT, LOG_PREFIX + "regions built in %.0f ms",
                    (System.nanoTime() - start) / 1e6));
            explosion.measure(surface, regions.regionByActiveFace, regions.regionCount);
            reselect();
        }
        boolean keysFree = scene.keys != null && !Toggle.IsTerminalFocused.value;
        int heldDirection = (keysFree && scene.keys.pressedKeys.contains(Keys.PERIOD) ? 1 : 0)
                - (keysFree && scene.keys.pressedKeys.contains(Keys.COMMA) ? 1 : 0);
        explosion.step((float) Clock.deltaTime(), heldDirection, runtime.tagOffsets);
        if (pendingExtract) {
            pendingExtract = false;
            if (selectedCount() == 0) {
                lastError = "select a region to extract first";
                Platforms.get().log(LOG_PREFIX + lastError);
                return;
            }
            releaseExtraction();
            long start = System.nanoTime();
            Map<String, SurfaceSpline> splineByLabel = scene.ringTool.liveRingSplines();
            SurfaceSpline[] splines = new SurfaceSpline[regions.ringLabels.length];
            for (int ring = 0; ring < splines.length; ring++) {
                splines[ring] = splineByLabel.get(regions.ringLabels[ring]);
            }
            extraction = new RingRegionExtraction(regions, splines, selectedRegions.clone());
            try {
                extraction.solidCheck = Platforms.get().meshBooleanBackend();
            } catch (UnsupportedOperationException noKernel) {
                extraction.solidCheck = null;
            }
            extraction.build();
            for (String line : extraction.reportLines()) {
                Platforms.get().log(LOG_PREFIX + line);
            }
            if (extraction.closedMesh == null) {
                lastError = "nothing was extracted";
                extraction = null;
                return;
            }
            // The export is a frozen operand for load_mesh and mesh_boolean; the node line logged
            // after it is the live form that follows ring edits.
            List<Integer> picked = new ArrayList<>();
            StringBuilder name = new StringBuilder(EXPORT_PREFIX);
            for (int region = 0; region < selectedRegions.length; region++) {
                if (selectedRegions[region]) {
                    picked.add(region);
                    name.append('_').append(region);
                }
            }
            Path target = AutomationPortFile.checkoutRoot().resolve(AutomationPortFile.TMP_DIRECTORY)
                    .resolve(EXPORT_DIRECTORY).resolve(name + ".obj");
            StringBuilder obj = new StringBuilder();
            Vector3f position = new Vector3f();
            MeshTopology piece = extraction.closedMesh;
            Map<Integer, Integer> objIndexByVertexId = new HashMap<>();
            for (int activeVertex = 0; activeVertex < piece.vertexCount(); activeVertex++) {
                int vertexId = piece.vertexIdAt(activeVertex);
                piece.vertexPosition(vertexId, position);
                objIndexByVertexId.put(vertexId, activeVertex + 1);
                obj.append(String.format(Locale.ROOT, "v %.6f %.6f %.6f%n", position.x,
                        position.y, position.z));
            }
            for (int activeFace = 0; activeFace < piece.faceCount(); activeFace++) {
                int faceId = piece.faceIdAt(activeFace);
                obj.append('f');
                for (int corner = 0; corner < piece.faceVertexCount(faceId); corner++) {
                    obj.append(' ').append(objIndexByVertexId.get(piece.faceVertexAt(faceId,
                            corner)));
                }
                obj.append('\n');
            }
            try {
                Files.createDirectories(target.getParent());
                Files.write(target, obj.toString().getBytes(StandardCharsets.UTF_8));
                exportedPath = target.toAbsolutePath().toString();
            } catch (IOException failure) {
                exportedPath = "";
                lastError = "could not export " + target + ": " + failure.getMessage();
                Platforms.get().log(LOG_PREFIX + lastError);
            }
            lastRow = String.format(Locale.ROOT, "extracted %d region(s) %s in %.0f ms: %d faces, "
                    + "closed=%b, exported to %s", picked.size(), picked, (System.nanoTime() - start)
                            / 1e6, piece.faceCount(), extraction.closed, exportedPath);
            Platforms.get().log(LOG_PREFIX + lastRow);
            Platforms.get().log(LOG_PREFIX + "as a graph statement: extract_ring_region("
                    + "geometry=<rings>.geometry, select=\"" + selectQuery + "\")");
            showingExtraction = true;
            showingOpenCut = false;
            uploadExtraction();
        }
        if (!runtime.facePickReady()) {
            runtime.uploadFacePickBuffer(surface);
        }
        if (!pendingClick) {
            return;
        }
        pendingClick = false;
        if (explosion.exploded()) {
            // The id pass draws the surface assembled, so a click on a moved region would name
            // whatever lies at its rest position.
            lastError = "collapse the exploded view (X) to pick a region";
            Platforms.get().log(LOG_PREFIX + lastError);
            return;
        }
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

    /**
     * The runtime tag a region's faces are coloured and offset under.
     *
     * @param region region index
     * @return the tag name
     */
    public static String regionTag(int region) {
        return TAG_PREFIX + region;
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
            String tag = regionTag(region);
            tags.put(tag, new boolean[mesh.vertexCount()]);
            Vector4f colour = HalfEdgeMeshRuntime.stableTagColor(REGION_COLOUR_KEY + region);
            if (anySelected && !selectedRegions[region]) {
                colour.set(UNSELECTED_GREY, UNSELECTED_GREY, UNSELECTED_GREY, 1f);
            }
            runtime.setTagColor(tag, colour);
        }
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            boolean[] mask = tags.get(regionTag(regions.regionByActiveFace[activeFace]));
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
