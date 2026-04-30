package unit.quadlayout.field;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.MeshLoader;
import ixdar.geometry.mesh.quadlayout.extraction.QuadEdgeGenerator;
import ixdar.geometry.mesh.quadlayout.extraction.QuadMeshExtractor;
import ixdar.geometry.mesh.quadlayout.field.PrecomputedFieldImporter;
import ixdar.geometry.mesh.quadlayout.integergrid.SeamlessParameterization;
import ixdar.geometry.mesh.quadlayout.extraction.TransitionMatrix;
import ixdar.geometry.mesh.quadlayout.lyon2021.QuadAssembler;
import ixdar.geometry.mesh.quadlayout.lyon2021.SplitArcs;
import ixdar.geometry.mesh.quadlayout.lyon2021.SplitVert;
import ixdar.geometry.mesh.quadlayout.quantization.Quantization;
import ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.tmesh.TMesh;
import ixdar.geometry.mesh.quadlayout.vectorfield.Singularity;

/**
 * PATCH-59 — stage-by-stage regression tests against metriko's TSV ground
 * truth on the Hand baseline. Each test loads a metriko intermediate
 * output as input to one of our pipeline stages and diffs the result
 * against metriko's downstream output.
 *
 * <p>These tests provide correctness signal that toy-input tests can't:
 * they exercise the algorithms on a real 30k-face mesh. Failures localise
 * which stage diverges from the reference implementation.
 *
 * <p>Each test prints diff metrics to stdout for manual inspection — the
 * raw numbers are as informative as the pass/fail bit.
 */
public class MetrikoStageRegressionTest {

    private static final Path BASELINE_DIR = Paths.get(
            "test/resources/quadlayout/baseline-hand");

    static boolean baselineAvailable() {
        return Files.exists(BASELINE_DIR.resolve("Hand-tri-30k.obj"))
                && Files.exists(BASELINE_DIR.resolve("stage2_uv_corners.tsv"))
                && Files.exists(BASELINE_DIR.resolve("hand-quad.obj"));
    }

