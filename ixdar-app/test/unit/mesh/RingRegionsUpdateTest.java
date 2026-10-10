package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.RingBundle;
import ixdar.geometry.mesh.data.RingRegions;
import ixdar.scenes.regions.RegionColouring;

/**
 * Incremental ring region updates on the capped cylinder of {@link RingRegionsTest}: after every
 * edit of a sequence the updated regions equal a fresh build and an independent flood, and the
 * region colours keep neighbours apart and stay put where an edit did not reach.
 */
class RingRegionsUpdateTest {

    private static final int AROUND = RingRegionsTest.SEGMENTS_AROUND;

    private static final int ROWS = RingRegionsTest.SIDE_ROWS;

    private static final int BOTTOM_CENTRE = (ROWS + 1) * AROUND;

    private static final int TOP_CENTRE = BOTTOM_CENTRE + 1;

    private static final int MERIDIAN_SEGMENT = AROUND / 2;

    private static final String MERIDIAN = "meridian";

    private static final String ROW_PREFIX = "row_";

    private static final int ABSORB_BELOW = 30;

    @Test
    void everyEditLeavesWhatAFreshBuildOfTheNewRingsHolds() {
        MeshTopology cylinder = RingRegionsTest.cappedCylinder();
        List<String> labels = new ArrayList<>(List.of(row(2), row(4), row(6)));
        List<boolean[]> masks = new ArrayList<>(List.of(rowMask(cylinder, 2, false),
                rowMask(cylinder, 4, false), rowMask(cylinder, 6, false)));
        RingRegions regions = new RingRegions(cylinder, labels.toArray(new String[0]),
                masks.toArray(new boolean[0][])).build();
        assertEquals(cylinder.faceCount(), regions.refloodedFaces);
        assertMatchesFreshBuild(regions, labels, masks, 0);

        // Add a ring above the others: only the top region is flooded again.
        int topRegion = regions.regionByActiveFace[regions.regionByActiveFace.length - 1];
        int topFaces = regions.regionFaceCount[topRegion];
        labels.add(row(7));
        masks.add(rowMask(cylinder, 7, false));
        update(regions, labels, masks);
        assertEquals(topFaces, regions.refloodedFaces);
        assertMatchesFreshBuild(regions, labels, masks, 0);

        // Move a ring: the same label with a new mask.
        masks.set(1, rowMask(cylinder, 3, false));
        update(regions, labels, masks);
        assertMatchesFreshBuild(regions, labels, masks, 0);

        // A ring crossing every other, then a duplicate of one, an open one, and deletions.
        labels.add(MERIDIAN);
        masks.add(meridianMask(cylinder));
        update(regions, labels, masks);
        assertMatchesFreshBuild(regions, labels, masks, 0);
        labels.add(row(6) + "_again");
        masks.add(rowMask(cylinder, 6, false));
        labels.add(row(5));
        masks.add(rowMask(cylinder, 5, true));
        update(regions, labels, masks);
        assertMatchesFreshBuild(regions, labels, masks, 0);
        labels.remove(0);
        masks.remove(0);
        update(regions, labels, masks);
        assertMatchesFreshBuild(regions, labels, masks, 0);
        int meridian = labels.indexOf(MERIDIAN);
        labels.remove(meridian);
        masks.remove(meridian);
        update(regions, labels, masks);
        assertMatchesFreshBuild(regions, labels, masks, 0);

        // Nothing changed: nothing is flooded.
        update(regions, labels, masks);
        assertEquals(0, regions.refloodedFaces);

        // Absorbing slivers merges each small region into its largest neighbour.
        regions.absorbBelowFaces = ABSORB_BELOW;
        regions.build();
        labels.add(row(1));
        masks.add(rowMask(cylinder, 1, false));
        update(regions, labels, masks);
        assertMatchesFreshBuild(regions, labels, masks, ABSORB_BELOW);
        for (int region = 0; region < regions.regionCount; region++) {
            assertTrue(regions.regionFaceCount[region] >= ABSORB_BELOW
                    || regions.neighboursByRegion[region].length == 0);
        }
    }

