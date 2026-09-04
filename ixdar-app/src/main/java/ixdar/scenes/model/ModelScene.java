package ixdar.scenes.model;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorBox;
import ixdar.graphics.render.color.ColorRGB;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.gui.terminal.Terminal;
import ixdar.gui.ui.menu.SceneModelMenu;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
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

    public static final String DEFAULT_OFF = "test/resources/quadlayout/figure_8/fertility_in_tri.off";

    /**
     * Prefix marking a {@link ModelChoice#path} as a registered graph's display name rather
     * than a mesh file path.
     */
    public static final String GRAPH_PREFIX = "graph:";

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

    /** Orbit distance of {@link #focusOrbitOn}, in framed-region radii. */
    public static final float FOCUS_ORBIT_RADIUS_MUL = 3f;

    /** Closest orbit approach of {@link #focusOrbitOn}, in framed-region radii. */
    public static final float FOCUS_ORBIT_MIN_MUL = 0.5f;

    /** Floats per point in a flat-xyz position array. */
    /** ESC menu of this scene's models. */
    public SceneModelMenu sceneModelMenu;

    /** Authored graphs registered for the model menu, in registration order. */
    public final List<GraphChoice> graphs = new ArrayList<>();

    public ModelCatalog modelCatalog;

    public String offPath;

    public HalfEdgeMesh halfEdgeMesh;

    public HalfEdgeMeshRuntime runtime;

    public OrbitMouseTrap orbitMouse;

    public OrbitCameraKeyGuy keyGuy;

    /**
     * Model path requested by the ESC menu or {@code model} command, applied on the
     * render thread.
     */
    public volatile String pendingModelPath;

    /** Center of the loaded mesh, used as the orbit target. */
    public final Vector3f meshCenter = new Vector3f();

    /** Azimuth the camera returns to when a model is framed. Scenes with a preferred view set it. */
    public float orbitAzimuth = CAMERA_AZIMUTH;

    /** Elevation the camera returns to when a model is framed. Scenes with a preferred view set it. */
    public float orbitElevation = CAMERA_ELEVATION;

    private ModelChoice currentChoice;

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
        orbitMouse.setOrbit(orbitAzimuth, orbitElevation, CAMERA_DISTANCE_DEFAULT);
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
            loadModelOrGraph(offPath);
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
        modelCatalog = ModelCatalog.quadLayout(Path.of(ModelCatalog.QUADLAYOUT_DIR));
    }

    /**
     * Working directory the scene terminal starts in.
     *
     * @return the terminal root path
     */
    public String terminalRoot() {
        return ModelCatalog.QUADLAYOUT_DIR;
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
        frameLoadedModel();
        updateCurrentChoice();
    }

    /**
     * Dispatch a loader token: a {@link #GRAPH_PREFIX} token loads the registered graph of
     * that display name through {@link #loadGraph}, anything else goes through
     * {@link #loadModel}. A graph token must never enter a scene's file pipeline, which is why
     * the split happens here and not inside {@code loadModel} overrides.
     *
     * @param path loader token: a mesh file path or {@code graph:<display name>}
     * @throws IOException if a mesh file cannot be read or no graph matches the token
     */
    public void loadModelOrGraph(String path) throws IOException {
        if (path != null && path.startsWith(GRAPH_PREFIX)) {
            String displayName = path.substring(GRAPH_PREFIX.length());
            for (GraphChoice choice : graphs) {
                if (choice.displayName.equals(displayName)) {
                    loadGraph(choice);
                    return;
                }
            }
            throw new IOException("no registered graph named " + displayName);
        }
        loadModel(path);
    }

    /**
     * Load a registered graph the way {@link #loadModel} loads a file: execute its .dsl fresh,
     * so reloading is resetting. The base knows only the choice's path and overrides; scenes
     * that show a graph's product override this, call {@code super.loadGraph} first, and hand
     * the outputs they understand to their runtime.
     *
     * @param choice registered graph to execute
     * @return the executed runtime, for the override to read outputs from
     */
    public NodeGraphRuntime loadGraph(GraphChoice choice) {
        NodeGraphRuntime graph = NodeGraphRuntime.executeResource(choice.dslPath, choice.overrides);
        offPath = GRAPH_PREFIX + choice.displayName;
        return graph;
    }

    /**
     * Registers an authored graph for the model menu; subclasses call this from their
     * constructor the way they add {@code ControlHint} rows.
     *
     * @param choice graph to list and load by display name
     */
    public void registerGraph(GraphChoice choice) {
        graphs.add(choice);
    }

    /**
     * Frame the orbit camera and zoom bounds around {@link #halfEdgeMesh}, shared by the file
     * and graph load paths.
     */
    public void frameLoadedModel() {
        runtime.setSolidColor(ColorRGB.BLUE_GRAY.toVector4f());
        runtime.frameCamera(camera);
        frameMesh(halfEdgeMesh);
    }

    /**
     * Centres the orbit on a mesh and pulls the camera back far enough to frame it, also setting
     * the zoom bounds. A null mesh resets the target to the origin and the default distance.
     *
     * @param target mesh to frame, or {@code null} to reset to the origin
     */
    public void frameMesh(MeshTopology target) {
        if (target != null) {
            meshCenter.set(target.center(new Vector3f()));
        } else {
            meshCenter.set(0f, 0f, 0f);
        }
        if (orbitMouse == null) {
            return;
        }
        orbitMouse.setTarget(meshCenter);
        float meshRadius = target != null ? target.radius() : 0f;
        orbitMouse.setDistanceBounds(
                Math.max(ZOOM_MIN_FLOOR, meshRadius * ZOOM_MIN_RADIUS_FRACTION),
                Math.max(CAMERA_DISTANCE_MIN, meshRadius * ZOOM_MAX_RADIUS_MUL));
        float orbitDist = target != null
                ? Math.max(CAMERA_DISTANCE_MIN, meshRadius * CAMERA_DISTANCE_RADIUS_MUL)
                : CAMERA_DISTANCE_DEFAULT;
        orbitMouse.setOrbit(orbitAzimuth, orbitElevation, orbitDist);
    }

    /**
     * Reload without yanking the camera. Saves the orbit orientation and zoom, runs {@code reload},
     * and puts them back if it succeeded. The reload sets the target and bounds from the new mesh.
     *
     * @param reload work that replaces the displayed mesh, reporting whether it succeeded
     * @return whatever {@code reload} reported
     */
    public boolean preserveOrbit(BooleanSupplier reload) {
        if (orbitMouse == null) {
            return reload.getAsBoolean();
        }
        float savedAzimuth = orbitMouse.getAzimuth();
        float savedElevation = orbitMouse.getElevation();
        float savedDistance = orbitMouse.getDistance();
        boolean loaded = reload.getAsBoolean();
        if (loaded) {
            orbitMouse.setOrbit(savedAzimuth, savedElevation, savedDistance);
        }
        return loaded;
    }

    /**
     * Re-centres the orbit on a group of dot clouds and pulls the camera in to frame them.
     *
     * @param clouds flat-xyz position arrays to frame together
     * @return the framed region's radius: the farthest point's distance from the centroid
     */
    public float focusOrbitOn(List<float[]> clouds) {
        Vector3f centroid = new Vector3f();
        float radius = HalfEdgeMeshRuntime.cloudRadius(clouds, centroid);
        orbitMouse.setTarget(centroid);
        orbitMouse.setDistanceBounds(radius * FOCUS_ORBIT_MIN_MUL,
                Math.max(CAMERA_DISTANCE_MIN, halfEdgeMesh.radius() * CAMERA_DISTANCE_RADIUS_MUL));
        orbitMouse.setOrbit(orbitAzimuth, orbitElevation, radius * FOCUS_ORBIT_RADIUS_MUL);
        return radius;
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
            loadModelOrGraph(path);
            Platforms.get().log(" loaded " + path);
        } catch (Exception ex) {
            Platforms.get().log(" failed to load " + path + ": " + ex.getMessage());
        }
    }

    /**
     * Match {@link #offPath} against the models list, graphs included, to set the highlighted
     * current model. Scenes that track the current model differently override
     * {@link #currentModel()} instead.
     */
    public void updateCurrentChoice() {
        currentChoice = null;
        for (ModelChoice choice : availableModels()) {
            if (choice.path.equals(offPath)) {
                currentChoice = choice;
                return;
            }
        }
        currentChoice = new ModelChoice(offPath, offPath);
    }

    /**
     * Models this scene can switch between, in display order: the catalog's files followed by
     * the registered graphs.
     *
     * @return the model list (never {@code null}; may be empty)
     */
    public List<ModelChoice> availableModels() {
        List<ModelChoice> choices = new ArrayList<>(modelCatalog.choices);
        for (GraphChoice choice : graphs) {
            choices.add(new ModelChoice(choice.displayName,
                    GRAPH_PREFIX + choice.displayName));
        }
        return choices;
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
