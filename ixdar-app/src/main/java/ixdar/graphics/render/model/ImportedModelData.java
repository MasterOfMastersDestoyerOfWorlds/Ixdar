package ixdar.graphics.render.model;

import org.joml.Vector3f;

public class ImportedModelData {
    public final float[] vertices;
    public final int[] indices;
    public final int vertexCount;
    public final boolean hasTexCoords;
    public final Vector3f center;
    public final float radius;

    /**
     * Raw output of an external model importer (Assimp): an interleaved
     * float buffer of position/normal/UV vertices, a triangle index buffer,
     * a flag indicating whether real UVs were present in the source, and
     * a bounding sphere. Consumed by the runtime when uploading to the GPU.
     *
     * @param vertices interleaved {@code (px, py, pz, nx, ny, nz, u, v)} per vertex
     * @param indices triangle indices into {@code vertices}
     * @param vertexCount number of unique vertices ({@code vertices.length / 8})
     * @param hasTexCoords {@code true} when the source supplied UV coordinates
     * @param center world-space bounding-sphere center
     * @param radius world-space bounding-sphere radius
     */
    public ImportedModelData(float[] vertices, int[] indices, int vertexCount, boolean hasTexCoords, Vector3f center, float radius) {
        this.vertices = vertices;
        this.indices = indices;
        this.vertexCount = vertexCount;
        this.hasTexCoords = hasTexCoords;
        this.center = center;
        this.radius = radius;
    }
}
