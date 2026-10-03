package ixdar.scenes.mesh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.joml.Vector3f;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.data.EdgeKey;
import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.SemanticPatchDecomposer;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.load.ObjMeshParser;
import ixdar.geometry.mesh.data.ops.MeshRepairReport;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorRGB;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.parsing.python.PythonParser;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.input.Keys;
import ixdar.platform.input.OrbitCameraKeyGuy;
import ixdar.platform.input.OrbitMouseTrap;
import ixdar.scenes.model.ControlHint;
import ixdar.scenes.model.ModelCatalog;
import ixdar.scenes.model.ModelChoice;
import ixdar.scenes.model.ModelScene;

@SceneAnnotation(id = "mesh-viewer")
public class MeshNodeViewerScene extends ModelScene {
    public static final String PATCHES = "  patches=";
    public static final String ON = "ON";
    public static final String OFF = "OFF";
    public static final String DSL = ".dsl";
    public static final String FAILED_TO_CREATE_MESH_GL_RUNTIME = "Failed to create mesh GL runtime";
    public static final String VERTS = " verts=";
    public static final String FACES = " faces=";
    public static final int MIN_FACE_VERTICES = 3;
    public static final float ERROR_RAMP_SCALE = 2f;
    public static final float MIN_RAMP_VALUE = 1e-6f;
    public static final int DECOMPOSE_SAMPLE_COUNT = 128;
    public static final int RED_SHIFT = 16;
    public static final int HEX_RADIX = 16;
    public static final int BYTE_MASK = 0xff;
    public static final float COLOR_CHANNEL_MAX = 255f;
    public static final int GREEN_SHIFT = 8;

    private static final String DSL_FOLDER = "dsl";
    private static final String DEFAULT_DSL_RESOURCE = "skull.dsl";
    private static final String DEFAULT_DSL_FINAL_NODE = "";
    private static final String DEFAULT_DSL_FINAL_PORT = "geometry";

    private static final Color[] EDGE_MARK_COLORS = {
        Color.EDGE_MARK_AMBER, Color.EDGE_MARK_CYAN, Color.EDGE_MARK_MAGENTA };

    private static final String DSL_RESOURCE_DIRECTORY = "src/main/resources/dsl";

    private static final String DSL_BUILD_DIRECTORY = "target/classes/dsl";

    private static final String TIMING_PREFIX = "[mesh-viewer]";

    private static final float HALF_EXTENT = 0.5f;

    /** The runtime the surface is drawn through, replaced by {@link #createRuntime} per model. */
    public volatile HalfEdgeMeshRuntime meshRuntime;

    private final String dslResource;
    private final String dslFinalNode;
    private final String dslFinalPort;

    /** When non-null, load this OBJ file instead of executing a DSL graph. */
    private final String objResource;

    private MeshTopology mesh;
    private GeometryBundle meshBundle;

    /** Half-edge copy of a mesh-file model, built on demand for the walks edge overlays need. */
    private MeshTopology halfEdgeSurface;

    /** Model {@link #halfEdgeSurface} was built from, so a model switch rebuilds it. */
    private MeshTopology halfEdgeSurfaceSource;

    private HalfEdgeMeshRuntime overlayRuntime;
    private ArrayMesh overlayMesh;
    private NodeGraphRuntime lastGraphRuntime;

    /** File the last graph was read from, which is also the file an edit is written back into. */
    private String loadedDslFile;

    // VIEW-7: catalog + per-mesh decomposition cache + overlay state
    private String currentModelKey; // absolutePath for staging-dir entries, or "" for initial load
    private String currentModelDisplayName = "(initial)";

    private SemanticPatchDecomposer.DecompositionDiagnostics cachedDiagnostics;
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
        sb.append(PATCHES).append(patchOverlayEnabled ? ON : OFF);
        sb.append("  shader=").append(shaderMode.name());
        sb.append("  texture=").append(hasTexturedDraw() ? ON : OFF);
        sb.append("  slots=").append(bundleSlotNames());
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
        if (modelCollection != null) {
            sb.append("  collection=").append(modelCollection.name)
                    .append(" kept=").append(modelCollection.keptCount())
                    .append('/').append(modelCollection.memberCount());
            int member = currentMemberIndex();
            if (member >= 0) {
                sb.append("  member=").append(modelCollection.memberNames[member])
                        .append(modelCollection.memberKeep[member] ? " KEEP" : " REJECT");
            }
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
        keyGuy = new OrbitCameraKeyGuy(orbitMouse, camera, this, controls);
        keys = keyGuy;
        orbitMouse.setTarget(meshCenter);
        orbitMouse.setOrbit(CAMERA_AZIMUTH, CAMERA_ELEVATION, CAMERA_DISTANCE_DEFAULT);
        mouse = orbitMouse;
        bindAutomationIfAvailable(Platforms.get(), keys, mouse);
        // Fallback: if reflection-based binding failed (TeaVM), wire callbacks directly
        bindInputDirect(Platforms.get(), keys, mouse);
    }

