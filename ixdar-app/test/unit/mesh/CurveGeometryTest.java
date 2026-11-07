package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.CurveGeometry;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.nodegraph.api.MapNodeContext;
import ixdar.nodegraph.field.Vector3Value;
import ixdar.nodegraph.nodes.curve.BirailLoftMeshNode;
import ixdar.nodegraph.nodes.curve.CircleCurveNode;
import ixdar.nodegraph.nodes.curve.CurvePrimitiveBezierNode;
import ixdar.nodegraph.nodes.curve.JoinCurvesNode;
import ixdar.nodegraph.nodes.curve.ResampleCurveNode;
import ixdar.nodegraph.nodes.transform.TransformGeometryNode;

/**
 * {@code CurveGeometry} on hand-built curves: per-curve access, the closed flag the primitives set
 * and the lofts read, resampling, splitting and simplifying per curve, and attributes carried
 * through join, resample and transform.
 */
class CurveGeometryTest {

    private static final float EPSILON = 1e-6f;

    private static final String WIDTH = "width";

    private static final String LIT_SIDE = "lit_side";

    private static final int CIRCLE_RESOLUTION = 12;

    private static final int SQUARE_SAMPLES = 8;

    private static final int LOFT_U = 6;

    private static final int LOFT_V = 3;

    private static final float SHIFT = 2f;

    private static final float[] UNIT_SQUARE_IN_XZ = {
        0f, 0f, 0f,
        1f, 0f, 0f,
        1f, 0f, 1f,
        0f, 0f, 1f,
    };

    @Test
    void perCurveAccessReadsEachCurvesOwnPoints() {
        float[] positions = new float[UNIT_SQUARE_IN_XZ.length + 6];
        System.arraycopy(UNIT_SQUARE_IN_XZ, 0, positions, 0, UNIT_SQUARE_IN_XZ.length);
        System.arraycopy(new float[] { 5f, 0f, 0f, 6f, 0f, 0f }, 0, positions, UNIT_SQUARE_IN_XZ.length, 6);
        CurveGeometry curves = new CurveGeometry(positions, new int[] { 0, 4, 6 }, new boolean[] { true, false },
                Map.of(), Map.of());

        assertEquals(2, curves.curveCount());
        assertEquals(0, curves.firstPoint(0));
        assertEquals(4, curves.pointCount(0));
        assertEquals(4, curves.firstPoint(1));
        assertEquals(2, curves.pointCount(1));
        assertArrayEquals(new float[] { 5f, 0f, 0f, 6f, 0f, 0f }, curves.curvePositions(1));
        assertTrue(curves.closed[0], "the square is a loop");
        assertFalse(curves.closed[1], "the segment is not");
    }

    @Test
    void primitivesSetTheClosedFlag() {
        CurveGeometry circle = curveOf(new MapNodeContext(new CircleCurveNode())
                .with(CircleCurveNode.RESOLUTION, CIRCLE_RESOLUTION)
                .eval()
                .output(CircleCurveNode.GEOMETRY, GeometryBundle.class));
        CurveGeometry bezier = curveOf(new MapNodeContext(new CurvePrimitiveBezierNode())
                .eval()
                .output(CurvePrimitiveBezierNode.GEOMETRY, GeometryBundle.class));

        assertTrue(circle.closed[0], "circle_curve is closed");
        assertEquals(CIRCLE_RESOLUTION, circle.pointCount(0), "its first point is not repeated");
        assertFalse(bezier.closed[0], "curve_bezier is open");
    }

    @Test
    void resampledWalksTheClosingSegmentAndInterpolatesAttributes() {
        CurveGeometry square = withWidth(CurveGeometry.singlePolyline(UNIT_SQUARE_IN_XZ, true));

        CurveGeometry resampled = square.resampled(SQUARE_SAMPLES);

        assertTrue(resampled.closed[0]);
        assertEquals(SQUARE_SAMPLES, resampled.pointCount(0));
        // Perimeter 4 in eight steps: the last sample is halfway along the closing edge.
        assertArrayEquals(new float[] { 0f, 0f, 0.5f }, sample(resampled, SQUARE_SAMPLES - 1), EPSILON);
        assertEquals(1.5f, resampled.pointAttributes.get(WIDTH)[SQUARE_SAMPLES - 1], EPSILON,
                "halfway between the last corner's width 3 and the first's 0");
        assertEquals(0.5f, resampled.pointAttributes.get(WIDTH)[1], EPSILON);
    }

