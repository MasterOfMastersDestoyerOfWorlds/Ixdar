package ixdar.tools.regions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.annotations.tool.ToolAnnotation;
import ixdar.geometry.mesh.data.labels.MeshLabels;
import ixdar.geometry.mesh.data.representation.EdgeKey;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.data.rings.RingRegionExtraction;
import ixdar.geometry.mesh.data.rings.RingRegions;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.spline.SurfaceWaypoints;
import ixdar.graphics.render.color.Palette;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.gui.terminal.Terminal;
import ixdar.gui.terminal.commands.LabelCommand;
import ixdar.platform.Platforms;
import ixdar.automation.AutomationPortFile;
import ixdar.platform.input.Keys;
import ixdar.scenes.main.Toggle;
import ixdar.scenes.ring.RingScene;
import ixdar.tools.ControlHint;
import ixdar.tools.Item;
import ixdar.tools.Tool;

/**
 * The region tool of the editing scene: shows the region colours, selects regions by click and
 * Shift+click, and extracts the selection as its own closed mesh, cut along the ring splines. The
 * selection is kept as surface points.
 */
@ToolAnnotation(id = RingRegionTool.TOOL_NAME)
public final class RingRegionTool extends Tool {

    public static final String LOG_PREFIX = "[ring-regions] ";

    public static final String TOOL_NAME = "region tool";

    public static final String TOOL_PURPOSE = "region colours, select, name and extract regions";

    public static final String PART_PREFIX = "part ";

    public static final String UNLABELLED_PART = "unlabelled";

    public static final String TAG_PREFIX = "region_";

    public static final String QUERY_JOIN = "; ";

    public static final int XYZ = SurfaceWaypoints.COORDINATES_PER_WAYPOINT;

    public static final String EXPORT_DIRECTORY = "extracted";

    public static final String EXPORT_PREFIX = "region";

    /** Scene the tool runs on, whose region layer holds the regions the tool selects. */
    public final RingScene scene;

    /** Regions currently selected, one flag per region. */
    public boolean[] selectedRegions = new boolean[0];

    /** Surface points of the regions picked by click. */
    public final List<Vector3f> pickedPoints = new ArrayList<>();

    /** The {@code ring_regions} {@code select} query the current selection is. */
    public String selectQuery = "";

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

    private int selectedRevision = -1;

    private boolean pendingExtract;

    private boolean pendingClick;

    private boolean pendingShift;

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

    @Override
    public String desc() {
        return "Select the regions the rings bound, name them as parts saved with the model, hide or "
                + "isolate them, and extract the selection as its own closed mesh";
    }

    /** Colour the regions through the region layer; clicks arrive through {@link #onRelease}. */
    @Override
    public void activate() {
        active = true;
        scene.regionLayer.redraw();
    }

    /** Hand the mouse back and drop the extraction; the layer drops the colours unless kept on. */
    @Override
    public void deactivate() {
        active = false;
        pendingClick = false;
        pendingExtract = false;
        releaseExtraction();
        scene.regionLayer.redraw();
    }

    @Override
    public void addControls(List<ControlHint> controls) {
        controls.add(new ControlHint(Keys.CLICK, "select region"));
        controls.add(new ControlHint(Keys.CLICK, false, true, "add / drop region"));
        controls.add(new ControlHint("clear-selection", Keys.C, "clear selection", this::clearSelection));
        controls.add(new ControlHint("extract", Keys.E, "extract selection / back to surface",
                this::extractPressed));
        controls.add(new ControlHint("toggle-open-cut", Keys.O, "extracted: open cut / capped",
                this::toggleOpenCut));
        controls.add(new ControlHint("toggle-explode", Keys.X, "explode / collapse regions",
                scene.regionLayer.explosion::toggle));
        controls.add(new ControlHint("hide-selected", Keys.H, false, true, "hide selected regions",
                () -> scene.regionLayer.hideSelection(false)));
        controls.add(new ControlHint("isolate-selected", Keys.I, false, true,
                "isolate selected regions (hide the rest)",
                () -> scene.regionLayer.hideSelection(true)));
        RegionLayer layer = scene.regionLayer;
        controls.add(ControlHint.repeating("explode-less", Keys.COMMA, "explode less (hold to repeat)",
                () -> layer.explosion.nudge(-1)));
        controls.add(ControlHint.repeating("explode-more", Keys.PERIOD, "explode more (hold to repeat)",
                () -> layer.explosion.nudge(1)));
        controls.add(new ControlHint("drop-part-names", Keys.L, false, true,
                "drop the selected regions' part names", this::dropPartNames));
        controls.add(new ControlHint("name-part", Keys.L,
                "name the selected regions as one part: type the name, then Enter", this::startNamingPart));
    }