    @Test
    void neighboursGetDistinctColoursThatStayPutAwayFromAnEdit() {
        for (int first = 0; first < RegionColouring.PALETTE.length; first++) {
            for (int second = first + 1; second < RegionColouring.PALETTE.length; second++) {
                assertTrue(distance(RegionColouring.paletteColor(first).toVector4f(),
                        RegionColouring.paletteColor(second).toVector4f())
                        >= RegionColouring.MINIMUM_PALETTE_DISTANCE, first + " vs " + second);
            }
        }
        MeshTopology cylinder = RingRegionsTest.cappedCylinder();
        List<String> labels = new ArrayList<>(List.of(row(2), row(4), row(6), MERIDIAN));
        List<boolean[]> masks = new ArrayList<>(List.of(rowMask(cylinder, 2, false),
                rowMask(cylinder, 4, false), rowMask(cylinder, 6, false),
                meridianMask(cylinder)));
        RingRegions regions = new RingRegions(cylinder, labels.toArray(new String[0]),
                masks.toArray(new boolean[0][])).build();
        RegionColouring colouring = new RegionColouring();
        colouring.colour(regions);
        assertEquals(2 * labels.size(), regions.regionCount);
        assertNeighboursDiffer(regions, colouring);

        int[] before = colouring.colourByRegion.clone();
        labels.add(row(7));
        masks.add(rowMask(cylinder, 7, false));
        update(regions, labels, masks);
        colouring.colour(regions);
        assertNeighboursDiffer(regions, colouring);
        int kept = 0;
        for (int region = 0; region < regions.regionCount; region++) {
            if (regions.regionKeptFaces[region]) {
                kept++;
                assertEquals(before[regions.formerRegionByRegion[region]],
                        colouring.colourByRegion[region]);
            }
        }
        assertEquals(2 * (labels.size() - 2), kept);
    }

    /**
     * Asserts the updated regions equal a fresh build of the same rings field by field, and that
     * their partition is the flood a plain breadth-first search between the walls gives.
     */
    private static void assertMatchesFreshBuild(RingRegions updated, List<String> labels,
            List<boolean[]> masks, int absorbBelow) {
        RingRegions fresh = new RingRegions(updated.mesh, labels.toArray(new String[0]),
                masks.toArray(new boolean[0][]));
        fresh.absorbBelowFaces = absorbBelow;
        fresh.build();
        String context = String.join("\n", updated.reportLines());
        assertEquals(fresh.regionCount, updated.regionCount, context);
        assertArrayEquals(fresh.regionByActiveFace, updated.regionByActiveFace, context);
        assertArrayEquals(fresh.regionFaceCount, updated.regionFaceCount);
        assertArrayEquals(fresh.regionArea, updated.regionArea, 0.0);
        assertArrayEquals(fresh.ringIsWall, updated.ringIsWall);
        assertArrayEquals(fresh.ringSeparates, updated.ringSeparates);
        assertArrayEquals(fresh.ringSplitsNothing, updated.ringSplitsNothing);
        assertArrayEquals(fresh.ringEdgeCount, updated.ringEdgeCount);
        assertEquals(fresh.problems, updated.problems);
        assertEquals(Arrays.deepToString(fresh.boundingRingsByRegion),
                Arrays.deepToString(updated.boundingRingsByRegion));
        assertEquals(Arrays.deepToString(fresh.neighboursByRegion),
                Arrays.deepToString(updated.neighboursByRegion));
        assertEquals(Arrays.deepToString(fresh.sideByRingRegion),
                Arrays.deepToString(updated.sideByRingRegion));
        assertEquals(fresh.reportLines(), updated.reportLines());
        if (absorbBelow > 0) {
            return;
        }
        // The flood the closed rings wall, by a plain breadth-first search over shared edges,
        // each component numbered by its lowest face.
        MeshTopology mesh = updated.mesh;
        boolean[] wall = new boolean[RingBundle.edgeIdCeiling(mesh)];
        for (int ring = 0; ring < updated.ringLabels.length; ring++) {
            boolean[] marks = updated.ringMarksByEdgeId[ring];
            for (int edgeId = 0; updated.ringIsWall[ring] && edgeId < wall.length; edgeId++) {
                wall[edgeId] |= edgeId < marks.length && marks[edgeId];
            }
        }
        int faces = mesh.faceCount();
        Map<Integer, Integer> activeFaceById = new HashMap<>();
        for (int activeFace = 0; activeFace < faces; activeFace++) {
            activeFaceById.put(mesh.faceIdAt(activeFace), activeFace);
        }
        int[] component = new int[faces];
        Arrays.fill(component, -1);
        int components = 0;
        for (int seed = 0; seed < faces; seed++) {
            if (component[seed] >= 0) {
                continue;
            }
            ArrayDeque<Integer> frontier = new ArrayDeque<>(List.of(seed));
            component[seed] = components;
            while (!frontier.isEmpty()) {
                int faceId = mesh.faceIdAt(frontier.poll());
                for (int slot = 0; slot < mesh.faceHalfEdgeCount(faceId); slot++) {
                    int halfEdge = mesh.faceHalfEdgeAt(faceId, slot);
                    int twin = mesh.halfEdgeTwin(halfEdge);
                    if (wall[mesh.halfEdgeEdge(halfEdge)] || twin == MeshTopology.NONE) {
                        continue;
                    }
                    int neighbour = activeFaceById.get(mesh.halfEdgeFace(twin));
                    if (component[neighbour] < 0) {
                        component[neighbour] = components;
                        frontier.add(neighbour);
                    }
                }
            }
            components++;
        }
        assertArrayEquals(component, updated.regionByActiveFace, context);
    }

