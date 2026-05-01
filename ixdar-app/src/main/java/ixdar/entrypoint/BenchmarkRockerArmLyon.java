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
        TMesh tmesh = TMesh.build(graph, param, mesh);
        long tTmesh = System.currentTimeMillis() - t2;
        System.out.printf("[bench-lyon] motorcycle=%dms traces=%d crashes=%d%n",
                tMcg, graph.traces().size(), graph.crashes().size());
        // PATCH-89: boundary-motorcycle stats.
        System.out.printf("[bench-lyon] boundary motorcycles=%d nodes-created=%d%n",
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statBoundaryMotorcycles,
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statBoundaryNodesCreated);
        // PATCH-92 motorcycle abort-cause census.
        System.out.printf("[bench-lyon] trace-stop census: proper-Lyon-stop=%d  "
                + "abort-degen-face=%d abort-no-exit-edge=%d "
                + "abort-boundary-edge=%d abort-no-neighbor=%d "
                + "abort-no-shared-corners=%d%n",
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statTraceProperStop,
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statAbortDegenStartFace,
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statAbortNoExitEdge,
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statAbortBoundaryEdge,
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statAbortNoNeighborFace,
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statAbortNoSharedCorners);
        for (String d : ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.abortDumps) {
            System.out.println("[bench-lyon]   " + d);
        }
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
        System.out.printf("[bench-lyon] enum stats: halfArcs=%d linkable=%d facesWalked=%d short=%d nonQuad=%d emitted=%d outerBoundary=%d%n",
                ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statHalfArcs,
                ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statHalfArcsLinkable,
                ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statFacesWalked,
                ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statFacesShortCycle,
                ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statFacesNonQuad,
                ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statFacesEmittedAsPatches,
                ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statFacesOuterBoundary);
        // PATCH-92 angular-sort audit: distinct mesh-face frames per node.
        // Bucket 0 = single-frame nodes (sort safe), bucket 1+ = multi-frame
        // nodes (potential angular-sort hazard for the planar-dual walk).
        System.out.printf("[bench-lyon] node-frame hist (idx=#frames-1): %s  multi-frame-total=%d  fan-sorted=%d%n",
                java.util.Arrays.toString(
                    ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statNodeFrameCountHist),
                ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statMultiFrameNodes,
                ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statFanSortedNodes);
        // PATCH-89 diagnostic: which dropped long cycles touch a mesh BOUNDARY?
        System.out.printf("[bench-lyon] long-cycle classification: with-boundary=%d  all-intersection=%d%n",
                ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statLongCyclesWithBoundaryCorner,
                ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statLongCyclesAllIntersection);
        for (int[] r : ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.longCycleLengths) {
            System.out.printf("[bench-lyon]   long-cycle: nHalfArcs=%d nCorners=%d boundaryCorners=%d singCorners=%d intersectionCorners=%d%n",
                    r[0], r[1], r[2], r[3], r[4]);
        }

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
        int mergedPatchCount = lr.layout().mergedPatchCount();
        System.out.printf("[bench-lyon] layout=%dms #P=%d (merged=%d) skipped=%d tJunctions=%d%n",
                tLayout, lr.layout().patchCount(), mergedPatchCount,
                lr.skippedPatchIds().size(),
                lr.layout().tJunctionsResolved());
        // PATCH-92 — T-junction extension bail diagnostics.
        var qle = ixdar.geometry.mesh.quadlayout.lyon2021.QuadLayoutExtractor.class;
        System.out.printf("[bench-lyon] T-junction stats: in=%d emit-clean=%d "
                + "extend-exact=%d extend-split=%d "
                + "bail-emptyside=%d bail-no-multiarc=%d "
                + "bail-eq2-violation=%d bail-no-match=%d bail-degen-split=%d%n",
                ixdar.geometry.mesh.quadlayout.lyon2021.QuadLayoutExtractor.statTPatchesIn,
                ixdar.geometry.mesh.quadlayout.lyon2021.QuadLayoutExtractor.statAllSingleArcEmit,
                ixdar.geometry.mesh.quadlayout.lyon2021.QuadLayoutExtractor.statTJunctionExactMatch,
                ixdar.geometry.mesh.quadlayout.lyon2021.QuadLayoutExtractor.statTJunctionArcSplit,
                ixdar.geometry.mesh.quadlayout.lyon2021.QuadLayoutExtractor.statBailEmptySide,
                ixdar.geometry.mesh.quadlayout.lyon2021.QuadLayoutExtractor.statBailNoMultiArcSide,
                ixdar.geometry.mesh.quadlayout.lyon2021.QuadLayoutExtractor.statBailEqTwoViolated,
                ixdar.geometry.mesh.quadlayout.lyon2021.QuadLayoutExtractor.statBailNoMatchNoSplit,
                ixdar.geometry.mesh.quadlayout.lyon2021.QuadLayoutExtractor.statBailDegenerateSplit);

        // PATCH-92: TRUE T-junction count by Lyon §5.2 definition — the
        // terminal node of every motorcycle trace IS a T-junction. Classify
        // by whether it appears as the interior point of a 4-sided patch's
        // multi-arc side (visible to QuadLayoutExtractor) or hidden inside
        // a non-4-sided cell / dropped DCEL cycle.
        int totalTraceTJunctions = 0;
        java.util.HashSet<Integer> tJunctionNodeIds = new java.util.HashSet<>();
        for (var mc : graph.traces()) {
            if (mc.singularityVertexId()
                    == ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.BOUNDARY_MOTORCYCLE_VID) continue;
            int term = mc.finalNodeId();
            if (term >= 0) {
                totalTraceTJunctions++;
                tJunctionNodeIds.add(term);
            }
        }
        // Of those T-junction nodes, how many appear as a CORNER of a 4-sided
        // TPatch (visible) vs only in non-4-sided cells (hidden)?
        java.util.HashSet<Integer> visibleInQuad = new java.util.HashSet<>();
        java.util.HashSet<Integer> presentInNonQuad = new java.util.HashSet<>();
        for (var tp : tmesh.patches()) {
            int[] corners = tp.cornerNodeIds();
            int[][] sides = tp.arcsBySide();
            boolean isQuad = sides != null && sides.length == 4;
            if (corners != null) {
                for (int c : corners) {
                    if (tJunctionNodeIds.contains(c)) {
                        if (isQuad) visibleInQuad.add(c);
                        else presentInNonQuad.add(c);
                    }
                }
            }
        }
        // Also: T-junctions appearing as INTERIOR points of multi-arc sides
        // (the actual "exposed for extension" condition).
        int exposedAsMultiArc = 0;
        for (var tp : tmesh.patches()) {
            int[][] sides = tp.arcsBySide();
            if (sides == null || sides.length != 4) continue;
            for (int s = 0; s < 4; s++) {
                if (sides[s] == null || sides[s].length <= 1) continue;
                // Interior nodes of this multi-arc side: end of arc[0..n-2].
                for (int k = 0; k < sides[s].length - 1; k++) {
                    int arcId = sides[s][k];
                    if (arcId < 0 || arcId >= tmesh.arcs().size()) continue;
                    var arc = tmesh.arcs().get(arcId);
                    int n1 = arc.startNode();
                    int n2 = arc.endNode();
                    if (tJunctionNodeIds.contains(n1)) exposedAsMultiArc++;
                    if (tJunctionNodeIds.contains(n2)) exposedAsMultiArc++;
                }
            }
        }
        int hiddenInNonQuad = presentInNonQuad.size() - visibleInQuad.size();
        if (hiddenInNonQuad < 0) hiddenInNonQuad = presentInNonQuad.size();
        int orphanTJunctions = totalTraceTJunctions - tJunctionNodeIds.size();   // duplicates
        int notInAnyPatch = tJunctionNodeIds.size()
                - visibleInQuad.size() - hiddenInNonQuad;
        if (notInAnyPatch < 0) notInAnyPatch = 0;
        System.out.printf("[bench-lyon] T-junction census: total=%d (motorcycle endpoints) "
                + "unique-nodes=%d visible-in-4sided-patch=%d hidden-in-non-4sided=%d "
                + "in-no-emitted-patch=%d exposed-as-multi-arc-interior=%d "
                + "extended-by-extractor=%d%n",
                totalTraceTJunctions, tJunctionNodeIds.size(),
                visibleInQuad.size(), hiddenInNonQuad, notInAnyPatch,
                exposedAsMultiArc, lr.layout().tJunctionsResolved());

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
        System.out.printf("  #P (raw) : %s (ours %d, paper %d)%n",
                band.apply((double) lr.layout().patchCount(), 159.0),
                lr.layout().patchCount(), 159);
        System.out.printf("  #P (merged after q=0 collapse): %s (ours %d, paper %d)%n",
                band.apply((double) mergedPatchCount, 159.0),
                mergedPatchCount, 159);
        System.out.printf("Lyon-stage pipeline (motorcycle→metrics) = %d ms%n",
                total - tBoot);
        System.out.println();
        System.out.println("Reference comparison vs metriko (same input, scale=0.03):");
        System.out.println("  metriko: nTE=256 (arcs), nTQ=96 (T-mesh patches)");
        System.out.println("  ours:    arcs=" + tmesh.arcs().size()
                + ", patches=" + tmesh.patches().size());
    }
}