    /** Drop the part names of the selected regions, then clear the selection. */
    private void dropPartNames() {
        RegionLayer layer = scene.regionLayer;
        RingRegions regions = layer.regions;
        if (regions == null || selectedCount() == 0) {
            lastError = "select the regions whose part names to drop first";
            scene.errorToasts.show(TOOL_NAME, lastError);
            return;
        }
        int dropped = 0;
        for (int region = 0; region < selectedRegions.length; region++) {
            dropped += selectedRegions[region] ? layer.partLabels.unlabel(regions, region) : 0;
        }
        layer.labelsChanged();
        clearSelection();
        lastError = "";
        lastRow = "dropped " + dropped + " part name point(s); unsaved";
        Platforms.get().log(LOG_PREFIX + lastRow);
    }

    /**
     * Focus the terminal with the label command typed, so the part name is typed on the
     * scene's one text entry and Enter runs it.
     */
    private void startNamingPart() {
        Terminal terminal = scene.sceneTerminal;
        if (terminal == null || selectedCount() == 0) {
            lastError = terminal == null ? "this scene has no terminal to type in"
                    : "select the regions to name first";
            scene.errorToasts.show(TOOL_NAME, lastError);
            return;
        }
        lastError = "";
        lastRow = "naming " + selectedCount() + " region(s): type the part name, then Enter";
        Toggle.setPanelFocus(Toggle.IsTerminalFocused);
        terminal.type(LabelCommand.cmd);
        terminal.keyPress(Keys.SPACE.keyCode, 0, false);
    }

    /** Drop a shown extraction, else extract the selection on the next frame. */
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

