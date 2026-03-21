package ixdar.scenes.mesh;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.joml.Vector3f;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;
import ixdar.platform.Platforms;
import ixdar.platform.automation.AutomationInputBinder;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.OrbitMouseTrap;
import ixdar.scenes.Scene;

@SceneAnnotation(id = "mesh-viewer")
public class MeshNodeViewerScene extends Scene {
    /** Classpath resource (under {@code src/main/resources/}), same layout as {@code glsl/} for shaders. */
    private static final String DSL_RESOURCE = "dsl/tool_quilt_mock.dsl";
    private static final String DSL_FINAL_NODE = "quilt_out";
    private static final String DSL_FINAL_PORT = "geometry";

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
            mesh = buildViewerMesh();
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

    private HalfEdgeMesh buildViewerMesh() throws Exception {
        String dslCode = readClasspathResourceUtf8(DSL_RESOURCE);
        PythonLexer lexer = new PythonLexer(dslCode);
        PythonParser parser = new PythonParser(lexer);
        List<PythonParser.ParsedNode> ast = parser.parseGraph();

        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();

        return runtime.executeGraphToMesh(ast, DSL_FINAL_NODE, DSL_FINAL_PORT);
    }

    /**
     * Loads DSL text from the classpath (e.g. {@code dsl/tool_quilt_mock.dsl}).
     * Mirrors {@link ixdar.platform.gl.lwjgl.LwjglPlatform#loadSource(String, String)} ({@code folder/filename}).
     */
    public static String readClasspathResourceUtf8(String classpathPath) throws IOException {
        ClassLoader cl = MeshNodeViewerScene.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(classpathPath)) {
            if (in == null) {
                throw new IOException("Classpath resource not found: " + classpathPath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Example graph using the embedded quad-sphere chain; tests and tools can call this directly.
     */
    public HalfEdgeMesh buildQuadSphereFromPythonDSL() throws Exception {
        String dslCode = "base_cube = cube(size=2.0)\n"
                + "smooth_cube = subdivision_surface(mesh=base_cube.mesh, levels=3)\n"
                + "quad_sphere = spherize(mesh=smooth_cube.mesh, factor=1.0)\n";

        PythonLexer lexer = new PythonLexer(dslCode);
        PythonParser parser = new PythonParser(lexer);
        List<PythonParser.ParsedNode> ast = parser.parseGraph();

        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();

        return runtime.executeGraph(ast, "quad_sphere");
    }

    private void disposeMeshRuntime() {
        if (meshRuntime != null) {
            meshRuntime.dispose();
            meshRuntime = null;
        }
        mesh = null;
    }

}
