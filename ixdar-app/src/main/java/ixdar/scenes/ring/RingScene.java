package ixdar.scenes.ring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import ixdar.annotations.command.Subsystem;
import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.data.paths.query.SurfacePicker;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.graphics.render.model.MeshOverlayRuntime;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.ErrorToasts;
import ixdar.gui.ui.text.HyperString;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.mesh.MeshNodeViewerScene;
import ixdar.tools.ControlHint;
import ixdar.tools.Tool;
import ixdar.tools.connection.ConnectionTool;
import ixdar.tools.regions.RegionLayer;
import ixdar.tools.regions.RingRegionTool;
import ixdar.tools.ring.RingDslWriter;
import ixdar.tools.ring.RingTool;

/**
 * The mesh editing scene: the mesh viewer hosting the orbit, ring, region-select and connection
 * {@link Tool}s over the ring tool's rings. Ctrl+R, Ctrl+T and Ctrl+J pick a tool; Esc goes
 * to the active tool, else back to orbit.
 */
@SceneAnnotation(id = "ring-tool")
public class RingScene extends MeshNodeViewerScene {

    public static final String LOG_PREFIX = "[mesh-edit] ";

    public static final String SAVE_RINGS_ACTION = "save-rings";

    public static final int COORDINATES_PER_POINT = 3;

    public static final float ANCHOR_HIT_RADIUS_PIXELS = 8f;

    public static final float HALF = 0.5f;

    public static final float LABEL_BORDER_PIXELS = 2f;

    public static final float LABEL_ROW_PIXELS = 26f;

    public static final String CAMERA_MOVING_REFUSAL =
            "the camera is still moving - click again once it stops";

    public static final String PICK_NOT_READY_REFUSAL =
            "the surface is not ready for picking yet - click again in a moment";

    public static final String MISSED_SURFACE_REFUSAL =
            "the click missed the mesh - click on the surface";

    public static final String NO_SURFACE_REFUSAL =
            "no mesh is shown yet - wait for the model to load, then click";

    /** The hover-to-preview ring tool, which holds the rings every tool works over. */
    public final RingTool ringTool = new RingTool(this);

    /** The region tool, which selects and extracts the regions the ring tool's rings enclose. */
    public final RingRegionTool regionTool = new RingRegionTool(this);

    /** The tool showing how two places are joined and the neck between them, to ring. */
    public final ConnectionTool connectionTool = new ConnectionTool(this);

    /** The region colours, shown in the region tool and, switched on, in every tool. */
    public final RegionLayer regionLayer = new RegionLayer(this);

    /** Why the tools refused the last clicks and keys, shown at the top left until they fade. */
    public final ErrorToasts errorToasts = new ErrorToasts(this);

    /** The ring-labelled masks the graph left, drawn thick and numbered rather than as edges. */
    public Map<String, boolean[]> ringMarksByLabel = new LinkedHashMap<>();

    /** Whether each ring's number is drawn beside it; the {@code N} key flips it. */
    public boolean showRingNumbers;

    /** Whether the rings are drawn, in every tool, while the regions are assembled; H flips it. */
    public boolean showRings = true;

    /**
     * Whether the rings are drawn while the region tool's view is exploded, where they would float
     * at the assembled positions; H flips this one then, and collapsing returns to
     * {@link #showRings}.
     */
    public boolean showRingsExploded;

    /** Surface point under the cursor at the active tool's last {@link #pickCursor}, packed xyz. */
    public final float[] cursorPoint = new float[COORDINATES_PER_POINT];

    /** The labels drawn this frame over the surface: ring numbers and region labels as shown. */
    public final List<HyperString> shownLabels = new ArrayList<>();

    /**
     * Whether the camera's view is the one the last frame had: labels keep their anchors while it
     * moves and are placed afresh once it holds still.
     */
    public boolean cameraStill;

    /** Direction of the view ray through the cursor at the last pick, packed xyz. */
    public final float[] cursorRayDirection = new float[COORDINATES_PER_POINT];

    /** Face the last pick hit, or -1 when it missed or the camera was moving. */
    public int cursorFaceId = -1;

    /** Corner of {@link #cursorFaceId} nearest {@link #cursorPoint}, the vertex a click anchors to. */
    public int cursorVertexId = -1;

    /** Why the last {@link #pickCursor} found no surface point, as a refusal says it, or empty. */
    public String cursorMissReason = "";

    /** Framebuffer pixel the last pick read, x. */
    public int cursorFramebufferX;

