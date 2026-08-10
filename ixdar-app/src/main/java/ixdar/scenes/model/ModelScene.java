package ixdar.scenes.model;

import java.io.IOException;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorBox;
import ixdar.graphics.render.color.ColorRGB;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.gui.terminal.Terminal;
import ixdar.gui.ui.menu.SceneModelMenu;
import ixdar.platform.Platforms;
import ixdar.platform.gl.Platform;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.Keys;
import ixdar.platform.input.MouseTrap;
import ixdar.platform.input.OrbitCameraKeyGuy;
import ixdar.platform.input.OrbitMouseTrap;
import ixdar.scenes.Scene;

/**
 * Base for scenes that view a switchable model: owns the ESC model dropdown,
 * the shared orbit input, and the load-a-model plumbing, with {@code initGL}
 * built from overridable hooks. {@link #requestModelLoad(String)} only records
 * the switch — {@link #applyPendingModel} applies it on the render thread,
 * never in the key/mouse callback.
 */
public abstract class ModelScene extends Scene {

    /**
     * System property, common to every model scene, naming the initial model (a
     * display-name token or a path). Takes precedence over a scene's own
     * {@code -D<scene>.off} property.
     */
    public static final String COMMON_MODEL_PROPERTY = "ixdar.model";

    public static final String DEFAULT_OFF = "test/resources/quadlayout/figure_8/botijo_in_tri.off";

    /** Named view for the right-side ESC menu strip. */
    public static final String VIEW_SCENE_MENU = "SCENE_MENU";

    /** Width in pixels of the right-side menu strip. */
    public static final int MENU_PANEL_WIDTH = 420;

    /** Closest zoom: 1% of mesh radius. */
    public static final float ZOOM_MIN_RADIUS_FRACTION = 0.01f;

    /** Farthest zoom: 5× mesh radius. */
    public static final float ZOOM_MAX_RADIUS_MUL = 5.0f;

    /**
     * Floor on the closest-zoom value so degenerate meshes never collapse to zero.
     */
    public static final float ZOOM_MIN_FLOOR = 0.0001f;

    public static final float CAMERA_AZIMUTH = (float) Math.toRadians(45.0);
    public static final float CAMERA_ELEVATION = (float) Math.toRadians(24.0);
    public static final float CAMERA_DISTANCE_MIN = 1.5f;
    public static final float CAMERA_DISTANCE_RADIUS_MUL = 2.5f;
    public static final float CAMERA_DISTANCE_DEFAULT = 3.5f;

    /** ESC menu of this scene's models. */
    public SceneModelMenu sceneModelMenu;

    public LayoutModelCatalog modelCatalog;

    public String offPath;

    public HalfEdgeMesh halfEdgeMesh;

    public HalfEdgeMeshRuntime runtime;

    public OrbitMouseTrap orbitMouse;

    public OrbitCameraKeyGuy keyGuy;

    private ModelChoice currentChoice;

    /**
     * Model path requested by the ESC menu or {@code model} command, applied on the
     * render thread.
     */
    private volatile String pendingModelPath;

    private final Vector3f meshCenter = new Vector3f();

    @Override
    public void initGL() {
        super.initGL();
        Platforms.gl().setWindowTitle(windowTitle());

        initInput();
        createCatalog();
        sceneTerminal = new Terminal(terminalRoot());
        sceneTerminal.modelScene = this;
        Terminal.current = sceneTerminal;
        sceneModelMenu = new SceneModelMenu(this);
        initPanes();
        initModel();
    }

    /**
     * Wire the orbit mouse and the controls-driven key handler. Scenes needing a
     * richer key handler (modified keys, extra bindings) override this.
     */
    public void initInput() {
        orbitMouse = new OrbitMouseTrap(camera, this);
        keyGuy = new OrbitCameraKeyGuy(orbitMouse, camera, this, controls);
        keys = keyGuy;
        mouse = orbitMouse;
        orbitMouse.setTarget(meshCenter);
        orbitMouse.setOrbit(CAMERA_AZIMUTH, CAMERA_ELEVATION, CAMERA_DISTANCE_DEFAULT);
        bindAutomationIfAvailable(Platforms.get(), keys, mouse);
        bindInputDirect(Platforms.get(), keys, mouse);
    }

    /**
     * Create the runtime and load the initial model. Scenes with async or
     * multi-runtime loading override this.
     *
     * @throws IllegalStateException if the initial model cannot be read
     */
    public void initModel() {
        runtime = createRuntime();
        offPath = resolveInitialModel();
        try {
            loadModel(offPath);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load initial model " + offPath, ex);
        }
    }

    @Override
    public void initPanes() {
        super.initPanes();
        chromeBackground = new ColorBox();
        webViews.put(VIEW_SCENE_MENU, new Bounds(
                Platforms.get().getFrameBufferWidth() - MENU_PANEL_WIDTH, 0,
                MENU_PANEL_WIDTH, Platforms.get().getFrameBufferHeight(),
                bounds -> bounds.update(Platforms.get().getFrameBufferWidth() - MENU_PANEL_WIDTH, 0,
                        MENU_PANEL_WIDTH, Platforms.get().getFrameBufferHeight()),
                VIEW_SCENE_MENU));
    }

    /**
     * Per-frame: apply a pending model switch, run the scene's 3D pass, then the UI
     * chrome.
     */
    @Override
    public void drawScene() {
        if (runtime == null) {
            return;
        }
        applyPendingModel();
        renderScene();
        super.drawScene();
    }

