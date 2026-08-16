package ixdar.graphics.render.model;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;

import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.Texture;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.graphics.render.shaders.VertexArrayObject;
import ixdar.graphics.render.shaders.VertexBufferObject;
import ixdar.platform.Platforms;
import ixdar.platform.file.FileManagement;

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
     * Initialize the runtime's mesh shader. Must be called on a thread with
     * a current GL context.
     *
     * @throws Exception if shader compilation or linking fails.
     */
    public AssimpModelRuntime() throws Exception {
        this.meshShader = ShaderProgram.ShaderType.Mesh.getShader();
        this.meshShader.init();
    }

    /**
     * Resolve {@code modelFileName} against the asset repository, import the
     * model with Assimp, upload its vertex/index buffers and a generated
     * checker texture, and return a {@link ModelHandle} that owns the GL
     * resources.
     *
     * @param modelFileName asset-relative path passed to
     *        {@link FileManagement#resolveAssetPath(String)}.
     * @throws Exception if the file cannot be resolved or imported.
     * @return new handle wrapping the uploaded VAO/VBO/EBO and bounds.
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
     * Position {@code camera} so the entire bounding sphere of {@code handle}
     * is visible: target the model center and pull back along +Z by
     * {@code 2.4 * radius}.
     *
     * @param handle model whose center/radius drives the framing.
     * @param camera camera mutated in-place; view matrix is refreshed.
     */
    @Override
    public void frameCamera(ModelHandle handle, Camera3D camera) {
        float distance = handle.radius * NUM_2_4;
        camera.position.set(handle.center.x, handle.center.y, handle.center.z + distance);
        camera.target.set(handle.center);
        camera.updateViewFirstPerson();
    }

    /**
     * Draw the model: build a perspective projection from the current
     * framebuffer aspect, bind the mesh shader with model/view/projection
     * and a fixed solid color + light direction, optionally bind the
     * checker texture if the import provided UVs, then issue a single
     * indexed triangle draw.
     *
     * @param handle uploaded model resources to draw.
     * @param camera supplies view matrix, fov, and orientation.
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
     * Release the GL resources owned by {@code handle} (texture, EBO, VBO,
     * VAO). Safe to call with a {@code null} handle.
     *
     * @param handle model whose resources should be freed; ignored if null.
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