    /** Every region's neighbours take other colours, which the palette keeps clearly apart. */
    private static void assertNeighboursDiffer(RingRegions regions, RegionColouring colouring) {
        for (int region = 0; region < regions.regionCount; region++) {
            for (int neighbour : regions.neighboursByRegion[region]) {
                int colour = colouring.colourByRegion[region];
                int other = colouring.colourByRegion[neighbour];
                assertNotEquals(colour, other, region + " and " + neighbour);
                assertTrue(distance(RegionColouring.paletteColor(colour).toVector4f(),
                        RegionColouring.paletteColor(other).toVector4f())
                        >= RegionColouring.MINIMUM_PALETTE_DISTANCE);
            }
        }
    }

    private static void update(RingRegions regions, List<String> labels, List<boolean[]> masks) {
        regions.update(labels.toArray(new String[0]), masks.toArray(new boolean[0][]));
    }

    private static String row(int row) {
        return ROW_PREFIX + row;
    }

    /**
     * The loop of edges around one vertex row of the cylinder.
     *
     * @param open leave the last edge out, so the ring does not close
     */
    static boolean[] rowMask(MeshTopology mesh, int row, boolean open) {
        boolean[] mask = new boolean[RingBundle.edgeIdCeiling(mesh)];
        int segments = open ? AROUND - 1 : AROUND;
        for (int segment = 0; segment < segments; segment++) {
            mark(mesh, mask, row * AROUND + segment, row * AROUND + (segment + 1) % AROUND);
        }
        return mask;
    }

    /**
     * A loop up the cylinder along segment 0, across the top cap through its centre, down along
     * the opposite segment and back across the bottom cap: it crosses every row ring.
     */
    private static boolean[] meridianMask(MeshTopology mesh) {
        boolean[] mask = new boolean[RingBundle.edgeIdCeiling(mesh)];
        for (int segment : new int[] { 0, MERIDIAN_SEGMENT }) {
            for (int row = 0; row < ROWS; row++) {
                mark(mesh, mask, row * AROUND + segment, (row + 1) * AROUND + segment);
            }
            mark(mesh, mask, ROWS * AROUND + segment, TOP_CENTRE);
            mark(mesh, mask, segment, BOTTOM_CENTRE);
        }
        return mask;
    }

    private static void mark(MeshTopology mesh, boolean[] mask, int from, int to) {
        int edgeId = mesh.edgeBetween(mesh.vertexIdAt(from), mesh.vertexIdAt(to));
        assertTrue(edgeId != MeshTopology.NONE, from + "-" + to);
        mask[edgeId] = true;
    }

    private static double distance(Vector4f first, Vector4f second) {
        double red = first.x - second.x;
        double green = first.y - second.y;
        double blue = first.z - second.z;
        return Math.sqrt(red * red + green * green + blue * blue);
    }
}
