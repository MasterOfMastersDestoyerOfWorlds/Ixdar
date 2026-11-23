package unit.mesh.ringbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceMetric;
import ixdar.geometry.mesh.data.paths.query.SurfaceCreases;
import ixdar.geometry.mesh.data.rings.RegionClusterFit;
import ixdar.nodegraph.nodes.data.SurfaceCreasesNode;
import ixdar.nodegraph.nodes.data.SurfaceMetricNode;
import ixdar.nodegraph.runtime.NodeGraphRuntime;

/**
 * The region clustering fit: its topology rule refuses moves that would merge rings, open a hole,
 * pinch or empty a region; on the bench, of two rings round a torus with one groove one takes the
 * groove and the other stands opposite.
 */
class RegionClusterFitTest {

    static final RingFitBench.RingFit CLUSTERING_FIT_FROM_BENCH_FIXTURE_NODES = drawn -> {
        GeometryBundle bundle = (GeometryBundle) drawn.lastOutput(NodeGraphRuntime.GEOMETRY);
        return fit(drawn).run().fitted(bundle,
                (SurfaceMetric) drawn.getNodeOutput("surface", SurfaceMetricNode.METRIC.name));
    };

    private static final String TWO_RINGS = "one_groove_two_rings";

    private static final int FIRST_RING = 0;

    // CRAW-106's fit passes 3 of the bench's cases; the clustering passed 9 when it was written.
    private static final int MINIMUM_PASSING = 9;

    @Test
    void ofTwoRingsOnATorusWithOneGrooveOneTakesItAndTheOtherStandsOpposite() {
        RingFitBench.Case twoRings = RingFitBench.CASES_IN_RING_ANCHOR_FIT_TEST_ORDER.stream()
                .filter(benchCase -> benchCase.fixture().equals(TWO_RINGS)).findFirst().orElseThrow();
        RingFitBench.Result result = RingFitBench.score(twoRings, CLUSTERING_FIT_FROM_BENCH_FIXTURE_NODES);
        assertTrue(result.passes(), result.toString());
    }

    @Test
    void aRingStepsTowardTheNextUntilTheNewLoopWouldTouchIt() {
        RegionClusterFit fit = fit(drawn(TWO_RINGS));
        // The side whose strip lies in the smaller region, between the two rings.
        int toward = towardSmallerRegion(fit, FIRST_RING);
        int steps = 0;
        int[] loop;
        do {
            int[] strip = fit.strip(FIRST_RING, toward);
            assertNotNull(strip, "the faces round the loop fall into two sides");
            loop = fit.proposedLoop(FIRST_RING, strip);
            if (loop != null) {
                int growing = fit.regionOfFace[fit.strip(FIRST_RING, 1 - toward)[0]];
                for (int face : strip) {
                    fit.regionOfFace[face] = growing;
                }
                fit.loops[FIRST_RING] = loop;
                steps++;
                toward = towardSmallerRegion(fit, FIRST_RING);
            }
        } while (loop != null && steps < fit.mesh.faceCount());
        assertTrue(steps > 0, "the ring moved before it was refused");
        assertTrue(fit.lastRefusal.contains("touches ring_01"), fit.lastRefusal);
        int between = fit.regionOfFace[fit.strip(FIRST_RING, toward)[0]];
        assertTrue(Arrays.stream(fit.regionOfFace).anyMatch(region -> region == between),
                "the region between the rings keeps its faces");
    }

    @Test
    void aStripThatWouldOpenAHoleOrPinchTheRegionIsRefused() {
        RegionClusterFit fit = fit(drawn(TWO_RINGS));
        int[] strip = fit.strip(FIRST_RING, 0);
        int[] loop = fit.proposedLoop(FIRST_RING, strip);
        assertNotNull(loop, fit.lastRefusal);
        // A face of the same region far from the strip: its edges would close a second cycle.
        int far = farFace(fit, strip, fit.regionOfFace[strip[0]]);
        assertNull(fit.proposedLoop(FIRST_RING, append(strip, far)));
        assertTrue(fit.lastRefusal.contains("several cycles"), fit.lastRefusal);
        // A face touching the new loop at one vertex only: that vertex would join four loop edges.
        int pinch = pinchingFace(fit, strip, loop);
        assertNull(fit.proposedLoop(FIRST_RING, append(strip, pinch)));
        assertTrue(fit.lastRefusal.contains("not one simple cycle"), fit.lastRefusal);
    }

