package ixdar.geometry.mesh.data.analysis.segmentation;

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
