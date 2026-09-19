package ixdar.geometry.mesh.data;

import java.util.Arrays;
import java.util.List;

import ixdar.geometry.mesh.data.MeshSkeletonExtractor.SkeletonBranch;
import ixdar.geometry.mesh.data.MeshSkeletonExtractor.SkeletonJoint;
import ixdar.geometry.mesh.data.MeshSkeletonExtractor.SkeletonResult;
import ixdar.geometry.mesh.data.paths.ConformingLoopSnap;
import ixdar.geometry.mesh.data.paths.FlipGeodesics;
import ixdar.geometry.mesh.data.paths.PlaneSurfaceLoop;
import ixdar.geometry.mesh.data.paths.SurfaceRing;
import ixdar.geometry.mesh.data.paths.TracedSurfacePath;
import ixdar.geometry.mesh.data.representation.ArrayMeshEngine;

/**
 * One cross-section loop at a fraction along a skeleton branch: the plane there cuts the surface,
 * and FlipOut tightens that cut when a constriction holds it.
 *
 * <p>
 * A tapering limb has no locally shortest loop, so the tightening collapses and the plane's cut is
 * kept.
 */
public final class BranchCrossSectionRing {

    /** Coordinates per point in every packed position array here. */
    public static final int COORDINATES_PER_POINT = 3;

    /** Waypoints the tightening is seeded through, spread around the planar cut. */
    public static final int WAYPOINTS = 3;

    /**
     * Share of the planar cut's length the tightened loop must keep. A constriction shortens the
     * cut a little; a taper lets it slide off the end and shrink to nothing.
     */
    public static final float MINIMUM_TIGHTENED_FRACTION = 0.3f;

    /** Voxel-grid resolution handed to {@link MeshSkeletonExtractor}. */
    public int resolution = MeshSkeletonExtractor.NUM_128;

    /** Branch-extraction rounds the skeleton may spend, as {@link RingCandidateExtractor} sets. */
    public int skeletonBranchBudget = 4 * MeshSkeletonExtractor.DEFAULT_BRANCH_BUDGET;

    /** Surface the last run cut. */
    public MeshTopology mesh;

    /** Skeleton the branch was chosen from. */
    public SkeletonResult skeleton;

    /** Branch the given point selected, or -1 when the surface has no skeleton. */
    public int branch = -1;

    /** Point on the branch at the requested fraction of its length. */
    public float[] planePoint = new float[COORDINATES_PER_POINT];

    /** Branch direction there, the cutting plane's normal. */
    public float[] planeNormal = new float[COORDINATES_PER_POINT];

    /** Radius of the skeleton joint nearest {@link #planePoint}, its distance from the boundary. */
    public float localSkeletonRadius;

    /** Whether the tightened cut survived, so the ring is a geodesic rather than the plane's. */
    public boolean tightened;

    /** What the plane's cut tightened to, or null when the tightening was rejected. */
    public SurfaceRing tightenedRing;

    private final ConformingLoopSnap snap = new ConformingLoopSnap();
    private TracedSurfacePath planarPath;
    private float[] planarPolyline = new float[0];
    private int[] planarEdgeId = new int[0];
    private double planarLength;
    private int[] planarVertexCycle = new int[0];

    /**
     * Cuts {@code surface} across the branch nearest {@code branchPoint}, at {@code parameter} of
     * the way along it.
     *
     * @param surface     triangle mesh to cut
     * @param branchPoint a point near the branch to cut, which selects it geometrically
     * @param parameter   fraction along the branch in {@code [0, 1]}, from its tip to its root
     * @return one ring, or no rings when the branch or the cut cannot be found
     */
    public RingCandidates extract(MeshTopology surface, float[] branchPoint, float parameter) {
        mesh = surface;
        tightened = false;
        tightenedRing = null;
        RingCandidates rings = new RingCandidates();
        if (surface == null || surface.faceCount() == 0) {
            return rings;
        }
        skeleton = MeshSkeletonExtractor.extract(
                ArrayMeshEngine.fromUniformMeshTopology(surface), resolution, skeletonBranchBudget);
        branch = nearestBranch(branchPoint);
        if (branch < 0) {
            return rings;
        }
        locatePlane(parameter);
        localSkeletonRadius = RingCandidates.skeletonRadiusNear(skeleton, planePoint[0],
                planePoint[1], planePoint[2]);
        if (!cutPlanarLoop()) {
            return rings;
        }
        tightenedRing = SurfaceRing.tightenedOrNull(surface, spreadWaypoints(), true, 0,
                FlipGeodesics.UNBOUNDED_ITERATIONS);
        tightened = tightenedRing != null && tightenedRing.length <= planarLength
                && tightenedRing.length >= MINIMUM_TIGHTENED_FRACTION * planarLength
                && RingCandidates.marksOneClosedCycle(surface, tightenedRing.markedEdgeIds);
        return fill(rings);
    }

