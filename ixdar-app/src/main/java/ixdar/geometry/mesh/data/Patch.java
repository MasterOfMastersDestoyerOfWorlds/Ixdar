package ixdar.geometry.mesh.data;

public record Patch(
        int id,
        int[] vertexIndices,
        int[] faceIndices,
        int branchId,
        float[] centroid,
        float curvatureMean,
        String color) {
}
