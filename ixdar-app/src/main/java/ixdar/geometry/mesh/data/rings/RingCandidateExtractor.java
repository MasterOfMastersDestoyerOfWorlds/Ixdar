package ixdar.geometry.mesh.data.rings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonExtractor.SkeletonBranch;
import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonExtractor.SkeletonJoint;
import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonExtractor.SkeletonResult;
import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonExtractor;
import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.paths.geodesic.FlipGeodesics;
import ixdar.geometry.mesh.data.paths.geodesic.TracedSurfacePath;
import ixdar.geometry.mesh.data.paths.query.NearestVertex;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.TriangulationRule;
import org.joml.Vector3f;

/**
 * Proposes neck loops from a TEASAR skeleton: branch regions, their rims as seed loops, FlipOut
 * tightening, and a ranking by neckness.
 *
 * <p>
 * See also Hetroy &amp; Attali 2003 and Tierny 2008, which look for constrictions directly.
 */
public final class RingCandidateExtractor {

    public static final float DEFAULT_MINIMUM_NECKNESS = 0.6f;

    public static final float DEFAULT_COLLAPSED_CIRCULARITY = 4.0f;

    public static final float MERGE_OVERLAP_FRACTION = 0.5f;

    public static final float MERGE_LENGTH_TOLERANCE = 0.1f;

    public static final int MINIMUM_RING_EDGES = 8;

    public static final int MINIMUM_COMPONENT_VERTICES = 6;

    public static final int COORDINATES_PER_POINT = 3;

    public static final int CYCLE_VERTEX_DEGREE = 2;

    /** Voxel-grid resolution handed to {@link MeshSkeletonExtractor}. */
    public int resolution = MeshSkeletonExtractor.DEFAULT_RESOLUTION;

    /**
     * Branch-extraction rounds the skeleton may spend. Four times the extractor's own default,
     * because a limb whose tip is a rounded cap costs a dozen rounds of dead ends before the next
     * limb is reached, and a limb the skeleton never reaches proposes no ring.
     */
    public int skeletonBranchBudget = 4 * MeshSkeletonExtractor.DEFAULT_BRANCH_BUDGET;

    /** Neckness a candidate must reach to survive. */
    public float minimumNeckness = DEFAULT_MINIMUM_NECKNESS;

    /**
     * How close, in ring radii, two loops of the same length may sit before the later counts as a
     * repeat: a straight limb carries a closed geodesic at every cross-section, so nearby rims
     * tighten to parallel loops that are one cut.
     */
    public float mergeDistanceInRadii = 2.0f;

    /**
     * How far inside the circle inscribed at its own centroid a loop may shrink before it counts
     * as collapsed: a loop still girdling the surface cannot pinch far past that circle.
     */
    public float collapsedCircularity = DEFAULT_COLLAPSED_CIRCULARITY;

    /** Surface the last run proposed rings on. */
    public MeshTopology mesh;

    /** Skeleton the last run's regions were grown from. */
    public SkeletonResult skeleton;

    /** Branch segments the skeleton was cut into at its junctions. */
    public int regionCount;

    /** Id of the skeleton branch each region was cut from; several regions share one branch. */
    public int[] branchOfRegion = new int[0];

    /**
     * Region index per vertex id, sized by the id bound, after the connected-region cleanup; gap
     * slots hold -1.
     */
    public int[] regionByVertex = new int[0];

    /** Surface area of each region, in world units squared. */
    public float[] regionArea = new float[0];

    private float[] positionByVertexId = new float[0];
    private float[] regionJointPosition = new float[0];
    private float[] regionJointRadius = new float[0];
    private int[] regionOfJoint = new int[0];
    private int[] bandIndexByVertex = new int[0];
    private int[] bandStamp = new int[0];
    private int currentBandStamp;
    private final List<SurfaceRing> candidateRing = new ArrayList<>();
    private int[] candidateRegionOnOneSide = new int[0];
    private int[] candidateRegionOnOtherSide = new int[0];
    private float[] candidateSkeletonRadius = new float[0];
    private float[] candidateNeckness = new float[0];