    private int nearestBranch(float[] branchPoint) {
        int best = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (SkeletonBranch candidate : skeleton.branches()) {
            for (SkeletonJoint joint : candidate.joints()) {
                double dx = joint.position()[0] - branchPoint[0];
                double dy = joint.position()[1] - branchPoint[1];
                double dz = joint.position()[2] - branchPoint[2];
                double distance = dx * dx + dy * dy + dz * dz;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate.id();
                }
            }
        }
        return best;
    }

    /**
     * Walks the branch's joints to the requested fraction of its length, taking the plane point
     * there and the segment direction as the plane's normal.
     */
    private void locatePlane(float parameter) {
        List<SkeletonJoint> joints = skeleton.branches().get(branch).joints();
        double total = 0.0;
        for (int index = 1; index < joints.size(); index++) {
            total += jointDistance(joints.get(index - 1), joints.get(index));
        }
        double target = Math.max(0.0, Math.min(1.0, parameter)) * total;
        double walked = 0.0;
        int segment = 1;
        while (segment < joints.size() - 1
                && walked + jointDistance(joints.get(segment - 1), joints.get(segment)) < target) {
            walked += jointDistance(joints.get(segment - 1), joints.get(segment));
            segment++;
        }
        SkeletonJoint from = joints.get(Math.max(0, segment - 1));
        SkeletonJoint to = joints.get(Math.min(joints.size() - 1, segment));
        double span = jointDistance(from, to);
        double fraction = span <= 0.0 ? 0.0
                : Math.max(0.0, Math.min(1.0, (target - walked) / span));
        for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
            planePoint[axis] = (float) (from.position()[axis]
                    + fraction * (to.position()[axis] - from.position()[axis]));
            planeNormal[axis] = to.position()[axis] - from.position()[axis];
        }
        float normalLength = (float) Math.sqrt(planeNormal[0] * planeNormal[0]
                + planeNormal[1] * planeNormal[1] + planeNormal[2] * planeNormal[2]);
        if (normalLength > 0f) {
            for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
                planeNormal[axis] /= normalLength;
            }
        } else {
            planeNormal[0] = 1f;
        }
    }

    /**
     * Follows the plane's intersection with the surface from the crossing nearest the plane
     * point, so the loop found is the one girdling this limb.
     *
     * @return true when a closed cross-section was traced
     */
    private boolean cutPlanarLoop() {
        PlaneSurfaceLoop cut = new PlaneSurfaceLoop();
        int startEdgeId = cut.nearestCrossingEdge(mesh, planePoint, planeNormal);
        if (startEdgeId < 0 || !cut.walk(mesh, planePoint, planeNormal, startEdgeId)) {
            return false;
        }
        return buildPlanarPath(cut);
    }

    /**
     * Packs the traced cross-section into the same shape a geodesic trace has, then snaps it to a
     * conforming edge cycle so the plane's cut is EdgeMarks-compatible on its own.
     */
    private boolean buildPlanarPath(PlaneSurfaceLoop cut) {
        if (cut.stepCount < WAYPOINTS) {
            return false;
        }
        planarPath = cut.asTracedPath(mesh);
        planarPolyline = planarPath.packedFloatPositions();
        planarLength = planarPath.polylineLength();
        boolean[] marksByEdgeId = snap.snap(mesh, planarPath);
        planarVertexCycle = Arrays.copyOf(snap.vertexCycle, snap.vertexCycleLength);
        int marked = 0;
        for (boolean mark : marksByEdgeId) {
            if (mark) {
                marked++;
            }
        }
        planarEdgeId = new int[marked];
        int next = 0;
        for (int candidate = 0; candidate < marksByEdgeId.length; candidate++) {
            if (marksByEdgeId[candidate]) {
                planarEdgeId[next++] = candidate;
            }
        }
        return marked > 0;
    }

    /**
     * Three vertices spaced by arclength around the planar cut, the seed the tightening starts
     * from; spacing them along the cut is what makes the seed wind around the limb rather than
     * double back.
     */
    private int[] spreadWaypoints() {
        int[] waypoints = new int[WAYPOINTS];
        double[] cumulative = new double[planarPath.pointCount];
        for (int point = 1; point < planarPath.pointCount; point++) {
            cumulative[point] = cumulative[point - 1] + spanLength(point - 1, point);
        }
        int taken = 0;
        for (int share = 0; share < WAYPOINTS; share++) {
            double target = planarLength * share / WAYPOINTS;
            int nearest = 0;
            double bestGap = Double.POSITIVE_INFINITY;
            for (int point = 0; point < planarPath.pointCount; point++) {
                double gap = Math.abs(cumulative[point] - target);
                if (gap < bestGap) {
                    bestGap = gap;
                    nearest = point;
                }
            }
            int vertexId = nearerEdgeEnd(nearest);
            boolean fresh = true;
            for (int index = 0; index < taken; index++) {
                fresh &= waypoints[index] != vertexId;
            }
            if (fresh) {
                waypoints[taken++] = vertexId;
            }
        }
        return taken == WAYPOINTS ? waypoints : new int[0];
    }

    private double spanLength(int from, int to) {
        double total = 0.0;
        for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
            double step = planarPath.positions[COORDINATES_PER_POINT * to + axis]
                    - planarPath.positions[COORDINATES_PER_POINT * from + axis];
            total += step * step;
        }
        return Math.sqrt(total);
    }

    private int nearerEdgeEnd(int point) {
        int halfEdge = mesh.edgeHalfEdge(planarPath.edgeId[point]);
        return planarPath.fraction[point] <= 0.5
                ? mesh.halfEdgeVertex(halfEdge)
                : mesh.halfEdgeEndVertex(halfEdge);
    }

    private RingCandidates fill(RingCandidates rings) {
        float[] polyline = tightened ? tightenedRing.polyline : planarPolyline;
        int[] edgeIds = tightened ? tightenedRing.markedEdgeIds : planarEdgeId;
        double length = tightened ? tightenedRing.length : planarLength;
        rings.ringCount = 1;
        rings.boundariesExamined = 1;
        rings.polylineOffset = new int[] { 0, polyline.length / COORDINATES_PER_POINT };
        rings.polylinePoint = polyline;
        rings.markedEdgeOffset = new int[] { 0, edgeIds.length };
        rings.markedEdgeId = edgeIds;
        rings.centroid = new float[COORDINATES_PER_POINT];
        double meanRadius = 0.0;
        int points = polyline.length / COORDINATES_PER_POINT;
        for (int point = 0; point < points; point++) {
            for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
                rings.centroid[axis] += polyline[COORDINATES_PER_POINT * point + axis] / points;
            }
        }
        for (int point = 0; point < points; point++) {
            double sum = 0.0;
            for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
                double step = polyline[COORDINATES_PER_POINT * point + axis] - rings.centroid[axis];
                sum += step * step;
            }
            meanRadius += Math.sqrt(sum) / points;
        }
        rings.length = new float[] { (float) length };
        rings.seedLength = new float[] { (float) planarLength };
        rings.meanRadius = new float[] { (float) meanRadius };
        rings.branchOnOneSide = new int[] { branch };
        rings.branchOnOtherSide = new int[] { branch };
        rings.regionOnOneSide = new int[] { -1 };
        rings.regionOnOtherSide = new int[] { -1 };
        rings.areaOnOneSide = new float[] { 0f };
        rings.areaOnOtherSide = new float[] { 0f };
        rings.skeletonRadius = new float[] { localSkeletonRadius };
        rings.neckness = new float[] { RingCandidates.neckness(localSkeletonRadius, length) };
        return rings;
    }

    private static double jointDistance(SkeletonJoint from, SkeletonJoint to) {
        double dx = to.position()[0] - from.position()[0];
        double dy = to.position()[1] - from.position()[1];
        double dz = to.position()[2] - from.position()[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
