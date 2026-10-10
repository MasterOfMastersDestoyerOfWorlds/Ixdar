package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.RingBundle;
import ixdar.geometry.mesh.data.RingRegions;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.nodes.api.BoolField;
import ixdar.geometry.mesh.nodes.api.IntField;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.data.TagGeometryNode;
import ixdar.geometry.mesh.nodes.selection.RingRegionsNode;

/**
 * Ring regions on a procedural capped cylinder: three horizontal rings cut it into four bands with
 * the right bounding rings and sides, and an open ring is reported and walls nothing.
 */
class RingRegionsTest {

    static final int SEGMENTS_AROUND = 12;

    static final int SIDE_ROWS = 8;

    private static final int[] RING_VERTEX_ROWS = { 2, 4, 6 };

    private static final String[] RING_LABELS = { "ring_a", "ring_b", "ring_c" };

    private static final float CYLINDER_HEIGHT = 4f;

    private static final String LINE_BREAK = "\n";

    private static final String SHIN_TAG = "shin";

    private static final String LOWER_BAND_POINT = "point 1,0,1.5";

    @Test
    void threeRingsCutACylinderIntoFourRegions() {
        MeshTopology cylinder = cappedCylinder();
        RingRegions regions = new RingRegions(cylinder, RING_LABELS, ringMarks(cylinder, -1))
                .build();

        assertEquals(4, regions.regionCount, String.join(LINE_BREAK, regions.reportLines()));
        assertTrue(regions.problems.isEmpty(), regions.problems.toString());
        int bottom = regionAt(regions, 0f);
        int lowerBand = regionAt(regions, 1.5f);
        int upperBand = regionAt(regions, 2.5f);
        int top = regionAt(regions, CYLINDER_HEIGHT);
        assertArrayEquals(new int[] { 0 }, regions.boundingRingsByRegion[bottom]);
        assertArrayEquals(new int[] { 0, 1 }, regions.boundingRingsByRegion[lowerBand]);
        assertArrayEquals(new int[] { 1, 2 }, regions.boundingRingsByRegion[upperBand]);
        assertArrayEquals(new int[] { 2 }, regions.boundingRingsByRegion[top]);
        assertEquals(RingRegions.SIDE_DISTAL, regions.sideByRingRegion[0][bottom]);
        assertEquals(RingRegions.SIDE_PROXIMAL, regions.sideByRingRegion[0][lowerBand]);
        assertEquals(RingRegions.SIDE_DISTAL, regions.sideByRingRegion[2][top]);

        boolean[] picked = regions.select(LOWER_BAND_POINT + "; region " + top);
        assertTrue(picked[lowerBand] && picked[top]);
        assertEquals(2, count(picked));
    }

    @Test
    void anOpenRingIsReportedAndMakesNoRegion() {
        MeshTopology cylinder = cappedCylinder();
        RingRegions regions = new RingRegions(cylinder, RING_LABELS, ringMarks(cylinder, 1))
                .build();

        assertEquals(3, regions.regionCount, String.join(LINE_BREAK, regions.reportLines()));
        assertFalse(regions.ringIsWall[1]);
        assertEquals(1, regions.problems.size(), regions.problems.toString());
        assertTrue(regions.problems.get(0).startsWith("ring_b is open: 2 loose end(s)"),
                regions.problems.get(0));
        int middle = regionAt(regions, 2f);
        assertArrayEquals(new int[] { 0, 2 }, regions.boundingRingsByRegion[middle]);
    }