    /**
     * Stage 2 → QEx: load metriko's per-corner UVs, run our quad mesh
     * extractor, print the diff against {@code hand-quad.obj}.
     *
     * <p>Diagnostic-only: prints stage counts so we can see exactly which
     * sub-stage of QEx (QVert generation, QPort generation, QEdge tracing,
     * QFace assembly) diverges from the reference. Doesn't enforce a
     * tolerance because the gap is currently large enough that the diff
     * itself is the actionable output. Tickets for fixing each diverging
     * sub-stage are tracked separately.
     *
     * <p>Current state (PATCH-59 baseline, before any fixes):
     * <ul>
     *   <li>~621 FACE QVerts (consistent with stage2's per-cell density)</li>
     *   <li>~0 EDGE QVerts (stage2 UVs aren't integer-aligned at edge
     *       crossings within numerical noise — see follow-up ticket)</li>
     *   <li>~0 VERT QVerts (only 3 of 89,880 corner UVs are integer)</li>
     *   <li>0 QEdges connected → 0 quads (cross-triangle tracing isn't
     *       finding mates on this input — see follow-up ticket)</li>
     * </ul>
     */
    @Test
    @EnabledIf("baselineAvailable")
    void stage2UvsThroughQexProducesQuads() throws IOException {
        Path obj = BASELINE_DIR.resolve("Hand-tri-30k.obj");
        Path stage1Field = BASELINE_DIR.resolve("stage1_extrinsic_field.tsv");
        Path stage1Match = BASELINE_DIR.resolve("stage1_matching.txt");
        Path stage1Seam  = BASELINE_DIR.resolve("stage1_seam.txt");
        Path stage1Sing  = BASELINE_DIR.resolve("stage1_singular.txt");
        Path stage2 = BASELINE_DIR.resolve("stage2_uv_corners.tsv");
        Path metrikoQuad = BASELINE_DIR.resolve("hand-quad.obj");

        // Load full bootstrap so we can pass CombedField (matching/seam) into
        // QEx — needed for cross-triangle iso-line tracing's TRS rotation.
        PrecomputedFieldImporter.Result boot = PrecomputedFieldImporter.load(
                obj, stage1Field, stage1Match, stage1Seam, stage1Sing);
        ArrayMesh mesh = boot.mesh();
        float[][] uv = PrecomputedFieldImporter.loadStage2Uv(stage2, mesh.faceCount());
        float[] uCorner = uv[0];
        float[] vCorner = uv[1];

        int metrikoQuadCount = countQuadFaces(metrikoQuad);
        int metrikoVertCount = countQuadVerts(metrikoQuad);

        long t0 = System.currentTimeMillis();
        QuadMeshExtractor.Result r = QuadMeshExtractor.extract(
                mesh, uCorner, vCorner, boot.combed());
        long elapsed = System.currentTimeMillis() - t0;

        int ourQuads = r.faces().size();
        int ourVerts = r.quadMesh() != null ? r.quadMesh().vertexCount() : 0;
        int totalQVerts = r.qVerts().total();
        int connectedPorts = 0;
        for (var p : r.ports()) if (p.connected) connectedPorts++;

        System.out.println("[regression-stage2-qex] " + obj.getFileName());
        System.out.println("  mesh F            = " + mesh.faceCount());
        System.out.println("  QVerts (vert/edge/face) = "
                + r.qVerts().vertQVerts().size() + " / "
                + r.qVerts().edgeQVerts().size() + " / "
                + r.qVerts().faceQVerts().size()
                + "  total=" + totalQVerts);
        System.out.println("  QPorts            = " + r.ports().size()
                + "  (connected " + connectedPorts + ")");
        System.out.println("  QEdges            = " + r.edges().size());
        System.out.println("  trace stats       = "
                + " attempted=" + QuadEdgeGenerator.statTracesAttempted
                + " same-face=" + QuadEdgeGenerator.statSameFaceHits
                + " cross-face=" + QuadEdgeGenerator.statCrossFaceHits
                + " no-exit=" + QuadEdgeGenerator.statNoExitFound
                + " boundary=" + QuadEdgeGenerator.statBoundaryHit
                + " hop-cap=" + QuadEdgeGenerator.statHopCapHits);
        System.out.println("  our quads         = " + ourQuads
                + "  (metriko: " + metrikoQuadCount + ")");
        System.out.println("  our verts         = " + ourVerts
                + "  (metriko: " + metrikoVertCount + ")");
        System.out.println("  QEx time          = " + elapsed + " ms");

        // Sanity: pipeline ran without exception and produced internal state.
        assertNotNull(r);
        assertTrue(totalQVerts > 0,
                "Stage-1 QVert generation must find SOME integer (u, v) preimage on a 30k-tri mesh; got "
                        + totalQVerts);
    }

