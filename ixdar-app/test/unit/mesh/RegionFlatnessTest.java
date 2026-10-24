package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.RingBundle;
import ixdar.geometry.mesh.data.paths.query.SurfaceCreases;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.RingRegions;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.scenes.ring.RingScene;
import ixdar.tools.Item;
import ixdar.tools.regions.RingRegionTool;
import unit.GlStandIn;

/**
 * Region flatness on a capped tube bent a quarter turn in its middle: with rings only near its
 * ends the bend's inner crease lies inside the middle region, and a ring across the bend puts it
 * on that ring instead.
 */
class RegionFlatnessTest {

    private static final int SEGMENTS_AROUND = 32;

    private static final int ROWS = 60;

    private static final double TUBE_RADIUS = 1.0;

    // The centre line bends round this radius, so the inner side curves at 1 / (1.2 - 1) = 5
    // along the tube, well over the tube's own curvature round its section.
    private static final double BEND_RADIUS = 1.2;

    private static final double STRAIGHT_LENGTH = 4.0;

    private static final int END_RING_ROWS = 5;

    private static final String START_RING = "ring_start";

    private static final String END_RING = "ring_end";

    private static final String[] END_RING_LABELS = { START_RING, END_RING };

    private static final String[] JOINT_RING_LABELS = { START_RING, "ring_bend", END_RING };

    private static final double BEND_LENGTH = BEND_RADIUS * Math.PI / 2;

    private static final double TUBE_LENGTH = 2 * STRAIGHT_LENGTH + BEND_LENGTH;

    private static final int BEND_ROW = (int) Math.round(ROWS
            * (STRAIGHT_LENGTH + BEND_LENGTH / 2) / TUBE_LENGTH);

    // The inner side of the bend at its middle, where the crease is deepest.
    private static final float[] BEND_INNER_POINT = {
        0f,
        (float) (BEND_RADIUS - (BEND_RADIUS - TUBE_RADIUS) * Math.cos(Math.PI / 4)),
        (float) (STRAIGHT_LENGTH + (BEND_RADIUS - TUBE_RADIUS) * Math.sin(Math.PI / 4)),
    };

    private static final double CREASE_POINT_REACH = 1.0;

    @Test
    void aBendNoRingSplitsIsAnInteriorCrease() {
        MeshTopology tube = bentTube();
        RingRegions regions = new RingRegions(tube, END_RING_LABELS,
                rowMarks(tube, new int[] { END_RING_ROWS, ROWS - END_RING_ROWS })).build();
        regions.measureFlatness(SurfaceCreases.of(tube));

        assertEquals(3, regions.regionCount);
        int middle = regions.regionByActiveFace[regions.nearestActiveFace(BEND_INNER_POINT[0],
                BEND_INNER_POINT[1], BEND_INNER_POINT[2])];
        Item item = regionItems(regions).get(middle);
        assertTrue(item.numbers.get(RingRegionTool.INTERIOR_CREASE_SHARE) > 0.0, item.detail);
        assertEquals(item.numbers.get(RingRegionTool.CREASE_SHARE),
                item.numbers.get(RingRegionTool.INTERIOR_CREASE_SHARE), 1e-9, item.detail);
        assertTrue(item.numbers.get(RingRegionTool.PEAK_OVER_SATURATION) > 0.0, item.detail);
        assertTrue(item.flags.get(RingRegionTool.CREASE_INTERIOR), item.detail);
        assertTrue(distanceToBend(item) < CREASE_POINT_REACH, item.detail);
    }

    @Test
    void aRingAcrossTheBendPutsTheCreaseOnItsRing() {
        MeshTopology tube = bentTube();
        RingRegions regions = new RingRegions(tube, JOINT_RING_LABELS, rowMarks(tube,
                new int[] { END_RING_ROWS, BEND_ROW, ROWS - END_RING_ROWS })).build();
        regions.measureFlatness(SurfaceCreases.of(tube));

        assertEquals(4, regions.regionCount);
        int halves = 0;
        List<Item> items = regionItems(regions);
        for (int region = 0; region < regions.regionCount; region++) {
            if (regions.boundingRingsByRegion[region].length != 2) {
                continue;
            }
            halves++;
            Item item = items.get(region);
            assertTrue(item.numbers.get(RingRegionTool.CREASE_SHARE) > 0.0, item.detail);
            assertEquals(0.0, item.numbers.get(RingRegionTool.INTERIOR_CREASE_SHARE), item.detail);
            assertFalse(item.flags.get(RingRegionTool.CREASE_INTERIOR), item.detail);
            assertTrue(distanceToBend(item) < CREASE_POINT_REACH, item.detail);
        }
        assertEquals(2, halves);
    }