    /**
     * Build the runtime the surface draws through and install it as {@link #meshRuntime}. Every
     * model load calls this, so a subclass overriding it gets its runtime kind on every switch.
     *
     * @return the installed runtime
     */
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
            loadDslSource(dslResource, dslCode -> {
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
                        Platforms.get().log("[mesh-viewer] " + t.getClass().getName() + ": " + t.getMessage());
                    }
                    throw new IllegalStateException(
                            "Failed to execute graph: dsl=" + dslResource + " finalNode=" + dslFinalNode
                                    + " port=" + dslFinalPort,
                            e);
                }
                logTiming(graphRuntime);
                createRuntime();
                meshRuntime.upload(mesh);
                meshRuntime.frameCamera(camera);
                if (mesh != null) {
                    Platforms.get().log(
                            "[mesh-viewer] mesh ready " + dslResource + VERTS + mesh.vertexCount() + FACES
                                    + mesh.faceCount());
                } else {
                    Platforms.get().log("[mesh-viewer] mesh is null for " + dslResource);
                }
                applyEdgeMarkOverlay(graphRuntime);
                frameMesh(mesh);
            });

        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize mesh viewer runtime", e);
        }
    }

    /**
     * The model named by {@code -Dixdar.model}: a directory opens as a collection, a catalog token
     * resolves through the catalog, a {@code .dsl} path is executed as a graph, and anything else
     * is taken as a mesh file path (the crawfish scans live outside any catalog).
     *
     * @return the choice to load instead of the DSL graph, or {@code null} when the property is unset
     */
    private ModelChoice requestedModel() {
        String common = System.getProperty(COMMON_MODEL_PROPERTY);
        if (common == null || common.isBlank()) {
            return null;
        }
        Path directory = Path.of(common);
        if (Files.isDirectory(directory)) {
            Path absolute = directory.toAbsolutePath();
            return new ModelChoice(absolute.getFileName().toString(), absolute.toString(),
                    ModelChoice.Kind.COLLECTION);
        }
        ModelChoice match = modelCatalog == null ? null : modelCatalog.resolve(common);
        if (match != null) {
            return match;
        }
        Path file = Path.of(common);
        if (!Files.exists(file) && Files.exists(Path.of(MeshLoader.MODULE_DIRECTORY, common))) {
            file = Path.of(MeshLoader.MODULE_DIRECTORY, common);
        }
        ModelChoice.Kind kind = common.endsWith(DSL) ? ModelChoice.Kind.DSL
                : ModelChoice.Kind.MESH_FILE;
        return new ModelChoice(file.getFileName().toString(), file.toAbsolutePath().toString(),
                kind);
    }

    private void initObjViewer() {
        try {
            Platforms.get().loadSourceAsync("obj", objResource, Platforms.gl().getPlatformID(), objText -> {
                ArrayMesh arrayMesh = ObjMeshParser.load(objText);
                createRuntime();
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

    /**
     * Load a requested switch through the staging catalog entry whose path it names, which is how
     * this viewer loads DSL graphs, mesh files and collections alike.
     *
     * @param path catalog path of the requested model
     * @throws IOException when the catalog has no entry for {@code path}
     */
    @Override
    public void loadModelOrGraph(String path) throws IOException {
        int index = modelCatalog == null ? -1 : modelCatalog.indexOfPath(path);
        if (index < 0) {
            throw new IOException("no catalog entry for " + path);
        }
        loadModelEntry(modelCatalog.select(index));
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
     * The {@code repair_mesh} report of the graph that last ran, which names every defect class it
     * found and every shell and hole it left behind.
     *
     * @return the report text, or an empty string when the graph carries no repair node
     */
    public String getMeshRepairReport() {
        MeshRepairReport report = getLastRepairReport();
        return report == null ? "" : report.toText();
    }

    /**
     * The {@code repair_mesh} report object the graph that last ran parked on its output bundle.
     *
     * @return the report, or null when the graph carries no repair node
     */
    public MeshRepairReport getLastRepairReport() {
        if (lastGraphRuntime == null
                || !(lastGraphRuntime.lastOutput(DEFAULT_DSL_FINAL_PORT)
                        instanceof GeometryBundle bundle)
                || !(bundle.slots().get(MeshRepairReport.SLOT) instanceof MeshRepairReport report)) {
            return null;
        }
        return report;
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
            if (mesh.faceVertexCount(faceId) < MIN_FACE_VERTICES) {
                degenerateFaceCount++;
                continue;
            }
            mesh.vertexPosition(mesh.faceVertexAt(faceId, 0), p0);
            mesh.vertexPosition(mesh.faceVertexAt(faceId, 1), p1);
            mesh.vertexPosition(mesh.faceVertexAt(faceId, 2), p2);
            edgeA.set(p1).sub(p0);
            edgeB.set(p2).sub(p0);
            edgeA.cross(edgeB, cross);
            if (cross.lengthSquared() == 0f) {
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
        return mesh == null ? 0f : mesh.radius();
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
            overlayMesh = refMesh;
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
        overlayMesh = null;
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
        loadDslSource(resolvedDslName, dslCode -> {
            NodeGraphRuntime runtime = NodeGraphRuntime.fromSource(dslCode);
            List<PythonParser.ParsedNode> ast = runtime.statements;
            lastGraphRuntime = runtime;

            String resolvedNode = (finalNode != null && !finalNode.isEmpty())
                    ? finalNode
                    : ast.get(ast.size() - 1).id;

            Object result;
            try {
                result = runtime.executeGraphResult(ast, resolvedNode, resolvedPort);
                runtime.logTimings(TIMING_PREFIX);
            } catch (Exception e) {
                Platforms.get().log("[mesh-viewer] DSL reload failed: " + e.getMessage());
                throw new IllegalStateException("Failed to execute DSL: " + resolvedDslName, e);
            }
            logTiming(runtime);
            uploadGraphOutput(result);

            if (mesh != null) {
                Platforms.get().log(
                        "[mesh-viewer] dsl reloaded: " + resolvedDslName + VERTS + mesh.vertexCount()
                                + FACES + mesh.faceCount());
            } else {
                Platforms.get().log("[mesh-viewer] dsl reload produced null mesh: " + resolvedDslName);
            }
            applyEdgeMarkOverlay(runtime);
            frameMesh(mesh);
        });
    }

    /**
     * Upload a graph's output as a whole bundle, the way a mesh file is, so a graph that ends in
     * textures (a boolean of two scans, say) draws each region with the material it kept.
     *
     * @param result the value on the graph's final port; anything but a bundle leaves no mesh
     */
    private void uploadGraphOutput(Object result) {
        meshBundle = result instanceof GeometryBundle bundle ? bundle : null;
        mesh = meshBundle == null ? null : meshBundle.mesh();
        createRuntime();
        if (meshBundle == null) {
            meshRuntime.upload(mesh);
        } else {
            meshRuntime.uploadBundle(meshBundle);
            if (meshRuntime.hasTexturedDraw()) {
                shaderMode = HalfEdgeMeshRuntime.ShaderMode.TEXTURED;
            }
            meshRuntime.setShaderMode(shaderMode);
        }
        meshRuntime.frameCamera(camera);
    }

    /**
     * Draws every boolean edge-marks label the graph left on its output bundle, so a seed loop
     * and the geodesic it tightens to are told apart on screen.
     *
     * @param runtime the graph that just ran; its last {@code geometry} output carries the marks
     */
    private void applyEdgeMarkOverlay(NodeGraphRuntime runtime) {
        showEdgeMarks(graphEdgeMarks(runtime));
    }

    /**
     * The boolean edge-mark masks a finished graph left on its output bundle, by label.
     *
     * @param runtime graph to read, or {@code null} for none
     * @return label-to-mask map, empty when the graph marked no edges
     */
    public Map<String, boolean[]> graphEdgeMarks(NodeGraphRuntime runtime) {
        Map<String, boolean[]> marksByLabel = new LinkedHashMap<>();
        if (runtime == null
                || !(runtime.lastOutput(DEFAULT_DSL_FINAL_PORT) instanceof GeometryBundle bundle)
                || !(bundle.slots().get(EdgeMarks.SLOT) instanceof Map<?, ?> marks)) {
            return marksByLabel;
        }
        for (Map.Entry<?, ?> entry : marks.entrySet()) {
            if (entry.getValue() instanceof boolean[] flags) {
                marksByLabel.put(String.valueOf(entry.getKey()), flags);
            }
        }
        return marksByLabel;
    }

    /**
     * Draws each named per-edge mask as its own coloured feature-edge overlay, taking colours in
     * sorted label order so a label keeps its colour between runs.
     *
     * @param marksByLabel edge-id-indexed masks by label; empty leaves the view untouched
     */
    public void showEdgeMarks(Map<String, boolean[]> marksByLabel) {
        MeshTopology marked = halfEdgeSurface();
        if (meshRuntime == null || marked == null || marksByLabel.isEmpty()) {
            return;
        }
        List<String> labels = new ArrayList<>(marksByLabel.keySet());
        labels.sort(String::compareTo);
        Map<Integer, Integer> denseVertexIndex = new HashMap<>();
        for (int index = 0; index < marked.vertexCount(); index++) {
            denseVertexIndex.put(marked.vertexIdAt(index), index);
        }
        List<HalfEdgeMeshRuntime.FeatureEdgeCategory> categories = new ArrayList<>();
        for (int slot = 0; slot < labels.size(); slot++) {
            boolean[] flags = marksByLabel.get(labels.get(slot));
            List<Long> edgeKeys = new ArrayList<>();
            for (int index = 0; index < marked.edgeCount(); index++) {
                int edgeId = marked.edgeIdAt(index);
                if (edgeId >= flags.length || !flags[edgeId]) {
                    continue;
                }
                int halfEdge = marked.edgeHalfEdge(edgeId);
                Integer tail = denseVertexIndex.get(marked.halfEdgeVertex(halfEdge));
                Integer head = denseVertexIndex.get(marked.halfEdgeEndVertex(halfEdge));
                if (tail != null && head != null) {
                    edgeKeys.add(EdgeKey.undirected(tail, head));
                }
            }
            categories.add(new HalfEdgeMeshRuntime.FeatureEdgeCategory(
                    EDGE_MARK_COLORS[slot % EDGE_MARK_COLORS.length], edgeKeys));
        }
        meshRuntime.setShaderMode(HalfEdgeMeshRuntime.ShaderMode.STAGES);
        meshRuntime.setFeatureEdgeOverlay(categories);
        Platforms.get().log("[mesh-viewer] edge-mark overlay: " + labels);
    }

    /**
     * Current mesh from the DSL graph, or null before async load completes.
     *
     * @return current mesh, or {@code null} if not yet loaded
     */
    public MeshTopology getMesh() {
        return mesh;
    }

    /**
     * Reference mesh loaded as a semi-transparent overlay, in the same world space as the mesh.
     *
     * @return the overlay mesh, or {@code null} when no overlay is loaded
     */
    public MeshTopology getOverlayMesh() {
        return overlayMesh;
    }

    /**
     * The bundle carrying the current mesh's named slots — tags, edge marks — whether it arrived
     * from a mesh file or from the DSL graph's final output.
     *
     * @return the geometry bundle, or {@code null} when nothing is loaded
     */
    public GeometryBundle getGeometryBundle() {
        if (meshBundle != null) {
            return meshBundle;
        }
        if (lastGraphRuntime != null
                && lastGraphRuntime.lastOutput(DEFAULT_DSL_FINAL_PORT) instanceof GeometryBundle bundle) {
            return bundle;
        }
        return null;
    }

    /**
     * The surface an edge-indexed overlay walks: the graph's own mesh, or a cached half-edge copy
     * when a mesh file loaded as an {@link ArrayMesh} with no edge adjacency.
     *
     * @return the surface with edge adjacency, or {@code null} if no mesh is loaded
     */
    public MeshTopology halfEdgeSurface() {
        if (mesh == null || mesh.edgeCount() > 0) {
            return mesh;
        }
        if (halfEdgeSurfaceSource != mesh && mesh instanceof ArrayMesh arrayMesh) {
            halfEdgeSurfaceSource = mesh;
            halfEdgeSurface = HalfEdgeMeshEngine.buildFromIndexedMesh(arrayMesh.copyPositions(),
                    arrayMesh.copyFaceIndices());
            Platforms.get().log("[mesh-viewer] half-edge copy with "
                    + halfEdgeSurface.edgeCount() + " edges");
        }
        return halfEdgeSurface == null ? mesh : halfEdgeSurface;
    }

    /**
     * The runtime the surface is drawn through, which the viewer replaces on every model change,
     * so every overlay and the pick buffer follow the live mesh.
     *
     * @return the live mesh runtime, or {@code null} before one is created
     */
    @Override
    public HalfEdgeMeshRuntime surfaceRuntime() {
        return meshRuntime;
    }

    /**
     * The DSL file backing this view: the file the last graph was read from, so an edit lands in
     * the graph the viewer is actually showing and a reload reads it back.
     *
     * @return the working graph's path, or {@code null} when no DSL file backs the view
     */
    public String workingDslPath() {
        if (lastGraphRuntime == null) {
            return null;
        }
        if (loadedDslFile != null) {
            return loadedDslFile;
        }
        return dslResource == null ? null : dslResourceFile(dslResource);
    }

    /**
     * Hands a graph's text to {@code onLoaded}, read from its tracked working copy when one exists
     * on disk so a reload shows the statements written into it, and from the packaged resource
     * otherwise, which is the only copy the web build has.
     *
     * @param resourceName graph's path below the {@code dsl} resource folder
     * @param onLoaded     receives the graph text
     */
    private void loadDslSource(String resourceName, Consumer<String> onLoaded) {
        String working = dslResourceFile(resourceName);
        if (working != null) {
            try {
                String source = Files.readString(Path.of(working));
                loadedDslFile = working;
                onLoaded.accept(source);
                return;
            } catch (IOException failure) {
                Platforms.get().log("[mesh-viewer] could not read " + working
                        + ", using the packaged copy: " + failure.getMessage());
            }
        }
        loadedDslFile = null;
        Platforms.get().loadSourceAsync(DSL_FOLDER, resourceName, Platforms.gl().getPlatformID(),
                onLoaded);
    }

    /**
     * The tracked file a DSL resource name lives in, preferred over the build output because that
     * is the copy a written statement persists into.
     *
     * @param resourceName graph's path below the {@code dsl} resource folder
     * @return the resolved path, or {@code null} when neither copy exists
     */
    private static String dslResourceFile(String resourceName) {
        Path inWorkingDirectory = Path.of(DSL_RESOURCE_DIRECTORY, resourceName);
        if (Files.exists(inWorkingDirectory)) {
            return inWorkingDirectory.toString();
        }
        Path inModule = Path.of(MeshLoader.MODULE_DIRECTORY, DSL_RESOURCE_DIRECTORY, resourceName);
        return Files.exists(inModule) ? inModule.toString() : null;
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
    public void setControls() {
        controls.add(new ControlHint(Keys.LEFT_BRACKET, "[", "previous model", this::prevModel));
        controls.add(new ControlHint(Keys.RIGHT_BRACKET, "]", "next model", this::nextModel));
        controls.add(new ControlHint(Keys.Z, "Z", "toggle wireframe", this::toggleMeshWireframe));
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
        if (entry.kind == ModelChoice.Kind.COLLECTION) {
            openCollection(Path.of(entry.path));
            Platforms.get().log("[mesh-viewer] collection " + modelCollection.name + ": "
                    + modelCollection.memberCount() + " members, " + modelCollection.keptCount()
                    + " kept, manifest " + modelCollection.manifestPath);
            loadModelEntry(modelCatalog.select(0));
            return;
        }
        // Invalidate any cached decomposition — the mesh is changing.
        cachedDiagnostics = null;
        patchOverlayEnabled = false;
        currentModelKey = entry.path;
        currentModelDisplayName = entry.displayName;
        Platforms.get().log("[mesh-viewer] loading " + entry.displayName);
        preserveOrbit(() -> {
            switch (entry.kind) {
                case DSL -> loadDslFromAbsolutePath(entry.path);
                case MESH_FILE -> loadMeshFileFromAbsolutePath(entry.path);
                default -> { }
            }
            return true;
        });
        logState();
    }

    private void loadDslFromAbsolutePath(String absolutePath) {
        // loadDsl() expects a resource-relative name, but the staging dir
        // contains symlinks — read the file directly and execute the graph.
        String file = workingCopyOf(absolutePath);
        try {
            String dslCode = new String(Files.readAllBytes(Path.of(file)));
            loadedDslFile = file;
            disposeMeshRuntime();
            meshBundle = null;
            NodeGraphRuntime runtime = NodeGraphRuntime.fromSource(dslCode);
            List<PythonParser.ParsedNode> ast = runtime.statements;
            lastGraphRuntime = runtime;
            String resolvedNode = ast.get(ast.size() - 1).id;
            Object result = runtime.executeGraphResult(ast, resolvedNode, DEFAULT_DSL_FINAL_PORT);
            runtime.logTimings(TIMING_PREFIX);
            uploadGraphOutput(result);
            if (mesh != null) {
                Platforms.get().log("[mesh-viewer] dsl loaded: " + file + VERTS + mesh.vertexCount()
                        + FACES + mesh.faceCount());
            }
            applyEdgeMarkOverlay(runtime);
            frameMesh(mesh);
        } catch (Exception e) {
            Platforms.get().log("[mesh-viewer] DSL load failed for " + file + ": " + e.getMessage());
        }
    }

    /**
     * The tracked source a DSL path corresponds to: a graph named under {@code target/classes} is
     * loaded from its {@code src/main/resources} copy, so a reload reads what a statement was
     * written
     * into.
     *
     * @param path path the caller asked for
     * @return the working copy when one exists, otherwise the path unchanged
     */
    private static String workingCopyOf(String path) {
        String normalized = path.replace('\\', '/');
        int build = normalized.indexOf(DSL_BUILD_DIRECTORY);
        int name = build + DSL_BUILD_DIRECTORY.length() + 1;
        if (build < 0 || name >= normalized.length()) {
            return path;
        }
        String resourceName = normalized.substring(name);
        String working = dslResourceFile(resourceName);
        if (working == null) {
            return path;
        }
        Path beside = Path.of(normalized.substring(0, build), DSL_RESOURCE_DIRECTORY, resourceName);
        return Files.exists(beside) ? beside.toString() : working;
    }

    /**
     * Load a mesh file with the attributes its format carries (glTF texture coordinates and
     * material), upload the whole bundle, and switch to {@link HalfEdgeMeshRuntime.ShaderMode#TEXTURED}
     * when a base-colour texture came with it.
     *
     * @param absolutePath resolved path of the mesh file
     */
    private void loadMeshFileFromAbsolutePath(String absolutePath) {
        try {
            boolean cached = modelCollection != null && modelCollection.isBundleCached(absolutePath);
            GeometryBundle bundle = modelCollection == null
                    ? MeshLoader.loadBundle(absolutePath)
                    : modelCollection.loadBundle(absolutePath);
            disposeMeshRuntime();
            meshBundle = bundle;
            mesh = bundle.mesh();
            createRuntime();
            meshRuntime.uploadBundle(bundle);
            if (meshRuntime.hasTexturedDraw()) {
                shaderMode = HalfEdgeMeshRuntime.ShaderMode.TEXTURED;
            }
            meshRuntime.setShaderMode(shaderMode);
            meshRuntime.frameCamera(camera);
            noteMemberLoaded(absolutePath, mesh.vertexCount(), mesh.faceCount());
            Platforms.get().log("[mesh-viewer] mesh loaded: " + absolutePath
                    + VERTS + mesh.vertexCount() + FACES + mesh.faceCount()
                    + "  slots=" + bundleSlotNames()
                    + (cached ? " (cached)" : ""));
            frameMesh(mesh);
        } catch (Exception e) {
            Platforms.get().log("[mesh-viewer] mesh load failed for " + absolutePath + ": " + e.getMessage());
        }
    }

    /**
     * Slot names on the loaded bundle, sorted so the automation snapshot is byte-stable.
     *
     * @return comma-separated slot names, empty when no bundle is loaded or it has no slots
     */
    public String bundleSlotNames() {
        if (meshBundle == null) {
            return "";
        }
        return meshBundle.slots().keySet().stream().sorted().collect(Collectors.joining(","));
    }

    /**
     * Name of the active shading mode, for the automation state snapshot.
     *
     * @return the {@link HalfEdgeMeshRuntime.ShaderMode} the viewer is drawing with
     */
    public String getShaderModeName() {
        return shaderMode.name();
    }

    /**
     * Whether the runtime can draw the mesh textured: a material and a UV field both uploaded.
     *
     * @return true once TEXTURED mode would sample the file's own texture
     */
    public boolean hasTexturedDraw() {
        return meshRuntime != null && meshRuntime.hasTexturedDraw();
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
