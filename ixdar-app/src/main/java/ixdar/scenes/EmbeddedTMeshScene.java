package ixdar.scenes;

import java.io.IOException;

import org.joml.Vector3f;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.embedding.ArcRerouteFailure;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedContraction;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMeshBuilder;
import ixdar.geometry.mesh.quadlayout.embedding.TorusLayoutFixture;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroPatchSplitOperator;
import ixdar.graphics.render.model.QuadLayoutRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.gl.Platform;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.MouseTrap;
import ixdar.platform.input.OrbitMouseTrap;

/**
 * Debug view of an embedded T-mesh, drawn on its mesh the way LCBK19 Figure 9 draws one:
 * the surface underneath, each arc as the jagged edge path it snapped onto (positive arcs
 * orange, zero arcs red so the collapse targets are obvious), and each node as a sphere
 * (critical nodes tinted apart, since the operators may never move those).
 *
 * <p>By default it renders the hand-authored {@link TorusLayoutFixture} — the Figure-9
 * configuration the operators are tested against. Set {@code -DembeddedTMesh.off=<path>} to
 * instead build the T-mesh from the real pipeline ({@link QuadLayoutEngine} through the carve,
 * assembled by {@link EmbeddedTMeshBuilder}) on that mesh, which is the raw quantized layout —
 * positive and zero arcs both present — before any contraction.
 *
 * <p>Launch with {@code IxdarWindow embedded-tmesh}. SPACE steps a zero-arc collapse, PERIOD a
 * zero-patch split, C drives all three operators to a fixed point, R rebuilds.
 */
@SceneAnnotation(id = "embedded-tmesh")
public class EmbeddedTMeshScene extends Scene {

    /** Window title. */
    public static final String SCENE_TITLE = "Ixdar : Embedded T-Mesh";

    /**
     * System property selecting a mesh file to build the T-mesh from the real pipeline; unset
     * renders the hand-authored torus fixture instead.
     */
    public static final String OFF_PROPERTY = "embeddedTMesh.off";

    /** System property for the pipeline's separation angle in degrees; defaults to 15. */
    public static final String ALPHA_PROPERTY = "embeddedTMesh.alpha";

    /**
     * System property for how many zero-arc collapses (LCBK19 operator 1) to apply before
     * rendering: an integer, or {@code all} to collapse every collapsible zero arc. Lets a
     * screenshot show the layout at a chosen point in the collapse.
     */
    public static final String COLLAPSE_PROPERTY = "tmesh.collapse";

    /** System property for how many operator-(2) splits to apply at startup: integer or {@code all}. */
    public static final String SPLIT_PROPERTY = "tmesh.split";

    /**
     * System property that, when set to {@code all}, drives all three operators to a fixed point
     * at startup via {@link EmbeddedContraction} — the fully re-embedded T-mesh, no zero arcs and
     * no zero patches — so a headless screenshot shows the final layout. In the window, C does the
     * same on demand.
     */
    public static final String CONTRACT_PROPERTY = "tmesh.contract";

    /**
     * System property that, when {@code true}, drives the operators until the first reroute failure
     * and highlights the wall it hit — the two disconnected regions, the pivot, the survivor, the
     * stranded arc, and the freed channel — so the failure can be seen rather than guessed. In the
     * window, H toggles the highlight.
     */
    public static final String CONTRACT_FAIL_PROPERTY = "embeddedTMesh.contractFail";

    /** Request value meaning "apply as many as possible". */
    public static final String ALL = "all";

    /** Log prefix for a count of operator steps applied at startup. */
    public static final String APPLIED_PREFIX = "[embedded-tmesh] applied ";

    /** Default pipeline separation angle, in degrees, when the mesh is built from a file. */
    public static final double DEFAULT_ALPHA_DEGREES = 15.0;

    /** Orbit azimuth the camera starts at, looking down onto the mesh. */
    public static final float CAMERA_AZIMUTH = (float) Math.toRadians(35.0);

    /** Orbit elevation the camera starts at. */
    public static final float CAMERA_ELEVATION = (float) Math.toRadians(35.0);

