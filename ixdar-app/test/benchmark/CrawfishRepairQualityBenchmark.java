package benchmark;

import static ixdar.geometry.mesh.data.representation.HalfEdgeMesh.TRIANGLE_CORNERS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.data.representation.TriangleGeometry;
import ixdar.nodegraph.api.MapNodeContext;
import ixdar.nodegraph.nodes.modifier.RepairMeshNode;
import ixdar.platform.Platforms;

/**
 * What repair_mesh made on one crawfish scan that a parametrization trips on (CRAW-146): slivers
 * beyond the raw scan's own, near-duplicate vertices and one-vertex angle-defect spikes, counted on
 * the faces whose corner positions form no face of the raw scan. Pick the scan with
 * {@code -Dbenchmark.glb}.
 */
public final class CrawfishRepairQualityBenchmark {

    public static final double NEAR_DUPLICATE_SHARE_OF_MEDIAN_EDGE = 0.01;

    private static final double PERCENTILE = 0.99;

    private static final long MIX = 0x9E3779B97F4A7C15L;

    private static final int MIX_SHIFT = 29;

    /** The raw scan's largest sliver aspect, longest edge squared over twice the area, faces of area only. */
    public double rawAspectMax;

    /** The raw scan's 99th percentile aspect. */
    public double rawAspectP99;

    /** The largest aspect among the faces the repair made. */
    public double madeAspectMax;

    /** The smallest corner angle among the faces the repair made, in degrees. */
    public double madeCornerMinDegrees;

    /** Faces the repair made. */
    public int madeFaces;

    /** Made faces whose aspect exceeds the raw scan's largest. */
    public int sliverFaces;

    /** Made faces whose aspect exceeds the raw scan's 99th percentile. */
    public int aboveRawP99Faces;

    /** Vertex pairs closer than a hundredth of the median edge, one of them not at a raw position. */
    public int nearDuplicatePairs;

    /**
     * Vertices on a made face whose angle defect is over a half turn and more than twice the median
     * of their neighbours', a needle cone.
     */
    public int spikeVertices;

    /** One line per near-duplicate pair and spike vertex: where it is and what surrounds it. */
    public List<String> defectDetails = new ArrayList<>();

    /**
     * Repairs one scan as the scan graphs do, logs the numbers and every defect, and asserts the
     * repair made no sliver, near-duplicate or spike.
     *
     * @throws IOException when the scan cannot be read
     */
    @Test
    public void repairMakesNoNeedles() throws IOException {
        String glbPath = System.getProperty(CrawfishMeshRepairBenchmark.GLB_PROPERTY,
                CrawfishMeshRepairBenchmark.DEFAULT_GLB);
        GeometryBundle bundle = MeshLoader.loadBundle(glbPath);
        RepairMeshNode node = new RepairMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput(RepairMeshNode.GEOMETRY.name, bundle);
        context.setInput(RepairMeshNode.MAX_HOLE_EDGES.name, CrawfishMeshRepairBenchmark.MAX_HOLE_EDGES);
        context.setInput(RepairMeshNode.MIN_SHELL_FACES.name, CrawfishMeshRepairBenchmark.MIN_SHELL_FACES);
        node.evaluate(context);
        HalfEdgeMesh repaired = assertInstanceOf(HalfEdgeMesh.class,
                context.getOutput(RepairMeshNode.GEOMETRY.name, GeometryBundle.class).mesh());
        CrawfishRepairQualityBenchmark quality = measure(bundle.mesh(), repaired);
        Platforms.log("[repair-quality] " + glbPath + " " + quality.line() + "\n");
        for (String defect : quality.defectDetails) {
            Platforms.log("[repair-quality] defect " + defect + "\n");
        }
        assertAll(
                () -> assertEquals(0, quality.sliverFaces, "made faces thinner than the scan's own thinnest"),
                () -> assertEquals(0, quality.nearDuplicatePairs, "near-duplicate vertices the repair made"),
                () -> assertEquals(0, quality.spikeVertices, "one-vertex angle-defect spikes on made faces"));
    }

