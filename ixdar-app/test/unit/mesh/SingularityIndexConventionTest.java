package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.fields.crossfield.CrossField;
import ixdar.geometry.mesh.data.fields.crossfield.NDirectionField;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceMetric;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * The per-vertex singularity index (BZK09 §3, Ray08) adds the angle defect to the transport angles
 * walked round the vertex's one-ring. That walk is the frame's holonomy, the same for every field,
 * so with the right orientation {@code defect + Σκ} is a whole number of turns at every interior
 * vertex; with the wrong one it is about twice the defect wherever the surface is curved.
 */
class SingularityIndexConventionTest {

    /** A curved mesh with sharp corners and creases, from the paper's figure 8. */
    private static final String BLOCK = "test/resources/quadlayout/figure_8/block_in_tri.off";

    private static final double TURN = 2.0 * Math.PI;

    private static final double HOLONOMY_TOLERANCE = 1.0e-3;

    /** Defects below this are flat enough that either orientation passes; they say nothing. */
    private static final double CURVED_DEFECT = 0.05;

    @Test
    void theKappaWalkCancelsTheDefectAtEveryCurvedVertex() throws Exception {
        HalfEdgeMesh mesh = (HalfEdgeMesh) MeshLoader.load(BLOCK);
        mesh.computeNormals();
        SurfaceMetric metric = SurfaceMetric.of(mesh);
        CrossField field = new NDirectionField().build(mesh, metric);

        int curved = 0;
        int failing = 0;
        double worst = 0.0;
        StringBuilder examples = new StringBuilder();
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            if (mesh.isBoundaryVertex(vertexId)) {
                continue;
            }
            double defect = TURN - metric.vertexAngleSum[activeVertex];
            if (Math.abs(defect) < CURVED_DEFECT) {
                continue;
            }
            curved++;
            double signedKappaSum = 0.0;
            for (int index = 0; index < mesh.vertexOutgoingHalfEdgeCount(vertexId); index++) {
                int halfEdge = mesh.vertexOutgoingHalfEdgeAt(vertexId, index);
                int edgeId = mesh.halfEdgeEdge(halfEdge);
                int sign = halfEdge == mesh.edgeHalfEdge(edgeId) ? 1 : -1;
                signedKappaSum += sign * field.kappa[field.edgeIdToActive[edgeId]];
            }
            double holonomy = defect + signedKappaSum;
            double offTurn = Math.abs(holonomy - TURN * Math.rint(holonomy / TURN));
            worst = Math.max(worst, offTurn);
            if (offTurn > HOLONOMY_TOLERANCE) {
                failing++;
                if (failing <= 3) {
                    examples.append(String.format(" v%d defect=%.4f defect+Σκ=%.4f;", vertexId, defect,
                            holonomy));
                }
            }
        }
        assertTrue(curved > 0, "the block has curved interior vertices");
        assertTrue(failing == 0, failing + " of " + curved + " curved vertices: defect + Σκ is not a"
                + " whole number of turns (worst off by " + worst + " rad):" + examples);
    }
}
