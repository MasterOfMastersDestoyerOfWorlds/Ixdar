package ixdar.scenes.mesh;

import java.nio.IntBuffer;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.shaders.MeshShader;
import ixdar.graphics.render.shaders.VertexArrayObject;
import ixdar.graphics.render.shaders.VertexBufferObject;
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

    private final Matrix4f model = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final Vector3f meshCenter = new Vector3f();
    private final Vector4f solidColor = Color.BLUE_WHITE.toVector4f();
    private final Vector3f lightDir = new Vector3f(0.4f, -1.0f, 0.25f);
    private final Vector3f emissiveColor = Color.BLUE_GRAY.toVector3f().mul(0.35f);
    private final Vector3f boundingBoxMin = new Vector3f(-HALF_EXTENT, -HALF_EXTENT, -HALF_EXTENT);
    private final Vector3f boundingBoxMax = new Vector3f(HALF_EXTENT, HALF_EXTENT, HALF_EXTENT);

    private MeshShader meshShader;
    private VertexArrayObject cubeVao;
    private VertexBufferObject cubeVbo;
    private int cubeEbo;
    private int cubeIndexCount;
    private int vertexCount;
    private int faceCount;

    @Override
    public void initGL() {
        super.initGL();
        Platforms.gl().setWindowTitle("Ixdar : Mesh Node Viewer");
        initCameraControls();
        initCubeMesh();
    }

    @Override
    public void drawScene() {
        if (meshShader == null || cubeVao == null || cubeIndexCount == 0) {
            return;
        }
        camera.resetView();
        int width = Platforms.get().getFrameBufferWidth();
        int height = Platforms.get().getFrameBufferHeight();
        float aspect = width <= 0 || height <= 0 ? 1f : ((float) width / (float) height);

        projection.identity().perspective((float) Math.toRadians((float) camera.fov), aspect, 0.01f, 100f);

        meshShader.use();
        meshShader.setMat4("model", model.identity());
        meshShader.setMat4("view", camera.view);
        meshShader.setMat4("projection", projection);
        meshShader.setVec4("solidColor", solidColor);
        meshShader.setVec3("lightDir", lightDir);
        meshShader.setBool("useTexture", false);
        meshShader.setVec3("emissiveColor", emissiveColor);
        meshShader.setFloat("emissiveStrength", 0.08f);
        meshShader.setFloat("rimStrength", 0.16f);

        cubeVao.bind();
        Platforms.gl().bindBuffer(Platforms.gl().ELEMENT_ARRAY_BUFFER(), cubeEbo);
        Platforms.gl().drawElements(Platforms.gl().TRIANGLES(), cubeIndexCount, Platforms.gl().UNSIGNED_INT(), 0);
    }

    @Override
    public void activate(boolean state) {
        super.activate(state);
        if (!state) {
            disposeCubeMesh();
        }
    }

    @Override
    public void shutdown() {
        disposeCubeMesh();
        super.shutdown();
    }

    public int getMeshVertexCount() {
        return vertexCount;
    }

    public int getMeshFaceCount() {
        return faceCount;
    }

    public Vector3f getBoundingBoxMin() {
        return new Vector3f(boundingBoxMin);
    }

    public Vector3f getBoundingBoxMax() {
        return new Vector3f(boundingBoxMax);
    }

    private void initCameraControls() {
        MenuBox.menuVisible = false;
        keys = new KeyGuy(camera, this);
        OrbitMouseTrap orbitMouse = new OrbitMouseTrap(camera, this);
        orbitMouse.setTarget(meshCenter);
        orbitMouse.setOrbit(CAMERA_AZIMUTH, CAMERA_ELEVATION, CAMERA_DISTANCE);
        mouse = orbitMouse;
        AutomationInputBinder.bind(Platforms.get(), keys, mouse);
    }

    private void initCubeMesh() {
        try {
            meshShader = new MeshShader(new VertexArrayObject(), new VertexBufferObject());
            meshShader.init();

            cubeVao = new VertexArrayObject();
            cubeVbo = new VertexBufferObject();
            cubeVao.bind();
            cubeVbo.bind(Platforms.gl().ARRAY_BUFFER());
            cubeVbo.uploadData(Platforms.gl().ARRAY_BUFFER(), cubeVertices(), Platforms.gl().STATIC_DRAW());

            cubeEbo = Platforms.gl().genBuffers();
            Platforms.gl().bindBuffer(Platforms.gl().ELEMENT_ARRAY_BUFFER(), cubeEbo);
            IntBuffer indexBuffer = BufferUtils.createIntBuffer(cubeIndices().length);
            indexBuffer.put(cubeIndices()).flip();
            Platforms.gl().bufferData(Platforms.gl().ELEMENT_ARRAY_BUFFER(), indexBuffer, Platforms.gl().STATIC_DRAW());

            Platforms.gl().vertexAttribPointer(0, 3, Platforms.gl().FLOAT(), false, 8 * Float.BYTES, 0);
            Platforms.gl().enableVertexAttribArray(0);
            Platforms.gl().vertexAttribPointer(1, 3, Platforms.gl().FLOAT(), false, 8 * Float.BYTES, 3 * Float.BYTES);
            Platforms.gl().enableVertexAttribArray(1);
            Platforms.gl().vertexAttribPointer(2, 2, Platforms.gl().FLOAT(), false, 8 * Float.BYTES, 6 * Float.BYTES);
            Platforms.gl().enableVertexAttribArray(2);

            vertexCount = 8;
            faceCount = 12;
            cubeIndexCount = 36;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize mesh viewer cube", e);
        }
    }

    private void disposeCubeMesh() {
        if (cubeEbo != 0) {
            Platforms.gl().deleteBuffers(cubeEbo);
            cubeEbo = 0;
        }
        if (cubeVbo != null) {
            cubeVbo.delete();
            cubeVbo = null;
        }
        if (cubeVao != null) {
            cubeVao.delete();
            cubeVao = null;
        }
        cubeIndexCount = 0;
    }

    private float[] cubeVertices() {
        float unit = 0.57735026f;
        return new float[] {
                -HALF_EXTENT, -HALF_EXTENT, -HALF_EXTENT, -unit, -unit, -unit, 0f, 0f,
                HALF_EXTENT, -HALF_EXTENT, -HALF_EXTENT, unit, -unit, -unit, 1f, 0f,
                HALF_EXTENT, HALF_EXTENT, -HALF_EXTENT, unit, unit, -unit, 1f, 1f,
                -HALF_EXTENT, HALF_EXTENT, -HALF_EXTENT, -unit, unit, -unit, 0f, 1f,
                -HALF_EXTENT, -HALF_EXTENT, HALF_EXTENT, -unit, -unit, unit, 0f, 0f,
                HALF_EXTENT, -HALF_EXTENT, HALF_EXTENT, unit, -unit, unit, 1f, 0f,
                HALF_EXTENT, HALF_EXTENT, HALF_EXTENT, unit, unit, unit, 1f, 1f,
                -HALF_EXTENT, HALF_EXTENT, HALF_EXTENT, -unit, unit, unit, 0f, 1f,
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
