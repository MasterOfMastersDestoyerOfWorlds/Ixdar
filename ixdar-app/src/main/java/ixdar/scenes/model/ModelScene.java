package ixdar.scenes.model;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.nodegraph.runtime.NodeGroup;
import ixdar.nodegraph.nodes.data.SceneModelNode;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorBox;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.gui.terminal.Terminal;
import ixdar.gui.ui.menu.model.SceneModelMenu;
import ixdar.models.CollectionManifest;
import ixdar.models.GraphChoice;
import ixdar.models.ModelCatalog;
import ixdar.models.ModelChoice;
import ixdar.models.ModelCollection;
import ixdar.models.ModelLoadLedger;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.Scene;
import ixdar.tools.ControlHint;
import ixdar.tools.Tool;
import ixdar.tools.graph.DslGraphView;
import ixdar.tools.orbit.OrbitTool;

/**
 * Base for scenes that view a switchable model: owns the ESC model dropdown,
 * the shared orbit input, and the load-a-model plumbing, with {@code initGL}
 * built from overridable hooks. {@link #requestModelLoad(String)} only records
 * the switch — {@link #applyPendingModel} applies it on the render thread,
 * never in the key/mouse callback.
 */
public abstract class ModelScene extends Scene {

    public static final String COMMON_MODEL_PROPERTY = "ixdar.model";

    public static final String DEFAULT_OFF = "test/resources/quadlayout/figure_8/botijo_in_tri.off";

    public static final String GRAPH_PREFIX = "graph:";

    public static final String VIEW_SCENE_MENU = "SCENE_MENU";

    public static final int MENU_PANEL_WIDTH = 420;

    public static final float ZOOM_MIN_RADIUS_FRACTION = 0.01f;

    public static final float ZOOM_MAX_RADIUS_MUL = 5.0f;

    public static final float ZOOM_MIN_FLOOR = 0.0001f;

    public static final float CAMERA_AZIMUTH = (float) Math.toRadians(45.0);
    public static final float CAMERA_ELEVATION = (float) Math.toRadians(24.0);
    public static final float CAMERA_DISTANCE_MIN = 1.5f;
    public static final float CAMERA_DISTANCE_RADIUS_MUL = 2.5f;
    public static final float CAMERA_DISTANCE_DEFAULT = 3.5f;

    public static final float FOCUS_ORBIT_RADIUS_MUL = 3f;

    public static final float FOCUS_ORBIT_MIN_MUL = 0.5f;

    /** Model menu of this scene's models. */
    public SceneModelMenu sceneModelMenu;

    /** Authored graphs registered for the model menu, in registration order. */
    public final List<GraphChoice> graphs = new ArrayList<>();

    public ModelCatalog modelCatalog;

    /** Open collection when the scene was pointed at a directory of scans, else {@code null}. */
    public ModelCollection modelCollection;

    public String offPath;

    public HalfEdgeMesh halfEdgeMesh;

    public HalfEdgeMeshRuntime runtime;

    /** The tool the scene opens with and Esc returns to, which only moves the camera. */
    public final OrbitTool orbitTool = new OrbitTool(this);

    /** Tool drawing the node tree of {@link #graph} in place of the 3D view. */
    public DslGraphView graphTool;

    /**
     * Model path requested by the model menu or {@code model} command, applied on the
     * render thread.
     */
    public volatile String pendingModelPath;

    /** Serial and outcome of every requested model switch, read by automation waiting on one. */
    public final ModelLoadLedger modelLoads = new ModelLoadLedger();

    /**
     * The graph the scene ran last, its scene graph or a registered graph, whose node tree
     * node-inspect and the timing route read; {@code null} before any graph ran.
     */
    public NodeGraphRuntime graph;

    /**
     * Overrides by path ({@code layout/field.curvature_bias}) the scene applies to every graph it
     * runs; they survive model switches and are set through {@code ixdar-cli model --set} or the
     * terminal's {@code set}.
     */
    public final Map<String, Object> overrides = new LinkedHashMap<>();