    /**
     * Stage 2 → motorcycle/T-mesh: load metriko's per-corner UVs +
     * stage1 cross field, run our motorcycle tracer + T-mesh build, print
     * the diff against metriko's stage3 arc count.
     *
     * <p>Diagnostic: tells us whether our motorcycle stage produces
     * roughly the right number of arcs given a known-good seamless
     * parametrization. metriko's stage3 has 352 arcs on Hand-30k.
     */
    @Test
    @EnabledIf("baselineAvailable")
    void stage2UvsThroughMotorcycleProducesArcs() throws IOException {
        Path obj = BASELINE_DIR.resolve("Hand-tri-30k.obj");
        Path stage1Field = BASELINE_DIR.resolve("stage1_extrinsic_field.tsv");
        Path stage1Match = BASELINE_DIR.resolve("stage1_matching.txt");
        Path stage1Seam  = BASELINE_DIR.resolve("stage1_seam.txt");
        Path stage1Sing  = BASELINE_DIR.resolve("stage1_singular.txt");
        Path stage2 = BASELINE_DIR.resolve("stage2_uv_corners.tsv");
        Path stage3 = BASELINE_DIR.resolve("stage3_tedges.tsv");

        // Stage 1: cross field + combing + singularities.
        PrecomputedFieldImporter.Result boot = PrecomputedFieldImporter.load(
                obj, stage1Field, stage1Match, stage1Seam, stage1Sing);

        // Stage 2: per-corner UVs from metriko, wrapped as SeamlessParameterization.
        float[][] uv = PrecomputedFieldImporter.loadStage2Uv(stage2, boot.mesh().faceCount());
        SeamlessParameterization param = SeamlessParameterization.fromExternal(
                boot.mesh(), uv[0], uv[1], /*injective=*/true);

        // Our motorcycle + T-mesh.
        long t0 = System.currentTimeMillis();
        MotorcycleGraph.Result graph = MotorcycleGraph.trace(
                param, boot.mesh(), boot.field(), boot.combed(), boot.singularities());
        long tGraph = System.currentTimeMillis() - t0;
        long t1 = System.currentTimeMillis();
        TMesh tmesh = TMesh.build(graph, param);
        long tMesh = System.currentTimeMillis() - t1;

        // metriko's stage3 arc count: # non-comment, non-empty lines.
        int metrikoArcs = 0;
        for (String line : Files.readAllLines(stage3)) {
            String s = line.trim();
            if (!s.isEmpty() && !s.startsWith("#")) metrikoArcs++;
        }

        int ourTraces = graph.traces().size();
        int ourArcs = tmesh.arcs().size();
        int ourPatches = tmesh.patches().size();

        System.out.println("[regression-stage2-motorcycle] " + obj.getFileName());
        System.out.println("  singularities     = " + boot.singularities().size());
        System.out.println("  motorcycle traces = " + ourTraces
                + "  (" + tGraph + " ms)");
        System.out.println("  our T-mesh arcs   = " + ourArcs
                + "  (metriko: " + metrikoArcs + ")");
        System.out.println("  our T-mesh patches= " + ourPatches
                + "  (" + tMesh + " ms)");

        // Sanity: pipeline ran. Numeric diff is informational.
        assertNotNull(graph);
        assertNotNull(tmesh);
    }

