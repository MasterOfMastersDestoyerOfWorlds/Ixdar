package ixdar.scenes.mesh;

import java.io.IOException;
import java.util.HashMap;

import java.util.ArrayList;

import java.nio.file.Path;

import java.util.HashSet;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import java.util.Set;
import org.joml.Vector4f;

import ixdar.geometry.mesh.data.EdgeKey;
import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.data.FeatureEdgeColors;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.Patch;

import ixdar.geometry.mesh.data.MorseSmaleDecomposer;

import ixdar.geometry.mesh.data.MorseSmaleComplex;
import ixdar.geometry.mesh.data.PatchRenderer;
import ixdar.geometry.mesh.data.SemanticPatchDecomposer;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.load.ObjMeshParser;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.graphics.render.color.ColorRGB;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.parsing.python.PythonParser;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.input.OrbitMouseTrap;
import ixdar.scenes.model.ControlHint;
import ixdar.scenes.model.ModelCatalog;
import ixdar.scenes.model.ModelChoice;
import ixdar.scenes.model.ModelScene;

@SceneAnnotation(id = "mesh-viewer")
public class MeshNodeViewerScene extends ModelScene {
    public static final String PATCHES = "  patches=";
    public static final String DSL = ".dsl";
    public static final String STR = ": ";
    public static final String FAILED_TO_CREATE_MESH_GL_RUNTIME = "Failed to create mesh GL runtime";
    public static final String VERTS = " verts=";
    public static final String FACES = " faces=";
    public static final String STR_2 = ")";
    public static final float NUM_0 = 0f;
    public static final int NUM_3 = 3;
    public static final float NUM_0_6 = 0.6f;
    public static final float NUM_0_2 = 0.2f;
    public static final float NUM_0_35 = 0.35f;
    public static final float NUM_2 = 2f;
    public static final float NUM_1e_6 = 1e-6f;
    public static final int NUM_0x000000 = 0x000000;
    public static final int NUM_128 = 128;
    public static final int NUM_16 = 16;
    public static final int NUM_0xf = 0xff;
    public static final float NUM_255 = 255f;
    public static final int NUM_8 = 8;
    public static final float NUM_1 = 1f;
    private static final String DSL_FOLDER = "dsl";
    private static final String DEFAULT_DSL_RESOURCE = "skull.dsl";
    private static final String DEFAULT_DSL_FINAL_NODE = "";
    private static final String DEFAULT_DSL_FINAL_PORT = "geometry";

    /** Log prefix for this scene's timing lines. */
    private static final String TIMING_PREFIX = "[mesh-viewer]";

    private static final float HALF_EXTENT = 0.5f;

    private final String dslResource;
    private final String dslFinalNode;
    private final String dslFinalPort;

    /** When non-null, load this OBJ file instead of executing a DSL graph. */
    private final String objResource;

    private MeshTopology mesh;
    private volatile HalfEdgeMeshRuntime meshRuntime;
    private HalfEdgeMeshRuntime overlayRuntime;
    private NodeGraphRuntime lastGraphRuntime;

    // VIEW-7: catalog + per-mesh decomposition cache + overlay state
    private String currentModelKey; // absolutePath for staging-dir entries, or "" for initial load
    private String currentModelDisplayName = "(initial)";

    private SemanticPatchDecomposer.DecompositionDiagnostics cachedDiagnostics;
    private String cachedDiagnosticsKey;
    private boolean patchOverlayEnabled = false;
    private HalfEdgeMeshRuntime.ShaderMode shaderMode = HalfEdgeMeshRuntime.ShaderMode.LAMBERT;
    private DecomposerKind activeDecomposer = DecomposerKind.SEMANTIC;

    /**
     * Default constructor: pick the DSL resource, final node, and port from the
     * {@code ixdar.mesh.*} system properties, falling back to built-in defaults.
     */
    public MeshNodeViewerScene() {
        this(
                sysPropOrDefault("ixdar.mesh.dsl", DEFAULT_DSL_RESOURCE),
                sysPropOrDefault("ixdar.mesh.node", DEFAULT_DSL_FINAL_NODE),
                sysPropOrDefault("ixdar.mesh.port", DEFAULT_DSL_FINAL_PORT));
    }

    /**
     * Construct a viewer that will execute a DSL graph and display its mesh output.
     *
     * @param dslResource  DSL filename (with or without {@code .dsl} suffix)
     * @param dslFinalNode output node id, or empty for the last node in the graph
     * @param dslFinalPort output port name on the final node
     */
    public MeshNodeViewerScene(String dslResource, String dslFinalNode, String dslFinalPort) {
        this.dslResource = dslResource.endsWith(DSL) ? dslResource : dslResource + DSL;
        this.dslFinalNode = dslFinalNode;
        this.dslFinalPort = dslFinalPort;
        this.objResource = null;
    }

    private MeshNodeViewerScene(String objFilename, boolean objMode) {
        this.dslResource = null;
        this.dslFinalNode = null;
        this.dslFinalPort = null;
        this.objResource = objFilename;
    }

