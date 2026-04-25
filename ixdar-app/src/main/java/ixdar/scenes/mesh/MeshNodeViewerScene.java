package ixdar.scenes.mesh;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;
import org.joml.Vector4f;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.MeshLoader;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.FeatureEdgeColors;
import ixdar.geometry.mesh.data.Patch;
import ixdar.geometry.mesh.data.PatchDecomposition;
import ixdar.geometry.mesh.data.PatchRenderer;
import ixdar.geometry.mesh.data.SemanticPatchDecomposer;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.input.OrbitMouseTrap;
import ixdar.scenes.Scene;

@SceneAnnotation(id = "mesh-viewer")
public class MeshNodeViewerScene extends Scene {
    private static final String DSL_FOLDER = "dsl";
    private static final String DEFAULT_DSL_RESOURCE = "skull.dsl";
    private static final String DEFAULT_DSL_FINAL_NODE = "";
    private static final String DEFAULT_DSL_FINAL_PORT = "geometry";

    private static final float HALF_EXTENT = 0.5f;
    private static final float CAMERA_AZIMUTH = (float) Math.toRadians(45.0);
    private static final float CAMERA_ELEVATION = (float) Math.toRadians(24.0);
    private static final float CAMERA_DISTANCE = 3.5f;

    private final String dslResource;
    private final String dslFinalNode;
    private final String dslFinalPort;

    /** When non-null, load this OBJ file instead of executing a DSL graph. */
    private final String objResource;

    private final Vector3f meshCenter = new Vector3f();

    private OrbitMouseTrap orbitMouse;
    private MeshTopology mesh;
    private volatile HalfEdgeMeshRuntime meshRuntime;
    private HalfEdgeMeshRuntime overlayRuntime;
    private NodeGraphRuntime lastGraphRuntime;

    // VIEW-7: catalog + per-mesh decomposition cache + overlay state
    private ModelCatalog modelCatalog;
    private String currentModelKey;  // absolutePath for staging-dir entries, or "" for initial load
    private String currentModelDisplayName = "(initial)";
    private SemanticPatchDecomposer.DecompositionDiagnostics cachedDiagnostics;
    private String cachedDiagnosticsKey;
    private boolean patchOverlayEnabled = false;
    private HalfEdgeMeshRuntime.ShaderMode shaderMode = HalfEdgeMeshRuntime.ShaderMode.LAMBERT;

    /**
     * PATCH-26: which decomposer's output drives the patch overlay. Both
     * pipelines coexist; D toggles. SEMANTIC defaults — the decomposer
     * the project shipped before MSC.
     */
    public enum DecomposerKind { SEMANTIC, MORSE_SMALE }
    private DecomposerKind activeDecomposer = DecomposerKind.SEMANTIC;

    /**
     * Log a full state string to the terminal whenever the viewer state
     * changes (model load, overlay toggle, shader mode toggle). macOS
     * absolutely refuses glfwSetWindowTitle from any thread other than the
     * actual OS main thread and even deferring to drawScene wasn't enough —
     * drawScene is called off-thread in this platform wiring. Terminal
     * log is thread-safe and user-visible. Proper pixel-space HUD is VIEW-8.
     */
    private void logState() {
        StringBuilder sb = new StringBuilder("[mesh-viewer] STATE ");
        sb.append("model=").append(currentModelDisplayName);
        sb.append("  patches=").append(patchOverlayEnabled ? "ON" : "OFF");
        if (patchOverlayEnabled) {
            sb.append("  mode=").append(shaderMode.name());
            sb.append("  decomposer=").append(activeDecomposer.name());
            if (cachedDiagnostics != null) {
                sb.append("  patches=").append(cachedDiagnostics.decomposition().patches().size());
            }
        }
        if (modelCatalog != null && !modelCatalog.entries().isEmpty()) {
            sb.append("  [").append(modelCatalog.currentIndex() + 1)
              .append('/').append(modelCatalog.entries().size()).append(']');
        }
        Platforms.get().log(sb.toString());
    }

