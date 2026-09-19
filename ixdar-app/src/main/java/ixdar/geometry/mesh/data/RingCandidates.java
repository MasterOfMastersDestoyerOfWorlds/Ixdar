package ixdar.geometry.mesh.data;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import ixdar.geometry.mesh.data.MeshSkeletonExtractor.SkeletonBranch;
import ixdar.geometry.mesh.data.MeshSkeletonExtractor.SkeletonJoint;
import ixdar.geometry.mesh.data.MeshSkeletonExtractor.SkeletonResult;

/**
 * Neck loops proposed on a surface, one entry per ring in every array: the tightened polyline, the
 * mesh edges nearest it, and the measurements that rank it.
 *
 * <p>
 * Rows are sorted by descending {@link #neckness}, so ring order is the ranking and two runs agree.
 */
public final class RingCandidates {

    /** Bundle slot the ring nodes read and write these candidates through. */
    public static final String SLOT = "_rings";

    /** Prefix of the per-ring edge-marks label, completed by the two-digit ring number. */
    public static final String MARK_LABEL_PREFIX = "ring_";

    /** Coordinates per point, and the stride of every packed position array here. */
    public static final int COORDINATES_PER_POINT = 3;

    /** Marked edges a vertex of a simple closed cycle carries: one arriving and one leaving. */
    public static final int CYCLE_VERTEX_DEGREE = 2;

    /** Number of rings; every array below holds this many entries (or a multiple of it). */
    public int ringCount;

    /** First point index of each ring in {@link #polylinePoint}; length {@code ringCount + 1}. */
    public int[] polylineOffset = new int[] { 0 };

    /** Packed xyz of every ring's tightened loop, in travel order, first point repeated to close. */
    public float[] polylinePoint = new float[0];

    /** First entry of each ring in {@link #markedEdgeId}; length {@code ringCount + 1}. */
    public int[] markedEdgeOffset = new int[] { 0 };

    /** Ascending mesh edge ids of the conforming edge cycle nearest each tightened loop. */
    public int[] markedEdgeId = new int[0];

    /** Packed xyz of each ring's centroid, the mean of its polyline points. */
    public float[] centroid = new float[0];

    /** Length of each tightened loop, in world units. */
    public float[] length = new float[0];

    /** Length of the seed the loop was tightened from, always at least {@link #length}. */
    public float[] seedLength = new float[0];

    /** Mean distance from a ring's centroid to its polyline points. */
    public float[] meanRadius = new float[0];

    /** Id of the skeleton branch the vertex region on one side of each ring belongs to. */
    public int[] branchOnOneSide = new int[0];

    /** Id of the skeleton branch the vertex region on the other side of each ring belongs to. */
    public int[] branchOnOtherSide = new int[0];

    /**
     * Vertex-region index on one side of each ring, or -1 when the producer grew no regions. One
     * branch is cut into several regions at its junctions, so this is finer than the branch id.
     */
    public int[] regionOnOneSide = new int[0];

    /** Vertex-region index on the other side of each ring, or -1 when no regions were grown. */
    public int[] regionOnOtherSide = new int[0];

    /** Surface area of the region on {@link #regionOnOneSide}, in world units squared. */
    public float[] areaOnOneSide = new float[0];

    /** Surface area of the region on {@link #regionOnOtherSide}. */
    public float[] areaOnOtherSide = new float[0];

    /** Radius of the skeleton joint nearest each ring, the TEASAR distance from boundary there. */
    public float[] skeletonRadius = new float[0];

    /** Skeleton circumference over loop length: 1 girdles a plain tube, more at a constriction. */
    public float[] neckness = new float[0];

    /** Region boundaries examined, whether or not they produced an accepted ring. */
    public int boundariesExamined;

    /** Candidates dropped because the tightened loop was longer than the seed walk. */
    public int rejectedGrown;

    /** Candidates dropped because the tightened loop pinched far inside its inscribed circle. */
    public int rejectedCollapsed;