    /**
     * Log a full state string to the terminal whenever the viewer state changes.
     *
     * <p>
     * State must not be surfaced through the window title instead: macOS rejects
     * {@code glfwSetWindowTitle} off the OS main thread, and {@code drawScene} runs
     * off-thread in this platform wiring.
     */
    private void logState() {
        StringBuilder sb = new StringBuilder("[mesh-viewer] STATE ");
        sb.append("model=").append(currentModelDisplayName);
        sb.append(PATCHES).append(patchOverlayEnabled ? "ON" : "OFF");
        if (patchOverlayEnabled) {
            sb.append("  mode=").append(shaderMode.name());
            sb.append("  decomposer=").append(activeDecomposer.name());
            if (cachedDiagnostics != null) {
                sb.append(PATCHES).append(cachedDiagnostics.decomposition().patches().size());
            }
        }
        if (modelCatalog != null && !modelCatalog.choices.isEmpty()) {
            sb.append("  [").append(modelCatalog.index() + 1)
                    .append('/').append(modelCatalog.choices.size()).append(']');
        }
        Platforms.get().log(sb.toString());
    }

    /**
     * Returns the NodeGraphRuntime from the most recent DSL execution (for timing
     * data).
     *
     * @return last runtime, or {@code null} if no DSL has been executed yet
     */
    public NodeGraphRuntime getLastGraphRuntime() {
        return lastGraphRuntime;
    }

    /**
     * Orbit-camera input handler driving the 3D view.
     *
     * @return the orbit mouse trap, or {@code null} before {@link #initGL()} runs
     */
    public OrbitMouseTrap getOrbitMouse() {
        return orbitMouse;
    }

    private static String sysPropOrDefault(String key, String fallback) {
        String v = System.getProperty(key);
        return (v != null && !v.isEmpty()) ? v : fallback;
    }

    /**
     * Create an OBJ viewer (no DSL execution, just loads and displays an OBJ file).
     *
     * @param objFilename OBJ resource filename to load on init
     * @return new viewer in OBJ mode
     */
    public static MeshNodeViewerScene forObj(String objFilename) {
        return new MeshNodeViewerScene(objFilename, true);
    }

    /**
     * Wire input handlers, scan the model catalog, and asynchronously load the
     * configured DSL graph (or OBJ file) into a {@link HalfEdgeMeshRuntime}, then
     * frame the orbit camera around the resulting mesh.
     *
     * @throws IllegalStateException if mesh runtime construction or DSL execution
     *                               fails
     */
    @Override
    public String windowTitle() {
        return "Ixdar : Mesh Node Viewer";
    }

    @Override
    public String terminalRoot() {
        return modelCatalog.root.toString();
    }

    /**
     * Scan the staging directory for selectable models and log the catalog state.
     */
    @Override
    public void createCatalog() {
        modelCatalog = ModelCatalog.staging(ModelCatalog.stagingRoot());
        int catalogSize = modelCatalog.choices.size();
        Platforms.get().log(
                "[mesh-viewer] model catalog: " + catalogSize + " entries in " + modelCatalog.root
                        + " (populate via 'uv run sync-models')");
        if (catalogSize > 0) {
            Platforms.get().log("[mesh-viewer] cycle models with [ and ]; P = patch overlay; "
                    + "Shift+P cycles shader mode "
                    + "(LAMBERT \u2192 FLAT \u2192 STAGES \u2192 CREST_VS_BOUNDARY \u2192 SCALAR/Coons-error \u2192 MSC); "
                    + "D toggles decomposer (SEMANTIC \u2194 MORSE_SMALE)");
        }
        logState();
    }

    @Override
    public void initInput() {
        MenuBox.menuVisible = false;
        orbitMouse = new OrbitMouseTrap(camera, this);
        keys = new MeshViewerKeyGuy(this, orbitMouse, camera, this);
        orbitMouse.setTarget(meshCenter);
        orbitMouse.setOrbit(CAMERA_AZIMUTH, CAMERA_ELEVATION, CAMERA_DISTANCE_DEFAULT);
        mouse = orbitMouse;
        bindAutomationIfAvailable(Platforms.get(), keys, mouse);
        // Fallback: if reflection-based binding failed (TeaVM), wire callbacks directly
        bindInputDirect(Platforms.get(), keys, mouse);
    }

    @Override
    public HalfEdgeMeshRuntime createRuntime() {
        try {
            meshRuntime = new HalfEdgeMeshRuntime();
        } catch (Exception ex) {
            throw new IllegalStateException(FAILED_TO_CREATE_MESH_GL_RUNTIME, ex);
        }
        return meshRuntime;
    }

    /**
     * Asynchronously load the configured DSL graph (or OBJ file). The mesh runtime
     * holds a placeholder until the async load completes so the chrome renders
     * meanwhile.
     */
    @Override
    public void initModel() {
        runtime = createRuntime();
        if (objResource != null) {
            initObjViewer();
            return;
        }
        ModelChoice requested = requestedModel();
        if (requested != null) {
            loadModelEntry(requested);
            return;
        }
        try {
            Platforms.get().loadSourceAsync(DSL_FOLDER, dslResource, Platforms.gl().getPlatformID(), dslCode -> {
                NodeGraphRuntime graphRuntime = NodeGraphRuntime.fromSource(dslCode);
                List<PythonParser.ParsedNode> ast = graphRuntime.statements;
                lastGraphRuntime = graphRuntime;
                // If no final node specified, use the last node in the graph
                String resolvedNode = (dslFinalNode != null && !dslFinalNode.isEmpty())
                        ? dslFinalNode
                        : ast.get(ast.size() - 1).id;
                try {
                    mesh = graphRuntime.executeGraphToMesh(ast, resolvedNode, dslFinalPort);
                    graphRuntime.logTimings(TIMING_PREFIX);
                } catch (Exception e) {
                    for (Throwable t = e; t != null; t = t.getCause()) {
                        Platforms.get().log("[mesh-viewer] " + t.getClass().getName() + STR + t.getMessage());
                    }
                    throw new IllegalStateException(
                            "Failed to execute graph: dsl=" + dslResource + " finalNode=" + dslFinalNode
                                    + " port=" + dslFinalPort,
                            e);
                }
                logTiming(graphRuntime);
                try {
                    meshRuntime = new HalfEdgeMeshRuntime();
                } catch (Exception e) {
                    throw new IllegalStateException(FAILED_TO_CREATE_MESH_GL_RUNTIME, e);
                }
                meshRuntime.upload(mesh);
                meshRuntime.frameCamera(camera);
                if (mesh != null) {
                    Platforms.get().log(
                            "[mesh-viewer] mesh ready " + dslResource + VERTS + mesh.vertexCount() + FACES
                                    + mesh.faceCount());
                } else {
                    Platforms.get().log("[mesh-viewer] mesh is null for " + dslResource);
                }
                frameMesh(mesh);
            });

        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize mesh viewer runtime", e);
        }
    }

