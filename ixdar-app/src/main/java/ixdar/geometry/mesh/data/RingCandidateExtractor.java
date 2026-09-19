package ixdar.geometry.mesh.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import ixdar.geometry.mesh.data.MeshSkeletonExtractor.SkeletonBranch;
import ixdar.geometry.mesh.data.MeshSkeletonExtractor.SkeletonJoint;
import ixdar.geometry.mesh.data.MeshSkeletonExtractor.SkeletonResult;
import ixdar.geometry.mesh.data.paths.FlipGeodesics;
import ixdar.geometry.mesh.data.paths.NearestVertex;
import ixdar.geometry.mesh.data.paths.SurfaceRing;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.ArrayMeshEngine;

/**
 * Proposes neck loops from a TEASAR skeleton: branch regions, their rims as seed loops, FlipOut
 * tightening, and a ranking by neckness.
 *
 * <p>
 * See also Hetroy &amp; Attali 2003 and Tierny 2008, which look for constrictions directly.
 */
public final class RingCandidateExtractor {

    /** Neckness a candidate must reach to be kept when the caller names no threshold. */
    public static final float DEFAULT_MINIMUM_NECKNESS = 0.6f;

    /** Circularity above which a loop counts as collapsed rather than girdling. */
    public static final float DEFAULT_COLLAPSED_CIRCULARITY = 4.0f;

    /** Share of a candidate's edges that may already belong to an accepted ring. */
    public static final float MERGE_OVERLAP_FRACTION = 0.5f;

    /** Relative length agreement two loops need before they count as the same cut. */
    public static final float MERGE_LENGTH_TOLERANCE = 0.1f;

    /** Edges a conforming cycle needs before the loop counts as girdling rather than collapsed. */
    public static final int MINIMUM_RING_EDGES = 8;

    /** Vertices one rim component needs before it is worth seeding a loop from. */
    public static final int MINIMUM_COMPONENT_VERTICES = 6;

    /** Coordinates per point in every packed position array here. */
    public static final int COORDINATES_PER_POINT = 3;

    /** Corners of a triangle. */
    public static final int TRIANGLE_CORNERS = 3;

    /** Voxel-grid resolution handed to {@link MeshSkeletonExtractor}. */
    public int resolution = MeshSkeletonExtractor.NUM_128;

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

    /** The same surface packed densely, the form the skeleton extractor takes. */
    public ArrayMesh dense;

    /** Skeleton the last run's regions were grown from. */
    public SkeletonResult skeleton;

    /** Branch segments the skeleton was cut into at its junctions. */
    public int regionCount;

    /** Id of the skeleton branch each region was cut from; several regions share one branch. */
    public int[] branchOfRegion = new int[0];

    /** Region index per dense vertex index, after the connected-region cleanup. */
    public int[] regionByVertex = new int[0];

    /** Surface area of each region, in world units squared. */
    public float[] regionArea = new float[0];

    private int[] vertexIndexByVertexId = new int[0];
    private float[] regionJointPosition = new float[0];
    private float[] regionJointRadius = new float[0];
    private int[] regionOfJoint = new int[0];
    private int[] bandIndexByVertex = new int[0];
    private int[] bandStamp = new int[0];
    private int currentBandStamp;
    private final List<float[]> candidatePolyline = new ArrayList<>();
    private final List<int[]> candidateEdgeIds = new ArrayList<>();
    private float[] candidateCentroid = new float[0];
    private float[] candidateLength = new float[0];
    private float[] candidateSeedLength = new float[0];
    private float[] candidateMeanRadius = new float[0];
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
        candidatePolyline.clear();
        candidateEdgeIds.clear();
        RingCandidates rings = new RingCandidates();
        if (surface == null || surface.faceCount() == 0) {
            return rings;
        }
        dense = ArrayMeshEngine.fromUniformMeshTopology(surface);
        skeleton = MeshSkeletonExtractor.extract(dense, resolution, skeletonBranchBudget);
        buildRegionSamples();
        if (regionCount < 2) {
            return rings;
        }
        indexVertices();
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