    /** Candidates dropped because the tightened loop landed on an accepted ring's edges. */
    public int rejectedMerged;

    /** Candidates dropped because their neckness fell below the caller's threshold. */
    public int rejectedBelowThreshold;

    /** Candidates dropped because no closed edge cycle could be built for them. */
    public int rejectedUnclosed;

    /**
     * Candidates dropped because their conforming edges were not one simple closed cycle: the
     * tightened path doubled back on itself instead of girdling the limb, so the edges it marks
     * are a tree or several pieces rather than a loop.
     */
    public int rejectedNotGirdling;

    /**
     * Adjacency of a marked edge set, keyed by mesh vertex id, each entry listing the vertices
     * that set joins it to.
     *
     * @param mesh    surface the edge ids belong to
     * @param edgeIds mesh edge ids of one ring's conforming cycle
     * @return the neighbour lists, empty when no edge is marked
     */
    private static Map<Integer, List<Integer>> markedAdjacency(MeshTopology mesh, int[] edgeIds) {
        Map<Integer, List<Integer>> neighbors = new HashMap<>();
        for (int edgeId : edgeIds) {
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            int tail = mesh.halfEdgeVertex(halfEdge);
            int head = mesh.halfEdgeEndVertex(halfEdge);
            if (tail < 0 || head < 0) {
                continue;
            }
            neighbors.computeIfAbsent(tail, key -> new ArrayList<>()).add(head);
            neighbors.computeIfAbsent(head, key -> new ArrayList<>()).add(tail);
        }
        return neighbors;
    }

    /**
     * Connected pieces a marked edge set falls into, read as a graph on the vertices it touches.
     *
     * @param mesh    surface the edge ids belong to
     * @param edgeIds mesh edge ids of one ring's conforming cycle
     * @return the number of connected components, 0 when no edge is marked
     */
    public static int markedEdgeComponents(MeshTopology mesh, int[] edgeIds) {
        Map<Integer, List<Integer>> neighbors = markedAdjacency(mesh, edgeIds);
        Set<Integer> seen = new HashSet<>();
        int components = 0;
        for (int start : neighbors.keySet()) {
            if (!seen.add(start)) {
                continue;
            }
            components++;
            Deque<Integer> pending = new ArrayDeque<>();
            pending.push(start);
            while (!pending.isEmpty()) {
                for (int next : neighbors.get(pending.pop())) {
                    if (seen.add(next)) {
                        pending.push(next);
                    }
                }
            }
        }
        return components;
    }

    /**
     * Whether a marked edge set is one simple closed cycle on the mesh: every vertex it touches
     * carries exactly two of its edges, and the whole set is connected. A loop that doubled back
     * marks each retraced edge once, so it fails on the degree-one ends it leaves behind.
     *
     * @param mesh    surface the edge ids belong to
     * @param edgeIds mesh edge ids of one ring's conforming cycle
     * @return true when the edges girdle the surface as a single closed loop
     */
    public static boolean marksOneClosedCycle(MeshTopology mesh, int[] edgeIds) {
        Map<Integer, List<Integer>> neighbors = markedAdjacency(mesh, edgeIds);
        if (neighbors.isEmpty()) {
            return false;
        }
        for (List<Integer> spokes : neighbors.values()) {
            if (spokes.size() != CYCLE_VERTEX_DEGREE) {
                return false;
            }
        }
        return markedEdgeComponents(mesh, edgeIds) == 1;
    }

    /**
     * Radius of the skeleton joint nearest a point: the TEASAR distance from boundary, which
     * measures the tube the surface encloses rather than the loop drawn on it.
     *
     * @param skeleton skeleton whose joints sample the local thickness
     * @param x        point x
     * @param y        point y
     * @param z        point z
     * @return the nearest joint's radius, or 0 when the skeleton found no joint
     */
    public static float skeletonRadiusNear(SkeletonResult skeleton, float x, float y, float z) {
        float radius = 0f;
        double bestSquared = Double.POSITIVE_INFINITY;
        for (SkeletonBranch branch : skeleton.branches()) {
            for (SkeletonJoint joint : branch.joints()) {
                double dx = joint.position()[0] - x;
                double dy = joint.position()[1] - y;
                double dz = joint.position()[2] - z;
                double squared = dx * dx + dy * dy + dz * dz;
                if (squared < bestSquared) {
                    bestSquared = squared;
                    radius = joint.radius();
                }
            }
        }
        return radius;
    }

