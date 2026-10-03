package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.ArrayMeshEngine;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.nodes.selection.RingDslWriter;
import ixdar.scenes.ring.RingTool;

/**
 * Deleting rings the way the ring tool's save does, a spline statement removed and the proposed
 * rings frozen to exact loops, keeps every other ring element-exact on the procedural Y.
 */
class RingDeletionTest {

    private static final String CANDIDATES_ID = "rings";

    private static final String FIXTURE = "\"fixture\"";

    private static final String CANDIDATES_SOURCE = CANDIDATES_ID
            + " = ring_candidates(geometry=" + FIXTURE + ", resolution=96)\n";

    private static final String GEOMETRY_PORT = "." + RingDslWriter.DEFAULT_UPSTREAM_PORT;

    private static final String FIRST_PROPOSED = "ring_00";

    private static final String DELETED_PROPOSED = "ring_01";

    private static final String THIRD_PROPOSED = "ring_02";

    private static final String DELETED_SPLINE = "ring_03";

    private static final String KEPT_SPLINE = "ring_04";

    private static final List<String> PROPOSED =
            List.of(FIRST_PROPOSED, DELETED_PROPOSED, THIRD_PROPOSED);

    private static final float ALONG_LEG = 0.7f;

    private static final float LEG_SURFACE_OFFSET = 0.12f;

    private static final float[][] LEG_DIRECTIONS = {
        { 0f, 1f, 0f },
        { 0.8660254f, -0.5f, 0f },
    };

    @Test
    void freezingTheProposedRingsKeepsEveryRingElementExact() throws Exception {
        MeshTopology fixture = ArrayMeshEngine.fromUniformMeshTopology(
                RingCandidatesTest.yJunction());
        String source = withTwoSplineRings(fixture);
        GeometryBundle saved = run(source, fixture);

        String frozen = frozen(source, saved, PROPOSED);

        assertFalse(frozen.contains(RingDslWriter.CANDIDATES_NODE), frozen);
        assertEquals(fingerprints(saved), fingerprints(run(frozen, fixture)),
                "a frozen proposed ring did not reload element-exact: " + frozen);
    }

    @Test
    void deletingASplineRingAndAProposedRingKeepsEveryOtherRingElementExact() throws Exception {
        MeshTopology fixture = ArrayMeshEngine.fromUniformMeshTopology(
                RingCandidatesTest.yJunction());
        String source = withTwoSplineRings(fixture);
        GeometryBundle savedRun = run(source, fixture);
        Map<String, String> saved = fingerprints(savedRun);
        assertEquals(List.of(FIRST_PROPOSED, DELETED_PROPOSED, THIRD_PROPOSED, DELETED_SPLINE,
                KEPT_SPLINE), List.copyOf(saved.keySet()),
                "the two spline rings were not written: " + source);
        assertTrue(source.contains(KEPT_SPLINE + " = spline_ring(geometry=" + DELETED_SPLINE
                + GEOMETRY_PORT), source);

        String deleted = RingDslWriter.remove(source, DELETED_SPLINE);
        deleted = frozen(deleted, savedRun, List.of(FIRST_PROPOSED, THIRD_PROPOSED));
        Map<String, String> reloaded = fingerprints(run(deleted, fixture));

        Map<String, String> expected = new TreeMap<>(saved);
        expected.remove(DELETED_SPLINE);
        expected.remove(DELETED_PROPOSED);
        assertEquals(expected, reloaded,
                "a ring other than the two deleted changed or vanished on reload: " + deleted);

        String allProposedDeleted = frozen(deleted, savedRun, List.of());
        assertTrue(allProposedDeleted.startsWith(KEPT_SPLINE + " = spline_ring(geometry="
                + FIXTURE), "deleting every proposed ring left a statement behind: "
                + allProposedDeleted);
        Map<String, String> splineOnly = fingerprints(run(allProposedDeleted, fixture));
        assertEquals(Set.of(KEPT_SPLINE), splineOnly.keySet(), allProposedDeleted);
        assertEquals(saved.get(KEPT_SPLINE), splineOnly.get(KEPT_SPLINE),
                "the spline ring moved once it read the loaded surface directly");
    }

