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

        // PATCH-97/98: with CG fallback in MiGreedyRounding, FaceRosyField
        // is now usable on 20K-face rocker-arm. Opt in via
        // -Dixdar.lyon.recomputeField=true to rebuild cross-field from
        // scratch with our BZK09 + §3 (PATCH-96) implementation. Default is
        // metriko-baseline precomputed (32 sing).
        boolean recomputeField = "true".equals(
                System.getProperty("ixdar.lyon.recomputeField"));
        ixdar.geometry.mesh.quadlayout.vectorfield.FaceRosyField mcField = boot.field();
        ixdar.geometry.mesh.quadlayout.vectorfield.CombedField mcCombed = boot.combed();
        java.util.List<ixdar.geometry.mesh.quadlayout.vectorfield.Singularity> mcSings = boot.singularities();
        ixdar.geometry.mesh.quadlayout.integergrid.SeamlessParameterization mcParam = param;
        if (recomputeField) {
            long tField0 = System.currentTimeMillis();
            // PATCH-115: default principalTau=Infinity → CIE*16 alignment chain
            //   DISABLED. With CIE*16 at 70° our cross-field over-counted to
            //   ~700 sings (verified 2026-05-02 bench); with CIE*16 off, pure
            //   BZK09 produces 36 sings, matching Lyon Table 1 exactly. The
            //   metriko reference also runs unconstrained BZK09 (32 sings).
            //   Re-enable for diagnosis via -Dixdar.lyon.principalTau=70 once
            //   the CIE*16 over-constraint bug (PATCH-117) is fixed. The
            //   property name is preserved for call-site compatibility — its
            //   meaning is the CIE*16 §3.2 ¶4 significance angle in degrees,
            //   not the deleted BZK09 §3 anisotropy from PATCH-96.
            double significanceDeg = Double.parseDouble(
                    System.getProperty("ixdar.lyon.principalTau", "Infinity"));
            var ourField = new ixdar.geometry.mesh.quadlayout.vectorfield.FaceRosyField(mesh, significanceDeg);
            ourField.solve();
            var ourSings = ourField.findSingularities();
            long tField = System.currentTimeMillis() - tField0;
            var lr = ourField.lastResult();
            String solverTag = System.getProperty("ixdar.lyon.crossFieldSolver", "bzk09");
            if (lr != null) {
                System.out.printf("[bench-lyon] our-FaceRosyField (%s + CIE*16 ∠F=%.1f°): "
                        + "sing=%d (metriko-precomp=%d, paper=36) tSolve=%dms "
                        + "gs-conv=%d cg-conv=%d direct-fb=%d cg-iters=%d%n",
                        solverTag, significanceDeg, ourSings.size(), boot.singularities().size(), tField,
                        lr.gsConverged, lr.cgConverged, lr.directFallbacks, lr.totalCgIters);
            } else {
                System.out.printf("[bench-lyon] our-FaceRosyField (%s + CIE*16 ∠F=%.1f°): "
                        + "sing=%d (metriko-precomp=%d, paper=36) tSolve=%dms%n",
                        solverTag, significanceDeg, ourSings.size(), boot.singularities().size(), tField);
            }
            // PATCH-115 diagnostic: index4 histogram. metriko reference = all ±1
            //   (16 of each). If we report many |index4| ≥ 2, that's the line-field
            //   sign-ambiguity feeding spurious m_e = ±2 hypothesis.
            int[] idxHist = new int[9]; // [-4,-3,-2,-1,0,+1,+2,+3,+4] mapped to 0..8
            for (var s : ourSings) {
                int b = Math.max(-4, Math.min(4, s.index4())) + 4;
                idxHist[b]++;
            }
            System.out.printf("[bench-lyon] index4 histogram (-4..-1, +1..+4): %d %d %d %d | %d %d %d %d  (metriko: 0 0 0 16 | 16 0 0 0)%n",
                    idxHist[0], idxHist[1], idxHist[2], idxHist[3],
                    idxHist[5], idxHist[6], idxHist[7], idxHist[8]);

            // PATCH-114: BZK09 §5.4 LocalStiffening replaces the old log-barrier
            //   step. Route our (field, combed, sings, param) tuple through the
            //   motorcycle stage if SeamlessParameterization produces an injective
            //   output.
            long tParam0 = System.currentTimeMillis();
            var ourCombed = ixdar.geometry.mesh.quadlayout.vectorfield.CombedField.comb(ourField);
            int paramMaxRoundingIter = Integer.getInteger(
                    "ixdar.lyon.paramMaxRoundingIter", Integer.MAX_VALUE);
            var ourParam = new ixdar.geometry.mesh.quadlayout.integergrid.SeamlessParameterization(
                    mesh, ourField, ourCombed, ourSings, paramMaxRoundingIter);
            long tParam = System.currentTimeMillis() - tParam0;
            System.out.printf("[bench-lyon] our-SeamlessParameterization (BZK09 §5/§5.4, PATCH-110/114): "
                    + "iterRounded=%d injective=%s tParam=%dms%n",
                    ourParam.iterationCount(), ourParam.injectiveOnAllTriangles(), tParam);
            mcField = ourField;
            mcCombed = ourCombed;
            mcSings = ourSings;
            mcParam = ourParam;
        }

        long t1 = System.currentTimeMillis();
        MotorcycleGraph.Result graph = MotorcycleGraph.trace(
                mcParam, mesh, mcField, mcCombed, mcSings);
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
        // PATCH-95 H1: |α_ij| histogram at all crashes. Buckets in degrees:
        // [0-5], [5-15], [15-30], [30-45], [45-60], [60-75], [75-90], [90+].
        // Lyon α=15° qualifies bucket 0 + bucket 1 only.
        System.out.printf("[bench-lyon] |α_ij| hist (deg buckets 0-5, 5-15, 15-30, 30-45, 45-60, 60-75, 75-90, 90+): %s%n",
                java.util.Arrays.toString(
                    ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statAlphaIjHist));
        // PATCH-92/94 motorcycle abort-cause census.
        System.out.printf("[bench-lyon] trace-stop census: proper-Lyon-stop=%d  "
                + "self-stop=%d "
                + "abort-degen-face=%d abort-no-exit-edge=%d "
                + "abort-boundary-edge=%d abort-no-neighbor=%d "
                + "abort-no-shared-corners=%d abort-max-steps=%d%n",
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statTraceProperStop,
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statRealSelfCrashes,
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statAbortDegenStartFace,
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statAbortNoExitEdge,
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statAbortBoundaryEdge,
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statAbortNoNeighborFace,
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statAbortNoSharedCorners,
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statAbortMaxSteps);
        // PATCH-105: per-cause classification of MAX_STEPS aborts.
        System.out.printf("[bench-lyon] MAX_STEPS triage: zero-crashes=%d  one-sided-α=%d  "
                + "two-sided-never-fired=%d  hit-flipped-face=%d  used-nudge=%d%n",
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statMaxStepsZeroCrashes,
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statMaxStepsOneSidedAlpha,
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statMaxStepsTwoSidedNeverFired,
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statMaxStepsHitFlippedFace,
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statMaxStepsUsedNudge);
        // PATCH-107 oscillation probe: per-max-stepped uniqueFaces histogram.
        System.out.printf("[bench-lyon] MAX_STEPS uniqueFaces hist [≤10, 11-50, 51-200, 201-1000, 1001-5000, 5001+]: %s%n",
                java.util.Arrays.toString(
                    ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statMaxStepsUniqueFacesHist));
        for (String d : ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.abortDumps) {
            System.out.println("[bench-lyon]   " + d);
        }
        // PATCH-94: dump motorcycle outcomes by id to see if MAX_STEPS aborts
        // correlate with launch order (sequential-launching hypothesis).
        var outcomes = ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.motorcycleOutcomes;
        int nMaxSteps = 0;
        java.util.List<Integer> maxStepsIds = new java.util.ArrayList<>();
        java.util.List<Integer> properStopIds = new java.util.ArrayList<>();
        for (String o : outcomes) {
            int idStart = o.indexOf("id=") + 3;
            int idEnd = o.indexOf(' ', idStart);
            if (idEnd < 0) idEnd = o.length();
            int id = Integer.parseInt(o.substring(idStart, idEnd));
            if (o.startsWith("MAX_STEPS")) {
                maxStepsIds.add(id);
                nMaxSteps++;
            } else if (o.startsWith("PROPER_STOP")) {
                properStopIds.add(id);
            }
        }
        System.out.printf("[bench-lyon] MAX_STEPS motorcycle ids (first 20 of %d): %s%n",
                nMaxSteps, maxStepsIds.subList(0, Math.min(20, maxStepsIds.size())));
        System.out.printf("[bench-lyon] PROPER_STOP motorcycle ids (first 20 of %d): %s%n",
                properStopIds.size(), properStopIds.subList(0, Math.min(20, properStopIds.size())));
        // PATCH-91 H1 diagnostic.
        System.out.printf("[bench-lyon] sing-launch-count hist (idx=#motorcycles): %s  total=%d boundary=%d%n",
                java.util.Arrays.toString(
                    ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statSingLaunchCount),
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statSingTotal,
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statSingBoundaryCardinals);
        // PATCH-110 launch-gap probe: how many input singularities couldn't
        //   be assigned a launch node (no incident face with uvSignedArea > 0)?
        System.out.printf("[bench-lyon] launch-gap: input-sings=%d  no-node-skipped=%d  launched=%d%n",
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statSingsInputCount,
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statSingsNoNode,
                ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph.statSingTotal);
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
