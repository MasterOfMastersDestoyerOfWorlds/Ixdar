package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.BranchCrossSectionRing;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshSkeletonExtractor.SkeletonBranch;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.RingCandidateExtractor;
import ixdar.geometry.mesh.data.RingCandidates;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.ArrayMeshEngine;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.primitives.IcosphereMeshNode;

/**
 * Ring proposal on procedurally built surfaces: a three-legged Y gives one ring per leg base, and a
 * dumbbell's waist is found by cutting its branch rather than by a region boundary.
 */
class RingCandidatesTest {

    /** Radius of each leg of the Y, so a leg's girth is {@code 2 * pi * LEG_RADIUS}. */
    private static final float LEG_RADIUS = 0.12f;

    /** Radius of the Y's central hub, wide enough to make each leg base a neck. */
    private static final float HUB_RADIUS = 0.30f;

    /** Length of each leg of the Y, measured from the hub centre. */
    private static final float LEG_LENGTH = 1.0f;

    /** Radius of each of the dumbbell's two balls. */
    private static final float BALL_RADIUS = 0.35f;

    /** Radius of the dumbbell's waist. */
    private static final float WAIST_RADIUS = 0.10f;

    /** Distance of each dumbbell ball's centre from the origin, along x. */
    private static final float BALL_OFFSET = 0.6f;

    /** Icosphere subdivision rounds the star-shaped fixtures are displaced from. */
    private static final int SUBDIVISIONS = 5;

    /** Voxel resolution the fixtures' skeletons are extracted at. */
    private static final int RESOLUTION = 96;

    /** Coordinates per point in a packed position array. */
    private static final int COORDINATES_PER_POINT = 3;

    /**
     * How far from 1 a plain tube's cross-section may score. The slack is the voxel grid's: the
     * distance from boundary is quantized, so a thin tube's skeleton radius reads a little short.
     */
    private static final float PLAIN_TUBE_TOLERANCE = 0.4f;

    /** Directions the Y's three legs point in, at 120 degrees in the xy plane. */
    private static final float[][] LEG_DIRECTIONS = {
        { 0f, 1f, 0f },
        { 0.8660254f, -0.5f, 0f },
        { -0.8660254f, -0.5f, 0f },
    };

    @Test
    void theYGivesOneRingPerLegBase() {
        MeshTopology mesh = yJunction();
        RingCandidateExtractor extractor = new RingCandidateExtractor();
        extractor.resolution = RESOLUTION;

        RingCandidates rings = extractor.extract(mesh);

        System.out.println("[y] branches=" + extractor.skeleton.branches().size()
                + " regions=" + extractor.regionCount
                + " boundaries=" + rings.boundariesExamined + " rings=" + rings.ringCount
                + " rejected grown/collapsed/merged/threshold/unclosed=" + rings.rejectedGrown + "/"
                + rings.rejectedCollapsed + "/" + rings.rejectedMerged + "/"
                + rings.rejectedBelowThreshold + "/" + rings.rejectedUnclosed + "/"
                + rings.rejectedNotGirdling);
        for (int ring = 0; ring < rings.ringCount; ring++) {
            System.out.printf("[y] ring %d length=%.4f neckness=%.3f skeletonRadius=%.4f "
                    + "centroid=%.3f,%.3f,%.3f branches=%d|%d regions=%d|%d%n", ring,
                    rings.length[ring], rings.neckness[ring], rings.skeletonRadius[ring],
                    rings.centroid[COORDINATES_PER_POINT * ring],
                    rings.centroid[COORDINATES_PER_POINT * ring + 1],
                    rings.centroid[COORDINATES_PER_POINT * ring + 2],
                    rings.branchOnOneSide[ring], rings.branchOnOtherSide[ring],
                    rings.regionOnOneSide[ring], rings.regionOnOtherSide[ring]);
        }

        assertEquals(LEG_DIRECTIONS.length, rings.ringCount,
                "the Y should propose one ring per leg base");
        double legGirth = 2.0 * Math.PI * LEG_RADIUS;
        for (int ring = 0; ring < rings.ringCount; ring++) {
            assertTrue(rings.neckness[ring] >= RingCandidateExtractor.DEFAULT_MINIMUM_NECKNESS,
                    "ring " + ring + " scored " + rings.neckness[ring] + ", below the threshold");
            assertTrue(rings.length[ring] < 2.0 * legGirth,
                    "ring " + ring + " is " + rings.length[ring] + " long, more than twice the "
                            + legGirth + " girth of a leg");
        }
        for (float[] direction : LEG_DIRECTIONS) {
            assertTrue(ringOnLeg(rings, direction) >= 0,
                    "no ring sits on the leg pointing at " + direction[0] + ", " + direction[1]);
        }
    }

