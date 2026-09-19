package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.CornerUvField;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.embedding.ChartBarycentric;

/**
 * Two hand-authored triangles sharing the vertex a separatrix crossing lands on.
 * The arrangement meets that one point once per face of the fan, and each visit
 * used to mint its own T-mesh node, which is the PATCH-114 collapse.
 */
class CoincidentCrossingNodeTest {

    /** Corners of a triangle. */
    private static final int CORNERS = 3;

    /** Chart u of the shared vertex; hand's failing crossing sat here. */
    private static final double SHARED_U = -43.99226664155535;

    /** Chart v of the shared vertex; hand's failing crossing sat here. */
    private static final double SHARED_V = 10.475873259011564;

    /**
     * The barycentric hand's node 41 actually carried: its chart position is the
     * shared vertex, but inverting the chart puts it a rounding step outside the
     * triangle, so every exact side test rejects the only face that holds it.
     */
    private static final double[] HAND_NODE_41_BARYCENTRIC = {
        -1.5543122344752192E-15, 1.0917910056726295E-15, 1.0000000000000004,
    };

    @Test
    void cornerHoldingNamesTheCornerEvenWhenRoundingPutsThePointOutsideTheTriangle() {
        assertTrue(HAND_NODE_41_BARYCENTRIC[0] < 0.0);
        assertEquals(2, ChartBarycentric.cornerHolding(HAND_NODE_41_BARYCENTRIC));
    }

    @Test
    void clampPullsARoundingStepOutsideTheFaceBackOntoIt() {
        double[] clamped = ChartBarycentric.clampOntoTriangle(
                HAND_NODE_41_BARYCENTRIC.clone());
        assertEquals(0.0, clamped[0]);
        assertTrue(clamped[1] >= 0.0);
        assertTrue(clamped[2] > 0.0);

        double[] onEdge = ChartBarycentric.clampOntoTriangle(
                new double[] { 0.3835602516777039, -1.9329600524575722E-16, 0.6164397483222963 });
        assertEquals(0.0, onEdge[1]);
        assertEquals(1.0, onEdge[0] + onEdge[1] + onEdge[2], 1.0e-15);
    }

    @Test
    void clampLeavesAPointThatIsGenuinelyOutsideItsFaceOutside() {
        double[] outside = ChartBarycentric.clampOntoTriangle(
                new double[] { 1.25, -0.25, 0.0 });
        assertTrue(outside[1] < 0.0);
    }

    @Test
    void cornerHoldingIsMinusOneForAPointClearOfEveryCorner() {
        double third = 1.0 / CORNERS;
        assertEquals(-1, ChartBarycentric.cornerHolding(new double[] { third, third, third }));
        assertEquals(-1, ChartBarycentric.cornerHolding(new double[] { 0.5, 0.5, 0.0 }));
    }

    @Test
    void bothFacesOfTheFanResolveOneCrossingToTheSameMeshVertex() {
        HalfEdgeMesh mesh = buildFan();
        CornerUvField uv = buildChart();
        double pointU = Math.nextDown(Math.nextDown(SHARED_U));
        double pointV = Math.nextUp(SHARED_V);

        int firstVertexId = cornerVertexUnder(mesh, uv, 0, pointU, pointV);
        int secondVertexId = cornerVertexUnder(mesh, uv, 1, pointU, pointV);

        assertEquals(firstVertexId, secondVertexId);
        assertEquals(0, firstVertexId);
    }

    @Test
    void edgeHoldingNamesTheEdgeAPointOnItRunsFrom() {
        double[] onEdge = { 0.3835602516777039, 0.0, 0.6164397483222963 };
        assertEquals(2, ChartBarycentric.edgeHolding(onEdge));
        assertEquals(0.3835602516777039, ChartBarycentric.parameterAlongEdge(onEdge, 2), 1.0e-15);
        assertEquals(-1, ChartBarycentric.edgeHolding(HAND_NODE_41_BARYCENTRIC));
        double third = 1.0 / CORNERS;
        assertEquals(-1, ChartBarycentric.edgeHolding(new double[] { third, third, third }));
    }

    @Test
    void bothFacesOfAnEdgeMeasureOneCrossingOnItAtTheSameParameter() {
        HalfEdgeMesh mesh = buildFan();
        CornerUvField uv = buildChart();
        double along = 0.3;
        double pointU = SHARED_U + along * (-43.0 - SHARED_U);
        double pointV = SHARED_V + along * (11.0 - SHARED_V);

        assertEquals(edgePointUnder(mesh, uv, 0, pointU, pointV),
                edgePointUnder(mesh, uv, 1, pointU, pointV), 1.0e-12);
        assertEquals(along, edgePointUnder(mesh, uv, 0, pointU, pointV), 1.0e-12);
    }