    /**
     * The one score both ring producers rank by: how much shorter a loop is than the circumference
     * the skeleton says the surface has there.
     *
     * @param skeletonRadius radius of the skeleton joint nearest the loop
     * @param loopLength     length of the loop, in world units
     * @return the neckness score, 0 for a loop of no length
     */
    public static float neckness(float skeletonRadius, double loopLength) {
        return loopLength <= 0.0 ? 0f : (float) (2.0 * Math.PI * skeletonRadius / loopLength);
    }

    /**
     * Label the ring's edge marks are stored under, zero-padded so sorted label order is ring
     * order.
     *
     * @param ring ring index in {@code [0, ringCount)}
     * @return the edge-marks label, e.g. {@code "ring_03"}
     */
    public String markLabel(int ring) {
        return String.format(Locale.ROOT, MARK_LABEL_PREFIX + "%02d", ring);
    }

    /**
     * Mesh edge ids of one ring's conforming cycle, copied out of the shared packed array.
     *
     * @param ring ring index in {@code [0, ringCount)}
     * @return the ascending edge ids of that ring alone
     */
    public int[] edgeIdsOf(int ring) {
        return Arrays.copyOfRange(markedEdgeId, markedEdgeOffset[ring], markedEdgeOffset[ring + 1]);
    }

    /**
     * Per-edge-id mask of one ring's conforming edge cycle.
     *
     * @param ring          ring index in {@code [0, ringCount)}
     * @param edgeIdCeiling one past the largest mesh edge id, the length of the returned mask
     * @return a fresh mask, true on every edge of that ring's cycle
     */
    public boolean[] edgeMarks(int ring, int edgeIdCeiling) {
        boolean[] marks = new boolean[edgeIdCeiling];
        for (int entry = markedEdgeOffset[ring]; entry < markedEdgeOffset[ring + 1]; entry++) {
            int edgeId = markedEdgeId[entry];
            if (edgeId < edgeIdCeiling) {
                marks[edgeId] = true;
            }
        }
        return marks;
    }

    /**
     * Per-edge-id mask of every ring's cycle at once, the union a selection output carries.
     *
     * @param edgeIdCeiling one past the largest mesh edge id, the length of the returned mask
     * @return a fresh mask, true on every edge of every ring
     */
    public boolean[] unionEdgeMarks(int edgeIdCeiling) {
        boolean[] marks = new boolean[edgeIdCeiling];
        for (int edgeId : markedEdgeId) {
            if (edgeId < edgeIdCeiling) {
                marks[edgeId] = true;
            }
        }
        return marks;
    }

    /**
     * Every ring as one curve geometry, each loop closed by repeating its first point.
     *
     * @return curve geometry with {@link #ringCount} polylines, in ring order
     */
    public CurveGeometry curves() {
        int[] offsets = new int[ringCount + 1];
        int pointTotal = 0;
        for (int ring = 0; ring < ringCount; ring++) {
            offsets[ring] = pointTotal;
            pointTotal += ringPointCount(ring) + 1;
        }
        offsets[ringCount] = pointTotal;
        float[] packed = new float[COORDINATES_PER_POINT * Math.max(1, pointTotal)];
        int target = 0;
        for (int ring = 0; ring < ringCount; ring++) {
            int points = ringPointCount(ring);
            for (int step = 0; step <= points; step++) {
                int source = COORDINATES_PER_POINT
                        * (polylineOffset[ring] + step % Math.max(1, points));
                packed[target] = polylinePoint[source];
                packed[target + 1] = polylinePoint[source + 1];
                packed[target + 2] = polylinePoint[source + 2];
                target += COORDINATES_PER_POINT;
            }
        }
        if (ringCount == 0) {
            return CurveGeometry.singlePolyline(new float[0]);
        }
        return new CurveGeometry(packed, offsets);
    }