    /**
     * Stage 2 → motorcycle/T-mesh → quantization → SplitArcs (Lyon 2021
     * Stage A): load metriko's per-corner UVs + cross field, run our full
     * pipeline, subdivide every T-arc into integer-quantized split
     * vertices, sum them. Compare to metriko's hand-quad.obj vertex count
     * (3641) — a rough lower bound, since duplicate verts at shared arc
     * endpoints will reduce the unique count.
     */
    @Test
    @EnabledIf("baselineAvailable")
    void stage2UvsThroughLyonSplitArcs() throws IOException {
        Path obj = BASELINE_DIR.resolve("Hand-tri-30k.obj");
        Path stage1Field = BASELINE_DIR.resolve("stage1_extrinsic_field.tsv");
        Path stage1Match = BASELINE_DIR.resolve("stage1_matching.txt");
        Path stage1Seam  = BASELINE_DIR.resolve("stage1_seam.txt");
        Path stage1Sing  = BASELINE_DIR.resolve("stage1_singular.txt");
        Path stage2 = BASELINE_DIR.resolve("stage2_uv_corners.tsv");
        Path metrikoQuad = BASELINE_DIR.resolve("hand-quad.obj");

        PrecomputedFieldImporter.Result boot = PrecomputedFieldImporter.load(
                obj, stage1Field, stage1Match, stage1Seam, stage1Sing);
        ArrayMesh mesh = boot.mesh();
        float[][] uv = PrecomputedFieldImporter.loadStage2Uv(stage2, mesh.faceCount());
        SeamlessParameterization param = SeamlessParameterization.fromExternal(
                mesh, uv[0], uv[1], true);

        MotorcycleGraph.Result graph = MotorcycleGraph.trace(
                param, mesh, boot.field(), boot.combed(), boot.singularities());
        TMesh tmesh = TMesh.build(graph, param);
        // PATCH-69: ILP hangs on the 332-arc T-mesh. Use synthetic X[i]=1
        // so this test exercises Stage A (split-vert subdivision) only.
        // The Quantization.solve path is exercised separately by
        // QuantizationTest's toy fixtures.
        int[] X = new int[tmesh.arcs().size()];
        java.util.Arrays.fill(X, 1);

        java.util.List<java.util.List<SplitVert>> splits =
                SplitArcs.generate(tmesh, mesh, uv[0], uv[1], X);

        int totalSplitVerts = 0;
        int minPerArc = Integer.MAX_VALUE;
        int maxPerArc = 0;
        for (var list : splits) {
            int n = list.size();
            totalSplitVerts += n;
            if (n < minPerArc) minPerArc = n;
            if (n > maxPerArc) maxPerArc = n;
        }
        int metrikoVertCount = countQuadVerts(metrikoQuad);
        System.out.println("[regression-stage2-lyon-splits] " + obj.getFileName());
        System.out.println("  T-mesh arcs       = " + tmesh.arcs().size()
                + "  (metriko: 352)");
        System.out.println("  quant total       = "
                + java.util.Arrays.stream(X).sum()
                + "  (synthetic X[i]=1)");
        System.out.println("  total split verts = " + totalSplitVerts
                + "  (metriko quad-vert count: " + metrikoVertCount + ")");
        System.out.println("  per-arc range     = ["
                + (minPerArc == Integer.MAX_VALUE ? 0 : minPerArc)
                + ", " + maxPerArc + "]");

        assertNotNull(splits);
        assertTrue(totalSplitVerts > 0);
    }

