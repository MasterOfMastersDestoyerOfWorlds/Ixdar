package ixdar.geometry.mesh.data.rings;

import java.util.Locale;

import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonExtractor.SkeletonBranch;
import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonExtractor.SkeletonJoint;
import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonExtractor.SkeletonResult;
import ixdar.geometry.mesh.data.bundle.CurveGeometry;
import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.paths.geodesic.TracedSurfacePath;

/**
 * Neck loops proposed on a surface, one entry per ring in every array: the tightened loop, the
 * mesh edges nearest it, and the measurements that rank it.
 *
 * <p>
 * Rows are sorted by descending {@link #neckness}, so ring order is the ranking and two runs agree.
 */
public final class RingCandidates {

    public static final String SLOT = "_rings";

    public static final String MARK_LABEL_PREFIX = "ring_";

    public static final int COORDINATES_PER_POINT = 3;

    /** Number of rings; every array below holds this many entries. */
    public int ringCount;

    /** Each ring's closed loop on the surface, measured: its length, centroid and mean radius. */
    public TracedSurfacePath[] path = new TracedSurfacePath[0];

    /** The conforming mesh edge cycle nearest each ring's loop. */
    public EdgeMarks[] marks = new EdgeMarks[0];

    /** Length of the seed each loop was tightened from, always at least the loop's own. */
    public double[] seedLength = new double[0];

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
     * Per-edge-id mask of one ring's conforming edge cycle, sized for the surface it is stored on.
     *
     * @param ring          ring index in {@code [0, ringCount)}
     * @param edgeIdCeiling one past the largest mesh edge id, the length of the returned mask
     * @return a fresh mask, true on every edge of that ring's cycle
     */
    public boolean[] edgeMarks(int ring, int edgeIdCeiling) {
        boolean[] mask = new boolean[edgeIdCeiling];
        for (int edgeId : marks[ring].markedEdgeIds) {
            if (edgeId < edgeIdCeiling) {
                mask[edgeId] = true;
            }
        }
        return mask;
    }

    /**
     * Per-edge-id mask of every ring's cycle at once, the union a selection output carries.
     *
     * @param edgeIdCeiling one past the largest mesh edge id, the length of the returned mask
     * @return a fresh mask, true on every edge of every ring
     */
    public boolean[] unionEdgeMarks(int edgeIdCeiling) {
        boolean[] mask = new boolean[edgeIdCeiling];
        for (int ring = 0; ring < ringCount; ring++) {
            for (int edgeId : marks[ring].markedEdgeIds) {
                if (edgeId < edgeIdCeiling) {
                    mask[edgeId] = true;
                }
            }
        }
        return mask;
    }

    /**
     * Every ring as one curve geometry, each loop closed by repeating its first point.
     *
     * @return curve geometry with {@link #ringCount} polylines, in ring order
     */
    public CurveGeometry curves() {
        if (ringCount == 0) {
            return CurveGeometry.singlePolyline(new float[0]);
        }
        float[][] drawn = new float[ringCount][];
        int[] offsets = new int[ringCount + 1];
        for (int ring = 0; ring < ringCount; ring++) {
            drawn[ring] = path[ring].drawnPolyline(path[ring].positions);
            offsets[ring + 1] = offsets[ring] + drawn[ring].length / COORDINATES_PER_POINT;
        }
        float[] packed = new float[COORDINATES_PER_POINT * Math.max(1, offsets[ringCount])];
        for (int ring = 0; ring < ringCount; ring++) {
            System.arraycopy(drawn[ring], 0, packed, COORDINATES_PER_POINT * offsets[ring],
                    drawn[ring].length);
        }
        return new CurveGeometry(packed, offsets);
    }

    /**
     * The ring passing nearest a surface point, the geometric pick {@code select_ring} makes.
     *
     * @param x point x
     * @param y point y
     * @param z point z
     * @return ring index of the nearest loop point, or -1 when there are no rings
     */
    public int nearestRing(float x, float y, float z) {
        int best = -1;
        double bestSquared = Double.POSITIVE_INFINITY;
        for (int ring = 0; ring < ringCount; ring++) {
            double[] xyz = path[ring].positions;
            for (int point = 0; point < path[ring].pointCount; point++) {
                int base = COORDINATES_PER_POINT * point;
                double dx = xyz[base] - x;
                double dy = xyz[base + 1] - y;
                double dz = xyz[base + 2] - z;
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
     * @return a fresh single-ring value sharing that ring's path and marks; counters are carried
     *         over unchanged
     */
    public RingCandidates single(int ring) {
        RingCandidates one = new RingCandidates();
        one.ringCount = 1;
        one.path = new TracedSurfacePath[] { path[ring] };
        one.marks = new EdgeMarks[] { marks[ring] };
        one.seedLength = new double[] { seedLength[ring] };
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