    private void indexVertices() {
        int maxVertexId = 0;
        for (int index = 0; index < mesh.vertexCount(); index++) {
            maxVertexId = Math.max(maxVertexId, mesh.vertexIdAt(index));
        }
        vertexIndexByVertexId = new int[maxVertexId + 1];
        Arrays.fill(vertexIndexByVertexId, -1);
        for (int index = 0; index < mesh.vertexCount(); index++) {
            vertexIndexByVertexId[mesh.vertexIdAt(index)] = index;
        }
    }

    /**
     * Labels every vertex with the region whose sampled joints it sits nearest, charging a vertex
     * outside a joint's inscribed sphere for the excess so a fat limb keeps its own surface.
     */
    private void assignVerticesToRegions() {
        float[] positions = dense.copyPositions();
        int vertexCount = positions.length / COORDINATES_PER_POINT;
        regionByVertex = new int[vertexCount];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            float x = positions[COORDINATES_PER_POINT * vertex];
            float y = positions[COORDINATES_PER_POINT * vertex + 1];
            float z = positions[COORDINATES_PER_POINT * vertex + 2];
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
            regionByVertex[vertex] = bestRegion;
        }
    }

    /**
     * Keeps only each region's largest connected patch and regrows the rest as a geodesic Voronoi
     * diagram, so every region is one connected piece and its rim is a closed band.
     */
    private void makeRegionsConnected() {
        int vertexCount = regionByVertex.length;
        int[] componentRoot = new int[vertexCount];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            componentRoot[vertex] = vertex;
        }
        for (int index = 0; index < mesh.edgeCount(); index++) {
            int halfEdge = mesh.edgeHalfEdge(mesh.edgeIdAt(index));
            int tail = vertexIndexByVertexId[mesh.halfEdgeVertex(halfEdge)];
            int head = vertexIndexByVertexId[mesh.halfEdgeEndVertex(halfEdge)];
            if (tail >= 0 && head >= 0 && regionByVertex[tail] == regionByVertex[head]) {
                union(componentRoot, tail, head);
            }
        }
        int[] componentSize = new int[vertexCount];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            componentSize[root(componentRoot, vertex)]++;
        }
        int[] largestComponentOfRegion = new int[regionCount];
        Arrays.fill(largestComponentOfRegion, -1);
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int component = root(componentRoot, vertex);
            int region = regionByVertex[vertex];
            int held = largestComponentOfRegion[region];
            if (held < 0 || componentSize[component] > componentSize[held]) {
                largestComponentOfRegion[region] = component;
            }
        }
        double[] distance = new double[vertexCount];
        PriorityQueue<double[]> frontier =
                new PriorityQueue<>(Comparator.comparingDouble(entry -> entry[0]));
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            if (root(componentRoot, vertex) == largestComponentOfRegion[regionByVertex[vertex]]) {
                distance[vertex] = 0.0;
                frontier.add(new double[] { 0.0, vertex });
            } else {
                regionByVertex[vertex] = -1;
                distance[vertex] = Double.POSITIVE_INFINITY;
            }
        }
        float[] positions = dense.copyPositions();
        while (!frontier.isEmpty()) {
            double[] entry = frontier.poll();
            int vertex = (int) entry[1];
            if (entry[0] > distance[vertex]) {
                continue;
            }
            int vertexId = mesh.vertexIdAt(vertex);
            int spokes = mesh.vertexEdgeCount(vertexId);
            for (int spoke = 0; spoke < spokes; spoke++) {
                int other = neighborVertex(vertexId, spoke);
                if (other < 0) {
                    continue;
                }
                double step = distanceBetween(positions, vertex, other);
                if (distance[vertex] + step < distance[other]) {
                    distance[other] = distance[vertex] + step;
                    regionByVertex[other] = regionByVertex[vertex];
                    frontier.add(new double[] { distance[other], other });
                }
            }
        }
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            if (regionByVertex[vertex] < 0) {
                regionByVertex[vertex] = 0;
            }
        }
    }

    private void measureRegionAreas() {
        regionArea = new float[regionCount];
        int[] faces = dense.copyFaceIndices();
        float[] positions = dense.copyPositions();
        for (int corner = 0; corner + TRIANGLE_CORNERS <= faces.length; corner += TRIANGLE_CORNERS) {
            int first = faces[corner];
            int second = faces[corner + 1];
            int third = faces[corner + 2];
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
     * each returned as its dense vertex indices in ascending order.
     */
    private List<int[]> rimComponents(int region) {
        currentBandStamp++;
        List<Integer> band = new ArrayList<>();
        for (int vertex = 0; vertex < regionByVertex.length; vertex++) {
            if (regionByVertex[vertex] != region || !touchesAnotherRegion(vertex, region)) {
                continue;
            }
            bandStamp[vertex] = currentBandStamp;
            bandIndexByVertex[vertex] = band.size();
            band.add(vertex);
        }
        int[] componentRoot = new int[band.size()];
        for (int index = 0; index < componentRoot.length; index++) {
            componentRoot[index] = index;
        }
        for (int index = 0; index < band.size(); index++) {
            int vertexId = mesh.vertexIdAt(band.get(index));
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

    private boolean touchesAnotherRegion(int vertex, int region) {
        int vertexId = mesh.vertexIdAt(vertex);
        int spokes = mesh.vertexEdgeCount(vertexId);
        for (int spoke = 0; spoke < spokes; spoke++) {
            int other = neighborVertex(vertexId, spoke);
            if (other >= 0 && regionByVertex[other] != region) {
                return true;
            }
        }
        return false;
    }

    /** The region most of a rim component faces, which is the ring's other side. */
    private int facingRegion(int[] component, int region) {
        int[] votes = new int[regionCount];
        for (int vertex : component) {
            int vertexId = mesh.vertexIdAt(vertex);
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
        if (ring == null || ring.unresolvedGaps > 0) {
            rings.rejectedUnclosed++;
            return;
        }
        if (!RingCandidates.marksOneClosedCycle(mesh, ring.markedEdgeIds)) {
            rings.rejectedNotGirdling++;
            return;
        }
        if (ring.markedEdgeIds.length < MINIMUM_RING_EDGES) {
            rings.rejectedCollapsed++;
            return;
        }
        if (ring.length > ring.seedLength) {
            rings.rejectedGrown++;
            return;
        }
        float inscribedRadius = (float) NearestVertex.distanceToNearest(mesh, ring.centroidX,
                ring.centroidY, ring.centroidZ);
        if (ring.length <= 0.0
                || 2.0 * Math.PI * inscribedRadius > collapsedCircularity * ring.length) {
            rings.rejectedCollapsed++;
            return;
        }
        float radius = RingCandidates.skeletonRadiusNear(skeleton, ring.centroidX, ring.centroidY,
                ring.centroidZ);
        float neckness = RingCandidates.neckness(radius, ring.length);
        if (repeatsAcceptedRing(ring)) {
            rings.rejectedMerged++;
            return;
        }
        if (neckness < minimumNeckness) {
            rings.rejectedBelowThreshold++;
            return;
        }
        int candidate = candidatePolyline.size();
        candidatePolyline.add(ring.polyline);
        candidateEdgeIds.add(ring.markedEdgeIds);
        growCandidateArrays(candidate + 1);
        candidateCentroid[COORDINATES_PER_POINT * candidate] = ring.centroidX;
        candidateCentroid[COORDINATES_PER_POINT * candidate + 1] = ring.centroidY;
        candidateCentroid[COORDINATES_PER_POINT * candidate + 2] = ring.centroidZ;
        candidateLength[candidate] = (float) ring.length;
        candidateSeedLength[candidate] = (float) ring.seedLength;
        candidateMeanRadius[candidate] = (float) ring.meanRadius;
        candidateRegionOnOneSide[candidate] = region;
        candidateRegionOnOtherSide[candidate] = facing;
        candidateSkeletonRadius[candidate] = radius;
        candidateNeckness[candidate] = neckness;
    }

    private void growCandidateArrays(int count) {
        candidateCentroid = Arrays.copyOf(candidateCentroid, COORDINATES_PER_POINT * count);
        candidateLength = Arrays.copyOf(candidateLength, count);
        candidateSeedLength = Arrays.copyOf(candidateSeedLength, count);
        candidateMeanRadius = Arrays.copyOf(candidateMeanRadius, count);
        candidateRegionOnOneSide = Arrays.copyOf(candidateRegionOnOneSide, count);
        candidateRegionOnOtherSide = Arrays.copyOf(candidateRegionOnOtherSide, count);
        candidateSkeletonRadius = Arrays.copyOf(candidateSkeletonRadius, count);
        candidateNeckness = Arrays.copyOf(candidateNeckness, count);
    }

    /**
     * Three vertices spread around a rim component: the one farthest from its centroid, the one
     * farthest from that, and the one farthest from both.
     */
    private int[] spreadWaypoints(int[] component) {
        float[] positions = dense.copyPositions();
        double centerX = 0.0;
        double centerY = 0.0;
        double centerZ = 0.0;
        for (int vertex : component) {
            centerX += positions[COORDINATES_PER_POINT * vertex];
            centerY += positions[COORDINATES_PER_POINT * vertex + 1];
            centerZ += positions[COORDINATES_PER_POINT * vertex + 2];
        }
        centerX /= component.length;
        centerY /= component.length;
        centerZ /= component.length;
        int first = component[0];
        double bestDistance = -1.0;
        for (int vertex : component) {
            double distance = distanceToPoint(positions, vertex, centerX, centerY, centerZ);
            if (distance > bestDistance) {
                bestDistance = distance;
                first = vertex;
            }
        }
        int second = component[0];
        bestDistance = -1.0;
        for (int vertex : component) {
            double distance = distanceBetween(positions, vertex, first);
            if (distance > bestDistance) {
                bestDistance = distance;
                second = vertex;
            }
        }
        int third = component[0];
        bestDistance = -1.0;
        for (int vertex : component) {
            double distance = Math.min(distanceBetween(positions, vertex, first),
                    distanceBetween(positions, vertex, second));
            if (distance > bestDistance) {
                bestDistance = distance;
                third = vertex;
            }
        }
        return new int[] {
            mesh.vertexIdAt(first), mesh.vertexIdAt(second), mesh.vertexIdAt(third) };
    }

    /**
     * Whether the loop just tightened is one an accepted ring already cuts: it either runs along
     * the same edges, or it is a parallel loop of the same length a short way along the same limb.
     *
     * @param ring the loop just tightened
     * @return true when an accepted ring already cuts the same place
     */
    private boolean repeatsAcceptedRing(SurfaceRing ring) {
        int[] edgeIds = ring.markedEdgeIds;
        for (int accepted = 0; accepted < candidateEdgeIds.size(); accepted++) {
            int[] acceptedEdges = candidateEdgeIds.get(accepted);
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
            double lengthGap = Math.abs(ring.length - candidateLength[accepted]);
            double centroidX = ring.centroidX - candidateCentroid[COORDINATES_PER_POINT * accepted];
            double centroidY =
                    ring.centroidY - candidateCentroid[COORDINATES_PER_POINT * accepted + 1];
            double centroidZ =
                    ring.centroidZ - candidateCentroid[COORDINATES_PER_POINT * accepted + 2];
            double centroidGap =
                    centroidX * centroidX + centroidY * centroidY + centroidZ * centroidZ;
            if (lengthGap < MERGE_LENGTH_TOLERANCE * candidateLength[accepted]
                    && Math.sqrt(centroidGap)
                            < mergeDistanceInRadii * candidateMeanRadius[accepted]) {
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
        int count = candidatePolyline.size();
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
            for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
                int byAxis = Float.compare(
                        candidateCentroid[COORDINATES_PER_POINT * leftIndex + axis],
                        candidateCentroid[COORDINATES_PER_POINT * rightIndex + axis]);
                if (byAxis != 0) {
                    return byAxis;
                }
            }
            return Float.compare(candidateLength[leftIndex], candidateLength[rightIndex]);
        });

        rings.ringCount = count;
        rings.polylineOffset = new int[count + 1];
        rings.markedEdgeOffset = new int[count + 1];
        rings.centroid = new float[COORDINATES_PER_POINT * count];
        rings.length = new float[count];
        rings.seedLength = new float[count];
        rings.meanRadius = new float[count];
        rings.branchOnOneSide = new int[count];
        rings.branchOnOtherSide = new int[count];
        rings.regionOnOneSide = new int[count];
        rings.regionOnOtherSide = new int[count];
        rings.areaOnOneSide = new float[count];
        rings.areaOnOtherSide = new float[count];
        rings.skeletonRadius = new float[count];
        rings.neckness = new float[count];
        int pointTotal = 0;
        int edgeTotal = 0;
        for (int ring = 0; ring < count; ring++) {
            pointTotal += candidatePolyline.get(order[ring]).length / COORDINATES_PER_POINT;
            edgeTotal += candidateEdgeIds.get(order[ring]).length;
        }
        rings.polylinePoint = new float[COORDINATES_PER_POINT * pointTotal];
        rings.markedEdgeId = new int[edgeTotal];
        int pointFill = 0;
        int edgeFill = 0;
        for (int ring = 0; ring < count; ring++) {
            int candidate = order[ring];
            float[] polyline = candidatePolyline.get(candidate);
            int[] edgeIds = candidateEdgeIds.get(candidate);
            rings.polylineOffset[ring] = pointFill / COORDINATES_PER_POINT;
            rings.markedEdgeOffset[ring] = edgeFill;
            System.arraycopy(polyline, 0, rings.polylinePoint, pointFill, polyline.length);
            System.arraycopy(edgeIds, 0, rings.markedEdgeId, edgeFill, edgeIds.length);
            pointFill += polyline.length;
            edgeFill += edgeIds.length;
            System.arraycopy(candidateCentroid, COORDINATES_PER_POINT * candidate, rings.centroid,
                    COORDINATES_PER_POINT * ring, COORDINATES_PER_POINT);
            rings.length[ring] = candidateLength[candidate];
            rings.seedLength[ring] = candidateSeedLength[candidate];
            rings.meanRadius[ring] = candidateMeanRadius[candidate];
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
        rings.polylineOffset[count] = pointFill / COORDINATES_PER_POINT;
        rings.markedEdgeOffset[count] = edgeFill;
        return rings;
    }

    private int neighborVertex(int vertexId, int spoke) {
        int halfEdge = mesh.edgeHalfEdge(mesh.vertexEdgeAt(vertexId, spoke));
        int start = mesh.halfEdgeVertex(halfEdge);
        int otherId = start == vertexId ? mesh.halfEdgeEndVertex(halfEdge) : start;
        return otherId < 0 ? -1 : vertexIndexByVertexId[otherId];
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

    private static double distanceToPoint(float[] positions, int vertex, double x, double y,
            double z) {
        double dx = positions[COORDINATES_PER_POINT * vertex] - x;
        double dy = positions[COORDINATES_PER_POINT * vertex + 1] - y;
        double dz = positions[COORDINATES_PER_POINT * vertex + 2] - z;
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