    @Test
    void theNodeLabelsFacesAndFeedsTheSelectionToFaceAndTagConsumers() {
        MeshTopology cylinder = cappedCylinder();
        boolean[][] marks = ringMarks(cylinder, -1);
        GeometryBundle bundle = GeometryBundle.ofMesh(cylinder);
        for (int ring = 0; ring < RING_LABELS.length; ring++) {
            bundle = EdgeMarks.with(bundle, RING_LABELS[ring], marks[ring]);
        }
        MapNodeContext context = new MapNodeContext(new RingRegionsNode())
                .with(RingRegionsNode.GEOMETRY, bundle)
                .with(RingRegionsNode.SELECT, LOWER_BAND_POINT)
                .with(RingRegionsNode.TAG, SHIN_TAG)
                .eval();

        assertEquals(4, context.output(RingRegionsNode.REGION_COUNT, Integer.class));
        IntField region = context.output(RingRegionsNode.REGION, IntField.class);
        BoolField selection = context.output(RingRegionsNode.SELECTION, BoolField.class);
        assertEquals(cylinder.faceCount(), region.length());
        GeometryBundle out = context.output(RingRegionsNode.GEOMETRY_OUT, GeometryBundle.class);
        RingRegions regions = (RingRegions) out.slots().get(RingRegions.SLOT);
        int lowerBand = regionAt(regions, 1.5f);
        int selected = 0;
        for (int activeFace = 0; activeFace < cylinder.faceCount(); activeFace++) {
            boolean expected = region.get(activeFace) == lowerBand;
            assertEquals(expected, selection.data()[activeFace]);
            selected += expected ? 1 : 0;
        }
        int bandRows = RING_VERTEX_ROWS[1] - RING_VERTEX_ROWS[0];
        assertEquals(2 * SEGMENTS_AROUND * bandRows, selected);
        Map<String, boolean[]> tags = TagGeometryNode.getTags(out);
        assertEquals(SEGMENTS_AROUND * (bandRows + 1), count(tags.get(SHIN_TAG)));
        assertTrue(context.output(RingRegionsNode.REPORT, String.class)
                .startsWith("4 region(s) from 3 closed ring(s) of 3"));
    }

    @Test
    void twoRingsAroundAHandleCutItIntoTwoStretchesAndAreReported() {
        int around = 16;
        int tube = 8;
        float[] positions = new float[3 * around * tube];
        int[] triangles = new int[3 * 2 * around * tube];
        int cursor = 0;
        for (int major = 0; major < around; major++) {
            for (int minor = 0; minor < tube; minor++) {
                double theta = 2 * Math.PI * major / around;
                double phi = 2 * Math.PI * minor / tube;
                int vertex = major * tube + minor;
                positions[3 * vertex] = (float) ((2 + Math.cos(phi)) * Math.cos(theta));
                positions[3 * vertex + 1] = (float) ((2 + Math.cos(phi)) * Math.sin(theta));
                positions[3 * vertex + 2] = (float) Math.sin(phi);
                int nextMajor = (major + 1) % around * tube;
                int nextMinor = (minor + 1) % tube;
                cursor = put(triangles, cursor, vertex, nextMajor + minor, nextMajor + nextMinor);
                cursor = put(triangles, cursor, vertex, nextMajor + nextMinor,
                        major * tube + nextMinor);
            }
        }
        MeshTopology torus = HalfEdgeMeshEngine.buildFromIndexedMesh(positions, triangles);
        int[] ringColumns = { 2, 6 };
        boolean[][] marks = new boolean[2][RingBundle.edgeIdCeiling(torus)];
        for (int ring = 0; ring < 2; ring++) {
            for (int minor = 0; minor < tube; minor++) {
                marks[ring][torus.edgeBetween(torus.vertexIdAt(ringColumns[ring] * tube + minor),
                        torus.vertexIdAt(ringColumns[ring] * tube + (minor + 1) % tube))] = true;
            }
        }
        RingRegions regions = new RingRegions(torus, new String[] { RING_LABELS[0],
            RING_LABELS[1] }, marks).build();

        assertEquals(2, regions.regionCount);
        assertFalse(regions.ringSeparates[0] || regions.ringSeparates[1]);
        assertTrue(regions.problems.isEmpty(), regions.problems.toString());
        // The outer equator point at column 4, midway between the rings' columns 2 and 6.
        boolean[] stretch = regions.select("point 0,3,0");
        assertEquals(1, count(stretch));
        assertEquals(2 * tube * (ringColumns[1] - ringColumns[0]),
                regions.regionFaceCount[stretch[0] ? 0 : 1]);
    }

