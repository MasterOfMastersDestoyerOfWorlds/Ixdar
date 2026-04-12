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
import ixdar.graphics.render.shaders.VertexArrayObject;
import ixdar.graphics.render.shaders.VertexBufferObject;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;

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
    private final VertexArrayObject meshVao;
    private final VertexBufferObject meshVbo;
    private int ebo;
    private int edgeEbo;
    private int edgeCount;
    private boolean wireframe = false;
    private volatile boolean orthographic = false;
    private boolean xray = true;

    public HalfEdgeMeshRuntime() throws Exception {
        this.meshShader = ShaderProgram.ShaderType.Mesh.getShader();
        this.meshUnlitShader = ShaderProgram.ShaderType.MeshUnlit.getShader();
        this.meshShader.init();
        this.meshUnlitShader.init();
        this.meshVao = new VertexArrayObject();
        this.meshVbo = new VertexBufferObject();
        this.ebo = Platforms.gl().genBuffers();
        this.edgeEbo = Platforms.gl().genBuffers();
    }

    public void upload(MeshTopology mesh) {
        if (mesh == null) {
            compiledMesh = null;
            edgeCount = 0;
            return;
        }
        compiledMesh = compileSurface(mesh);
        uploadCompiledMesh(Platforms.gl().STATIC_DRAW());
        uploadEdgeData(mesh);
    }

    public void reupload(MeshTopology mesh) {
        if (mesh == null) {
            compiledMesh = null;
            edgeCount = 0;
            return;
        }
        compiledMesh = compileSurface(mesh);
        uploadCompiledMesh(Platforms.gl().DYNAMIC_DRAW());
        uploadEdgeData(mesh);
    }

    private static HalfEdgeCompiledMeshData compileSurface(MeshTopology mesh) {
        if (mesh == null) {
            return null;
        }
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
        if (meshShader.ID < 0) {
            return;
        }

        int width = Platforms.get().getFrameBufferWidth();
        int height = Platforms.get().getFrameBufferHeight();
        float aspect = width <= 0 || height <= 0 ? 1f : ((float) width / (float) height);

        float far = Math.max(1000f, compiledMesh.radius * 20f);
        if (orthographic) {
            float dist = camera.position.distance(camera.target);
            float halfH = dist * (float) Math.tan(Math.toRadians(camera.fov / 2.0));
            float halfW = halfH * aspect;
            projectionMatrix.identity().ortho(-halfW, halfW, -halfH, halfH, 0.01f, far);
        } else {
            projectionMatrix.identity().perspective(
                    (float) Math.toRadians((float) camera.fov),
                    aspect,
                    0.01f,
                    far);
        }

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

        meshVao.bind();
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
        meshVbo.delete();
        meshVao.delete();
    }

    private void uploadEdgeData(MeshTopology mesh) {
        if (mesh == null) {
            edgeCount = 0;
            return;
        }
        int[] edgeIndices = edgeIndices(mesh);
        Platforms.gl().bindBuffer(Platforms.gl().ELEMENT_ARRAY_BUFFER(), edgeEbo);
        
        IntBuffer buffer = BufferUtils.createIntBuffer(edgeIndices.length);
        buffer.put(edgeIndices).flip();
        Platforms.gl().bufferData(Platforms.gl().ELEMENT_ARRAY_BUFFER(), buffer, Platforms.gl().STATIC_DRAW());
        edgeCount = edgeIndices.length;
    }

    private static int[] edgeIndices(MeshTopology mesh) {
        if (mesh == null) {
            return new int[0];
        }
        if (mesh instanceof ArrayMesh am) {
            return am.getEdgeIndices();
        }
        if (mesh instanceof HalfEdgeMesh hem) {
            return hem.getEdgeIndices();
        }
        throw new IllegalArgumentException("Unsupported mesh for edge indices: " + mesh.getClass().getName());
    }

    public void renderEdges(Camera3D camera) {
        if (meshUnlitShader.ID < 0 || edgeCount <= 0) {
            return;
        }
        meshUnlitShader.use();
        meshUnlitShader.setMat4("model", modelMatrix.identity());
        meshUnlitShader.setMat4("view", camera.view);
        meshUnlitShader.setMat4("projection", projectionMatrix);
        meshVao.bind();
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

    public void setSolidColor(float r, float g, float b, float a) {
        solidColor.set(r, g, b, a);
    }

    public boolean isWireframe() {
        return wireframe;
    }

    public void setWireframe(boolean wireframe) {
        this.wireframe = wireframe;
    }

    public boolean isOrthographic() {
        return orthographic;
    }

    public void setOrthographic(boolean orthographic) {
        this.orthographic = orthographic;
    }

    private void uploadCompiledMesh(int usage) {
        if (compiledMesh == null) {
            return;
        }

        GL gl = Platforms.gl();
        meshVao.bind();
        meshVbo.bind(gl.ARRAY_BUFFER());
        meshVbo.uploadData(gl.ARRAY_BUFFER(), compiledMesh.vertices, usage);
        gl.vertexAttribPointer(0, 3, gl.FLOAT(), false, 8 * Float.BYTES, 0);
        gl.enableVertexAttribArray(0);
        gl.vertexAttribPointer(1, 3, gl.FLOAT(), false, 8 * Float.BYTES, 3 * Float.BYTES);
        gl.enableVertexAttribArray(1);
        gl.vertexAttribPointer(2, 2, gl.FLOAT(), false, 8 * Float.BYTES, 6 * Float.BYTES);
        gl.enableVertexAttribArray(2);

        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), ebo);
        IntBuffer uploadBuffer = ensureIndexBufferCapacity(compiledMesh.indices.length);
        uploadBuffer.clear();
        uploadBuffer.put(compiledMesh.indices).flip();
        gl.bufferData(gl.ELEMENT_ARRAY_BUFFER(), uploadBuffer, usage);

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