    @Test
    void aRingRoundTheHornStopsBeforeItEmptiesTheHorn() {
        RegionClusterFit fit = fit(drawn("horn_ring_on_horn"));
        int horn = fit.regionOfFace[fit.strip(FIRST_RING, 0)[0]];
        int hornSide = 0;
        long faces0 = Arrays.stream(fit.regionOfFace).filter(region -> region == horn).count();
        long faces1 = Arrays.stream(fit.regionOfFace)
                .filter(region -> region == fit.regionOfFace[fit.strip(FIRST_RING, 1)[0]]).count();
        hornSide = faces0 <= faces1 ? 0 : 1;
        int steps = 0;
        int[] loop;
        do {
            int[] strip = fit.strip(FIRST_RING, hornSide);
            loop = strip == null ? null : fit.proposedLoop(FIRST_RING, strip);
            if (loop != null) {
                int growing = fit.regionOfFace[fit.strip(FIRST_RING, 1 - hornSide)[0]];
                int eaten = fit.regionOfFace[strip[0]];
                for (int face : strip) {
                    fit.regionOfFace[face] = growing;
                }
                fit.loops[FIRST_RING] = loop;
                steps++;
                hornSide = fit.regionOfFace[fit.strip(FIRST_RING, 0)[0]] == eaten ? 0 : 1;
            }
        } while (loop != null && steps < fit.mesh.faceCount());
        assertTrue(steps > 0, "the ring moved up the horn before it was refused");
        int sphere = fit.regionOfFace[fit.strip(FIRST_RING, 1 - hornSide)[0]];
        assertTrue(Arrays.stream(fit.regionOfFace).anyMatch(region -> region != sphere && region >= 0),
                "the horn keeps a face: " + fit.lastRefusal);
    }

    @Test
    void theBenchScoresAreAtLeastTheBaselineOnEveryCaseTheFitMoves() {
        List<RingFitBench.Result> results = RingFitBench.run(CLUSTERING_FIT_FROM_BENCH_FIXTURE_NODES);
        assertEquals(RingFitBench.CASES_IN_RING_ANCHOR_FIT_TEST_ORDER.size(), results.size());
        long passing = results.stream().filter(RingFitBench.Result::passes).count();
        assertTrue(passing >= MINIMUM_PASSING, results.toString());
    }

    private static NodeGraphRuntime drawn(String fixture) {
        return NodeGraphRuntime.executeResource(RingFitBench.FIXTURE_RESOURCE_DIRECTORY + fixture + ".dsl", Map.of());
    }

    private static RegionClusterFit fit(NodeGraphRuntime drawn) {
        String port = SurfaceCreasesNode.CREASES.name;
        return new RegionClusterFit((GeometryBundle) drawn.lastOutput(NodeGraphRuntime.GEOMETRY),
                (SurfaceCreases) drawn.getNodeOutput(port, port));
    }

    private static int towardSmallerRegion(RegionClusterFit fit, int ring) {
        long[] sizes = new long[2];
        for (int side = 0; side < sizes.length; side++) {
            int region = fit.regionOfFace[fit.strip(ring, side)[0]];
            sizes[side] = Arrays.stream(fit.regionOfFace).filter(at -> at == region).count();
        }
        return sizes[0] <= sizes[1] ? 0 : 1;
    }

    private static int[] append(int[] faces, int face) {
        int[] more = Arrays.copyOf(faces, faces.length + 1);
        more[faces.length] = face;
        return more;
    }

    private static int farFace(RegionClusterFit fit, int[] strip, int region) {
        boolean[] near = new boolean[fit.mesh.vertexIdBound()];
        for (int face : strip) {
            for (int corner = 0; corner < fit.mesh.faceVertexCount(face); corner++) {
                int vertex = fit.mesh.faceVertexAt(face, corner);
                near[vertex] = true;
                for (int at = 0; at < fit.mesh.vertexEdgeCount(vertex); at++) {
                    near[fit.mesh.edgeOtherVertex(fit.mesh.vertexEdgeAt(vertex, at), vertex)] = true;
                }
            }
        }
        for (int active = 0; active < fit.mesh.faceCount(); active++) {
            int face = fit.mesh.faceIdAt(active);
            boolean touches = false;
            for (int corner = 0; corner < fit.mesh.faceVertexCount(face); corner++) {
                touches |= near[fit.mesh.faceVertexAt(face, corner)];
            }
            if (fit.regionOfFace[face] == region && !touches) {
                return face;
            }
        }
        throw new AssertionError("no face of region " + region + " away from the strip");
    }

    private static int pinchingFace(RegionClusterFit fit, int[] strip, int[] loop) {
        boolean[] inStrip = new boolean[fit.mesh.faceIdBound()];
        for (int face : strip) {
            inStrip[face] = true;
        }
        int vertex = fit.mesh.halfEdgeVertex(fit.mesh.edgeHalfEdge(loop[0]));
        for (int at = 0; at < fit.mesh.vertexFaceCount(vertex); at++) {
            int face = fit.mesh.vertexFaceAt(vertex, at);
            boolean sharesEdge = false;
            for (int side = 0; side < fit.mesh.faceEdgeCount(face); side++) {
                int edge = fit.mesh.faceEdgeAt(face, side);
                for (int across = 0; across < 2; across++) {
                    int other = fit.mesh.edgeFace(edge, across);
                    sharesEdge |= other >= 0 && other != face && inStrip[other];
                }
            }
            if (!inStrip[face] && !sharesEdge) {
                return face;
            }
        }
        throw new AssertionError("no face meets the new loop at one vertex only");
    }
}
