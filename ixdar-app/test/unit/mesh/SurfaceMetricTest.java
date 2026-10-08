package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.FlipGeodesics;
import ixdar.geometry.mesh.data.paths.GeodesicSeedPath;
import ixdar.geometry.mesh.data.paths.IntrinsicPathTracer;
import ixdar.geometry.mesh.data.paths.IntrinsicTriangulation;
import ixdar.geometry.mesh.data.paths.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.SurfaceMetric;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.primitives.IcosphereMeshNode;

/**
 * One surface metric shared by several geodesic users: interleaved users trace exactly what each
 * traces on a metric of its own, and the shared metric reads the same after every query.
 */
class SurfaceMetricTest {

    private static final int SPHERE_SUBDIVISIONS = 4;

    // Vertex pairs far enough apart on the icosphere that every geodesic flips edges.
    private static final int[][] FIRST_USER_PAIRS = { { 0, 40 }, { 3, 900 }, { 17, 2000 } };

    private static final int[][] SECOND_USER_PAIRS = { { 5, 1500 }, { 0, 40 }, { 700, 2500 } };

    @Test
    void interleavedEnginesOnOneMetricTraceWhatSeparateMetricsTrace() {
        MeshTopology sphere = icosphere();
        SurfaceMetric shared = SurfaceMetric.of(sphere);
        SurfaceGeodesics first = SurfaceGeodesics.over(shared);
        SurfaceGeodesics second = SurfaceGeodesics.over(shared);
        SurfaceGeodesics firstAlone = SurfaceGeodesics.over(SurfaceMetric.of(sphere));
        SurfaceGeodesics secondAlone = SurfaceGeodesics.over(SurfaceMetric.of(sphere));
        int crossings = 0;
        for (int query = 0; query < FIRST_USER_PAIRS.length; query++) {
            assertTrue(first.geodesic(FIRST_USER_PAIRS[query][0], FIRST_USER_PAIRS[query][1]));
            assertTrue(second.geodesic(SECOND_USER_PAIRS[query][0], SECOND_USER_PAIRS[query][1]));
            assertTrue(firstAlone.geodesic(FIRST_USER_PAIRS[query][0],
                    FIRST_USER_PAIRS[query][1]));
            assertTrue(secondAlone.geodesic(SECOND_USER_PAIRS[query][0],
                    SECOND_USER_PAIRS[query][1]));
            assertSamePath(firstAlone, first, "first user, query " + query);
            assertSamePath(secondAlone, second, "second user, query " + query);
            for (int point = 0; point < first.tracedPointCount; point++) {
                crossings += first.tracedEdgeId[point] >= 0 ? 1 : 0;
            }
        }
        assertTrue(crossings > 0, "no geodesic crossed an edge, so none flipped one");
    }

    @Test
    void flipsHeldByOneTriangulationNeverReachAnotherOnTheSameMetric() {
        MeshTopology sphere = icosphere();
        SurfaceMetric shared = SurfaceMetric.of(sphere);
        IntrinsicTriangulation first = IntrinsicTriangulation.over(shared);
        IntrinsicTriangulation second = IntrinsicTriangulation.over(shared);
        int[] firstSeed = GeodesicSeedPath.throughVertices(first, FIRST_USER_PAIRS[1], false);
        int[] secondSeed = GeodesicSeedPath.throughVertices(second, SECOND_USER_PAIRS[2], false);
        // Both runs keep their flips while the other one runs.
        FlipGeodesics firstFlipper = new FlipGeodesics();
        int[] firstPath = firstFlipper.shorten(first, firstSeed, false,
                FlipGeodesics.UNBOUNDED_ITERATIONS);
        FlipGeodesics secondFlipper = new FlipGeodesics();
        int[] secondPath = secondFlipper.shorten(second, secondSeed, false,
                FlipGeodesics.UNBOUNDED_ITERATIONS);
        assertTrue(firstFlipper.flipCount > 0 && secondFlipper.flipCount > 0,
                "both runs must flip for the test to mean anything");
        IntrinsicPathTracer tracer = IntrinsicPathTracer.over(shared);
        double[] firstTrace = tracer.trace(first, firstPath, false).positions;
        double[] secondTrace = tracer.trace(second, secondPath, false).positions;

        SurfaceMetric firstOwn = SurfaceMetric.of(sphere);
        IntrinsicTriangulation firstAlone = IntrinsicTriangulation.over(firstOwn);
        int[] firstAlonePath = new FlipGeodesics().shorten(firstAlone,
                GeodesicSeedPath.throughVertices(firstAlone, FIRST_USER_PAIRS[1], false), false,
                FlipGeodesics.UNBOUNDED_ITERATIONS);
        SurfaceMetric secondOwn = SurfaceMetric.of(sphere);
        IntrinsicTriangulation secondAlone = IntrinsicTriangulation.over(secondOwn);
        int[] secondAlonePath = new FlipGeodesics().shorten(secondAlone,
                GeodesicSeedPath.throughVertices(secondAlone, SECOND_USER_PAIRS[2], false), false,
                FlipGeodesics.UNBOUNDED_ITERATIONS);
        assertArrayEquals(IntrinsicPathTracer.over(firstOwn).trace(firstAlone, firstAlonePath,
                false).positions, firstTrace, 0.0, "the first run saw the second run's flips");
        assertArrayEquals(IntrinsicPathTracer.over(secondOwn).trace(secondAlone, secondAlonePath,
                false).positions, secondTrace, 0.0, "the second run saw the first run's flips");
    }

