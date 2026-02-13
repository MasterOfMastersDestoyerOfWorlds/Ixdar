package ixdar.graphics.render.model;

import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.Texture;
import ixdar.graphics.render.shaders.MeshShader;
import ixdar.graphics.render.shaders.VertexArrayObject;
import ixdar.graphics.render.shaders.VertexBufferObject;
import ixdar.platform.Platforms;
import ixdar.platform.file.FileManagement;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.assimp.AIFace;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIVector3D;
import org.lwjgl.assimp.Assimp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class AssimpModelRuntime implements ModelRuntime {

    private final MeshShader meshShader;
    private final Matrix4f modelMatrix = new Matrix4f();

    public AssimpModelRuntime() throws Exception {
        this.meshShader = new MeshShader(new VertexArrayObject(), new VertexBufferObject());
        this.meshShader.init();
    }

    @Override
    public ModelHandle loadFromAssetRepo(String modelFileName) throws Exception {
        String absoluteModelPath = FileManagement.resolveAssetPath(modelFileName);
        ImportedModel imported = importWithAssimp(absoluteModelPath);

        VertexArrayObject vao = new VertexArrayObject();
        VertexBufferObject vbo = new VertexBufferObject();
        vao.bind();
        vbo.bind(Platforms.gl().ARRAY_BUFFER());
        vbo.uploadData(Platforms.gl().ARRAY_BUFFER(), imported.vertices, Platforms.gl().STATIC_DRAW());

        int ebo = Platforms.gl().genBuffers();
        Platforms.gl().bindBuffer(Platforms.gl().ELEMENT_ARRAY_BUFFER(), ebo);
        IntBuffer indexBuffer = BufferUtils.createIntBuffer(imported.indices.length);
        indexBuffer.put(imported.indices).flip();
        Platforms.gl().bufferData(Platforms.gl().ELEMENT_ARRAY_BUFFER(), indexBuffer, Platforms.gl().STATIC_DRAW());

        // Configure attributes for this VAO with mesh shader layout.
        vao.bind();
        vbo.bind(Platforms.gl().ARRAY_BUFFER());
        Platforms.gl().vertexAttribPointer(0, 3, Platforms.gl().FLOAT(), false, 8 * Float.BYTES, 0);
        Platforms.gl().enableVertexAttribArray(0);
        Platforms.gl().vertexAttribPointer(1, 3, Platforms.gl().FLOAT(), false, 8 * Float.BYTES, 3 * Float.BYTES);
        Platforms.gl().enableVertexAttribArray(1);
        Platforms.gl().vertexAttribPointer(2, 2, Platforms.gl().FLOAT(), false, 8 * Float.BYTES, 6 * Float.BYTES);
        Platforms.gl().enableVertexAttribArray(2);

        Texture checkerTexture = createCheckerTexture(256, 256, 16);
        return new ModelHandle(
                vao,
                vbo,
                ebo,
                imported.indices.length,
                imported.vertexCount,
                imported.hasTexCoords,
                checkerTexture,
                imported.center,
                imported.radius);
    }

    @Override
    public void frameCamera(ModelHandle handle, Camera3D camera) {
        float distance = handle.radius * 2.4f;
        camera.position.set(handle.center.x, handle.center.y, handle.center.z + distance);
        camera.target.set(handle.center);
        camera.updateViewFirstPerson();
    }

    @Override
    public void render(ModelHandle handle, Camera3D camera) {
        int width = Platforms.get().getFrameBufferWidth();
        int height = Platforms.get().getFrameBufferHeight();
        float aspect = width <= 0 || height <= 0 ? 1f : ((float) width / (float) height);

        camera.updateViewFirstPerson();
        Matrix4f projection = new Matrix4f()
                .perspective((float) Math.toRadians((float) camera.fov), aspect, 0.01f, Math.max(1000f, handle.radius * 20f));

        meshShader.use();
        meshShader.setMat4("model", modelMatrix);
        meshShader.setMat4("view", camera.view);
        meshShader.setMat4("projection", projection);
        meshShader.setVec4("solidColor", new Vector4f(0.7f, 0.85f, 1.0f, 1.0f));
        meshShader.setVec3("lightDir", new Vector3f(0.5f, -1.0f, 0.2f));
        if (handle.texture != null && handle.hasTexCoords) {
            meshShader.setBool("useTexture", true);
            meshShader.setTexture("albedoTex", handle.texture, Platforms.gl().TEXTURE0(), 0);
        } else {
            meshShader.setBool("useTexture", false);
        }

        handle.vao.bind();
        Platforms.gl().bindBuffer(Platforms.gl().ELEMENT_ARRAY_BUFFER(), handle.ebo);
        Platforms.gl().drawElements(Platforms.gl().TRIANGLES(), handle.indexCount, Platforms.gl().UNSIGNED_INT(), 0);
    }

    @Override
    public void dispose(ModelHandle handle) {
        if (handle == null) {
            return;
        }
        Platforms.gl().deleteBuffers(handle.ebo);
        handle.vbo.delete();
        handle.vao.delete();
    }

    private ImportedModel importWithAssimp(String absoluteModelPath) throws IOException {
        int flags = Assimp.aiProcess_Triangulate
                | Assimp.aiProcess_JoinIdenticalVertices
                | Assimp.aiProcess_GenSmoothNormals;
        AIScene scene = Assimp.aiImportFile(absoluteModelPath, flags);
        if (scene == null || scene.mMeshes() == null || scene.mNumMeshes() == 0) {
            throw new IOException("Assimp failed to import model: " + Assimp.aiGetErrorString());
        }

        List<Float> vertexData = new ArrayList<>();
        List<Integer> indexData = new ArrayList<>();
        boolean hasTexCoords = false;
        int baseVertex = 0;
        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
        Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);

        try {
            for (int meshIndex = 0; meshIndex < scene.mNumMeshes(); meshIndex++) {
                AIMesh mesh = AIMesh.create(scene.mMeshes().get(meshIndex));
                AIVector3D.Buffer verts = mesh.mVertices();
                if (verts == null) {
                    continue;
                }
                AIVector3D.Buffer normals = mesh.mNormals();
                AIVector3D.Buffer texCoords = mesh.mTextureCoords(0);

                for (int i = 0; i < verts.remaining(); i++) {
                    AIVector3D p = verts.get(i);
                    Vector3f v = new Vector3f(p.x(), p.y(), p.z());
                    vertexData.add(v.x);
                    vertexData.add(v.y);
                    vertexData.add(v.z);

                    if (normals != null && i < normals.remaining()) {
                        AIVector3D n = normals.get(i);
                        vertexData.add(n.x());
                        vertexData.add(n.y());
                        vertexData.add(n.z());
                    } else {
                        vertexData.add(0f);
                        vertexData.add(0f);
                        vertexData.add(1f);
                    }

                    if (texCoords != null && i < texCoords.remaining()) {
                        AIVector3D t = texCoords.get(i);
                        vertexData.add(t.x());
                        vertexData.add(t.y());
                        hasTexCoords = true;
                    } else {
                        vertexData.add(0f);
                        vertexData.add(0f);
                    }

                    min.min(v);
                    max.max(v);
                }

                for (int faceIndex = 0; faceIndex < mesh.mNumFaces(); faceIndex++) {
                    AIFace face = mesh.mFaces().get(faceIndex);
                    IntBuffer faceIndices = face.mIndices();
                    if (faceIndices == null || faceIndices.remaining() < 3) {
                        continue;
                    }
                    indexData.add(baseVertex + faceIndices.get(0));
                    indexData.add(baseVertex + faceIndices.get(1));
                    indexData.add(baseVertex + faceIndices.get(2));
                }
                baseVertex += verts.remaining();
            }
        } finally {
            Assimp.aiReleaseImport(scene);
        }

        if (vertexData.isEmpty() || indexData.isEmpty()) {
            throw new IOException("Imported model has no geometry: " + Path.of(absoluteModelPath).getFileName());
        }

        float[] verts = new float[vertexData.size()];
        for (int i = 0; i < vertexData.size(); i++) {
            verts[i] = vertexData.get(i);
        }
        int[] indices = new int[indexData.size()];
        for (int i = 0; i < indexData.size(); i++) {
            indices[i] = indexData.get(i);
        }

        Vector3f center = new Vector3f(min).add(max).mul(0.5f);
        float radius = 0f;
        for (int i = 0; i < verts.length; i += 8) {
            radius = Math.max(radius, new Vector3f(verts[i], verts[i + 1], verts[i + 2]).sub(center).length());
        }
        if (radius < 0.001f) {
            radius = 1f;
        }

        return new ImportedModel(verts, indices, verts.length / 8, hasTexCoords, center, radius);
    }

    private Texture createCheckerTexture(int width, int height, int cellSize) {
        ByteBuffer buf = ByteBuffer.allocateDirect(width * height * 4);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean dark = (((x / cellSize) + (y / cellSize)) & 1) == 0;
                int c = dark ? 35 : 220;
                buf.put((byte) c);
                buf.put((byte) c);
                buf.put((byte) c);
                buf.put((byte) 255);
            }
        }
        buf.flip();
        Texture t = new Texture("generated-checker", buf, width, height);
        t.initGL();
        return t;
    }

    private static class ImportedModel {
        final float[] vertices;
        final int[] indices;
        final int vertexCount;
        final boolean hasTexCoords;
        final Vector3f center;
        final float radius;

        ImportedModel(float[] vertices, int[] indices, int vertexCount, boolean hasTexCoords, Vector3f center, float radius) {
            this.vertices = vertices;
            this.indices = indices;
            this.vertexCount = vertexCount;
            this.hasTexCoords = hasTexCoords;
            this.center = center;
            this.radius = radius;
        }
    }
}