    /** Switch a shown extraction between the open cut and the capped mesh. */
    public void toggleOpenCut() {
        if (!showingExtraction || extraction == null) {
            lastError = "no extraction is shown - select a region and press E first";
            scene.errorToasts.show(TOOL_NAME, lastError);
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
        int[] colourByRegion = scene.regionLayer.regions.colouring.colourByNode;
        Palette palette = scene.palette;
        extractedRuntime.setSolidColor(Palette.shadedEntry(palette.region,
                firstRegion < colourByRegion.length ? colourByRegion[firstRegion] : 0).toVector4f());
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
        extractedRuntime.setFeatureEdgeOverlay(List.of(
                new HalfEdgeMeshRuntime.FeatureEdgeCategory(palette.line, boundaryKeys)));
    }

    /**
     * Queue a click for the next frame, which picks against a fresh face buffer; Shift held adds
     * to the selection instead of replacing it. Drags stay the camera's.
     *
     * @param x     framebuffer x of the release, unused: the frame's surface pick places the click
     * @param y     framebuffer y of the release, unused
     * @param click true when the press did not move
     * @return {@code click}: every click is the tool's
     */
    @Override
    public boolean onRelease(float x, float y, boolean click) {
        if (click) {
            pendingClick = active;
            pendingShift = shiftHeld();
        }
        return click;
    }

    /**
     * One frame while active: re-resolve the selection when the region layer's regions changed,
     * then run a queued extraction or click.
     */
    @Override
    public void perFrame() {
        HalfEdgeMeshRuntime runtime = scene.surfaceRuntime();
        MeshTopology surface = scene.halfEdgeSurface();
        RingRegions regions = scene.regionLayer.regions;
        if (!active || runtime == null || surface == null || surface.faceCount() == 0
                || regions == null || regions.mesh != surface) {
            if (pendingClick) {
                lastError = runtime == null || surface == null || surface.faceCount() == 0
                        ? RingScene.NO_SURFACE_REFUSAL
                        : "the regions are still being built - click again in a moment";
                scene.errorToasts.show(TOOL_NAME, lastError);
            }
            pendingClick = false;
            return;
        }
        if (selectedRevision != scene.regionLayer.revision) {
            selectedRevision = scene.regionLayer.revision;
            releaseExtraction();
            reselect();
        }
        if (pendingExtract) {
            pendingExtract = false;
            if (selectedCount() == 0) {
                lastError = "no region is selected - click a region, then press E";
                scene.errorToasts.show(TOOL_NAME, lastError);
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
                lastError = "nothing was extracted - the log says why; select other regions";
                scene.errorToasts.show(TOOL_NAME, lastError);
                extraction = null;
                return;
            }
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
                lastError = "could not export " + target + ": " + failure.getMessage()
                        + " - the extraction is shown but not saved";
                scene.errorToasts.show(TOOL_NAME, lastError);
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
            runtime.uploadFacePickBuffer(surface, scene.regionLayer.hiddenByActiveFace);
        }
        if (!pendingClick) {
            return;
        }
        pendingClick = false;
        int width = Platforms.get().getWindowWidth();
        int height = Platforms.get().getWindowHeight();
        int framebufferX = width <= 0 ? 0
                : Math.round(scene.mouse.lastX * (float) Platforms.get().getFrameBufferWidth()
                        / width);
        int framebufferY = height <= 0 ? 0
                : Math.round(scene.mouse.lastY
                        * (float) Platforms.get().getFrameBufferHeight() / height);
        lastError = scene.cursorPickRefusal(runtime);
        int activeFace = !lastError.isEmpty() ? -1
                : runtime.faceIndexAtPixel(scene.camera, framebufferX, framebufferY);
        if (activeFace < 0 || activeFace >= regions.regionByActiveFace.length) {
            lastError = lastError.isEmpty() ? RingScene.MISSED_SURFACE_REFUSAL : lastError;
            scene.errorToasts.show(TOOL_NAME, lastError);
            return;
        }
        lastError = "";
        Vector3f centroid = surface.faceCentroid(surface.faceIdAt(activeFace));
        int region = regions.regionByActiveFace[activeFace];
        if (!pendingShift) {
            pickedPoints.clear();
        }
        boolean dropped = false;
        for (int picked = pickedPoints.size() - 1; pendingShift && picked >= 0; picked--) {
            Vector3f point = pickedPoints.get(picked);
            int pickedFace = regions.nearestActiveFace(point);
            if (regions.regionByActiveFace[pickedFace] == region) {
                pickedPoints.remove(picked);
                dropped = true;
            }
        }
        if (!dropped) {
            pickedPoints.add(centroid);
        }
        lastRow = (dropped ? "dropped region " : "picked region ") + region + ": "
                + regions.regionFaceCount[region] + " faces" + (regions.isSliver(region)
                        ? " (sliver)" : "") + ", bounded by " + regions.boundingRingText(region);
        reselect();
        Platforms.get().log(LOG_PREFIX + lastRow + " -> " + selectedCount() + " of "
                + regions.regionCount + " selected, select=\"" + selectQuery + "\"");
    }

    /**
     * The runtime tag a region's faces are drawn under, which names its draw range.
     *
     * @param region region index
     * @return the tag name
     */
    public static String regionTag(int region) {
        return TAG_PREFIX + region;
    }

    /** Rebuild the query from the picked points, select its regions and recolour. */
    public void reselect() {
        RingRegions regions = scene.regionLayer.regions;
        if (regions == null) {
            return;
        }
        List<String> terms = new ArrayList<>();
        for (Vector3f point : pickedPoints) {
            terms.add(RingRegions.POINT_TERM + " " + SurfaceWaypoints.format(point));
        }
        selectQuery = String.join(QUERY_JOIN, terms);
        selectedRegions = regions.select(selectQuery);
        scene.regionLayer.redraw();
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
        return scene.keys != null && (scene.keys.pressedKeys.contains(Keys.LEFT_SHIFT.keyCode)
                || scene.keys.pressedKeys.contains(Keys.RIGHT_SHIFT.keyCode));
    }

    /**
     * Handles on the regions the rings enclose: tag at the corners' centroid, face count, and part
     * name, bounding rings and state as detail; saved while every part name is. Area and flatness
     * are the region dataset's.
     *
     * @return one item per region, empty before the regions are first built
     */
    @Override
    public List<Item> items() {
        RegionLayer layer = scene.regionLayer;
        RingRegions regions = layer.regions;
        List<Item> items = new ArrayList<>();
        if (regions == null) {
            return items;
        }
        boolean hiding = layer.hiddenByActiveFace.length == regions.regionByActiveFace.length;
        int[] hiddenFaces = new int[regions.regionCount];
        PackedPoints corners = new PackedPoints(regions.regionCount);
        int[] cornerCount = new int[regions.regionCount];
        Vector3f corner = new Vector3f();
        for (int activeFace = 0; activeFace < regions.regionByActiveFace.length; activeFace++) {
            int region = regions.regionByActiveFace[activeFace];
            if (region < 0 || region >= regions.regionCount) {
                continue;
            }
            hiddenFaces[region] += hiding && layer.hiddenByActiveFace[activeFace] ? 1 : 0;
            int faceId = regions.mesh.faceIdAt(activeFace);
            for (int side = 0; side < regions.mesh.faceVertexCount(faceId); side++) {
                corners.add(region, regions.mesh.vertexPosition(regions.mesh.faceVertexAt(faceId, side), corner));
                cornerCount[region]++;
            }
        }
        for (int region = 0; region < regions.regionCount; region++) {
            Item item = new Item();
            item.label = regionTag(region);
            if (cornerCount[region] > 0) {
                item.location.set(corners.at(region).div(cornerCount[region]));
            }
            item.size = regions.regionFaceCount[region];
            boolean hidden = hiddenFaces[region] == regions.regionFaceCount[region];
            int label = region < layer.partLabels.labelByRegion.length
                    ? layer.partLabels.labelByRegion[region] : MeshLabels.UNLABELLED;
            item.saved = !layer.labelsUnsaved;
            item.detail = String.format(Locale.ROOT, "%s, bounded by %s%s%s%s",
                    label == MeshLabels.UNLABELLED ? UNLABELLED_PART
                            : PART_PREFIX + layer.partLabels.names.get(label),
                    regions.boundingRingText(region), regions.isSliver(region) ? ", sliver" : "",
                    selectedRegions.length > region && selectedRegions[region] ? ", selected" : "",
                    hidden ? ", hidden" : hiddenFaces[region] > 0 ? ", partly hidden" : "");
            items.add(item);
        }
        return items;
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
