package unit.quadlayout.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import ixdar.geometry.mesh.quadlayout.field.PrecomputedFieldImporter;
import ixdar.geometry.mesh.quadlayout.integergrid.SeamlessParameterization;
import ixdar.geometry.mesh.quadlayout.vectorfield.Singularity;

public class PrecomputedFieldImporterTest {

    private static final Path BASELINE_DIR = Paths.get(
            "test/resources/quadlayout/baseline-hand");

    static boolean baselineAvailable() {
        return Files.exists(BASELINE_DIR.resolve("Hand-tri-30k.obj"))
                && Files.exists(BASELINE_DIR.resolve("stage1_extrinsic_field.tsv"))
                && Files.exists(BASELINE_DIR.resolve("stage1_matching.txt"))
                && Files.exists(BASELINE_DIR.resolve("stage1_seam.txt"))
                && Files.exists(BASELINE_DIR.resolve("stage1_singular.txt"));
    }

    @Test
    @EnabledIf("baselineAvailable")
    void countsLineUpWithMetrikoFiles() throws IOException {
        PrecomputedFieldImporter.Result r = loadBaseline();
        assertNotNull(r);
        assertEquals(29960, r.mesh().faceCount());
        assertEquals(14982, r.mesh().vertexCount());
        // # matching values per metriko = E_total = 44940 == interior edge count
        // since the Hand mesh is closed.
        assertEquals(44940, r.field().interiorEdgeCount());

        // # singularities loaded = # non-zero lines in stage1_singular.txt.
        // Hand baseline: 27 +1 entries, 19 -1 entries, sum = 8 (= 4 * chi for
        // sphere topology).
        List<Singularity> sing = r.singularities();
        assertEquals(46, sing.size());
        int sum = sing.stream().mapToInt(Singularity::index4).sum();
        assertEquals(8, sum, "sum(index4) == 4*chi for closed surface (sphere = 2)");
    }

    @Test
    @EnabledIf("baselineAvailable")
    void seamEdgesNonEmpty() throws IOException {
        PrecomputedFieldImporter.Result r = loadBaseline();
        assertTrue(r.combed().seamEdgeCount() > 0,
                "metriko ships a non-trivial seam graph");
        // Sanity: every matching value is in {0,1,2,3}.
        int E = r.field().interiorEdgeCount();
        for (int i = 0; i < E; i++) {
            int m = r.combed().matching(i);
            assertTrue(m >= 0 && m < 4, "matching out of range at " + i + ": " + m);
        }
    }

    @Test
    @EnabledIf("baselineAvailable")
    void downstreamSeamlessHandlesBaseline() throws IOException {
        PrecomputedFieldImporter.Result r = loadBaseline();
        // Run PATCH-40 / PATCH-48 on top of the bootstrapped triple. The
        // point of the test is to prove the bootstrap-produced triple is
        // shape-compatible with the downstream solver and that the IGM
        // pipeline runs end-to-end at Hand-30k scale.
        //
        // PATCH-54 collapsed the per-corner variable layout (N=180k, did not
        // converge under any preconditioner) to per-vertex (N=30k, CG+ICC
        // converges in ~500 iter / 1s). 4 pin attempts at ~1s each puts the
        // total around 100s including bootstrap parsing + log-barrier Newton
        // iterations. We don't assert injectivity yet — the iterative-rounding
        // loop intentionally caps at 4 pins to keep the test fast; full
        // injectivity validation is the rocker-arm benchmark CLI.
        SeamlessParameterization param = new SeamlessParameterization(
                r.mesh(), r.field(), r.combed(), r.singularities(),
                /*maxRoundingIter=*/4);
        assertNotNull(param);
    }

    private static PrecomputedFieldImporter.Result loadBaseline() throws IOException {
        return PrecomputedFieldImporter.load(
                BASELINE_DIR.resolve("Hand-tri-30k.obj"),
                BASELINE_DIR.resolve("stage1_extrinsic_field.tsv"),
                BASELINE_DIR.resolve("stage1_matching.txt"),
                BASELINE_DIR.resolve("stage1_seam.txt"),
                BASELINE_DIR.resolve("stage1_singular.txt"));
    }
}
