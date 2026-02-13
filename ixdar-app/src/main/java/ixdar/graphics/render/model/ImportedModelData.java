package ixdar.graphics.render.model;

import org.joml.Vector3f;

public class ImportedModelData {
    public final float[] vertices;
    public final int[] indices;
    public final int vertexCount;
    public final boolean hasTexCoords;
    public final Vector3f center;
    public final float radius;

    public ImportedModelData(float[] vertices, int[] indices, int vertexCount, boolean hasTexCoords, Vector3f center, float radius) {
        this.vertices = vertices;
        this.indices = indices;
        this.vertexCount = vertexCount;
        this.hasTexCoords = hasTexCoords;
        this.center = center;
        this.radius = radius;
    }
}