    /** Nearest the camera may zoom, as a floor independent of mesh size. */
    public static final float CAMERA_DISTANCE_MIN = 0.5f;

    /** Camera distance as a multiple of the mesh radius. */
    public static final float CAMERA_DISTANCE_RADIUS_MUL = 2.5f;

    /** Farthest the camera may zoom, as a multiple of the mesh radius. */
    public static final float ZOOM_MAX_RADIUS_MUL = 5.0f;

    /** Nearest zoom as a fraction of the mesh radius. */
    public static final float ZOOM_MIN_RADIUS_FRACTION = 0.02f;

    /** Camera distance, as a multiple of mesh radius, when framing a captured reroute failure. */
    public static final float FAILURE_VIEW_DISTANCE_MUL = 0.55f;

    private OrbitMouseTrap orbitMouse;
    private QuadLayoutRuntime runtime;
    private String offPath;
    private EmbeddedTMesh tmesh;
    private HalfEdgeMesh surfaceMesh;
    private int eulerCharacteristic;
    private ZeroArcCollapseOperator collapseOperator;
    private ZeroPatchSplitOperator splitOperator;
    private ArcRerouteFailure failure;
    private final Vector3f meshCenter = new Vector3f();

    /** Zero-arc collapses (operator 1) requested by keypress, applied on the render thread. */
    private volatile int pendingCollapseSteps;

    /** Zero-patch splits (operator 2) requested by keypress, applied on the render thread. */
    private volatile int pendingSplitSteps;

    /** Whether a rebuild of the layout was requested by keypress. */
    private volatile boolean pendingReset;

    /** Whether a full contraction (all three operators to a fixed point) was requested by keypress. */
    private volatile boolean pendingContract;

    /**
     * Default constructor wired by the scene annotation processor.
     */
    public EmbeddedTMeshScene() {
        super();
    }

    @Override
    public void initGL() {
        super.initGL();
        Platforms.gl().setWindowTitle(SCENE_TITLE);

        orbitMouse = new OrbitMouseTrap(camera, this);
        keys = new EmbeddedTMeshSceneKeys(this, orbitMouse, camera, this);
        mouse = orbitMouse;
        bindAutomationIfAvailable(Platforms.get(), keys, mouse);
        bindInputDirect(Platforms.get(), keys, mouse);

        offPath = System.getProperty(OFF_PROPERTY);
        try {
            assembleLayout();
            applyInitialSplits();
            applyInitialCollapses();
            applyInitialContraction();

            runtime = new QuadLayoutRuntime();
            runtime.upload(surfaceMesh);
            runtime.frameCamera(camera);
            applyContractToFailure();
            runtime.setEmbeddedTMesh(tmesh);
            if (failure != null) {
                runtime.setFailureHighlight(tmesh.topology.copy, failure);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize embedded T-mesh scene", ex);
        }

        meshCenter.set(surfaceMesh.center(new Vector3f()));
        float meshRadius = surfaceMesh.radius();
        float minZoom = Math.max(CAMERA_DISTANCE_MIN, meshRadius * ZOOM_MIN_RADIUS_FRACTION);
        float maxZoom = Math.max(CAMERA_DISTANCE_MIN, meshRadius * ZOOM_MAX_RADIUS_MUL);
        orbitMouse.setDistanceBounds(minZoom, maxZoom);
        if (failure != null) {
            aimAtFailure(meshRadius);
        } else {
            float orbitDistance = Math.max(CAMERA_DISTANCE_MIN,
                    meshRadius * CAMERA_DISTANCE_RADIUS_MUL);
            orbitMouse.setTarget(meshCenter);
            orbitMouse.setOrbit(CAMERA_AZIMUTH, CAMERA_ELEVATION, orbitDistance);
        }

        Platforms.get().log(String.format(
                "[embedded-tmesh] source=%s nodes=%d arcs=%d patches=%d euler=%d",
                offPath == null ? "torus-fixture" : offPath, tmesh.nodes.size(),
                tmesh.arcs.size(), tmesh.patches.size(), eulerCharacteristic));
    }

    /**
     * Builds the T-mesh, its surface mesh, and its Euler characteristic: from the real pipeline
     * when {@link #OFF_PROPERTY} names a mesh, otherwise from the hand-authored torus fixture.
     * Also wires the operators for the interactive steps.
     */
    private void assembleLayout() {
        if (offPath == null) {
            TorusLayoutFixture fixture = new TorusLayoutFixture();
            tmesh = fixture.tmesh;
            surfaceMesh = fixture.torus;
            eulerCharacteristic = TorusLayoutFixture.TORUS_EULER_CHARACTERISTIC;
        } else {
            double alphaDegrees = Double.parseDouble(
                    System.getProperty(ALPHA_PROPERTY, Double.toString(DEFAULT_ALPHA_DEGREES)));
            ArrayMesh arrayMesh;
            try {
                arrayMesh = MeshLoader.load(offPath);
            } catch (IOException ex) {
                throw new IllegalStateException("could not read mesh " + offPath, ex);
            }
            surfaceMesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                    arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());
            QuadLayoutEngine engine = new QuadLayoutEngine(
                    surfaceMesh, (float) Math.toRadians(alphaDegrees));
            engine.buildLayoutEmbedding();
            EmbeddedTMeshBuilder builder = new EmbeddedTMeshBuilder(engine.embedding);
            tmesh = builder.build();
            eulerCharacteristic = builder.expectedEulerCharacteristic;
        }
        tmesh.validate(eulerCharacteristic);
        collapseOperator = new ZeroArcCollapseOperator(tmesh);
        splitOperator = new ZeroPatchSplitOperator(tmesh);
    }

