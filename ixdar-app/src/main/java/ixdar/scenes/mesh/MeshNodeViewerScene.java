package ixdar.scenes.mesh;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.joml.Vector3f;

import ixdar.annotations.command.Subsystem;
import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.data.representation.EdgeKey;
import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.analysis.segmentation.SemanticPatchDecomposer;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.io.ObjMeshParser;
import ixdar.geometry.mesh.data.ops.MeshRepairReport;
import ixdar.geometry.mesh.data.paths.query.SurfacePicker;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.Palettes;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.models.ModelCatalog;
import ixdar.models.ModelChoice;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.input.Keys;
import ixdar.scenes.model.ModelScene;
import ixdar.tools.ControlHint;

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
    public static final float PIXEL_CENTRE = 0.5f;

    private static final String DSL_FOLDER = "dsl";
    private static final String DEFAULT_DSL_RESOURCE = "skull.dsl";
    private static final String DEFAULT_DSL_FINAL_NODE = "";
    private static final String DEFAULT_DSL_FINAL_PORT = "geometry";

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

    private HalfEdgeMeshRuntime overlayRuntime;
    private MeshTopology overlayMesh;

    /** File the last graph was read from, which is also the file an edit is written back into. */
    private String loadedDslFile;

    private SemanticPatchDecomposer.DecompositionDiagnostics cachedDiagnostics;
    private boolean patchOverlayEnabled = false;
    private HalfEdgeMeshRuntime.ShaderMode shaderMode = HalfEdgeMeshRuntime.ShaderMode.LAMBERT;
    private DecomposerKind activeDecomposer = DecomposerKind.SEMANTIC;

    /**
     * Default constructor: the model comes from {@code -Dixdar.model} (a catalog name, a mesh or
     * a {@code .dsl} graph), falling back to the built-in default graph.
     */
    public MeshNodeViewerScene() {
        this(DEFAULT_DSL_RESOURCE, DEFAULT_DSL_FINAL_NODE, DEFAULT_DSL_FINAL_PORT);
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
        ModelChoice current = currentModel();
        sb.append("model=").append(current == null ? "(initial)" : current.displayName);
        sb.append(PATCHES).append(patchOverlayEnabled ? ON : OFF);
        sb.append("  shader=").append(shaderMode.name());
        sb.append("  texture=").append(texturedDraw() ? ON : OFF);
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
        if (modelCollection.hasManifest()) {
            sb.append("  collection=").append(modelCollection.name)
                    .append(" kept=").append(modelCollection.keptCount())
                    .append('/').append(modelCollection.memberCount());
            int member = currentMemberIndex();
            if (member >= 0) {
                sb.append("  member=").append(modelCollection.members[member].displayName)
                        .append(modelCollection.memberKeep[member] ? " KEEP" : " REJECT");
            }
        }
        Platforms.get().log(sb.toString());
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
     * List the repo's DSL graphs, then the staging directory's entries, put the cursor on the
     * graph this scene was launched with, and log the catalog state. The web build has no folder
     * to walk, so its list stays empty.
     */
    @Override
    public void createCatalog() {
        modelCatalog = ModelCatalog.repoGraphsAndStaging(ModelCatalog.repoDslRoot(),
                ModelCatalog.stagingRoot());
        ModelChoice launched = dslResource == null ? null : modelCatalog.select(
                modelCatalog.indexOfFile(ModelCatalog.packagedGraph(dslResource).path));
        if (launched != null) {
            modelCollection = modelCollection.open(launched);
        }
        int catalogSize = modelCatalog.choices.size();
        Platforms.get().log(
                "[mesh-viewer] model catalog: " + catalogSize + " entries from " + modelCatalog.root
                        + " and " + ModelCatalog.stagingRoot());
        if (catalogSize > 0) {
            Platforms.get().log("[mesh-viewer] cycle models with [ and ]; P = patch overlay; "
                    + "Shift+P cycles shader mode "
                    + "(LAMBERT \u2192 FLAT \u2192 STAGES \u2192 CREST_VS_BOUNDARY \u2192 SCALAR/Coons-error \u2192 MSC); "
                    + "D toggles decomposer (SEMANTIC \u2194 MORSE_SMALE)");
        }
        logState();
    }

    /** Hide the main menu, then wire the orbit input and activate the default tool as every model scene does. */
    @Override
    public void initInput() {
        MenuBox.menuVisible = false;
        super.initInput();
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
        String requested = System.getProperty(COMMON_MODEL_PROPERTY);
        if (requested != null && !requested.isBlank()) {
            loadModelEntry(ModelCatalog.launchChoice(requested, modelCatalog));
            return;
        }
        ModelChoice defaultGraph = ModelCatalog.packagedGraph(dslResource);
        if (defaultGraph.path != null) {
            loadModelEntry(defaultGraph);
            return;
        }
        try {
            loadDslSource(defaultGraph, dslCode -> {
                Object result;
                try {
                    result = executeViewerGraph(dslCode, dslFinalNode, dslFinalPort);
                } catch (Exception e) {
                    for (Throwable t = e; t != null; t = t.getCause()) {
                        Platforms.get().log("[mesh-viewer] " + t.getClass().getName() + ": " + t.getMessage());
                    }
                    throw new IllegalStateException(
                            "Failed to execute graph: dsl=" + dslResource + " finalNode=" + dslFinalNode
                                    + " port=" + dslFinalPort,
                            e);
                }
                uploadGraphOutput(result);
                if (mesh != null) {
                    Platforms.get().log(
                            "[mesh-viewer] mesh ready " + dslResource + VERTS + mesh.vertexCount() + FACES
                                    + mesh.faceCount());
                } else {
                    Platforms.get().log("[mesh-viewer] mesh is null for " + dslResource);
                }
                applyEdgeMarkOverlay(graph);
                frameMesh(mesh);
            });

        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize mesh viewer runtime", e);
        }
    }

    private void initObjViewer() {
        try {
            Platforms.get().loadSourceAsync("obj", objResource, Platforms.gl().getPlatformID(), objText -> {
                MeshTopology objMesh = ObjMeshParser.load(objText);
                createRuntime();
                meshRuntime.upload(objMesh);
                meshRuntime.frameCamera(camera);
                Platforms.get().log("[mesh-viewer] OBJ loaded: " + objResource
                        + VERTS + objMesh.vertexCount() + FACES + objMesh.faceCount());
                frameMesh(objMesh.vertexCount() > 0 ? objMesh : null);
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
     * Load a requested switch through {@link #loadModelEntry}, which is how this viewer loads DSL
     * graphs, mesh files and collections alike, moving the catalog cursor onto it when listed.
     *
     * @param choice the requested model
     */
    @Override
    public void loadModelOrGraph(ModelChoice choice) {
        modelCatalog.select(modelCatalog.indexOfPath(choice.path));
        loadModelEntry(choice);
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
        return mesh == null ? 0 : mesh.boundaryEdgeCount();
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
        if (graph == null
                || !(graph.lastOutput(DEFAULT_DSL_FINAL_PORT)
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
        return mesh == null ? 0 : mesh.degenerateFaceCount();
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
            MeshTopology refMesh = MeshLoader.load(objPath);
            overlayMesh = refMesh;
            overlayRuntime = new HalfEdgeMeshRuntime();
            overlayRuntime.upload(refMesh);
            overlayRuntime.setSolidColor(Color.BLUE_GREY.toVector4f());
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
        loadDslSource(ModelCatalog.packagedGraph(resolvedDslName), dslCode -> {
            Object result;
            try {
                result = executeViewerGraph(dslCode, finalNode, resolvedPort);
            } catch (Exception e) {
                Platforms.get().log("[mesh-viewer] DSL reload failed: " + e.getMessage());
                throw new IllegalStateException("Failed to execute DSL: " + resolvedDslName, e);
            }
            uploadGraphOutput(result);

            if (mesh != null) {
                Platforms.get().log(
                        "[mesh-viewer] dsl reloaded: " + resolvedDslName + VERTS + mesh.vertexCount()
                                + FACES + mesh.faceCount());
            } else {
                Platforms.get().log("[mesh-viewer] dsl reload produced null mesh: " + resolvedDslName);
            }
            applyEdgeMarkOverlay(graph);
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
                    Palettes.EDGE_MARK.color(slot), edgeKeys));
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
        if (graph != null
                && graph.lastOutput(DEFAULT_DSL_FINAL_PORT) instanceof GeometryBundle bundle) {
            return bundle;
        }
        return null;
    }

    /**
     * The surface an edge-indexed overlay walks: the graph's own mesh, whose edge ids the overlays
     * index.
     *
     * @return the surface, or {@code null} if no mesh is loaded
     */
    public MeshTopology halfEdgeSurface() {
        return mesh;
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
     * Why {@link #pickPixel} and {@link #projectPoint} cannot answer for the view shown now.
     *
     * @return the reason, or empty when the shown surface can be picked
     */
    public String pickRefusal() {
        MeshTopology surface = halfEdgeSurface();
        return surfaceRuntime() == null || surface == null || surface.faceCount() == 0
                ? "no mesh is loaded" : "";
    }

    /**
     * The surface under a framebuffer pixel as the viewer draws it, touching no tool: the nearest
     * face the view ray through the pixel's centre crosses.
     *
     * @param framebufferX pixel x from the left, whole numbers at pixel centres
     * @param framebufferY pixel y from the top, whole numbers at pixel centres
     * @param picker       receives the hit face, its weights and the world point
     * @return true when the ray meets the surface
     */
    public boolean pickPixel(float framebufferX, float framebufferY, SurfacePicker picker) {
        HalfEdgeMeshRuntime runtime = surfaceRuntime();
        MeshTopology surface = halfEdgeSurface();
        picker.faceId = -1;
        float[] origin = new float[SurfacePicker.COORDINATES_PER_POINT];
        float[] direction = new float[SurfacePicker.COORDINATES_PER_POINT];
        return runtime != null && surface != null && surface.faceCount() > 0
                && runtime.rayThroughPixel(camera, framebufferX, framebufferY, origin, direction)
                && picker.pickNearestFace(surface, origin, direction);
    }

    /**
     * Where a world point lands in the framebuffer and what hides it: the surface hides the point
     * when the view ray to it crosses a face more than one pixel's span in front of it.
     *
     * @param x         world x
     * @param y         world y
     * @param z         world z
     * @param pixelDest receives framebuffer x and y, whole numbers at pixel centres as
     *                  {@link #pickPixel} takes them
     * @param occluder  receives the hit that hides the point; its face is -1 when none does
     * @return true when the point lies in front of the camera
     */
    public boolean projectPoint(float x, float y, float z, float[] pixelDest,
            SurfacePicker occluder) {
        occluder.faceId = -1;
        HalfEdgeMeshRuntime runtime = surfaceRuntime();
        MeshTopology surface = halfEdgeSurface();
        float[] projected = new float[SurfacePicker.COORDINATES_PER_POINT];
        if (runtime == null || !runtime.projectToPixels(camera, x, y, z, projected)) {
            return false;
        }
        pixelDest[0] = projected[0] - PIXEL_CENTRE;
        pixelDest[1] = projected[1] - PIXEL_CENTRE;
        float[] origin = new float[SurfacePicker.COORDINATES_PER_POINT];
        float[] direction = new float[SurfacePicker.COORDINATES_PER_POINT];
        float[] besideOrigin = new float[SurfacePicker.COORDINATES_PER_POINT];
        float[] besideDirection = new float[SurfacePicker.COORDINATES_PER_POINT];
        if (surface == null || surface.faceCount() == 0
                || !runtime.rayThroughPixel(camera, pixelDest[0], pixelDest[1], origin, direction)
                || !runtime.rayThroughPixel(camera, pixelDest[0] + 1f, pixelDest[1], besideOrigin,
                        besideDirection)
                || !occluder.pickNearestFace(surface, origin, direction)) {
            occluder.faceId = -1;
            return true;
        }
        // Both rays run near plane to far plane, so one parameter is one depth on each and the
        // gap between them there is the world span of a pixel at the point.
        Vector3f along = new Vector3f(direction[0], direction[1], direction[2]);
        float pointParameter = along.dot(x - origin[0], y - origin[1], z - origin[2])
                / along.lengthSquared();
        float pixelSpan = new Vector3f(besideOrigin[0], besideOrigin[1], besideOrigin[2])
                .fma(pointParameter, new Vector3f(besideDirection[0], besideDirection[1],
                        besideDirection[2]))
                .distance(origin[0] + pointParameter * direction[0],
                        origin[1] + pointParameter * direction[1],
                        origin[2] + pointParameter * direction[2]);
        if ((pointParameter - occluder.distanceAlongRay) * along.length() <= pixelSpan) {
            occluder.faceId = -1;
        }
        return true;
    }

    /**
     * The DSL file backing this view: the file the last graph was read from, so an edit lands in
     * the graph the viewer is actually showing and a reload reads it back.
     *
     * @return the working graph's path, or {@code null} when no DSL file backs the view
     */
    public String workingDslPath() {
        if (graph == null) {
            return null;
        }
        if (loadedDslFile != null) {
            return loadedDslFile;
        }
        return dslResource == null ? null : ModelCatalog.packagedGraph(dslResource).workingFile();
    }

    /**
     * The graph a model runs: a {@code .dsl} model is its own graph; a mesh file runs none, so an
     * override is checked against the graph that ran last.
     *
     * @param model the model, or {@code null} for the current one
     * @throws IllegalArgumentException when the model runs no graph or its file cannot be read
     * @return the parsed graph, not executed
     */
    @Override
    public NodeGraphRuntime graphFor(ModelChoice model) {
        ModelChoice choice = model != null ? model : currentModel();
        if (choice != null && choice.kind == ModelChoice.Kind.DSL) {
            try {
                return NodeGraphRuntime.fromSource(choice.readSource());
            } catch (IOException unreadable) {
                throw new IllegalArgumentException("cannot read graph " + choice.path, unreadable);
            }
        }
        if (graph != null) {
            return graph;
        }
        throw new IllegalArgumentException("the current model runs no graph");
    }

    /**
     * Hands a graph's text to {@code onLoaded}, read from its tracked working copy when one exists
     * on disk so a reload shows the statements written into it, and from the packaged resource
     * otherwise, which is the only copy the web build has.
     *
     * @param graph    the packaged graph to read
     * @param onLoaded receives the graph text
     */
    private void loadDslSource(ModelChoice graph, Consumer<String> onLoaded) {
        String working = graph.workingFile();
        if (working != null) {
            try {
                String source = graph.readSource();
                loadedDslFile = working;
                onLoaded.accept(source);
                return;
            } catch (IOException failure) {
                Platforms.get().log("[mesh-viewer] could not read " + working
                        + ", using the packaged copy: " + failure.getMessage());
            }
        }
        loadedDslFile = null;
        graph.loadPackagedSource(onLoaded);
    }

    /**
     * The viewer shows a mesh: the bundle it last uploaded, whose mesh, slots, edge marks and
     * repair report describe it.
     *
     * @param subsystem a scene-held subsystem
     * @return the shown bundle for {@link Subsystem#MESH}, null before one, else the model scene's
     */
    @Override
    public Object dataset(Subsystem subsystem) {
        return subsystem == Subsystem.MESH ? meshBundle : super.dataset(subsystem);
    }

    @Override
    public void setControls(List<ControlHint> controls) {
        controls.add(new ControlHint("previous-model", Keys.LEFT_BRACKET, "[", "previous model", this::prevModel));
        controls.add(new ControlHint("next-model", Keys.RIGHT_BRACKET, "]", "next model", this::nextModel));
        controls.add(new ControlHint("toggle-wireframe", Keys.Z, "Z", "toggle wireframe", this::toggleMeshWireframe));
        super.setControls(controls);
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
            openCollection(entry);
            Platforms.get().log("[mesh-viewer] collection " + modelCollection.name + ": "
                    + modelCollection.memberCount() + " members, " + modelCollection.keptCount()
                    + " kept, manifest " + modelCollection.manifestPath);
            loadModelEntry(modelCatalog.select(0));
            return;
        }
        // Invalidate any cached decomposition — the mesh is changing.
        cachedDiagnostics = null;
        patchOverlayEnabled = false;
        modelCollection = modelCollection.open(entry);
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
        // contains symlinks — read the choice's file directly and execute the graph.
        ModelChoice choice = new ModelChoice(absolutePath, absolutePath, ModelChoice.Kind.DSL);
        String file = choice.workingFile();
        try {
            String dslCode = choice.readSource();
            loadedDslFile = file;
            disposeMeshRuntime();
            meshBundle = null;
            uploadGraphOutput(executeViewerGraph(dslCode, DEFAULT_DSL_FINAL_NODE,
                    DEFAULT_DSL_FINAL_PORT));
            if (mesh != null) {
                noteMemberLoaded(absolutePath, mesh.vertexCount(), mesh.faceCount());
                Platforms.get().log("[mesh-viewer] dsl loaded: " + file + VERTS + mesh.vertexCount()
                        + FACES + mesh.faceCount());
            }
            applyEdgeMarkOverlay(graph);
            frameMesh(mesh);
        } catch (Exception e) {
            Platforms.get().log("[mesh-viewer] DSL load failed for " + file + ": " + e.getMessage());
        }
    }

    /**
     * Executes a viewer graph as the scene's {@link #graph} with the overrides the collection gives
     * the current model, logs its timing, and returns the value on the chosen output.
     *
     * @param dslCode   graph source
     * @param finalNode id of the node whose output is shown, or empty for the last statement
     * @param finalPort output port of that node
     * @throws Exception if an override is invalid or a node fails
     * @return the value on that port, a bundle for a mesh-producing graph
     */
    private Object executeViewerGraph(String dslCode, String finalNode, String finalPort)
            throws Exception {
        NodeGraphRuntime run = NodeGraphRuntime.fromSource(dslCode);
        graph = run;
        String resolvedNode = finalNode != null && !finalNode.isEmpty() ? finalNode : run.lastId();
        ModelChoice current = currentModel();
        Object result = run.executeGraphResult(resolvedNode, finalPort, current == null
                ? modelCollection.overrides : modelCollection.overridesFor(current));
        run.logTimings(TIMING_PREFIX);
        return result;
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
            boolean cached = modelCollection.isBundleCached(absolutePath);
            GeometryBundle bundle = modelCollection.loadBundle(absolutePath);
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
     * Name of the active shading mode.
     *
     * @return the {@link HalfEdgeMeshRuntime.ShaderMode} the viewer is drawing with
     */
    @Override
    public String shaderModeName() {
        return shaderMode.name();
    }

    /**
     * Whether the runtime can draw the mesh textured: a material and a UV field both uploaded.
     *
     * @return true once TEXTURED mode would sample the file's own texture
     */
    @Override
    public boolean texturedDraw() {
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