    /**
     * Measures a repaired mesh against the raw scan it came from.
     *
     * @param raw the scan as loaded
     * @param repaired the closed repaired surface
     * @return the numbers
     */
    public static CrawfishRepairQualityBenchmark measure(MeshTopology raw, MeshTopology repaired) {
        CrawfishRepairQualityBenchmark quality = new CrawfishRepairQualityBenchmark();
        PackedPoints rawPositions = raw.packedPositions(null);
        int[] rawCorners = corners(raw);
        double[] rawAspects = aspects(rawPositions, rawCorners);
        double[] positive = Arrays.stream(rawAspects).filter(Double::isFinite).sorted().toArray();
        quality.rawAspectMax = positive[positive.length - 1];
        quality.rawAspectP99 = positive[(int) (PERCENTILE * (positive.length - 1))];
        long[] rawVertexKeys = IntStream.range(0, rawPositions.size())
                .mapToLong(vertex -> positionKey(rawPositions, vertex)).toArray();
        long[] rawFaceKeys = IntStream.range(0, rawCorners.length / TRIANGLE_CORNERS)
                .mapToLong(face -> faceKey(rawVertexKeys, rawCorners, face)).sorted().toArray();
        long[] rawPositionKeys = rawVertexKeys.clone();
        Arrays.sort(rawPositionKeys);

        PackedPoints positions = repaired.packedPositions(null);
        int[] corners = corners(repaired);
        int faces = corners.length / TRIANGLE_CORNERS;
        long[] vertexKeys = IntStream.range(0, positions.size()).mapToLong(vertex -> positionKey(positions, vertex))
                .toArray();
        double[] aspects = aspects(positions, corners);
        boolean[] madeVertex = new boolean[positions.size()];
        quality.madeCornerMinDegrees = Double.POSITIVE_INFINITY;
        double[] angleSum = new double[positions.size()];
        double[] lengths = new double[corners.length];
        for (int face = 0; face < faces; face++) {
            boolean made = Arrays.binarySearch(rawFaceKeys, faceKey(vertexKeys, corners, face)) < 0;
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                int at = corners[face * TRIANGLE_CORNERS + corner];
                int next = corners[face * TRIANGLE_CORNERS + (corner + 1) % TRIANGLE_CORNERS];
                double angle = TriangleGeometry.cornerAngle(positions, at, next,
                        corners[face * TRIANGLE_CORNERS + (corner + 2) % TRIANGLE_CORNERS]);
                lengths[face * TRIANGLE_CORNERS + corner] = Math.sqrt(positions.distanceSquared(at, next));
                angleSum[at] += angle;
                if (made) {
                    madeVertex[at] = true;
                    quality.madeCornerMinDegrees = Math.min(quality.madeCornerMinDegrees, Math.toDegrees(angle));
                }
            }
            if (made) {
                quality.madeFaces++;
                quality.madeAspectMax = Math.max(quality.madeAspectMax, aspects[face]);
                quality.sliverFaces += aspects[face] > quality.rawAspectMax ? 1 : 0;
                quality.aboveRawP99Faces += aspects[face] > quality.rawAspectP99 ? 1 : 0;
            }
        }
        int[][] rings = repaired.oneRings();
        Arrays.sort(lengths);
        double tolerance = NEAR_DUPLICATE_SHARE_OF_MEDIAN_EDGE * lengths[lengths.length / 2];
        Integer[] order = IntStream.range(0, positions.size()).boxed().toArray(Integer[]::new);
        Arrays.sort(order, (left, right) -> Float.compare(positions.get(left, 0), positions.get(right, 0)));
        for (int index = 0; index < order.length; index++) {
            int first = order[index];
            for (int next = index + 1; next < order.length
                    && positions.get(order[next], 0) - positions.get(first, 0) < tolerance; next++) {
                int second = order[next];
                boolean madeEither = Arrays.binarySearch(rawPositionKeys, vertexKeys[first]) < 0
                        || Arrays.binarySearch(rawPositionKeys, vertexKeys[second]) < 0;
                if (madeEither && positions.distanceSquared(first, second) < tolerance * tolerance) {
                    quality.nearDuplicatePairs++;
                    quality.defectDetails.add(String.format("near-duplicate %s and %s, apart %.3g of the median edge,"
                            + " valences %d and %d, joined %b", positions.at(first), positions.at(second),
                            Math.sqrt(positions.distanceSquared(first, second)) / lengths[lengths.length / 2],
                            rings[first].length, rings[second].length,
                            Arrays.stream(rings[first]).anyMatch(neighbour -> neighbour == second)));
                }
            }
        }
        int[] spikes = IntStream.range(0, positions.size()).filter(vertex -> madeVertex[vertex]).filter(vertex -> {
            double defect = Math.abs(2 * Math.PI - angleSum[vertex]);
            double[] around = Arrays.stream(rings[vertex]).mapToDouble(n -> Math.abs(2 * Math.PI - angleSum[n]))
                    .sorted().toArray();
            return defect > Math.PI && around.length > 0 && defect > 2 * around[around.length / 2];
        }).toArray();
        quality.spikeVertices = spikes.length;
        for (int spike : spikes) {
            double[] around = Arrays.stream(rings[spike])
                    .mapToDouble(neighbour -> Math.toDegrees(2 * Math.PI - angleSum[neighbour])).toArray();
            quality.defectDetails.add(String.format("spike %s angle sum %.1f valence %d at raw position %b,"
                    + " neighbour defects %s",
                    positions.at(spike), Math.toDegrees(angleSum[spike]), rings[spike].length,
                    Arrays.binarySearch(rawPositionKeys, vertexKeys[spike]) >= 0,
                    Arrays.toString(Arrays.stream(around).map(Math::rint).toArray())));
        }
        return quality;
    }

    /**
     * One line of the numbers, for a log.
     *
     * @return the line
     */
    public String line() {
        return String.format("made faces %d, made aspect max %.1f (raw max %.1f, raw p99 %.2f), made corner min %.3f deg,"
                + " slivers beyond raw max %d, above raw p99 %d, near-duplicate pairs %d, defect spikes %d",
                madeFaces, madeAspectMax, rawAspectMax, rawAspectP99, madeCornerMinDegrees, sliverFaces,
                aboveRawP99Faces, nearDuplicatePairs, spikeVertices);
    }

    /**
     * Triangle corners of a mesh as dense vertex indices.
     *
     * @param mesh a triangle mesh
     * @return three dense indices per face
     */
    private static int[] corners(MeshTopology mesh) {
        int[] activeOf = mesh.activeIndexById();
        int[] corners = new int[mesh.faceCount() * TRIANGLE_CORNERS];
        for (int face = 0; face < mesh.faceCount(); face++) {
            int faceId = mesh.faceIdAt(face);
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                corners[face * TRIANGLE_CORNERS + corner] = activeOf[mesh.faceVertexAt(faceId, corner)];
            }
        }
        return corners;
    }

    /**
     * Per face the longest edge squared over twice the area, infinite on a face of no area.
     *
     * @param positions vertex positions
     * @param corners three indices per face
     * @return the aspects
     */
    private static double[] aspects(PackedPoints positions, int[] corners) {
        double[] areas = TriangleGeometry.areas(positions, corners);
        return IntStream.range(0, areas.length).mapToDouble(face -> TriangleGeometry.longestEdgeSquared(positions,
                corners[face * TRIANGLE_CORNERS], corners[face * TRIANGLE_CORNERS + 1],
                corners[face * TRIANGLE_CORNERS + 2]) / (2 * areas[face])).toArray();
    }

    /**
     * A hash of one vertex's exact position.
     *
     * @param positions vertex positions
     * @param vertex the vertex
     * @return the key
     */
    private static long positionKey(PackedPoints positions, int vertex) {
        long key = 0;
        for (int axis = 0; axis < PackedPoints.XYZ; axis++) {
            key = (key ^ Float.floatToIntBits(positions.get(vertex, axis))) * MIX;
            key ^= key >>> MIX_SHIFT;
        }
        return key;
    }

    /**
     * A hash of one face's corner positions, independent of corner order.
     *
     * @param vertexKeys position key per vertex
     * @param corners three indices per face
     * @param face the face
     * @return the key
     */
    private static long faceKey(long[] vertexKeys, int[] corners, int face) {
        long[] keys = {vertexKeys[corners[face * TRIANGLE_CORNERS]], vertexKeys[corners[face * TRIANGLE_CORNERS + 1]],
            vertexKeys[corners[face * TRIANGLE_CORNERS + 2]]};
        Arrays.sort(keys);
        long key = 0;
        for (long part : keys) {
            key = (key ^ part) * MIX;
            key ^= key >>> MIX_SHIFT;
        }
        return key;
    }
}
