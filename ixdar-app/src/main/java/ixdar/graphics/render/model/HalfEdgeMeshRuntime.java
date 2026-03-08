package ixdar.graphics.render.model;

import java.nio.IntBuffer;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;

import ixdar.geometry.mesh.HalfEdgeMesh;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.shaders.MeshShader;
import ixdar.graphics.render.shaders.VertexArrayObject;
import ixdar.graphics.render.shaders.VertexBufferObject;
import ixdar.platform.Platforms;

public class HalfEdgeMeshRuntime {
    private final MeshShader meshShader;
    private final VertexArrayObject vao;
    private final VertexBufferObject vbo;
    private final Matrix4f modelMatrix = new Matrix4f();
    private final Matrix4f projectionMatrix = new Matrix4f();
    private final Vector4f solidColor = new Vector4f(0.7f, 0.85f, 1.0f, 1.0f);
    private final Vector3f lightDir = new Vector3f(0.4f, -1.0f, 0.25f);
    private final Vector3f emissiveColor = new Vector3f(0.25f, 0.34f, 0.48f);
    private final Vector3f minBounds = new Vector3f();
    private final Vector3f maxBounds = new Vector3f();
    private final Vector3f center = new Vector3f();

    private IntBuffer indexBuffer;
    private HalfEdgeCompiledMeshData compiledMesh;
    private int ebo;

    public HalfEdgeMeshRuntime() throws Exception {
        this.vao = new VertexArrayObject();
        this.vbo = new VertexBufferObject();
        this.meshShader = new MeshShader(vao, vbo);
        this.meshShader.init();
        this.ebo = Platforms.gl().genBuffers();
    }

    public void upload(HalfEdgeMesh mesh) {
        compiledMesh = mesh.compileSurfaceData();
        uploadCompiledMesh(Platforms.gl().STATIC_DRAW());
    }

    public void reupload(HalfEdgeMesh mesh) {
        compiledMesh = mesh.compileSurfaceData();
        uploadCompiledMesh(Platforms.gl().DYNAMIC_DRAW());
    }

    public void frameCamera(Camera3D camera) {
        if (compiledMesh == null) {
            return;
        }
        float distance = Math.max(1.5f, compiledMesh.radius * 2.5f);
        camera.position.set(compiledMesh.center.x, compiledMesh.center.y, compiledMesh.center.z + distance);
        camera.target.set(compiledMesh.center);
        camera.fov = 45f;
        camera.updateViewFirstPerson();
    }

    public void render(Camera3D camera) {
        if (compiledMesh == null || compiledMesh.indices.length == 0) {
            return;
        }

        int width = Platforms.get().getFrameBufferWidth();
        int height = Platforms.get().getFrameBufferHeight();
        float aspect = width <= 0 || height <= 0 ? 1f : ((float) width / (float) height);

        projectionMatrix.identity().perspective(
                (float) Math.toRadians((float) camera.fov),
                aspect,
                0.01f,
                Math.max(1000f, compiledMesh.radius * 20f));

        meshShader.use();
        meshShader.setMat4("model", modelMatrix.identity());
        meshShader.setMat4("view", camera.view);
        meshShader.setMat4("projection", projectionMatrix);
        meshShader.setVec4("solidColor", solidColor);
        meshShader.setVec3("lightDir", lightDir);
        meshShader.setBool("useTexture", false);
        meshShader.setVec3("emissiveColor", emissiveColor);
        meshShader.setFloat("emissiveStrength", 0.08f);
        meshShader.setFloat("rimStrength", 0.16f);

        vao.bind();
        Platforms.gl().bindBuffer(Platforms.gl().ELEMENT_ARRAY_BUFFER(), ebo);
        Platforms.gl().drawElements(Platforms.gl().TRIANGLES(), compiledMesh.indices.length, Platforms.gl().UNSIGNED_INT(), 0);
    }

    public void dispose() {
        if (ebo != 0) {
            Platforms.gl().deleteBuffers(ebo);
            ebo = 0;
        }
        vbo.delete();
        vao.delete();
    }

    public int getVertexCount() {
        return compiledMesh == null ? 0 : compiledMesh.vertexCount;
    }

    public int getFaceCount() {
        return compiledMesh == null ? 0 : compiledMesh.faceCount;
    }

    public Vector3f getBoundingBoxMin() {
        return compiledMesh == null ? new Vector3f() : new Vector3f(minBounds);
    }

    public Vector3f getBoundingBoxMax() {
        return compiledMesh == null ? new Vector3f() : new Vector3f(maxBounds);
    }

    public Vector3f getCenter() {
        return compiledMesh == null ? new Vector3f() : new Vector3f(center);
    }

    private void uploadCompiledMesh(int usage) {
        if (compiledMesh == null) {
            return;
        }

        vao.bind();
        vbo.bind(Platforms.gl().ARRAY_BUFFER());
        vbo.uploadData(Platforms.gl().ARRAY_BUFFER(), compiledMesh.vertices, usage);

        Platforms.gl().bindBuffer(Platforms.gl().ELEMENT_ARRAY_BUFFER(), ebo);
        IntBuffer uploadBuffer = ensureIndexBufferCapacity(compiledMesh.indices.length);
        uploadBuffer.clear();
        uploadBuffer.put(compiledMesh.indices).flip();
        Platforms.gl().bufferData(Platforms.gl().ELEMENT_ARRAY_BUFFER(), uploadBuffer, usage);

        minBounds.set(compiledMesh.minBounds);
        maxBounds.set(compiledMesh.maxBounds);
        center.set(compiledMesh.center);
    }

    private IntBuffer ensureIndexBufferCapacity(int requiredCapacity) {
        if (indexBuffer == null || indexBuffer.capacity() < requiredCapacity) {
            indexBuffer = BufferUtils.createIntBuffer(requiredCapacity);
        }
        return indexBuffer;
    }
}