    /**
     * The Y's graph with two spline rings chained after its proposed rings.
     *
     * @param fixture the fixture surface
     * @throws Exception when a node fails
     * @return the graph's text
     */
    private static String withTwoSplineRings(MeshTopology fixture) throws Exception {
        Map<String, String> proposed = fingerprints(run(CANDIDATES_SOURCE, fixture));
        assertEquals(PROPOSED, List.copyOf(proposed.keySet()), "the Y should propose three rings");
        String source = CANDIDATES_SOURCE;
        for (float[] direction : LEG_DIRECTIONS) {
            float[] anchor = {
                ALONG_LEG * direction[0], ALONG_LEG * direction[1], LEG_SURFACE_OFFSET };
            source = RingDslWriter.appendSpline(source, anchor, 1, direction, proposed.keySet());
        }
        return source;
    }

    /**
     * The graph with its proposed rings' block replaced by one exact-loop statement per kept
     * ring, as the ring tool's save writes it.
     *
     * @param source   graph holding the {@code ring_candidates} line or an earlier freeze of it
     * @param proposed a run of the graph that proposed the rings
     * @param kept     labels of the proposed rings to keep, in rank order
     * @return the frozen graph's text
     */
    private static String frozen(String source, GeometryBundle proposed, List<String> kept) {
        MeshTopology mesh = proposed.mesh();
        Map<String, boolean[]> marks = marks(proposed);
        String output = FIXTURE;
        List<String> block = new ArrayList<>();
        Vector3f position = new Vector3f();
        for (String label : kept) {
            int[] loop = RingTool.orderedLoop(mesh, marks.get(label));
            assertTrue(loop.length > 0, label + " is not one closed edge loop");
            float[] loopXyz = new float[RingTool.COORDINATES_PER_POINT * loop.length];
            for (int vertex = 0; vertex < loop.length; vertex++) {
                mesh.vertexPosition(loop[vertex], position);
                loopXyz[RingTool.COORDINATES_PER_POINT * vertex] = position.x;
                loopXyz[RingTool.COORDINATES_PER_POINT * vertex + 1] = position.y;
                loopXyz[RingTool.COORDINATES_PER_POINT * vertex + 2] = position.z;
            }
            block.add(RingDslWriter.exactLoopStatement(label, output, loopXyz, loop.length));
            output = label + GEOMETRY_PORT;
        }
        Set<String> blockIds = new HashSet<>(PROPOSED);
        blockIds.add(CANDIDATES_ID);
        return RingDslWriter.replaceBlock(source, blockIds, block, FIXTURE, output);
    }

    /**
     * Run a graph over the fixture surface.
     *
     * @param source  graph whose first statement reads the fixture through its geometry argument
     * @param fixture the fixture surface
     * @throws Exception when a node fails
     * @return the bundle the graph's last statement leaves
     */
    private static GeometryBundle run(String source, MeshTopology fixture) throws Exception {
        NodeGraphRuntime runtime = NodeGraphRuntime.fromSource(source);
        String last = runtime.statements.get(runtime.statements.size() - 1).id;
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put(runtime.statements.get(0).id + GEOMETRY_PORT,
                GeometryBundle.ofMesh(fixture));
        Object result = runtime.executeGraphResult(runtime.statements, last,
                RingDslWriter.DEFAULT_UPSTREAM_PORT, overrides);
        assertTrue(result instanceof GeometryBundle, "the graph left no bundle");
        return (GeometryBundle) result;
    }

    /**
     * Every ring mark a run left.
     *
     * @param bundle the run's output
     * @return marks by ring label, in label order
     */
    private static Map<String, boolean[]> marks(GeometryBundle bundle) {
        Map<String, boolean[]> marks = new TreeMap<>();
        if (bundle.slots().get(EdgeMarks.SLOT) instanceof Map<?, ?> byLabel) {
            for (Map.Entry<?, ?> entry : byLabel.entrySet()) {
                if (entry.getValue() instanceof boolean[] flags) {
                    marks.put(String.valueOf(entry.getKey()), flags);
                }
            }
        }
        return marks;
    }

    /**
     * Fingerprint every ring mark a run left.
     *
     * @param bundle the run's output, whose mesh the marks index
     * @return fingerprint by label, in label order
     */
    private static Map<String, String> fingerprints(GeometryBundle bundle) {
        Map<String, String> fingerprints = new TreeMap<>();
        for (Map.Entry<String, boolean[]> entry : marks(bundle).entrySet()) {
            fingerprints.put(entry.getKey(),
                    EdgeMarks.fingerprint(bundle.mesh(), entry.getValue()));
        }
        return fingerprints;
    }
}