    /**
     * The model named by {@code -Dixdar.model}: a catalog token when one matches, otherwise the
     * property taken as a mesh file path (the crawfish scans live outside any catalog).
     *
     * @return the choice to load instead of the DSL graph, or {@code null} when the property is unset
     */
    private ModelChoice requestedModel() {
        String common = System.getProperty(COMMON_MODEL_PROPERTY);
        if (common == null || common.isBlank()) {
            return null;
        }
        ModelChoice match = modelCatalog == null ? null : modelCatalog.resolve(common);
        if (match != null) {
            return match;
        }
        Path file = Path.of(common);
        if (!Files.exists(file) && Files.exists(Path.of(MeshLoader.MODULE_DIRECTORY, common))) {
            file = Path.of(MeshLoader.MODULE_DIRECTORY, common);
        }
        return new ModelChoice(file.getFileName().toString(), file.toAbsolutePath().toString());
    }

    private void initObjViewer() {
        try {
            Platforms.get().loadSourceAsync("obj", objResource, Platforms.gl().getPlatformID(), objText -> {
                ArrayMesh arrayMesh = ObjMeshParser.load(objText);
                try {
                    meshRuntime = new HalfEdgeMeshRuntime();
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to create mesh GL runtime for OBJ", e);
                }
                meshRuntime.upload(arrayMesh);
                meshRuntime.frameCamera(camera);
                Platforms.get().log("[mesh-viewer] OBJ loaded: " + objResource
                        + VERTS + arrayMesh.vertexCount() + FACES + arrayMesh.faceCount());
                frameMesh(arrayMesh.vertexCount() > 0 ? arrayMesh : null);
            });
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load OBJ: " + objResource, e);
        }
    }

    /**
     * Per-frame render: reset the camera view, draw the main mesh, then draw any
     * reference overlay with alpha blending and depth-write disabled so the
     * underlying surface remains visible.
     */
    @Override
    public void renderScene() {
        if (meshRuntime == null) {
            return;
        }
        camera.resetView();
        meshRuntime.render(camera);

        if (overlayRuntime != null) {
            GL gl = Platforms.gl();
            gl.enable(gl.BLEND());
            gl.blendFunc(gl.SRC_ALPHA(), gl.ONE_MINUS_SRC_ALPHA());
            gl.depthMask(false);
            overlayRuntime.render(camera);
            gl.depthMask(true);
            gl.disable(gl.BLEND());
        }
    }

    @Override
    public void applyPendingModel() {
        if (pendingModelPath == null) {
            return;
        }
        String path = pendingModelPath;
        pendingModelPath = null;
        if (modelCatalog == null) {
            return;
        }
        int index = modelCatalog.indexOfPath(path);
        if (index >= 0) {
            loadModelEntry(modelCatalog.select(index));
        } else {
            Platforms.get().log("[mesh-viewer] no catalog entry for " + path);
        }
    }

    /**
     * Toggle scene activation. Disposes the mesh runtime when the scene is being
     * deactivated so GL resources are released.
     *
     * @param state true to activate, false to deactivate
     */
    @Override
    public void activate(boolean state) {
        super.activate(state);
        if (!state) {
            disposeMeshRuntime();
        }
    }

    /**
     * Release the mesh runtime and any overlay before chaining to the base
     * shutdown.
     */
    @Override
    public void shutdown() {
        disposeMeshRuntime();
        super.shutdown();
    }

    private void logTiming(NodeGraphRuntime runtime) {
        var timing = runtime.lastTimingMs();
        long threshold = 1; // only log nodes that took >= 1ms
        StringBuilder sb = new StringBuilder();
        sb.append("[dsl-timing] total=").append(runtime.lastTotalMs()).append("ms");
        int slow = 0;
        for (var entry : timing.entrySet()) {
            if (entry.getValue() >= threshold) {
                sb.append("\n  ").append(entry.getValue()).append("ms  ").append(entry.getKey());
                slow++;
            }
        }
        if (slow == 0)
            sb.append(" (all nodes <1ms)");
        Platforms.get().log(sb.toString());
    }

    /**
     * Vertex count of the current mesh.
     *
     * @return vertex count, or 0 if no mesh is loaded
     */
    public int getMeshVertexCount() {
        return mesh == null ? 0 : mesh.vertexCount();
    }

    /**
     * Face count of the current mesh.
     *
     * @return face count, or 0 if no mesh is loaded
     */
    public int getMeshFaceCount() {
        return mesh == null ? 0 : mesh.faceCount();
    }

