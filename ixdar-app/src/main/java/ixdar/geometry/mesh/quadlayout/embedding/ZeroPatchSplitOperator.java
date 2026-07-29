package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
     * Splits one non-simple zero-patch into simple ones, extending every T-joint it carries.
     *
     * <p>LCBK19 §6.1: <em>"this operation splits a non-simple zero-patch into several simple
     * zero-patches"</em>. Only this patch's descendants are pursued; a neighbour made non-simple by
     * splitting a shared boundary arc belongs to a later application.
     *
     * @param patchId non-simple zero-patch to split
     * @throws IllegalStateException when a half has no extendable T-joint
     */
    public void split(int patchId) {
        Deque<Integer> pending = new ArrayDeque<>();
        pending.push(patchId);
        int extensionBudget = tmesh.arcs.size();
        while (!pending.isEmpty()) {
            int half = pending.pop();
            if (!tmesh.patches.get(half).alive || !tmesh.isZeroPatch(half)
                    || tmesh.nonZeroArcCount(half) <= 2) {
                continue;
            }
            if (extensionBudget-- <= 0) {
                throw new IllegalStateException("zero-patch split of patch " + patchId
                        + " exceeded " + tmesh.arcs.size() + " T-joint extensions; a half keeps"
                        + " re-qualifying (currently patch " + half + " with "
                        + tmesh.nonZeroArcCount(half) + " non-zero arcs)");
            }
            for (int descendant : extendOneTJoint(half)) {
                pending.push(descendant);
            }
        }
    }

    /**
     * Extends one T-joint across a non-simple zero-patch, inserting a zero-arc and cutting the
     * patch in two. The T-mesh gains one node, two arcs and one patch, leaving its Euler
     * characteristic unchanged.
     *
     * @param patchId non-simple zero-patch to cut
     * @throws IllegalStateException when the patch has no extendable T-joint
     * @return the ids of the two halves
     */
    private int[] extendOneTJoint(int patchId) {
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
                EmbeddedMeshTopology.UNCLAIMED)) {
            throw new IllegalStateException("could not route a zero-arc across patch " + patchId
                    + " from node " + tjointNodeId + " to node " + oppositeNodeId);
        }
        int newArc = tmesh.addArc(EmbeddedTMesh.NONE, tjointNodeId, oppositeNodeId, 0, false, routed);
        int[] halves = tmesh.splitPatchByArc(patchId, newArc);
        splitCount++;
        return halves;
    }

    /**
     * The first T-joint of a non-simple zero-patch: an interior node of one of its non-zero
     * sides, with the node's quantized offset from that side's start.
     *
     * @param patchId patch to search
     * @throws IllegalStateException when the patch has no interior node on a non-zero side
     * @return {@code {side, nodeId, offset}}
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
        throw new IllegalStateException("patch " + patchId + " has no extendable T-joint");
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
     * @throws IllegalStateException when the offset lies outside the side
     * @return the node id at that offset
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
     * @throws IllegalStateException when the arc's path has no interior vertex to split at
     * @return the index of the nearest strictly interior path vertex
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
     * @throws IllegalStateException when no side of any boundary arc floods an interior
     * @return the copy faces it covers
     */
    public IntIdList patchFaces(int patchId) {
        return floodWithin(patchWall(patchId), seedFaceInside(patchId));
    }

    /**
     * A copy face lying inside a patch, taken from the interior side of one of its boundary arcs.
     *
     * <p>The interior side comes from {@code leftPatchId}, which records the direction
     * {@code addPatch} walked rather than a fact about the surface, so a layout walked the other
     * way seeds outside. See {@code PatchInteriorSeedTest}.
     *
     * @param patchId patch to seed a flood inside
     * @throws IllegalStateException when the patch has no live boundary arc to take a side from
     * @return a copy face it covers
     */
    private int seedFaceInside(int patchId) {
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            for (int boundaryArcId : tmesh.patches.get(patchId).sideArcIds.get(side)) {
                EmbeddedArc boundaryArc = tmesh.arcs.get(boundaryArcId);
                if (!boundaryArc.alive || boundaryArc.path.copyVertexPath.size() < 2) {
                    continue;
                }
                int faceId = seedFromArc(boundaryArc, boundaryArc.leftPatchId == patchId);
                if (faceId != EmbeddedMeshTopology.UNCLAIMED) {
                    return faceId;
                }
            }
        }
        throw new IllegalStateException("patch " + patchId
                + " has no live boundary arc to seed its interior from");
    }

    /**
     * The copy edges a patch's boundary arcs run along — the wall a flood of its interior may not
     * cross.
     *
     * @param patchId patch whose boundary is wanted
     * @return the boundary's copy edges
     */
    private ActiveIdSet patchWall(int patchId) {
        ActiveIdSet wall = new ActiveIdSet(tmesh.topology.copy.edgeCount());
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            for (int boundaryArcId : tmesh.patches.get(patchId).sideArcIds.get(side)) {
                for (int edgeId : tmesh.arcs.get(boundaryArcId).path.copyEdgePath) {
                    wall.add(edgeId);
                }
            }
        }
        return wall;
    }

    /**
     * The faces reachable from a seed without crossing a wall edge.
     *
     * @param wall edges the flood stops at
     * @param seed face to flood from
     * @return the reachable faces, seed first
     */
    private IntIdList floodWithin(ActiveIdSet wall, int seed) {
        HalfEdgeMesh copy = tmesh.topology.copy;
        ActiveIdSet visited = new ActiveIdSet(copy.faceCount());
        IntIdList faces = new IntIdList(copy.faceCount());
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
     * The copy face on one side of a boundary arc's first hop.
     *
     * <p>A half-edge carries the face on its left, so walking the arc's path forwards yields the
     * face to the arc's left and backwards the face to its right.
     *
     * @param boundaryArc arc to take a side from
     * @param takeLeft    whether to take the face left of the arc's forward direction
     * @return the copy face on that side, or {@link EmbeddedMeshTopology#UNCLAIMED} off the mesh
     */
    private int seedFromArc(EmbeddedArc boundaryArc, boolean takeLeft) {
        HalfEdgeMesh copy = tmesh.topology.copy;
        List<Integer> path = boundaryArc.path.copyVertexPath;
        int from = takeLeft ? path.get(0) : path.get(1);
        int to = takeLeft ? path.get(1) : path.get(0);
        int halfEdge = copy.edgeHalfEdge(tmesh.topology.edgeBetween(from, to));
        if (copy.halfEdgeVertex(halfEdge) != from) {
            halfEdge = copy.halfEdgeTwin(halfEdge);
        }
        return copy.halfEdgeFace(halfEdge);
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