    /**
     * Draw the base chrome (terminal), then the ESC model menu when visible.
     */
    @Override
    public void drawUI() {
        super.drawUI();
        if (sceneModelMenu != null && sceneModelMenu.isVisible()) {
            camera2D.updateView(VIEW_SCENE_MENU);
            chromeBackground.draw(Color.DARK_GRAY, camera2D);
            sceneModelMenu.draw(camera2D);
            camera2D.resetView();
        }
    }

    /**
     * The scene's 3D render pass, invoked each frame after any pending model switch
     * is applied.
     */
    public abstract void renderScene();

    /**
     * Create the runtime this scene renders through, before the initial
     * {@link #loadModel}.
     *
     * @return the scene's mesh runtime
     */
    public abstract HalfEdgeMeshRuntime createRuntime();

    /**
     * Window title shown for this scene.
     *
     * @return the title string
     */
    public String windowTitle() {
        return "Ixdar";
    }

    /**
     * Build the model catalog. Base scans {@code test/resources/quadlayout}; scenes
     * with a different catalog override this and leave {@link #modelCatalog}
     * unused.
     */
    public void createCatalog() {
        modelCatalog = new LayoutModelCatalog();
    }

    /**
     * Working directory the scene terminal starts in.
     *
     * @return the terminal root path
     */
    public String terminalRoot() {
        return LayoutModelCatalog.QUADLAYOUT_DIR;
    }

    /**
     * Resolve the initial model: {@code -Dixdar.model} (display-name token or path)
     * through the catalog, else {@link #DEFAULT_OFF}. Scenes with a different
     * catalog override this.
     *
     * @return the initial model path
     */
    public String resolveInitialModel() {
        String common = System.getProperty(COMMON_MODEL_PROPERTY);
        if (common != null && !common.isBlank()) {
            ModelChoice match = modelCatalog.resolve(common);
            return match != null ? match.path : common;
        }
        return DEFAULT_OFF;
    }

    /**
     * Load {@code path}: build the surface mesh, upload it, and frame the orbit
     * camera. Scenes override to add their pipeline (calling
     * {@code super.loadModel}) or to load differently.
     *
     * @param path mesh file path to load
     * @throws IOException if the mesh file cannot be read
     */
    public void loadModel(String path) throws IOException {
        offPath = path;
        ArrayMesh arrayMesh = MeshLoader.load(path);
        halfEdgeMesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());
        runtime.upload(halfEdgeMesh);
        runtime.setSolidColor(ColorRGB.BLUE_GRAY.toVector4f());
        runtime.frameCamera(camera);
        meshCenter.set(halfEdgeMesh.center(new Vector3f()));
        float meshRadius = halfEdgeMesh.radius();
        float minZoom = Math.max(ZOOM_MIN_FLOOR, meshRadius * ZOOM_MIN_RADIUS_FRACTION);
        float maxZoom = Math.max(CAMERA_DISTANCE_MIN, meshRadius * ZOOM_MAX_RADIUS_MUL);
        orbitMouse.setDistanceBounds(minZoom, maxZoom);
        float orbitDist = Math.max(CAMERA_DISTANCE_MIN, meshRadius * CAMERA_DISTANCE_RADIUS_MUL);
        orbitMouse.setTarget(meshCenter);
        orbitMouse.setOrbit(CAMERA_AZIMUTH, CAMERA_ELEVATION, orbitDist);
        updateCurrentChoice();
    }

    /**
     * Apply a model switch requested since the last frame, on the render thread.
     */
    public void applyPendingModel() {
        if (pendingModelPath == null) {
            return;
        }
        String path = pendingModelPath;
        pendingModelPath = null;
        try {
            loadModel(path);
            Platforms.get().log(" loaded " + path);
        } catch (Exception ex) {
            Platforms.get().log(" failed to load " + path + ": " + ex.getMessage());
        }
    }

    /**
     * Match {@link #offPath} against the catalog to set the highlighted current
     * model. Scenes that track the current model differently override
     * {@link #currentModel()} instead.
     */
    public void updateCurrentChoice() {
        currentChoice = null;
        for (ModelChoice choice : modelCatalog.choices()) {
            if (choice.path.equals(offPath)) {
                currentChoice = choice;
                return;
            }
        }
        currentChoice = new ModelChoice(offPath, offPath);
    }

    /**
     * Models this scene can switch between, in display order.
     *
     * @return the model list (never {@code null}; may be empty)
     */
    public List<ModelChoice> availableModels() {
        return modelCatalog.choices();
    }

    /**
     * The model currently loaded.
     *
     * @return the current choice, or {@code null} if none is loaded
     */
    public ModelChoice currentModel() {
        return currentChoice;
    }

    /**
     * Request that the scene load {@code path} and recompute, applied on the next
     * frame.
     *
     * @param path loader argument (see {@link ModelChoice#path}) of the model to
     *             load
     */
    public void requestModelLoad(String path) {
        pendingModelPath = path;
    }

    /**
     * The scene's key controls, shown as the ESC menu's Controls section.
     *
     * @return the control list
     */
    public List<ControlHint> controls() {
        return controls;
    }

    /**
     * Populate {@link #controls}. Base adds the orbit/scroll rows; scenes call
     * {@code super.setControls()} then add their keyed hints.
     */
    @Override
    public void setControls() {
        controls.add(new ControlHint("drag", "orbit the camera"));
        controls.add(new ControlHint("scroll", "zoom"));
        controls.add(
                new ControlHint(Keys.ESCAPE, "escape", "toggle the model scene menu", () -> sceneModelMenu.toggle()));
        super.setControls();
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
}
