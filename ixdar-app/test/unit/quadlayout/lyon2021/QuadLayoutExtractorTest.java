package unit.quadlayout.lyon2021;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import ixdar.geometry.mesh.quadlayout.lyon2021.QuadLayout;
import ixdar.geometry.mesh.quadlayout.lyon2021.QuadLayoutExtractor;
import ixdar.geometry.mesh.quadlayout.lyon2021.QuadLayoutPatch;
import ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.tmesh.TArc;
import ixdar.geometry.mesh.quadlayout.tmesh.TMesh;
import ixdar.geometry.mesh.quadlayout.tmesh.TNode;
import ixdar.geometry.mesh.quadlayout.tmesh.TPatch;

/**
 * PATCH-73: verify the conforming-quad-layout extractor emits 4-sided
 * patches. v1 covers the no-T-junction fast path; T-junction resolution is
 * deferred to PATCH-77.
 */
public class QuadLayoutExtractorTest {

    private static final Path BASELINE_DIR = Paths.get(
            "test/resources/quadlayout/baseline-hand");

    static boolean baselineAvailable() {
        return Files.exists(BASELINE_DIR.resolve("Hand-tri-30k.obj"));
    }

    /** Minimal: a single 4-arc patch maps 1:1 to a layout patch. */
    @Test
    void singlePatchMapsDirectly() {
        List<TNode> nodes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            nodes.add(new TNode(i, TNode.NodeKind.SINGULARITY, -1, 0f, 0f));
        }
        List<TArc> arcs = new ArrayList<>();
        arcs.add(TArc.simple(0, 0, 1, new ArrayList<>(), 0, 1f));
        arcs.add(TArc.simple(1, 1, 2, new ArrayList<>(), 1, 1f));
        arcs.add(TArc.simple(2, 2, 3, new ArrayList<>(), 0, 1f));
        arcs.add(TArc.simple(3, 3, 0, new ArrayList<>(), 1, 1f));
        TPatch p = TPatch.single(0, new int[]{0, 1, 2, 3}, new int[]{0, 1, 2, 3});
        TMesh tmesh = TMesh.fromComponents(nodes, arcs, List.of(p));

        int[] q = {1, 1, 1, 1};
        QuadLayoutExtractor.Result r = QuadLayoutExtractor.extract(tmesh, q);

        assertEquals(1, r.layout().patchCount(), "1 conforming patch expected");
        assertEquals(0, r.skippedPatchIds().size(), "no patches skipped");
        QuadLayoutPatch lp = r.layout().patches().get(0);
        for (int s = 0; s < 4; s++) {
            assertEquals(1, lp.arcsBySide()[s].length,
                    "side " + s + " must be single-arc");
        }
        assertArrayEquals(new int[]{0, 1, 2, 3}, lp.cornerNodeIds());
    }

    /** A patch with one T-junction (side 0 has 2 arcs) and matching q falling
     *  mid-arc on the opposite side — PATCH-80 arc-splits and resolves it. */
    @Test
    void tJunctionPatchResolvesViaArcSplit() {
        List<TNode> nodes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            nodes.add(new TNode(i, TNode.NodeKind.INTERSECTION, -1, 0f, 0f));
        }
        List<TArc> arcs = new ArrayList<>();
        arcs.add(TArc.simple(0, 0, 4, new ArrayList<>(), 0, 0.5f));   // n0 → t-junc
        arcs.add(TArc.simple(1, 4, 1, new ArrayList<>(), 0, 0.5f));   // t-junc → n1
        arcs.add(TArc.simple(2, 1, 2, new ArrayList<>(), 1, 1f));
        arcs.add(TArc.simple(3, 2, 3, new ArrayList<>(), 0, 1f));
        arcs.add(TArc.simple(4, 3, 0, new ArrayList<>(), 1, 1f));
        // arcsBySide: side 0 = [0, 1] (T-junction), others single.
        int[][] sides = {{0, 1}, {2}, {3}, {4}};
        TPatch p = TPatch.multi(0, sides, new int[]{0, 1, 2, 3});
        TMesh tmesh = TMesh.fromComponents(nodes, arcs, List.of(p));

        int[] q = {1, 1, 2, 2, 2};
        QuadLayoutExtractor.Result r = QuadLayoutExtractor.extract(tmesh, q);

        assertEquals(2, r.layout().patchCount(),
                "PATCH-80 arc-split resolves a T-junction with mid-arc match");
        assertEquals(0, r.skippedPatchIds().size());
        assertEquals(1, r.layout().tJunctionsResolved());
    }

    /** Hand-30k regression: extractor runs cleanly, every emitted patch is
     *  4-single-arc-sided, and skipped count is reported. */
    @Test
    @EnabledIf("baselineAvailable")
    void handMeshProducesConformingLayout() throws IOException {
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

        // Synthetic q ≡ 1 (full ILP solve deferred to PATCH-76).
        int[] q = new int[tmesh.arcs().size()];
        java.util.Arrays.fill(q, 1);

        QuadLayoutExtractor.Result r = QuadLayoutExtractor.extract(tmesh, q);
        System.out.println("[hand-quadlayout]");
        System.out.println("  T-mesh patches    = " + r.tMeshPatches());
        System.out.println("  conforming #P     = " + r.conformingPatches());
        System.out.println("  skipped (T-junc)  = " + r.skippedPatchIds().size());

        // Every emitted patch is 4-single-arc-sided.
        for (QuadLayoutPatch lp : r.layout().patches()) {
            for (int s = 0; s < 4; s++) {
                assertEquals(1, lp.arcsBySide()[s].length,
                        "v1 must only emit single-arc-side patches");
            }
        }
        // After PATCH-77 a single TPatch can yield multiple conforming
        // sub-patches AND/OR contribute to skipped (e.g. one sub-patch resolves,
        // another needs PATCH-79 arc-splitting). Loose invariant: every TPatch
        // is accounted for somewhere.
        assertTrue(r.conformingPatches() + r.skippedPatchIds().size() >= r.tMeshPatches(),
                "every T-mesh patch must contribute to conforming or skipped");
    }
}
