package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.CornerUvField;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.nodes.api.IntField;
import ixdar.geometry.mesh.quadlayout.ChartAtlas;
import ixdar.geometry.mesh.quadlayout.motorcycle.ChartWalker;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceAxis;

/**
 * Two hand-authored triangles sharing one edge, with a level line that leaves
 * the first face exactly at that edge. A trace standing on its own face exit
 * has no forward crossing left, which is the PATCH-109 crash.
 */
class ChartWalkerStalledExitTest {

    /** Level of the traced iso-line; the trace holds {@code u} at this value. */
    private static final double LEVEL_U = 0.0;

    /** {@code v} where the level line meets the shared edge of both faces. */
    private static final double SHARED_EDGE_V = 1.5;

    /** {@code v} where the level line meets the far edge of the first face. */
    private static final double FIRST_FACE_FAR_V = 0.5;

    /** {@code v} where the level line meets the far edge of the second face. */
    private static final double SECOND_FACE_FAR_V = 2.5;

    /** Parameter along the shared edge at the level crossing; both corners are unit offsets. */
    private static final double SHARED_EDGE_PARAMETER = 0.5;

    @Test
    void nextEdgeHitIsNullWhenTheDirectionLeavesTheFaceAtTheCurrentPoint() {
        ChartWalker walker = buildWalker();
        assertNull(walker.nextEdgeHit(stalledState()));
    }

    @Test
    void nextEdgeHitStillFindsTheForwardCrossingFromInsideTheFace() {
        ChartWalker walker = buildWalker();
        ChartWalker.State inside = new ChartWalker.State(0, LEVEL_U, 1.0, TraceAxis.V, 1);
        ChartWalker.EdgeHit hit = walker.nextEdgeHit(inside);
        assertNotNull(hit);
        assertEquals(0, hit.localEdgeIndex);
        assertEquals(-1, hit.cornerLocalIndex);
        assertEquals(SHARED_EDGE_V, hit.exitV);
        assertEquals(SHARED_EDGE_V - 1.0, hit.parametricDelta);
        assertEquals(SHARED_EDGE_PARAMETER, hit.edgeParameter);
    }

    @Test
    void nextEdgeHitRunsBackwardsFromInsideTheFaceWhenTheSignReverses() {
        ChartWalker walker = buildWalker();
        ChartWalker.State inside = new ChartWalker.State(0, LEVEL_U, 1.0, TraceAxis.V, -1);
        ChartWalker.EdgeHit hit = walker.nextEdgeHit(inside);
        assertNotNull(hit);
        assertEquals(2, hit.localEdgeIndex);
        assertEquals(FIRST_FACE_FAR_V, hit.exitV);
    }

    @Test
    void stalledExitHitNamesTheEdgeUnderTheStalledPoint() {
        ChartWalker walker = buildWalker();
        ChartWalker.EdgeHit stall = walker.stalledExitHit(stalledState());
        assertNotNull(stall);
        assertEquals(0, stall.localEdgeIndex);
        assertEquals(-1, stall.cornerLocalIndex);
        assertEquals(LEVEL_U, stall.exitU);
        assertEquals(SHARED_EDGE_V, stall.exitV);
        assertEquals(0.0, stall.parametricDelta);
        assertFalse(stall.boundary);
    }

    @Test
    void crossingTheStalledEdgeContinuesInTheNeighbourFace() {
        ChartWalker walker = buildWalker();
        ChartWalker.State stalled = stalledState();
        ChartWalker.EdgeHit stall = walker.stalledExitHit(stalled);
        ChartWalker.State carried = new ChartWalker.State(stalled);
        assertTrue(walker.crossEdge(stalled, stall, carried));
        assertEquals(1, carried.activeFace);
        assertEquals(LEVEL_U, carried.u);
        assertEquals(SHARED_EDGE_V, carried.v);
        assertEquals(TraceAxis.V, carried.axis);
        assertEquals(1, carried.sign);

        ChartWalker.EdgeHit forward = walker.nextEdgeHit(carried);
        assertNotNull(forward);
        assertEquals(SECOND_FACE_FAR_V, forward.exitV);
        assertEquals(SECOND_FACE_FAR_V - SHARED_EDGE_V, forward.parametricDelta);
    }

    /**
     * The state the crash reproduces from: on the shared edge at the first face's
     * own exit crossing, travelling in the direction that leaves it.
     *
     * @return the stalled walker state
     */
    private static ChartWalker.State stalledState() {
        return new ChartWalker.State(0, LEVEL_U, SHARED_EDGE_V, TraceAxis.V, 1);
    }

    /**
     * Two triangles in one chart, planar so the chart coordinates and the surface
     * positions agree. Face 0 spans {@code v} from {@link #FIRST_FACE_FAR_V} up to
     * the shared edge; face 1 carries the level line on from there.
     *
     * @return a walker over the two-triangle fixture with identity transitions
     */
    private static ChartWalker buildWalker() {
        float[] positions = {
            1f, 1f, 0f,
            -1f, 2f, 0f,
            -1f, 0f, 0f,
            1f, 3f, 0f,
        };
        int[] faceIndices = { 0, 1, 2, 1, 0, 3 };
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faceIndices);
        double[] cornerU = { 1.0, -1.0, -1.0, -1.0, 1.0, 1.0 };
        double[] cornerV = { 1.0, 2.0, 0.0, 2.0, 1.0, 3.0 };
        CornerUvField uv = new CornerUvField(cornerU, cornerV);
        ChartAtlas charts = new ChartAtlas(mesh.faceCount(), mesh.faceCount(), mesh.edgeCount(),
                true);
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            charts.chartOfFace[activeFace] = activeFace;
        }
        for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
            charts.chartA[activeEdge] = 0;
            charts.chartB[activeEdge] = 1;
            charts.quarterTurns[activeEdge] = 0;
        }
        return new ChartWalker(mesh, uv, charts, new IntField(new int[mesh.vertexCount()]));
    }
}
