package ixdar.scenes.mesh;

import java.io.IOException;
import java.util.List;

import org.joml.Vector3f;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.MeshLoader;
import ixdar.geometry.mesh.data.MeshTopology;
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

    private final Vector3f meshCenter = new Vector3f();

    private OrbitMouseTrap orbitMouse;
    private MeshTopology mesh;
    private HalfEdgeMeshRuntime meshRuntime;
    private HalfEdgeMeshRuntime overlayRuntime;

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
        this.dslResource = dslResource;
        this.dslFinalNode = dslFinalNode;
        this.dslFinalPort = dslFinalPort;
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
        try {
            Platforms.get().loadSourceAsync(DSL_FOLDER, dslResource, Platforms.gl().getPlatformID(), dslCode -> {
                PythonLexer lexer = new PythonLexer(dslCode);
                PythonParser parser = new PythonParser(lexer);
                List<PythonParser.ParsedNode> ast = parser.parseGraph();

                NodeGraphRuntime runtime = new NodeGraphRuntime();
                runtime.registerAllFromAnnotationRegistry();
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
                    orbitMouse.setOrbit(CAMERA_AZIMUTH, CAMERA_ELEVATION, CAMERA_DISTANCE);
                }
            });

        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize mesh viewer runtime", e);
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

            String resolvedNode = (finalNode != null && !finalNode.isEmpty())
                    ? finalNode
                    : ast.get(ast.size() - 1).id;

            try {
                mesh = runtime.executeGraphToMesh(ast, resolvedNode, resolvedPort);
            } catch (Exception e) {
                Platforms.get().log("[mesh-viewer] DSL reload failed: " + e.getMessage());
                throw new IllegalStateException("Failed to execute DSL: " + resolvedDslName, e);
            }
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
                orbitMouse.setOrbit(CAMERA_AZIMUTH, CAMERA_ELEVATION, CAMERA_DISTANCE);
            }
        });
    }

    /** Current mesh from the DSL graph, or null before async load completes. */
    public MeshTopology getMesh() {
        return mesh;
    }

    public void toggleMeshWireframe() {
        if (meshRuntime == null) {
            return;
        }
        meshRuntime.setWireframe(!meshRuntime.isWireframe());
        Platforms.get().log("[mesh-viewer] wireframe=" + meshRuntime.isWireframe());
    }

    private void disposeMeshRuntime() {
        if (meshRuntime != null) {
            meshRuntime.dispose();
            meshRuntime = null;
        }
        disposeOverlay();
        mesh = null;
    }

}