    /** Returns the NodeGraphRuntime from the most recent DSL execution (for timing data). */
    public NodeGraphRuntime getLastGraphRuntime() {
        return lastGraphRuntime;
    }

    public OrbitMouseTrap getOrbitMouse() {
        return orbitMouse;
    }

    public MeshNodeViewerScene() {
        this(
                sysPropOrDefault("ixdar.mesh.dsl", DEFAULT_DSL_RESOURCE),
                sysPropOrDefault("ixdar.mesh.node", DEFAULT_DSL_FINAL_NODE),
                sysPropOrDefault("ixdar.mesh.port", DEFAULT_DSL_FINAL_PORT));
    }

    private static String sysPropOrDefault(String key, String fallback) {
        String v = System.getProperty(key);
        return (v != null && !v.isEmpty()) ? v : fallback;
    }

    public MeshNodeViewerScene(String dslResource, String dslFinalNode, String dslFinalPort) {
        this.dslResource = dslResource.endsWith(".dsl") ? dslResource : dslResource + ".dsl";
        this.dslFinalNode = dslFinalNode;
        this.dslFinalPort = dslFinalPort;
        this.objResource = null;
    }

    /** Create an OBJ viewer (no DSL execution, just loads and displays an OBJ file). */
    public static MeshNodeViewerScene forObj(String objFilename) {
        return new MeshNodeViewerScene(objFilename, true);
    }

    private MeshNodeViewerScene(String objFilename, boolean objMode) {
        this.dslResource = null;
        this.dslFinalNode = null;
        this.dslFinalPort = null;
        this.objResource = objFilename;
    }

    @Override
    public void initGL() {
        super.initGL();
        Platforms.gl().setWindowTitle("Ixdar : Mesh Node Viewer");
        MenuBox.menuVisible = false;
        keys = new MeshViewerKeyGuy(this, camera, this);
        orbitMouse = new OrbitMouseTrap(camera, this);
        orbitMouse.setTarget(meshCenter);
        orbitMouse.setOrbit(CAMERA_AZIMUTH, CAMERA_ELEVATION, CAMERA_DISTANCE);
        mouse = orbitMouse;
        bindAutomationIfAvailable(Platforms.get(), keys, mouse);
        // Fallback: if reflection-based binding failed (TeaVM), wire callbacks directly
        bindInputDirect(Platforms.get(), keys, mouse);

        // VIEW-7: scan the staging directory for selectable models.
        modelCatalog = new ModelCatalog();
        int catalogSize = modelCatalog.entries().size();
        Platforms.get().log(
                "[mesh-viewer] model catalog: " + catalogSize + " entries in " + modelCatalog.root()
                        + " (populate via 'uv run sync-models')");
        if (catalogSize > 0) {
            Platforms.get().log("[mesh-viewer] cycle models with [ and ]; P = patch overlay; "
                    + "Shift+P cycles shader mode "
                    + "(LAMBERT \u2192 FLAT \u2192 STAGES \u2192 CREST_VS_BOUNDARY \u2192 SCALAR/Coons-error \u2192 MSC); "
                    + "D toggles decomposer (SEMANTIC \u2194 MORSE_SMALE)");
        }
        logState();

        if (objResource != null) {
            initObjViewer();
            return;
        }

        try {
            Platforms.get().loadSourceAsync(DSL_FOLDER, dslResource, Platforms.gl().getPlatformID(), dslCode -> {
                PythonLexer lexer = new PythonLexer(dslCode);
                PythonParser parser = new PythonParser(lexer);
                List<PythonParser.ParsedNode> ast = parser.parseGraph();

                NodeGraphRuntime runtime = new NodeGraphRuntime();
                runtime.registerAllFromAnnotationRegistry();
                runtime.registerFunctionDefs(parser.functionDefs());
                lastGraphRuntime = runtime;
                // If no final node specified, use the last node in the graph
                String resolvedNode = (dslFinalNode != null && !dslFinalNode.isEmpty())
                        ? dslFinalNode
                        : ast.get(ast.size() - 1).id;
                try {
                    mesh = runtime.executeGraphToMesh(ast, resolvedNode, dslFinalPort);
                } catch (Exception e) {
                    for (Throwable t = e; t != null; t = t.getCause()) {
                        Platforms.get().log("[mesh-viewer] " + t.getClass().getName() + ": " + t.getMessage());
                    }
                    throw new IllegalStateException(
                            "Failed to execute graph: dsl=" + dslResource + " finalNode=" + dslFinalNode
                                    + " port=" + dslFinalPort,
                            e);
                }
                logTiming(runtime);
                try {
                    meshRuntime = new HalfEdgeMeshRuntime();
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to create mesh GL runtime", e);
                }
                meshRuntime.upload(mesh);
                meshRuntime.frameCamera(camera);
                if (mesh != null) {
                    Platforms.get().log(
                            "[mesh-viewer] mesh ready " + dslResource + " verts=" + mesh.vertexCount() + " faces="
                                    + mesh.faceCount());
                } else {
                    Platforms.get().log("[mesh-viewer] mesh is null for " + dslResource);
                }
                if (mesh != null) {
                    meshCenter.set(mesh.center(new Vector3f()));
                } else {
                    meshCenter.set(0f, 0f, 0f);
                }
                if (orbitMouse != null) {
                    orbitMouse.setTarget(meshCenter);
                    float orbitDist = mesh != null ? Math.max(1.5f, mesh.radius() * 2.5f) : CAMERA_DISTANCE;
                    orbitMouse.setOrbit(CAMERA_AZIMUTH, CAMERA_ELEVATION, orbitDist);
                }
            });

        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize mesh viewer runtime", e);
        }
    }

