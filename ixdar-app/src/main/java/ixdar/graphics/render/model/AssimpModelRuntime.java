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
    public static final String USETEXTURE = "useTexture";
    public static final int NUM_3 = 3;
    public static final int NUM_8 = 8;
    public static final int NUM_6 = 6;
    public static final int NUM_256 = 256;
    public static final int NUM_16 = 16;
    public static final float NUM_2_4 = 2.4f;
    public static final float NUM_1 = 1f;
    public static final float NUM_0_01 = 0.01f;
    public static final float NUM_1000 = 1000f;
    public static final float NUM_20 = 20f;
    public static final float NUM_0_7 = 0.7f;
    public static final float NUM_0_85 = 0.85f;
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_0_2 = 0.2f;
    public static final int NUM_4 = 4;
    public static final int NUM_35 = 35;
    public static final int NUM_220 = 220;
    public static final int NUM_255 = 255;

    private final ShaderProgram meshShader;
    private final AssimpModelImporter importer = new AssimpModelImporter();
    private final Matrix4f modelMatrix = new Matrix4f();

    /**
     * TODO: document {@code AssimpModelRuntime}.
     *
     * @throws Exception TODO: describe
     */
    public AssimpModelRuntime() throws Exception {
        this.meshShader = ShaderProgram.ShaderType.Mesh.getShader();
        this.meshShader.init();
    }

    /**
     * TODO: document {@code loadFromAssetRepo}.
     *
     * @param modelFileName TODO: describe
     * @throws Exception TODO: describe
     * @return TODO: describe
     */
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
        Platforms.gl().vertexAttribPointer(0, NUM_3, Platforms.gl().FLOAT(), false, NUM_8 * Float.BYTES, 0);
        Platforms.gl().enableVertexAttribArray(0);
        Platforms.gl().vertexAttribPointer(1, NUM_3, Platforms.gl().FLOAT(), false, NUM_8 * Float.BYTES, NUM_3 * Float.BYTES);
        Platforms.gl().enableVertexAttribArray(1);
        Platforms.gl().vertexAttribPointer(2, 2, Platforms.gl().FLOAT(), false, NUM_8 * Float.BYTES, NUM_6 * Float.BYTES);
        Platforms.gl().enableVertexAttribArray(2);

        Texture checkerTexture = createCheckerTexture(NUM_256, NUM_256, NUM_16);
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

    /**
     * TODO: document {@code frameCamera}.
     *
     * @param handle TODO: describe
     * @param camera TODO: describe
     */
    @Override
    public void frameCamera(ModelHandle handle, Camera3D camera) {
        float distance = handle.radius * NUM_2_4;
        camera.position.set(handle.center.x, handle.center.y, handle.center.z + distance);
        camera.target.set(handle.center);
        camera.updateViewFirstPerson();
    }

    /**
     * TODO: document {@code render}.
     *
     * @param handle TODO: describe
     * @param camera TODO: describe
     */
    @Override
    public void render(ModelHandle handle, Camera3D camera) {
        int width = Platforms.get().getFrameBufferWidth();
        int height = Platforms.get().getFrameBufferHeight();
        float aspect = width <= 0 || height <= 0 ? NUM_1 : ((float) width / (float) height);

        camera.updateViewFirstPerson();
        Matrix4f projection = new Matrix4f()
                .perspective((float) Math.toRadians((float) camera.fov), aspect, NUM_0_01, Math.max(NUM_1000, handle.radius * NUM_20));

        meshShader.use();
        meshShader.setMat4("model", modelMatrix);
        meshShader.setMat4("view", camera.view);
        meshShader.setMat4("projection", projection);
        meshShader.setVec4("solidColor", new Vector4f(NUM_0_7, NUM_0_85, 1.0f, 1.0f));
        meshShader.setVec3("lightDir", new Vector3f(NUM_0_5, -1.0f, NUM_0_2));
        if (handle.texture != null && handle.hasTexCoords) {
            meshShader.setBool(USETEXTURE, true);
            meshShader.setTexture("albedoTex", handle.texture, Platforms.gl().TEXTURE0(), 0);
        } else {
            meshShader.setBool(USETEXTURE, false);
        }

        handle.vao.bind();
        Platforms.gl().bindBuffer(Platforms.gl().ELEMENT_ARRAY_BUFFER(), handle.ebo);
        Platforms.gl().drawElements(Platforms.gl().TRIANGLES(), handle.indexCount, Platforms.gl().UNSIGNED_INT(), 0);
    }

    /**
     * TODO: document {@code dispose}.
     *
     * @param handle TODO: describe
     */
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
        ByteBuffer buf = ByteBuffer.allocateDirect(width * height * NUM_4);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean dark = (((x / cellSize) + (y / cellSize)) & 1) == 0;
                int c = dark ? NUM_35 : NUM_220;
                buf.put((byte) c);
                buf.put((byte) c);
                buf.put((byte) c);
                buf.put((byte) NUM_255);
            }
        }
        buf.flip();
        Texture t = new Texture("generated-checker", buf, width, height);
        t.initGL();
        return t;
    }
}