    @Test
    void everyAcceptedRingIsOneClosedCycle() {
        MeshTopology mesh = yJunction();
        RingCandidateExtractor extractor = new RingCandidateExtractor();
        extractor.resolution = RESOLUTION;

        RingCandidates rings = extractor.extract(mesh);

        assertTrue(rings.ringCount > 0, "the Y proposed no ring to check the loop shape of");
        for (int ring = 0; ring < rings.ringCount; ring++) {
            int[] edgeIds = rings.edgeIdsOf(ring);
            assertEquals(edgeIds.length, cycleWalkLength(mesh, edgeIds),
                    "ring " + ring + "'s " + edgeIds.length + " marked edges are not one closed "
                            + "cycle; a loop that doubled back marks a path, not a girdle");
            int points = rings.ringPointCount(ring);
            assertTrue(points > 1, "ring " + ring + " has a polyline of " + points + " points");
            int first = COORDINATES_PER_POINT * rings.polylineOffset[ring];
            int last = COORDINATES_PER_POINT * (rings.polylineOffset[ring] + points - 1);
            for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
                assertEquals(rings.polylinePoint[first + axis], rings.polylinePoint[last + axis],
                        0f, "ring " + ring + "'s polyline does not close on its first point");
            }
        }
    }

    @Test
    void theYRingsNameBranchesTheSkeletonReports() {
        MeshTopology mesh = yJunction();
        RingCandidateExtractor extractor = new RingCandidateExtractor();
        extractor.resolution = RESOLUTION;

        RingCandidates rings = extractor.extract(mesh);

        Set<Integer> reportedBranchIds = new HashSet<>();
        for (SkeletonBranch branch : extractor.skeleton.branches()) {
            reportedBranchIds.add(branch.id());
        }
        assertTrue(rings.ringCount > 0, "the Y proposed no ring to check the branch ids of");
        for (int ring = 0; ring < rings.ringCount; ring++) {
            assertTrue(reportedBranchIds.contains(rings.branchOnOneSide[ring]),
                    "ring " + ring + " names branch " + rings.branchOnOneSide[ring]
                            + " on one side, which is not one of the " + reportedBranchIds.size()
                            + " ids the skeleton reports");
            assertTrue(reportedBranchIds.contains(rings.branchOnOtherSide[ring]),
                    "ring " + ring + " names branch " + rings.branchOnOtherSide[ring]
                            + " on the other side, which the skeleton does not report");
            assertTrue(rings.regionOnOneSide[ring] >= 0
                    && rings.regionOnOneSide[ring] < extractor.regionCount,
                    "ring " + ring + " names region " + rings.regionOnOneSide[ring] + " of "
                            + extractor.regionCount);
            assertEquals(extractor.branchOfRegion[rings.regionOnOneSide[ring]],
                    rings.branchOnOneSide[ring],
                    "ring " + ring + " names a branch its own region was not cut from");
        }
        assertTrue(extractor.regionCount >= extractor.skeleton.branches().size(),
                "cutting branches at their junctions cannot leave fewer regions than branches");
    }

    @Test
    void aWaistScoresHigherNecknessThanAPlainTube() {
        BranchCrossSectionRing waistCutter = new BranchCrossSectionRing();
        waistCutter.resolution = RESOLUTION;
        BranchCrossSectionRing tubeCutter = new BranchCrossSectionRing();
        tubeCutter.resolution = RESOLUTION;

        RingCandidates waist = waistCutter.extract(dumbbell(), new float[] { 0f, 0f, 0f }, 0.5f);
        RingCandidates tube = tubeCutter.extract(plainTube(), new float[] { 0f, 0f, 0f }, 0.5f);

        assertEquals(1, waist.ringCount, "the dumbbell's waist cut produced no ring to score");
        assertEquals(1, tube.ringCount, "the plain tube's cut produced no ring to score");
        System.out.printf("[neckness] waist length=%.4f skeletonRadius=%.4f neckness=%.3f; "
                + "tube length=%.4f skeletonRadius=%.4f neckness=%.3f%n", waist.length[0],
                waist.skeletonRadius[0], waist.neckness[0], tube.length[0], tube.skeletonRadius[0],
                tube.neckness[0]);

        assertTrue(Math.abs(tube.neckness[0] - 1f) < PLAIN_TUBE_TOLERANCE,
                "a plain tube's cross-section scored " + tube.neckness[0]
                        + ", not the 1 a loop matching the skeleton's local circumference scores");
        assertTrue(waist.neckness[0] > tube.neckness[0] + PLAIN_TUBE_TOLERANCE,
                "the waist scored " + waist.neckness[0] + ", no more constricted than the plain "
                        + "tube's " + tube.neckness[0]);
    }

    @Test
    void theYRankingIsStableAcrossRuns() {
        MeshTopology mesh = yJunction();
        RingCandidateExtractor first = new RingCandidateExtractor();
        first.resolution = RESOLUTION;
        RingCandidateExtractor second = new RingCandidateExtractor();
        second.resolution = RESOLUTION;

        RingCandidates left = first.extract(mesh);
        RingCandidates right = second.extract(mesh);

        assertEquals(left.ringCount, right.ringCount, "the two runs proposed different ring counts");
        assertArrayEquals(left.neckness, right.neckness, 0f, "the two runs ranked differently");
        assertArrayEquals(left.centroid, right.centroid, 0f, "the two runs placed rings apart");
        assertArrayEquals(left.markedEdgeId, right.markedEdgeId, "the two runs marked other edges");
    }

    @Test
    void theDumbbellWaistIsFoundByCuttingItsBranch() {
        MeshTopology mesh = dumbbell();
        BranchCrossSectionRing cutter = new BranchCrossSectionRing();
        cutter.resolution = RESOLUTION;

        RingCandidates rings = cutter.extract(mesh, new float[] { 0f, 0f, 0f }, 0.5f);

        System.out.printf("[dumbbell] branches=%d plane=%.3f,%.3f,%.3f radius=%.4f rings=%d%n",
                cutter.skeleton.branches().size(), cutter.planePoint[0], cutter.planePoint[1],
                cutter.planePoint[2], cutter.localSkeletonRadius, rings.ringCount);
        assertEquals(1, rings.ringCount, "cutting the dumbbell's branch produced no ring");
        System.out.printf("[dumbbell] ring length=%.4f planar=%.4f tightened=%b neckness=%.3f "
                + "centroid=%.3f,%.3f,%.3f edges=%d%n", rings.length[0], rings.seedLength[0],
                cutter.tightened, rings.neckness[0], rings.centroid[0], rings.centroid[1],
                rings.centroid[2], rings.markedEdgeOffset[1]);
        if (cutter.tightenedRing != null) {
            System.out.printf("[dumbbell] tightening seed=%.4f tight=%.4f gaps=%d edges=%d%n",
                    cutter.tightenedRing.seedLength, cutter.tightenedRing.length,
                    cutter.tightenedRing.unresolvedGaps, cutter.tightenedRing.markedEdgeIds.length);
        }
        double waistGirth = 2.0 * Math.PI * WAIST_RADIUS;
        assertTrue(Math.abs(rings.length[0] - waistGirth) < 0.25 * waistGirth,
                "the tightened ring is " + rings.length[0] + ", not the waist girth " + waistGirth);
        assertTrue(Math.abs(rings.centroid[0]) < BALL_OFFSET / 2f,
                "the ring settled at x=" + rings.centroid[0] + ", away from the waist at x=0");
    }

    @Test
    void aPlainTubeProposesNoRing() {
        MeshTopology mesh = dumbbell();
        RingCandidateExtractor extractor = new RingCandidateExtractor();
        extractor.resolution = RESOLUTION;

        RingCandidates rings = extractor.extract(mesh);

        System.out.println("[dumbbell] branches=" + extractor.skeleton.branches().size()
                + " regions=" + extractor.regionCount + " boundaries=" + rings.boundariesExamined + " rings=" + rings.ringCount);
        assertEquals(0, rings.ringCount,
                "an unbranched surface has no region boundary, so it can propose no ring");
    }

    /**
     * Index of the ring whose centroid lies along {@code direction} from the origin, within the
     * hub radius of the leg's axis.
     */
    private static int ringOnLeg(RingCandidates rings, float[] direction) {
        for (int ring = 0; ring < rings.ringCount; ring++) {
            float x = rings.centroid[COORDINATES_PER_POINT * ring];
            float y = rings.centroid[COORDINATES_PER_POINT * ring + 1];
            float z = rings.centroid[COORDINATES_PER_POINT * ring + 2];
            float along = x * direction[0] + y * direction[1] + z * direction[2];
            float acrossX = x - along * direction[0];
            float acrossY = y - along * direction[1];
            float acrossZ = z - along * direction[2];
            float across = (float) Math.sqrt(
                    acrossX * acrossX + acrossY * acrossY + acrossZ * acrossZ);
            if (along > 0f && across < HUB_RADIUS) {
                return ring;
            }
        }
        return -1;
    }

    /**
     * Edges a walk crosses before it returns to where it started, stepping through each vertex
     * onto its other marked edge, or -1 when some vertex does not carry exactly two. Equal to the
     * edge count only for one simple closed cycle.
     */
    private static int cycleWalkLength(MeshTopology mesh, int[] edgeIds) {
        Map<Integer, List<Integer>> neighbors = new HashMap<>();
        for (int edgeId : edgeIds) {
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            int tail = mesh.halfEdgeVertex(halfEdge);
            int head = mesh.halfEdgeEndVertex(halfEdge);
            neighbors.computeIfAbsent(tail, key -> new ArrayList<>()).add(head);
            neighbors.computeIfAbsent(head, key -> new ArrayList<>()).add(tail);
        }
        for (List<Integer> spokes : neighbors.values()) {
            if (spokes.size() != RingCandidates.CYCLE_VERTEX_DEGREE) {
                return -1;
            }
        }
        if (edgeIds.length == 0) {
            return -1;
        }
        int start = mesh.halfEdgeVertex(mesh.edgeHalfEdge(edgeIds[0]));
        int previous = start;
        int current = neighbors.get(start).get(0);
        int steps = 1;
        while (current != start && steps <= edgeIds.length) {
            List<Integer> spokes = neighbors.get(current);
            int next = spokes.get(0) == previous ? spokes.get(1) : spokes.get(0);
            previous = current;
            current = next;
            steps++;
        }
        return current == start ? steps : -1;
    }

    /** Three legs of one radius around a fatter hub, so every leg base is a constriction. */
    private static MeshTopology yJunction() {
        float[] segments = new float[LEG_DIRECTIONS.length * 2 * COORDINATES_PER_POINT
                + 2 * COORDINATES_PER_POINT];
        float[] radii = new float[LEG_DIRECTIONS.length + 1];
        int next = 0;
        for (float[] direction : LEG_DIRECTIONS) {
            for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
                segments[next + axis] = 0f;
                segments[next + COORDINATES_PER_POINT + axis] = LEG_LENGTH * direction[axis];
            }
            radii[next / (2 * COORDINATES_PER_POINT)] = LEG_RADIUS;
            next += 2 * COORDINATES_PER_POINT;
        }
        radii[LEG_DIRECTIONS.length] = HUB_RADIUS;
        return starShaped(segments, radii);
    }

    /** Two balls joined by a thin waist, the classic single-constriction surface. */
    private static MeshTopology dumbbell() {
        float[] segments = {
            -BALL_OFFSET, 0f, 0f, -BALL_OFFSET, 0f, 0f,
            BALL_OFFSET, 0f, 0f, BALL_OFFSET, 0f, 0f,
            -BALL_OFFSET, 0f, 0f, BALL_OFFSET, 0f, 0f,
        };
        float[] radii = { BALL_RADIUS, BALL_RADIUS, WAIST_RADIUS };
        return starShaped(segments, radii);
    }

    /** One capsule of uniform radius, so every cross-section of it is a plain tube section. */
    private static MeshTopology plainTube() {
        float[] segments = { -BALL_OFFSET, 0f, 0f, BALL_OFFSET, 0f, 0f };
        float[] radii = { WAIST_RADIUS };
        return starShaped(segments, radii);
    }

    /**
     * An icosphere pushed out onto the boundary of a union of capsules, which stays a closed
     * manifold because that union is star-shaped about the origin.
     *
     * @param segments packed pairs of capsule end points, six coordinates per capsule
     * @param radii    radius of each capsule
     */
    private static MeshTopology starShaped(float[] segments, float[] radii) {
        IcosphereMeshNode node = new IcosphereMeshNode();
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput("radius", 1.0f);
        ctx.setInput("subdivisions", SUBDIVISIONS);
        node.evaluate(ctx);
        ArrayMesh sphere = ArrayMeshEngine.fromUniformMeshTopology(
                ctx.getOutput("mesh", GeometryBundle.class).mesh());
        float[] positions = sphere.copyPositions();
        for (int vertex = 0; vertex * COORDINATES_PER_POINT < positions.length; vertex++) {
            int base = COORDINATES_PER_POINT * vertex;
            float length = (float) Math.sqrt(positions[base] * positions[base]
                    + positions[base + 1] * positions[base + 1]
                    + positions[base + 2] * positions[base + 2]);
            float directionX = positions[base] / length;
            float directionY = positions[base + 1] / length;
            float directionZ = positions[base + 2] / length;
            float reach = surfaceReach(segments, radii, directionX, directionY, directionZ);
            positions[base] = reach * directionX;
            positions[base + 1] = reach * directionY;
            positions[base + 2] = reach * directionZ;
        }
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(positions,
                sphere.copyFaceIndices());
        mesh.computeNormals();
        return mesh;
    }

    /** Distance from the origin to the union's boundary along a unit direction, by bisection. */
    private static float surfaceReach(float[] segments, float[] radii, float directionX,
            float directionY, float directionZ) {
        float inside = 0f;
        float outside = 8f;
        for (int step = 0; step < 40; step++) {
            float middle = 0.5f * (inside + outside);
            if (unionDistance(segments, radii, middle * directionX, middle * directionY,
                    middle * directionZ) <= 0f) {
                inside = middle;
            } else {
                outside = middle;
            }
        }
        return inside;
    }

    /** Signed distance to the union of the capsules: negative inside, zero on the surface. */
    private static float unionDistance(float[] segments, float[] radii, float x, float y, float z) {
        float best = Float.MAX_VALUE;
        for (int capsule = 0; capsule < radii.length; capsule++) {
            int base = 2 * COORDINATES_PER_POINT * capsule;
            float ax = segments[base];
            float ay = segments[base + 1];
            float az = segments[base + 2];
            float spanX = segments[base + COORDINATES_PER_POINT] - ax;
            float spanY = segments[base + COORDINATES_PER_POINT + 1] - ay;
            float spanZ = segments[base + COORDINATES_PER_POINT + 2] - az;
            float spanSquared = spanX * spanX + spanY * spanY + spanZ * spanZ;
            float along = spanSquared <= 0f ? 0f
                    : ((x - ax) * spanX + (y - ay) * spanY + (z - az) * spanZ) / spanSquared;
            along = Math.max(0f, Math.min(1f, along));
            float dx = x - (ax + along * spanX);
            float dy = y - (ay + along * spanY);
            float dz = z - (az + along * spanZ);
            best = Math.min(best,
                    (float) Math.sqrt(dx * dx + dy * dy + dz * dz) - radii[capsule]);
        }
        return best;
    }
}