    /**
     * Edge count of the current mesh.
     *
     * @return edge count, or 0 if no mesh is loaded
     */
    public int getMeshEdgeCount() {
        return mesh == null ? 0 : mesh.edgeCount();
    }

    /**
     * Number of edges on the mesh boundary (i.e. with only one incident face).
     *
     * @return boundary-edge count, or 0 if no mesh is loaded
     */
    public int getMeshBoundaryEdgeCount() {
        if (mesh == null) {
            return 0;
        }
        int boundaryEdgeCount = 0;
        for (int i = 0; i < mesh.edgeCount(); i++) {
            if (mesh.isBoundaryEdge(mesh.edgeIdAt(i))) {
                boundaryEdgeCount++;
            }
        }
        return boundaryEdgeCount;
    }

    /**
     * Euler characteristic V - E + F of the current mesh.
     *
     * @return characteristic, or 0 if no mesh is loaded
     */
    public int getMeshEulerCharacteristic() {
        return mesh == null ? 0 : mesh.vertexCount() - mesh.edgeCount() + mesh.faceCount();
    }

    /**
     * Whether the mesh has no boundary edges (i.e. is closed).
     *
     * @return true if a mesh is loaded and has zero boundary edges
     */
    public boolean isMeshClosed() {
        return mesh != null && getMeshBoundaryEdgeCount() == 0;
    }

    /**
     * Count faces that have fewer than three vertices or whose first triangle has
     * zero cross-product area (collinear/duplicate verts).
     *
     * @return degenerate-face count, or 0 if no mesh is loaded
     */
    public int getMeshDegenerateFaceCount() {
        if (mesh == null) {
            return 0;
        }

        int degenerateFaceCount = 0;
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        Vector3f edgeA = new Vector3f();
        Vector3f edgeB = new Vector3f();
        Vector3f cross = new Vector3f();
        for (int i = 0; i < mesh.faceCount(); i++) {
            int faceId = mesh.faceIdAt(i);
            if (mesh.faceVertexCount(faceId) < NUM_3) {
                degenerateFaceCount++;
                continue;
            }
            mesh.vertexPosition(mesh.faceVertexAt(faceId, 0), p0);
            mesh.vertexPosition(mesh.faceVertexAt(faceId, 1), p1);
            mesh.vertexPosition(mesh.faceVertexAt(faceId, 2), p2);
            edgeA.set(p1).sub(p0);
            edgeB.set(p2).sub(p0);
            edgeA.cross(edgeB, cross);
            if (cross.lengthSquared() == NUM_0) {
                degenerateFaceCount++;
            }
        }
        return degenerateFaceCount;
    }

    /**
     * Bounding-sphere radius of the current mesh.
     *
     * @return radius, or 0 if no mesh is loaded
     */
    public float getMeshRadius() {
        return mesh == null ? NUM_0 : mesh.radius();
    }

    /**
     * Centroid of the current mesh.
     *
     * @return a fresh {@link Vector3f} at the centroid, or origin if no mesh is
     *         loaded
     */
    public Vector3f getMeshCenter() {
        return mesh == null ? new Vector3f() : mesh.center(new Vector3f());
    }

    /**
     * Minimum corner of the current mesh's axis-aligned bounding box.
     *
     * @return min corner, or {@code (-HALF_EXTENT, -HALF_EXTENT, -HALF_EXTENT)} if
     *         no mesh is loaded
     */
    public Vector3f getBoundingBoxMin() {
        return mesh == null ? new Vector3f(-HALF_EXTENT, -HALF_EXTENT, -HALF_EXTENT) : mesh.boundsMin(new Vector3f());
    }

    /**
     * Maximum corner of the current mesh's axis-aligned bounding box.
     *
     * @return max corner, or {@code (HALF_EXTENT, HALF_EXTENT, HALF_EXTENT)} if no
     *         mesh is loaded
     */
    public Vector3f getBoundingBoxMax() {
        return mesh == null ? new Vector3f(HALF_EXTENT, HALF_EXTENT, HALF_EXTENT) : mesh.boundsMax(new Vector3f());
    }

    /**
     * Load a reference OBJ file as a semi-transparent overlay.
     *
     * @param objPath path or resource of the OBJ file to overlay
     */
    public void loadOverlay(String objPath) {
        disposeOverlay();
        try {
            ArrayMesh refMesh = MeshLoader.load(objPath);
            overlayRuntime = new HalfEdgeMeshRuntime();
            overlayRuntime.upload(refMesh);
            overlayRuntime.setSolidColor(ColorRGB.BLUE_WHITE.toVector4f());
            Platforms.get().log("[mesh-viewer] overlay loaded: " + objPath
                    + VERTS + refMesh.vertexCount() + FACES + refMesh.faceCount());
        } catch (IOException e) {
            Platforms.get().log("[mesh-viewer] overlay load failed: " + e.getMessage());
        } catch (Exception e) {
            Platforms.get().log("[mesh-viewer] overlay GL init failed: " + e.getMessage());
        }
    }

    /** Remove any currently loaded overlay. */
    public void clearOverlay() {
        disposeOverlay();
    }

    private void disposeOverlay() {
        if (overlayRuntime != null) {
            overlayRuntime.dispose();
            overlayRuntime = null;
        }
    }

