package unit.quadlayout.lyon2021;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import ixdar.geometry.mesh.quadlayout.field.PrecomputedFieldImporter;
import ixdar.geometry.mesh.quadlayout.integergrid.SeamlessParameterization;
import ixdar.geometry.mesh.quadlayout.lyon2021.LyonMetrics;
import ixdar.geometry.mesh.quadlayout.lyon2021.QuadLayoutExtractor;
import ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.tmesh.TArc;
import ixdar.geometry.mesh.quadlayout.tmesh.TMesh;
import ixdar.geometry.mesh.quadlayout.tmesh.TNode;
import ixdar.geometry.mesh.quadlayout.tmesh.TPatch;

/** PATCH-74: layout-metrics harness — verifies dmean / dmax against
 *  hand-built fixtures and records Hand-30k diagnostics for paper
 *  comparison. */
public class LyonMetricsTest {

    private static final Path BASELINE_DIR = Paths.get(
            "test/resources/quadlayout/baseline-hand");

    static boolean baselineAvailable() {
        return Files.exists(BASELINE_DIR.resolve("Hand-tri-30k.obj"));
    }

    /** Axis-aligned single-patch layout: every step has dev=0. */
    @Test
    void axisAlignedPatchHasZeroDeviation() {
        List<TNode> nodes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            nodes.add(new TNode(i, TNode.NodeKind.SINGULARITY, -1, 0f, 0f));
        }
        // Build 4 arcs each with 2 steps, perfectly axis-aligned.
        List<TArc> arcs = new ArrayList<>();
        arcs.add(arcWithSteps(0, 0, 1, 0,
                new float[][]{{0,0,0.5f,0}, {0.5f,0,1,0}}));
        arcs.add(arcWithSteps(1, 1, 2, 1,
                new float[][]{{1,0,1,0.5f}, {1,0.5f,1,1}}));
        arcs.add(arcWithSteps(2, 2, 3, 0,
                new float[][]{{1,1,0.5f,1}, {0.5f,1,0,1}}));
        arcs.add(arcWithSteps(3, 3, 0, 1,
                new float[][]{{0,1,0,0.5f}, {0,0.5f,0,0}}));
        TPatch p = TPatch.single(0, new int[]{0, 1, 2, 3}, new int[]{0, 1, 2, 3});
        TMesh tmesh = TMesh.fromComponents(nodes, arcs, List.of(p));

