package unit.quadlayout.vectorfield;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.MeshLoader;
import ixdar.geometry.mesh.quadlayout.vectorfield.FaceRosyField;
import ixdar.geometry.mesh.quadlayout.vectorfield.Singularity;

/**
 * PATCH-115 regression: rocker-arm-20k cross-field singularity count.
 *
 * <p>Lyon 2021 Table 1 (page 311, ROCKERARM row) reports 36 singularities at
 * α=15° on this exact mesh. The metriko reference implementation (libigl-
 * based, used as our golden) produces 32 singularities — see
 * {@code baseline-rocker-arm/stage1_singular.txt}. Pure BZK09 §4 with no
 * directional constraints reproduces 36 exactly.
 *
 * <p>This test pins that property: future changes to the cross-field stage
 * must not regress past 50 singularities. Earlier Ixdar attempts had
 * ~700-1335 singularities until PATCH-115 disabled the over-constraining
 * CIE*16 alignment chain by default.
 */
public class FaceRosyFieldRockerArmRegressionTest {

    /** Search both module-relative (mvn test default) and workspace-relative paths. */
    private static Path rockerArmPath() {
        String[] candidates = {
                "test/resources/quadlayout/baseline-rocker-arm/rocker-arm.obj",
                "ixdar-app/test/resources/quadlayout/baseline-rocker-arm/rocker-arm.obj",
        };
        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.exists(p)) return p;
        }
        return null;
    }

    static boolean rockerArmAvailable() {
        return rockerArmPath() != null;
    }

    @Test
    @EnabledIf("rockerArmAvailable")
    public void rockerArm20kProduces32To50Singularities() throws IOException {
        ArrayMesh mesh = MeshLoader.load(rockerArmPath().toString());
        assertEquals(20088, mesh.faceCount(), "rocker-arm faceCount sanity");

        // No second arg → principalThreshold = +Infinity → CIE*16 alignment OFF
        // (PATCH-115). Pure BZK09 §4 mixed-integer cross-field. Lyon §7 ¶1
        // says they use BZK09 + CIE*16 directional constraints, but our CIE*16
        // implementation over-constrains 50% of the mesh and inflates the sing
        // count to ~700; until that's fixed (separate PATCH-117), the bench
        // and tests use the unconstrained path which matches Lyon's count
        // exactly.
        FaceRosyField field = new FaceRosyField(mesh);
        field.solve();
        List<Singularity> sings = field.findSingularities();

        int n = sings.size();
        assertTrue(n >= 32 && n <= 50,
                "rocker-arm sing count must be in [32, 50] (paper=36, metriko=32, got "
                        + n + "). PATCH-115 caps any regression past 50.");

        // Poincaré–Hopf: rocker-arm is genus-0 closed surface (χ = 2)? Or
        // is it higher genus? Empirically Σ index4 = 0 on this mesh, so
        // χ = 0 — torus topology (the rocker-arm has a hole). Both the
        // metriko reference and our solver agree on this.
        int sumIdx4 = 0;
        for (Singularity s : sings) sumIdx4 += s.index4();
        assertEquals(0, sumIdx4,
                "Poincare-Hopf: Σ index4 must equal 4·χ. Rocker-arm is a torus (χ=0).");
    }
}