    /**
     * Reload the viewer with a different DSL file at runtime. Disposes existing
     * mesh, parses and executes the new DSL, uploads to GPU. Must be called on the
     * render thread (via AutomationRuntime.runOnMainThread).
     *
     * @param dslName   DSL filename without path (e.g. "test_finger.dsl" or
     *                  "test_finger")
     * @param finalNode output node ID, or empty for last node in graph
     * @param finalPort output port name, or empty for "geometry"
     * @throws IllegalStateException if mesh runtime construction or DSL execution
     *                               fails
     */
    public void loadDsl(String dslName, String finalNode, String finalPort) {
        // Normalize: append .dsl if missing
        if (!dslName.endsWith(DSL)) {
            dslName = dslName + DSL;
        }
        if (finalPort == null || finalPort.isEmpty()) {
            finalPort = DEFAULT_DSL_FINAL_PORT;
        }

        disposeMeshRuntime();

        String resolvedPort = finalPort;
        String resolvedDslName = dslName;
        Platforms.get().loadSourceAsync(DSL_FOLDER, resolvedDslName, Platforms.gl().getPlatformID(), dslCode -> {
            NodeGraphRuntime runtime = NodeGraphRuntime.fromSource(dslCode);
            List<PythonParser.ParsedNode> ast = runtime.statements;
            lastGraphRuntime = runtime;

            String resolvedNode = (finalNode != null && !finalNode.isEmpty())
                    ? finalNode
                    : ast.get(ast.size() - 1).id;

            try {
                mesh = runtime.executeGraphToMesh(ast, resolvedNode, resolvedPort);
                runtime.logTimings(TIMING_PREFIX);
            } catch (Exception e) {
                Platforms.get().log("[mesh-viewer] DSL reload failed: " + e.getMessage());
                throw new IllegalStateException("Failed to execute DSL: " + resolvedDslName, e);
            }
            logTiming(runtime);
            try {
                meshRuntime = new HalfEdgeMeshRuntime();
            } catch (Exception e) {
                throw new IllegalStateException(FAILED_TO_CREATE_MESH_GL_RUNTIME, e);
            }
            meshRuntime.upload(mesh);
            meshRuntime.frameCamera(camera);

            if (mesh != null) {
                Platforms.get().log(
                        "[mesh-viewer] dsl reloaded: " + resolvedDslName + VERTS + mesh.vertexCount()
                                + FACES + mesh.faceCount());
            } else {
                Platforms.get().log("[mesh-viewer] dsl reload produced null mesh: " + resolvedDslName);
            }
            frameMesh(mesh);
        });
    }

    /**
     * Current mesh from the DSL graph, or null before async load completes.
     *
     * @return current mesh, or {@code null} if not yet loaded
     */
    public MeshTopology getMesh() {
        return mesh;
    }

    // ==================== VIEW-7 model switching + patch overlay
    // ====================

    @Override
    public ModelChoice currentModel() {
        if (currentModelKey == null || currentModelKey.isEmpty()) {
            return null;
        }
        return new ModelChoice(currentModelDisplayName, currentModelKey);
    }

    @Override
    public void requestModelLoad(String path) {
        pendingModelPath = path;
    }

    @Override
    public void setControls() {
        controls.add(new ControlHint("[", "previous model", this::prevModel));
        controls.add(new ControlHint("]", "next model", this::nextModel));
        controls.add(new ControlHint("Z", "toggle wireframe", this::toggleMeshWireframe));
        controls.add(new ControlHint("P", "toggle patch overlay", this::togglePatchOverlay));
        controls.add(new ControlHint("Shift+P", "cycle shader mode", this::toggleShaderMode));
        controls.add(new ControlHint("D", "cycle decomposer", this::toggleDecomposer));
        super.setControls();
    }

    /**
     * Advance the catalog cursor and load the next model. No-op if the catalog is
     * empty.
     */
    public void nextModel() {
        if (modelCatalog == null || modelCatalog.choices.isEmpty())
            return;
        loadModelEntry(modelCatalog.next());
    }

    /**
     * Step the catalog cursor back and load the previous model. No-op if the
     * catalog is empty.
     */
    public void prevModel() {
        if (modelCatalog == null || modelCatalog.choices.isEmpty())
            return;
        loadModelEntry(modelCatalog.prev());
    }

    /**
     * Load a specific catalog entry while preserving the current orbit camera,
     * invalidating any cached patch decomposition, and turning off the patch
     * overlay.
     *
     * @param entry catalog entry to load (ignored if null)
     */
    public void loadModelEntry(ModelChoice entry) {
        if (entry == null)
            return;
        // Invalidate any cached decomposition — the mesh is changing.
        cachedDiagnostics = null;
        cachedDiagnosticsKey = null;
        patchOverlayEnabled = false;
        currentModelKey = entry.path;
        currentModelDisplayName = entry.displayName;
        Platforms.get().log("[mesh-viewer] loading " + entry.displayName);
        preserveOrbit(() -> {
            switch (entry.kind) {
                case DSL -> loadDslFromAbsolutePath(entry.path);
                case MESH_FILE -> loadMeshFileFromAbsolutePath(entry.path);
            }
            return true;
        });
        logState();
    }