    @Test
    void subdividedKeepsEveryCornerAndDoesNotRepeatAClosedStart() {
        CurveGeometry square = withWidth(CurveGeometry.singlePolyline(UNIT_SQUARE_IN_XZ, true));

        CurveGeometry split = square.subdivided(0.5f);

        assertTrue(split.closed[0]);
        assertEquals(SQUARE_SAMPLES, split.pointCount(0), "four edges in two pieces each");
        for (int corner = 0; corner < 4; corner++) {
            assertArrayEquals(new float[] { UNIT_SQUARE_IN_XZ[corner * 3], UNIT_SQUARE_IN_XZ[corner * 3 + 1], UNIT_SQUARE_IN_XZ[corner * 3 + 2] },
                    sample(split, corner * 2), EPSILON, "corner " + corner + " kept");
        }
        assertEquals(2.5f, split.pointAttributes.get(WIDTH)[5], EPSILON);
    }

    @Test
    void simplifiedDropsCollinearPointsAndKeepsSurvivorsAttributes() {
        float[] line = { 0f, 0f, 0f, 1f, 0f, 0f, 2f, 0f, 0f, 2f, 1f, 0f };
        CurveGeometry curve = new CurveGeometry(line, new int[] { 0, 4 }, new boolean[1],
                Map.of(WIDTH, new float[] { 0f, 1f, 2f, 3f }), Map.of());

        CurveGeometry simplified = curve.simplified(EPSILON);

        assertEquals(3, simplified.pointCount(0), "the straight run's middle point is dropped");
        assertArrayEquals(new float[] { 0f, 2f, 3f }, simplified.pointAttributes.get(WIDTH));
    }

    @Test
    void pictureOutlineRoundTripsThroughTheDrawnLoop() {
        float[] drawnSquare = { 0f, 0f, 1f, 0f, 2f, 0f, 2f, 2f, 0f, 2f, 0f, 0f };

        CurveGeometry outline = CurveGeometry.planarLoop(drawnSquare);
        float[] simplified = outline.simplified(EPSILON).drawnPlanar(0);

        assertTrue(outline.closed[0]);
        assertEquals(5, outline.pointCount(0), "the repeated first point is dropped");
        assertEquals(0f, outline.positions[2], "the outline lies in z = 0");
        assertArrayEquals(new float[] { 0f, 0f, 2f, 0f, 2f, 2f, 0f, 2f, 0f, 0f }, simplified,
                "the collinear point goes and the loop is drawn closed again");
    }

    @Test
    void joinCarriesAttributesFromBothSides() {
        CurveGeometry left = new CurveGeometry(new float[] { 0f, 0f, 0f, 1f, 0f, 0f }, new int[] { 0, 2 },
                new boolean[1], Map.of(WIDTH, new float[] { 1f, 2f }), Map.of(LIT_SIDE, new float[] { -1f }));
        CurveGeometry right = new CurveGeometry(new float[] { 1f, 0f, 0f, 2f, 0f, 0f }, new int[] { 0, 2 },
                new boolean[1], Map.of(WIDTH, new float[] { 9f, 4f }), Map.of());

        CurveGeometry joined = curveOf(new MapNodeContext(new JoinCurvesNode())
                .with(JoinCurvesNode.CURVE_A, bundle(left))
                .with(JoinCurvesNode.CURVE_B, bundle(right))
                .eval()
                .output(JoinCurvesNode.GEOMETRY, GeometryBundle.class));

        assertEquals(3, joined.pointCount(), "the shared point is kept once");
        assertArrayEquals(new float[] { 1f, 2f, 4f }, joined.pointAttributes.get(WIDTH));
        assertArrayEquals(new float[] { -1f }, joined.curveAttributes.get(LIT_SIDE));
        assertFalse(joined.closed[0]);
    }

