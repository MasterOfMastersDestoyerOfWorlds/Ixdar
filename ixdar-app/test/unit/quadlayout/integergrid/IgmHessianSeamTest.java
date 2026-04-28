package unit.quadlayout.integergrid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.vectorfield.CombedField;
import ixdar.geometry.mesh.quadlayout.vectorfield.FaceRosyField;

/**
 * PATCH-52 / PATCH-54: verify that {@link ixdar.geometry.mesh.quadlayout.integergrid.IgmHessian}'s
 * variable layout puts (j, k) only on seam edges and uses chart-vertex
 * unknowns, not per-corner.
 *
 * <p>{@code IgmHessian} is package-private; this test reaches in via
 * reflection. We assert
 * <ol>
 *   <li>{@code seamEdgeCount} matches {@link CombedField#seamEdgeCount()}.</li>
 *   <li>{@code N == 2 * chartVertexCount + 2 * Es} (PATCH-54 chart-vertex
 *       layout). The pre-PATCH-54 layout was {@code 6F + 2*Es}; the new
 *       layout is strictly smaller because corners on the same vertex inside
 *       a chart collapse to one variable.</li>
 *   <li>{@code seamSlot[e] >= 0} iff {@code combed.isSeamEdge(e)}.</li>
 *   <li>{@code chartVertexCount <= 3 * F} (the pre-PATCH-54 corner count is
 *       an upper bound on the chart-vertex count).</li>
 * </ol>
 *
 * <p>Two meshes: a cube (closed, ~8 singularities, modest seam count) and a
 * subdivided cube (more interior edges, more chart-vertex collapse).
 */
public class IgmHessianSeamTest {

    @Test
    void cubeHessianRestrictsTranslationToSeamEdges() throws Exception {
        ArrayMesh mesh = makeCube();
        FaceRosyField field = new FaceRosyField(mesh);
        field.solve();
        CombedField combed = CombedField.comb(field);
        assertHessianSeamLayout(mesh, field, combed);
    }

    @Test
    void subdividedCubeHessianRestrictsTranslationToSeamEdges() throws Exception {
        ArrayMesh mesh = makeSubdividedCube(2);
        FaceRosyField field = new FaceRosyField(mesh);
        field.solve();
        CombedField combed = CombedField.comb(field);
        int seamCount = combed.seamEdgeCount();
        int interior = field.interiorEdgeCount();
        // A subdivided cube must still have far fewer seam edges than total
        // interior edges; otherwise PATCH-52 buys nothing here.
        assertTrue(seamCount < interior,
                "subdivided cube: seam=" + seamCount + " interior=" + interior);
        assertHessianSeamLayout(mesh, field, combed);
    }

