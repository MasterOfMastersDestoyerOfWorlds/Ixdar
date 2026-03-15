package ixdar.graphics.render.model;

import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.Texture;
import ixdar.graphics.render.shaders.MeshShader;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.graphics.render.shaders.VertexArrayObject;
import ixdar.graphics.render.shaders.VertexBufferObject;
import ixdar.platform.Platforms;
import ixdar.platform.file.FileManagement;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

public class AssimpModelRuntime implements ModelRuntime {

    private final ShaderProgram meshShader;
    private final AssimpModelImporter importer = new AssimpModelImporter();
    private final Matrix4f modelMatrix = new Matrix4f();

    public AssimpModelRuntime() throws Exception {
        this.meshShader = ShaderProgram.ShaderType.Mesh.getShader();
        this.meshShader.init();
    }

    @Override
    public ModelHandle loadFromAssetRepo(String modelFileName) throws Exception {
        String absoluteModelPath = FileManagement.resolveAssetPath(modelFileName);
        ImportedModelData imported = importer.importFromFile(absoluteModelPath);

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
        if (handle.texture != null) {
            handle.texture.delete();
        }
        Platforms.gl().deleteBuffers(handle.ebo);
        handle.vbo.delete();
        handle.vao.delete();
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
}
