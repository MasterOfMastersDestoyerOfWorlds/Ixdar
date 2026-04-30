package unit.quadlayout.lyon2021;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.field.PrecomputedFieldImporter;
import ixdar.geometry.mesh.quadlayout.integergrid.SeamlessParameterization;
import ixdar.geometry.mesh.quadlayout.lyon2021.LyonLayoutDecomposer;
import ixdar.geometry.mesh.quadlayout.lyon2021.QuadLayoutExtractor;
import ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.tmesh.TMesh;

/** PATCH-33 toward Goal #2: verify the Lyon → DecompositionDiagnostics
 *  bridge assigns every mesh face to a layout patch. */
public class LyonLayoutDecomposerTest {

    private static final Path BASELINE_DIR = Paths.get(
            "test/resources/quadlayout/baseline-hand");

    static boolean baselineAvailable() {
        return Files.exists(BASELINE_DIR.resolve("Hand-tri-30k.obj"));
    }

    @Test
    @EnabledIf("baselineAvailable")
    void everyMeshFaceGetsAPatchLabel() throws IOException {
        Path obj = BASELINE_DIR.resolve("Hand-tri-30k.obj");
        PrecomputedFieldImporter.Result boot = PrecomputedFieldImporter.load(
                obj,
                BASELINE_DIR.resolve("stage1_extrinsic_field.tsv"),
                BASELINE_DIR.resolve("stage1_matching.txt"),
                BASELINE_DIR.resolve("stage1_seam.txt"),
                BASELINE_DIR.resolve("stage1_singular.txt"));
        ArrayMesh mesh = boot.mesh();
        float[][] uv = PrecomputedFieldImporter.loadStage2Uv(
                BASELINE_DIR.resolve("stage2_uv_corners.tsv"), mesh.faceCount());
        SeamlessParameterization param = SeamlessParameterization.fromExternal(
                mesh, uv[0], uv[1], true);
        MotorcycleGraph.Result graph = MotorcycleGraph.trace(
                param, mesh, boot.field(), boot.combed(), boot.singularities());
        TMesh tmesh = TMesh.build(graph, param);

        int[] q = new int[tmesh.arcs().size()];
        java.util.Arrays.fill(q, 1);
        var trs = ixdar.geometry.mesh.quadlayout.extraction.TransitionMatrix.compute(
                mesh, uv[0], uv[1], boot.combed());
        QuadLayoutExtractor.Result lr = QuadLayoutExtractor.extract(tmesh, q,
                mesh, uv[0], uv[1], trs);
        var diag = LyonLayoutDecomposer.decompose(mesh, tmesh, lr.layout());

        assertNotNull(diag.decomposition());
        assertNotNull(diag.facePatchId());
        assertEquals(mesh.copyFaceIndices().length / 3, diag.facePatchId().length);

        int unlabeled = 0;
        for (int label : diag.facePatchId()) {
            if (label < 0) unlabeled++;
        }
        assertTrue(unlabeled == 0,
                "every mesh face must get a patch label; got " + unlabeled + " unlabeled");

        assertTrue(diag.decomposition().patches().size()
                        == lr.layout().patches().size() + lr.layout().triangles().size(),
                "Patch count must match layout (quads + triangles)");
    }
}
