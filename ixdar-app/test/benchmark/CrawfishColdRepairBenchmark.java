package benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.nodegraph.api.MapNodeContext;
import ixdar.nodegraph.nodes.modifier.RepairMeshNode;
import ixdar.platform.Platforms;
import unit.SuitePlatform;

/**
 * Times repair_mesh on each crawfish scan the way the viewer first meets it: one fresh JVM per scan,
 * started with the app's flags and default heap, loading the scan and repairing it once.
 */
public final class CrawfishColdRepairBenchmark {

    public static final String TIME_PREFIX = "[cold-repair] ";

    public static final double COLD_REPAIR_LIMIT_MILLIS = 1000.0;

    private static final double NANOS_PER_MILLISECOND = 1.0e6;

    private static final int CHECK_MINUTES = 20;

    /**
     * Repairs each scan in its own JVM and asserts every first repair finishes under
     * {@link #COLD_REPAIR_LIMIT_MILLIS}; {@code -Dbenchmark.scans} picks scans.
     *
     * @throws IOException          when a child JVM cannot be started
     * @throws InterruptedException when interrupted waiting for one
     */
    @Test
    @Timeout(value = CHECK_MINUTES, unit = TimeUnit.MINUTES)
    public void everyScanRepairsColdUnderASecond() throws IOException, InterruptedException {
        String picked = System.getProperty(CrawfishOrientationCheckBenchmark.SCAN_LIST_PROPERTY, "");
        List<String> scans = picked.isEmpty() ? CrawfishOrientationCheckBenchmark.CRAWFISH_SCAN_NAMES
                : Arrays.asList(picked.split(CrawfishOrientationCheckBenchmark.SCAN_LIST_SEPARATOR));
        double slowest = 0;
        for (String scan : scans) {
            List<String> command = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-enableassertions", "--enable-native-access=ALL-UNNAMED", "-cp",
                    System.getProperty("java.class.path")));
            command.add(CrawfishColdRepairBenchmark.class.getName());
            command.add(scan);
            Process child = new ProcessBuilder(command).redirectErrorStream(true).start();
            double millis = Double.NaN;
            try (BufferedReader output = new BufferedReader(
                    new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line = output.readLine(); line != null; line = output.readLine()) {
                    if (line.startsWith(TIME_PREFIX)) {
                        millis = Double.parseDouble(line.substring(TIME_PREFIX.length()).trim());
                    } else {
                        Platforms.log(line + System.lineSeparator());
                    }
                }
            }
            child.waitFor();
            Platforms.log(String.format("[cold-repair] %s %.0f ms%n", scan, millis));
            slowest = Math.max(slowest, Double.isNaN(millis) ? Double.MAX_VALUE : millis);
        }
        assertTrue(slowest < COLD_REPAIR_LIMIT_MILLIS,
                "every scan's first repair in a fresh JVM finishes under a second, slowest " + slowest);
    }

    /**
     * Child JVM entry: loads one scan, repairs it once with the scan graph's settings and prints the
     * repair time.
     *
     * @param args the scan name
     * @throws IOException when the scan cannot be read
     */
    public static void main(String[] args) throws IOException {
        new SuitePlatform().launcherSessionOpened(null);
        GeometryBundle bundle = MeshLoader.loadBundle(
                MeshLoader.DATA_ROOT_PLACEHOLDER + "/crawfish/" + args[0] + ".glb");
        RepairMeshNode node = new RepairMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput(RepairMeshNode.GEOMETRY.name, bundle);
        context.setInput(RepairMeshNode.WELD_EPSILON.name, 0f);
        context.setInput(RepairMeshNode.MAX_HOLE_EDGES.name,
                CrawfishOrientationCheckBenchmark.SCAN_GRAPH_MAX_HOLE_EDGES);
        context.setInput(RepairMeshNode.MIN_SHELL_FACES.name,
                CrawfishOrientationCheckBenchmark.SCAN_GRAPH_MIN_SHELL_FACES);
        context.setInput(RepairMeshNode.STRICT.name, false);
        long start = System.nanoTime();
        node.evaluate(context);
        System.out.println(TIME_PREFIX + (System.nanoTime() - start) / NANOS_PER_MILLISECOND);
        System.exit(0);
    }
}