    /** Center of the loaded mesh, used as the orbit target. */
    public final Vector3f meshCenter = new Vector3f();

    /** Azimuth the camera returns to when a model is framed. Scenes with a preferred view set it. */
    public float orbitAzimuth = CAMERA_AZIMUTH;

    /** Elevation the camera returns to when a model is framed. Scenes with a preferred view set it. */
    public float orbitElevation = CAMERA_ELEVATION;

    /**
     * Distance {@link #frameMesh} last framed the model at, the default until a model is framed;
     * {@link #preserveOrbit} keeps the zoom as a multiple of it, so a model of another size is
     * framed rather than viewed from the last model's distance.
     */
    public float framedDistance = CAMERA_DISTANCE_DEFAULT;

    private ModelChoice currentChoice;

    /**
     * Host the orbit tool as the default, and the DSL graph tool on ctrl+G, which toggles back to
     * the default tool; scenes add their own tools after these.
     */
    @Override
    public void setTools() {
        super.setTools();
        tools.add(orbitTool);
        defaultTool = orbitTool;
        graphTool = new DslGraphView(this);
        addToolSwitch(graphTool, new ControlHint(Keys.G, true, "ctrl+G", "the DSL's node graph and run times",
                () -> switchTool(activeTool == graphTool ? defaultTool : graphTool)));
    }

    @Override
    public void initGL() {
        super.initGL();
        Platforms.gl().setWindowTitle(windowTitle());

        initInput();
        createCatalog();
        sceneTerminal = new Terminal(terminalRoot(), this);
        Terminal.current = sceneTerminal;
        sceneModelMenu = new SceneModelMenu(this);
        initPanes();
        refreshControls();
        initModel();
    }

    /**
     * Hand the mouse to the camera's orbit, aimed at the mesh centre, and bind the input.
     */
    public void initInput() {
        mouse.controller = camera;
        camera.setTarget(meshCenter);
        camera.setOrbit(orbitAzimuth, orbitElevation, CAMERA_DISTANCE_DEFAULT);
        bindAutomationIfAvailable(Platforms.get());
        bindInputDirect(Platforms.get(), keys, mouse);
    }

    /**
     * Create the runtime and load the initial model, reporting a load failure instead of
     * propagating it: an unreadable initial model leaves an empty view the model menu can load
     * another model into. Scenes with async or multi-runtime loading override this.
     */
    public void initModel() {
        runtime = createRuntime();
        offPath = resolveInitialModel();
        try {
            loadModelOrGraph(offPath);
        } catch (Exception ex) {
            reportFailedLoad(offPath, ex);
        }
    }

    /**
     * Add the model menu's strip to the scene's views, so the menu is drawn into it.
     */
    @Override
    public void initPanes() {
        super.initPanes();
        chromeBackground = new ColorBox();
        webViews.put(VIEW_SCENE_MENU, sceneModelMenu.strip);
    }

    /**
     * Per-frame: apply a pending model switch, run the scene's 3D pass, or the active tool's view
     * when it replaces that pass, then the UI chrome.
     */
    @Override
    public void drawScene() {
        if (runtime == null) {
            return;
        }
        applyPendingModel();
        updateScene();
        if (activeTool.replacesView()) {
            activeTool.drawView();
        } else {
            renderScene();
        }
        super.drawScene();
    }

    /**
     * Per-frame work run after a pending model switch and before the 3D pass: one frame of every
     * hosted tool. Scenes with more call {@code super.updateScene()} first.
     */
    public void updateScene() {
        for (Tool tool : tools) {
            tool.perFrame();
        }
    }

    /**
     * Draw the base chrome (terminal), the scene's overlay text unless the active tool's view
     * covers the 3D view it labels, then the ESC model menu when visible.
     */
    @Override
    public void drawUI() {
        super.drawUI();
        if (!activeTool.replacesView()) {
            drawSceneOverlayText();
        }
        if (sceneModelMenu != null && sceneModelMenu.isVisible()) {
            camera2D.updateView(VIEW_SCENE_MENU);
            chromeBackground.draw(Color.GREY_VERY_DARK, camera2D);
            sceneModelMenu.draw(camera2D);
            camera2D.resetView();
        }
    }

