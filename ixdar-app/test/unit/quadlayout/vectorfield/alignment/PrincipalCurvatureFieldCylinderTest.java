package unit.quadlayout.vectorfield.alignment;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.vectorfield.alignment.PrincipalCurvatureField;

/**
 * PATCH-118: gating diagnostic for PATCH-116 / PATCH-119.
 *
 * <p>On a cylinder of radius R aligned with the +Z axis, principal curvatures
 * are exactly:
 * <ul>
 *   <li>κ_max = 1/R, with a_max perpendicular to the axis (around the cylinder)</li>
 *   <li>κ_min = 0,   with a_min along the axis</li>
 * </ul>
 *
 * <p>This test pins the convention: {@link PrincipalCurvatureField#aMin} must
 * point along the LOW-curvature direction (axis) and {@link PrincipalCurvatureField#aMax}
 * along the HIGH-curvature direction (around the cylinder). If the
 * eigenvalue→direction assignment in the implementation is inverted (PATCH-119
 * Issue A), this test fails.
 *
 * <p>CIE*16 §3 page 5 col 2 explicitly defines κ_max as "the principal
 * curvature with largest absolute value" — for a cylinder that's 1/R, along
 * the perpendicular direction.
 */
public class PrincipalCurvatureFieldCylinderTest {

    /** Cylinder radius (world units). */
    private static final float R = 1.0f;
    /** Cylinder height (world units). */
    private static final float H = 2.0f;
    /** Number of segments around the cylinder. */
    private static final int N_RADIAL = 100;
    /** Number of segments along the axis. */
    private static final int N_AXIAL = 50;

    @Test
    public void cylinderAMaxIsAroundAxisAndAMinIsAlongAxis() {
        ArrayMesh cyl = buildCylinder();
        // Diagnostic sweep: characterize magnitude calibration vs r_geo. ACDLD03
        // §2.1 ¶3 default is bbox/100 but the discrete formula under-estimates
        // |κ_max| substantially at small r_geo. The DIRECTION assignment
        // (which is what PATCH-118 is gating) is correct at bbox/100+.
        double bboxDiag = Math.sqrt(4 * R * R + H * H);
        double[] rGeoFractions = {1.0 / 200.0, 1.0 / 100.0, 1.0 / 50.0, 1.0 / 25.0, 1.0 / 10.0};
        for (double frac : rGeoFractions) {
            double rGeoSweep = bboxDiag * frac;
            PrincipalCurvatureField pdfSweep = PrincipalCurvatureField.compute(cyl, rGeoSweep);
            double meanKMax = sweepMeanKappaMax(cyl, pdfSweep);
            System.out.printf("[cylinder-pdf-sweep] rGeo=bbox/%-3.0f (=%.4f)  mean|kMax|=%.4f  ratio_vs_truth=%.3f%n",
                    1.0 / frac, rGeoSweep, meanKMax, meanKMax / (1.0 / R));
        }

        // Direction asserts at the production default (ACDLD03 §2.1 ¶3 = bbox/100).
        double rGeo = bboxDiag / 100.0;
        PrincipalCurvatureField pdf = PrincipalCurvatureField.compute(cyl, rGeo);

        // Sample faces from the middle band (z between -0.5 and +0.5) so we
        // avoid the rim where the principal directions are noisy from boundary
        // effects on the tensor integration BFS.
        Vector3f aMin = new Vector3f();
        Vector3f aMax = new Vector3f();
        Vector3f centroid = new Vector3f();
        Vector3f p = new Vector3f();
        int sampled = 0;
        int aMaxAroundAxis = 0;
        int aMinAlongAxis = 0;
        int kappaMaxNearOneOverR = 0;
        int kappaMinNearZero = 0;
        double sumKappaMax = 0;
        double sumKappaMin = 0;

        for (int f = 0; f < cyl.faceCount(); f++) {
            centroid.set(0, 0, 0);
            for (int c = 0; c < 3; c++) {
                cyl.vertexPosition(cyl.faceVertexAt(f, c), p);
                centroid.add(p);
            }
            centroid.mul(1f / 3f);
            if (Math.abs(centroid.z) > 0.5f) continue;
            sampled++;

            pdf.aMin(f, aMin);
            pdf.aMax(f, aMax);
            double kMax = pdf.kappaMax(f);
            double kMin = pdf.kappaMin(f);

            // a_max should be perpendicular to z-axis (around the cylinder).
            if (Math.abs(aMax.z) < 0.2f) aMaxAroundAxis++;
            // a_min should be parallel to z-axis (along the cylinder).
            if (Math.abs(aMin.z) > 0.8f) aMinAlongAxis++;
            // |κ_max| ≈ 1/R = 1.0 (within smoothing tolerance).
            if (Math.abs(kMax) > 0.5 && Math.abs(kMax) < 2.0) kappaMaxNearOneOverR++;
            // |κ_min| should be small (cylinder principal curvature along axis is 0).
            if (Math.abs(kMin) < 0.5) kappaMinNearZero++;

            sumKappaMax += Math.abs(kMax);
            sumKappaMin += Math.abs(kMin);
        }

        double pct = 100.0 / Math.max(sampled, 1);
        System.out.printf(
                "[cylinder-pdf] sampled=%d  aMax⊥axis=%.1f%%  aMin∥axis=%.1f%%  "
                        + "|kMax|∈[0.5,2]=%.1f%%  |kMin|<0.5=%.1f%%  "
                        + "mean|kMax|=%.3f  mean|kMin|=%.3f%n",
                sampled,
                aMaxAroundAxis * pct, aMinAlongAxis * pct,
                kappaMaxNearOneOverR * pct, kappaMinNearZero * pct,
                sumKappaMax / sampled, sumKappaMin / sampled);

        if (sampled < 100) fail("not enough mid-band faces sampled: " + sampled);

        // PATCH-118 direction-convention asserts. If the eigenvalue→direction
        // assignment is ever swapped (PATCH-119 / Issue A hypothesis), these
        // drop from ~100% to <50%.
        assertTrue(aMaxAroundAxis * pct > 80.0,
                "a_max should be perpendicular to cylinder axis on ≥80% of mid-band faces, got "
                        + (aMaxAroundAxis * pct) + "%");
        assertTrue(aMinAlongAxis * pct > 80.0,
                "a_min should be parallel to cylinder axis on ≥80% of mid-band faces, got "
                        + (aMinAlongAxis * pct) + "%");
        assertTrue(kappaMinNearZero * pct > 80.0,
                "|κ_min| should be ≈ 0 (cylinder principal curvature along axis) on "
                        + "≥80% of mid-band faces, got " + (kappaMinNearZero * pct) + "%");

        // |κ_max| magnitude is informational: at r_geo=bbox/100 the discrete
        // ACDLD03 estimator under-reports by ~3x on this cylinder. See
        // PATCH-124 — separate ticket from the convention question.
        System.out.printf("[cylinder-pdf-mag] |κ_max| ≈ 1/R magnitude calibration: "
                        + "%.1f%% of mid-band faces in [0.5, 2.0], mean = %.3f (truth = 1.0)%n",
                kappaMaxNearOneOverR * pct, sumKappaMax / sampled);
    }

