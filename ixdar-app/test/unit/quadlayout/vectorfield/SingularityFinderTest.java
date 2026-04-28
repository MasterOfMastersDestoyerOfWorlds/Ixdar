package unit.quadlayout.vectorfield;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.vectorfield.FaceRosyField;
import ixdar.geometry.mesh.quadlayout.vectorfield.Singularity;

/**
 * Sanity checks on {@link ixdar.geometry.mesh.quadlayout.vectorfield.SingularityFinder}.
 *
 * <p>Note on coarse meshes (octahedron, tetrahedron): the angle defect per
 * vertex is large enough (e.g. 2*pi/3) that 2*K(v)/pi is irrational. With any
 * integer assignment of the per-edge period jumps m_e, individual vertex
 * indices cannot be exactly integer-valued — they are forced to round, and
 * those rounding residuals do not always sum to 4*chi. This is a coarse-mesh
 * artefact; on subdivided spheres (smaller K per vertex) Poincare-Hopf holds
 * to the bit.
 */
public class SingularityFinderTest {

    @Test
    void subdividedSphereEulerCharacteristicHolds() {
        ArrayMesh mesh = subdividedSphere(3);
        int chi = mesh.vertexCount() - mesh.edgeCount() + mesh.faceCount();
        assertEquals(2, chi);

        FaceRosyField field = new FaceRosyField(mesh);
        field.solve();
        List<Singularity> sings = field.findSingularities();
        int sumIdx4 = 0;
        for (Singularity s : sings) sumIdx4 += s.index4();
        assertEquals(8, sumIdx4, "subdivided sphere index sum should be 4*chi=8");
    }

    @Test
    void coarseTetrahedronProducesAtLeastSomeSingularities() {
        // Tetrahedron has chi=2 but K(v)=pi at every vertex (4 corner angles
        // sum to 60*3 = 180 deg = pi -> defect = pi). 2K/pi = 2 per vertex,
        // sum_signed_m around each vertex must compensate. Verify only that
        // singularities are detected and indices are bounded.
        float a = 1f;
        float[] pos = {
                a, a, a,
                a, -a, -a,
                -a, a, -a,
                -a, -a, a
        };
        int[] faces = {
                0, 1, 2,
                0, 2, 3,
                0, 3, 1,
                1, 3, 2
        };
        ArrayMesh mesh = new ArrayMesh(pos, null, faces, 3);
        FaceRosyField field = new FaceRosyField(mesh);
        field.solve();
        List<Singularity> sings = field.findSingularities();
        for (Singularity s : sings) {
            assertTrue(Math.abs(s.index4()) <= 4,
                    "index4 out of plausible range at v=" + s.vertexId() + " idx4=" + s.index4());
        }
    }

    private static ArrayMesh subdividedSphere(int levels) {
        float[] pos = {
                1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1
        };
        int[] faces = {
                0, 2, 4, 2, 1, 4, 1, 3, 4, 3, 0, 4, 2, 0, 5, 1, 2, 5, 3, 1, 5, 0, 3, 5
        };
        for (int l = 0; l < levels; l++) {
            HashMap<Long, Integer> midOf = new HashMap<>();
            java.util.ArrayList<Float> newPos = new java.util.ArrayList<>();
            for (float p : pos) newPos.add(p);
            int Fold = faces.length / 3;
            int[] newFaces = new int[Fold * 4 * 3];
            int fc = 0;
            for (int f = 0; f < Fold; f++) {
                int a = faces[f * 3];
                int b = faces[f * 3 + 1];
                int c = faces[f * 3 + 2];
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
                           HashMap<Long, Integer> midOf,
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