    private void loadDslFromAbsolutePath(String absolutePath) {
        // loadDsl() expects a resource-relative name, but the staging dir
        // contains symlinks — read the file directly and execute the graph.
        try {
            String dslCode = new String(Files.readAllBytes(Path.of(absolutePath)));
            disposeMeshRuntime();
            NodeGraphRuntime runtime = NodeGraphRuntime.fromSource(dslCode);
            List<PythonParser.ParsedNode> ast = runtime.statements;
            lastGraphRuntime = runtime;
            String resolvedNode = ast.get(ast.size() - 1).id;
            mesh = runtime.executeGraphToMesh(ast, resolvedNode, DEFAULT_DSL_FINAL_PORT);
            runtime.logTimings(TIMING_PREFIX);
            meshRuntime = new HalfEdgeMeshRuntime();
            meshRuntime.upload(mesh);
            meshRuntime.frameCamera(camera);
            if (mesh != null) {
                Platforms.get().log("[mesh-viewer] dsl loaded: " + absolutePath + VERTS + mesh.vertexCount()
                        + FACES + mesh.faceCount());
            }
            frameMesh(mesh);
        } catch (Exception e) {
            Platforms.get().log("[mesh-viewer] DSL load failed for " + absolutePath + STR + e.getMessage());
        }
    }

    private void loadMeshFileFromAbsolutePath(String absolutePath) {
        try {
            ArrayMesh arrayMesh = MeshLoader.load(absolutePath);
            disposeMeshRuntime();
            mesh = arrayMesh;
            meshRuntime = new HalfEdgeMeshRuntime();
            meshRuntime.upload(arrayMesh);
            meshRuntime.frameCamera(camera);
            Platforms.get().log("[mesh-viewer] mesh loaded: " + absolutePath
                    + VERTS + arrayMesh.vertexCount() + FACES + arrayMesh.faceCount());
            frameMesh(arrayMesh);
        } catch (Exception e) {
            Platforms.get().log("[mesh-viewer] mesh load failed for " + absolutePath + STR + e.getMessage());
        }
    }

    /**
     * Toggle the patch overlay. When turning on, lazily compute or reuse the cached
     * decomposition, install patch tags / feature edges / scalar fields. When
     * turning off, clear all overlay GL state.
     */
    public void togglePatchOverlay() {
        if (meshRuntime == null || mesh == null) {
            Platforms.get().log("[mesh-viewer] patch overlay: no mesh loaded");
            return;
        }
        patchOverlayEnabled = !patchOverlayEnabled;
        if (!patchOverlayEnabled) {
            meshRuntime.clearTags();
            meshRuntime.clearFeatureEdgeOverlay();
            meshRuntime.clearPerVertexScalar();
            Platforms.get().log("[mesh-viewer] patches: OFF");
            logState();
            return;
        }
        ensureDecomposition();
        if (cachedDiagnostics == null) {
            Platforms.get().log("[mesh-viewer] patch overlay: decomposition unavailable");
            patchOverlayEnabled = false;
            logState();
            return;
        }
        applyCurrentOverlay();
        applyFeatureEdgeOverlay();
        applyScalarOverlay();
        Platforms.get().log("[mesh-viewer] patches: ON (" + cachedDiagnostics.decomposition().patches().size()
                + " patches, mode=" + shaderMode + STR_2);
        logState();
    }

    /**
     * PATCH-26: cycle the active decomposer. Invalidates the cache so the next
     * overlay-on triggers a recompute via the new decomposer; if patches are
     * already on, recompute eagerly so the user sees the swap immediately.
     */
    public void toggleDecomposer() {
        DecomposerKind[] cycle = DecomposerKind.values();
        activeDecomposer = cycle[(activeDecomposer.ordinal() + 1) % cycle.length];
        cachedDiagnostics = null;
        cachedDiagnosticsKey = null;
        Platforms.get().log("[mesh-viewer] decomposer: " + activeDecomposer);
        if (patchOverlayEnabled && meshRuntime != null && mesh != null) {
            ensureDecomposition();
            applyCurrentOverlay();
            applyFeatureEdgeOverlay();
            applyScalarOverlay();
        }
        logState();
    }

    /**
     * Cycle through {@link HalfEdgeMeshRuntime.ShaderMode}, push the new mode to
     * the runtime, and re-derive any overlay state that depends on the active mode.
     */
    public void toggleShaderMode() {
        HalfEdgeMeshRuntime.ShaderMode[] cycle = HalfEdgeMeshRuntime.ShaderMode.values();
        shaderMode = cycle[(shaderMode.ordinal() + 1) % cycle.length];
        if (meshRuntime != null) {
            meshRuntime.setShaderMode(shaderMode);
        }
        if (patchOverlayEnabled && cachedDiagnostics != null) {
            applyCurrentOverlay(); // rebuild tag colours for new mode
            applyFeatureEdgeOverlay(); // (re)install overlay edges if needed
            applyScalarOverlay(); // upload coonsError when entering SCALAR mode
        }
        Platforms.get().log("[mesh-viewer] shader mode: " + shaderMode);
        logState();
    }

    /**
     * Feed the per-vertex Coons reconstruction error (PATCH-16) into the SCALAR
     * shader pipeline (PATCH-15) when the user cycles into SCALAR mode. Ramp is
     * scaled so the pass/fail threshold reads as the middle of the thermal gradient
     * — dark ≤ threshold (Coons-fit OK), bright > threshold (Coons-fit failing).
     */
    private void applyScalarOverlay() {
        if (meshRuntime == null || cachedDiagnostics == null)
            return;
        if (shaderMode != HalfEdgeMeshRuntime.ShaderMode.SCALAR) {
            meshRuntime.clearPerVertexScalar();
            return;
        }
        float[] errors = cachedDiagnostics.coonsError();
        if (errors == null || errors.length == 0) {
            meshRuntime.clearPerVertexScalar();
            return;
        }
        float rampMax = Math.max(NUM_2 * cachedDiagnostics.coonsErrorThreshold(), NUM_1e_6);
        meshRuntime.setPerVertexScalar(errors, NUM_0, rampMax);
    }