    @Test
    void resampleNodeKeepsClosedAndAttributes() {
        CurveGeometry square = withWidth(CurveGeometry.singlePolyline(UNIT_SQUARE_IN_XZ, true));

        CurveGeometry resampled = curveOf(new MapNodeContext(new ResampleCurveNode())
                .with(ResampleCurveNode.CURVE, bundle(square))
                .with(ResampleCurveNode.LENGTH, 0.5f)
                .eval()
                .output(ResampleCurveNode.GEOMETRY_OUT, GeometryBundle.class));

        assertTrue(resampled.closed[0]);
        assertEquals(SQUARE_SAMPLES, resampled.pointAttributes.get(WIDTH).length);
        assertArrayEquals(new float[] { 2f }, resampled.curveAttributes.get(LIT_SIDE));
    }

    @Test
    void transformMovesPointsAndKeepsClosedAndAttributes() {
        CurveGeometry square = withWidth(CurveGeometry.singlePolyline(UNIT_SQUARE_IN_XZ, true));

        CurveGeometry moved = curveOf(new MapNodeContext(new TransformGeometryNode())
                .with(TransformGeometryNode.GEOMETRY, bundle(square))
                .with(TransformGeometryNode.TRANSLATION, new Vector3Value(0f, SHIFT, 0f))
                .eval()
                .output(TransformGeometryNode.GEOMETRY_OUT, GeometryBundle.class));

        assertEquals(SHIFT, moved.positions[1], EPSILON);
        assertTrue(moved.closed[0]);
        assertArrayEquals(square.pointAttributes.get(WIDTH), moved.pointAttributes.get(WIDTH));
        assertArrayEquals(new float[] { 2f }, moved.curveAttributes.get(LIT_SIDE));
    }

    @Test
    void birailLoftWrapsClosedRailsAndRefusesMixedOnes() {
        CurveGeometry lower = CurveGeometry.singlePolyline(UNIT_SQUARE_IN_XZ, true);
        float[] raised = UNIT_SQUARE_IN_XZ.clone();
        for (int point = 0; point < 4; point++) {
            raised[point * 3 + 1] = 1f;
        }
        CurveGeometry upper = CurveGeometry.singlePolyline(raised, true);
        CurveGeometry openUpper = CurveGeometry.singlePolyline(raised, false);

        assertEquals(LOFT_U * (LOFT_V - 1), loft(lower, upper).mesh().faceCount(), "a closed band wraps around");
        assertEquals((LOFT_U - 1) * (LOFT_V - 1),
                loft(CurveGeometry.singlePolyline(UNIT_SQUARE_IN_XZ, false), openUpper).mesh().faceCount(),
                "an open strip does not");
        assertEquals(0, loft(lower, openUpper).mesh().faceCount(), "a closed rail and an open one make nothing");
    }

    /**
     * The curve with a point attribute counting its points and a curve attribute of 2.
     *
     * @param curve a single-curve geometry
     * @return the same curve carrying {@link #WIDTH} and {@link #LIT_SIDE}
     */
    private static CurveGeometry withWidth(CurveGeometry curve) {
        float[] width = new float[curve.pointCount()];
        for (int point = 0; point < width.length; point++) {
            width[point] = point;
        }
        return new CurveGeometry(curve.positions, curve.curveOffsets, curve.closed, Map.of(WIDTH, width),
                Map.of(LIT_SIDE, new float[] { 2f }));
    }

    private static float[] sample(CurveGeometry curve, int point) {
        return new float[] { curve.positions[point * 3], curve.positions[point * 3 + 1],
            curve.positions[point * 3 + 2] };
    }

    private static GeometryBundle bundle(CurveGeometry curve) {
        return GeometryBundle.empty().withSlot(CurveGeometry.SLOT, curve);
    }

    private static CurveGeometry curveOf(GeometryBundle bundle) {
        return (CurveGeometry) bundle.slots().get(CurveGeometry.SLOT);
    }

    private static GeometryBundle loft(CurveGeometry railA, CurveGeometry railB) {
        return new MapNodeContext(new BirailLoftMeshNode())
                .with(BirailLoftMeshNode.RAIL_A, bundle(railA))
                .with(BirailLoftMeshNode.RAIL_B, bundle(railB))
                .with(BirailLoftMeshNode.U_SEGMENTS, LOFT_U)
                .with(BirailLoftMeshNode.V_SEGMENTS, LOFT_V)
                .eval()
                .output(BirailLoftMeshNode.GEOMETRY, GeometryBundle.class);
    }
}