    /** Framebuffer pixel the last pick read, y. */
    public int cursorFramebufferY;

    private final SurfacePicker cursorPicker = new SurfacePicker();
    private final Matrix4f lastFrameView = new Matrix4f();
    private final float[] cursorRayOrigin = new float[COORDINATES_PER_POINT];
    private final float[] anchorPixel = new float[COORDINATES_PER_POINT];
    private final Vector3f anchorPosition = new Vector3f();

    /** Host the ring, region and connection tools on ctrl+R, ctrl+T and ctrl+J, after the model scene's. */
    @Override
    public void setTools() {
        super.setTools();
        addToolSwitch(ringTool, new ControlHint("ring-tool", Keys.R, true, "ctrl+R", "draw and edit rings",
                () -> switchTool(ringTool)));
        addToolSwitch(regionTool, new ControlHint("region-tool", Keys.T, true, "ctrl+T",
                RingRegionTool.TOOL_PURPOSE, () -> switchTool(regionTool)));
        addToolSwitch(connectionTool, new ControlHint("connection-tool", Keys.J, true, "ctrl+J",
                "find the neck joining two places", () -> switchTool(connectionTool)));
    }

    @Override
    public String windowTitle() {
        return "Ixdar : Mesh Edit";
    }

    /**
     * Draw the surface through a runtime that also draws general overlays, since the rings are
     * point sets and coloured line groups over it; it also tells the camera what hides a point,
     * for the ring numbers anchored in 3D.
     *
     * @return the installed overlay runtime
     */
    @Override
    public HalfEdgeMeshRuntime createRuntime() {
        meshRuntime = new MeshOverlayRuntime();
        camera.pointVisibility = meshRuntime;
        return meshRuntime;
    }

    /**
     * Run one frame of every tool and the region layer, then gather the labels shown: ring numbers
     * while numbered and shown, region labels while the regions are assembled and current.
     */
    @Override
    public void updateScene() {
        super.updateScene();
        cameraStill = camera.view.equals(lastFrameView);
        lastFrameView.set(camera.view);
        regionLayer.perFrame();
        errorToasts.perFrame();
        shownLabels.clear();
        if (showRingNumbers && ringsShown()) {
            shownLabels.addAll(ringTool.ringNumbers);
        }
        if (!regionLayer.explosion.exploded() && !regionTool.showingExtraction
                && regionLayer.regions != null && regionLayer.regions.mesh == halfEdgeSurface()
                && regionLayer.builtRingRevision == ringTool.ringRevision) {
            shownLabels.addAll(regionLayer.regionLabels);
        }
    }

    /**
     * Why a tool cannot read the face under the cursor now: the camera is moving, the view itself
     * refuses picks ({@link #pickRefusal()}), or the surface's face-pick buffer is not uploaded.
     *
     * @param runtime the surface's runtime, or {@code null} before one is created
     * @return the refusal a click gets, or empty when a pick can run
     */
    public String cursorPickRefusal(HalfEdgeMeshRuntime runtime) {
        if (cameraMoving()) {
            return CAMERA_MOVING_REFUSAL;
        }
        String viewRefusal = pickRefusal();
        if (!viewRefusal.isEmpty()) {
            return viewRefusal;
        }
        return runtime == null || !runtime.facePickReady() ? PICK_NOT_READY_REFUSAL : "";
    }

    /**
     * Pick the surface under the cursor for the active tool: the face from the GPU id buffer, the
     * point the view ray hits on it and the face corner nearest that point. Nothing is picked
     * while the camera moves; {@link #cursorMissReason} says why nothing was.
     *
     * @param runtime the surface's runtime, its face-pick buffer uploaded
     * @param surface the shown surface
     * @return true when {@link #cursorVertexId} names a vertex under the cursor
     */
    public boolean pickCursor(HalfEdgeMeshRuntime runtime, MeshTopology surface) {
        cursorFaceId = -1;
        cursorVertexId = -1;
        cursorMissReason = cursorPickRefusal(runtime);
        int width = Platforms.get().getWindowWidth();
        int height = Platforms.get().getWindowHeight();
        cursorFramebufferX = width <= 0 ? 0
                : Math.round(mouse.lastX * (float) Platforms.get().getFrameBufferWidth()
                        / width);
        cursorFramebufferY = height <= 0 ? 0
                : Math.round(mouse.lastY * (float) Platforms.get().getFrameBufferHeight()
                        / height);
        int faceIndex = !cursorMissReason.isEmpty() ? -1
                : runtime.faceIndexAtPixel(camera, cursorFramebufferX, cursorFramebufferY);
        if (faceIndex < 0 || faceIndex >= surface.faceCount()
                || !runtime.rayThroughPixel(camera, cursorFramebufferX, cursorFramebufferY,
                        cursorRayOrigin, cursorRayDirection)
                || !cursorPicker.pickNear(surface, surface.faceIdAt(faceIndex), cursorRayOrigin,
                        cursorRayDirection)) {
            cursorMissReason = cursorMissReason.isEmpty() ? MISSED_SURFACE_REFUSAL
                    : cursorMissReason;
            return false;
        }
        cursorFaceId = cursorPicker.faceId;
        cursorPoint[0] = cursorPicker.pointX;
        cursorPoint[1] = cursorPicker.pointY;
        cursorPoint[2] = cursorPicker.pointZ;
        cursorVertexId = cursorPicker.nearestCornerVertexId(surface);
        return cursorVertexId >= 0;
    }

