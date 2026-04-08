package ixdar.scenes.mesh;

import java.util.List;

import org.joml.Vector3f;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;
import ixdar.platform.Platforms;
import ixdar.platform.input.OrbitMouseTrap;
import ixdar.scenes.Scene;

@SceneAnnotation(id = "mesh-viewer")
public class MeshNodeViewerScene extends Scene {
    private static final String DSL_FOLDER = "dsl";
    private static final String DEFAULT_DSL_RESOURCE = "skull.dsl";
    private static final String DEFAULT_DSL_FINAL_NODE = "skull_carved";
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

    public MeshNodeViewerScene() {
        this(DEFAULT_DSL_RESOURCE, DEFAULT_DSL_FINAL_NODE, DEFAULT_DSL_FINAL_PORT);
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
                try {
                    mesh = runtime.executeGraphToMesh(ast, dslFinalNode, dslFinalPort);
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
        mesh = null;
    }

}
