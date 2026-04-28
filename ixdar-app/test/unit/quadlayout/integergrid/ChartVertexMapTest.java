package unit.quadlayout.integergrid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.vectorfield.CombedField;
import ixdar.geometry.mesh.quadlayout.vectorfield.FaceRosyField;

/**
 * PATCH-54 — direct coverage for {@link ixdar.geometry.mesh.quadlayout.integergrid.ChartVertexMap}.
 *
 * <p>The map is package-private; tests reach in via reflection. Asserts:
 * <ol>
 *   <li>For a closed surface with no seams, every face lands in chart 0 and
 *       {@code chartVertexCount == V} (one chart-vertex per mesh vertex,
 *       no duplication).</li>
 *   <li>{@code chartVertexCount} never exceeds {@code 3 * F}: every corner
 *       contributes at most one new chart-vertex.</li>
 *   <li>Inside any single chart, sibling corners on the same mesh vertex
 *       resolve to the SAME chart-vertex id (the whole point of the rewrite).</li>
 *   <li>Cube has exactly 8 chart-vertices when no edge is a seam.</li>
 *   <li>Forcing every interior edge to be a seam splits every shared corner
 *       across charts so {@code chartVertexCount == 3 * F}.</li>
 * </ol>
 */
public class ChartVertexMapTest {

    @Test
    void cubeNoSeamsCollapsesCornersToVertices() throws Exception {
        ArrayMesh mesh = makeCube();
        FaceRosyField field = new FaceRosyField(mesh);
        field.solve();

        // Forge a CombedField with no seams. CombedField.fromExternal lets us
        // hand-pick the matching/seam arrays so we control exactly what
        // ChartVertexMap sees.
        int Ei = field.interiorEdgeCount();
        int[] branch = new int[mesh.faceCount()];
        int[] matching = new int[Ei];
        boolean[] seam = new boolean[Ei];
        CombedField combed = CombedField.fromExternal(field, branch, matching, seam);

        Object map = buildMap(mesh, field, combed);
        int F = mesh.faceCount();
        int V = mesh.vertexCount();

        int chartCount = (int) get(map, "chartCount");
        int chartVertexCount = (int) get(map, "chartVertexCount");
        int[] faceChart = (int[]) get(map, "faceChart");
        int[] cornerCV = (int[]) get(map, "cornerChartVertex");

        assertEquals(1, chartCount, "no seams = single chart");
        assertEquals(V, chartVertexCount,
                "no seams + closed mesh = one chart-vertex per mesh vertex");
        for (int f = 0; f < F; f++) {
            assertEquals(0, faceChart[f], "every face in chart 0");
        }

        // Sibling-corner equality: corners on the same vertex must collapse
        // to the same chart-vertex id.
        java.util.HashMap<Integer, Integer> vertexFirstCV = new java.util.HashMap<>();
        for (int f = 0; f < F; f++) {
            for (int c = 0; c < 3; c++) {
                int mv = mesh.faceVertexAt(f, c);
                int cv = cornerCV[f * 3 + c];
                Integer prior = vertexFirstCV.putIfAbsent(mv, cv);
                if (prior != null) {
                    assertEquals(prior.intValue(), cv,
                            "vertex " + mv + " maps to " + prior
                                    + " from earlier corner but " + cv + " at face " + f);
                }
            }
        }
    }

    @Test
    void everyEdgeSeamSplitsAllCornersAcrossCharts() throws Exception {
        ArrayMesh mesh = makeCube();
        FaceRosyField field = new FaceRosyField(mesh);
        field.solve();

        // Mark every interior edge as a seam: each face becomes its own chart.
        int Ei = field.interiorEdgeCount();
        int F = mesh.faceCount();
        int[] branch = new int[F];
        int[] matching = new int[Ei];
        boolean[] seam = new boolean[Ei];
        for (int e = 0; e < Ei; e++) seam[e] = true;
        CombedField combed = CombedField.fromExternal(field, branch, matching, seam);

        Object map = buildMap(mesh, field, combed);

        int chartCount = (int) get(map, "chartCount");
        int chartVertexCount = (int) get(map, "chartVertexCount");

        // Every seam edge cuts charts; with all interior edges as seams we
        // get F isolated faces => F charts. Each face contributes 3 distinct
        // chart-vertices.
        assertEquals(F, chartCount, "all-seam case = F charts");
        assertEquals(3 * F, chartVertexCount,
                "all-seam = no corner sharing across faces");
    }

    @Test
    void chartVertexCountUpperBoundedByThreeF() throws Exception {
        ArrayMesh mesh = makeCube();
        FaceRosyField field = new FaceRosyField(mesh);
        field.solve();
        CombedField combed = CombedField.comb(field);

        Object map = buildMap(mesh, field, combed);
        int F = mesh.faceCount();
        int chartVertexCount = (int) get(map, "chartVertexCount");
        assertTrue(chartVertexCount <= 3 * F,
                "chartVertexCount " + chartVertexCount + " exceeds 3*F = " + (3 * F));
        assertTrue(chartVertexCount >= mesh.vertexCount() / 2,
                "chartVertexCount " + chartVertexCount
                        + " unexpectedly small for cube with vertex count "
                        + mesh.vertexCount());
    }

    private static Object buildMap(ArrayMesh mesh, FaceRosyField field, CombedField combed)
            throws Exception {
        Class<?> cls = Class.forName("ixdar.geometry.mesh.quadlayout.integergrid.ChartVertexMap");
        Method build = cls.getDeclaredMethod("build", ArrayMesh.class,
                FaceRosyField.class, CombedField.class);
        build.setAccessible(true);
        Object map = build.invoke(null, mesh, field, combed);
        assertNotNull(map);
        return map;
    }

    private static Object get(Object map, String fieldName) throws Exception {
        Field f = map.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.get(map);
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
}