    /**
     * Apply the number of zero-arc collapses requested at startup by
     * {@link #COLLAPSE_PROPERTY}: an integer, or {@code all}. This is the headless entry
     * point (a screenshot then shows that point in the collapse); once the window is open,
     * SPACE steps one more collapse and R rebuilds.
     */
    private void applyInitialCollapses() {
        String request = System.getProperty(COLLAPSE_PROPERTY);
        if (request == null || request.isBlank()) {
            return;
        }
        int limit = ALL.equalsIgnoreCase(request.trim())
                ? Integer.MAX_VALUE : Integer.parseInt(request.trim());
        int applied = 0;
        while (applied < limit && collapseOneZeroArc()) {
            applied++;
        }
        Platforms.get().log(APPLIED_PREFIX + applied + " zero-arc collapse(s)");
    }

    /**
     * Drive all three operators to a fixed point at startup when {@link #CONTRACT_PROPERTY} is
     * {@code all}, via {@link EmbeddedContraction}, so a headless screenshot shows the fully
     * re-embedded T-mesh. Runs after any requested splits and collapses.
     */
    private void applyInitialContraction() {
        String request = System.getProperty(CONTRACT_PROPERTY);
        if (request == null || !ALL.equalsIgnoreCase(request.trim())) {
            return;
        }
        EmbeddedContraction contraction =
                new EmbeddedContraction(tmesh, eulerCharacteristic).contract();
        Platforms.get().log(APPLIED_PREFIX + contractionSummary(contraction));
    }

    /**
     * Frames the captured reroute failure: targets the midpoint of the pivot and survivor and
     * looks straight in along the surface normal there, close enough to see the wall.
     *
     * @param meshRadius the surface's radius, for the camera distance
     */
    private void aimAtFailure(float meshRadius) {
        Vector3f pivot = tmesh.topology.copy.vertexPosition(failure.pivotVertex, new Vector3f());
        Vector3f survivor = tmesh.topology.copy.vertexPosition(failure.survivorVertex,
                new Vector3f());
        Vector3f target = new Vector3f(pivot).add(survivor).mul(0.5f);
        Vector3f outward = new Vector3f(target).sub(meshCenter).normalize();
        float elevation = (float) Math.asin(outward.y);
        float azimuth = (float) Math.atan2(outward.z, outward.x);
        float distance = Math.max(CAMERA_DISTANCE_MIN, meshRadius * FAILURE_VIEW_DISTANCE_MUL);
        orbitMouse.setTarget(target);
        orbitMouse.setOrbit(azimuth, elevation, distance);
    }