    @Test
    void crossingsAFewUlpsApartAreOnePointAndDistinctCrossingsAreNot() {
        HalfEdgeMesh mesh = buildFan();
        CornerUvField uv = buildChart();
        double[] cornerUv = new double[2 * CORNERS];
        uv.faceCornerUv(mesh.faceIdAt(0), cornerUv);

        double[] centre = ChartBarycentric.ofChartPoint(cornerUv,
                (cornerUv[0] + cornerUv[2] + cornerUv[4]) / CORNERS,
                (cornerUv[1] + cornerUv[3] + cornerUv[5]) / CORNERS);
        double[] nudgedCentre = ChartBarycentric.ofChartPoint(cornerUv,
                Math.nextUp((cornerUv[0] + cornerUv[2] + cornerUv[4]) / CORNERS),
                (cornerUv[1] + cornerUv[3] + cornerUv[5]) / CORNERS);
        double[] elsewhere = ChartBarycentric.ofChartPoint(cornerUv,
                (cornerUv[0] + cornerUv[2]) / 2.0, (cornerUv[1] + cornerUv[3]) / 2.0);

        assertNotNull(centre);
        assertTrue(ChartBarycentric.sameChartPoint(centre, nudgedCentre));
        assertFalse(ChartBarycentric.sameChartPoint(centre, elsewhere));
    }

    @Test
    void ofChartPointIsNullOnADegenerateChart() {
        double[] collapsed = { 0.0, 0.0, 1.0, 1.0, 2.0, 2.0 };
        assertEquals(null, ChartBarycentric.ofChartPoint(collapsed, 0.5, 0.5));
    }

    /**
     * The mesh vertex a chart point sits on, read through one face of the fan.
     *
     * @param mesh       the fan's mesh
     * @param uv         the fan's chart
     * @param activeFace face of the fan to read the point in
     * @param pointU     chart u of the point
     * @param pointV     chart v of the point
     * @return the mesh vertex id under the point
     */
    private static int cornerVertexUnder(HalfEdgeMesh mesh, CornerUvField uv, int activeFace,
            double pointU, double pointV) {
        int faceId = mesh.faceIdAt(activeFace);
        double[] cornerUv = new double[2 * CORNERS];
        uv.faceCornerUv(faceId, cornerUv);
        double[] barycentric = ChartBarycentric.ofChartPoint(cornerUv, pointU, pointV);
        assertNotNull(barycentric);
        int corner = ChartBarycentric.cornerHolding(barycentric);
        assertTrue(corner >= 0);
        return mesh.faceVertexAt(faceId, corner);
    }

    /**
     * How far along the shared edge a chart point on it sits, measured from the
     * lower-numbered endpoint, read through one face of the pair.
     *
     * @param mesh       the fan's mesh
     * @param uv         the fan's chart
     * @param activeFace face of the pair to read the point in
     * @param pointU     chart u of the point
     * @param pointV     chart v of the point
     * @return the parameter along the edge, from its lower-numbered endpoint
     */
    private static double edgePointUnder(HalfEdgeMesh mesh, CornerUvField uv, int activeFace,
            double pointU, double pointV) {
        int faceId = mesh.faceIdAt(activeFace);
        double[] cornerUv = new double[2 * CORNERS];
        uv.faceCornerUv(faceId, cornerUv);
        double[] barycentric = ChartBarycentric.ofChartPoint(cornerUv, pointU, pointV);
        assertNotNull(barycentric);
        int localEdge = ChartBarycentric.edgeHolding(barycentric);
        assertTrue(localEdge >= 0);
        double alongEdge = ChartBarycentric.parameterAlongEdge(barycentric, localEdge);
        int fromVertexId = mesh.faceVertexAt(faceId, localEdge);
        int toVertexId = mesh.faceVertexAt(faceId, (localEdge + 1) % CORNERS);
        return fromVertexId < toVertexId ? alongEdge : 1.0 - alongEdge;
    }

    /**
     * Two triangles sharing vertex 0 and the edge to vertex 2, laid flat so the
     * chart and the surface agree. Vertex 0 carries the crossing both faces see.
     *
     * @return the two-triangle fan
     */
    private static HalfEdgeMesh buildFan() {
        float[] positions = {
            (float) SHARED_U, (float) SHARED_V, 0f,
            -43f, 10f, 0f,
            -43f, 11f, 0f,
            -45f, 10.2f, 0f,
        };
        int[] faceIndices = { 0, 1, 2, 0, 2, 3 };
        return HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faceIndices);
    }

    /**
     * The fan's chart, at the magnitudes hand's failing crossing carried: the
     * rounding of the inversion is what the collapse turns on, so the coordinates
     * have to be the size of the real ones rather than unit-sized.
     *
     * @return the per-corner chart of the fan
     */
    private static CornerUvField buildChart() {
        double[] cornerU = { SHARED_U, -43.0, -43.0, SHARED_U, -43.0, -45.0 };
        double[] cornerV = { SHARED_V, 10.0, 11.0, SHARED_V, 11.0, 10.2 };
        return new CornerUvField(cornerU, cornerV);
    }
}
