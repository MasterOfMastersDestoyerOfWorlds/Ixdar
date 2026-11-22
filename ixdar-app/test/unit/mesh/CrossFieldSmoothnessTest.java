package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.fields.crossfield.CrossField;
import ixdar.geometry.mesh.data.fields.crossfield.NDirectionField;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceMetric;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.nodegraph.runtime.NodeGraphRuntime;

/**
 * The smoothest cross field reads its eigenvector in the realified matrices' interleaved layout:
 * on a sphere it has KCP13 Fig. 4's eight index-1/4 cones.
 */
class CrossFieldSmoothnessTest {

    private static final String SPHERE_SOURCE = "carrier = icosphere(radius=1.0, subdivisions=3)";

    private static final int SPHERE_CONES = 8;

    @Test
    void theSmoothestFieldOnASphereHasEightQuarterCones() throws Exception {
        NodeGraphRuntime runtime = NodeGraphRuntime.fromSource(SPHERE_SOURCE);
        GeometryBundle bundle = (GeometryBundle) runtime.executeGraphResult("carrier", "geometry");
        HalfEdgeMesh mesh = (HalfEdgeMesh) bundle.mesh();
        NDirectionField solve = new NDirectionField();
        solve.useCurvatureAlignment = false;
        CrossField field = solve.build(mesh, SurfaceMetric.of(mesh));
        int cones = 0;
        for (int vertex = 0; vertex < field.singularityIndex4.length(); vertex++) {
            int index = field.singularityIndex4.get(vertex);
            if (index != 0) {
                assertEquals(1, index, "cone index at vertex " + vertex);
                cones++;
            }
        }
        assertEquals(SPHERE_CONES, cones, "cones of the smoothest field on a sphere");
    }
}
