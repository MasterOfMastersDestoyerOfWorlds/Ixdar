package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.tools.ring.RingDslWriter;

/**
 * Deleting spline rings the way the ring tool's save does, the statement removed and its reader
 * rewired, keeps every other ring element-exact on a sphere.
 */
class RingDeletionTest {

    private static final String SPHERE = "sphere = icosphere(radius=1.0, subdivisions=3)\n";

    private static final String FIRST = "ring_00";

    private static final String MIDDLE = "ring_01";

    private static final String LAST = "ring_02";

    private static final float[][] ANCHORS = {
        { 0.85f, -0.48f, 0.17f },
        { 0.97f, 0.02f, 0.23f },
        { 0.84f, 0.51f, 0.2f },
    };

    private static final float[] LATITUDE_NORMAL = { 0f, 1f, 0f };

    @Test
    void deletingSplineRingsKeepsEveryOtherRingElementExact() throws Exception {
        String source = SPHERE;
        for (float[] anchor : ANCHORS) {
            source = RingDslWriter.appendSpline(source, anchor, 1, LATITUDE_NORMAL, List.of());
        }
        Map<String, String> saved = fingerprints(run(source));
        assertEquals(List.of(FIRST, MIDDLE, LAST), List.copyOf(saved.keySet()), source);
        assertTrue(source.contains(LAST + " = spline_ring(geometry=" + MIDDLE + "."), source);

        // The middle ring is the one the last reads from, so its reader is rewired.
        String middleDeleted = RingDslWriter.remove(source, MIDDLE);
        Map<String, String> expected = new TreeMap<>(saved);
        expected.remove(MIDDLE);
        assertEquals(expected, fingerprints(run(middleDeleted)),
                "a ring other than the deleted one changed or vanished on reload: "
                        + middleDeleted);

        // With the first gone too, the last reads the sphere's surface directly.
        String onlyLast = RingDslWriter.remove(middleDeleted, FIRST);
        Map<String, String> last = fingerprints(run(onlyLast));
        assertEquals(Set.of(LAST), last.keySet(), onlyLast);
        assertEquals(saved.get(LAST), last.get(LAST),
                "the last ring moved once it read the surface directly");
    }

    /**
     * Run a graph wired as the ring tool's save wires it.
     *
     * @param source graph whose last statement leaves the rings
     * @throws Exception when a node fails
     * @return the bundle the last statement leaves
     */
    private static GeometryBundle run(String source) throws Exception {
        NodeGraphRuntime runtime = NodeGraphRuntime.fromSource(
                RingDslWriter.wireRingInputs(source));
        Object result = runtime.executeGraphResult(runtime.lastId(),
                RingDslWriter.DEFAULT_UPSTREAM_PORT);
        assertTrue(result instanceof GeometryBundle, "the graph left no bundle");
        return (GeometryBundle) result;
    }

    /**
     * Fingerprint every ring mark a run left.
     *
     * @param bundle the run's output, whose mesh the marks index
     * @return fingerprint by label, in label order
     */
    private static Map<String, String> fingerprints(GeometryBundle bundle) {
        Map<String, String> fingerprints = new TreeMap<>();
        if (bundle.slots().get(EdgeMarks.SLOT) instanceof Map<?, ?> byLabel) {
            for (Map.Entry<?, ?> entry : byLabel.entrySet()) {
                if (entry.getValue() instanceof boolean[] flags) {
                    fingerprints.put(String.valueOf(entry.getKey()),
                            EdgeMarks.fingerprint(bundle.mesh(), flags));
                }
            }
        }
        return fingerprints;
    }
}
