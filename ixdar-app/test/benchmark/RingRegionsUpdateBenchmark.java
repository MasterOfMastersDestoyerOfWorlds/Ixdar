package benchmark;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.RingRegions;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.parsing.python.PythonParser;
import ixdar.scenes.ring.RingTool;

/**
 * Times deleting, adding back and moving single rings of a ring graph with
 * {@link RingRegions#update}, and checks every update against a fresh build. Run it explicitly:
 *
 * <pre>
 * mvn test -Dtest=RingRegionsUpdateBenchmark -Dbenchmark.ringGraph=path/to/rings.dsl
 * </pre>
 */
public final class RingRegionsUpdateBenchmark {

    private static final String RING_GRAPH_PROPERTY = "benchmark.ringGraph";

    private static final String DEFAULT_RING_GRAPH =
            "src/main/resources/dsl/fixtures/fertility_rings.dsl";

    private static final String GEOMETRY_PORT = "geometry";

    private static final int SAMPLED_RINGS = 8;

    private static final double NANOS_PER_MILLI = 1e6;

    /**
     * Builds the graph's rings once, then for a spread of rings deletes one, adds it back and
     * moves it onto the next ring's loop, timing each update and comparing it with a fresh build.
     *
     * @throws Exception when the graph fails to run
     */
    @Test
    public void deleteAddAndMoveSingleRings() throws Exception {
        String graphPath = System.getProperty(RING_GRAPH_PROPERTY, DEFAULT_RING_GRAPH);
        NodeGraphRuntime graph = NodeGraphRuntime.fromSource(
                new String(Files.readAllBytes(Path.of(graphPath)), StandardCharsets.UTF_8));
        List<PythonParser.ParsedNode> statements = graph.statements;
        GeometryBundle bundle = (GeometryBundle) graph.executeGraphResult(statements,
                statements.get(statements.size() - 1).id, GEOMETRY_PORT);
        List<String> labels = new ArrayList<>();
        List<boolean[]> masks = new ArrayList<>();
        if (bundle.slots().get(EdgeMarks.SLOT) instanceof Map<?, ?> marks) {
            for (Map.Entry<?, ?> entry : marks.entrySet()) {
                String label = String.valueOf(entry.getKey());
                if (entry.getValue() instanceof boolean[] mask && RingTool.isRingLabel(label)) {
                    labels.add(label);
                    masks.add(mask);
                }
            }
        }
        long start = System.nanoTime();
        RingRegions regions = new RingRegions(bundle.mesh(), labels.toArray(new String[0]),
                masks.toArray(new boolean[0][])).build();
        System.out.printf(Locale.ROOT, "[update] %s: %d faces, %d rings, %d regions, built in "
                + "%.0f ms%n", graphPath, bundle.mesh().faceCount(), labels.size(),
                regions.regionCount, (System.nanoTime() - start) / NANOS_PER_MILLI);

        int step = Math.max(1, labels.size() / SAMPLED_RINGS);
        for (int ring = 0; ring + 1 < labels.size(); ring += step) {
            List<String> withoutRing = new ArrayList<>(labels);
            List<boolean[]> withoutMask = new ArrayList<>(masks);
            withoutRing.remove(ring);
            withoutMask.remove(ring);
            timeAndCheck(regions, "delete " + labels.get(ring), withoutRing, withoutMask);
            timeAndCheck(regions, "add " + labels.get(ring), labels, masks);
            // Move: the ring takes the next ring's loop, which is gone from the set meanwhile.
            List<String> withoutNext = new ArrayList<>(labels);
            List<boolean[]> withoutNextMask = new ArrayList<>(masks);
            withoutNext.remove(ring + 1);
            withoutNextMask.remove(ring + 1);
            regions.update(withoutNext.toArray(new String[0]),
                    withoutNextMask.toArray(new boolean[0][]));
            List<boolean[]> moved = new ArrayList<>(withoutNextMask);
            moved.set(ring, masks.get(ring + 1));
            timeAndCheck(regions, "move " + labels.get(ring) + " onto " + labels.get(ring + 1),
                    withoutNext, moved);
            regions.update(labels.toArray(new String[0]), masks.toArray(new boolean[0][]));
        }
    }

    /**
     * Updates the regions to a ring set, prints the time and the faces re-flooded, and asserts the
     * result equals a fresh build of the same rings.
     */
    private static void timeAndCheck(RingRegions regions, String edit, List<String> labels,
            List<boolean[]> masks) {
        String[] labelArray = labels.toArray(new String[0]);
        boolean[][] maskArray = masks.toArray(new boolean[0][]);
        long start = System.nanoTime();
        regions.update(labelArray, maskArray);
        double millis = (System.nanoTime() - start) / NANOS_PER_MILLI;
        RingRegions fresh = new RingRegions(regions.mesh, labelArray, maskArray).build();
        System.out.printf(Locale.ROOT, "[update] %-34s %6.1f ms, %7d faces re-flooded, %d "
                + "regions%n", edit, millis, regions.refloodedFaces, regions.regionCount);
        assertEquals(fresh.regionCount, regions.regionCount, edit);
        assertArrayEquals(fresh.regionByActiveFace, regions.regionByActiveFace, edit);
        assertEquals(fresh.problems, regions.problems, edit);
        assertEquals(Arrays.deepToString(fresh.boundingRingsByRegion),
                Arrays.deepToString(regions.boundingRingsByRegion), edit);
        assertEquals(Arrays.deepToString(fresh.sideByRingRegion),
                Arrays.deepToString(regions.sideByRingRegion), edit);
    }
}