    @Test
    void theSharedMetricReadsTheSameAfterEveryQuery() {
        SurfaceMetric shared = SurfaceMetric.of(icosphere());
        int[] halfEdgeTail = shared.halfEdgeTail.clone();
        int[] halfEdgeNext = shared.halfEdgeNext.clone();
        int[] halfEdgeFace = shared.halfEdgeFace.clone();
        int[] faceHalfEdge = shared.faceHalfEdge.clone();
        int[] vertexReferenceHalfEdge = shared.vertexReferenceHalfEdge.clone();
        double[] edgeLength = shared.edgeLength.clone();
        double[] signpostAngle = shared.signpostAngle.clone();
        double[] vertexAngleSum = shared.vertexAngleSum.clone();
        SurfaceGeodesics first = SurfaceGeodesics.over(shared);
        SurfaceGeodesics second = SurfaceGeodesics.over(shared);
        for (int query = 0; query < FIRST_USER_PAIRS.length; query++) {
            assertTrue(first.geodesic(FIRST_USER_PAIRS[query][0], FIRST_USER_PAIRS[query][1]));
            assertTrue(second.geodesic(SECOND_USER_PAIRS[query][0], SECOND_USER_PAIRS[query][1]));
        }
        IntrinsicTriangulation held = IntrinsicTriangulation.over(shared);
        new FlipGeodesics().shorten(held,
                GeodesicSeedPath.throughVertices(held, FIRST_USER_PAIRS[2], false), false,
                FlipGeodesics.UNBOUNDED_ITERATIONS);

        assertArrayEquals(halfEdgeTail, shared.halfEdgeTail, "half-edge tails changed");
        assertArrayEquals(halfEdgeNext, shared.halfEdgeNext, "half-edge links changed");
        assertArrayEquals(halfEdgeFace, shared.halfEdgeFace, "half-edge faces changed");
        assertArrayEquals(faceHalfEdge, shared.faceHalfEdge, "face half-edges changed");
        assertArrayEquals(vertexReferenceHalfEdge, shared.vertexReferenceHalfEdge,
                "reference half-edges changed");
        assertArrayEquals(edgeLength, shared.edgeLength, 0.0, "edge lengths changed");
        assertArrayEquals(signpostAngle, shared.signpostAngle, 0.0, "signpost angles changed");
        assertArrayEquals(vertexAngleSum, shared.vertexAngleSum, 0.0, "angle sums changed");
    }

    private static void assertSamePath(SurfaceGeodesics expected, SurfaceGeodesics actual,
            String what) {
        assertEquals(expected.tracedPointCount, actual.tracedPointCount, what + ": point count");
        for (int point = 0; point < expected.tracedPointCount; point++) {
            assertEquals(expected.tracedVertexId[point], actual.tracedVertexId[point],
                    what + ": vertex at point " + point);
            assertEquals(expected.tracedEdgeId[point], actual.tracedEdgeId[point],
                    what + ": edge at point " + point);
            assertEquals(expected.tracedFraction[point], actual.tracedFraction[point], 0.0,
                    what + ": fraction at point " + point);
        }
        assertEquals(expected.pathLength, actual.pathLength, 0.0, what + ": length");
    }

    private static MeshTopology icosphere() {
        IcosphereMeshNode node = new IcosphereMeshNode();
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput(IcosphereMeshNode.RADIUS.name, 1f);
        ctx.setInput(IcosphereMeshNode.SUBDIVISIONS.name, SPHERE_SUBDIVISIONS);
        node.evaluate(ctx);
        return ctx.getOutput(IcosphereMeshNode.MESH.name, GeometryBundle.class).mesh();
    }
}
