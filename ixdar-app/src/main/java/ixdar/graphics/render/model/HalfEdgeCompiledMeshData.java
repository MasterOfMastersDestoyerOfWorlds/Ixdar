package ixdar.graphics.render.model;

import org.joml.Vector3f;

public class HalfEdgeCompiledMeshData {
    public final float[] vertices;
    public final int[] indices;
    public final int vertexCount;
    public final int faceCount;
    public final Vector3f minBounds;
    public final Vector3f maxBounds;
    public final Vector3f center;
    public final float radius;

    /**
     * TODO: document {@code HalfEdgeCompiledMeshData}.
     *
     * @param vertices TODO: describe
     * @param indices TODO: describe
     * @param vertexCount TODO: describe
     * @param faceCount TODO: describe
     * @param minBounds TODO: describe
     * @param maxBounds TODO: describe
     * @param center TODO: describe
     * @param radius TODO: describe
     */
    public HalfEdgeCompiledMeshData(
            float[] vertices,
            int[] indices,
            int vertexCount,
            int faceCount,
            Vector3f minBounds,
            Vector3f maxBounds,
            Vector3f center,
            float radius) {
        this.vertices = vertices;
        this.indices = indices;
        this.vertexCount = vertexCount;
        this.faceCount = faceCount;
        this.minBounds = minBounds;
        this.maxBounds = maxBounds;
        this.center = center;
        this.radius = radius;
    }
}