    /**
     * Runs the whole proposal on {@code surface} and returns the ranked survivors.
     *
     * @param surface triangle mesh to propose neck loops on
     * @return the accepted rings, sorted by descending neckness, plus the rejection counters
     */
    public RingCandidates extract(MeshTopology surface) {
        mesh = surface;
        candidateRing.clear();
        RingCandidates rings = new RingCandidates();
        if (surface == null || surface.faceCount() == 0) {
            return rings;
        }
        skeleton = MeshSkeletonExtractor.extract(surface, resolution, skeletonBranchBudget);
        buildRegionSamples();
        if (regionCount < 2) {
            return rings;
        }
        assignVerticesToRegions();
        makeRegionsConnected();
        measureRegionAreas();
        seedRingsFromRegionRims(rings);
        return pack(rings);
    }

    /**
     * Cuts every skeleton branch at the joints its children attach to, so a path TEASAR traced
     * straight through a junction becomes one region per limb rather than one region for both.
     */
    private void buildRegionSamples() {
        List<SkeletonBranch> branches = skeleton.branches();
        int jointTotal = 0;
        for (SkeletonBranch branch : branches) {
            jointTotal += branch.joints().size();
        }
        regionJointPosition = new float[COORDINATES_PER_POINT * jointTotal];
        regionJointRadius = new float[jointTotal];
        regionOfJoint = new int[jointTotal];
        branchOfRegion = new int[jointTotal + branches.size()];
        regionCount = 0;
        int next = 0;
        for (SkeletonBranch branch : branches) {
            boolean[] cutAfterJoint = new boolean[branch.joints().size()];
            for (SkeletonBranch child : branches) {
                if (child.parentBranch() != branch.id() || child.joints().isEmpty()) {
                    continue;
                }
                int attach = nearestJoint(branch, child.joints().get(child.joints().size() - 1));
                if (attach > 0 && attach < cutAfterJoint.length - 1) {
                    cutAfterJoint[attach] = true;
                }
            }
            int region = regionCount++;
            branchOfRegion[region] = branch.id();
            for (int index = 0; index < branch.joints().size(); index++) {
                SkeletonJoint joint = branch.joints().get(index);
                regionJointPosition[COORDINATES_PER_POINT * next] = joint.position()[0];
                regionJointPosition[COORDINATES_PER_POINT * next + 1] = joint.position()[1];
                regionJointPosition[COORDINATES_PER_POINT * next + 2] = joint.position()[2];
                regionJointRadius[next] = joint.radius();
                regionOfJoint[next] = region;
                next++;
                if (cutAfterJoint[index]) {
                    region = regionCount++;
                    branchOfRegion[region] = branch.id();
                }
            }
        }
        branchOfRegion = Arrays.copyOf(branchOfRegion, regionCount);
    }

