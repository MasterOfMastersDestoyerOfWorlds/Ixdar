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
     * GPU-resident handle for a renderable model: bound VAO/VBO/EBO triple
     * plus the optional diffuse texture and bounding-sphere metadata. The
     * triangle count is derived from {@code indexCount / 3}; {@code center}
     * is defensively copied.
     *
     * @param vao vertex-array object binding the attribute layout
     * @param vbo vertex-buffer object holding interleaved vertex data
     * @param ebo element-buffer name (raw GL handle) for indexed draws
     * @param indexCount number of indices in the EBO
     * @param vertexCount number of unique vertices in the VBO
     * @param hasTexCoords {@code true} when the VBO interleaves UVs
     * @param texture diffuse texture, or {@code null} for untextured models
     * @param center world-space bounding-sphere center (copied)
     * @param radius world-space bounding-sphere radius
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