    /**
     * The viewer's refusal, or the region tool's view when it does not draw the surface where it
     * lies: an exploded view or a shown extraction.
     *
     * @return the reason, or empty when the shown surface can be picked
     */
    @Override
    public String pickRefusal() {
        if (regionTool.showingExtraction) {
            return "the region tool shows an extraction; E returns to the surface";
        }
        if (regionLayer.explosion.exploded()) {
            return "collapse the exploded view (X in the region tool, or xp 0) to pick the surface";
        }
        return super.pickRefusal();
    }

    /**
     * The anchor the cursor is over at the last pick: the nearest whose projection lies within
     * {@link #ANCHOR_HIT_RADIUS_PIXELS} of the cursor and which the surface does not hide.
     *
     * @param runtime        the surface's runtime, which projects anchors to pixels
     * @param surface        the shown surface
     * @param anchorVertexId the anchors, each a vertex of {@code surface}
     * @return the anchor's index in {@code anchorVertexId}, or -1 for none
     */
    public int anchorUnderCursor(HalfEdgeMeshRuntime runtime, MeshTopology surface,
            int[] anchorVertexId) {
        if (cursorVertexId < 0) {
            return -1;
        }
        int hovered = -1;
        double nearestPixels = Double.POSITIVE_INFINITY;
        double eyeToHit = camera.position.distance(cursorPoint[0], cursorPoint[1], cursorPoint[2]);
        double depthReach = ANCHOR_HIT_RADIUS_PIXELS * worldPerPixel();
        for (int anchor = 0; anchor < anchorVertexId.length; anchor++) {
            int vertexId = anchorVertexId[anchor];
            surface.vertexPosition(vertexId, anchorPosition);
            boolean onCursorFace = false;
            for (int corner = 0; corner < surface.faceVertexCount(cursorFaceId); corner++) {
                onCursorFace |= surface.faceVertexAt(cursorFaceId, corner) == vertexId;
            }
            if (!onCursorFace && camera.position.distance(anchorPosition) > eyeToHit + depthReach
                    || !runtime.projectToPixels(camera, anchorPosition.x, anchorPosition.y,
                            anchorPosition.z, anchorPixel)) {
                continue;
            }
            double pixels = Math.hypot(Math.floor(anchorPixel[0]) - cursorFramebufferX,
                    Math.floor(anchorPixel[1]) - cursorFramebufferY);
            if (pixels <= ANCHOR_HIT_RADIUS_PIXELS && pixels < nearestPixels) {
                nearestPixels = pixels;
                hovered = anchor;
            }
        }
        return hovered;
    }

    /**
     * Model units one screen pixel spans at the cursor's surface point, the scale a pixel reach
     * is in.
     *
     * @return the span, or 0 before the framebuffer has a size
     */
    public double worldPerPixel() {
        return camera.worldPerPixel(cursorPoint[0], cursorPoint[1], cursorPoint[2]);
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
            overlay.showLineGroups = ringsShown();
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
        if (pendingModel != null) {
            ringTool.discardConfirmedRings("loading " + pendingModel.path);
        }
        super.applyPendingModel();
    }