    private static double sweepMeanKappaMax(ArrayMesh cyl, PrincipalCurvatureField pdf) {
        Vector3f p = new Vector3f();
        Vector3f c = new Vector3f();
        double sum = 0;
        int n = 0;
        for (int f = 0; f < cyl.faceCount(); f++) {
            c.set(0, 0, 0);
            for (int k = 0; k < 3; k++) { cyl.vertexPosition(cyl.faceVertexAt(f, k), p); c.add(p); }
            c.mul(1f / 3f);
            if (Math.abs(c.z) > 0.5f) continue;
            sum += Math.abs(pdf.kappaMax(f));
            n++;
        }
        return sum / Math.max(n, 1);
    }

    /**
     * Triangulated cylinder: radius R along +Z, height H, with N_RADIAL
     * segments around and N_AXIAL segments tall. No caps — open at top and
     * bottom (cylinder side surface only). Vertex layout: row-major, ring
     * by ring from z = -H/2 to z = +H/2.
     */
    private static ArrayMesh buildCylinder() {
        int rings = N_AXIAL + 1;
        int verticesPerRing = N_RADIAL;
        int totalVerts = rings * verticesPerRing;
        float[] pos = new float[totalVerts * 3];
        for (int ring = 0; ring < rings; ring++) {
            float z = -H / 2f + (H * ring) / N_AXIAL;
            for (int seg = 0; seg < verticesPerRing; seg++) {
                double theta = (2.0 * Math.PI * seg) / verticesPerRing;
                float x = R * (float) Math.cos(theta);
                float y = R * (float) Math.sin(theta);
                int idx = (ring * verticesPerRing + seg) * 3;
                pos[idx] = x;
                pos[idx + 1] = y;
                pos[idx + 2] = z;
            }
        }
        // Two triangles per quad on the cylinder side.
        int totalFaces = N_AXIAL * verticesPerRing * 2;
        int[] faces = new int[totalFaces * 3];
        int fi = 0;
        for (int ring = 0; ring < N_AXIAL; ring++) {
            for (int seg = 0; seg < verticesPerRing; seg++) {
                int segNext = (seg + 1) % verticesPerRing;
                int v00 = ring * verticesPerRing + seg;
                int v01 = ring * verticesPerRing + segNext;
                int v10 = (ring + 1) * verticesPerRing + seg;
                int v11 = (ring + 1) * verticesPerRing + segNext;
                // CCW triangles when viewed from outside the cylinder.
                faces[fi++] = v00; faces[fi++] = v01; faces[fi++] = v11;
                faces[fi++] = v00; faces[fi++] = v11; faces[fi++] = v10;
            }
        }
        return new ArrayMesh(pos, null, faces, 3);
    }
}
