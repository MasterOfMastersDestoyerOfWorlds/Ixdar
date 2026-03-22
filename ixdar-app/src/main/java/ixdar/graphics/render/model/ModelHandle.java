package ixdar.graphics.render.model;

import ixdar.graphics.render.Texture;
import ixdar.graphics.render.shaders.VertexArrayObject;
import ixdar.graphics.render.shaders.VertexBufferObject;
import org.joml.Vector3f;

public class ModelHandle {
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
        this.triangleCount = indexCount / 3;
        this.hasTexCoords = hasTexCoords;
        this.texture = texture;
        this.center = new Vector3f(center);
        this.radius = radius;
    }
}