        var layoutR = QuadLayoutExtractor.extract(tmesh, new int[]{1, 1, 1, 1});
        var m = LyonMetrics.compute(layoutR.layout(), tmesh);
        assertEquals(0.0, m.dmeanDeg(), 1e-9, "axis-aligned layout: dmean=0");
        assertEquals(0.0, m.dmaxDeg(), 1e-9, "axis-aligned layout: dmax=0");
        assertEquals(1, m.patchCount());
        assertEquals(8, m.stepsMeasured(), "8 steps total (4 arcs × 2 steps)");
    }

    /** A side that drifts off-axis at 30°: dmean and dmax should both = 30°. */
    @Test
    void thirtyDegreeOffsetMeasuredCorrectly() {
        List<TNode> nodes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            nodes.add(new TNode(i, TNode.NodeKind.SINGULARITY, -1, 0f, 0f));
        }
        // Arc 0 (u-axis) drifts 30°: each step Δu=cos30°, Δv=sin30°.
        double cos30 = Math.cos(Math.PI / 6);
        double sin30 = Math.sin(Math.PI / 6);
        List<TArc> arcs = new ArrayList<>();
        arcs.add(arcWithSteps(0, 0, 1, 0, new float[][]{
                {0, 0, (float) cos30, (float) sin30}}));
        // Other arcs: axis-aligned, contribute 0 to deviation.
        arcs.add(arcWithSteps(1, 1, 2, 1, new float[][]{{1,0,1,1}}));
        arcs.add(arcWithSteps(2, 2, 3, 0, new float[][]{{1,1,0,1}}));
        arcs.add(arcWithSteps(3, 3, 0, 1, new float[][]{{0,1,0,0}}));
        TPatch p = TPatch.single(0, new int[]{0, 1, 2, 3}, new int[]{0, 1, 2, 3});
        TMesh tmesh = TMesh.fromComponents(nodes, arcs, List.of(p));

        var layoutR = QuadLayoutExtractor.extract(tmesh, new int[]{1, 1, 1, 1});
        var m = LyonMetrics.compute(layoutR.layout(), tmesh);
        assertEquals(30.0, m.dmaxDeg(), 1e-3,
                "max deviation = 30° from the offset arc");
        // Length-weighted mean: 1 step at 30° (length=1) + 3 steps at 0° (length=1
        // each) → dmean = 30°/4 = 7.5°.
        assertEquals(7.5, m.dmeanDeg(), 1e-3,
                "length-weighted mean = 7.5° on this 4-arc patch");
    }

    /** Hand-30k diagnostic — print Lyon-Table-1-style row for the
     *  conforming subset of the layout. */
    @Test
    @EnabledIf("baselineAvailable")
    void handMeshLyonTableRow() throws IOException {
        Path obj = BASELINE_DIR.resolve("Hand-tri-30k.obj");
        Path stage1Field = BASELINE_DIR.resolve("stage1_extrinsic_field.tsv");
        Path stage1Match = BASELINE_DIR.resolve("stage1_matching.txt");
        Path stage1Seam  = BASELINE_DIR.resolve("stage1_seam.txt");
        Path stage1Sing  = BASELINE_DIR.resolve("stage1_singular.txt");
        Path stage2 = BASELINE_DIR.resolve("stage2_uv_corners.tsv");

        PrecomputedFieldImporter.Result boot = PrecomputedFieldImporter.load(
                obj, stage1Field, stage1Match, stage1Seam, stage1Sing);
        var mesh = boot.mesh();
        float[][] uv = PrecomputedFieldImporter.loadStage2Uv(stage2, mesh.faceCount());
        SeamlessParameterization param = SeamlessParameterization.fromExternal(
                mesh, uv[0], uv[1], true);
        MotorcycleGraph.Result graph = MotorcycleGraph.trace(
                param, mesh, boot.field(), boot.combed(), boot.singularities());
        TMesh tmesh = TMesh.build(graph, param);
        int[] q = new int[tmesh.arcs().size()];
        java.util.Arrays.fill(q, 1);

        var lr = QuadLayoutExtractor.extract(tmesh, q);
        var trs = ixdar.geometry.mesh.quadlayout.extraction.TransitionMatrix.compute(
                mesh, uv[0], uv[1], boot.combed());
        var m = LyonMetrics.compute(lr.layout(), tmesh, mesh, trs);
        System.out.println("[lyon-table-row] HAND-30k (synthetic q=1)");
        System.out.println(String.format(
                "  #Faces=%d  #Sing=%d  #Arcs=%d  #P=%d  dmean=%.1f°  dmax=%.1f°",
                mesh.faceCount(), boot.singularities().size(),
                tmesh.arcs().size(), m.patchCount(), m.dmeanDeg(), m.dmaxDeg()));
        System.out.println("  arcs measured     = " + m.arcsMeasured()
                + "  steps measured = " + m.stepsMeasured());
        System.out.println("  layout-conformant = " + lr.conformingPatches() + "/"
                + lr.tMeshPatches() + "  (skipped " + lr.skippedPatchIds().size() + ")");

        // Sanity: dmean must be finite and ≥ 0.
        assertTrue(m.dmeanDeg() >= 0 && m.dmeanDeg() < 90,
                "dmean must be a real angle in [0°, 90°); got " + m.dmeanDeg());
        assertTrue(m.dmaxDeg() >= m.dmeanDeg(),
                "dmax must be ≥ dmean");
    }

    private static TArc arcWithSteps(int id, int start, int end, int dir, float[][] steps) {
        var stepList = new ArrayList<float[]>();
        for (var s : steps) stepList.add(s);
        var faces = new ArrayList<int[]>();
        for (int i = 0; i < steps.length; i++) faces.add(new int[]{0, 0});
        return new TArc(id, start, end, faces, stepList, dir, 1f);
    }
}
