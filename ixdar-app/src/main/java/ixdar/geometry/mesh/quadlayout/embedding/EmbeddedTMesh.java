package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.joml.Vector3f;

/**
 * The T-mesh, embedded: one mutable structure holding the nodes, arcs and patches of the
 * quad layout together with their realization on the working copy of the triangle mesh.
 *
 * <p>There is deliberately only one of these. CBK15, which defines the T-mesh, is explicit
 * that it never embeds it — <em>"we only make use of T's topological structure — its
 * geometric embedding is of no concern"</em> — and LCBK19 §6 exists precisely to make the
 * T-mesh geometrically real on the surface, because the per-patch maps of §6.2 need patch
 * boundaries that are chains of actual triangle edges. That is why combinatorics and
 * embedding cannot live in separate structures here: every operator changes both at once,
 * and any arrangement where one half can drift out of step with the other is a bug
 * waiting to be written.
 *
 * <p>Nothing is ever removed from the three lists. Elements are retired by clearing their
 * {@code alive} flag, so an id is always its index and stays valid forever. Ids are handed
 * out by appending, so operators may mint nodes and arcs freely.
 *
 * <p>This class is the only writer of the claim arrays on {@link EmbeddedMeshTopology}.
 * Those arrays are not bookkeeping — they <em>are</em> LCBK19's one-to-one mapping between
 * T-mesh elements and mesh elements, and that mapping is the entire reason the embedded
 * arcs cannot cross: two paths in a mesh's 1-skeleton that share no vertex and no edge are
 * disjoint curves. Letting several classes write those arrays independently is how the
 * mapping stops being one-to-one without anybody noticing.
 */
public final class EmbeddedTMesh {

    /** Absent id, for elements with no source and for unset patch references. */
    public static final int NONE = -1;

    /** Corners of a triangular copy face. */
    private static final int TRIANGLE_CORNERS = 3;

    /** Split position for a midpoint edge split. */
    private static final double EDGE_MIDPOINT = 0.5;

    /** Message fragment naming a patch. */
    private static final String PATCH = "patch ";

    /** Message fragment naming an arc. */
    private static final String ARC = "arc ";

    /** Message fragment naming a side of a patch. */
    private static final String SIDE = " side ";

    /** Message fragment joining two ids. */
    private static final String TO = " to ";

    /** Message fragment introducing a count. */
    private static final String HAS = " has ";

    /** Message fragment joining two listed ids. */
    private static final String AND = " and ";

    /** Message fragment for an element absent from a patch boundary. */
    private static final String NOT_ON_BOUNDARY = " is not on the boundary of ";

    public final EmbeddedMeshTopology topology;

    /** Every node ever created; retired ones are still here, with {@code alive} false. */
    public final List<EmbeddedNode> nodes;

    /** Every arc ever created; retired ones are still here, with {@code alive} false. */
    public final List<EmbeddedArc> arcs;

    /** Every patch ever created; retired ones are still here, with {@code alive} false. */
    public final List<EmbeddedPatch> patches;

    /**
     * Arc ends incident to each node: the id of every live arc with an end at that node,
     * a loop appearing twice. The count is the node's degree, which is what the T-junction
     * and collapse rules are phrased in terms of, and which the embedding otherwise has no
     * way to ask — the topology's claim arrays record a single owner per element, not the
     * set of arcs meeting at a vertex.
     */
    public final List<List<Integer>> arcEndsByNode;

    /**
     * Creates an empty T-mesh over a working copy.
     *
     * @param topology working copy the T-mesh is embedded in
     */
    public EmbeddedTMesh(EmbeddedMeshTopology topology) {
        this.topology = topology;
        this.nodes = new ArrayList<>();
        this.arcs = new ArrayList<>();
        this.patches = new ArrayList<>();
        this.arcEndsByNode = new ArrayList<>();
    }

    /**
     * Adds a node on a copy vertex.
     *
     * @param sourceNodeId originating {@code TMeshNode} id, or {@link #NONE}
     * @param copyVertex   vertex of the working copy the node sits on
     * @param critical     whether the node's position is prescribed (LCBK19 Def 6.2)
     * @param border       whether the node lies in the surface boundary (LCBK19 Def 6.1)
     * @return the new node's id
     */
    public int addNode(int sourceNodeId, int copyVertex, boolean critical, boolean border) {
        int nodeId = nodes.size();
        nodes.add(new EmbeddedNode(nodeId, sourceNodeId, copyVertex, critical, border));
        arcEndsByNode.add(new ArrayList<>());
        topology.ownerNodeByCopyVertex[copyVertex] = nodeId;
        return nodeId;
    }

    /**
     * Adds an arc between two nodes, realized by a path of copy vertices, and claims the
     * mesh elements it runs along.
     *
     * <p>The caller gives vertices, not an {@link ArcEdgePath}, because an arc's path is
     * stamped with the arc's own id and that id does not exist until the arc does. The
     * edges are looked up rather than supplied, so a path that does not actually walk the
     * mesh is rejected here rather than surviving to be discovered later.
     *
     * @param sourceArcId     originating {@code TraceArc} id, or {@link #NONE}
     * @param startNodeId     node the arc runs from
     * @param endNodeId       node the arc runs to
     * @param quantizedLength prescribed parametric length, never negative
     * @param feature         whether the arc lies on a feature or boundary curve
     * @param vertexPath      copy vertices the arc passes through, from its start node's
     *                        vertex to its end node's vertex
     * @return the new arc's id
     * @throws IllegalStateException when consecutive vertices of the path are not joined
     *                               by an edge of the working copy
     */
    public int addArc(int sourceArcId, int startNodeId, int endNodeId, int quantizedLength,
            boolean feature, List<Integer> vertexPath) {
        int arcId = arcs.size();
        List<Integer> vertices = new ArrayList<>(vertexPath);
        List<Integer> edges = new ArrayList<>(vertices.size() - 1);
        for (int index = 1; index < vertices.size(); index++) {
            edges.add(requireEdge(arcId, vertices.get(index - 1), vertices.get(index)));
        }
        ArcEdgePath path = new ArcEdgePath(arcId, vertices, edges);
        arcs.add(new EmbeddedArc(arcId, sourceArcId, startNodeId, endNodeId, quantizedLength,
                feature, path));
        arcEndsByNode.get(startNodeId).add(arcId);
        arcEndsByNode.get(endNodeId).add(arcId);
        topology.claimPath(arcId, path);
        return arcId;
    }