    /**
     * Points in one ring's polyline.
     *
     * @param ring ring index in {@code [0, ringCount)}
     * @return number of points stored for that ring
     */
    public int ringPointCount(int ring) {
        return polylineOffset[ring + 1] - polylineOffset[ring];
    }

    /**
     * The ring passing nearest a surface point, the geometric pick {@code select_ring} makes.
     *
     * @param x point x
     * @param y point y
     * @param z point z
     * @return ring index of the nearest polyline point, or -1 when there are no rings
     */
    public int nearestRing(float x, float y, float z) {
        int best = -1;
        double bestSquared = Double.POSITIVE_INFINITY;
        for (int ring = 0; ring < ringCount; ring++) {
            for (int point = polylineOffset[ring]; point < polylineOffset[ring + 1]; point++) {
                int base = COORDINATES_PER_POINT * point;
                double dx = polylinePoint[base] - x;
                double dy = polylinePoint[base + 1] - y;
                double dz = polylinePoint[base + 2] - z;
                double squared = dx * dx + dy * dy + dz * dz;
                if (squared < bestSquared) {
                    bestSquared = squared;
                    best = ring;
                }
            }
        }
        return best;
    }

    /**
     * A copy holding one ring, so a picked ring travels the graph as an ordinary candidate set.
     *
     * @param ring ring index in {@code [0, ringCount)}
     * @return a fresh single-ring value; counters are carried over unchanged
     */
    public RingCandidates single(int ring) {
        RingCandidates one = new RingCandidates();
        one.ringCount = 1;
        int points = ringPointCount(ring);
        one.polylineOffset = new int[] { 0, points };
        one.polylinePoint = new float[COORDINATES_PER_POINT * points];
        System.arraycopy(polylinePoint, COORDINATES_PER_POINT * polylineOffset[ring],
                one.polylinePoint, 0, one.polylinePoint.length);
        int edges = markedEdgeOffset[ring + 1] - markedEdgeOffset[ring];
        one.markedEdgeOffset = new int[] { 0, edges };
        one.markedEdgeId = new int[edges];
        System.arraycopy(markedEdgeId, markedEdgeOffset[ring], one.markedEdgeId, 0, edges);
        one.centroid = new float[] {
            centroid[COORDINATES_PER_POINT * ring],
            centroid[COORDINATES_PER_POINT * ring + 1],
            centroid[COORDINATES_PER_POINT * ring + 2] };
        one.length = new float[] { length[ring] };
        one.seedLength = new float[] { seedLength[ring] };
        one.meanRadius = new float[] { meanRadius[ring] };
        one.branchOnOneSide = new int[] { branchOnOneSide[ring] };
        one.branchOnOtherSide = new int[] { branchOnOtherSide[ring] };
        one.regionOnOneSide = new int[] { regionOnOneSide[ring] };
        one.regionOnOtherSide = new int[] { regionOnOtherSide[ring] };
        one.areaOnOneSide = new float[] { areaOnOneSide[ring] };
        one.areaOnOtherSide = new float[] { areaOnOtherSide[ring] };
        one.skeletonRadius = new float[] { skeletonRadius[ring] };
        one.neckness = new float[] { neckness[ring] };
        one.boundariesExamined = boundariesExamined;
        one.rejectedGrown = rejectedGrown;
        one.rejectedCollapsed = rejectedCollapsed;
        one.rejectedMerged = rejectedMerged;
        one.rejectedBelowThreshold = rejectedBelowThreshold;
        one.rejectedUnclosed = rejectedUnclosed;
        one.rejectedNotGirdling = rejectedNotGirdling;
        return one;
    }
}
