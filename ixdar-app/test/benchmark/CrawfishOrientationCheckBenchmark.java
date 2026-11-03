package benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.ops.MeshRepairReport;
import ixdar.geometry.mesh.data.paths.query.TriangleBvh;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.nodegraph.api.MapNodeContext;
import ixdar.nodegraph.nodes.modifier.RepairMeshNode;
import ixdar.platform.Platforms;

/**
 * Counts, per repaired crawfish scan, the triangles whose back is seen from outside, straight on
 * or at a slant, and the flaps seeing out on both sides; all must be zero (CRAW-92).
 */
public final class CrawfishOrientationCheckBenchmark {

    public static final String SCAN_LIST_PROPERTY = "benchmark.scans";

    public static final String SCAN_LIST_SEPARATOR = ",";

    public static final List<String> CRAWFISH_SCAN_NAMES = List.of("IMG_4109", "IMG_4110",
            "IMG_4111", "IMG_4112", "IMG_4113", "IMG_4114", "IMG_4115", "IMG_4116", "IMG_4117",
            "IMG_4118");

    public static final int SCAN_GRAPH_MAX_HOLE_EDGES = 4096;

    public static final int SCAN_GRAPH_MIN_SHELL_FACES = 100;

    private static final double NANOS_PER_MILLISECOND = 1.0e6;

    private static final int BACK_OUT = 1;

    private static final int FRONT_OUT = 2;

    private static final int BACK_SEEN = 4;

    private static final int CHECK_MINUTES = 30;

    /**
     * Repairs each scan as its graph does and casts from every triangle's centroid along its
     * normal and into its back hemisphere; {@code -Dbenchmark.scans=IMG_4113,IMG_4117} picks scans.
     *
     * @throws IOException when a scan cannot be read
     */
    @Test
    @Timeout(value = CHECK_MINUTES, unit = TimeUnit.MINUTES)
    public void everyRepairedScanFacesOut() throws IOException {
        String picked = System.getProperty(SCAN_LIST_PROPERTY, "");
        List<String> scans = picked.isEmpty() ? CRAWFISH_SCAN_NAMES
                : Arrays.asList(picked.split(SCAN_LIST_SEPARATOR));
        int backOutTotal = 0;
        int flapTotal = 0;
        int seenTotal = 0;
        for (String scan : scans) {
            GeometryBundle bundle = MeshLoader.loadBundle(
                    MeshLoader.DATA_ROOT_PLACEHOLDER + "/crawfish/" + scan + ".glb");
            RepairMeshNode node = new RepairMeshNode();
            MapNodeContext context = new MapNodeContext(node);
            context.setInput(RepairMeshNode.GEOMETRY.name, bundle);
            context.setInput(RepairMeshNode.WELD_EPSILON.name, 0f);
            context.setInput(RepairMeshNode.MAX_HOLE_EDGES.name, SCAN_GRAPH_MAX_HOLE_EDGES);
            context.setInput(RepairMeshNode.MIN_SHELL_FACES.name, SCAN_GRAPH_MIN_SHELL_FACES);
            context.setInput(RepairMeshNode.STRICT.name, false);
            long start = System.nanoTime();
            node.evaluate(context);
            double repairMillis = (System.nanoTime() - start) / NANOS_PER_MILLISECOND;
            GeometryBundle repaired =
                    context.getOutput(RepairMeshNode.GEOMETRY.name, GeometryBundle.class);
            MeshRepairReport report =
                    (MeshRepairReport) repaired.slots().get(MeshRepairReport.SLOT);
            MeshTopology mesh = assertInstanceOf(MeshTopology.class, repaired.mesh(),
                    "the repair hands back a mesh");

            int[] faceOfTriangle = new int[mesh.triangleCount()];
            TriangleBvh bvh = TriangleBvh.over(mesh, faceOfTriangle);
            ThreadLocal<int[]> stacks = ThreadLocal.withInitial(() -> new int[Math.max(1, bvh.nodeCount)]);
            int[] verdicts = IntStream.range(0, bvh.triangleCount).parallel()
                    .map(triangle -> verdict(bvh, triangle, stacks.get())).toArray();
            // back-out, back-out fill, flaps, flap fill, back seen
            int[] counts = new int[5];
            for (int triangle = 0; triangle < bvh.triangleCount; triangle++) {
                int verdict = verdicts[triangle];
                boolean fill = Arrays.binarySearch(report.fillFaceIndices, faceOfTriangle[triangle]) >= 0;
                if ((verdict & BACK_OUT) != 0) {
                    int kind = (verdict & FRONT_OUT) != 0 ? 2 : 0;
                    counts[kind]++;
                    counts[kind + 1] += fill ? 1 : 0;
                }
                counts[4] += (verdict & BACK_SEEN) != 0 ? 1 : 0;
            }
            backOutTotal += counts[0];
            flapTotal += counts[2];
            seenTotal += counts[4];
            Platforms.log(String.format(
                    "[orientation] %s faces=%d backOut=%d (fill %d) flaps=%d (fill %d) backSeen=%d"
                            + " repairMs=%.0f exposed=%d pruned=%d rounds=%d refilled=%d refillOpen=%d%n",
                    scan, mesh.faceCount(), counts[0], counts[1], counts[2], counts[3], counts[4],
                    repairMillis, report.exposedFaceCount, report.prunedFaceCount,
                    report.pruneRoundCount, report.refilledHoleCount, report.refillOpenHoleCount));
        }
        Platforms.log(String.format("[orientation] total backOut=%d flaps=%d backSeen=%d%n",
                backOutTotal, flapTotal, seenTotal));
        assertEquals(0, backOutTotal, "no repaired triangle shows its back to the outside");
        assertEquals(0, flapTotal, "no repaired triangle sees out on both sides");
        assertEquals(0, seenTotal, "no repaired triangle's back is seen from outside at any slant");
    }