    /**
     * Compute the feature-edge categories appropriate for the current shader mode
     * and push them to the runtime. Category assignment mirrors
     * {@code PatchRenderer.drawFeatureEdgeOverlay} so the on-screen colors match
     * the offline PNG diagnostic — the two paths must stay in lockstep.
     */
    private void applyFeatureEdgeOverlay() {
        if (meshRuntime == null || cachedDiagnostics == null)
            return;
        if (shaderMode == HalfEdgeMeshRuntime.ShaderMode.STAGES) {
            Set<Long> dih = cachedDiagnostics.dihedralFeatureEdges();
            Set<Long> prin = cachedDiagnostics.principalFeatureEdges();
            Set<Long> crest = cachedDiagnostics.crestEdges();
            Set<Long> saddle = cachedDiagnostics.saddleSeparatorEdges();

            Set<Long> all = new HashSet<>(dih);
            all.addAll(prin);
            all.addAll(crest);
            all.addAll(saddle);

            List<Long> dihOnly = new ArrayList<>();
            List<Long> prinOnly = new ArrayList<>();
            List<Long> crestOnly = new ArrayList<>();
            List<Long> multi = new ArrayList<>();
            for (long key : all) {
                boolean d = dih.contains(key);
                boolean p = prin.contains(key);
                boolean c = crest.contains(key);
                boolean s = saddle.contains(key);
                int sourceCount = (d ? 1 : 0) + (p ? 1 : 0) + (c ? 1 : 0) + (s ? 1 : 0);
                if (sourceCount >= 2)
                    multi.add(key);
                else if (d)
                    dihOnly.add(key);
                else if (p)
                    prinOnly.add(key);
                else if (c)
                    crestOnly.add(key);
                // saddle-only not drawn here; saddle drawn as a last pass below for emphasis.
            }
            List<HalfEdgeMeshRuntime.FeatureEdgeCategory> cats = new ArrayList<>();
            cats.add(new HalfEdgeMeshRuntime.FeatureEdgeCategory(FeatureEdgeColors.DIHEDRAL, dihOnly));
            cats.add(new HalfEdgeMeshRuntime.FeatureEdgeCategory(FeatureEdgeColors.PRINCIPAL, prinOnly));
            cats.add(new HalfEdgeMeshRuntime.FeatureEdgeCategory(FeatureEdgeColors.CREST, crestOnly));
            cats.add(new HalfEdgeMeshRuntime.FeatureEdgeCategory(FeatureEdgeColors.MULTI_SOURCE, multi));
            cats.add(new HalfEdgeMeshRuntime.FeatureEdgeCategory(FeatureEdgeColors.SADDLE, saddle));
            meshRuntime.setFeatureEdgeOverlay(cats);
        } else if (shaderMode == HalfEdgeMeshRuntime.ShaderMode.CREST_VS_BOUNDARY) {
            Set<Long> crest = cachedDiagnostics.crestEdges();
            Set<Long> boundary = cachedDiagnostics.patchBoundaryEdges();
            List<Long> boundaryOnly = new ArrayList<>();
            List<Long> crestIgnored = new ArrayList<>();
            List<Long> aligned = new ArrayList<>();
            Set<Long> union = new HashSet<>(crest);
            union.addAll(boundary);
            for (long key : union) {
                boolean c = crest.contains(key);
                boolean b = boundary.contains(key);
                if (c && b)
                    aligned.add(key);
                else if (b)
                    boundaryOnly.add(key);
                else if (c)
                    crestIgnored.add(key);
            }
            List<HalfEdgeMeshRuntime.FeatureEdgeCategory> cats = new ArrayList<>();
            cats.add(new HalfEdgeMeshRuntime.FeatureEdgeCategory(FeatureEdgeColors.BOUNDARY_ONLY, boundaryOnly));
            cats.add(new HalfEdgeMeshRuntime.FeatureEdgeCategory(FeatureEdgeColors.CREST_IGNORED, crestIgnored));
            cats.add(new HalfEdgeMeshRuntime.FeatureEdgeCategory(FeatureEdgeColors.CREST_HONORED, aligned));
            meshRuntime.setFeatureEdgeOverlay(cats);
        } else if (shaderMode == HalfEdgeMeshRuntime.ShaderMode.MSC) {
            MorseSmaleComplex.Result msc = cachedDiagnostics.morseSmale();
            if (msc != null) {
                // Convert each MSC arc polyline into mesh edges; push as
                // a single black-colored category. Critical-point dots
                // are CPU-only for now (live-viewer point sprites are
                // future work — the arcs alone already give the topology
                // structure on screen).
                List<Long> arcEdges = new ArrayList<>();
                for (var arc : msc.arcs()) {
                    int[] verts = arc.vertices();
                    for (int i = 0; i + 1 < verts.length; i++) {
                        int u = verts[i];
                        int v = verts[i + 1];
                        long key = EdgeKey.undirected(u, v);
                        arcEdges.add(key);
                    }
                }
                List<HalfEdgeMeshRuntime.FeatureEdgeCategory> cats = new ArrayList<>();
                cats.add(new HalfEdgeMeshRuntime.FeatureEdgeCategory(NUM_0x000000, arcEdges));
                meshRuntime.setFeatureEdgeOverlay(cats);
            } else {
                meshRuntime.clearFeatureEdgeOverlay();
            }
        } else {
            meshRuntime.clearFeatureEdgeOverlay();
        }
    }