    /**
     * When {@link #CONTRACT_FAIL_PROPERTY} is set, drive the operators until the first reroute
     * failure and, if one occurs, refresh the (partial) T-mesh render and highlight the wall.
     * Runs after the runtime is up.
     *
     * <p>This touches no runtime state; the caller hands the result over afterwards. Handing a
     * layout to the runtime builds its patch regions, and a layout that still holds zero-patches —
     * cells embedded onto a point or a curve, enclosing no faces — has no region for them to
     * correspond to, so that throws. Doing it before the contraction had reported discarded the one
     * line saying how the contraction actually ended, and made a reroute failure look like a torn
     * layout.
     */
    private void applyContractToFailure() {
        if (!Boolean.parseBoolean(System.getProperty(CONTRACT_FAIL_PROPERTY, "false"))) {
            return;
        }
        EmbeddedContraction contraction = new EmbeddedContraction(tmesh, eulerCharacteristic);
        failure = contraction.contractToFailure();
        if (failure == null) {
            Platforms.get().log("[embedded-tmesh] contracted to a fixed point with no failure: "
                    + contractionSummary(contraction));
        } else {
            Platforms.get().log("[embedded-tmesh] stopped at reroute failure after "
                    + contractionSummary(contraction) + " | fenceVertices="
                    + failure.fenceVertices.size() + " pivotSpokes="
                    + (failure.pivotSpokes.size() / 2) + " | " + failure.getMessage());
        }
    }

    /**
     * A one-line summary of how many of each operator a contraction applied.
     *
     * @param contraction a finished contraction
     * @return "{@code N collapse(s), M split(s), K patch-collapse(s)}"
     */
    private static String contractionSummary(EmbeddedContraction contraction) {
        return contraction.arcCollapseCount + " collapse(s), "
                + contraction.patchSplitCount + " split(s), "
                + contraction.patchCollapseCount + " patch-collapse(s)";
    }

    /**
     * Apply the number of non-simple zero-patch splits requested at startup by
     * {@code -Dtmesh.split} (an integer, or {@code all}), before any collapses, so a headless
     * screenshot can show operator (2)'s effect. In the window, PERIOD steps one split.
     */
    private void applyInitialSplits() {
        String request = System.getProperty(SPLIT_PROPERTY);
        if (request == null || request.isBlank()) {
            return;
        }
        int limit = ALL.equalsIgnoreCase(request.trim())
                ? Integer.MAX_VALUE : Integer.parseInt(request.trim());
        int applied = 0;
        while (applied < limit) {
            int patchId = splitOperator.nextNonSimpleZeroPatch();
            if (patchId == EmbeddedTMesh.NONE) {
                break;
            }
            splitOperator.split(patchId);
            tmesh.validate(eulerCharacteristic);
            applied++;
        }
        Platforms.get().log(APPLIED_PREFIX + applied + " zero-patch split(s)");
    }

    /**
     * Collapse the next collapsible zero arc, validating the result, and report whether one
     * was found.
     *
     * @return true when an arc was collapsed, false when none remains
     */
    private boolean collapseOneZeroArc() {
        int arcId = collapseOperator.nextCollapsibleArc();
        if (arcId == EmbeddedTMesh.NONE) {
            return false;
        }
        collapseOperator.collapse(arcId);
        tmesh.validate(eulerCharacteristic);
        return true;
    }

    /**
     * Ask for one more zero-arc collapse; applied on the next frame, on the render thread.
     */
    public void requestCollapseStep() {
        pendingCollapseSteps++;
    }

    /**
     * Ask for one more non-simple zero-patch split; applied on the next frame, on the render
     * thread.
     */
    public void requestSplitStep() {
        pendingSplitSteps++;
    }

    /**
     * Ask to rebuild the layout; applied on the next frame, on the render thread.
     */
    public void requestReset() {
        pendingReset = true;
    }