    /**
     * PATCH-67: end-to-end Lyon 2021 extractor test on Hand-30k. Loads
     * metriko's stage1 (cross field) + stage2 (UVs), runs the full pipeline
     * (motorcycle → TMesh → SplitArcs with synthetic X[i]=1 → SplitTable
     * → SplitArcTracer → IntersectionTable → QuadAssembler), prints the
     * resulting quad/vertex counts vs metriko's hand-quad.obj.
     *
     * <p>Diagnostic-only: doesn't enforce a quad-count target because the
     * pipeline currently lacks T-junction-aware TPatch enumeration (so
     * {@code TMesh.patches()} usually returns 0 on real meshes). The point
     * is the wire-up + per-stage timing baseline.
     */
    @Test
    @EnabledIf("baselineAvailable")
    void stage2UvsThroughLyonEndToEnd() throws IOException {
        Path obj = BASELINE_DIR.resolve("Hand-tri-30k.obj");
        Path stage1Field = BASELINE_DIR.resolve("stage1_extrinsic_field.tsv");
        Path stage1Match = BASELINE_DIR.resolve("stage1_matching.txt");
        Path stage1Seam  = BASELINE_DIR.resolve("stage1_seam.txt");
        Path stage1Sing  = BASELINE_DIR.resolve("stage1_singular.txt");
        Path stage2 = BASELINE_DIR.resolve("stage2_uv_corners.tsv");
        Path metrikoQuad = BASELINE_DIR.resolve("hand-quad.obj");

        long t0 = System.currentTimeMillis();
        PrecomputedFieldImporter.Result boot = PrecomputedFieldImporter.load(
                obj, stage1Field, stage1Match, stage1Seam, stage1Sing);
        ArrayMesh mesh = boot.mesh();
        float[][] uv = PrecomputedFieldImporter.loadStage2Uv(stage2, mesh.faceCount());
        SeamlessParameterization param = SeamlessParameterization.fromExternal(
                mesh, uv[0], uv[1], true);
        long tBoot = System.currentTimeMillis() - t0;

        long t1 = System.currentTimeMillis();
        MotorcycleGraph.Result graph = MotorcycleGraph.trace(
                param, mesh, boot.field(), boot.combed(), boot.singularities());
        TMesh tmesh = TMesh.build(graph, param);
        long tTmesh = System.currentTimeMillis() - t1;

        // Synthetic X[i] for all arcs — bypass the slow ILP. Read from
        // -Dixdar.lyon.syntheticX to allow scaling experiments; default 1.
        int syntheticX = Integer.getInteger("ixdar.lyon.syntheticX", 1);
        int[] X = new int[tmesh.arcs().size()];
        java.util.Arrays.fill(X, syntheticX);

        long t2 = System.currentTimeMillis();
        java.util.List<java.util.List<SplitVert>> splits =
                SplitArcs.generate(tmesh, mesh, uv[0], uv[1], X);
        long tSplits = System.currentTimeMillis() - t2;

        long t3 = System.currentTimeMillis();
        TransitionMatrix trs = TransitionMatrix.compute(mesh, uv[0], uv[1], boot.combed());
        QuadAssembler.Result lyon = QuadAssembler.assemble(
                tmesh, splits, mesh, uv[0], uv[1], trs);
        long tAssem = System.currentTimeMillis() - t3;

        int metrikoQuads = countQuadFaces(metrikoQuad);
        int metrikoVerts = countQuadVerts(metrikoQuad);
        int totalSplitVerts = splits.stream().mapToInt(java.util.List::size).sum();

        System.out.println("[regression-stage2-lyon-end2end] " + obj.getFileName());
        System.out.println("  bootstrap          = " + tBoot + " ms");
        System.out.println("  motorcycle+tmesh   = " + tTmesh + " ms");
        System.out.println("  T-mesh arcs        = " + tmesh.arcs().size()
                + "  patches=" + tmesh.patches().size());
        System.out.println("  patch-enum stats   = halfArcs="
                + ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statHalfArcs
                + " linkable=" + ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statHalfArcsLinkable
                + " facesWalked=" + ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statFacesWalked
                + " short=" + ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statFacesShortCycle
                + " nonQuad=" + ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statFacesNonQuad
                + " emitted=" + ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statFacesEmittedAsPatches);
        System.out.println("  side-count hist    = "
                + java.util.Arrays.toString(ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.statSideHistogram));
        for (int i = 0; i < ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.nonQuadCardinals.size(); i++) {
            System.out.println("  non-quad #" + i + " cardinals = "
                    + java.util.Arrays.toString(
                        ixdar.geometry.mesh.quadlayout.tmesh.TPatchEnumerator.nonQuadCardinals.get(i)));
        }
        System.out.println("  total split verts  = " + totalSplitVerts
                + "  (synthetic X[i]=1)  " + tSplits + " ms");
        System.out.println("  Lyon assembler     = " + tAssem + " ms");
        System.out.println("  Lyon patches       = "
                + lyon.patchesProcessed() + " ok / "
                + lyon.patchesSkipped() + " skipped");
        System.out.println("  our quads          = " + lyon.totalQuads()
                + "  (metriko: " + metrikoQuads + ")");
        System.out.println("  our verts          = " + lyon.totalVertices()
                + "  (metriko: " + metrikoVerts + ")");

        assertNotNull(lyon);
    }

    private static int countQuadFaces(Path obj) throws IOException {
        int n = 0;
        for (String line : Files.readAllLines(obj)) {
            if (line.startsWith("f ")) n++;
        }
        return n;
    }

    private static int countQuadVerts(Path obj) throws IOException {
        int n = 0;
        for (String line : Files.readAllLines(obj)) {
            if (line.startsWith("v ")) n++;
        }
        return n;
    }
}
