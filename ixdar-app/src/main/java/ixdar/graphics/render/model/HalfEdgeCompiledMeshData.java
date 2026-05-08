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
     * GPU-ready surface data compiled from a half-edge mesh: an interleaved
     * float buffer of position/normal/UV vertices, a triangle index buffer,
     * and bounding-volume metadata (axis-aligned box plus sphere).
     *
     * @param vertices interleaved {@code (px, py, pz, nx, ny, nz, u, v)} per vertex
     * @param indices triangle indices into {@code vertices}
     * @param vertexCount number of unique vertices represented in {@code vertices}
     * @param faceCount number of triangles ({@code indices.length / 3})
     * @param minBounds world-space axis-aligned bounding-box minimum
     * @param maxBounds world-space axis-aligned bounding-box maximum
     * @param center world-space bounding-sphere center
     * @param radius world-space bounding-sphere radius
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