    private static void assertHessianSeamLayout(ArrayMesh mesh,
                                                 FaceRosyField field,
                                                 CombedField combed) throws Exception {
        Class<?> cls = Class.forName("ixdar.geometry.mesh.quadlayout.integergrid.IgmHessian");
        Constructor<?> ctor = cls.getDeclaredConstructor(
                ArrayMesh.class, FaceRosyField.class, CombedField.class);
        ctor.setAccessible(true);
        Object H = ctor.newInstance(mesh, field, combed);
        assertNotNull(H);

        Field seamEdgeCount = cls.getDeclaredField("seamEdgeCount");
        seamEdgeCount.setAccessible(true);
        Field seamSlot = cls.getDeclaredField("seamSlot");
        seamSlot.setAccessible(true);
        Field nField = cls.getDeclaredField("N");
        nField.setAccessible(true);
        Field faceCount = cls.getDeclaredField("faceCount");
        faceCount.setAccessible(true);
        Field interiorEdgeCount = cls.getDeclaredField("interiorEdgeCount");
        interiorEdgeCount.setAccessible(true);
        Field chartField = cls.getDeclaredField("chart");
        chartField.setAccessible(true);
        Object chart = chartField.get(H);
        Field chartVertexCountField = chart.getClass().getDeclaredField("chartVertexCount");
        chartVertexCountField.setAccessible(true);
        int numCV = chartVertexCountField.getInt(chart);

        int Es = seamEdgeCount.getInt(H);
        int N = nField.getInt(H);
        int F = faceCount.getInt(H);
        int Ei = interiorEdgeCount.getInt(H);
        int[] slots = (int[]) seamSlot.get(H);

        // Hessian's seam count must match CombedField's reported count.
        assertEquals(combed.seamEdgeCount(), Es,
                "IgmHessian.seamEdgeCount must match CombedField.seamEdgeCount");

        // Variable layout: 2*numCV + 2*Es (PATCH-54 chart-vertex layout).
        assertEquals(2 * numCV + 2 * Es, N,
                "Hessian dimension must be 2*chartVertexCount + 2*Es (PATCH-54 layout)");
        // Sanity: chart-vertex count never exceeds the per-corner count.
        assertTrue(numCV <= 3 * F,
                "chartVertexCount " + numCV + " exceeds 3*F upper bound " + (3 * F));

        // Per-edge slot map alignment with seam predicate.
        assertEquals(Ei, slots.length, "seamSlot length");
        int seenSeam = 0;
        for (int e = 0; e < Ei; e++) {
            if (combed.isSeamEdge(e)) {
                assertTrue(slots[e] >= 0, "seam edge " + e + " missing slot");
                seenSeam++;
            } else {
                assertEquals(-1, slots[e],
                        "non-seam edge " + e + " must have seamSlot == -1");
            }
        }
        assertEquals(Es, seenSeam,
                "seam-edge count walk must equal Es");
    }

    private static ArrayMesh makeCube() {
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

    private static ArrayMesh makeSubdividedCube(int n) {
        java.util.HashMap<Long, Integer> vertexOf = new java.util.HashMap<>();
        java.util.ArrayList<Float> pos = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> faces = new java.util.ArrayList<>();
        for (int axis = 0; axis < 3; axis++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                int[][] grid = new int[n + 1][n + 1];
                for (int j = 0; j <= n; j++) {
                    for (int i = 0; i <= n; i++) {
                        float[] xyz = new float[3];
                        xyz[axis] = sign * 0.5f;
                        int u = (axis + 1) % 3;
                        int v = (axis + 2) % 3;
                        xyz[u] = -0.5f + (float) i / n;
                        xyz[v] = -0.5f + (float) j / n;
                        long key = Math.round(xyz[0] * 1_000_000.0) * 1_000_001L * 1_000_001L
                                + Math.round(xyz[1] * 1_000_000.0) * 1_000_001L
                                + Math.round(xyz[2] * 1_000_000.0);
                        Integer existing = vertexOf.get(key);
                        int idx;
                        if (existing != null) {
                            idx = existing;
                        } else {
                            idx = pos.size() / 3;
                            pos.add(xyz[0]);
                            pos.add(xyz[1]);
                            pos.add(xyz[2]);
                            vertexOf.put(key, idx);
                        }
                        grid[j][i] = idx;
                    }
                }
                for (int j = 0; j < n; j++) {
                    for (int i = 0; i < n; i++) {
                        int a = grid[j][i];
                        int b = grid[j][i + 1];
                        int c = grid[j + 1][i + 1];
                        int d = grid[j + 1][i];
                        boolean flip = (sign > 0) ^ ((axis + 1) % 2 == 1);
                        if (flip) {
                            faces.add(a); faces.add(b); faces.add(c);
                            faces.add(a); faces.add(c); faces.add(d);
                        } else {
                            faces.add(a); faces.add(c); faces.add(b);
                            faces.add(a); faces.add(d); faces.add(c);
                        }
                    }
                }
            }
        }
        float[] posArr = new float[pos.size()];
        for (int i = 0; i < posArr.length; i++) posArr[i] = pos.get(i);
        int[] faceArr = faces.stream().mapToInt(Integer::intValue).toArray();
        return new ArrayMesh(posArr, null, faceArr, 3);
    }
}
