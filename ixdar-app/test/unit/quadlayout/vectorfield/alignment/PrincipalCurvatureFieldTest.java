package unit.quadlayout.vectorfield.alignment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.vectorfield.alignment.PrincipalCurvatureField;

/**
 * Unit tests for {@link PrincipalCurvatureField}.
 *
 * <h3>References</h3>
 * <ul>
 *   <li><b>ACDLD03 §2.4</b> — umbilic regions, where principal directions
 *       are not well defined; field is isotropic on locally spherical
 *       surfaces.</li>
 *   <li><b>CIE*16 §3.2 ¶1</b> — robust normal estimation.</li>
 * </ul>
 */
public class PrincipalCurvatureFieldTest {

    @Test
    void sphereHasIsotropicCurvature() {
        // ACDLD03 §2.4: sphere is umbilic — κ_min ≈ κ_max ≈ 1, anisotropy ≈ 0.
        ArrayMesh sphere = subdividedSphere(3);
        double bbox = boundingBoxDiagonal(sphere);
        var pdf = PrincipalCurvatureField.compute(sphere, bbox / 100.0);
        // On a unit sphere, both principal magnitudes should be roughly the same.
        // Tensor magnitudes can differ from the analytical 1.0 due to discretization
        // and integration domain choice; we just check that anisotropy is low.
        int nLowAnisotropy = 0;
        for (int f = 0; f < sphere.faceCount(); f++) {
            double kMin = Math.abs(pdf.kappaMin(f));
            double kMax = Math.abs(pdf.kappaMax(f));
            double denom = Math.max(kMin, kMax);
            if (denom < 1e-12) { nLowAnisotropy++; continue; }
            double anisotropy = (kMax - kMin) / denom;   // BZK09 §3 τ
            if (anisotropy < 0.5) nLowAnisotropy++;
        }
        // At least 80% of faces should be near-isotropic on a sphere.
        double frac = (double) nLowAnisotropy / sphere.faceCount();
        assertTrue(frac > 0.80, "sphere should be mostly isotropic; low-anisotropy fraction = " + frac);
    }

    @Test
    void allFacesProduceUnitDirections() {
        // CIE*16 §3.2 ¶2: a_min, a_max are unit vectors per face.
        ArrayMesh sphere = subdividedSphere(2);
        var pdf = PrincipalCurvatureField.compute(sphere, boundingBoxDiagonal(sphere) / 50.0);
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        Vector3f n = new Vector3f();
        for (int f = 0; f < sphere.faceCount(); f++) {
            pdf.aMin(f, a);
            pdf.aMax(f, b);
            pdf.normal(f, n);
            assertEquals(1.0, a.length(), 1e-3, "a_min not unit at face " + f);
            assertEquals(1.0, b.length(), 1e-3, "a_max not unit at face " + f);
            assertEquals(1.0, n.length(), 1e-3, "normal not unit at face " + f);
            // CIE*16 §3.2 ¶1: a_min ⊥ n, a_max ⊥ n.
            assertTrue(Math.abs(a.dot(n)) < 1e-2, "a_min not orthogonal to n at face " + f);
            assertTrue(Math.abs(b.dot(n)) < 1e-2, "a_max not orthogonal to n at face " + f);
            // a_max ⊥ a_min by construction.
            assertTrue(Math.abs(a.dot(b)) < 1e-2, "a_max not orthogonal to a_min at face " + f);
        }
    }

    private static double boundingBoxDiagonal(ArrayMesh mesh) {
        Vector3f p = new Vector3f();
        mesh.vertexPosition(0, p);
        float minX = p.x, minY = p.y, minZ = p.z;
        float maxX = p.x, maxY = p.y, maxZ = p.z;
        for (int v = 1; v < mesh.vertexCount(); v++) {
            mesh.vertexPosition(v, p);
            if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x;
            if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y;
            if (p.z < minZ) minZ = p.z; if (p.z > maxZ) maxZ = p.z;
        }
        double dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static ArrayMesh subdividedSphere(int levels) {
        float[] pos = {1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1};
        int[] faces = {0, 2, 4, 2, 1, 4, 1, 3, 4, 3, 0, 4, 2, 0, 5, 1, 2, 5, 3, 1, 5, 0, 3, 5};
        for (int l = 0; l < levels; l++) {
            java.util.HashMap<Long, Integer> midOf = new java.util.HashMap<>();
            java.util.ArrayList<Float> newPos = new java.util.ArrayList<>();
            for (float p : pos) newPos.add(p);
            int Fold = faces.length / 3;
            int[] newFaces = new int[Fold * 4 * 3];
            int fc = 0;
            for (int f = 0; f < Fold; f++) {
                int a = faces[f * 3], b = faces[f * 3 + 1], c = faces[f * 3 + 2];
                int ab = mid(a, b, pos, midOf, newPos);
                int bc = mid(b, c, pos, midOf, newPos);
                int ca = mid(c, a, pos, midOf, newPos);
                newFaces[fc++] = a; newFaces[fc++] = ab; newFaces[fc++] = ca;
                newFaces[fc++] = b; newFaces[fc++] = bc; newFaces[fc++] = ab;
                newFaces[fc++] = c; newFaces[fc++] = ca; newFaces[fc++] = bc;
                newFaces[fc++] = ab; newFaces[fc++] = bc; newFaces[fc++] = ca;
            }
            pos = new float[newPos.size()];
            for (int i = 0; i < pos.length; i++) pos[i] = newPos.get(i);
            faces = newFaces;
            int Vnew = pos.length / 3;
            for (int v = 0; v < Vnew; v++) {
                float x = pos[v * 3], y = pos[v * 3 + 1], z = pos[v * 3 + 2];
                float len = (float) Math.sqrt(x * x + y * y + z * z);
                if (len > 1e-30f) { pos[v * 3] = x / len; pos[v * 3 + 1] = y / len; pos[v * 3 + 2] = z / len; }
            }
        }
        return new ArrayMesh(pos, null, faces, 3);
    }

    private static int mid(int a, int b, float[] pos,
                           java.util.HashMap<Long, Integer> midOf,
                           java.util.ArrayList<Float> newPos) {
        long key = (((long) Math.min(a, b)) << 32) | (Math.max(a, b) & 0xFFFFFFFFL);
        Integer ex = midOf.get(key);
        if (ex != null) return ex;
        int idx = newPos.size() / 3;
        newPos.add((pos[a * 3] + pos[b * 3]) * 0.5f);
        newPos.add((pos[a * 3 + 1] + pos[b * 3 + 1]) * 0.5f);
        newPos.add((pos[a * 3 + 2] + pos[b * 3 + 2]) * 0.5f);
        midOf.put(key, idx);
        return idx;
    }
}