    /**
     * Which rays from one triangle's centroid escape.
     *
     * @param bvh      tree over the repaired mesh
     * @param triangle triangle to cast from
     * @param stack    this thread's node stack
     * @return {@link #BACK_OUT}, {@link #FRONT_OUT} and {@link #BACK_SEEN} bits; zero for a
     *         zero-area triangle
     */
    private static int verdict(TriangleBvh bvh, int triangle, int[] stack) {
        float[] p = bvh.positions;
        int cornerA = bvh.triangleCorners[triangle * TriangleBvh.TRIANGLE_CORNERS] * 3;
        int cornerB = bvh.triangleCorners[triangle * TriangleBvh.TRIANGLE_CORNERS + 1] * 3;
        int cornerC = bvh.triangleCorners[triangle * TriangleBvh.TRIANGLE_CORNERS + 2] * 3;
        double alongX = p[cornerB] - p[cornerA];
        double alongY = p[cornerB + 1] - p[cornerA + 1];
        double alongZ = p[cornerB + 2] - p[cornerA + 2];
        double acrossX = p[cornerC] - p[cornerA];
        double acrossY = p[cornerC + 1] - p[cornerA + 1];
        double acrossZ = p[cornerC + 2] - p[cornerA + 2];
        double normalX = alongY * acrossZ - alongZ * acrossY;
        double normalY = alongZ * acrossX - alongX * acrossZ;
        double normalZ = alongX * acrossY - alongY * acrossX;
        double length = Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
        if (length == 0.0) {
            return 0;
        }
        float[] centroid = new float[3];
        for (int axis = 0; axis < 3; axis++) {
            centroid[axis] = (p[cornerA + axis] + p[cornerB + axis] + p[cornerC + axis]) / 3f;
        }
        float[] front = {(float) (normalX / length), (float) (normalY / length), (float) (normalZ / length)};
        float[] back = {-front[0], -front[1], -front[2]};
        int verdict = bvh.anyHit(centroid, front, stack) < 0 ? FRONT_OUT : 0;
        if (bvh.anyHit(centroid, back, stack) < 0) {
            verdict |= BACK_OUT | BACK_SEEN;
        } else if (bvh.hemisphereEscapes(centroid, back, stack)) {
            verdict |= BACK_SEEN;
        }
        return verdict;
    }
}
