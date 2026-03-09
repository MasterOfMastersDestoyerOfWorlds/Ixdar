package ixdar.scenes.mesh;

import org.joml.Vector3f;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.HalfEdgeMesh;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.platform.Platforms;
import ixdar.platform.automation.AutomationInputBinder;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.OrbitMouseTrap;
import ixdar.scenes.Scene;

@SceneAnnotation(id = "mesh-viewer")
public class MeshNodeViewerScene extends Scene {
    private static final float HALF_EXTENT = 0.5f;
    private static final float CAMERA_AZIMUTH = (float) Math.toRadians(45.0);
    private static final float CAMERA_ELEVATION = (float) Math.toRadians(24.0);
    private static final float CAMERA_DISTANCE = 3.5f;

    private final Vector3f meshCenter = new Vector3f();

    private OrbitMouseTrap orbitMouse;
    private HalfEdgeMesh mesh;
    private HalfEdgeMeshRuntime meshRuntime;

    @Override
    public void initGL() {
        super.initGL();
        Platforms.gl().setWindowTitle("Ixdar : Mesh Node Viewer");
        initCameraControls();
        initMeshRuntime();
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

    private void initCameraControls() {
        MenuBox.menuVisible = false;
        keys = new KeyGuy(camera, this);
        orbitMouse = new OrbitMouseTrap(camera, this);
        orbitMouse.setTarget(meshCenter);
        orbitMouse.setOrbit(CAMERA_AZIMUTH, CAMERA_ELEVATION, CAMERA_DISTANCE);
        mouse = orbitMouse;
        AutomationInputBinder.bind(Platforms.get(), keys, mouse);
    }

    private void initMeshRuntime() {
        try {
            mesh = HalfEdgeMesh.buildFromIndexedMesh(cubeVertices(), cubeIndices());
            meshRuntime = new HalfEdgeMeshRuntime();
            meshRuntime.upload(mesh);
            meshRuntime.frameCamera(camera);
            meshCenter.set(mesh.center(new Vector3f()));
            if (orbitMouse != null) {
                orbitMouse.setTarget(meshCenter);
                orbitMouse.setOrbit(CAMERA_AZIMUTH, CAMERA_ELEVATION, CAMERA_DISTANCE);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize mesh viewer runtime", e);
        }
    }

    private void disposeMeshRuntime() {
        if (meshRuntime != null) {
            meshRuntime.dispose();
            meshRuntime = null;
        }
        mesh = null;
    }

    private float[] cubeVertices() {
        return new float[] {
                -HALF_EXTENT, -HALF_EXTENT, -HALF_EXTENT,
                HALF_EXTENT, -HALF_EXTENT, -HALF_EXTENT,
                HALF_EXTENT, HALF_EXTENT, -HALF_EXTENT,
                -HALF_EXTENT, HALF_EXTENT, -HALF_EXTENT,
                -HALF_EXTENT, -HALF_EXTENT, HALF_EXTENT,
                HALF_EXTENT, -HALF_EXTENT, HALF_EXTENT,
                HALF_EXTENT, HALF_EXTENT, HALF_EXTENT,
                -HALF_EXTENT, HALF_EXTENT, HALF_EXTENT,
        };
    }

    private int[] cubeIndices() {
        return new int[] {
                0, 1, 2, 2, 3, 0,
                4, 7, 6, 6, 5, 4,
                0, 4, 5, 5, 1, 0,
                3, 2, 6, 6, 7, 3,
                1, 5, 6, 6, 2, 1,
                0, 3, 7, 7, 4, 0,
        };
    }
}