    /**
     * Splitting the bend by an update re-measures the regions it changed and keeps the caps', and
     * the result is what a fresh build measures.
     */
    @Test
    void anUpdateMeasuresWhatAFreshBuildMeasures() {
        MeshTopology tube = bentTube();
        SurfaceCreases creases = SurfaceCreases.of(tube);
        boolean[][] endMarks = rowMarks(tube, new int[] { END_RING_ROWS, ROWS - END_RING_ROWS });
        boolean[][] jointMarks = rowMarks(tube,
                new int[] { END_RING_ROWS, BEND_ROW, ROWS - END_RING_ROWS });
        RingRegions updated = new RingRegions(tube, END_RING_LABELS, endMarks).build();
        updated.measureFlatness(creases);
        updated.update(JOINT_RING_LABELS, new boolean[][] { endMarks[0], jointMarks[1],
            endMarks[1] });
        updated.measureFlatness(creases);
        RingRegions fresh = new RingRegions(tube, JOINT_RING_LABELS, jointMarks).build();
        fresh.measureFlatness(creases);

        assertEquals(fresh.regionCount, updated.regionCount);
        List<Item> freshItems = regionItems(fresh);
        List<Item> updatedItems = regionItems(updated);
        for (int region = 0; region < fresh.regionCount; region++) {
            assertEquals(freshItems.get(region).numbers, updatedItems.get(region).numbers);
            assertEquals(freshItems.get(region).flags, updatedItems.get(region).flags);
            assertArrayEquals(freshItems.get(region).points.get(RingRegionTool.CREASE_POINT),
                    updatedItems.get(region).points.get(RingRegionTool.CREASE_POINT));
        }
    }

    /** The region tool's items over measured regions, as ui-state and the tools route export them. */
    private static List<Item> regionItems(RingRegions regions) {
        Platform suitePlatform = Platforms.get();
        GL suiteGl = Platforms.gl();
        Platforms.init(suitePlatform, GlStandIn.create());
        try {
            RingScene scene = new RingScene();
            scene.regionLayer.regions = regions;
            return new RingRegionTool(scene).items();
        } finally {
            Platforms.init(suitePlatform, suiteGl);
        }
    }

    private static double distanceToBend(Item item) {
        float[] point = item.points.get(RingRegionTool.CREASE_POINT);
        double dx = point[0] - BEND_INNER_POINT[0];
        double dy = point[1] - BEND_INNER_POINT[1];
        double dz = point[2] - BEND_INNER_POINT[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * A tube of radius one along z, bent a quarter turn toward +y in its middle and straight on
     * along y, each end capped by a fan; side vertex {@code row * SEGMENTS_AROUND + segment}.
     */
    private static MeshTopology bentTube() {
        int sideVertices = (ROWS + 1) * SEGMENTS_AROUND;
        float[] positions = new float[3 * (sideVertices + 2)];
        double[] centre = new double[3];
        double[] across = new double[3];
        for (int row = 0; row <= ROWS; row++) {
            double along = TUBE_LENGTH * row / ROWS;
            // Centre line and the in-plane section axis; the other axis is always x.
            if (along <= STRAIGHT_LENGTH) {
                centre[1] = 0.0;
                centre[2] = along;
                across[1] = 1.0;
                across[2] = 0.0;
            } else if (along <= STRAIGHT_LENGTH + BEND_LENGTH) {
                double angle = (along - STRAIGHT_LENGTH) / BEND_RADIUS;
                centre[1] = BEND_RADIUS * (1 - Math.cos(angle));
                centre[2] = STRAIGHT_LENGTH + BEND_RADIUS * Math.sin(angle);
                across[1] = Math.cos(angle);
                across[2] = -Math.sin(angle);
            } else {
                centre[1] = BEND_RADIUS + along - STRAIGHT_LENGTH - BEND_LENGTH;
                centre[2] = STRAIGHT_LENGTH + BEND_RADIUS;
                across[1] = 0.0;
                across[2] = -1.0;
            }
            for (int segment = 0; segment < SEGMENTS_AROUND; segment++) {
                double angle = 2 * Math.PI * segment / SEGMENTS_AROUND;
                int vertex = row * SEGMENTS_AROUND + segment;
                positions[3 * vertex] = (float) (TUBE_RADIUS * Math.cos(angle));
                positions[3 * vertex + 1] = (float) (centre[1]
                        + TUBE_RADIUS * Math.sin(angle) * across[1]);
                positions[3 * vertex + 2] = (float) (centre[2]
                        + TUBE_RADIUS * Math.sin(angle) * across[2]);
            }
            if (row == ROWS) {
                positions[3 * (sideVertices + 1) + 1] = (float) centre[1];
                positions[3 * (sideVertices + 1) + 2] = (float) centre[2];
            }
        }
        int bottomCentre = sideVertices;
        int topCentre = sideVertices + 1;
        int topRow = ROWS * SEGMENTS_AROUND;
        int[] triangles = new int[3 * 2 * (ROWS + 1) * SEGMENTS_AROUND];
        int cursor = 0;
        for (int row = 0; row < ROWS; row++) {
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
        return HalfEdgeMeshOps.buildFromIndexedMesh(positions, triangles);
    }

    private static int put(int[] triangles, int cursor, int first, int second, int third) {
        triangles[cursor] = first;
        triangles[cursor + 1] = second;
        triangles[cursor + 2] = third;
        return cursor + 3;
    }

    /** One edge-id-indexed mask per ring, each the loop of edges round its vertex row. */
    private static boolean[][] rowMarks(MeshTopology mesh, int[] rows) {
        boolean[][] marks = new boolean[rows.length][RingBundle.edgeIdCeiling(mesh)];
        for (int ring = 0; ring < rows.length; ring++) {
            int rowStart = rows[ring] * SEGMENTS_AROUND;
            for (int segment = 0; segment < SEGMENTS_AROUND; segment++) {
                int edgeId = mesh.edgeBetween(mesh.vertexIdAt(rowStart + segment),
                        mesh.vertexIdAt(rowStart + (segment + 1) % SEGMENTS_AROUND));
                assertTrue(edgeId != MeshTopology.NONE);
                marks[ring][edgeId] = true;
            }
        }
        return marks;
    }
}
