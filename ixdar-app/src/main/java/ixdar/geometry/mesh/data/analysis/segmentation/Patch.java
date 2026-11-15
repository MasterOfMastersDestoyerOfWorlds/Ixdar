package ixdar.geometry.mesh.data.analysis.segmentation;

import org.joml.Vector3f;

import ixdar.graphics.render.color.Color;

public record Patch(
        int id,
        int[] vertexIndices,
        int[] faceIndices,
        int branchId,
        Vector3f centroid,
        float curvatureMean,
        Color color) {
}
