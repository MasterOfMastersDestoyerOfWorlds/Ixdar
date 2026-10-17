package ixdar.geometry.mesh.data;

import ixdar.graphics.render.color.Color;

public record Patch(
        int id,
        int[] vertexIndices,
        int[] faceIndices,
        int branchId,
        float[] centroid,
        float curvatureMean,
        Color color) {
}
