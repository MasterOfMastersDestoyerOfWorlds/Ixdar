package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.ActiveIdSet;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.IntIdList;

/**
 * Operator (2), the non-simple zero-patch split: extends one T-joint across the patch as a new
 * zero-arc. A half still carrying a T-joint is split again later.
 *
 * <p>The new arc is quantized to zero but routed as an edge path inside the patch.
 *
 * <p>See also: LCBK19 Section 6.1
 */
public final class ZeroPatchSplitOperator {

    /** Diagnostic prefix naming a patch. */
    private static final String PATCH_TAG = "patch ";

    public final EmbeddedTMesh tmesh;
    public final ArcRerouter rerouter;

    public int splitCount;

    /**
     * Stores the T-mesh and builds the router over its working copy.
     *
     * @param tmesh embedded T-mesh whose non-simple zero-patches are split
     */
    public ZeroPatchSplitOperator(EmbeddedTMesh tmesh) {
        this.tmesh = tmesh;
        this.rerouter = new ArcRerouter(tmesh.topology);
    }

    /**
     * The id of a live non-simple zero-patch, or {@link EmbeddedTMesh#NONE} when none remains
     * — the driver's "is operator (2) applicable" test.
     *
     * @return a non-simple zero-patch id, or {@link EmbeddedTMesh#NONE}
     */
    public int nextNonSimpleZeroPatch() {
        for (EmbeddedPatch patch : tmesh.patches) {
            if (patch.alive && tmesh.isZeroPatch(patch.patchId)
                    && tmesh.nonZeroArcCount(patch.patchId) > 2) {
                return patch.patchId;
            }
        }
        return EmbeddedTMesh.NONE;
    }

    /**
     * Splits one non-simple zero-patch: extends its first T-joint across to the opposite side,
     * inserting a zero-arc, and cuts the patch in two. The T-mesh gains one node, two arcs and
     * one patch, leaving its Euler characteristic unchanged.
     *
     * @param patchId non-simple zero-patch to split
     * @throws IllegalStateException when the patch has no extendable T-joint
     */
    public void split(int patchId) {
        int[] tjoint = findTJoint(patchId);
        int side = tjoint[0];
        int tjointNodeId = tjoint[1];
        int offset = tjoint[2];

        int oppositeSide = (side + 2) % EmbeddedPatch.SIDES;
        int oppositeOffset = tmesh.oppositeOffset(patchId, side, offset);
        int oppositeNodeId = nodeAtOffsetOrSplit(patchId, oppositeSide, oppositeOffset);

        ActiveIdSet corridor = corridorVerticesOf(patchFaces(patchId));
        int startVertex = tmesh.nodes.get(tjointNodeId).copyVertex;
        int endVertex = tmesh.nodes.get(oppositeNodeId).copyVertex;

        List<Integer> routed = new ArrayList<>();
        if (!rerouter.tryRoute(EmbeddedTMesh.NONE, routed, startVertex, endVertex, corridor,
                EmbeddedMeshTopology.UNCLAIMED, ArcRerouter.REFINE_ROUND_CAP)) {
            throw new IllegalStateException("could not route a zero-arc across patch " + patchId
                    + " from node " + tjointNodeId + " to node " + oppositeNodeId);
        }
        int newArc = tmesh.addArc(EmbeddedTMesh.NONE, tjointNodeId, oppositeNodeId, 0, false, routed);
        tmesh.splitPatchByArc(patchId, newArc);
        splitCount++;
    }