    /**
     * A closed cylinder of radius 1 along z, its side split into triangles and each end capped
     * by a fan around a centre vertex; side vertex {@code row * SEGMENTS_AROUND + segment}.
     */
    static MeshTopology cappedCylinder() {
        int sideVertices = (SIDE_ROWS + 1) * SEGMENTS_AROUND;
        float[] positions = new float[3 * (sideVertices + 2)];
        for (int row = 0; row <= SIDE_ROWS; row++) {
            for (int segment = 0; segment < SEGMENTS_AROUND; segment++) {
                double angle = 2 * Math.PI * segment / SEGMENTS_AROUND;
                int vertex = row * SEGMENTS_AROUND + segment;
                positions[3 * vertex] = (float) Math.cos(angle);
                positions[3 * vertex + 1] = (float) Math.sin(angle);
                positions[3 * vertex + 2] = CYLINDER_HEIGHT * row / SIDE_ROWS;
            }
        }
        int bottomCentre = sideVertices;
        int topCentre = sideVertices + 1;
        positions[3 * topCentre + 2] = CYLINDER_HEIGHT;
        int topRow = SIDE_ROWS * SEGMENTS_AROUND;
        int[] triangles = new int[3 * 2 * (SIDE_ROWS + 1) * SEGMENTS_AROUND];
        int cursor = 0;
        for (int row = 0; row < SIDE_ROWS; row++) {
            for (int segment = 0; segment < SEGMENTS_AROUND; segment++) {
                int next = (segment + 1) % SEGMENTS_AROUND;
                int lowLeft = row * SEGMENTS_AROUND + segment;
                int lowRight = row * SEGMENTS_AROUND + next;
                cursor = put(triangles, cursor, lowLeft, lowRight, lowRight + SEGMENTS_AROUND);
                cursor = put(triangles, cursor, lowLeft, lowRight + SEGMENTS_AROUND,
                        lowLeft + SEGMENTS_AROUND);
            }
        }
        for (int segment = 0; segment < SEGMENTS_AROUND; segment++) {
            int next = (segment + 1) % SEGMENTS_AROUND;
            cursor = put(triangles, cursor, bottomCentre, next, segment);
            cursor = put(triangles, cursor, topCentre, topRow + segment, topRow + next);
        }
        return HalfEdgeMeshEngine.buildFromIndexedMesh(positions, triangles);
    }

    private static int put(int[] triangles, int cursor, int first, int second, int third) {
        triangles[cursor] = first;
        triangles[cursor + 1] = second;
        triangles[cursor + 2] = third;
        return cursor + 3;
    }

    /**
     * One edge-id-indexed mask per ring, each the loop of edges around its vertex row.
     *
     * @param mesh     the cylinder
     * @param openRing ring to leave one edge short of closing, or -1 for none
     */
    private static boolean[][] ringMarks(MeshTopology mesh, int openRing) {
        boolean[][] marks = new boolean[RING_VERTEX_ROWS.length][RingBundle.edgeIdCeiling(mesh)];
        for (int ring = 0; ring < RING_VERTEX_ROWS.length; ring++) {
            int rowStart = RING_VERTEX_ROWS[ring] * SEGMENTS_AROUND;
            int segments = ring == openRing ? SEGMENTS_AROUND - 1 : SEGMENTS_AROUND;
            for (int segment = 0; segment < segments; segment++) {
                int edgeId = mesh.edgeBetween(mesh.vertexIdAt(rowStart + segment),
                        mesh.vertexIdAt(rowStart + (segment + 1) % SEGMENTS_AROUND));
                assertTrue(edgeId != MeshTopology.NONE);
                marks[ring][edgeId] = true;
            }
        }
        return marks;
    }

    /** The region holding the face nearest the cylinder's surface at height {@code z}. */
    private static int regionAt(RingRegions regions, float z) {
        int activeFace = regions.nearestActiveFace(1f, 0f, z);
        return regions.regionByActiveFace[activeFace];
    }

    private static int count(boolean[] flags) {
        int total = 0;
        for (boolean flag : flags) {
            total += flag ? 1 : 0;
        }
        return total;
    }
}