    private void ensureDecomposition() {
        if (mesh == null)
            return;
        String key = currentModelKey != null ? currentModelKey : DSL_FOLDER;
        if (cachedDiagnostics != null && key.equals(cachedDiagnosticsKey))
            return;
        // TEMPORARY (MESH-49): until the node ecosystem canonicalizes output
        // to ArrayMesh, convert on the fly here so patch overlay works on
        // DSL-produced HalfEdgeMesh too. Once MESH-49 lands we can drop this
        // conversion and require the upstream mesh to already be ArrayMesh.
        ArrayMesh am = (mesh instanceof ArrayMesh existing)
                ? existing
                : SemanticPatchDecomposer.toArrayMesh(mesh);
        if (!(mesh instanceof ArrayMesh)) {
            // Re-upload the converted ArrayMesh so the runtime's EBO index
            // space matches the Patch.vertexIndices we're about to install as
            // tags. Without this, a HalfEdgeMesh's own compileSurfaceData
            // uses a different vertex ordering and the tag masks would colour
            // the wrong triangles.
            mesh = am;
            meshRuntime.upload(am);
            Platforms.get().log("[mesh-viewer] converted HalfEdgeMesh\u2192ArrayMesh for patch overlay (MESH-49)");
        }
        Platforms.get().log("[mesh-viewer] decomposing " + key
                + " (" + am.vertexCount() + " verts)...");
        long start = System.currentTimeMillis();
        cachedDiagnostics = (activeDecomposer == DecomposerKind.MORSE_SMALE)
                ? MorseSmaleDecomposer.decomposeWithDiagnostics(am, NUM_128)
                : SemanticPatchDecomposer.decomposeWithDiagnostics(am, NUM_128);
        cachedDiagnosticsKey = key;
        long elapsed = System.currentTimeMillis() - start;
        Platforms.get().log("[mesh-viewer] decomposed in " + elapsed + "ms: "
                + cachedDiagnostics.decomposition().patches().size() + " patches"
                + " (crest=" + cachedDiagnostics.crestEdges().size()
                + " saddle=" + cachedDiagnostics.saddleSeparatorEdges().size()
                + " boundary=" + cachedDiagnostics.patchBoundaryEdges().size() + STR_2);
    }

    private void applyCurrentOverlay() {
        if (meshRuntime == null || cachedDiagnostics == null)
            return;
        int vertexCount = mesh.vertexCount();
        Map<String, boolean[]> tags = new HashMap<>();
        meshRuntime.clearTagColors();
        for (Patch p : cachedDiagnostics.decomposition().patches()) {
            String name = "patch_" + p.id();
            boolean[] mask = new boolean[vertexCount];
            for (int v : p.vertexIndices()) {
                if (v >= 0 && v < vertexCount)
                    mask[v] = true;
            }
            tags.put(name, mask);
            Vector4f color;
            if (shaderMode == HalfEdgeMeshRuntime.ShaderMode.FLAT) {
                int rgb = PatchRenderer.uniquePatchColor(p.id());
                color = new Vector4f(
                        ((rgb >> NUM_16) & NUM_0xf) / NUM_255,
                        ((rgb >> NUM_8) & NUM_0xf) / NUM_255,
                        (rgb & NUM_0xf) / NUM_255,
                        NUM_1);
            } else {
                int rgb = Integer.parseInt(p.color(), NUM_16);
                color = new Vector4f(
                        ((rgb >> NUM_16) & NUM_0xf) / NUM_255,
                        ((rgb >> NUM_8) & NUM_0xf) / NUM_255,
                        (rgb & NUM_0xf) / NUM_255,
                        NUM_1);
            }
            meshRuntime.setTagColor(name, color);
        }
        meshRuntime.setShaderMode(shaderMode);
        meshRuntime.setTags(tags);
    }

    /**
     * Toggle wireframe rendering on the mesh runtime.
     */
    public void toggleMeshWireframe() {
        if (meshRuntime == null) {
            return;
        }
        meshRuntime.setWireframe(!meshRuntime.isWireframe());
        Platforms.get().log("[mesh-viewer] wireframe=" + meshRuntime.isWireframe());
    }

    /**
     * Switch the camera projection between perspective and orthographic on the mesh
     * runtime.
     *
     * @param ortho true for orthographic, false for perspective
     */
    public void setOrthographic(boolean ortho) {
        HalfEdgeMeshRuntime rt = meshRuntime;
        if (rt == null)
            return;
        rt.setOrthographic(ortho);
    }

    /**
     * Whether the mesh runtime is currently using an orthographic projection.
     *
     * @return true if orthographic, false if perspective or no runtime is loaded
     */
    public boolean isOrthographic() {
        HalfEdgeMeshRuntime rt = meshRuntime;
        return rt != null && rt.isOrthographic();
    }

    private void disposeMeshRuntime() {
        if (meshRuntime != null) {
            meshRuntime.dispose();
            meshRuntime = null;
        }
        disposeOverlay();
        mesh = null;
    }

    /**
     * PATCH-26: which decomposer's output drives the patch overlay. Both pipelines
     * coexist; D toggles. SEMANTIC defaults — the decomposer the project shipped
     * before MSC.
     */
    public enum DecomposerKind {
        SEMANTIC, MORSE_SMALE
    }

}