    /**
     * Adds a patch whose sides are given as chains of arcs, walking the boundary in one
     * consistent cyclic direction. The node chain of each side is derived from the arcs,
     * so the caller does not state it twice and cannot state it inconsistently.
     *
     * @param sourcePatchId originating {@code TMeshPatch} id, or {@link #NONE}
     * @param sideArcIds    four sides, each a list of arc ids in the side's walking order
     * @param firstCornerId node the first side starts at, which fixes the walk's direction
     * @return the new patch's id
     * @throws IllegalStateException when the given arcs do not chain into a closed boundary
     */
    public int addPatch(int sourcePatchId, List<List<Integer>> sideArcIds, int firstCornerId) {
        int patchId = patches.size();
        EmbeddedPatch patch = new EmbeddedPatch(patchId, sourcePatchId);
        patches.add(patch);
        int walkNode = firstCornerId;
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            List<Integer> sideArcs = sideArcIds.get(side);
            patch.sideArcIds.get(side).addAll(sideArcs);
            patch.sideNodeIds.get(side).add(walkNode);
            for (int arcId : sideArcs) {
                EmbeddedArc arc = arcs.get(arcId);
                if (arc.startNodeId != walkNode && arc.endNodeId != walkNode) {
                    throw new IllegalStateException(PATCH + patchId + SIDE + side
                            + ": arc " + arcId + " does not touch node " + walkNode);
                }
                boolean forward = arc.startNodeId == walkNode;
                if (forward) {
                    arc.leftPatchId = patchId;
                } else {
                    arc.rightPatchId = patchId;
                }
                walkNode = arc.otherNode(walkNode);
                patch.sideNodeIds.get(side).add(walkNode);
            }
        }
        if (walkNode != firstCornerId) {
            throw new IllegalStateException(PATCH + patchId
                    + " boundary does not close: walked back to node " + walkNode
                    + " instead of " + firstCornerId);
        }
        return patchId;
    }

    /**
     * The number of live arc ends at a node.
     *
     * @param nodeId node to measure
     * @return the node's degree, a loop counting twice
     */
    public int degree(int nodeId) {
        int degree = 0;
        for (int arcId : arcEndsByNode.get(nodeId)) {
            if (arcs.get(arcId).alive) {
                degree++;
            }
        }
        return degree;
    }

    /**
     * The total quantized length of one side of a patch.
     *
     * @param patchId patch to measure
     * @param side    side index in {@code [0, 4)}
     * @return the sum of the side's arcs' quantized lengths; zero for an empty side
     */
    public int sideQuantizedLength(int patchId, int side) {
        int total = 0;
        for (int arcId : patches.get(patchId).sideArcIds.get(side)) {
            total += arcs.get(arcId).quantizedLength;
        }
        return total;
    }

    /**
     * The offset on the opposite side of a patch matching an offset on one side — the
     * "corresponding point" that LCBK19 operator (2) and LCK21a's T-junction extension
     * both need.
     *
     * <p>It is a subtraction rather than an identity because sides {@code i} and
     * {@code i + 2} are walked in opposite directions around the patch boundary, so an
     * offset measured from the start of one is measured from the <em>end</em> of the
     * other. Getting this backwards silently connects a T-junction to the wrong place, and
     * the result still looks like a valid layout, so it is written down once, here, and
     * nowhere else.
     *
     * @param patchId patch to measure across
     * @param side    side the offset is measured on, in {@code [0, 4)}
     * @param offset  quantized offset from the start of that side
     * @return the matching quantized offset from the start of the opposite side
     */
    public int oppositeOffset(int patchId, int side, int offset) {
        int oppositeSide = (side + 2) % EmbeddedPatch.SIDES;
        return sideQuantizedLength(patchId, oppositeSide) - offset;
    }

    /**
     * The number of a patch's live boundary arcs whose quantized length is positive.
     *
     * <p>This is what classifies a zero-patch, and it is counted over arcs rather than
     * sides on purpose. A side can carry both positive and zero arcs, and the node between
     * them is a T-joint — which is exactly what makes a zero-patch <em>non-simple</em> in
     * LCBK19's sense, and what operator (2) must extend through the patch. Counting sides
     * would miss it.
     *
     * @param patchId patch to measure
     * @return the count: zero for a point patch, two for a simple zero-patch, more for a
     *         non-simple one
     */
    public int nonZeroArcCount(int patchId) {
        int count = 0;
        EmbeddedPatch patch = patches.get(patchId);
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            for (int arcId : patch.sideArcIds.get(side)) {
                EmbeddedArc arc = arcs.get(arcId);
                if (arc.alive && arc.quantizedLength > 0) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Whether the quantization gives a patch zero parametric area, so that it must be
     * re-embedded onto a curve or a point before the per-patch map of LCBK19 §6.2 can
     * exist at all — a patch with a zero-area parameter domain and a positive area on the
     * surface has no bijective map between them.
     *
     * @param patchId patch to test
     * @return true when either of the patch's two dimensions is zero
     */
    public boolean isZeroPatch(int patchId) {
        return sideQuantizedLength(patchId, 0) == 0 || sideQuantizedLength(patchId, 1) == 0;
    }

    /**
     * Re-routes an arc along a new path of copy vertices, releasing the mesh elements it
     * used to hold and claiming the ones it now runs along.
     *
     * <p>This is what LCBK19 operator (1) means by "pulling its incident arcs with it, i.e.
     * their embedding path is adjusted such that they connect to n0 at its new position".
     * The release has to happen before the claim: an arc that keeps most of its old lane
     * would otherwise collide with itself.
     *
     * @param arcId      arc to re-route
     * @param vertexPath copy vertices the arc now passes through, end to end
     * @throws IllegalStateException when consecutive vertices are not joined by an edge
     */
    public void setPath(int arcId, List<Integer> vertexPath) {
        EmbeddedArc arc = arcs.get(arcId);
        releaseClaims(arc);
        List<Integer> vertices = new ArrayList<>(vertexPath);
        List<Integer> edges = new ArrayList<>(Math.max(0, vertices.size() - 1));
        for (int index = 1; index < vertices.size(); index++) {
            edges.add(requireEdge(arcId, vertices.get(index - 1), vertices.get(index)));
        }
        arc.path = new ArcEdgePath(arcId, vertices, edges);
        topology.claimPath(arcId, arc.path);
    }

    /**
     * Embeds one node onto another: every arc that ended at the discarded node now ends at
     * the kept one, and the discarded node's vertex is handed back to the mesh.
     *
     * <p>The incident arcs' paths must already have been re-routed to reach the kept node's
     * vertex — this changes the T-mesh's combinatorics, not its geometry, and it would
     * happily leave an arc claiming a path that no longer reaches its own node. That is
     * checked, so the mistake cannot survive.
     *
     * @param keepNodeId    node that stays, and that everything is re-pointed at
     * @param discardNodeId node that is embedded onto it
     * @throws IllegalStateException when an arc at the discarded node has not been re-routed
     */
    public void mergeNodeInto(int keepNodeId, int discardNodeId) {
        if (keepNodeId == discardNodeId) {
            return;
        }
        EmbeddedNode keep = nodes.get(keepNodeId);
        EmbeddedNode discard = nodes.get(discardNodeId);
        for (int arcId : arcEndsByNode.get(discardNodeId)) {
            EmbeddedArc arc = arcs.get(arcId);
            if (!arc.alive) {
                continue;
            }
            if (arc.startNodeId == discardNodeId) {
                arc.startNodeId = keepNodeId;
                requireEndOfPathIsAt(arc, arc.path.copyVertexPath.get(0), keep.copyVertex);
            }
            if (arc.endNodeId == discardNodeId) {
                arc.endNodeId = keepNodeId;
                requireEndOfPathIsAt(arc,
                        arc.path.copyVertexPath.get(arc.path.copyVertexPath.size() - 1),
                        keep.copyVertex);
            }
            arcEndsByNode.get(keepNodeId).add(arcId);
        }
        arcEndsByNode.get(discardNodeId).clear();
        for (EmbeddedPatch patch : patches) {
            if (!patch.alive) {
                continue;
            }
            for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
                List<Integer> sideNodes = patch.sideNodeIds.get(side);
                for (int index = 0; index < sideNodes.size(); index++) {
                    if (sideNodes.get(index) == discardNodeId) {
                        sideNodes.set(index, keepNodeId);
                    }
                }
            }
        }
        topology.ownerNodeByCopyVertex[discard.copyVertex] = EmbeddedMeshTopology.UNCLAIMED;
        discard.alive = false;
        keep.critical = keep.critical || discard.critical;
        keep.border = keep.border || discard.border;
    }

    /**
     * Removes an arc whose two ends have become the same node, which is what a zero arc is
     * once {@link #mergeNodeInto} has closed it up. LCBK19: "Arc a is embedded onto a single
     * point (coincident with the nodes n0 and n1)."
     *
     * <p>Each patch the arc bounded loses it from the side it lay on, and loses the node
     * that separated it from its neighbour on that side — because that node and the one
     * before it are now the same node. A side can end up empty, and its two bounding corners
     * then coincide: that is the double corner LCBK19 Figure 9 marks with a red circle, and
     * the state operator (3) is waiting for.
     *
     * @param arcId arc to remove
     * @throws IllegalStateException when the arc's ends are still two different nodes
     */
    public void removeCollapsedArc(int arcId) {
        EmbeddedArc arc = arcs.get(arcId);
        if (!arc.isLoop()) {
            throw new IllegalStateException(ARC + arcId + " cannot be removed as collapsed:"
                    + " its ends are still nodes " + arc.startNodeId + AND + arc.endNodeId);
        }
        releaseClaims(arc);
        for (int patchId : new int[] { arc.leftPatchId, arc.rightPatchId }) {
            if (patchId == NONE || !patches.get(patchId).alive) {
                continue;
            }
            EmbeddedPatch patch = patches.get(patchId);
            int[] position = sidePosition(patchId, arcId);
            List<Integer> sideArcs = patch.sideArcIds.get(position[0]);
            List<Integer> sideNodes = patch.sideNodeIds.get(position[0]);
            sideArcs.remove(position[1]);
            sideNodes.remove(position[1] + 1);
        }
        arcEndsByNode.get(arc.startNodeId).removeIf(id -> id == arcId);
        arc.alive = false;
    }

    /**
     * Retires an arc whose embedding has been abandoned — LCBK19 operator (3)'s dying arc,
     * whose lane the surviving arc's neighbour takes over. It releases the arc's mesh claims,
     * so the freed lane lets the neighbouring region flood across to the surviving arc, and
     * drops it from its nodes' incidence lists.
     *
     * <p>Unlike {@link #removeCollapsedArc} this makes no change to any patch's boundary — the
     * caller has already re-pointed the one patch that used the dying arc onto the survivor and
     * retired the collapsed patch — so it does not require the arc to be a loop.
     *
     * @param arcId arc whose embedding is discarded
     */
    public void discardArc(int arcId) {
        EmbeddedArc arc = arcs.get(arcId);
        releaseClaims(arc);
        arcEndsByNode.get(arc.startNodeId).removeIf(id -> id == arcId);
        arcEndsByNode.get(arc.endNodeId).removeIf(id -> id == arcId);
        arc.alive = false;
    }

    /**
     * Splits an arc at an interior point of its path, inserting a node there and replacing
     * the arc with the two halves, in both of the patches it bounds.
     *
     * <p>The two children carry exactly the two halves of the parent's edge path, so no new
     * geometry is invented and both bordering patches see the split at the same vertex. That
     * is what keeps the layout watertight: a boundary shared by two patches stays one curve.
     *
     * @param arcId           arc to split
     * @param quantizedOffset prescribed length of the first half, measured from the arc's
     *                        start node; the second half takes the remainder
     * @param pathVertexIndex index into the arc's vertex path of the vertex the node lands
     *                        on; must be strictly interior, so that both halves are real
     * @return the ids of the two child arcs, in the parent's direction
     * @throws IllegalStateException when the offset or the vertex would make a half empty
     */
    public int[] splitArc(int arcId, int quantizedOffset, int pathVertexIndex) {
        EmbeddedArc arc = arcs.get(arcId);
        List<Integer> vertices = arc.path.copyVertexPath;
        if (pathVertexIndex <= 0 || pathVertexIndex >= vertices.size() - 1) {
            throw new IllegalStateException(ARC + arcId + " cannot be split at path vertex "
                    + pathVertexIndex + ": the split must be strictly inside a path of "
                    + vertices.size() + " vertices");
        }
        if (quantizedOffset < 0 || quantizedOffset > arc.quantizedLength) {
            throw new IllegalStateException(ARC + arcId + " cannot be split at offset "
                    + quantizedOffset + ": it lies outside the arc's length "
                    + arc.quantizedLength);
        }
        // LCBK19 operator (2): the inserted node is "marked as critical, if the split arc
        // is critical" — a point inserted on a feature or boundary curve inherits that
        // curve's status, and so may never be moved off it.
        int splitVertex = vertices.get(pathVertexIndex);
        int splitNodeId = addNode(NONE, splitVertex, arc.feature, arc.feature);
        releaseClaims(arc);
        arc.alive = false;
        arcEndsByNode.get(arc.startNodeId).removeIf(id -> id == arcId);
        arcEndsByNode.get(arc.endNodeId).removeIf(id -> id == arcId);

        int firstArcId = addArc(arc.sourceArcId, arc.startNodeId, splitNodeId, quantizedOffset,
                arc.feature, vertices.subList(0, pathVertexIndex + 1));
        int secondArcId = addArc(arc.sourceArcId, splitNodeId, arc.endNodeId,
                arc.quantizedLength - quantizedOffset, arc.feature,
                vertices.subList(pathVertexIndex, vertices.size()));
        arcs.get(firstArcId).leftPatchId = arc.leftPatchId;
        arcs.get(firstArcId).rightPatchId = arc.rightPatchId;
        arcs.get(secondArcId).leftPatchId = arc.leftPatchId;
        arcs.get(secondArcId).rightPatchId = arc.rightPatchId;

        for (int patchId : new int[] { arc.leftPatchId, arc.rightPatchId }) {
            if (patchId == NONE || !patches.get(patchId).alive) {
                continue;
            }
            EmbeddedPatch patch = patches.get(patchId);
            int[] position = sidePosition(patchId, arcId);
            List<Integer> sideArcs = patch.sideArcIds.get(position[0]);
            List<Integer> sideNodes = patch.sideNodeIds.get(position[0]);
            boolean forward = sideNodes.get(position[1]) == arc.startNodeId;
            sideArcs.set(position[1], forward ? firstArcId : secondArcId);
            sideArcs.add(position[1] + 1, forward ? secondArcId : firstArcId);
            sideNodes.add(position[1] + 1, splitNodeId);
        }
        return new int[] { firstArcId, secondArcId };
    }

    /**
     * Cuts a patch in two along an arc that already runs across it, from a node on one side
     * to a node on the opposite side — LCBK19 operator (2)'s split, and the same cut the
     * LCK21a T-junction extension makes.
     *
     * <p>The dividing arc joins a point on side {@code s} to a point on side {@code s + 2}, so
     * it splits the patch's boundary cycle into two four-sided halves, each bounded by part of
     * side {@code s}, a whole neighbouring side, part of side {@code s + 2}, and the dividing
     * arc. The originating patch is retired and the two halves are added; the dividing arc
     * ends up bounding both of them, and every other boundary arc keeps the neighbour it had
     * on its far side.
     *
     * @param patchId    patch to cut
     * @param dividerArc arc running from a node on one side to a node on the opposite side
     * @return the ids of the two halves
     * @throws IllegalStateException when the arc's endpoints do not lie on opposite sides of
     *                               the patch's boundary
     */
    public int[] splitPatchByArc(int patchId, int dividerArc) {
        EmbeddedPatch patch = patches.get(patchId);
        EmbeddedArc divider = arcs.get(dividerArc);
        int[] endA = locateNodeOnBoundary(patch, divider.startNodeId);
        int[] endB = locateNodeOnBoundary(patch, divider.endNodeId);
        if ((endA[0] + 2) % EmbeddedPatch.SIDES != endB[0]) {
            throw new IllegalStateException(ARC + dividerArc + " does not divide " + PATCH
                    + patchId + ": its ends lie on sides " + endA[0] + AND + endB[0]
                    + ", which are not opposite");
        }
        int sideA = endA[0];
        int sideB = endB[0];
        List<Integer> beforeA = new ArrayList<>(patch.sideArcIds.get(sideA).subList(0, endA[1]));
        List<Integer> afterA = new ArrayList<>(
                patch.sideArcIds.get(sideA).subList(endA[1], patch.sideArcIds.get(sideA).size()));
        List<Integer> beforeB = new ArrayList<>(patch.sideArcIds.get(sideB).subList(0, endB[1]));
        List<Integer> afterB = new ArrayList<>(
                patch.sideArcIds.get(sideB).subList(endB[1], patch.sideArcIds.get(sideB).size()));
        List<Integer> divide = List.of(dividerArc);

        List<List<Integer>> firstSides = List.of(afterA,
                new ArrayList<>(patch.sideArcIds.get((sideA + 1) % EmbeddedPatch.SIDES)),
                beforeB, divide);
        List<List<Integer>> secondSides = List.of(afterB,
                new ArrayList<>(patch.sideArcIds.get((sideA + 3) % EmbeddedPatch.SIDES)),
                beforeA, divide);

        patch.alive = false;
        int firstPatch = addPatch(patch.sourcePatchId, firstSides, divider.startNodeId);
        int secondPatch = addPatch(patch.sourcePatchId, secondSides, divider.endNodeId);
        return new int[] { firstPatch, secondPatch };
    }

    /**
     * Where a node sits on a patch's boundary: which side it is on and its index within that
     * side's node chain. A node on a corner is reported on the side it starts.
     *
     * @param patch  patch to look in
     * @param nodeId node to locate
     * @return the side index and the node's position within that side's node list
     * @throws IllegalStateException when the node is not on the patch's boundary
     */
    private int[] locateNodeOnBoundary(EmbeddedPatch patch, int nodeId) {
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            List<Integer> sideNodes = patch.sideNodeIds.get(side);
            for (int index = 0; index < sideNodes.size() - 1; index++) {
                if (sideNodes.get(index) == nodeId) {
                    return new int[] { side, index };
                }
            }
        }
        throw new IllegalStateException("node " + nodeId + NOT_ON_BOUNDARY + PATCH + patch.patchId);
    }

    /**
     * Swaps one arc for another on a patch's boundary. The two must run between the same
     * nodes, because the patch's node chain is not touched.
     *
     * <p>This is the whole of LCBK19 operator (3): "a simple zero-patch is easily collapsed
     * by replacing the embedding of one non-zero arc with the embedding of the other one".
     * The patch on the far side of the dying arc is told to use the surviving one instead,
     * and the surviving arc inherits the neighbour the dying one had.
     *
     * @param patchId  patch whose boundary is being changed
     * @param oldArcId arc leaving the boundary
     * @param newArcId arc taking its place, running between the same two nodes
     * @throws IllegalStateException when the two arcs do not share their endpoints
     */
    public void replaceArcInPatch(int patchId, int oldArcId, int newArcId) {
        EmbeddedArc oldArc = arcs.get(oldArcId);
        EmbeddedArc newArc = arcs.get(newArcId);
        boolean sameEnds = oldArc.startNodeId == newArc.startNodeId
                && oldArc.endNodeId == newArc.endNodeId;
        boolean reversedEnds = oldArc.startNodeId == newArc.endNodeId
                && oldArc.endNodeId == newArc.startNodeId;
        if (!sameEnds && !reversedEnds) {
            throw new IllegalStateException(ARC + newArcId + " cannot replace arc " + oldArcId
                    + " in " + PATCH + patchId + ": they run between different nodes");
        }
        int[] position = sidePosition(patchId, oldArcId);
        patches.get(patchId).sideArcIds.get(position[0]).set(position[1], newArcId);
        if (newArc.leftPatchId == oldArc.leftPatchId || newArc.leftPatchId == oldArc.rightPatchId) {
            newArc.leftPatchId = patchId;
        } else {
            newArc.rightPatchId = patchId;
        }
    }

    /**
     * Retires a patch that has been collapsed away.
     *
     * @param patchId patch to retire
     */
    public void removePatch(int patchId) {
        patches.get(patchId).alive = false;
    }

    /**
     * Where an arc sits on a patch's boundary.
     *
     * @param patchId patch to look in
     * @param arcId   arc to find
     * @return the side index and the position within that side
     * @throws IllegalStateException when the arc is not on that patch's boundary
     */
    private int[] sidePosition(int patchId, int arcId) {
        EmbeddedPatch patch = patches.get(patchId);
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            int index = patch.sideArcIds.get(side).indexOf(arcId);
            if (index >= 0) {
                return new int[] { side, index };
            }
        }
        throw new IllegalStateException(ARC + arcId + NOT_ON_BOUNDARY + PATCH + patchId);
    }

    /**
     * Re-routes the end of an arc that a moving node is dragging with it, from the node's
     * old vertex onto its new one — LCBK19 operator (1)'s <em>"pulling its incident arcs
     * with it, i.e. their embedding path is adjusted such that they connect to n0 at its
     * new position"</em>.
     *
     * <p>The paper's mechanism, exactly: keep the longest prefix of the arc's old path that
     * still reaches, and re-route the tail from there to the new vertex with a
     * claims-respecting Dijkstra (the {@link ArcRerouter}), backing off to an earlier
     * prefix when the search cannot pass, and refining the mesh with a few edge splits when
     * a lane is walled in. The corridor is seeded with the arc's own old path and the
     * collapsing arc's freed channel, and the search is biased onto that lane, so the
     * re-routed arc stays close to where it was.
     *
     * <p>This lives here, not in the operator, because the arc's claims and its {@code path}
     * field must move together — the multi-step back-off leaves them briefly out of step,
     * and letting an operator poke the claim arrays during that window is how the one-to-one
     * mapping silently breaks.
     *
     * @param arcId        arc whose end is being dragged
     * @param movedVertex  the moving node's old copy vertex, an endpoint of the arc's path
     * @param targetVertex the moving node's new copy vertex
     * @param rerouter     the claims-respecting router
     * @param channel      the collapsing arc's released path vertices, seeding the corridor
     * @throws IllegalStateException when the arc's path does not end at the moved vertex
     * @throws ArcRerouteFailure    when no back-off point can be re-routed to the target, carrying
     *                              the two disconnected regions for inspection
     */
    public void dragArcEndOntoVertex(int arcId, int movedVertex, int targetVertex,
            ArcRerouter rerouter, List<Integer> channel) {
        EmbeddedArc arc = arcs.get(arcId);
        List<Integer> vertices = new ArrayList<>(arc.path.copyVertexPath);
        if (vertices.size() == 1) {
            if (vertices.get(0) == targetVertex) {
                return;
            }
            throw new IllegalStateException(ARC + arcId
                    + " is embedded as a point away from the target while its node moves");
        }
        boolean reversed = vertices.get(0) == movedVertex;
        if (reversed) {
            Collections.reverse(vertices);
        }
        if (vertices.get(vertices.size() - 1) != movedVertex) {
            throw new IllegalStateException(ARC + arcId + " path does not end at the moved node's"
                    + " vertex " + movedVertex);
        }
        releaseClaims(arc.path);
        List<Vector3f> pull = positionsOf(vertices);
        pull.addAll(positionsOf(channel));
        for (int passThrough : new int[] {EmbeddedMeshTopology.UNCLAIMED, movedVertex}) {
            if (passThrough == movedVertex && channel.size() > 1) {
                openPivotSpoke(movedVertex, unclaimedComponent(channel.get(1)));
            }
            for (int keep = vertices.size() - 2; keep >= 0; keep--) {
                List<Integer> prefix = new ArrayList<>(vertices.subList(0, keep + 1));
                List<Integer> prefixEdges = new ArrayList<>(keep);
                if (!rerouter.tryLegEdges(prefix, prefixEdges)) {
                    continue;
                }
                ArcEdgePath prefixPath = new ArcEdgePath(arcId, prefix, prefixEdges);
                topology.claimPath(arcId, prefixPath);
                List<Integer> attempt = new ArrayList<>(prefix);
                Set<Integer> corridor = new HashSet<>(vertices);
                corridor.addAll(channel);
                corridor.add(targetVertex);
                corridor.add(movedVertex);
                if (passThrough != EmbeddedMeshTopology.UNCLAIMED && channel.size() > 1) {
                    corridor.addAll(unclaimedComponent(channel.get(1)));
                }
                if (rerouter.tryRoute(arcId, attempt, vertices.get(keep), targetVertex, corridor,
                        pull, passThrough, ArcRerouter.REFINE_ROUND_CAP)) {
                    List<Integer> edges = new ArrayList<>(prefixEdges);
                    rerouter.rebuildLegEdges(attempt, edges);
                    if (reversed) {
                        Collections.reverse(attempt);
                        Collections.reverse(edges);
                    }
                    arc.path = new ArcEdgePath(arcId, attempt, edges);
                    topology.claimPath(arcId, arc.path);
                    return;
                }
                releaseClaims(prefixPath);
            }
        }
        Set<Integer> bodyComponent = unclaimedComponent(vertices.get(1));
        Set<Integer> channelComponent = unclaimedComponent(channel.get(1));
        String message = ARC + arcId + " could not be re-routed onto vertex "
                + targetVertex + " from any back-off point of its old path"
                + " (pathLen=" + vertices.size() + " channelLen=" + channel.size()
                + " freeSpokesAtTarget=" + freeSpokeCount(targetVertex)
                + " freeSpokesAtMoved=" + freeSpokeCount(movedVertex)
                + " bodyComp=" + bodyComponent.size()
                + " bodyReachesTarget=" + touchesVertex(bodyComponent, targetVertex)
                + " channelComp=" + channelComponent.size()
                + " channelReachesTarget=" + touchesVertex(channelComponent, targetVertex)
                + " bodyReachesChannel=" + bodyComponent.contains(channel.get(1))
                + " pivotTouchesBody=" + touchesVertex(bodyComponent, movedVertex)
                + " pivotTouchesChannel=" + touchesVertex(channelComponent, movedVertex)
                + " refineSplits=" + rerouter.refinedEdgeSplitCount + ")";
        throw new ArcRerouteFailure(message, arcId, movedVertex, targetVertex,
                new ArrayList<>(vertices), new ArrayList<>(channel), bodyComponent,
                channelComponent, claimedBoundaryOf(bodyComponent), unclaimedEdgesFrom(movedVertex));
    }

    /**
     * The claimed vertices ringing a set of unclaimed vertices — the neighbours a re-route hits
     * and cannot step through, because a node or an arc's own path owns them. This, not any set of
     * edges, is the wall that encloses an unclaimed region.
     *
     * @param vertices unclaimed region vertices
     * @return the claimed vertices adjacent to the region
     */
    private Set<Integer> claimedBoundaryOf(Set<Integer> vertices) {
        Set<Integer> boundary = new HashSet<>();
        for (int vertex : vertices) {
            for (int index = 0; index < topology.copy.vertexEdgeCount(vertex); index++) {
                int neighbor = topology.otherEndpoint(topology.copy.vertexEdgeAt(vertex, index),
                        vertex);
                if (topology.ownerArcByCopyVertex[neighbor] != EmbeddedMeshTopology.UNCLAIMED
                        || topology.ownerNodeByCopyVertex[neighbor] != EmbeddedMeshTopology.UNCLAIMED) {
                    boundary.add(neighbor);
                }
            }
        }
        return boundary;
    }

    /**
     * A vertex's unclaimed incident edges, as flat consecutive vertex pairs — the spokes a
     * re-route may legally step out along.
     *
     * @param vertex copy vertex to spoke out from
     * @return one {@code (vertex, neighbour)} pair per unclaimed incident edge
     */
    private List<Integer> unclaimedEdgesFrom(int vertex) {
        List<Integer> segments = new ArrayList<>();
        for (int index = 0; index < topology.copy.vertexEdgeCount(vertex); index++) {
            int edgeId = topology.copy.vertexEdgeAt(vertex, index);
            if (topology.ownerArcByCopyEdge[edgeId] == EmbeddedMeshTopology.UNCLAIMED) {
                segments.add(vertex);
                segments.add(topology.otherEndpoint(edgeId, vertex));
            }
        }
        return segments;
    }

    /**
     * The connected set of copy vertices reachable from a start through unclaimed edges without
     * passing through a claimed vertex — the arc-walled region a re-route may use.
     *
     * @param startVertex vertex to flood from
     * @return the reachable unclaimed component, including the start
     */
    private Set<Integer> unclaimedComponent(int startVertex) {
        Set<Integer> seen = new HashSet<>();
        List<Integer> frontier = new ArrayList<>();
        seen.add(startVertex);
        frontier.add(startVertex);
        while (!frontier.isEmpty()) {
            int vertex = frontier.remove(frontier.size() - 1);
            for (int index = 0; index < topology.copy.vertexEdgeCount(vertex); index++) {
                int edgeId = topology.copy.vertexEdgeAt(vertex, index);
                if (topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                    continue;
                }
                int neighbor = topology.otherEndpoint(edgeId, vertex);
                if (topology.ownerArcByCopyVertex[neighbor] == EmbeddedMeshTopology.UNCLAIMED
                        && topology.ownerNodeByCopyVertex[neighbor] == EmbeddedMeshTopology.UNCLAIMED
                        && seen.add(neighbor)) {
                    frontier.add(neighbor);
                }
            }
        }
        return seen;
    }

    /**
     * Whether an unclaimed component touches a vertex, directly or through one of its edges — a
     * target node vertex is claimed, so it is reached at the last hop rather than contained.
     *
     * @param component the flooded component
     * @param vertex    the vertex to test reachability of
     * @return whether the component contains the vertex or an unclaimed-edge neighbour of it
     */
    private boolean touchesVertex(Set<Integer> component, int vertex) {
        if (component.contains(vertex)) {
            return true;
        }
        for (int index = 0; index < topology.copy.vertexEdgeCount(vertex); index++) {
            int edgeId = topology.copy.vertexEdgeAt(vertex, index);
            if (topology.ownerArcByCopyEdge[edgeId] == EmbeddedMeshTopology.UNCLAIMED
                    && component.contains(topology.otherEndpoint(edgeId, vertex))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Opens a free spoke from a collapsing node into a region it must reach, by splitting the edge
     * <em>opposite</em> the node in one of its faces whose far side lies in that region. This is
     * LCBK19's <em>"resolved by refinement with a few edge splits"</em> for the pivot: once sibling
     * arcs have taken every existing spoke from the node into the freed channel, the node needs a
     * fresh one, and splitting the opposite edge joins the minted vertex to the node (a new spoke)
     * while placing it on an edge of the region (so the spoke reaches in).
     *
     * @param pivotVertex   the collapsing node's copy vertex
     * @param channelRegion the unclaimed region the node must gain a spoke into
     */
    private void openPivotSpoke(int pivotVertex, Set<Integer> channelRegion) {
        for (int index = 0; index < topology.copy.vertexFaceCount(pivotVertex); index++) {
            int faceId = topology.copy.vertexFaceAt(pivotVertex, index);
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                int edgeId = topology.copy.faceEdgeAt(faceId, corner);
                int halfEdge = topology.copy.edgeHalfEdge(edgeId);
                int endpointA = topology.copy.halfEdgeVertex(halfEdge);
                int endpointB = topology.copy.halfEdgeEndVertex(halfEdge);
                if (endpointA != pivotVertex && endpointB != pivotVertex
                        && channelRegion.contains(endpointA) && channelRegion.contains(endpointB)
                        && topology.ownerArcByCopyEdge[edgeId] == EmbeddedMeshTopology.UNCLAIMED) {
                    topology.splitEdgeAtParameter(edgeId, EDGE_MIDPOINT);
                    return;
                }
            }
        }
    }

    /**
     * The number of a copy vertex's incident edges that no arc claims — the free spokes a
     * re-route can leave or enter through.
     *
     * @param copyVertex copy vertex to count around
     * @return count of unclaimed incident edges
     */
    private int freeSpokeCount(int copyVertex) {
        int free = 0;
        for (int index = 0; index < topology.copy.vertexEdgeCount(copyVertex); index++) {
            int edgeId = topology.copy.vertexEdgeAt(copyVertex, index);
            if (topology.ownerArcByCopyEdge[edgeId] == EmbeddedMeshTopology.UNCLAIMED) {
                free++;
            }
        }
        return free;
    }

    /**
     * The copy-mesh positions of a list of vertices, in order.
     *
     * @param vertices copy vertices
     * @return their positions
     */
    private List<Vector3f> positionsOf(List<Integer> vertices) {
        List<Vector3f> positions = new ArrayList<>(vertices.size());
        for (int vertexId : vertices) {
            positions.add(topology.copy.vertexPosition(vertexId, new Vector3f()));
        }
        return positions;
    }

    /**
     * Hands back every mesh element an arc holds, so the elements are free for another arc
     * to take. The arc's own nodes keep their vertices: those belong to the nodes, not to
     * the arc.
     *
     * @param arc arc whose claims are released
     */
    private void releaseClaims(EmbeddedArc arc) {
        releaseClaims(arc.path);
    }

    /**
     * Releases the claims of a specific path, used both to free an arc's current embedding
     * and to undo a prefix claimed during a failed re-route back-off.
     *
     * @param path path whose edges and interior vertices are released
     */
    private void releaseClaims(ArcEdgePath path) {
        for (int edgeId : path.copyEdgePath) {
            topology.ownerArcByCopyEdge[edgeId] = EmbeddedMeshTopology.UNCLAIMED;
        }
        List<Integer> vertices = path.copyVertexPath;
        for (int index = 1; index < vertices.size() - 1; index++) {
            topology.ownerArcByCopyVertex[vertices.get(index)] = EmbeddedMeshTopology.UNCLAIMED;
        }
    }

    /**
     * Checks that an end of an arc's path has been re-routed onto the vertex its node is
     * about to move to.
     *
     * @param arc            arc being re-pointed
     * @param pathEndVertex  vertex the arc's path currently ends on
     * @param nodeCopyVertex vertex the node it is being re-pointed at sits on
     * @throws IllegalStateException when the path does not reach the node
     */
    private void requireEndOfPathIsAt(EmbeddedArc arc, int pathEndVertex, int nodeCopyVertex) {
        if (pathEndVertex != nodeCopyVertex) {
            throw new IllegalStateException(ARC + arc.arcId + " was re-pointed at a node on "
                    + "copy vertex " + nodeCopyVertex + " but its path still ends on "
                    + pathEndVertex + "; re-route the arc before merging its node");
        }
    }

    /**
     * Checks the T-mesh is still a cell decomposition of the surface, and throws if it is
     * not.
     *
     * <p>The Euler characteristic is the load-bearing check. Counting live nodes, arcs and
     * patches, {@code V - E + F} must equal the surface's characteristic, and no plausible
     * corruption of the complex survives it: an arc lost, a patch left un-split, two nodes
     * merged that should not have been, all move it. It costs nothing and it is run after
     * every stage.
     *
     * @param expectedEulerCharacteristic the surface's characteristic, {@code 2 - 2g}
     * @throws IllegalStateException when the T-mesh is no longer a cell decomposition
     */
    public void validate(int expectedEulerCharacteristic) {
        int liveNodes = 0;
        for (EmbeddedNode node : nodes) {
            if (node.alive) {
                liveNodes++;
            }
        }
        int liveArcs = 0;
        for (EmbeddedArc arc : arcs) {
            if (!arc.alive) {
                continue;
            }
            liveArcs++;
            if (arc.quantizedLength < 0) {
                throw new IllegalStateException(ARC + arc.arcId
                        + HAS + "negative quantized length " + arc.quantizedLength);
            }
            if (!nodes.get(arc.startNodeId).alive || !nodes.get(arc.endNodeId).alive) {
                throw new IllegalStateException(ARC + arc.arcId + " ends on a retired node");
            }
            requirePathRunsBetweenItsNodes(arc);
        }
        int livePatches = 0;
        for (EmbeddedPatch patch : patches) {
            if (patch.alive) {
                livePatches++;
                requireSidesClose(patch);
                requireOppositeSidesAgree(patch);
            }
        }
        int characteristic = liveNodes - liveArcs + livePatches;
        if (characteristic != expectedEulerCharacteristic) {
            throw new IllegalStateException("T-mesh is not a cell decomposition of the surface:"
                    + " V - E + F = " + liveNodes + " - " + liveArcs + " + " + livePatches
                    + " = " + characteristic + ", expected " + expectedEulerCharacteristic);
        }
    }

    /**
     * Checks an arc's edge path really does run between its two nodes' vertices, and that
     * every step of it is a real edge of the working copy.
     *
     * @param arc arc to check
     */
    private void requirePathRunsBetweenItsNodes(EmbeddedArc arc) {
        List<Integer> vertices = arc.path.copyVertexPath;
        int expectedStart = nodes.get(arc.startNodeId).copyVertex;
        int expectedEnd = nodes.get(arc.endNodeId).copyVertex;
        if (vertices.get(0) != expectedStart
                || vertices.get(vertices.size() - 1) != expectedEnd) {
            throw new IllegalStateException(ARC + arc.arcId + " path runs from "
                    + vertices.get(0) + TO + vertices.get(vertices.size() - 1)
                    + " but its nodes sit on " + expectedStart + AND + expectedEnd);
        }
        for (int index = 1; index < vertices.size(); index++) {
            requireEdge(arc.arcId, vertices.get(index - 1), vertices.get(index));
        }
    }

    /**
     * The edge of the working copy joining two consecutive vertices of an arc's path.
     *
     * <p>An arc that does not walk the mesh is not an embedding of anything, so a missing
     * edge is refused here rather than being discovered later by whatever tries to use the
     * path.
     *
     * @param arcId      arc whose path is being checked, for the message
     * @param fromVertex vertex the step leaves
     * @param toVertex   vertex the step arrives at
     * @return the edge between them
     * @throws IllegalStateException when the two vertices are not joined by an edge
     */
    private int requireEdge(int arcId, int fromVertex, int toVertex) {
        int edgeId = topology.edgeBetween(fromVertex, toVertex);
        if (edgeId == EmbeddedMeshTopology.UNCLAIMED) {
            throw new IllegalStateException(ARC + arcId + " path steps from " + fromVertex
                    + TO + toVertex + " with no edge between them");
        }
        return edgeId;
    }

    /**
     * Checks a patch's four sides chain end-to-end and close back on the first corner.
     *
     * @param patch patch to check
     */
    private void requireSidesClose(EmbeddedPatch patch) {
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            List<Integer> sideNodes = patch.sideNodeIds.get(side);
            List<Integer> sideArcs = patch.sideArcIds.get(side);
            if (sideNodes.size() != sideArcs.size() + 1) {
                throw new IllegalStateException(PATCH + patch.patchId + SIDE + side
                        + HAS + sideArcs.size() + " arcs but " + sideNodes.size()
                        + " nodes; a side has one more node than it has arcs");
            }
            int nextSide = (side + 1) % EmbeddedPatch.SIDES;
            int endOfThisSide = sideNodes.get(sideNodes.size() - 1);
            int startOfNextSide = patch.sideNodeIds.get(nextSide).get(0);
            if (endOfThisSide != startOfNextSide) {
                throw new IllegalStateException(PATCH + patch.patchId + SIDE + side
                        + " ends on node " + endOfThisSide + " but side " + nextSide
                        + " starts on node " + startOfNextSide);
            }
        }
    }

    /**
     * Checks a patch's opposite sides carry equal quantized length.
     *
     * <p>This is CBK15's consistency condition — <em>"the parametric lengths of the edges
     * on opposite sides sum up to the same value"</em> — and it is what makes the patch a
     * rectangle in parameter space at all. The quantization is solved subject to it, so a
     * violation here is never a rounding artefact; it means an operator has changed one
     * side and not its opposite, which would leave the T-mesh describing a shape that is
     * not a rectangle and cannot be mapped to one.
     *
     * @param patch patch to check
     */
    private void requireOppositeSidesAgree(EmbeddedPatch patch) {
        for (int side = 0; side < 2; side++) {
            int here = sideQuantizedLength(patch.patchId, side);
            int opposite = sideQuantizedLength(patch.patchId, side + 2);
            if (here != opposite) {
                throw new IllegalStateException(PATCH + patch.patchId
                        + " is not a rectangle: side " + side + " has quantized length " + here
                        + " but the opposite side " + (side + 2) + HAS + opposite);
            }
        }
    }
}
