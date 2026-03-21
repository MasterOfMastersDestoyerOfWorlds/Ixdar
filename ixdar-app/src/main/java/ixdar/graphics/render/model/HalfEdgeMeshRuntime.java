package ixdar.graphics.render.model;

import java.nio.IntBuffer;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.platform.Platforms;

public class HalfEdgeMeshRuntime {
    private final ShaderProgram meshShader;
    private final ShaderProgram meshUnlitShader;
    private final Matrix4f modelMatrix = new Matrix4f();
    private final Matrix4f projectionMatrix = new Matrix4f();
    private final Vector4f solidColor = Color.BLUE_GRAY.toVector4f();
    private final Vector4f edgeColor = Color.RED.toVector4f();
    private final Vector4f edgeFaintColor = Color.RED_FAINT.toVector4f();
    private final Vector3f lightDir = new Vector3f(0.4f, -1.0f, 0.25f);
    private final Vector3f emissiveColor = Color.BLUE_WHITE.toVector3f();
    private final Vector3f minBounds = new Vector3f();
    private final Vector3f maxBounds = new Vector3f();
    private final Vector3f center = new Vector3f();

    private IntBuffer indexBuffer;
    private HalfEdgeCompiledMeshData compiledMesh;
    private int ebo;
    private int edgeEbo;
    private int edgeCount;
    private boolean wireframe = false;
    private boolean xray = true;

    public HalfEdgeMeshRuntime() throws Exception {
        this.meshShader = ShaderProgram.ShaderType.Mesh.getShader();
        this.meshUnlitShader = ShaderProgram.ShaderType.MeshUnlit.getShader();
        this.meshShader.init();
        this.meshUnlitShader.init();
        this.ebo = Platforms.gl().genBuffers();
        this.edgeEbo = Platforms.gl().genBuffers();
    }

    public void upload(MeshTopology mesh) {
        compiledMesh = compileSurface(mesh);
        uploadCompiledMesh(Platforms.gl().STATIC_DRAW());
        uploadEdgeData(mesh);
    }

    public void reupload(MeshTopology mesh) {
        compiledMesh = compileSurface(mesh);
        uploadCompiledMesh(Platforms.gl().DYNAMIC_DRAW());
        uploadEdgeData(mesh);
    }

    private static HalfEdgeCompiledMeshData compileSurface(MeshTopology mesh) {
        if (mesh instanceof ArrayMesh am) {
            return am.compileSurfaceData();
        }
        if (mesh instanceof HalfEdgeMesh hem) {
            return hem.compileSurfaceData();
        }
        throw new IllegalArgumentException("Unsupported mesh for rendering: " + mesh.getClass().getName());
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

        meshShader.vao.bind();
        Platforms.gl().bindBuffer(Platforms.gl().ELEMENT_ARRAY_BUFFER(), ebo);
        Platforms.gl().drawElements(Platforms.gl().TRIANGLES(), compiledMesh.indices.length, Platforms.gl().UNSIGNED_INT(), 0);
        if (wireframe) {
            renderEdges(camera);
        }
    }

    public void dispose() {
        if (ebo != 0) {
            Platforms.gl().deleteBuffers(ebo);
            ebo = 0;
        }
        if (edgeEbo != 0) {
            Platforms.gl().deleteBuffers(edgeEbo);
            edgeEbo = 0;
        }
        meshShader.vbo.delete();
        meshShader.vao.delete();
    }

    private void uploadEdgeData(MeshTopology mesh) {
        int[] edgeIndices = edgeIndices(mesh);
        Platforms.gl().bindBuffer(Platforms.gl().ELEMENT_ARRAY_BUFFER(), edgeEbo);
        
        IntBuffer buffer = BufferUtils.createIntBuffer(edgeIndices.length);
        buffer.put(edgeIndices).flip();
        Platforms.gl().bufferData(Platforms.gl().ELEMENT_ARRAY_BUFFER(), buffer, Platforms.gl().STATIC_DRAW());
        edgeCount = edgeIndices.length;
    }

    private static int[] edgeIndices(MeshTopology mesh) {
        if (mesh instanceof ArrayMesh am) {
            return am.getEdgeIndices();
        }
        if (mesh instanceof HalfEdgeMesh hem) {
            return hem.getEdgeIndices();
        }
        throw new IllegalArgumentException("Unsupported mesh for edge indices: " + mesh.getClass().getName());
    }

    public void renderEdges(Camera3D camera) {
        meshUnlitShader.use();
        meshUnlitShader.setMat4("model", modelMatrix.identity());
        meshUnlitShader.setMat4("view", camera.view);
        meshUnlitShader.setMat4("projection", projectionMatrix);
        meshShader.vao.bind();
        meshUnlitShader.setVec4("solidColor", edgeFaintColor);
        Platforms.gl().bindBuffer(Platforms.gl().ELEMENT_ARRAY_BUFFER(), edgeEbo);
        Platforms.gl().disable(Platforms.gl().DEPTH_TEST());
        Platforms.gl().lineWidth(1.5f);
        Platforms.gl().drawElements(Platforms.gl().LINES(), edgeCount, Platforms.gl().UNSIGNED_INT(), 0);
        Platforms.gl().enable(Platforms.gl().DEPTH_TEST());

        meshUnlitShader.setVec4("solidColor", edgeColor);
        Platforms.gl().lineWidth(2.0f);
        Platforms.gl().drawElements(Platforms.gl().LINES(), edgeCount , Platforms.gl().UNSIGNED_INT(), 0);
        

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

    public boolean isWireframe() {
        return wireframe;
    }

    public void setWireframe(boolean wireframe) {
        this.wireframe = wireframe;
    }

    private void uploadCompiledMesh(int usage) {
        if (compiledMesh == null) {
            return;
        }

        meshShader.vao.bind();
        meshShader.vbo.bind(Platforms.gl().ARRAY_BUFFER());
        meshShader.vbo.uploadData(Platforms.gl().ARRAY_BUFFER(), compiledMesh.vertices, usage);

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