    /**
     * The first T-joint of a non-simple zero-patch: an interior node of one of its non-zero
     * sides, with the node's quantized offset from that side's start.
     *
     * @param patchId patch to search
     * @return {@code {side, nodeId, offset}}
     * @throws IllegalStateException when the patch has no interior node on a non-zero side
     */
    private int[] findTJoint(int patchId) {
        EmbeddedPatch patch = tmesh.patches.get(patchId);
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            if (tmesh.sideQuantizedLength(patchId, side) == 0) {
                continue;
            }
            List<Integer> sideArcs = patch.sideArcIds.get(side);
            List<Integer> sideNodes = patch.sideNodeIds.get(side);
            int offset = 0;
            for (int index = 0; index < sideArcs.size(); index++) {
                offset += tmesh.arcs.get(sideArcs.get(index)).quantizedLength;
                if (index < sideArcs.size() - 1) {
                    return new int[] { side, sideNodes.get(index + 1), offset };
                }
            }
        }
        throw new IllegalStateException(PATCH_TAG + patchId + " has no extendable T-joint");
    }

    /**
     * The node at a quantized offset along a patch side, inserting one by splitting an arc
     * when no node sits exactly there.
     *
     * <p>See also: LCBK19 Section 6.1
     *
     * @param patchId patch the side belongs to
     * @param side    side to walk
     * @param offset  quantized offset from the side's start
     * @return the node id at that offset
     * @throws IllegalStateException when the offset lies outside the side
     */
    private int nodeAtOffsetOrSplit(int patchId, int side, int offset) {
        EmbeddedPatch patch = tmesh.patches.get(patchId);
        List<Integer> sideArcs = patch.sideArcIds.get(side);
        List<Integer> sideNodes = patch.sideNodeIds.get(side);
        int cumulative = 0;
        if (offset == 0) {
            return sideNodes.get(0);
        }
        for (int index = 0; index < sideArcs.size(); index++) {
            int arcId = sideArcs.get(index);
            EmbeddedArc arc = tmesh.arcs.get(arcId);
            int nextCumulative = cumulative + arc.quantizedLength;
            if (offset == nextCumulative) {
                return sideNodes.get(index + 1);
            }
            if (offset < nextCumulative) {
                int offsetIntoArc = offset - cumulative;
                boolean forward = sideNodes.get(index) == arc.startNodeId;
                int arcOffset = forward ? offsetIntoArc : arc.quantizedLength - offsetIntoArc;
                int pathVertexIndex = interiorPathVertexAtFraction(arc,
                        (double) arcOffset / arc.quantizedLength);
                int[] halves = tmesh.splitArc(arcId, arcOffset, pathVertexIndex);
                return tmesh.arcs.get(halves[0]).endNodeId;
            }
            cumulative = nextCumulative;
        }
        throw new IllegalStateException("offset " + offset + " lies beyond side " + side
                + " of patch " + patchId);
    }

    /**
     * The interior path vertex of an arc nearest a fraction of its 3D arc length — LCBK19
     * operator (2) places the split node "at the corresponding location", and 3D arc length is
     * the only intrinsic parameter a rerouted arc still carries.
     *
     * @param arc      arc to split
     * @param fraction fraction of the arc's length, in {@code (0, 1)}
     * @return the index of the nearest strictly interior path vertex
     * @throws IllegalStateException when the arc's path has no interior vertex to split at
     */
    private int interiorPathVertexAtFraction(EmbeddedArc arc, double fraction) {
        List<Integer> vertices = arc.path.copyVertexPath;
        if (vertices.size() < 3) {
            throw new IllegalStateException(arc.arcId + " is a single-edge arc and cannot host a"
                    + " split node without mesh refinement");
        }
        double[] cumulative = new double[vertices.size()];
        Vector3f here = new Vector3f();
        Vector3f previous = new Vector3f();
        tmesh.topology.copy.vertexPosition(vertices.get(0), previous);
        for (int index = 1; index < vertices.size(); index++) {
            tmesh.topology.copy.vertexPosition(vertices.get(index), here);
            cumulative[index] = cumulative[index - 1] + previous.distance(here);
            previous.set(here);
        }
        double target = fraction * cumulative[vertices.size() - 1];
        int best = 1;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int index = 1; index < vertices.size() - 1; index++) {
            double distance = Math.abs(cumulative[index] - target);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = index;
            }
        }
        return best;
    }

    /**
     * The copy faces one patch covers, found by flooding outwards from inside it and stopping at its
     * own boundary arcs.
     *
     * <p>Local by necessity: a live zero-patch encloses no faces, so {@link PatchRegions} cannot
     * answer this while operator (2) applies.
     *
     * @param patchId patch whose faces are wanted
     * @return the copy faces it covers
     */
    private IntIdList patchFaces(int patchId) {
        HalfEdgeMesh copy = tmesh.topology.copy;
        ActiveIdSet wall = new ActiveIdSet(copy.edgeCount());
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            for (int boundaryArcId : tmesh.patches.get(patchId).sideArcIds.get(side)) {
                for (int edgeId : tmesh.arcs.get(boundaryArcId).path.copyEdgePath) {
                    wall.add(edgeId);
                }
            }
        }
        ActiveIdSet visited = new ActiveIdSet(copy.faceCount());
        IntIdList faces = new IntIdList(copy.faceCount());
        int seed = seedFaceInside(patchId);
        visited.add(seed);
        faces.add(seed);
        for (int cursor = 0; cursor < faces.size(); cursor++) {
            int faceId = faces.get(cursor);
            for (int corner = 0; corner < copy.faceHalfEdgeCount(faceId); corner++) {
                int edgeId = copy.faceEdgeAt(faceId, corner);
                if (wall.contains(edgeId)) {
                    continue;
                }
                int halfEdge = copy.edgeHalfEdge(edgeId);
                int neighbour = copy.halfEdgeFace(halfEdge) == faceId
                        ? copy.halfEdgeFace(copy.halfEdgeTwin(halfEdge))
                        : copy.halfEdgeFace(halfEdge);
                if (neighbour != EmbeddedMeshTopology.UNCLAIMED && !visited.contains(neighbour)) {
                    visited.add(neighbour);
                    faces.add(neighbour);
                }
            }
        }
        return faces;
    }

    /**
     * A copy face lying inside a patch, taken from the interior side of one of its boundary arcs.
     *
     * @param patchId patch to seed a flood inside
     * @return a copy face it covers
     * @throws IllegalStateException when the patch has no live boundary arc to take a side from
     */
    private int seedFaceInside(int patchId) {
        HalfEdgeMesh copy = tmesh.topology.copy;
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            for (int boundaryArcId : tmesh.patches.get(patchId).sideArcIds.get(side)) {
                EmbeddedArc boundaryArc = tmesh.arcs.get(boundaryArcId);
                List<Integer> path = boundaryArc.path.copyVertexPath;
                if (!boundaryArc.alive || path.size() < 2) {
                    continue;
                }
                boolean patchOnLeft = boundaryArc.leftPatchId == patchId;
                int from = patchOnLeft ? path.get(0) : path.get(1);
                int to = patchOnLeft ? path.get(1) : path.get(0);
                int halfEdge = copy.edgeHalfEdge(tmesh.topology.edgeBetween(from, to));
                if (copy.halfEdgeVertex(halfEdge) != from) {
                    halfEdge = copy.halfEdgeTwin(halfEdge);
                }
                int faceId = copy.halfEdgeFace(halfEdge);
                if (faceId != EmbeddedMeshTopology.UNCLAIMED) {
                    return faceId;
                }
            }
        }
        throw new IllegalStateException(PATCH_TAG + patchId
                + " has no live boundary arc to seed its interior from");
    }

    /**
     * The set of copy vertices bounding a patch's faces, seeding the re-route corridor.
     *
     * @param faces the patch's copy faces
     * @return the vertices of those faces
     */
    private ActiveIdSet corridorVerticesOf(IntIdList faces) {
        ActiveIdSet corridor = rerouter.freshCorridor();
        for (int index = 0; index < faces.size(); index++) {
            int faceId = faces.get(index);
            for (int corner = 0; corner < tmesh.topology.copy.faceHalfEdgeCount(faceId); corner++) {
                corridor.add(tmesh.topology.copy.faceVertexAt(faceId, corner));
            }
        }
        return corridor;
    }
}
