package ixdar.graphics.render.model;

import ixdar.graphics.render.Texture;
import org.joml.Vector3f;

public class ModelHandle {
    public final VertexBuffer buffer;
    public final int vertexCount;
    public final int triangleCount;
    public final boolean hasTexCoords;
    public final Texture texture;
    public final Vector3f center;
    public final float radius;

    /**
     * GPU-resident handle for a renderable model: its indexed vertex buffer plus the optional
     * diffuse texture and bounding-sphere metadata. {@code center} is defensively copied.
     *
     * @param buffer uploaded vertices and triangle indices
     * @param vertexCount number of unique vertices in the buffer
     * @param hasTexCoords {@code true} when the vertices carry real UVs
     * @param texture diffuse texture, or {@code null} for untextured models
     * @param center world-space bounding-sphere center (copied)
     * @param radius world-space bounding-sphere radius
     */
    public ModelHandle(
            VertexBuffer buffer,
            int vertexCount,
            boolean hasTexCoords,
            Texture texture,
            Vector3f center,
            float radius) {
        this.buffer = buffer;
        this.vertexCount = vertexCount;
        this.triangleCount = buffer.indexCount / 3;
        this.hasTexCoords = hasTexCoords;
        this.texture = texture;
        this.center = new Vector3f(center);
        this.radius = radius;
    }
}