    private void initObjViewer() {
        try {
            Platforms.get().loadSourceAsync("obj", objResource, Platforms.gl().getPlatformID(), objText -> {
                ArrayMesh arrayMesh = MeshLoader.parseObj(objText);
                try {
                    meshRuntime = new HalfEdgeMeshRuntime();
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to create mesh GL runtime for OBJ", e);
                }
                meshRuntime.upload(arrayMesh);
                meshRuntime.frameCamera(camera);
                Platforms.get().log("[mesh-viewer] OBJ loaded: " + objResource
                        + " verts=" + arrayMesh.vertexCount() + " faces=" + arrayMesh.faceCount());
                if (arrayMesh.vertexCount() > 0) {
                    meshCenter.set(arrayMesh.center(new Vector3f()));
                } else {
                    meshCenter.set(0f, 0f, 0f);
                }
                if (orbitMouse != null) {
                    orbitMouse.setTarget(meshCenter);
                    orbitMouse.setOrbit(CAMERA_AZIMUTH, CAMERA_ELEVATION, CAMERA_DISTANCE);
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load OBJ: " + objResource, e);
        }
    }

    @Override
    public void drawScene() {
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
    public void activate(boolean state) {
        super.activate(state);
        if (!state) {
            disposeMeshRuntime();
        }
    }

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
        if (slow == 0) sb.append(" (all nodes <1ms)");
        Platforms.get().log(sb.toString());
    }

    public int getMeshVertexCount() {
        return mesh == null ? 0 : mesh.vertexCount();
    }

    public int getMeshFaceCount() {
        return mesh == null ? 0 : mesh.faceCount();
    }

    public int getMeshEdgeCount() {
        return mesh == null ? 0 : mesh.edgeCount();
    }

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

    public int getMeshEulerCharacteristic() {
        return mesh == null ? 0 : mesh.vertexCount() - mesh.edgeCount() + mesh.faceCount();
    }

    public boolean isMeshClosed() {
        return mesh != null && getMeshBoundaryEdgeCount() == 0;
    }

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
            if (mesh.faceVertexCount(faceId) < 3) {
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

    public float getMeshRadius() {
        return mesh == null ? 0f : mesh.radius();
    }

    public Vector3f getMeshCenter() {
        return mesh == null ? new Vector3f() : mesh.center(new Vector3f());
    }

    public Vector3f getBoundingBoxMin() {
        return mesh == null ? new Vector3f(-HALF_EXTENT, -HALF_EXTENT, -HALF_EXTENT) : mesh.boundsMin(new Vector3f());
    }

    public Vector3f getBoundingBoxMax() {
        return mesh == null ? new Vector3f(HALF_EXTENT, HALF_EXTENT, HALF_EXTENT) : mesh.boundsMax(new Vector3f());
    }

    /** Load a reference OBJ file as a semi-transparent overlay. */
    public void loadOverlay(String objPath) {
        disposeOverlay();
        try {
            ArrayMesh refMesh = MeshLoader.load(objPath);
            overlayRuntime = new HalfEdgeMeshRuntime();
            overlayRuntime.upload(refMesh);
            overlayRuntime.setSolidColor(1.0f, 0.6f, 0.2f, 0.35f);
            Platforms.get().log("[mesh-viewer] overlay loaded: " + objPath
                    + " verts=" + refMesh.vertexCount() + " faces=" + refMesh.faceCount());
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
     * Reload the viewer with a different DSL file at runtime.
     * Disposes existing mesh, parses and executes the new DSL, uploads to GPU.
     * Must be called on the render thread (via AutomationRuntime.runOnMainThread).
     *
     * @param dslName DSL filename without path (e.g. "test_finger.dsl" or "test_finger")
     * @param finalNode output node ID, or empty for last node in graph
     * @param finalPort output port name, or empty for "geometry"
     */
    public void loadDsl(String dslName, String finalNode, String finalPort) {
        // Normalize: append .dsl if missing
        if (!dslName.endsWith(".dsl")) {
            dslName = dslName + ".dsl";
        }
        if (finalPort == null || finalPort.isEmpty()) {
            finalPort = DEFAULT_DSL_FINAL_PORT;
        }

        disposeMeshRuntime();

        String resolvedPort = finalPort;
        String resolvedDslName = dslName;
        Platforms.get().loadSourceAsync(DSL_FOLDER, resolvedDslName, Platforms.gl().getPlatformID(), dslCode -> {
            PythonLexer lexer = new PythonLexer(dslCode);
            PythonParser parser = new PythonParser(lexer);
            List<PythonParser.ParsedNode> ast = parser.parseGraph();

            NodeGraphRuntime runtime = new NodeGraphRuntime();
            runtime.registerAllFromAnnotationRegistry();
            runtime.registerFunctionDefs(parser.functionDefs());
            lastGraphRuntime = runtime;

            String resolvedNode = (finalNode != null && !finalNode.isEmpty())
                    ? finalNode
                    : ast.get(ast.size() - 1).id;

            try {
                mesh = runtime.executeGraphToMesh(ast, resolvedNode, resolvedPort);
            } catch (Exception e) {
                Platforms.get().log("[mesh-viewer] DSL reload failed: " + e.getMessage());
                throw new IllegalStateException("Failed to execute DSL: " + resolvedDslName, e);
            }
            logTiming(runtime);
            try {
                meshRuntime = new HalfEdgeMeshRuntime();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to create mesh GL runtime", e);
            }
            meshRuntime.upload(mesh);
            meshRuntime.frameCamera(camera);

            if (mesh != null) {
                Platforms.get().log(
                        "[mesh-viewer] dsl reloaded: " + resolvedDslName + " verts=" + mesh.vertexCount()
                                + " faces=" + mesh.faceCount());
                meshCenter.set(mesh.center(new Vector3f()));
            } else {
                Platforms.get().log("[mesh-viewer] dsl reload produced null mesh: " + resolvedDslName);
                meshCenter.set(0f, 0f, 0f);
            }
            if (orbitMouse != null) {
                orbitMouse.setTarget(meshCenter);
                float orbitDist = mesh != null ? Math.max(1.5f, mesh.radius() * 2.5f) : CAMERA_DISTANCE;
                orbitMouse.setOrbit(CAMERA_AZIMUTH, CAMERA_ELEVATION, orbitDist);
            }
        });
    }

    /** Current mesh from the DSL graph, or null before async load completes. */
    public MeshTopology getMesh() {
        return mesh;
    }

    // ==================== VIEW-7 model switching + patch overlay ====================

    public ModelCatalog getModelCatalog() {
        return modelCatalog;
    }

    public void nextModel() {
        if (modelCatalog == null || modelCatalog.entries().isEmpty()) return;
        loadModelEntry(modelCatalog.next());
    }

    public void prevModel() {
        if (modelCatalog == null || modelCatalog.entries().isEmpty()) return;
        loadModelEntry(modelCatalog.prev());
    }

    public void loadModelEntry(ModelCatalog.ModelEntry entry) {
        if (entry == null) return;
        // Preserve current orbit across the reload so the user doesn't get
        // yanked back to the default camera on every switch.
        float savedAz = orbitMouse != null ? orbitMouse.getAzimuth() : CAMERA_AZIMUTH;
        float savedEl = orbitMouse != null ? orbitMouse.getElevation() : CAMERA_ELEVATION;
        float savedDist = orbitMouse != null ? orbitMouse.getDistance() : CAMERA_DISTANCE;
        // Invalidate any cached decomposition — the mesh is changing.
        cachedDiagnostics = null;
        cachedDiagnosticsKey = null;
        patchOverlayEnabled = false;
        currentModelKey = entry.absolutePath().toString();
        currentModelDisplayName = entry.displayName();
        Platforms.get().log("[mesh-viewer] loading " + entry.displayName());
        switch (entry.type()) {
            case DSL -> loadDslFromAbsolutePath(entry.absolutePath().toString(), savedAz, savedEl, savedDist);
            case OBJ -> loadObjFromAbsolutePath(entry.absolutePath().toString(), savedAz, savedEl, savedDist);
        }
        logState();
    }

    private void loadDslFromAbsolutePath(String absolutePath, float az, float el, float dist) {
        // loadDsl() expects a resource-relative name, but the staging dir
        // contains symlinks — read the file directly and execute the graph.
        try {
            String dslCode = new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(absolutePath)));
            disposeMeshRuntime();
            PythonLexer lexer = new PythonLexer(dslCode);
            PythonParser parser = new PythonParser(lexer);
            List<PythonParser.ParsedNode> ast = parser.parseGraph();
            NodeGraphRuntime runtime = new NodeGraphRuntime();
            runtime.registerAllFromAnnotationRegistry();
            runtime.registerFunctionDefs(parser.functionDefs());
            lastGraphRuntime = runtime;
            String resolvedNode = ast.get(ast.size() - 1).id;
            mesh = runtime.executeGraphToMesh(ast, resolvedNode, DEFAULT_DSL_FINAL_PORT);
            meshRuntime = new HalfEdgeMeshRuntime();
            meshRuntime.upload(mesh);
            meshRuntime.frameCamera(camera);
            if (mesh != null) {
                Platforms.get().log("[mesh-viewer] dsl loaded: " + absolutePath + " verts=" + mesh.vertexCount()
                        + " faces=" + mesh.faceCount());
                meshCenter.set(mesh.center(new Vector3f()));
            } else {
                meshCenter.set(0f, 0f, 0f);
            }
            if (orbitMouse != null) {
                orbitMouse.setTarget(meshCenter);
                orbitMouse.setOrbit(az, el, dist);
            }
        } catch (Exception e) {
            Platforms.get().log("[mesh-viewer] DSL load failed for " + absolutePath + ": " + e.getMessage());
        }
    }

    private void loadObjFromAbsolutePath(String absolutePath, float az, float el, float dist) {
        try {
            ArrayMesh arrayMesh = MeshLoader.load(absolutePath);
            disposeMeshRuntime();
            mesh = arrayMesh;
            meshRuntime = new HalfEdgeMeshRuntime();
            meshRuntime.upload(arrayMesh);
            meshRuntime.frameCamera(camera);
            Platforms.get().log("[mesh-viewer] obj loaded: " + absolutePath
                    + " verts=" + arrayMesh.vertexCount() + " faces=" + arrayMesh.faceCount());
            meshCenter.set(arrayMesh.center(new Vector3f()));
            if (orbitMouse != null) {
                orbitMouse.setTarget(meshCenter);
                orbitMouse.setOrbit(az, el, dist);
            }
        } catch (Exception e) {
            Platforms.get().log("[mesh-viewer] OBJ load failed for " + absolutePath + ": " + e.getMessage());
        }
    }

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
                + " patches, mode=" + shaderMode + ")");
        logState();
    }

    /**
     * PATCH-26: cycle the active decomposer. Invalidates the cache so
     * the next overlay-on triggers a recompute via the new decomposer;
     * if patches are already on, recompute eagerly so the user sees the
     * swap immediately.
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

    public void toggleShaderMode() {
        HalfEdgeMeshRuntime.ShaderMode[] cycle = HalfEdgeMeshRuntime.ShaderMode.values();
        shaderMode = cycle[(shaderMode.ordinal() + 1) % cycle.length];
        if (meshRuntime != null) {
            meshRuntime.setShaderMode(shaderMode);
        }
        if (patchOverlayEnabled && cachedDiagnostics != null) {
            applyCurrentOverlay();  // rebuild tag colours for new mode
            applyFeatureEdgeOverlay();  // (re)install overlay edges if needed
            applyScalarOverlay();  // upload coonsError when entering SCALAR mode
        }
        Platforms.get().log("[mesh-viewer] shader mode: " + shaderMode);
        logState();
    }

    /**
     * Feed the per-vertex Coons reconstruction error (PATCH-16) into the
     * SCALAR shader pipeline (PATCH-15) when the user cycles into SCALAR
     * mode. Ramp is scaled so the pass/fail threshold reads as the middle
     * of the thermal gradient — dark ≤ threshold (Coons-fit OK), bright
     * > threshold (Coons-fit failing).
     */
    private void applyScalarOverlay() {
        if (meshRuntime == null || cachedDiagnostics == null) return;
        if (shaderMode != HalfEdgeMeshRuntime.ShaderMode.SCALAR) {
            meshRuntime.clearPerVertexScalar();
            return;
        }
        float[] errors = cachedDiagnostics.coonsError();
        if (errors == null || errors.length == 0) {
            meshRuntime.clearPerVertexScalar();
            return;
        }
        float rampMax = Math.max(2f * cachedDiagnostics.coonsErrorThreshold(), 1e-6f);
        meshRuntime.setPerVertexScalar(errors, 0f, rampMax);
    }

    /**
     * Compute the feature-edge categories appropriate for the current shader
     * mode and push them to the runtime. Category assignment mirrors
     * {@code PatchRenderer.drawFeatureEdgeOverlay} so the on-screen colors
     * match the offline PNG diagnostic — the two paths must stay in lockstep.
     */
    private void applyFeatureEdgeOverlay() {
        if (meshRuntime == null || cachedDiagnostics == null) return;
        if (shaderMode == HalfEdgeMeshRuntime.ShaderMode.STAGES) {
            java.util.Set<Long> dih = cachedDiagnostics.dihedralFeatureEdges();
            java.util.Set<Long> prin = cachedDiagnostics.principalFeatureEdges();
            java.util.Set<Long> crest = cachedDiagnostics.crestEdges();
            java.util.Set<Long> saddle = cachedDiagnostics.saddleSeparatorEdges();

            java.util.Set<Long> all = new java.util.HashSet<>(dih);
            all.addAll(prin);
            all.addAll(crest);
            all.addAll(saddle);

            java.util.List<Long> dihOnly = new java.util.ArrayList<>();
            java.util.List<Long> prinOnly = new java.util.ArrayList<>();
            java.util.List<Long> crestOnly = new java.util.ArrayList<>();
            java.util.List<Long> multi = new java.util.ArrayList<>();
            for (long key : all) {
                boolean d = dih.contains(key);
                boolean p = prin.contains(key);
                boolean c = crest.contains(key);
                boolean s = saddle.contains(key);
                int sourceCount = (d ? 1 : 0) + (p ? 1 : 0) + (c ? 1 : 0) + (s ? 1 : 0);
                if (sourceCount >= 2) multi.add(key);
                else if (d) dihOnly.add(key);
                else if (p) prinOnly.add(key);
                else if (c) crestOnly.add(key);
                // saddle-only not drawn here; saddle drawn as a last pass below for emphasis.
            }
            java.util.List<HalfEdgeMeshRuntime.FeatureEdgeCategory> cats = new java.util.ArrayList<>();
            cats.add(new HalfEdgeMeshRuntime.FeatureEdgeCategory(FeatureEdgeColors.DIHEDRAL, dihOnly));
            cats.add(new HalfEdgeMeshRuntime.FeatureEdgeCategory(FeatureEdgeColors.PRINCIPAL, prinOnly));
            cats.add(new HalfEdgeMeshRuntime.FeatureEdgeCategory(FeatureEdgeColors.CREST, crestOnly));
            cats.add(new HalfEdgeMeshRuntime.FeatureEdgeCategory(FeatureEdgeColors.MULTI_SOURCE, multi));
            cats.add(new HalfEdgeMeshRuntime.FeatureEdgeCategory(FeatureEdgeColors.SADDLE, saddle));
            meshRuntime.setFeatureEdgeOverlay(cats);
        } else if (shaderMode == HalfEdgeMeshRuntime.ShaderMode.CREST_VS_BOUNDARY) {
            java.util.Set<Long> crest = cachedDiagnostics.crestEdges();
            java.util.Set<Long> boundary = cachedDiagnostics.patchBoundaryEdges();
            java.util.List<Long> boundaryOnly = new java.util.ArrayList<>();
            java.util.List<Long> crestIgnored = new java.util.ArrayList<>();
            java.util.List<Long> aligned = new java.util.ArrayList<>();
            java.util.Set<Long> union = new java.util.HashSet<>(crest);
            union.addAll(boundary);
            for (long key : union) {
                boolean c = crest.contains(key);
                boolean b = boundary.contains(key);
                if (c && b) aligned.add(key);
                else if (b) boundaryOnly.add(key);
                else if (c) crestIgnored.add(key);
            }
            java.util.List<HalfEdgeMeshRuntime.FeatureEdgeCategory> cats = new java.util.ArrayList<>();
            cats.add(new HalfEdgeMeshRuntime.FeatureEdgeCategory(FeatureEdgeColors.BOUNDARY_ONLY, boundaryOnly));
            cats.add(new HalfEdgeMeshRuntime.FeatureEdgeCategory(FeatureEdgeColors.CREST_IGNORED, crestIgnored));
            cats.add(new HalfEdgeMeshRuntime.FeatureEdgeCategory(FeatureEdgeColors.CREST_HONORED, aligned));
            meshRuntime.setFeatureEdgeOverlay(cats);
        } else if (shaderMode == HalfEdgeMeshRuntime.ShaderMode.MSC) {
            ixdar.geometry.mesh.data.MorseSmaleComplex.Result msc = cachedDiagnostics.morseSmale();
            if (msc != null) {
                // Convert each MSC arc polyline into mesh edges; push as
                // a single black-colored category. Critical-point dots
                // are CPU-only for now (live-viewer point sprites are
                // future work — the arcs alone already give the topology
                // structure on screen).
                java.util.List<Long> arcEdges = new java.util.ArrayList<>();
                for (var arc : msc.arcs()) {
                    int[] verts = arc.vertices();
                    for (int i = 0; i + 1 < verts.length; i++) {
                        int u = verts[i];
                        int v = verts[i + 1];
                        long key = u < v
                                ? ((long) u << 32) | (v & 0xffffffffL)
                                : ((long) v << 32) | (u & 0xffffffffL);
                        arcEdges.add(key);
                    }
                }
                java.util.List<HalfEdgeMeshRuntime.FeatureEdgeCategory> cats = new java.util.ArrayList<>();
                cats.add(new HalfEdgeMeshRuntime.FeatureEdgeCategory(0x000000, arcEdges));
                meshRuntime.setFeatureEdgeOverlay(cats);
            } else {
                meshRuntime.clearFeatureEdgeOverlay();
            }
        } else {
            meshRuntime.clearFeatureEdgeOverlay();
        }
    }

    private void ensureDecomposition() {
        if (mesh == null) return;
        String key = currentModelKey != null ? currentModelKey : "dsl";
        if (cachedDiagnostics != null && key.equals(cachedDiagnosticsKey)) return;
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
                ? ixdar.geometry.mesh.data.MorseSmaleDecomposer.decomposeWithDiagnostics(am, 128)
                : SemanticPatchDecomposer.decomposeWithDiagnostics(am, 128);
        cachedDiagnosticsKey = key;
        long elapsed = System.currentTimeMillis() - start;
        Platforms.get().log("[mesh-viewer] decomposed in " + elapsed + "ms: "
                + cachedDiagnostics.decomposition().patches().size() + " patches"
                + " (crest=" + cachedDiagnostics.crestEdges().size()
                + " saddle=" + cachedDiagnostics.saddleSeparatorEdges().size()
                + " boundary=" + cachedDiagnostics.patchBoundaryEdges().size() + ")");
    }

    private void applyCurrentOverlay() {
        if (meshRuntime == null || cachedDiagnostics == null) return;
        int vertexCount = mesh.vertexCount();
        Map<String, boolean[]> tags = new HashMap<>();
        meshRuntime.clearTagColors();
        for (Patch p : cachedDiagnostics.decomposition().patches()) {
            String name = "patch_" + p.id();
            boolean[] mask = new boolean[vertexCount];
            for (int v : p.vertexIndices()) {
                if (v >= 0 && v < vertexCount) mask[v] = true;
            }
            tags.put(name, mask);
            Vector4f color;
            if (shaderMode == HalfEdgeMeshRuntime.ShaderMode.FLAT) {
                int rgb = PatchRenderer.uniquePatchColor(p.id());
                color = new Vector4f(
                        ((rgb >> 16) & 0xff) / 255f,
                        ((rgb >> 8) & 0xff) / 255f,
                        (rgb & 0xff) / 255f,
                        1f);
            } else {
                int rgb = Integer.parseInt(p.color(), 16);
                color = new Vector4f(
                        ((rgb >> 16) & 0xff) / 255f,
                        ((rgb >> 8) & 0xff) / 255f,
                        (rgb & 0xff) / 255f,
                        1f);
            }
            meshRuntime.setTagColor(name, color);
        }
        meshRuntime.setShaderMode(shaderMode);
        meshRuntime.setTags(tags);
    }

    public void toggleMeshWireframe() {
        if (meshRuntime == null) {
            return;
        }
        meshRuntime.setWireframe(!meshRuntime.isWireframe());
        Platforms.get().log("[mesh-viewer] wireframe=" + meshRuntime.isWireframe());
    }

    public void setOrthographic(boolean ortho) {
        HalfEdgeMeshRuntime rt = meshRuntime;
        if (rt == null) return;
        rt.setOrthographic(ortho);
    }

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
     * Direct input binding that doesn't rely on reflection (works in TeaVM).
     * bindAutomationIfAvailable uses Class.forName which silently fails in
     * TeaVM, leaving all input callbacks null. This method wires them directly.
     */
    private static void bindInputDirect(ixdar.platform.gl.Platform platform,
                                         ixdar.platform.input.KeyGuy keys,
                                         ixdar.platform.input.MouseTrap mouse) {
        platform.setCursorPosCallback((window, x, y) -> {
            mouse.moveOrDrag(window, (float) x, (float) y);
        });
        platform.setMouseButtonCallback((button, action, mods) -> {
            mouse.mouseButton(button, action, mods);
        });
        platform.setScrollCallback((xoff, yoff) -> {
            mouse.scrollCallback(yoff);
        });
        platform.setKeyCallback((key, scancode, action, mods) -> {
            keys.keyCallback(0L, key, scancode, action, mods);
        });
    }

}
