package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    /** Diagnostic tag prefixing an arc id in a compact ownership report. */
    private static final String ARC_TAG = "a";

    /** System property enabling the per-drag before/after path trace. */
    private static final String TRACE_DRAG = "embeddedTMesh.traceDrag";

    /** Separator between the two ownership tags of a diagnostic gate report. */
    private static final String TAG_SEPARATOR = "/";

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
     * The corridor of the re-route attempt that just failed, held only so the failure diagnostic can
     * report whether a blocked gate's endpoints were ever inside it — refinement can split an edge
     * only when both endpoints are corridor members, so a gate outside the corridor is one the
     * refinement never even considered.
     */
    public Set<Integer> diagnosticCorridor;

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
     * <p>When the arc is a loop its two ends are already the same node, so {@link #mergeNodeInto}
     * retired nothing and the complex would lose an arc without losing a cell. A loop only becomes
     * collapsible once the region it encloses has itself shrunk to nothing — LCBK19 §6.1 counts
     * <em>"a zero-patch without any non-zero arc… one that is supposed to be embedded onto a single
     * point rather than a curve"</em> as <em>"already handled by the zero-arc collapse"</em> — so any
     * bordering patch left with an empty boundary is retired here, which is what keeps the Euler
     * characteristic fixed across the contraction.
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
        for (int patchId : new int[] { arc.leftPatchId, arc.rightPatchId }) {
            if (patchId != NONE && patches.get(patchId).alive
                    && patches.get(patchId).sideArcIds.stream().allMatch(List::isEmpty)) {
                patches.get(patchId).alive = false;
            }
        }
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
     * <p>The mechanism is a drag, not a redraw: keep the longest prefix of the arc's old path that
     * still reaches, and re-route only the tail from there to the new vertex with a claims-respecting
     * Dijkstra (the {@link ArcRerouter}), backing off to an earlier prefix when the search cannot
     * pass, and refining the mesh with a few edge splits when a lane is walled in.
     *
     * <p>Preferring the longest surviving prefix is what keeps the arc <em>where it was</em>. LCBK19
     * spells this out for the border case — the dragged arc is <em>"re-embedded onto the joint edge
     * paths of b and a"</em>, i.e. its own path extended along the collapsed one. Re-routing the whole
     * arc between its two endpoints instead looks like the paper's <em>"Dijkstra's shortest path
     * algorithm between the respective two vertices"</em>, but it is not safe here: at the moment of a
     * drag the short continuation is blocked by the claims of the collapse, so the search returns a
     * path around the <em>other</em> side. That path crosses and touches nothing and joins the right
     * nodes, yet the arc no longer separates the two patches the T-mesh records it as separating, and
     * the layout tears while every local check still passes.
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
                        passThrough, ArcRerouter.REFINE_ROUND_CAP)) {
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
        diagnosticCorridor = rerouter.lastCorridorSet;
        Set<Integer> bodyComponent = unclaimedComponent(vertices.get(1));
        Set<Integer> channelComponent = unclaimedComponent(channel.get(1));
        Set<Integer> fence = claimedBoundaryOf(bodyComponent);
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
                + " refineSplits=" + rerouter.refinedEdgeSplitCount
                + " lastReached=" + rerouter.lastReachedCount
                + " lastCorridor=" + rerouter.lastCorridorSize
                + " growths=" + rerouter.corridorGrowthCount
                + " " + wallArcSummary(fence, movedVertex)
                + " " + channelOwnerReport(channel, movedVertex)
                + " faceThreadPivotToTarget=" + faceReachesAcrossFreeEdges(movedVertex, targetVertex)
                + " faceThreadBodyToTarget="
                + faceReachesAcrossFreeEdges(vertices.get(1), targetVertex)
                + " body:" + faceCorridorReport(vertices.get(1), targetVertex)
                + " bodyToPivot:" + faceCorridorReport(vertices.get(1), movedVertex)
                + " pivot:" + faceCorridorReport(movedVertex, targetVertex) + ")";
        throw new ArcRerouteFailure(message, arcId, movedVertex, targetVertex,
                new ArrayList<>(vertices), new ArrayList<>(channel), bodyComponent,
                channelComponent, fence, unclaimedEdgesFrom(movedVertex));
    }

    /**
     * A summary of the arcs owning the wall around a body region: how many there are, how many are
     * incident to the collapsing pivot node, and how many of those have already been re-routed this
     * collapse (their path no longer touches the pivot). This tells whether the wall is a just-moved
     * sibling — an ordering problem — or an unrelated structural arc.
     *
     * @param fence       the claimed vertices ringing the body region
     * @param pivotVertex the collapsing node's copy vertex
     * @return a compact {@code wallArcs=… incident=… incidentMoved=… ids=[…]} summary
     */
    private String wallArcSummary(Set<Integer> fence, int pivotVertex) {
        int pivotNode = topology.ownerNodeByCopyVertex[pivotVertex];
        Set<Integer> wallArcs = new HashSet<>();
        for (int fenceVertex : fence) {
            int owner = topology.ownerArcByCopyVertex[fenceVertex];
            if (owner != EmbeddedMeshTopology.UNCLAIMED) {
                wallArcs.add(owner);
            }
        }
        int incident = 0;
        int incidentMoved = 0;
        for (int wallArc : wallArcs) {
            if (pivotNode != EmbeddedMeshTopology.UNCLAIMED
                    && arcEndsByNode.get(pivotNode).contains(wallArc)) {
                incident++;
                if (!arcs.get(wallArc).path.copyVertexPath.contains(pivotVertex)) {
                    incidentMoved++;
                }
            }
        }
        return "wallArcs=" + wallArcs.size() + " incident=" + incident
                + " incidentMoved=" + incidentMoved + " ids=" + wallArcs;
    }

    /**
     * The vertices of the two patches an arc separates — the only ground it may be re-routed over.
     *
     * <p>LCBK19 restricts the re-route search <em>"to not intersect (cross or touch) other arcs in
     * order to preserve the topology of the embedded T-mesh"</em>. Skipping claimed edges and
     * vertices is not enough to deliver that, because a collapse releases <em>two</em> arcs — the one
     * being dragged and the one being collapsed — and the patches they separated merge into a region
     * wide enough to hold several non-crossing routes between the same two nodes. Those routes bound
     * different patches, so the search can return a perfectly legal curve that no longer separates
     * the patches the T-mesh records it as separating, and the layout tears while every local check
     * still passes.
     *
     * <p>So the walls are built from the arc's <em>own</em> two patches, flooded here while the arc
     * is still claimed and with the collapsing arc's channel also treated as a wall. Within that
     * region the arc's two nodes lie on the boundary, so any simple route between them splits it the
     * same way the old one did and the patches on either side are preserved by construction.
     *
     * @param arcVertices  the arc's current path, still claimed, whose two sides are wanted
     * @param channel      the collapsing arc's released path, treated as a wall so its patches do
     *                     not merge in
     * @return every copy vertex on a face of the arc's own two patches
     */
    private Set<Integer> arcSideRegionVertices(List<Integer> arcVertices, List<Integer> channel) {
        Set<Integer> blockedEdges = new HashSet<>();
        for (int step = 1; step < channel.size(); step++) {
            int edgeId = topology.edgeBetween(channel.get(step - 1), channel.get(step));
            if (edgeId != EmbeddedMeshTopology.UNCLAIMED) {
                blockedEdges.add(edgeId);
            }
        }
        Set<Integer> visitedFaces = new HashSet<>();
        Set<Integer> regionVertices = new HashSet<>(arcVertices);
        Deque<Integer> frontier = new ArrayDeque<>();
        for (int arcVertex : arcVertices) {
            for (int index = 0; index < topology.copy.vertexFaceCount(arcVertex); index++) {
                int face = topology.copy.vertexFaceAt(arcVertex, index);
                if (visitedFaces.add(face)) {
                    frontier.add(face);
                }
            }
        }
        while (!frontier.isEmpty()) {
            int face = frontier.poll();
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                regionVertices.add(topology.copy.faceVertexAt(face, corner));
                int edgeId = topology.copy.faceEdgeAt(face, corner);
                if (topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED
                        || blockedEdges.contains(edgeId)) {
                    continue;
                }
                int halfEdge = topology.copy.edgeHalfEdge(edgeId);
                int neighborFace = topology.copy.halfEdgeFace(halfEdge) == face
                        ? topology.copy.halfEdgeFace(topology.copy.halfEdgeTwin(halfEdge))
                        : topology.copy.halfEdgeFace(halfEdge);
                if (neighborFace != EmbeddedMeshTopology.UNCLAIMED && visitedFaces.add(neighborFace)) {
                    frontier.add(neighborFace);
                }
            }
        }
        return regionVertices;
    }

    /**
     * The vertices of the region a collapse has freed around the moving node: every corner of every
     * face reachable from it without crossing an arc.
     *
     * <p>This is the ground a dragged arc is allowed to move over, and confining it matters as much
     * as finding a short path. By the time a drag runs, the collapsing arc's claims and the dragged
     * arc's own claims have both been released, so the patches those two arcs separated have merged
     * into exactly one region — and the survivor lies on its boundary, being an endpoint of the
     * collapsing arc. Any path inside it keeps the arc between the same patches it always separated.
     *
     * <p>A shortest path over a wider corridor does not: it can slip through a neighbouring patch and
     * come out bounding different patches than before, which leaves the layout torn — regions no
     * longer matching the patches that are supposed to enclose them. LCBK19's operators are
     * <em>"local operators concerning individual zero-patches"</em>, and this is what that locality
     * means concretely.
     *
     * @param seedVertex the moving node's copy vertex, inside the freed region
     * @return every copy vertex on a face of that region
     */
    private Set<Integer> freedRegionVertices(int seedVertex) {
        Set<Integer> visitedFaces = new HashSet<>();
        Set<Integer> regionVertices = new HashSet<>();
        Deque<Integer> frontier = new ArrayDeque<>();
        for (int index = 0; index < topology.copy.vertexFaceCount(seedVertex); index++) {
            int face = topology.copy.vertexFaceAt(seedVertex, index);
            if (visitedFaces.add(face)) {
                frontier.add(face);
            }
        }
        while (!frontier.isEmpty()) {
            int face = frontier.poll();
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                regionVertices.add(topology.copy.faceVertexAt(face, corner));
                int edgeId = topology.copy.faceEdgeAt(face, corner);
                if (topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                    continue;
                }
                int halfEdge = topology.copy.edgeHalfEdge(edgeId);
                int neighborFace = topology.copy.halfEdgeFace(halfEdge) == face
                        ? topology.copy.halfEdgeFace(topology.copy.halfEdgeTwin(halfEdge))
                        : topology.copy.halfEdgeFace(halfEdge);
                if (neighborFace != EmbeddedMeshTopology.UNCLAIMED && visitedFaces.add(neighborFace)) {
                    frontier.add(neighborFace);
                }
            }
        }
        return regionVertices;
    }

    /**
     * The ownership of each vertex along the vacated channel path, ordered from the pivot end to the
     * survivor end. Each entry is {@code .} when the vertex is free, {@code n<id>} when a node owns
     * it, or {@code a<id>} when an arc owns it. This shows directly whether a dragged sibling arc has
     * re-claimed a stretch of the channel and where — the {@code closes off in the middle} test.
     *
     * @param channel     the collapsing arc's vacated copy-vertex path
     * @param pivotVertex the collapsing node's copy vertex, oriented to the pivot end
     * @return a compact {@code channel=[…]} ownership strip from pivot to survivor
     */
    private String channelOwnerReport(List<Integer> channel, int pivotVertex) {
        List<Integer> ordered = new ArrayList<>(channel);
        if (!ordered.isEmpty() && ordered.get(0) != pivotVertex) {
            Collections.reverse(ordered);
        }
        StringBuilder strip = new StringBuilder("channel=[");
        for (int index = 0; index < ordered.size(); index++) {
            int vertex = ordered.get(index);
            int arcOwner = topology.ownerArcByCopyVertex[vertex];
            int nodeOwner = topology.ownerNodeByCopyVertex[vertex];
            if (index > 0) {
                strip.append(' ');
            }
            if (nodeOwner != EmbeddedMeshTopology.UNCLAIMED) {
                strip.append('n').append(nodeOwner);
            } else if (arcOwner != EmbeddedMeshTopology.UNCLAIMED) {
                strip.append('a').append(arcOwner);
            } else {
                strip.append('.');
            }
        }
        return strip.append(']').toString();
    }

    /**
     * The face corridor a re-route could follow, and how blocked each of its steps is. Walks the
     * shortest claimed-edge-free face path from one vertex to another and classifies every crossing
     * edge by how many of its two endpoints are claimed: {@code 0} the search can walk straight
     * across, {@code 1} it can step along the free endpoint, {@code 2} it can stand on neither end
     * and only an edge split can thread it. A long run of {@code 2}s is a stretch the vertex search
     * cannot traverse without refinement, which is what LCBK19's <em>"a few edge splits"</em> must
     * open.
     *
     * @param startVertex  corridor source vertex
     * @param targetVertex corridor target vertex
     * @return a {@code faceCorridor=… gates=[…] bothClaimed=…} report, or {@code none}
     */
    private String faceCorridorReport(int startVertex, int targetVertex) {
        Set<Integer> targetFaces = new HashSet<>();
        for (int index = 0; index < topology.copy.vertexFaceCount(targetVertex); index++) {
            targetFaces.add(topology.copy.vertexFaceAt(targetVertex, index));
        }
        Map<Integer, Integer> parentFace = new HashMap<>();
        Map<Integer, Integer> parentEdge = new HashMap<>();
        Deque<Integer> frontier = new ArrayDeque<>();
        for (int index = 0; index < topology.copy.vertexFaceCount(startVertex); index++) {
            int face = topology.copy.vertexFaceAt(startVertex, index);
            if (parentFace.putIfAbsent(face, EmbeddedMeshTopology.UNCLAIMED) == null) {
                frontier.add(face);
            }
        }
        int reachedFace = EmbeddedMeshTopology.UNCLAIMED;
        while (!frontier.isEmpty() && reachedFace == EmbeddedMeshTopology.UNCLAIMED) {
            int face = frontier.poll();
            if (targetFaces.contains(face)) {
                reachedFace = face;
                break;
            }
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                int edgeId = topology.copy.faceEdgeAt(face, corner);
                if (topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                    continue;
                }
                int halfEdge = topology.copy.edgeHalfEdge(edgeId);
                int neighborFace = topology.copy.halfEdgeFace(halfEdge) == face
                        ? topology.copy.halfEdgeFace(topology.copy.halfEdgeTwin(halfEdge))
                        : topology.copy.halfEdgeFace(halfEdge);
                if (neighborFace != EmbeddedMeshTopology.UNCLAIMED
                        && parentFace.putIfAbsent(neighborFace, face) == null) {
                    parentEdge.put(neighborFace, edgeId);
                    frontier.add(neighborFace);
                }
            }
        }
        if (reachedFace == EmbeddedMeshTopology.UNCLAIMED) {
            return "faceCorridor=none";
        }
        List<Integer> crossings = new ArrayList<>();
        for (int walk = reachedFace; parentFace.get(walk) != EmbeddedMeshTopology.UNCLAIMED;
                walk = parentFace.get(walk)) {
            crossings.add(parentEdge.get(walk));
        }
        Collections.reverse(crossings);
        StringBuilder gates = new StringBuilder();
        StringBuilder detail = new StringBuilder();
        int bothClaimed = 0;
        int freeOutsideCorridor = 0;
        for (int edgeId : crossings) {
            int halfEdge = topology.copy.edgeHalfEdge(edgeId);
            for (int endpoint : new int[] {topology.copy.halfEdgeVertex(halfEdge),
                    topology.copy.halfEdgeEndVertex(halfEdge)}) {
                if (!isClaimedVertex(endpoint) && diagnosticCorridor != null
                        && !diagnosticCorridor.contains(endpoint)) {
                    freeOutsideCorridor++;
                }
            }
        }
        for (int edgeId : crossings) {
            int halfEdge = topology.copy.edgeHalfEdge(edgeId);
            int endpointA = topology.copy.halfEdgeVertex(halfEdge);
            int endpointB = topology.copy.halfEdgeEndVertex(halfEdge);
            int blocked = (isClaimedVertex(endpointA) ? 1 : 0) + (isClaimedVertex(endpointB) ? 1 : 0);
            if (blocked == 2) {
                bothClaimed++;
                detail.append(' ').append(gateDetail(edgeId, halfEdge, endpointA, endpointB));
            }
            gates.append(blocked);
        }
        return "faceCorridor=" + crossings.size() + " gates=[" + gates + "] bothClaimed=" + bothClaimed
                + " freeOutsideCorridor=" + freeOutsideCorridor
                + " detail=[" + detail.toString().trim() + "]";
    }

    /**
     * The local structure at a blocked gate: the owners of the crossing edge's two endpoints and of
     * the two opposite face corners, plus whether the gate edge is itself claimed. If both opposite
     * corners are free the search could step around the gate, so a gate only truly blocks when they
     * are claimed too — this reports which.
     *
     * @param edgeId    the crossing edge
     * @param halfEdge  a half-edge of that edge
     * @param endpointA the crossing edge's first endpoint
     * @param endpointB the crossing edge's second endpoint
     * @return a compact {@code a<owner>/b<owner>|opp<owner>/<owner>} description
     */
    private String gateDetail(int edgeId, int halfEdge, int endpointA, int endpointB) {
        int twin = topology.copy.halfEdgeTwin(halfEdge);
        return "{" + ownerTag(endpointA) + TAG_SEPARATOR + ownerTag(endpointB)
                + "|opp " + ownerTag(oppositeCorner(topology.copy.halfEdgeFace(halfEdge), endpointA,
                        endpointB))
                + TAG_SEPARATOR + ownerTag(oppositeCorner(topology.copy.halfEdgeFace(twin), endpointA,
                        endpointB))
                + "|edge " + (topology.ownerArcByCopyEdge[edgeId] == EmbeddedMeshTopology.UNCLAIMED
                        ? "free" : ARC_TAG + topology.ownerArcByCopyEdge[edgeId])
                + "|inCorridor " + inLastCorridor(endpointA) + TAG_SEPARATOR
                + inLastCorridor(endpointB)
                + "|freeNbrs " + freeNeighbourCount(endpointA) + TAG_SEPARATOR
                + freeNeighbourCount(endpointB) + TAG_SEPARATOR
                + freeNeighbourCount(oppositeCorner(topology.copy.halfEdgeFace(halfEdge), endpointA,
                        endpointB))
                + TAG_SEPARATOR + freeNeighbourCount(oppositeCorner(
                        topology.copy.halfEdgeFace(twin), endpointA, endpointB))
                + "}";
    }

    /**
     * Whether a vertex was inside the corridor of the re-route attempt that just failed. Refinement
     * splits an edge only when both endpoints are corridor members, so a gate endpoint reporting
     * {@code NO} is one the refinement never considered splitting.
     *
     * @param copyVertex vertex to test, or {@link EmbeddedMeshTopology#UNCLAIMED}
     * @return {@code yes}, {@code NO}, or {@code ?} when no corridor was recorded
     */
    private String inLastCorridor(int copyVertex) {
        if (diagnosticCorridor == null || copyVertex == EmbeddedMeshTopology.UNCLAIMED) {
            return "?";
        }
        return diagnosticCorridor.contains(copyVertex) ? "yes" : "NO";
    }

    /**
     * How many of a vertex's neighbours are unclaimed — how much free ground touches it. A pocket
     * whose every corner reports zero is sealed off from the free region, so a midpoint minted
     * inside it is unreachable by the vertex search no matter how many edges are split.
     *
     * @param copyVertex vertex to measure, or {@link EmbeddedMeshTopology#UNCLAIMED}
     * @return the count of unclaimed neighbours, or -1 when the vertex is absent
     */
    private int freeNeighbourCount(int copyVertex) {
        if (copyVertex == EmbeddedMeshTopology.UNCLAIMED) {
            return -1;
        }
        int free = 0;
        for (int index = 0; index < topology.copy.vertexEdgeCount(copyVertex); index++) {
            int neighbor = topology.otherEndpoint(topology.copy.vertexEdgeAt(copyVertex, index),
                    copyVertex);
            if (!isClaimedVertex(neighbor)) {
                free++;
            }
        }
        return free;
    }

    /**
     * The corner of a triangular face that is neither of two given vertices.
     *
     * @param faceId  face to inspect
     * @param exclude first vertex to skip
     * @param exclude2 second vertex to skip
     * @return the remaining corner, or {@link EmbeddedMeshTopology#UNCLAIMED} when the face is absent
     */
    private int oppositeCorner(int faceId, int exclude, int exclude2) {
        if (faceId == EmbeddedMeshTopology.UNCLAIMED) {
            return EmbeddedMeshTopology.UNCLAIMED;
        }
        for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
            int vertex = topology.copy.faceVertexAt(faceId, corner);
            if (vertex != exclude && vertex != exclude2) {
                return vertex;
            }
        }
        return EmbeddedMeshTopology.UNCLAIMED;
    }

    /**
     * A copy vertex's ownership as a short tag: {@code n<id>} for a node, {@code a<id>} for an arc,
     * {@code .} when free.
     *
     * @param copyVertex vertex to describe
     * @return the ownership tag
     */
    private String ownerTag(int copyVertex) {
        if (copyVertex == EmbeddedMeshTopology.UNCLAIMED) {
            return "-";
        }
        if (topology.ownerNodeByCopyVertex[copyVertex] != EmbeddedMeshTopology.UNCLAIMED) {
            return "n" + topology.ownerNodeByCopyVertex[copyVertex];
        }
        if (topology.ownerArcByCopyVertex[copyVertex] != EmbeddedMeshTopology.UNCLAIMED) {
            return ARC_TAG + topology.ownerArcByCopyVertex[copyVertex];
        }
        return ".";
    }

    /**
     * Whether a copy vertex is owned by a T-mesh node or an embedded arc.
     *
     * @param copyVertex copy vertex to test
     * @return true when either ownership claim is set
     */
    private boolean isClaimedVertex(int copyVertex) {
        return topology.ownerNodeByCopyVertex[copyVertex] != EmbeddedMeshTopology.UNCLAIMED
                || topology.ownerArcByCopyVertex[copyVertex] != EmbeddedMeshTopology.UNCLAIMED;
    }

    /**
     * Whether a face-walk from one vertex reaches another without ever crossing a claimed
     * (arc-owned) edge — face-connectivity of the arc arrangement, blocked only by arc <em>edges</em>,
     * free to pass beside claimed <em>vertices</em>. This is the topological test the vertex-flood
     * {@link #unclaimedComponent(int)} cannot make: two regions can be vertex-disconnected (claimed
     * vertices sit between them) yet face-connected (a threadable gap remains that refinement could
     * open). If this is true while the re-route failed, the block is a refinement gap, not a real
     * wall; if false, an arc's edge path genuinely separates the two.
     *
     * @param startVertex  walk source vertex, whose incident faces seed the flood
     * @param targetVertex walk target vertex, reached when any of its incident faces is entered
     * @return true when a claimed-edge-free face path connects the two vertices
     */
    private boolean faceReachesAcrossFreeEdges(int startVertex, int targetVertex) {
        Set<Integer> targetFaces = new HashSet<>();
        for (int index = 0; index < topology.copy.vertexFaceCount(targetVertex); index++) {
            targetFaces.add(topology.copy.vertexFaceAt(targetVertex, index));
        }
        Set<Integer> visited = new HashSet<>();
        Deque<Integer> frontier = new ArrayDeque<>();
        for (int index = 0; index < topology.copy.vertexFaceCount(startVertex); index++) {
            int face = topology.copy.vertexFaceAt(startVertex, index);
            if (visited.add(face)) {
                frontier.add(face);
            }
        }
        while (!frontier.isEmpty()) {
            int face = frontier.poll();
            if (targetFaces.contains(face)) {
                return true;
            }
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                int edgeId = topology.copy.faceEdgeAt(face, corner);
                if (topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                    continue;
                }
                int halfEdge = topology.copy.edgeHalfEdge(edgeId);
                int neighborFace = topology.copy.halfEdgeFace(halfEdge) == face
                        ? topology.copy.halfEdgeFace(topology.copy.halfEdgeTwin(halfEdge))
                        : topology.copy.halfEdgeFace(halfEdge);
                if (neighborFace != EmbeddedMeshTopology.UNCLAIMED && visited.add(neighborFace)) {
                    frontier.add(neighborFace);
                }
            }
        }
        return false;
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