    /**
     * Ask to drive all three operators to a fixed point; applied on the next frame, on the
     * render thread.
     */
    public void requestFullContraction() {
        pendingContract = true;
    }

    /**
     * Apply any keypress-requested edit on the render thread, where the GL context is current,
     * and re-upload the changed T-mesh. Doing this here rather than in the key callback keeps
     * every GL call on the thread that owns the context.
     */
    private void applyPendingEdits() {
        if (pendingReset) {
            pendingReset = false;
            pendingCollapseSteps = 0;
            pendingSplitSteps = 0;
            assembleLayout();
            runtime.setEmbeddedTMesh(tmesh);
            Platforms.get().log("[embedded-tmesh] rebuilt");
            return;
        }
        if (pendingContract) {
            pendingContract = false;
            EmbeddedContraction contraction =
                    new EmbeddedContraction(tmesh, eulerCharacteristic).contract();
            runtime.setEmbeddedTMesh(tmesh);
            Platforms.get().log("[embedded-tmesh] contracted to fixed point: "
                    + contractionSummary(contraction) + "; arcs=" + countLiveArcs());
            return;
        }
        boolean changed = false;
        while (pendingSplitSteps > 0) {
            pendingSplitSteps--;
            int patchId = splitOperator.nextNonSimpleZeroPatch();
            if (patchId == EmbeddedTMesh.NONE) {
                Platforms.get().log("[embedded-tmesh] no non-simple zero-patch remains");
                break;
            }
            splitOperator.split(patchId);
            tmesh.validate(eulerCharacteristic);
            changed = true;
        }
        while (pendingCollapseSteps > 0) {
            pendingCollapseSteps--;
            if (collapseOneZeroArc()) {
                changed = true;
            } else {
                Platforms.get().log("[embedded-tmesh] no collapsible zero arc remains");
                break;
            }
        }
        if (changed) {
            runtime.setEmbeddedTMesh(tmesh);
            Platforms.get().log("[embedded-tmesh] collapsed " + collapseOperator.collapsedCount
                    + " total; arcs=" + countLiveArcs());
        }
    }

    /**
     * The number of live arcs in the T-mesh, for the status log.
     *
     * @return count of arcs still part of the layout
     */
    private int countLiveArcs() {
        int count = 0;
        for (int arcId = 0; arcId < tmesh.arcs.size(); arcId++) {
            if (tmesh.arcs.get(arcId).alive) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void drawScene() {
        if (runtime == null) {
            return;
        }
        applyPendingEdits();
        camera.resetView();
        runtime.render(camera);
        runtime.renderOverlays(camera);
        runtime.renderHighlights(camera);
    }

    /**
     * Toggle the reroute-failure highlight, applied on the render thread.
     */
    public void toggleFailureHighlight() {
        if (runtime != null) {
            runtime.showFailureHighlight = !runtime.showFailureHighlight;
        }
    }

    @Override
    public void activate(boolean state) {
        super.activate(state);
        if (!state) {
            disposeRuntime();
        }
    }

    @Override
    public void shutdown() {
        disposeRuntime();
        super.shutdown();
    }

    private void disposeRuntime() {
        if (runtime != null) {
            runtime.dispose();
            runtime = null;
        }
    }

    /**
     * Route raw platform input to the scene's key and mouse handlers.
     *
     * @param platform  active platform
     * @param keyGuy    key handler
     * @param mouseTrap mouse handler
     */
    private static void bindInputDirect(Platform platform, KeyGuy keyGuy, MouseTrap mouseTrap) {
        platform.setCursorPosCallback(
                (window, x, y) -> mouseTrap.moveOrDrag(window, (float) x, (float) y));
        platform.setMouseButtonCallback(
                (button, action, mods) -> mouseTrap.mouseButton(button, action, mods));
        platform.setScrollCallback((xoff, yoff) -> mouseTrap.scrollCallback(yoff));
        platform.setKeyCallback(
                (key, scancode, action, mods) -> keyGuy.keyCallback(0L, key, scancode, action, mods));
    }
}
