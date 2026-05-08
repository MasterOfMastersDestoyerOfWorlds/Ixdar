package ixdar.graphics.render.model;

import ixdar.graphics.render.Texture;
import ixdar.graphics.render.shaders.VertexArrayObject;
import ixdar.graphics.render.shaders.VertexBufferObject;
import org.joml.Vector3f;

public class ModelHandle {
    public static final int NUM_3 = 3;
    public final VertexArrayObject vao;
    public final VertexBufferObject vbo;
    public final int ebo;
    public final int indexCount;
    public final int vertexCount;
    public final int triangleCount;
    public final boolean hasTexCoords;
    public final Texture texture;
    public final Vector3f center;
    public final float radius;

    /**
     * TODO: document {@code ModelHandle}.
     *
     * @param vao TODO: describe
     * @param vbo TODO: describe
     * @param ebo TODO: describe
     * @param indexCount TODO: describe
     * @param vertexCount TODO: describe
     * @param hasTexCoords TODO: describe
     * @param texture TODO: describe
     * @param center TODO: describe
     * @param radius TODO: describe
     */
    public ModelHandle(
            VertexArrayObject vao,
            VertexBufferObject vbo,
            int ebo,
            int indexCount,
            int vertexCount,
            boolean hasTexCoords,
            Texture texture,
            Vector3f center,
            float radius) {
        this.vao = vao;
        this.vbo = vbo;
        this.ebo = ebo;
        this.indexCount = indexCount;
        this.vertexCount = vertexCount;
        this.triangleCount = indexCount / NUM_3;
        this.hasTexCoords = hasTexCoords;
        this.texture = texture;
        this.center = new Vector3f(center);
        this.radius = radius;
    }
}