    private static int nearestJoint(SkeletonBranch branch, SkeletonJoint target) {
        int best = 0;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int index = 0; index < branch.joints().size(); index++) {
            float[] position = branch.joints().get(index).position();
            double dx = position[0] - target.position()[0];
            double dy = position[1] - target.position()[1];
            double dz = position[2] - target.position()[2];
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = index;
            }
        }
        return best;
    }

    /**
     * Labels every vertex with the region whose sampled joints it sits nearest, charging a vertex
     * outside a joint's inscribed sphere for the excess so a fat limb keeps its own surface.
     */
    private void assignVerticesToRegions() {
        int vertexIdBound = mesh.vertexIdBound();
        positionByVertexId = new float[COORDINATES_PER_POINT * vertexIdBound];
        regionByVertex = new int[vertexIdBound];
        Arrays.fill(regionByVertex, -1);
        Vector3f position = new Vector3f();
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            mesh.vertexPosition(vertexId, position);
            positionByVertexId[COORDINATES_PER_POINT * vertexId] = position.x;
            positionByVertexId[COORDINATES_PER_POINT * vertexId + 1] = position.y;
            positionByVertexId[COORDINATES_PER_POINT * vertexId + 2] = position.z;
            float x = position.x;
            float y = position.y;
            float z = position.z;
            float bestScore = Float.MAX_VALUE;
            int bestRegion = 0;
            for (int joint = 0; joint < regionOfJoint.length; joint++) {
                float dx = x - regionJointPosition[COORDINATES_PER_POINT * joint];
                float dy = y - regionJointPosition[COORDINATES_PER_POINT * joint + 1];
                float dz = z - regionJointPosition[COORDINATES_PER_POINT * joint + 2];
                float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                float score = distance + Math.max(0f, distance - regionJointRadius[joint]);
                if (score < bestScore) {
                    bestScore = score;
                    bestRegion = regionOfJoint[joint];
                }
            }
            regionByVertex[vertexId] = bestRegion;
        }
    }

    /**
     * Keeps only each region's largest connected patch and regrows the rest as a geodesic Voronoi
     * diagram, so every region is one connected piece and its rim is a closed band.
     */
    private void makeRegionsConnected() {
        int vertexIdBound = regionByVertex.length;
        int vertexCount = mesh.vertexCount();
        int[] componentRoot = new int[vertexIdBound];
        for (int vertexId = 0; vertexId < vertexIdBound; vertexId++) {
            componentRoot[vertexId] = vertexId;
        }
        for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
            int halfEdge = mesh.edgeHalfEdge(mesh.edgeIdAt(activeEdge));
            int tail = mesh.halfEdgeVertex(halfEdge);
            int head = mesh.halfEdgeEndVertex(halfEdge);
            if (regionByVertex[tail] == regionByVertex[head]) {
                union(componentRoot, tail, head);
            }
        }
        int[] componentSize = new int[vertexIdBound];
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            componentSize[root(componentRoot, mesh.vertexIdAt(activeVertex))]++;
        }
        int[] largestComponentOfRegion = new int[regionCount];
        Arrays.fill(largestComponentOfRegion, -1);
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            int component = root(componentRoot, vertexId);
            int region = regionByVertex[vertexId];
            int held = largestComponentOfRegion[region];
            if (held < 0 || componentSize[component] > componentSize[held]) {
                largestComponentOfRegion[region] = component;
            }
        }
        double[] distance = new double[vertexIdBound];
        PriorityQueue<double[]> frontier =
                new PriorityQueue<>(Comparator.comparingDouble(entry -> entry[0]));
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            if (root(componentRoot, vertexId)
                    == largestComponentOfRegion[regionByVertex[vertexId]]) {
                distance[vertexId] = 0.0;
                frontier.add(new double[] { 0.0, vertexId });
            } else {
                regionByVertex[vertexId] = -1;
                distance[vertexId] = Double.POSITIVE_INFINITY;
            }
        }
        while (!frontier.isEmpty()) {
            double[] entry = frontier.poll();
            int vertexId = (int) entry[1];
            if (entry[0] > distance[vertexId]) {
                continue;
            }
            int spokes = mesh.vertexEdgeCount(vertexId);
            for (int spoke = 0; spoke < spokes; spoke++) {
                int other = neighborVertex(vertexId, spoke);
                if (other < 0) {
                    continue;
                }
                double step = distanceBetween(positionByVertexId, vertexId, other);
                if (distance[vertexId] + step < distance[other]) {
                    distance[other] = distance[vertexId] + step;
                    regionByVertex[other] = regionByVertex[vertexId];
                    frontier.add(new double[] { distance[other], other });
                }
            }
        }
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            if (regionByVertex[vertexId] < 0) {
                regionByVertex[vertexId] = 0;
            }
        }
    }

    /**
     * Sums each region's surface area over the faces fanned from their first corner, charging a
     * triangle to the region its last two corners share, else to its first corner's region.
     */
    private void measureRegionAreas() {
        regionArea = new float[regionCount];
        float[] positions = positionByVertexId;
        int[] triangles = mesh.triangulate(TriangulationRule.FAN, null);
        for (int corner = 0; corner < triangles.length; corner += 3) {
            int first = triangles[corner];
            int second = triangles[corner + 1];
            int third = triangles[corner + 2];
            int region = regionByVertex[first];
            if (regionByVertex[second] == regionByVertex[third]) {
                region = regionByVertex[second];
            }
            float ax = positions[COORDINATES_PER_POINT * second]
                    - positions[COORDINATES_PER_POINT * first];
            float ay = positions[COORDINATES_PER_POINT * second + 1]
                    - positions[COORDINATES_PER_POINT * first + 1];
            float az = positions[COORDINATES_PER_POINT * second + 2]
                    - positions[COORDINATES_PER_POINT * first + 2];
            float bx = positions[COORDINATES_PER_POINT * third]
                    - positions[COORDINATES_PER_POINT * first];
            float by = positions[COORDINATES_PER_POINT * third + 1]
                    - positions[COORDINATES_PER_POINT * first + 1];
            float bz = positions[COORDINATES_PER_POINT * third + 2]
                    - positions[COORDINATES_PER_POINT * first + 2];
            float crossX = ay * bz - az * by;
            float crossY = az * bx - ax * bz;
            float crossZ = ax * by - ay * bx;
            regionArea[region] += 0.5f
                    * (float) Math.sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ);
        }
    }

    /**
     * Seeds a loop from every connected piece of every region's rim.
     *
     * <p>
     * A region's rim closes where a boundary between two regions does not: at a three-limb
     * junction every limb has a closed rim, while no two limbs share a closed curve.
     */
    private void seedRingsFromRegionRims(RingCandidates rings) {
        bandIndexByVertex = new int[regionByVertex.length];
        bandStamp = new int[regionByVertex.length];
        for (int region = 0; region < regionCount; region++) {
            for (int[] component : rimComponents(region)) {
                rings.boundariesExamined++;
                considerCandidate(rings, component, region, facingRegion(component, region));
            }
        }
    }

    /**
     * The connected pieces of the band of {@code region} vertices that touch any other region,
     * each returned as its vertex ids in active-vertex order.
     */
    private List<int[]> rimComponents(int region) {
        currentBandStamp++;
        List<Integer> band = new ArrayList<>();
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            if (regionByVertex[vertexId] != region) {
                continue;
            }
            boolean touchesAnotherRegion = false;
            int vertexSpokes = mesh.vertexEdgeCount(vertexId);
            for (int spoke = 0; spoke < vertexSpokes && !touchesAnotherRegion; spoke++) {
                int other = neighborVertex(vertexId, spoke);
                touchesAnotherRegion = other >= 0 && regionByVertex[other] != region;
            }
            if (!touchesAnotherRegion) {
                continue;
            }
            bandStamp[vertexId] = currentBandStamp;
            bandIndexByVertex[vertexId] = band.size();
            band.add(vertexId);
        }
        int[] componentRoot = new int[band.size()];
        for (int index = 0; index < componentRoot.length; index++) {
            componentRoot[index] = index;
        }
        for (int index = 0; index < band.size(); index++) {
            int vertexId = band.get(index);
            int spokes = mesh.vertexEdgeCount(vertexId);
            for (int spoke = 0; spoke < spokes; spoke++) {
                int other = neighborVertex(vertexId, spoke);
                if (other >= 0 && bandStamp[other] == currentBandStamp) {
                    union(componentRoot, index, bandIndexByVertex[other]);
                }
            }
        }
        int[] memberCount = new int[band.size()];
        for (int index = 0; index < band.size(); index++) {
            memberCount[root(componentRoot, index)]++;
        }
        int[][] members = new int[band.size()][];
        int[] fillPosition = new int[band.size()];
        for (int index = 0; index < band.size(); index++) {
            if (memberCount[index] >= MINIMUM_COMPONENT_VERTICES) {
                members[index] = new int[memberCount[index]];
            }
        }
        for (int index = 0; index < band.size(); index++) {
            int component = root(componentRoot, index);
            if (members[component] != null) {
                members[component][fillPosition[component]++] = band.get(index);
            }
        }
        List<int[]> components = new ArrayList<>();
        for (int[] member : members) {
            if (member != null) {
                components.add(member);
            }
        }
        return components;
    }

    /** The region most of a rim component faces, which is the ring's other side. */
    private int facingRegion(int[] component, int region) {
        int[] votes = new int[regionCount];
        for (int vertexId : component) {
            int spokes = mesh.vertexEdgeCount(vertexId);
            for (int spoke = 0; spoke < spokes; spoke++) {
                int other = neighborVertex(vertexId, spoke);
                if (other >= 0 && regionByVertex[other] != region) {
                    votes[regionByVertex[other]]++;
                }
            }
        }
        int best = region;
        int bestVotes = -1;
        for (int candidate = 0; candidate < regionCount; candidate++) {
            if (votes[candidate] > bestVotes) {
                bestVotes = votes[candidate];
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Tightens one rim component into a loop and applies the rejections: no closed cycle, edges
     * that do not girdle, a loop that grew, one that collapsed or landed on an accepted ring, and
     * one whose neckness misses the threshold.
     */
    private void considerCandidate(RingCandidates rings, int[] component, int region, int facing) {
        SurfaceRing ring = SurfaceRing.tightenedOrNull(mesh, spreadWaypoints(component), true, 0,
                FlipGeodesics.UNBOUNDED_ITERATIONS);
        if (ring == null || ring.marks.unresolvedGaps > 0) {
            rings.rejectedUnclosed++;
            return;
        }
        if (!marksOneClosedCycle(mesh, ring.marks.markedEdgeIds)) {
            rings.rejectedNotGirdling++;
            return;
        }
        if (ring.marks.markedEdgeCount < MINIMUM_RING_EDGES) {
            rings.rejectedCollapsed++;
            return;
        }
        double length = ring.path.length;
        if (length > ring.seed.length) {
            rings.rejectedGrown++;
            return;
        }
        float centroidX = (float) ring.path.centroid[0];
        float centroidY = (float) ring.path.centroid[1];
        float centroidZ = (float) ring.path.centroid[2];
        float inscribedRadius = (float) NearestVertex.distanceToNearest(mesh, centroidX, centroidY,
                centroidZ);
        if (length <= 0.0 || 2.0 * Math.PI * inscribedRadius > collapsedCircularity * length) {
            rings.rejectedCollapsed++;
            return;
        }
        float radius = RingCandidates.skeletonRadiusNear(skeleton, centroidX, centroidY,
                centroidZ);
        float neckness = RingCandidates.neckness(radius, length);
        if (repeatsAcceptedRing(ring)) {
            rings.rejectedMerged++;
            return;
        }
        if (neckness < minimumNeckness) {
            rings.rejectedBelowThreshold++;
            return;
        }
        int candidate = candidateRing.size();
        candidateRing.add(ring);
        candidateRegionOnOneSide = Arrays.copyOf(candidateRegionOnOneSide, candidate + 1);
        candidateRegionOnOtherSide = Arrays.copyOf(candidateRegionOnOtherSide, candidate + 1);
        candidateSkeletonRadius = Arrays.copyOf(candidateSkeletonRadius, candidate + 1);
        candidateNeckness = Arrays.copyOf(candidateNeckness, candidate + 1);
        candidateRegionOnOneSide[candidate] = region;
        candidateRegionOnOtherSide[candidate] = facing;
        candidateSkeletonRadius[candidate] = radius;
        candidateNeckness[candidate] = neckness;
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
        if (neighbors.isEmpty()) {
            return false;
        }
        for (List<Integer> spokes : neighbors.values()) {
            if (spokes.size() != CYCLE_VERTEX_DEGREE) {
                return false;
            }
        }
        Set<Integer> seen = new HashSet<>();
        Deque<Integer> pending = new ArrayDeque<>();
        int start = neighbors.keySet().iterator().next();
        seen.add(start);
        pending.push(start);
        while (!pending.isEmpty()) {
            for (int next : neighbors.get(pending.pop())) {
                if (seen.add(next)) {
                    pending.push(next);
                }
            }
        }
        return seen.size() == neighbors.size();
    }

    /**
     * Three vertices spread around a rim component: the one farthest from its centroid, the one
     * farthest from that, and the one farthest from both.
     */
    private int[] spreadWaypoints(int[] component) {
        float[] positions = positionByVertexId;
        double centerX = 0.0;
        double centerY = 0.0;
        double centerZ = 0.0;
        for (int vertexId : component) {
            centerX += positions[COORDINATES_PER_POINT * vertexId];
            centerY += positions[COORDINATES_PER_POINT * vertexId + 1];
            centerZ += positions[COORDINATES_PER_POINT * vertexId + 2];
        }
        centerX /= component.length;
        centerY /= component.length;
        centerZ /= component.length;
        int first = component[0];
        double bestDistance = -1.0;
        for (int vertexId : component) {
            double dx = positions[COORDINATES_PER_POINT * vertexId] - centerX;
            double dy = positions[COORDINATES_PER_POINT * vertexId + 1] - centerY;
            double dz = positions[COORDINATES_PER_POINT * vertexId + 2] - centerZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > bestDistance) {
                bestDistance = distance;
                first = vertexId;
            }
        }
        int second = component[0];
        bestDistance = -1.0;
        for (int vertexId : component) {
            double distance = distanceBetween(positions, vertexId, first);
            if (distance > bestDistance) {
                bestDistance = distance;
                second = vertexId;
            }
        }
        int third = component[0];
        bestDistance = -1.0;
        for (int vertexId : component) {
            double distance = Math.min(distanceBetween(positions, vertexId, first),
                    distanceBetween(positions, vertexId, second));
            if (distance > bestDistance) {
                bestDistance = distance;
                third = vertexId;
            }
        }
        return new int[] { first, second, third };
    }

    /**
     * Whether the loop just tightened is one an accepted ring already cuts: it either runs along
     * the same edges, or it is a parallel loop of the same length a short way along the same limb.
     *
     * @param ring the loop just tightened
     * @return true when an accepted ring already cuts the same place
     */
    private boolean repeatsAcceptedRing(SurfaceRing ring) {
        int[] edgeIds = ring.marks.markedEdgeIds;
        for (SurfaceRing acceptedRing : candidateRing) {
            int[] acceptedEdges = acceptedRing.marks.markedEdgeIds;
            int shared = 0;
            int here = 0;
            int there = 0;
            while (here < edgeIds.length && there < acceptedEdges.length) {
                if (edgeIds[here] == acceptedEdges[there]) {
                    shared++;
                    here++;
                    there++;
                } else if (edgeIds[here] < acceptedEdges[there]) {
                    here++;
                } else {
                    there++;
                }
            }
            if (shared > MERGE_OVERLAP_FRACTION * Math.min(edgeIds.length, acceptedEdges.length)) {
                return true;
            }
            TracedSurfacePath accepted = acceptedRing.path;
            double lengthGap = Math.abs(ring.path.length - accepted.length);
            double centroidX = ring.path.centroid[0] - accepted.centroid[0];
            double centroidY = ring.path.centroid[1] - accepted.centroid[1];
            double centroidZ = ring.path.centroid[2] - accepted.centroid[2];
            double centroidGap =
                    centroidX * centroidX + centroidY * centroidY + centroidZ * centroidZ;
            if (lengthGap < MERGE_LENGTH_TOLERANCE * accepted.length
                    && Math.sqrt(centroidGap) < mergeDistanceInRadii * accepted.meanRadius) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sorts the accepted candidates by descending neckness, breaking ties on centroid then length
     * so row order does not depend on the order the rims happened to be walked.
     */
    private RingCandidates pack(RingCandidates rings) {
        int count = candidateRing.size();
        Integer[] order = new Integer[count];
        for (int index = 0; index < count; index++) {
            order[index] = index;
        }
        Arrays.sort(order, (leftIndex, rightIndex) -> {
            int byNeckness = Float.compare(candidateNeckness[rightIndex],
                    candidateNeckness[leftIndex]);
            if (byNeckness != 0) {
                return byNeckness;
            }
            TracedSurfacePath left = candidateRing.get(leftIndex).path;
            TracedSurfacePath right = candidateRing.get(rightIndex).path;
            for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
                int byAxis = Double.compare(left.centroid[axis], right.centroid[axis]);
                if (byAxis != 0) {
                    return byAxis;
                }
            }
            return Double.compare(left.length, right.length);
        });

        rings.ringCount = count;
        rings.path = new TracedSurfacePath[count];
        rings.marks = new EdgeMarks[count];
        rings.seedLength = new double[count];
        rings.branchOnOneSide = new int[count];
        rings.branchOnOtherSide = new int[count];
        rings.regionOnOneSide = new int[count];
        rings.regionOnOtherSide = new int[count];
        rings.areaOnOneSide = new float[count];
        rings.areaOnOtherSide = new float[count];
        rings.skeletonRadius = new float[count];
        rings.neckness = new float[count];
        for (int ring = 0; ring < count; ring++) {
            int candidate = order[ring];
            SurfaceRing accepted = candidateRing.get(candidate);
            rings.path[ring] = accepted.path;
            rings.marks[ring] = accepted.marks;
            rings.seedLength[ring] = accepted.seed.length;
            int regionOnOneSide = candidateRegionOnOneSide[candidate];
            int regionOnOtherSide = candidateRegionOnOtherSide[candidate];
            rings.branchOnOneSide[ring] = branchOfRegion[regionOnOneSide];
            rings.branchOnOtherSide[ring] = branchOfRegion[regionOnOtherSide];
            rings.regionOnOneSide[ring] = regionOnOneSide;
            rings.regionOnOtherSide[ring] = regionOnOtherSide;
            rings.areaOnOneSide[ring] = regionArea[regionOnOneSide];
            rings.areaOnOtherSide[ring] = regionArea[regionOnOtherSide];
            rings.skeletonRadius[ring] = candidateSkeletonRadius[candidate];
            rings.neckness[ring] = candidateNeckness[candidate];
        }
        return rings;
    }

    private int neighborVertex(int vertexId, int spoke) {
        return mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertexId, spoke), vertexId);
    }

    private static double distanceBetween(float[] positions, int first, int second) {
        double dx = positions[COORDINATES_PER_POINT * first]
                - positions[COORDINATES_PER_POINT * second];
        double dy = positions[COORDINATES_PER_POINT * first + 1]
                - positions[COORDINATES_PER_POINT * second + 1];
        double dz = positions[COORDINATES_PER_POINT * first + 2]
                - positions[COORDINATES_PER_POINT * second + 2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static int root(int[] componentRoot, int member) {
        int walk = member;
        while (componentRoot[walk] != walk) {
            componentRoot[walk] = componentRoot[componentRoot[walk]];
            walk = componentRoot[walk];
        }
        return walk;
    }

    private static void union(int[] componentRoot, int first, int second) {
        int firstRoot = root(componentRoot, first);
        int secondRoot = root(componentRoot, second);
        if (firstRoot != secondRoot) {
            componentRoot[firstRoot] = secondRoot;
        }
    }
}
