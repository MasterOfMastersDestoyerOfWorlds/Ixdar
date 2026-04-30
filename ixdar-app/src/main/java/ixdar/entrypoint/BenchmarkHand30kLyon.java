package ixdar.entrypoint;

import java.nio.file.Path;
import java.nio.file.Paths;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.PatchRenderer;
import ixdar.geometry.mesh.quadlayout.field.PrecomputedFieldImporter;
import ixdar.geometry.mesh.quadlayout.integergrid.SeamlessParameterization;
import ixdar.geometry.mesh.quadlayout.lyon2021.LyonLayoutDecomposer;
import ixdar.geometry.mesh.quadlayout.lyon2021.LyonMetrics;
import ixdar.geometry.mesh.quadlayout.lyon2021.QuadLayoutExtractor;
import ixdar.geometry.mesh.quadlayout.quantization.Quantization;
import ixdar.geometry.mesh.quadlayout.quantization.StripEquivalence;
import ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.tmesh.TMesh;

/**
 * End-to-end Lyon pipeline benchmark + colored-patch overlay render on
 * Hand-30k (metriko baseline).
 *
 * <pre>
 *   mvn -pl ixdar-app exec:java \
 *       -Dexec.mainClass=ixdar.entrypoint.BenchmarkHand30kLyon
 * </pre>
 */
public final class BenchmarkHand30kLyon {

    private BenchmarkHand30kLyon() {}

    public static void main(String[] args) throws Exception {
        Path baseline = args.length > 0
                ? Paths.get(args[0])
                : Paths.get("ixdar-app/test/resources/quadlayout/baseline-hand");

        Path obj = baseline.resolve("Hand-tri-30k.obj");
        PrecomputedFieldImporter.Result boot = PrecomputedFieldImporter.load(
                obj,
                baseline.resolve("stage1_extrinsic_field.tsv"),
                baseline.resolve("stage1_matching.txt"),
                baseline.resolve("stage1_seam.txt"),
                baseline.resolve("stage1_singular.txt"));
        ArrayMesh mesh = boot.mesh();
        float[][] uv = PrecomputedFieldImporter.loadStage2Uv(
                baseline.resolve("stage2_uv_corners.tsv"), mesh.faceCount());
        SeamlessParameterization param = SeamlessParameterization.fromExternal(
                mesh, uv[0], uv[1], true);

        MotorcycleGraph.Result graph = MotorcycleGraph.trace(
                param, mesh, boot.field(), boot.combed(), boot.singularities());
        TMesh tmesh = TMesh.build(graph, param);
        StripEquivalence.Result strips = StripEquivalence.compute(tmesh);

        Quantization.Result qres = Quantization.solve(tmesh);
        int[] q = qres.feasible() ? qres.arcQuantization()
                : new int[tmesh.arcs().size()];
        if (!qres.feasible()) java.util.Arrays.fill(q, 1);

        var trs = ixdar.geometry.mesh.quadlayout.extraction.TransitionMatrix.compute(
                mesh, uv[0], uv[1], boot.combed());
        QuadLayoutExtractor.Result lr = QuadLayoutExtractor.extract(tmesh, q,
                mesh, uv[0], uv[1], trs);
        LyonMetrics.Result m = LyonMetrics.compute(lr.layout(), tmesh, mesh, trs);

        var diag = LyonLayoutDecomposer.decompose(mesh, tmesh, lr.layout());
        var multiview = PatchRenderer.renderFeatureEdgeMultiview(
                mesh, diag, PatchRenderer.OverlayMode.PATCHES_VS_CREST, 1.0f);
        Path outPng = Paths.get("/tmp/lyon-hand30k-overlay.png");
        javax.imageio.ImageIO.write(multiview.composite(), "PNG", outPng.toFile());

        System.out.println();
        System.out.println("==== Lyon 2021 — HAND-30k ====");
        System.out.printf("  #Faces=%d  #Sing=%d  #Arcs=%d  #Vars=%d  #P=%d  dmean=%.1f°  dmax=%.1f°%n",
                mesh.faceCount(), boot.singularities().size(),
                tmesh.arcs().size(), strips.classCount(), m.patchCount(),
                m.dmeanDeg(), m.dmaxDeg());
        System.out.printf("  conforming = %d quads + %d triangles  (skipped %d)%n",
                lr.layout().patches().size(),
                lr.layout().triangles().size(),
                lr.skippedPatchIds().size());
        System.out.printf("  ILP feasible=%s  objective=%.1f%n",
                qres.feasible(), qres.objectiveValue());
        System.out.printf("  rendered overlay -> %s%n", outPng);
    }
}