    /**
     * Scene-specific 2D overlay text, drawn over the 3D view and under the ESC menu. The base
     * scene draws none.
     */
    public void drawSceneOverlayText() {
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
     * through {@link #availableModels()}, registered graphs included, else
     * {@link #DEFAULT_OFF}. Scenes with a different default override this.
     *
     * @return the initial model path or {@code graph:} token
     */
    public String resolveInitialModel() {
        String common = System.getProperty(COMMON_MODEL_PROPERTY);
        if (common != null && !common.isBlank()) {
            ModelChoice match = ModelCatalog.resolve(availableModels(), common);
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
     * Execute a registered graph fresh as {@link #graph}, the scene's overrides replacing the
     * choice's key by key. Scenes showing its product override this and call it first.
     *
     * @param choice registered graph to execute
     * @throws IllegalStateException when an override is invalid or a node fails
     * @return the executed runtime, for the override to read outputs from
     */
    public NodeGraphRuntime loadGraph(GraphChoice choice) {
        Map<String, Object> merged = new LinkedHashMap<>(choice.overrides);
        merged.putAll(overrides);
        NodeGraphRuntime run;
        try {
            run = NodeGraphRuntime.fromResource(choice.dslPath);
            graph = run;
            run.executeGraphResult(run.lastId(), NodeGraphRuntime.RESULT, merged);
        } catch (Exception failure) {
            throw new IllegalStateException("graph " + choice.dslPath + " failed", failure);
        }
        offPath = GRAPH_PREFIX + choice.displayName;
        return run;
    }

    /**
     * Graph a mesh-file model runs through, built in Java, whose {@code scene_model()} node is fed
     * the loaded model; {@code null} for a scene that runs no graph over its model.
     *
     * @return the scene graph, or {@code null}
     */
    public NodeGroup sceneGraph() {
        return null;
    }

    /**
     * Starts a run of the scene graph over {@link #halfEdgeMesh} with the scene's overrides and
     * makes it {@link #graph}. Nothing evaluates until the scene reads an output through
     * {@link #sceneOutput}, which runs only what that output depends on.
     *
     * @throws IllegalArgumentException when an override names no path or port of the graph
     * @return the prepared run
     */
    public NodeGraphRuntime runSceneGraph() {
        NodeGraphRuntime run = new NodeGraphRuntime(sceneGraph());
        run.graphInputs.put(SceneModelNode.BINDING, GeometryBundle.ofMesh(halfEdgeMesh));
        graph = run;
        run.setOverrides(overrides);
        return run;
    }

    /**
     * An output of the current {@link #graph}, evaluating only what it depends on.
     *
     * @param reference {@code path.port}, e.g. {@code layout.seamless}
     * @throws IllegalStateException when no graph ran or a node fails with a checked exception
     * @return the output's value
     */
    public Object sceneOutput(String reference) {
        if (graph == null) {
            throw new IllegalStateException("no scene graph has run");
        }
        try {
            return graph.pull(reference);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(reference + " failed", failure);
        }
    }

    /**
     * Sets, or with a {@code null} value clears, a scene override by path. The key is checked
     * against the graph the given model runs, so a typo fails here and not on the next load.
     *
     * @param key       override key, e.g. {@code layout/field.curvature_bias}
     * @param value     the port's value, or {@code null} to clear the key
     * @param modelPath loader token of the model the override is meant for
     * @throws IllegalArgumentException when the key names no path or port of that graph
     */
    public void setOverride(String key, Object value, String modelPath) {
        if (value == null) {
            overrides.remove(key);
            return;
        }
        graphFor(modelPath).validateOverrideKey(key);
        overrides.put(key, value);
    }

    /**
     * The parsed graph a model runs: its registered graph for a {@link #GRAPH_PREFIX} token, else
     * the scene graph.
     *
     * @param modelPath loader token, or {@code null} for the current model
     * @throws IllegalArgumentException when the model runs no graph
     * @return the parsed graph, not executed
     */
    public NodeGraphRuntime graphFor(String modelPath) {
        String path = modelPath != null ? modelPath : offPath;
        if (path != null && path.startsWith(GRAPH_PREFIX)) {
            for (GraphChoice choice : graphs) {
                if (path.equals(GRAPH_PREFIX + choice.displayName)) {
                    return NodeGraphRuntime.fromResource(choice.dslPath);
                }
            }
        }
        if (sceneGraph() != null) {
            return new NodeGraphRuntime(sceneGraph());
        }
        if (graph != null) {
            return graph;
        }
        throw new IllegalArgumentException("this scene runs no graph over its model");
    }

    /**
     * Steps a float scene override, as a key control nudging a group parameter does.
     *
     * @param key      override key
     * @param delta    amount added to the current value
     * @param floor    smallest value the step may reach
     * @param fallback value before the first step, the parameter's default
     * @return the new value
     */
    public float stepOverride(String key, float delta, float floor, float fallback) {
        float current = overrides.get(key) instanceof Number number ? number.floatValue() : fallback;
        float next = Math.max(floor, current + delta);
        overrides.put(key, next);
        return next;
    }

    /**
     * Parses an override value the way the DSL parses a literal: a number is a float, true and
     * false are booleans, anything else is a string.
     *
     * @param text the value as typed
     * @return the literal
     */
    public static Object parseOverrideValue(String text) {
        String trimmed = text.trim();
        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            return Boolean.parseBoolean(trimmed);
        }
        try {
            return Float.parseFloat(trimmed);
        } catch (NumberFormatException notANumber) {
            return trimmed;
        }
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
        runtime.setSolidColor(Color.BLUE_VERY_DARK.toVector4f());
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
        camera.setTarget(meshCenter);
        float meshRadius = target != null ? target.radius() : 0f;
        camera.setDistanceBounds(
                Math.max(ZOOM_MIN_FLOOR, meshRadius * ZOOM_MIN_RADIUS_FRACTION),
                Math.max(CAMERA_DISTANCE_MIN, meshRadius * ZOOM_MAX_RADIUS_MUL));
        float orbitDist = target != null
                ? Math.max(CAMERA_DISTANCE_MIN, meshRadius * CAMERA_DISTANCE_RADIUS_MUL)
                : CAMERA_DISTANCE_DEFAULT;
        framedDistance = orbitDist;
        camera.setOrbit(orbitAzimuth, orbitElevation, orbitDist);
    }

    /**
     * Frame the active tool's view, or, for a tool over the 3D view, the whole model from where the
     * camera looks now: the orbit back on the centre at the distance {@link #frameMesh} chose.
     *
     * @return whether the active tool framed its own view
     */
    @Override
    public boolean frameAll() {
        boolean toolFramed = super.frameAll();
        if (!toolFramed) {
            camera.setTarget(meshCenter);
            camera.setOrbit(camera.azimuth, camera.elevation, framedDistance);
        }
        return toolFramed;
    }

    /**
     * Reload without yanking the camera. Saves the orbit orientation and the zoom relative to
     * {@link #framedDistance}, runs {@code reload}, and puts them back if it succeeded, so an
     * unzoomed view frames the new mesh whatever its size. The reload sets the target and bounds.
     *
     * @param reload work that replaces the displayed mesh, reporting whether it succeeded
     * @return whatever {@code reload} reported
     */
    public boolean preserveOrbit(BooleanSupplier reload) {
        float savedAzimuth = camera.azimuth;
        float savedElevation = camera.elevation;
        float savedZoom = camera.distance / framedDistance;
        boolean loaded = reload.getAsBoolean();
        if (loaded) {
            camera.setOrbit(savedAzimuth, savedElevation, savedZoom * framedDistance);
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
        camera.setTarget(centroid);
        camera.setDistanceBounds(radius * FOCUS_ORBIT_MIN_MUL,
                Math.max(CAMERA_DISTANCE_MIN, halfEdgeMesh.radius() * CAMERA_DISTANCE_RADIUS_MUL));
        camera.setOrbit(orbitAzimuth, orbitElevation, radius * FOCUS_ORBIT_RADIUS_MUL);
        return radius;
    }

    /**
     * Apply a model switch requested since the last frame, on the render thread, reporting a
     * failure rather than propagating it, and record the outcome in {@link #modelLoads}.
     */
    public void applyPendingModel() {
        if (pendingModelPath == null) {
            return;
        }
        String path = pendingModelPath;
        int serial = modelLoads.requestedSerial;
        pendingModelPath = null;
        long started = System.nanoTime();
        Exception failure = null;
        try {
            loadModelOrGraph(path);
            Platforms.get().log(" loaded " + path);
        } catch (Exception ex) {
            failure = ex;
            reportFailedLoad(path, ex);
        }
        modelLoads.finish(serial, failure, started);
    }

    /**
     * Log a failed model load with the whole cause chain and its frames: the throwing
     * class and line are the only thing that tells a stale-state failure apart from an
     * unreadable file.
     *
     * @param path the model path that could not be loaded
     * @param ex   the failure
     */
    public void reportFailedLoad(String path, Exception ex) {
        Platforms.get().log(" failed to load " + path + ": " + ex);
        for (Throwable level = ex; level != null; level = level.getCause()) {
            if (level != ex) {
                Platforms.get().log(" caused by: " + level);
            }
            for (StackTraceElement frame : level.getStackTrace()) {
                Platforms.get().log("     at " + frame);
            }
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
     * frame. Render thread only; the switch's serial is {@link ModelLoadLedger#requestedSerial}.
     *
     * @param path loader argument (see {@link ModelChoice#path}) of the model to
     *             load
     */
    public void requestModelLoad(String path) {
        pendingModelPath = path;
        modelLoads.request();
    }

    /**
     * Resolve {@code token} against {@link #availableModels()} (exact display name first, then a
     * case-insensitive substring of display name or path) and request that model's load.
     *
     * @param token model display name, part of one, or a path
     * @return the model whose load was requested, or {@code null} when nothing matches
     */
    public ModelChoice requestModel(String token) {
        ModelChoice match = ModelCatalog.resolve(availableModels(), token);
        if (match != null) {
            requestModelLoad(match.path);
        }
        return match;
    }

    /**
     * Open a directory of scans as the scene's collection: its members replace the model catalog
     * and its manifest is (re)written, so membership is recorded before anything is merged.
     *
     * @param directory choice naming the directory of scans
     */
    public void openCollection(ModelChoice directory) {
        modelCollection = directory.openCollection();
        modelCatalog = ModelCatalog.ofCollection(modelCollection);
        writeCollectionManifest();
    }

    /**
     * Persist the open collection's membership and keep flags. A write failure is logged rather
     * than thrown: a read-only scan directory must not take the scene down mid-frame.
     */
    public void writeCollectionManifest() {
        if (modelCollection == null) {
            return;
        }
        try {
            CollectionManifest.write(modelCollection);
        } catch (IOException ex) {
            Platforms.get().log(" collection manifest write failed for "
                    + modelCollection.manifestPath + ": " + ex.getMessage());
        }
    }

    /**
     * The collection member under the cursor.
     *
     * @return the current member index, or {@code -1} when no collection is open or it is empty
     */
    public int currentMemberIndex() {
        return modelCollection == null ? -1 : modelCollection.index();
    }

    /**
     * Move the collection cursor onto the member that was just loaded and record its size, so the
     * menu can show counts without re-parsing.
     *
     * @param memberPath absolute path of the mesh that was loaded
     * @param vertexCount vertices of the parsed mesh
     * @param triangleCount triangles of the parsed mesh
     */
    public void noteMemberLoaded(String memberPath, int vertexCount, int triangleCount) {
        if (modelCollection == null) {
            return;
        }
        int member = modelCollection.selectPath(memberPath);
        if (member >= 0) {
            modelCollection.memberVertexCount[member] = vertexCount;
            modelCollection.memberTriangleCount[member] = triangleCount;
        }
    }

    /**
     * Step the collection cursor forward and request that member.
     */
    public void nextMember() {
        loadMember(modelCollection == null ? -1 : modelCollection.next());
    }

    /**
     * Step the collection cursor back and request that member.
     */
    public void prevMember() {
        loadMember(modelCollection == null ? -1 : modelCollection.prev());
    }

    /**
     * Flip the current member between kept and rejected and rewrite the manifest.
     */
    public void toggleKeepCurrentMember() {
        int member = currentMemberIndex();
        if (member < 0) {
            return;
        }
        modelCollection.memberKeep[member] = !modelCollection.memberKeep[member];
        writeCollectionManifest();
        Platforms.get().log(" " + modelCollection.memberNames[member]
                + (modelCollection.memberKeep[member] ? " kept" : " rejected"));
    }

    /**
     * Request a collection member, ignoring {@code -1} so callers need no empty-collection check.
     *
     * @param member member index to load, or {@code -1} to do nothing
     */
    public void loadMember(int member) {
        if (member >= 0 && modelCollection != null) {
            requestModelLoad(modelCollection.memberPaths[member]);
        }
    }

    /**
     * Escape closes an open model menu, else goes to the active tool, else returns to the default
     * tool.
     */
    public void escapePressed() {
        if (sceneModelMenu != null && sceneModelMenu.isVisible()) {
            sceneModelMenu.toggle();
        } else if (!activeTool.escapePressed()) {
            switchTool(defaultTool);
        }
    }

    /**
     * The Escape row's text in the controls: close the menu, else the active tool's Esc, else the
     * default tool.
     *
     * @return the description
     */
    public String escapeDescription() {
        String toolEscape = activeTool.escapeDescription();
        return "close menu, else " + (toolEscape.isEmpty() ? "" : toolEscape + ", else ")
                + toolLabel(defaultTool);
    }

    /**
     * The runtime the surface is actually drawn through, which the face-id pick buffer and any
     * overlay live on. Scenes that swap runtimes on a model change override this.
     *
     * @return the live surface runtime, or {@code null} before one is created
     */
    public HalfEdgeMeshRuntime surfaceRuntime() {
        return runtime;
    }

    /**
     * Whether the input layer is moving the camera: the scene's trap reports a camera drag, held
     * still or moving, or a wheel zoom still settling.
     *
     * @return true from a drag's first move until its release, and through a zoom and its settle
     */
    public boolean cameraMoving() {
        return mouse != null && (mouse.isCameraDragging() || mouse.isZooming());
    }

    /**
     * The scene's key controls, shown as the ctrl+I menu's Controls section.
     *
     * @return the control list
     */
    public List<ControlHint> controls() {
        return controls;
    }

    /**
     * Populate {@link #controls}. Base adds the orbit/scroll rows while the mouse orbits the camera,
     * Ctrl+R to re-centre the orbit unless a tool binds it, Ctrl+I for the menu and Esc to close
     * it; scenes call {@code super.setControls()} then add their keyed hints.
     */
    @Override
    public void setControls() {
        if (mouse.controller == camera) {
            controls.add(new ControlHint("drag", "orbit the camera"));
            controls.add(new ControlHint("scroll", "zoom"));
        }
        boolean controlRBound = false;
        for (ControlHint hint : controls) {
            controlRBound |= hint.keyCode == Keys.R && hint.controlHeld && !hint.shiftHeld;
        }
        if (!controlRBound) {
            controls.add(new ControlHint(Keys.R, true, "ctrl+R", "re-centre the orbit on the model",
                    camera::resetTarget));
        }
        controls.add(new ControlHint(Keys.I, true, "ctrl+I", "menu", () -> sceneModelMenu.toggle()));
        controls.add(new ControlHint(Keys.ESCAPE, "esc", escapeDescription(), this::escapePressed));
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
