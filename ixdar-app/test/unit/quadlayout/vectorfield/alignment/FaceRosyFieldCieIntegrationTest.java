package unit.quadlayout.vectorfield.alignment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.vectorfield.FaceRosyField;
import ixdar.geometry.mesh.quadlayout.vectorfield.Singularity;

/**
 * End-to-end test: FaceRosyField with the CIE*16 directional-constraint chain
 * activated, verifying Poincaré-Hopf invariants on small meshes where we
 * know the answer. Acts as a regression for the CIE*16 wiring.
 *
 * <h3>References</h3>
 * <ul>
 *   <li><b>CIE*16 §3 + §4.1</b> — directional-constraint chain.</li>
 *   <li><b>BZK09 §4 + Campen 2014 thesis Theorem 4.2.1</b> — Poincaré–Hopf
 *       invariant: {@code Σ index4 = 4·χ}.</li>
 * </ul>
 */
public class FaceRosyFieldCieIntegrationTest {

    @Test
    void sphereLevel3StillSatisfiesPoincareHopfWithCie16Enabled() {
        // CIE*16 §3.2 ¶1: sphere is umbilic — no faces should pass into a
        // kept smooth region after significance filtering. Effectively a no-op,
        // and Poincaré-Hopf must hold (Campen-thesis Theorem 4.2.1):
        //   Σ index4 = 4·χ(sphere) = 8.
        ArrayMesh mesh = subdividedSphere(3);
        int chi = mesh.vertexCount() - mesh.edgeCount() + mesh.faceCount();
        assertEquals(2, chi, "sphere chi");

        FaceRosyField field = new FaceRosyField(mesh, 70.0);  // CIE*16 default significance
        field.solve();
        List<Singularity> sings = field.findSingularities();
        int sumIdx4 = 0;
        for (Singularity s : sings) sumIdx4 += s.index4();
        assertEquals(8, sumIdx4, "Poincare-Hopf should hold with CIE*16 enabled on sphere");
    }

    @Test
    void sphereLevel3UnconstrainedAndConstrainedMatch() {
        // On a sphere, CIE*16 should produce no constraints (no significant
        // regions), so the constrained and unconstrained singularity counts
        // should be equal.
        ArrayMesh mesh = subdividedSphere(3);
        FaceRosyField fieldUncon = new FaceRosyField(mesh);
        fieldUncon.solve();
        int sumUncon = 0;
        for (Singularity s : fieldUncon.findSingularities()) sumUncon += s.index4();

        FaceRosyField fieldCon = new FaceRosyField(mesh, 70.0);
        fieldCon.solve();
        int sumCon = 0;
        for (Singularity s : fieldCon.findSingularities()) sumCon += s.index4();

        assertEquals(sumUncon, sumCon,
                "CIE*16 on sphere should be no-op (no kept smooth regions): "
                        + "uncon sum=" + sumUncon + ", cie16 sum=" + sumCon);
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
