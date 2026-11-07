package benchmark;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.labels.MeshLabels;
import ixdar.geometry.mesh.data.paths.query.SurfaceCreases;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.RegionExplosion;
import ixdar.geometry.mesh.data.rings.RingRegions;
import ixdar.graphics.render.color.Palettes;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.tools.regions.RingRegionTool;
import ixdar.tools.ring.RingTool;

/**
 * Times the groove measurement and a fresh region build, first run and warm in one JVM, printing
 * a digest of each repetition's results to check a change for identical output. Run it explicitly:
 *
 * <pre>
 * mvn test -Dtest=RegionsAndCreasesBenchmark -Dbenchmark.ringGraph=path/to/rings.dsl
 * </pre>
 */
public final class RegionsAndCreasesBenchmark {

    private static final String RING_GRAPH_PROPERTY = "benchmark.ringGraph";

    private static final String REPETITIONS_PROPERTY = "benchmark.repetitions";

    private static final String DEFAULT_RING_GRAPH =
            "src/main/resources/dsl/fixtures/fertility_rings.dsl";

    private static final String GEOMETRY_PORT = "geometry";

    private static final int DEFAULT_REPETITIONS = 8;

    private static final double NANOS_PER_MILLI = 1e6;

    /**
     * Runs the graph once, then measures the grooves and builds the regions of its rings again and
     * again, printing each repetition's time and result digest.
     *
     * @throws Exception when the graph fails to run
     */
    @Test
    public void creasesAndRegionsFirstRunAndWarm() throws Exception {
        String graphPath = System.getProperty(RING_GRAPH_PROPERTY, DEFAULT_RING_GRAPH);
        int repetitions = Integer.getInteger(REPETITIONS_PROPERTY, DEFAULT_REPETITIONS);
        NodeGraphRuntime graph = NodeGraphRuntime.fromSource(
                new String(Files.readAllBytes(Path.of(graphPath)), StandardCharsets.UTF_8));
        GeometryBundle bundle = (GeometryBundle) graph.executeGraphResult(graph.lastId(),
                GEOMETRY_PORT);
        MeshTopology surface = bundle.mesh();
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
        String[] labelArray = labels.toArray(new String[0]);
        boolean[][] maskArray = masks.toArray(new boolean[0][]);
        System.out.printf(Locale.ROOT, "[bench] %s: %d faces, %d vertices, %d rings%n", graphPath,
                surface.faceCount(), surface.vertexCount(), labelArray.length);
        for (int ring = 0; ring < labelArray.length; ring++) {
            System.out.printf(Locale.ROOT, "[bench] ring %s %s%n", labelArray[ring],
                    EdgeMarks.fingerprint(surface, maskArray[ring]));
        }
        SurfaceCreases creases = null;
        for (int repetition = 0; repetition < repetitions; repetition++) {
            long start = System.nanoTime();
            creases = SurfaceCreases.of(surface);
            double millis = (System.nanoTime() - start) / NANOS_PER_MILLI;
            int costHash = 1;
            for (int activeEdge = 0; activeEdge < surface.edgeCount(); activeEdge++) {
                costHash = 31 * costHash
                        + Double.hashCode(creases.edgeCost[surface.edgeIdAt(activeEdge)]);
            }
            System.out.printf(Locale.ROOT, "[bench] creases #%d %.1f ms; strength %08x curvature "
                    + "%08x cost %08x saturation %s%n", repetition, millis,
                    Arrays.hashCode(creases.valleyStrength), Arrays.hashCode(creases.valleyCurvature),
                    costHash, Double.toString(creases.saturation));
        }
        RegionExplosion explosion = new RegionExplosion(RingRegionTool::regionTag);
        for (int repetition = 0; repetition < repetitions; repetition++) {
            long start = System.nanoTime();
            RingRegions regions = new RingRegions(surface, new String[0], new boolean[0][]);
            regions.rebuild(labelArray, maskArray, creases, Palettes.DARK.region);
            double buildMillis = (System.nanoTime() - start) / NANOS_PER_MILLI;
            long explodeStart = System.nanoTime();
            explosion.measure(surface, regions.regionByActiveFace, regions.regionCount);
            double explodeMillis = (System.nanoTime() - explodeStart) / NANOS_PER_MILLI;
            long partsStart = System.nanoTime();
            MeshLabels parts = new MeshLabels(MeshLabels.inBundle(bundle));
            parts.resolve(regions, Palettes.DARK.categories);
            double partsMillis = (System.nanoTime() - partsStart) / NANOS_PER_MILLI;
            System.out.printf(Locale.ROOT, "[bench] regions #%d %.1f ms + explosion %.1f ms + %d "
                    + "parts %.1f ms; parts %08x; %d "
                    + "regions; faces %08x counts %08x area %08x rings %08x sides %08x "
                    + "crease %08x interior %08x peak %08x point %08x inside %08x label %08x "
                    + "colours %08x problems %08x notes %08x centroids %08x%n", repetition,
                    buildMillis, explodeMillis, parts.names.size(), partsMillis,
                    Arrays.hashCode(parts.labelByRegion), regions.regionCount,
                    Arrays.hashCode(regions.regionByActiveFace),
                    Arrays.hashCode(regions.regionFaceCount), Arrays.hashCode(regions.regionArea),
                    Arrays.deepHashCode(regions.boundingRingsByRegion),
                    Arrays.deepHashCode(regions.sideByRingRegion),
                    Arrays.hashCode(regions.regionCreaseShare),
                    Arrays.hashCode(regions.regionInteriorCreaseShare),
                    Arrays.hashCode(regions.regionPeakCurvature),
                    Arrays.hashCode(regions.regionCreasePoint),
                    Arrays.hashCode(regions.regionCreaseInterior),
                    Arrays.hashCode(regions.regionLabelPoint),
                    Arrays.hashCode(regions.colouring.colourByNode), regions.problems.hashCode(),
                    regions.noteByRingLabel.hashCode(),
                    Arrays.hashCode(explosion.regionCentroidXyz));
        }
    }
}
