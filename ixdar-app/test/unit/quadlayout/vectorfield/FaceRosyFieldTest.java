package unit.quadlayout.vectorfield;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.boundary.BoundaryCapper;
import ixdar.geometry.mesh.quadlayout.vectorfield.CombedField;
import ixdar.geometry.mesh.quadlayout.vectorfield.FaceRosyField;
import ixdar.geometry.mesh.quadlayout.vectorfield.Singularity;

public class FaceRosyFieldTest {

    @Test
    void flatPlaneCappedHasFewSingularitiesAndLowEnergy() {
        // 4x4 grid of vertices = 25 verts, triangulated into 32 triangles.
        // Open boundary, so cap it. The angle-based formulation on a flat
        // disc capped by a fan should still produce ~no singularities away
        // from the cap centroid.
        int n = 5;
        float[] pos = new float[n * n * 3];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int v = j * n + i;
                pos[v * 3] = i;
                pos[v * 3 + 1] = j;
                pos[v * 3 + 2] = 0f;
            }
        }
        ArrayList<Integer> idx = new ArrayList<>();
        for (int j = 0; j < n - 1; j++) {
            for (int i = 0; i < n - 1; i++) {
                int v00 = j * n + i;
                int v10 = j * n + (i + 1);
                int v01 = (j + 1) * n + i;
                int v11 = (j + 1) * n + (i + 1);
                idx.add(v00); idx.add(v10); idx.add(v11);
                idx.add(v00); idx.add(v11); idx.add(v01);
            }
        }
        int[] faces = idx.stream().mapToInt(Integer::intValue).toArray();
        ArrayMesh raw = new ArrayMesh(pos, null, faces, 3);
        BoundaryCapper.CapResult cap = BoundaryCapper.cap(raw);
        ArrayMesh mesh = cap.closedMesh();

        FaceRosyField field = new FaceRosyField(mesh);
        field.solve();
        double E = field.smoothnessEnergy();
        // The cap fan triangles introduce some non-flat structure, but the
        // residual energy should be small.
        assertTrue(E < 50.0, "smoothness energy too high: " + E);

        List<Singularity> sings = field.findSingularities();
        // On a topological disc capped to a sphere, chi=2 -> sum(index4)=8.
        int sumIdx4 = 0;
        for (Singularity s : sings) sumIdx4 += s.index4();
        int chi = mesh.vertexCount() - mesh.edgeCount() + mesh.faceCount();
        assertEquals(4 * chi, sumIdx4,
                "Poincare-Hopf failure: sum(idx4)=" + sumIdx4 + " 4*chi=" + (4 * chi));
    }

    @Test
    void subdividedSphereSumsToEightQuarters() {
        // Subdivide an octahedron a few times so that per-vertex angle defect
        // is small enough to dodge the coarse-mesh rounding artefact (where
        // 2*K(v)/pi is irrational and per-vertex round() can't sum to 4*chi).
        ArrayMesh mesh = subdividedSphere(3);
        int chi = mesh.vertexCount() - mesh.edgeCount() + mesh.faceCount();
        assertEquals(2, chi, "subdivided sphere should have chi=2");

        FaceRosyField field = new FaceRosyField(mesh);
        field.solve();

        List<Singularity> sings = field.findSingularities();
        int sumIdx4 = 0;
        for (Singularity s : sings) sumIdx4 += s.index4();
        assertEquals(8, sumIdx4, "sphere singularity index sum should equal 4*chi=8; got "
                + sumIdx4 + " over " + sings.size() + " singularities (mesh F=" + mesh.faceCount() + ")");
        // PATCH-50 BZK12 should produce a small singularity count on a smooth
        // sphere - far below the spurious-rounding bound of the per-edge
        // PATCH-39 path. Subdivided octahedron has 6 octahedral corners + 12
        // edge midpoints; the field concentrates curvature on a small subset.
        assertTrue(sings.size() <= 12,
                "BZK12 should produce <= 12 singularities on subdivided sphere; got " + sings.size());
    }

    @Test
    void cubeProducesEightCornerSingularities() {
        // PATCH-50: BZK12 greedy round-and-resolve should detect every corner
        // of a closed cube as a +1/4 singularity (Poincare-Hopf sum = 8 = 4*chi
        // for a sphere-topology surface). PATCH-39's per-edge rounding under-
        // detected 2 corners on this mesh.
        ArrayMesh mesh = makeClosedCube();
        int chi = mesh.vertexCount() - mesh.edgeCount() + mesh.faceCount();
        assertEquals(2, chi, "closed cube should have chi=2");

        FaceRosyField field = new FaceRosyField(mesh);
        field.solve();

        List<Singularity> sings = field.findSingularities();
        int sumIdx4 = 0;
        for (Singularity s : sings) sumIdx4 += s.index4();
        assertEquals(8, sumIdx4, "cube singularity index sum should equal 4*chi=8; got "
                + sumIdx4 + " over " + sings.size() + " singularities");
        assertTrue(sings.size() <= 8,
                "BZK12 should produce <= 8 singularities on cube; got " + sings.size());
    }

    @Test
    void torusHasZeroSumOfIndices() {
        ArrayMesh mesh = makeTorus(12, 8, 1.0f, 0.4f);
        int chi = mesh.vertexCount() - mesh.edgeCount() + mesh.faceCount();
        assertEquals(0, chi, "torus chi should be 0");

        FaceRosyField field = new FaceRosyField(mesh);
        field.solve();
        List<Singularity> sings = field.findSingularities();
        int sumIdx4 = 0;
        for (Singularity s : sings) sumIdx4 += s.index4();
        assertEquals(0, sumIdx4, "torus singularity index sum should be 0; got " + sumIdx4
                + " over " + sings.size() + " singularities");
    }

    @Test
    void combedFieldExposesSeamMatching() {
        ArrayMesh mesh = makeIcosphere();
        FaceRosyField field = new FaceRosyField(mesh);
        field.solve();
        CombedField combed = CombedField.comb(field);

        assertNotNull(combed);
        // Every face has a branch in {0,1,2,3}.
        for (int f = 0; f < mesh.faceCount(); f++) {
            int b = combed.branch(f);
            assertTrue(b >= 0 && b < 4, "branch out of range at face " + f + ": " + b);
        }
        // All matchings in {0..3}.
        for (int e = 0; e < field.interiorEdgeCount(); e++) {
            int r = combed.matching(e);
            assertTrue(r >= 0 && r < 4, "matching out of range at edge " + e + ": " + r);
        }
    }

    private static ArrayMesh makeClosedCube() {
        float[] pos = {
                -0.5f, -0.5f, -0.5f,
                 0.5f, -0.5f, -0.5f,
                 0.5f,  0.5f, -0.5f,
                -0.5f,  0.5f, -0.5f,
                -0.5f, -0.5f,  0.5f,
                 0.5f, -0.5f,  0.5f,
                 0.5f,  0.5f,  0.5f,
                -0.5f,  0.5f,  0.5f,
        };
        int[] faces = {
                0, 2, 1,  0, 3, 2,
                4, 5, 6,  4, 6, 7,
                0, 1, 5,  0, 5, 4,
                3, 7, 6,  3, 6, 2,
                0, 4, 7,  0, 7, 3,
                1, 2, 6,  1, 6, 5,
        };
        return new ArrayMesh(pos, null, faces, 3);
    }

    private ArrayMesh makeTorus(int majorSegs, int minorSegs, float R, float r) {
        int V = majorSegs * minorSegs;
        float[] pos = new float[V * 3];
        for (int i = 0; i < majorSegs; i++) {
            double u = 2.0 * Math.PI * i / majorSegs;
            double cu = Math.cos(u);
            double su = Math.sin(u);
            for (int j = 0; j < minorSegs; j++) {
                double v = 2.0 * Math.PI * j / minorSegs;
                double cv = Math.cos(v);
                double sv = Math.sin(v);
                int vi = i * minorSegs + j;
                pos[vi * 3] = (float) ((R + r * cv) * cu);
                pos[vi * 3 + 1] = (float) ((R + r * cv) * su);
                pos[vi * 3 + 2] = (float) (r * sv);
            }
        }
        int[] faces = new int[majorSegs * minorSegs * 6];
        int c = 0;
        for (int i = 0; i < majorSegs; i++) {
            int ip = (i + 1) % majorSegs;
            for (int j = 0; j < minorSegs; j++) {
                int jp = (j + 1) % minorSegs;
                int v00 = i * minorSegs + j;
                int v10 = ip * minorSegs + j;
                int v01 = i * minorSegs + jp;
                int v11 = ip * minorSegs + jp;
                faces[c++] = v00; faces[c++] = v10; faces[c++] = v11;
                faces[c++] = v00; faces[c++] = v11; faces[c++] = v01;
            }
        }
        return new ArrayMesh(pos, null, faces, 3);
    }

    private ArrayMesh makeIcosphere() {
        return subdividedSphere(2);
    }

    /**
     * Loop-subdivide an octahedron {@code levels} times and project onto the
     * unit sphere after each pass. Produces a smooth triangulated sphere with
     * 8 * 4^levels faces.
     */
    private static ArrayMesh subdividedSphere(int levels) {
        float[] pos = {
                1, 0, 0,
                -1, 0, 0,
                0, 1, 0,
                0, -1, 0,
                0, 0, 1,
                0, 0, -1
        };
        int[] faces = {
                0, 2, 4,
                2, 1, 4,
                1, 3, 4,
                3, 0, 4,
                2, 0, 5,
                1, 2, 5,
                3, 1, 5,
                0, 3, 5
        };
        for (int l = 0; l < levels; l++) {
            int Vold = pos.length / 3;
            // For each edge, create a midpoint vertex (deduped via
            // (min,max) key).
            java.util.HashMap<Long, Integer> midOf = new java.util.HashMap<>();
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
            // Project all to unit sphere.
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
