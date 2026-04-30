package ixdar.entrypoint;

import java.nio.file.Path;
import java.nio.file.Paths;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.field.PrecomputedFieldImporter;
import ixdar.geometry.mesh.quadlayout.integergrid.SeamlessParameterization;
import ixdar.geometry.mesh.quadlayout.lyon2021.LyonMetrics;
import ixdar.geometry.mesh.quadlayout.lyon2021.QuadLayoutExtractor;
import ixdar.geometry.mesh.quadlayout.quantization.StripEquivalence;
import ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.tmesh.TMesh;

/**
 * Lyon-Table-1 pipeline benchmark on ROCKERARM, using metriko's
 * precomputed stage1/stage2 outputs (cross field, matching, seam,
 * singularities, IGM). This bypasses our (currently slow) cross-field +
 * IGM stages so we can measure the Lyon-specific stages (motorcycle,
 * T-mesh, strip reduction, layout, metrics) on a paper benchmark mesh.
 *
 * <pre>
 *   mvn -pl ixdar-app exec:java \
 *       -Dexec.mainClass=ixdar.entrypoint.BenchmarkRockerArmLyon
 * </pre>
 */
public final class BenchmarkRockerArmLyon {

    private BenchmarkRockerArmLyon() {}

    public static void main(String[] args) throws Exception {
        Path baseline = args.length > 0
                ? Paths.get(args[0])
                : Paths.get("ixdar-app/test/resources/quadlayout/baseline-rocker-arm");

        Path obj = baseline.resolve("rocker-arm.obj");
        Path stage1Field = baseline.resolve("stage1_extrinsic_field.tsv");
        Path stage1Match = baseline.resolve("stage1_matching.txt");
        Path stage1Seam  = baseline.resolve("stage1_seam.txt");
        Path stage1Sing  = baseline.resolve("stage1_singular.txt");
        Path stage2 = baseline.resolve("stage2_uv_corners.tsv");

        long t0 = System.currentTimeMillis();
        PrecomputedFieldImporter.Result boot = PrecomputedFieldImporter.load(
                obj, stage1Field, stage1Match, stage1Seam, stage1Sing);
        ArrayMesh mesh = boot.mesh();
        float[][] uv = PrecomputedFieldImporter.loadStage2Uv(stage2, mesh.faceCount());
        SeamlessParameterization param = SeamlessParameterization.fromExternal(
                mesh, uv[0], uv[1], true);
        long tBoot = System.currentTimeMillis() - t0;
        System.out.printf("[bench-lyon] bootstrap (load+stage1+stage2)=%dms F=%d V=%d sing=%d%n",
                tBoot, mesh.faceCount(), mesh.vertexCount(), boot.singularities().size());

        long t1 = System.currentTimeMillis();
        MotorcycleGraph.Result graph = MotorcycleGraph.trace(
                param, mesh, boot.field(), boot.combed(), boot.singularities());
        long tMcg = System.currentTimeMillis() - t1;

        long t2 = System.currentTimeMillis();
        TMesh tmesh = TMesh.build(graph, param);
        long tTmesh = System.currentTimeMillis() - t2;
        System.out.printf("[bench-lyon] motorcycle=%dms traces=%d crashes=%d%n",
                tMcg, graph.traces().size(), graph.crashes().size());
        // PATCH-91 H1 diagnostic.
        System.out.printf("[bench-lyon] sing-launch-count hist (idx=#motorcycles): %s  total=%d boundary=%d%n",
                java.util.Arrays.toString(
                    ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statSingLaunchCount),
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statSingTotal,
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statSingBoundaryCardinals);
        // PATCH-91 H2 diagnostic.
        System.out.printf("[bench-lyon] triangles: with-sing-corner=%d  all-intersection=%d%n",
                ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statTrianglesWithSingularityCorner,
                ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statTrianglesAllIntersection);
        for (String dump : ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.triangleDumps) {
            System.out.println("[bench-lyon] " + dump);
        }
        System.out.printf("[bench-lyon] T-mesh=%dms arcs=%d patches=%d%n",
                tTmesh, tmesh.arcs().size(), tmesh.patches().size());
        System.out.println("[bench-lyon] side-count hist=" +
                java.util.Arrays.toString(
                        ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statSideHistogram));
        System.out.printf("[bench-lyon] enum stats: halfArcs=%d linkable=%d facesWalked=%d short=%d nonQuad=%d emitted=%d%n",
                ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statHalfArcs,
                ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statHalfArcsLinkable,
                ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statFacesWalked,
                ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statFacesShortCycle,
                ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statFacesNonQuad,
                ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statFacesEmittedAsPatches);

        long t3 = System.currentTimeMillis();
        StripEquivalence.Result strips = StripEquivalence.compute(tmesh);
        long tStrips = System.currentTimeMillis() - t3;
        System.out.printf("[bench-lyon] strips=%dms classes=%d (was %d arcs)%n",
                tStrips, strips.classCount(), tmesh.arcs().size());
        StripEquivalence.dumpStats(strips);
        // D1: face-length histogram (compress to readable buckets).
        int[] flh = ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statFaceLengthHist;
        StringBuilder flb = new StringBuilder("[bench-lyon] face-length hist: ");
        // Show non-zero buckets.
        for (int i = 0; i < flh.length; i++) {
            if (flh[i] > 0) flb.append(i).append("=").append(flh[i]).append(' ');
        }
        System.out.println(flb);
        // D2: arc-incidence.
        int[] aih = ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statArcIncidenceHist;
        System.out.printf("[bench-lyon] arc-incidence hist (0,1,2,3+): %d %d %d %d%n",
                aih[0], aih[1], aih[2],
                aih[3] + aih[4] + aih[5] + aih[6] + aih[7]);

        // Real ILP solve (PATCH-76: UB tightening + 10s time cap).
        long tIlp0 = System.currentTimeMillis();
        var qres = ixdar.geometry.mesh.quadlayout.quantization.Quantization.solve(tmesh);
        long tIlpMs = System.currentTimeMillis() - tIlp0;
        int[] q = qres.feasible() ? qres.arcQuantization()
                : new int[tmesh.arcs().size()];
        if (!qres.feasible()) java.util.Arrays.fill(q, 1);
        System.out.printf("[bench-lyon] ILP=%dms feasible=%s objective=%.1f%n",
                tIlpMs, qres.feasible(), qres.objectiveValue());

        long t4 = System.currentTimeMillis();
        var trs = ixdar.geometry.mesh.quadlayout.extraction.TransitionMatrix.compute(
                mesh, uv[0], uv[1], boot.combed());
        QuadLayoutExtractor.Result lr = QuadLayoutExtractor.extract(tmesh, q,
                mesh, uv[0], uv[1], trs);
        long tLayout = System.currentTimeMillis() - t4;
        System.out.printf("[bench-lyon] layout=%dms #P=%d skipped=%d tJunctions=%d%n",
                tLayout, lr.layout().patchCount(),
                lr.skippedPatchIds().size(),
                lr.layout().tJunctionsResolved());

        long t5 = System.currentTimeMillis();
        LyonMetrics.Result m = LyonMetrics.compute(lr.layout(), tmesh, mesh, trs);
        long tMetric = System.currentTimeMillis() - t5;

        // Goal #2: render Lyon layout as colored patches via PatchRenderer.
        long tRender0 = System.currentTimeMillis();
        var diag = ixdar.geometry.mesh.quadlayout.lyon2021.LyonLayoutDecomposer
                .decompose(mesh, tmesh, lr.layout());
        var multiview = ixdar.geometry.mesh.data.PatchRenderer.renderFeatureEdgeMultiview(
                mesh, diag,
                ixdar.geometry.mesh.data.PatchRenderer.OverlayMode.PATCHES_VS_CREST,
                1.0f);
        java.nio.file.Path outPng = java.nio.file.Paths.get(
                "/tmp/lyon-rocker-arm-overlay.png");
        try {
            javax.imageio.ImageIO.write(multiview.composite(), "PNG",
                    outPng.toFile());
            System.out.printf("[bench-lyon] rendered overlay -> %s in %dms%n",
                    outPng, System.currentTimeMillis() - tRender0);
        } catch (java.io.IOException ioe) {
            System.out.printf("[bench-lyon] render failed: %s%n", ioe.getMessage());
        }
        System.out.printf("[bench-lyon] metrics=%dms%n", tMetric);

        long total = System.currentTimeMillis() - t0;

        System.out.println();
        // PATCH-83 checkpoint: Lyon Table 1 row + ratio.
        double alphaDeg = Math.toDegrees(
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.defaultAlpha());
        System.out.printf("==== Lyon 2021 Table 1 — ROCKERARM   (α=%.0f°) ====%n", alphaDeg);
        System.out.printf("%-12s | %-15s | %-10s%n", "metric", "ours", "paper (α=15°)");
        System.out.println("-------------+-----------------+----------");
        System.out.printf("%-12s | %-15d | %-10d%n", "#Faces", mesh.faceCount(), 20088);
        System.out.printf("%-12s | %-15d | %-10d%n", "#Sing",
                boot.singularities().size(), 36);
        System.out.printf("%-12s | %-15d | %-10d%n", "#Traces",
                graph.traces().size(), 144);
        System.out.printf("%-12s | %-15d | %-10d%n", "#Arcs",
                tmesh.arcs().size(), 2742);
        System.out.printf("%-12s | %-15d | %-10d%n", "#Vars",
                strips.classCount(), 192);
        System.out.printf("%-12s | %-15d | %-10d%n",
                "#P", lr.layout().patchCount(), 159);
        System.out.printf("%-12s | %-15.1f | %-10.1f%n", "dmean (deg)",
                m.dmeanDeg(), 3.7);
        System.out.printf("%-12s | %-15.1f | %-10.1f%n", "dmax (deg)",
                m.dmaxDeg(), 14.7);
        System.out.printf("%-12s | %-15s | %-10s   (PATCH-75 LCBK19)%n",
                "MSJavg", "n/a", "0.989");
        System.out.printf("%-12s | %-15s | %-10dms%n", "tMCG",
                (tMcg + tTmesh) + " ms", 92);
        System.out.printf("%-12s | %-15s | %-10dms%n",
                "tILP", tIlpMs + " ms", 30);
        System.out.println("-------------+-----------------+----------");
        // Compute parity-band assessments per PATCH-83 checkpoint plan.
        java.util.function.BiFunction<Double, Double, String> band = (ours, paper) -> {
            if (paper == 0) return "n/a";
            double ratio = Math.abs(ours - paper) / paper;
            return ratio <= 0.10 ? "✓ within 10%"
                    : ratio <= 0.20 ? "~ within 20%"
                    : "✗ over 20% off";
        };
        System.out.println();
        System.out.println("PATCH-83 parity check:");
        System.out.printf("  #Sing    : %s (ours %d, paper %d)%n",
                band.apply((double) boot.singularities().size(), 36.0),
                boot.singularities().size(), 36);
        System.out.printf("  #Traces  : %s (ours %d, paper %d)%n",
                band.apply((double) graph.traces().size(), 144.0),
                graph.traces().size(), 144);
        System.out.printf("  #Arcs    : %s (ours %d, paper %d)%n",
                band.apply((double) tmesh.arcs().size(), 2742.0),
                tmesh.arcs().size(), 2742);
        System.out.printf("  #Vars    : %s (ours %d, paper %d)%n",
                band.apply((double) strips.classCount(), 192.0),
                strips.classCount(), 192);
        System.out.printf("  #P       : %s (ours %d, paper %d)%n",
                band.apply((double) lr.layout().patchCount(), 159.0),
                lr.layout().patchCount(), 159);
        System.out.printf("Lyon-stage pipeline (motorcycle→metrics) = %d ms%n",
                total - tBoot);
        System.out.println();
        System.out.println("Reference comparison vs metriko (same input, scale=0.03):");
        System.out.println("  metriko: nTE=256 (arcs), nTQ=96 (T-mesh patches)");
        System.out.println("  ours:    arcs=" + tmesh.arcs().size()
                + ", patches=" + tmesh.patches().size());
    }
}