    /**
     * Draw the labels {@link #updateScene} gathered at their anchors, each ring number hidden where
     * the surface is in front of it, then the refusals at the top left.
     */
    @Override
    public void drawSceneOverlayText() {
        if (surfaceRuntime() != null) {
            if (showRingNumbers && ringsShown()) {
                ringTool.placeRingNumbers(camera);
            }
            Drawing.getDrawing().font.drawAnchoredStrings(shownLabels, LABEL_ROW_PIXELS, camera2D);
        }
        errorToasts.draw(camera2D);
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
     * Write the rings the tool holds, then the region tool's part labels as the .dsl's closing
     * block of label statements. Ctrl+S or a click on the menu row reaches here; a bare S does
     * not, and nothing else touches the working .dsl. A refused save is shown.
     */
    public void saveRingsPressed() {
        boolean ringsDue = ringTool.active || ringTool.unsavedRingCount() > 0;
        if (ringsDue) {
            ringTool.saveRings();
            errorToasts.show(ringTool.toolName(), ringTool.lastError);
        }
        // Labels wait for a ring save that failed, so the file never holds them past its rings.
        if (!regionLayer.labelsUnsaved || ringsDue && !ringTool.lastError.isEmpty()) {
            return;
        }
        String target = workingDslPath();
        regionTool.lastError = target == null ? "this scene has no working .dsl; "
                + regionLayer.partLabels.names.size() + " part label(s) stay unsaved" : "";
        try {
            if (target != null) {
                Path path = Path.of(target);
                RingDslWriter.writeAtomically(path, RingDslWriter.withLabels(
                        new String(Files.readAllBytes(path), StandardCharsets.UTF_8),
                        regionLayer.partLabels));
                regionLayer.labelsUnsaved = false;
                regionTool.lastRow = "saved " + regionLayer.partLabels.names.size()
                        + " part label(s) to " + target + "; " + regionLayer.partsSummary();
                Platforms.get().log(RingRegionTool.LOG_PREFIX + regionTool.lastRow);
            }
        } catch (IOException | RuntimeException failure) {
            regionTool.lastError = "could not write the part labels to " + target + ": "
                    + failure.getMessage();
        }
        errorToasts.show(regionTool.toolName(), regionTool.lastError);
    }

    /**
     * The hints every tool shares, after the tool keys and the active tool's own hints the scene
     * lists first. Ctrl+I and Esc come from the model scene, so no tool may bind either; a tool
     * takes Esc through {@link Tool#escapePressed} instead.
     *
     * @param controls the scene's section
     */
    @Override
    public void setControls(List<ControlHint> controls) {
        // Each toggle reports its new state as the active tool's last row, which the reply carries.
        controls.add(new ControlHint("toggle-region-colours", Keys.G, "G", "region colours in every tool", () -> {
            regionLayer.toggleVisible();
            activeTool.lastRow = ControlHint.toggled("region colours outside the region tool", regionLayer.visible);
        }));
        controls.add(new ControlHint("toggle-absorb-slivers", Keys.A, "A", "absorb slivers into largest neighbour",
                () -> {
                    regionLayer.toggleAbsorbSlivers();
                    activeTool.lastRow = ControlHint.toggled("slivers absorbed", regionLayer.absorbSlivers);
                }));
        controls.add(new ControlHint("show-hidden-regions", Keys.U, "U", "show hidden regions", regionLayer::showAll));
        controls.add(new ControlHint(SAVE_RINGS_ACTION, Keys.S, true, "ctrl+S", "save rings", this::saveRingsPressed));
        controls.add(new ControlHint("toggle-ring-numbers", Keys.N, "N", "ring numbers", () -> {
            showRingNumbers = !showRingNumbers;
            activeTool.lastRow = ControlHint.toggled("ring numbers shown", showRingNumbers);
        }));
        // H flips the visibility that applies now: the exploded view's, or the assembled one.
        controls.add(new ControlHint("toggle-rings", Keys.H, "H", "show / hide rings", () -> {
            if (regionLayer.explosion.exploded()) {
                showRingsExploded = !showRingsExploded;
            } else {
                showRings = !showRings;
            }
            activeTool.lastRow = ControlHint.toggled("rings shown", ringsShown());
        }));
        super.setControls(controls);
    }

    /**
     * Whether the ring lines and numbers are drawn this frame: the exploded view's own setting
     * while the regions are exploded, else the viewer's.
     *
     * @return true when the rings are drawn
     */
    public boolean ringsShown() {
        return regionLayer.explosion.exploded() ? showRingsExploded : showRings;
    }

    /**
     * The ring scene shows its ring regions besides the model and the view.
     *
     * @param subsystem a scene-held subsystem
     * @return {@link #regionLayer} for {@link Subsystem#RINGS}, else what the model scene shows
     */
    @Override
    public Object dataset(Subsystem subsystem) {
        return subsystem == Subsystem.RINGS ? regionLayer : super.dataset(subsystem);
    }
}
