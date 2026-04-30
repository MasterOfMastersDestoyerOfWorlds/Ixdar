package ixdar.entrypoint;

import java.nio.file.Path;
import java.nio.file.Paths;
import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.MeshLoader;
import ixdar.geometry.mesh.quadlayout.field.PrecomputedFieldImporter;
import ixdar.geometry.mesh.quadlayout.integergrid.SeamlessParameterization;

public final class UvSanity {
    public static void main(String[] args) throws Exception {
        Path baseDir = Paths.get("ixdar-app/test/resources/quadlayout/baseline-hand");
        ArrayMesh mesh = MeshLoader.load(baseDir.resolve("Hand-tri-30k.obj").toString());
        float[][] uv = PrecomputedFieldImporter.loadStage2Uv(baseDir.resolve("stage2_uv_corners.tsv"), mesh.faceCount());
        SeamlessParameterization p = SeamlessParameterization.fromExternal(mesh, uv[0], uv[1], true);
        int F = mesh.faceCount();
        int positive = 0, zero = 0, negative = 0;
        double minArea = 1e9, maxArea = -1e9;
        for (int f = 0; f < F; f++) {
            float a = p.uvSignedArea(f);
            if (a > 1e-9f) positive++;
            else if (a < -1e-9f) negative++;
            else zero++;
            if (a < minArea) minArea = a;
            if (a > maxArea) maxArea = a;
        }
        System.out.printf("F=%d pos=%d (%.1f%%) zero=%d neg=%d  signed-area range=[%.4g, %.4g]%n",
                F, positive, 100.0 * positive / F, zero, negative, minArea, maxArea);
    }
}
