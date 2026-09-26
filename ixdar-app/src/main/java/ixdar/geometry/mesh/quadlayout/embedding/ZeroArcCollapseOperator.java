package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.IntIdList;
import ixdar.geometry.mesh.quadlayout.embedding.records.ArcEdgePath;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedNode;

/**
 * Operator (1), the zero-arc collapse: one node moves onto the other, each arc
 * it carries is re-embedded by {@link ArcRerouter}'s Dijkstra, and the zero arc
 * becomes that point.
 *
 * <p>
 * See also: LCBK19 Section 6.1, "Operator Implementation", and Def 6.2
 */
public final class ZeroArcCollapseOperator {

    /** Starting capacity of the zero-arc candidate list; grows by doubling. */
    private static final int CANDIDATE_INITIAL_CAPACITY = 256;

    /** Diagnostic group name of the collapsing arc's path. */
    private static final String GROUP_CHANNEL = "collapsing arc";

    /** Diagnostic group name of the moving node's vertex marker. */
    private static final String GROUP_MOVED_VERTEX = "moved vertex";

    /** Diagnostic group name of the surviving node's vertex marker. */
    private static final String GROUP_TARGET_VERTEX = "target vertex";

    /** First allocation of {@link #touchedPatches}. */
    private static final int TOUCHED_PATCH_INITIAL_CAPACITY = 8;

    public final ArcNetwork tmesh;
    public final ArcRerouter rerouter;

    public int collapsedCount;

    /**
     * Drags that found no edge path even after refinement, each of which threw an
     * {@link ArrangementDiagnosticException}.
     */
    public int blockedDragCount;

    /** Faces the last {@link #floodFreeSpace} reached, in flood order. */
    public final IntIdList freeRegionFaces = new IntIdList(0);

    /** Visit stamps of {@link #floodFreeSpace}, indexed by copy face. */
    public int[] freeRegionStampByCopyFace = new int[0];

    /** Current stamp generation of {@link #freeRegionStampByCopyFace}. */
    public int freeRegionStamp;

    /**
     * Patches this collapse moves the boundary of: the flanks of the arc it
     * collapses and of every arc it drags, whose covers {@link #finishCollapse}
     * re-reads.
     */
    public int[] touchedPatches = new int[0];

    /** Live entry count of {@link #touchedPatches}. */
    public int touchedPatchCount;

    /**
     * Arc the in-flight collapse is collapsing, or {@link ArcNetwork#NONE} when no
     * collapse is between {@link #beginCollapse} and {@link #finishCollapse}.
     */
    public int collapsingArcId = ArcNetwork.NONE;

    /** The in-flight collapse's moving node. */
    public int movedNodeId;

    /** The in-flight collapse's surviving node. */
    public int survivingNodeId;

    /** Copy vertex the in-flight collapse's moving node stands on. */
    public int movedVertex;

    /** Copy vertex the in-flight collapse merges the moving node onto. */
    public int targetVertex;

    /** The collapsing arc's path, snapshotted before it is embedded onto the point. */
    public final List<Integer> channel = new ArrayList<>();

    /**
     * Spoke at the surviving vertex the next drag from the fan's front arrives just
     * after: first the arc before the collapsing arc there, then the last arc
     * dragged from the front. {@link ArcNetwork#NONE} when no arc bounds it.
     */
    public int frontArrivalSpoke = ArcNetwork.NONE;

    /**
     * Spoke the next drag from the fan's back arrives just before: first the arc
     * after the collapsing arc there, then the last arc dragged from the back.
     */
    public int backArrivalSpoke = ArcNetwork.NONE;

    /**
     * The moving node's other incident arcs, one entry per path end on it, in
     * cyclic order around it from the collapsing arc.
     */
    public final List<Integer> fan = new ArrayList<>();

    /** Whether each {@link #fan} entry is its arc's path start rather than its end. */
    public boolean[] fanMovesPathStart = new boolean[0];

    /** Fan entries already consumed, indexing the oscillating drag order. */
    public int fanCursor;

    /**
     * Arc the last {@link #dragNextArc} dragged, or {@link ArcNetwork#NONE} before
     * the first drag.
     */
    public int lastDraggedArcId = ArcNetwork.NONE;

    /** The last dragged arc's path before its drag, for the step view. */
    public final List<Integer> lastDraggedPreviousPath = new ArrayList<>();

    /** Live zero arcs still worth testing, compacted as arcs die. */
    public int[] zeroArcCandidates = new int[0];

    /** Live entry count of {@link #zeroArcCandidates}. */
    public int zeroArcCandidateCount;

    /**
     * Arc-list size already swept for new zero arcs; the split operator adds more.
     */
    public int scannedArcBound;

    /**
     * Stores the T-mesh to operate on and builds the re-router over its working
     * copy.
     *
     * @param tmesh embedded T-mesh whose zero arcs are collapsed
     */
    public ZeroArcCollapseOperator(ArcNetwork tmesh) {
        this.tmesh = tmesh;
        this.rerouter = new ArcRerouter(tmesh.topology);
    }

    /**
     * The collapsible zero arc whose node has the most arcs on it, so crowded fans
     * clear while the mesh is still coarse. Ties keep the lowest arc id.
     *
     * <p>
     * Only zero arcs qualify and {@code alive} never returns, so candidates are
     * appended once per new arc and compacted as arcs die.
     *
     * @return the chosen zero arc id, or {@link ArcNetwork#NONE} when none remains
     */
    public int mostContendedArc() {
        for (int arcId = scannedArcBound; arcId < tmesh.arcs.size(); arcId++) {
            if (tmesh.arcs.get(arcId).quantizedLength != 0) {
                continue;
            }
            if (zeroArcCandidateCount == zeroArcCandidates.length) {
                zeroArcCandidates = Arrays.copyOf(zeroArcCandidates,
                        Math.max(CANDIDATE_INITIAL_CAPACITY, zeroArcCandidateCount * 2));
            }
            zeroArcCandidates[zeroArcCandidateCount++] = arcId;
        }
        scannedArcBound = tmesh.arcs.size();

        int found = ArcNetwork.NONE;
        int bestValence = 0;
        int keep = 0;
        for (int index = 0; index < zeroArcCandidateCount; index++) {
            int arcId = zeroArcCandidates[index];
            EmbeddedArc arc = tmesh.arcs.get(arcId);
            if (!arc.alive) {
                continue;
            }
            zeroArcCandidates[keep++] = arcId;
            int movableNodeId = movingEndpoint(arc);
            if (movableNodeId == ArcNetwork.NONE) {
                continue;
            }
            int valence = tmesh.arcEndsByNode.get(movableNodeId).size();
            if (found == ArcNetwork.NONE || valence > bestValence) {
                found = arcId;
                bestValence = valence;
            }
        }
        zeroArcCandidateCount = keep;
        return found;
    }

    /**
     * Collapses one zero arc: moves its movable node onto the other, dragging every
     * other incident arc along, embeds the arc onto that point, and retires the
     * moved node and the arc. The T-mesh loses one node and one arc together, so
     * its Euler characteristic is unchanged.
     *
     * @param arcId zero arc to collapse
     * @throws IllegalStateException when the arc is not a collapsible zero arc
     */
    public void collapse(int arcId) {
        beginCollapse(arcId);
        while (dragNextArc()) {
            continue;
        }
        finishCollapse();
    }

    /**
     * Starts a collapse: resolves the moving and surviving nodes, snapshots the
     * collapsing arc's path and orders the fan. Drags run through
     * {@link #dragNextArc} and the merge lands in {@link #finishCollapse}.
     *
     * @param arcId zero arc to collapse
     * @throws IllegalStateException when the arc is not a collapsible zero arc
     */
    public void beginCollapse(int arcId) {
        EmbeddedArc arc = tmesh.arcs.get(arcId);
        if (!arc.alive || arc.quantizedLength != 0) {
            throw new IllegalStateException(ArcNetwork.NONE == arcId ? "no arc"
                    : "arc " + arcId + " is not a live zero arc");
        }
        movedNodeId = movingEndpoint(arc);
        if (movedNodeId == ArcNetwork.NONE) {
            throw new IllegalStateException("zero arc " + arcId + " is not collapsible: both of its"
                    + " nodes " + arc.startNodeId + " and " + arc.endNodeId + " are critical, so the"
                    + " quantization has placed two prescribed points at zero distance");
        }
        survivingNodeId = arc.otherNode(movedNodeId);
        movedVertex = tmesh.nodes.get(movedNodeId).copyVertex;
        targetVertex = tmesh.nodes.get(survivingNodeId).copyVertex;
        channel.clear();
        channel.addAll(arc.path.copyVertexPath);
        int channelNeighbor = channel.size() < 2 ? ArcNetwork.NONE
                : channel.get(channel.size() - 1) == movedVertex
                        ? channel.get(channel.size() - 2)
                        : channel.get(1);
        orderFan(movedVertex, channelNeighbor, arcId, movedNodeId);
        frontArrivalSpoke = ArcNetwork.NONE;
        backArrivalSpoke = ArcNetwork.NONE;
        if (channel.size() > 1) {
            int arcSpoke = outgoingHalfEdge(targetVertex,
                    channel.get(0) == targetVertex ? channel.get(1) : channel.get(channel.size() - 2));
            frontArrivalSpoke = nextClaimedSpoke(arcSpoke, false);
            backArrivalSpoke = nextClaimedSpoke(arcSpoke, true);
        }
        fanCursor = 0;
        lastDraggedArcId = ArcNetwork.NONE;
        lastDraggedPreviousPath.clear();
        touchedPatchCount = 0;
        rememberTouchedPatch(arc.leftPatchId);
        rememberTouchedPatch(arc.rightPatchId);
        for (int incidentArcId : fan) {
            EmbeddedArc incidentArc = tmesh.arcs.get(incidentArcId);
            rememberTouchedPatch(incidentArc.leftPatchId);
            rememberTouchedPatch(incidentArc.rightPatchId);
        }
        collapsingArcId = arcId;
    }

    /**
     * Drags one fan arc onto the surviving node, taking the fan's two ends before
     * its middle. Contracting the zero arc splices the moving node's rotation into
     * the surviving node's in its place, so each arc arrives beside the last one
     * dragged from its end of the fan.
     *
     * @throws ArrangementDiagnosticException when the arc has no edge path
     * @return true when an arc was dragged, false when the fan is exhausted
     */
    public boolean dragNextArc() {
        if (movedNodeId == survivingNodeId) {
            return false;
        }
        int size = fan.size();
        while (fanCursor < size) {
            int index = fanCursor++;
            int oscillatingIndex = oscillatingFanIndex(index);
            int incidentArcId = fan.get(oscillatingIndex);
            EmbeddedArc incidentArc = tmesh.arcs.get(incidentArcId);
            if (!incidentArc.alive) {
                continue;
            }
            lastDraggedArcId = incidentArcId;
            lastDraggedPreviousPath.clear();
            lastDraggedPreviousPath.addAll(incidentArc.path.copyVertexPath);
            boolean lastLiveArc = true;
            for (int later = fanCursor; later < size && lastLiveArc; later++) {
                lastLiveArc = !tmesh.arcs.get(fan.get(oscillatingFanIndex(later))).alive;
            }
            // The zero arc keeps the arrivals from the fan's two ends apart until the last
            // drag, which may then use the edges it frees.
            if (lastLiveArc) {
                tmesh.setPath(collapsingArcId, List.of(targetVertex));
            }
            dragPathEnd(incidentArcId, fanMovesPathStart[oscillatingIndex], index % 2 == 0,
                    movedVertex, targetVertex);
            return true;
        }
        return false;
    }

    /**
     * The fan entry the drag order takes at one step: the fan's two ends first,
     * alternating, then inward.
     *
     * @param step position in the drag order
     * @return index into {@link #fan}
     */
    private int oscillatingFanIndex(int step) {
        return step % 2 == 0 ? step / 2 : fan.size() - (step / 2) - 1;
    }

    /**
     * Finishes the collapse once the fan is drained: embeds the collapsing arc onto
     * the surviving vertex, merges the nodes, retires the arc and any fan arc left
     * on a point, and re-reads the covers this collapse moved.
     */
    public void finishCollapse() {
        tmesh.setPath(collapsingArcId, List.of(targetVertex));
        tmesh.mergeNodeInto(survivingNodeId, movedNodeId);
        tmesh.removeCollapsedArc(collapsingArcId, survivingNodeId != movedNodeId);
        // A fan arc whose far node was the survivor is now a loop embedded on a point; it
        // separates nothing, so it is retired and its flanks merge before the covers are
        // re-read — leaving it alive would let the twin flanks share one cell unlabeled.
        for (int incidentArcId : fan) {
            EmbeddedArc incidentArc = tmesh.arcs.get(incidentArcId);
            if (incidentArc.alive && incidentArc.path.copyVertexPath.size() == 1) {
                tmesh.retirePointEmbeddedArc(incidentArcId);
            }
        }
        // The drags never relabel — mid-collapse cells are transient merges no label fits —
        // so every cover this collapse moved is re-read here, once the arrangement is whole.
        for (int index = 0; index < touchedPatchCount; index++) {
            tmesh.relabelPatchCover(touchedPatches[index]);
        }
        collapsedCount++;
        collapsingArcId = ArcNetwork.NONE;
    }

    /**
     * The in-flight collapse as geometry groups: the collapsing arc, the last
     * drag's previous and new paths, and the moving and target vertices.
     *
     * @return groups for the renderer; meaningful only between
     *         {@link #beginCollapse} and {@link #finishCollapse}
     */
    public ArrangementDiagnostic stepDiagnostic() {
        ArrangementDiagnostic diagnostic = new ArrangementDiagnostic();
        diagnostic.addPathGroup(GROUP_CHANNEL, List.copyOf(channel));
        if (lastDraggedArcId != ArcNetwork.NONE) {
            diagnostic.addPathGroup("dragged arc previous path",
                    List.copyOf(lastDraggedPreviousPath));
            diagnostic.addPathGroup("dragged arc new path",
                    tmesh.arcs.get(lastDraggedArcId).path.copyVertexPath);
        }
        diagnostic.addMarkerGroup(GROUP_MOVED_VERTEX, new int[] { movedVertex });
        diagnostic.addMarkerGroup(GROUP_TARGET_VERTEX, new int[] { targetVertex });
        return diagnostic;
    }

    /**
     * Re-embeds the end of one arc that sits on the moving node's vertex onto the
     * node's new vertex; the path's end there is the one moved.
     *
     * @param arcId            arc whose end is being dragged
     * @param movedCopyVertex  the moving node's old copy vertex, an endpoint of the
     *                         arc's path
     * @param targetCopyVertex the moving node's new copy vertex
     * @throws IllegalStateException          when the arc's path does not end at the
     *                                        moved vertex
     * @throws ArrangementDiagnosticException when refinement still finds no edge
     *                                        path
     */
    public void dragArcEndOntoVertex(int arcId, int movedCopyVertex, int targetCopyVertex) {
        List<Integer> path = tmesh.arcs.get(arcId).path.copyVertexPath;
        dragPathEnd(arcId, path.get(path.size() - 1) != movedCopyVertex, true, movedCopyVertex,
                targetCopyVertex);
    }

    /**
     * Re-embeds one arc by Dijkstra from its fixed vertex, leaving through the
     * corner it held there, to the node's new vertex beside the arc dragged before
     * it. An arc closing into a loop routes from its middle vertex.
     *
     * <p>
     * See also: LCBK19 Section 6.1, "Operator Implementation"
     *
     * @param arcId            arc whose end is being dragged
     * @param movesPathStart   whether the dragged end is the path's start
     * @param fromFront        whether the arc is dragged from the fan's front, so
     *                         it arrives after {@link #frontArrivalSpoke} rather than
     *                         before {@link #backArrivalSpoke}
     * @param movedCopyVertex  the moving node's old copy vertex
     * @param targetCopyVertex the moving node's new copy vertex
     * @throws IllegalStateException          when that path end is not on the moved
     *                                        vertex
     * @throws ArrangementDiagnosticException when refinement still finds no edge
     *                                        path
     */
    private void dragPathEnd(int arcId, boolean movesPathStart, boolean fromFront,
            int movedCopyVertex, int targetCopyVertex) {
        EmbeddedArc arc = tmesh.arcs.get(arcId);
        List<Integer> oldPath = new ArrayList<>(arc.path.copyVertexPath);
        if (oldPath.size() == 1) {
            if (oldPath.get(0) != movedCopyVertex && oldPath.get(0) != targetCopyVertex) {
                throw new IllegalStateException("arc " + arcId + " is embedded as the point "
                        + oldPath.get(0) + ", which is neither the moving node's vertex "
                        + movedCopyVertex + " nor its target " + targetCopyVertex
                        + "; a point-embedded arc must sit on the node it belongs to");
            }
            tmesh.setPath(arcId, List.of(targetCopyVertex));
            return;
        }
        if (movesPathStart) {
            Collections.reverse(oldPath);
        }
        if (oldPath.get(oldPath.size() - 1) != movedCopyVertex) {
            throw new IllegalStateException("arc " + arcId + " path does not end at the moved"
                    + " node's vertex " + movedCopyVertex);
        }
        // Both ends land on the surviving vertex, so the merge closes the arc into a loop
        // (LCBK19 Appendix A.3); it may shrink to that point only when it then separates nothing.
        int farVertex = oldPath.get(0);
        if (farVertex == targetCopyVertex && pointEmbedsCleanly(arc)) {
            tmesh.setPath(arcId, List.of(targetCopyVertex));
            // Its spoke may have bounded the arrivals; the next held spoke outward takes over.
            if (frontArrivalSpoke != ArcNetwork.NONE && !spokeHeld(frontArrivalSpoke)) {
                frontArrivalSpoke = nextClaimedSpoke(frontArrivalSpoke, false);
            }
            if (backArrivalSpoke != ArcNetwork.NONE && !spokeHeld(backArrivalSpoke)) {
                backArrivalSpoke = nextClaimedSpoke(backArrivalSpoke, true);
            }
            return;
        }
        tmesh.releaseClaims(arc.path);
        boolean closesIntoLoop = farVertex == targetCopyVertex || farVertex == movedCopyVertex;
        if (closesIntoLoop && oldPath.size() == 2) {
            oldPath.add(1, tmesh.topology.splitEdgeAtMidpoint(
                    tmesh.topology.copy.edgeBetween(farVertex, movedCopyVertex)));
        }
        int anchorIndex = closesIntoLoop ? oldPath.size() / 2 : 0;
        int anchorVertex = oldPath.get(anchorIndex);
        List<Integer> routedVertices = new ArrayList<>(oldPath.subList(0, anchorIndex + 1));
        List<Integer> routedEdges = new ArrayList<>();
        rerouter.rebuildLegEdges(routedVertices, routedEdges);
        ArcEdgePath keptHalf = new ArcEdgePath(arcId, new ArrayList<>(routedVertices),
                new ArrayList<>(routedEdges));
        tmesh.topology.claimPath(arcId, keptHalf);
        rerouter.openCorners();
        int departureSpoke = outgoingHalfEdge(anchorVertex, oldPath.get(anchorIndex + 1));
        admitFacesBetween(anchorVertex, nextClaimedSpoke(departureSpoke, false),
                nextClaimedSpoke(departureSpoke, true), false);
        if (collapsingArcId != ArcNetwork.NONE) {
            int besideSpoke = fromFront ? frontArrivalSpoke : backArrivalSpoke;
            int farSpoke = nextClaimedSpoke(besideSpoke, fromFront);
            admitFacesBetween(targetCopyVertex, fromFront ? besideSpoke : farSpoke,
                    fromFront ? farSpoke : besideSpoke, true);
        }
        // Once the zero arc lies on the point this is the last arc on the moving node, whose
        // vertex is then an ordinary one the route may cross.
        boolean movedVertexVacated = collapsingArcId == ArcNetwork.NONE
                || tmesh.arcs.get(collapsingArcId).path.copyVertexPath.size() == 1;
        boolean routed;
        try {
            routed = rerouter.tryRoute(arcId, routedVertices, anchorVertex, targetCopyVertex,
                    rerouter.freshCorridor(), movedVertexVacated ? movedCopyVertex
                            : EmbeddedMeshTopology.UNCLAIMED);
        } finally {
            rerouter.closeCorners();
        }
        if (!routed) {
            tmesh.releaseClaims(keptHalf);
            blockedDragCount++;
            throw blockedDrag(arc, oldPath, anchorVertex, movedCopyVertex, targetCopyVertex);
        }
        rerouter.rebuildLegEdges(routedVertices, routedEdges);
        int arrivedSpoke = outgoingHalfEdge(targetCopyVertex,
                routedVertices.get(routedVertices.size() - 2));
        if (fromFront) {
            frontArrivalSpoke = arrivedSpoke;
        } else {
            backArrivalSpoke = arrivedSpoke;
        }
        if (movesPathStart) {
            Collections.reverse(routedVertices);
            Collections.reverse(routedEdges);
        }
        arc.path = new ArcEdgePath(arcId, routedVertices, routedEdges);
        tmesh.topology.claimPath(arcId, arc.path);
        rememberTouchedPatch(arc.leftPatchId);
        rememberTouchedPatch(arc.rightPatchId);
    }

    /**
     * Whether an arc holds a spoke.
     *
     * @param spokeHalfEdge half-edge of the spoke
     * @return true when its edge is claimed
     */
    private boolean spokeHeld(int spokeHalfEdge) {
        return tmesh.topology.ownerArcByCopyEdge[tmesh.topology.copy.halfEdgeEdge(spokeHalfEdge)]
                != EmbeddedMeshTopology.UNCLAIMED;
    }

    /**
     * The nearest spoke an arc holds, rotating around a vertex from one of its
     * spokes; the start spoke itself when it is the only one held.
     *
     * @param spokeHalfEdge half-edge leaving the vertex to rotate from
     * @param forward       whether to rotate the way the half-edge's own face lies
     * @return the held spoke, or {@link ArcNetwork#NONE} when no arc holds one
     */
    private int nextClaimedSpoke(int spokeHalfEdge, boolean forward) {
        if (spokeHalfEdge < 0) {
            return ArcNetwork.NONE;
        }
        HalfEdgeMesh copy = tmesh.topology.copy;
        int spokeCount = copy.vertexEdgeCount(copy.halfEdgeVertex(spokeHalfEdge));
        int halfEdge = spokeHalfEdge;
        for (int walked = 0; walked < spokeCount; walked++) {
            halfEdge = rotateSpoke(halfEdge, forward);
            if (halfEdge < 0) {
                return ArcNetwork.NONE;
            }
            if (tmesh.topology.ownerArcByCopyEdge[copy.halfEdgeEdge(halfEdge)]
                    != EmbeddedMeshTopology.UNCLAIMED) {
                return halfEdge;
            }
        }
        return ArcNetwork.NONE;
    }

    /**
     * Admits to one end of the next route the faces around a vertex from one spoke
     * rotating forward to another, or every face around it when no spoke bounds
     * them.
     *
     * @param vertex        vertex the route leaves or reaches
     * @param firstHalfEdge spoke leaving the vertex that opens the corner, or
     *                      {@link ArcNetwork#NONE}
     * @param lastHalfEdge  spoke that closes it; the same spoke closes a full turn
     * @param arrival       whether this is the route's target rather than its start
     */
    private void admitFacesBetween(int vertex, int firstHalfEdge, int lastHalfEdge,
            boolean arrival) {
        HalfEdgeMesh copy = tmesh.topology.copy;
        if (firstHalfEdge == ArcNetwork.NONE) {
            for (int index = 0; index < copy.vertexFaceCount(vertex); index++) {
                rerouter.admitCornerFace(copy.vertexFaceAt(vertex, index), arrival);
            }
            return;
        }
        int halfEdge = firstHalfEdge;
        for (int walked = 0; walked < copy.vertexEdgeCount(vertex); walked++) {
            if (copy.halfEdgeFace(halfEdge) >= 0) {
                rerouter.admitCornerFace(copy.halfEdgeFace(halfEdge), arrival);
            }
            halfEdge = rotateSpoke(halfEdge, true);
            if (halfEdge < 0 || halfEdge == lastHalfEdge) {
                return;
            }
        }
    }

    /**
     * The next spoke around the vertex a spoke leaves.
     *
     * @param spokeHalfEdge half-edge leaving the vertex
     * @param forward       whether to rotate the way the half-edge's own face lies
     * @return the neighbouring spoke's half-edge, or -1 at the mesh boundary
     */
    private int rotateSpoke(int spokeHalfEdge, boolean forward) {
        HalfEdgeMesh copy = tmesh.topology.copy;
        int through = forward ? copy.halfEdgePrev(spokeHalfEdge) : spokeHalfEdge;
        int twin = through < 0 ? -1 : copy.halfEdgeTwin(through);
        if (twin < 0 || forward) {
            return twin;
        }
        return copy.halfEdgeFace(twin) < 0 ? -1 : copy.halfEdgeNext(twin);
    }

    /**
     * The half-edge of the copy running from one vertex to an adjacent one.
     *
     * @param fromVertex vertex the half-edge leaves
     * @param toVertex   adjacent vertex it reaches
     * @return the oriented half-edge
     */
    private int outgoingHalfEdge(int fromVertex, int toVertex) {
        HalfEdgeMesh copy = tmesh.topology.copy;
        int halfEdge = copy.edgeHalfEdge(copy.edgeBetween(fromVertex, toVertex));
        return copy.halfEdgeVertex(halfEdge) == fromVertex ? halfEdge : copy.halfEdgeTwin(halfEdge);
    }

    /**
     * The failure LCBK19 leaves open: no edge path between the two vertices even
     * after refinement, reported with the region the search could reach and the two
     * patches the arc separates.
     *
     * @param arc              arc that could not be re-embedded
     * @param oldPath          its path before the drag, ending at the moved vertex
     * @param anchorVertex     vertex the failed route left
     * @param movedCopyVertex  the moving node's old copy vertex
     * @param targetCopyVertex the moving node's new copy vertex
     * @return the throwable failure, carrying the region as geometry groups
     */
    private ArrangementDiagnosticException blockedDrag(EmbeddedArc arc, List<Integer> oldPath,
            int anchorVertex, int movedCopyVertex, int targetCopyVertex) {
        Set<Integer> boundaryArcs = new HashSet<>();
        boolean reachedTarget = floodFreeSpace(anchorVertex, targetCopyVertex, boundaryArcs);
        int[] freeFaces = freeRegionFaces.toArray();
        IntIdList arrivalFaces = new IntIdList(0);
        int arrivalFacesReached = 0;
        for (int faceId = 0; faceId < rerouter.arrivalStampByFace.length; faceId++) {
            if (rerouter.arrivalStampByFace[faceId] == rerouter.cornerStamp) {
                arrivalFaces.add(faceId);
                arrivalFacesReached += freeRegionStampByCopyFace.length > faceId
                        && freeRegionStampByCopyFace[faceId] == freeRegionStamp ? 1 : 0;
            }
        }
        int leftPatchId = tmesh.topology.resolvePatch(arc.leftPatchId);
        int rightPatchId = tmesh.topology.resolvePatch(arc.rightPatchId);
        ArrangementDiagnostic diagnostic = new ArrangementDiagnostic();
        diagnostic.addFaceGroup("free region", freeFaces);
        diagnostic.addFaceGroup("arrival corner", arrivalFaces.toArray());
        diagnostic.addFaceGroup("left cover", coverFaces(leftPatchId));
        diagnostic.addFaceGroup("right cover", coverFaces(rightPatchId));
        diagnostic.addPathGroup(GROUP_CHANNEL, List.copyOf(channel));
        diagnostic.addPathGroup("released arc path", List.copyOf(oldPath));
        diagnostic.addMarkerGroup(GROUP_MOVED_VERTEX, new int[] { movedCopyVertex });
        diagnostic.addMarkerGroup(GROUP_TARGET_VERTEX, new int[] { targetCopyVertex });
        return new ArrangementDiagnosticException("arc " + arc.arcId
                + " could not be re-embedded from vertex " + anchorVertex + " onto vertex "
                + targetCopyVertex + " inside patches " + leftPatchId + " and " + rightPatchId
                + ", which it separates: no edge path between them survives refinement; moved"
                + " vertex " + movedCopyVertex + " path " + oldPath + " collapsing arc "
                + collapsingArcId + " " + channel + "\n free region from " + anchorVertex
                + " reaches " + freeRegionFaces.size() + " faces, target reached " + reachedTarget
                + ", bounded by arcs " + boundaryArcs + "; " + arrivalFacesReached + " of the "
                + arrivalFaces.size() + " faces it may arrive through are in that region",
                diagnostic);
    }

    /**
     * Whether the merge may embed an arc on the surviving point: only when at most
     * one of its flanks keeps boundary of its own, so the point separates nothing.
     * {@link ArcNetwork#retirePointEmbeddedArc} asks the same question afterwards.
     *
     * @param arc arc being dragged, whose far node is already the surviving node
     * @return true unless both of its flanks keep a side of their own
     */
    private boolean pointEmbedsCleanly(EmbeddedArc arc) {
        int flanksKeepingExtent = 0;
        for (int flankId : new int[] { arc.leftPatchId, arc.rightPatchId }) {
            int patchId = tmesh.topology.resolvePatch(flankId);
            if (patchId == ArcNetwork.NONE || !tmesh.patches.get(patchId).alive) {
                continue;
            }
            boolean keepsExtent = false;
            for (List<Integer> sideArcIds : tmesh.patches.get(patchId).sideArcIds) {
                for (int sideArcId : sideArcIds) {
                    EmbeddedArc sideArc = tmesh.arcs.get(sideArcId);
                    keepsExtent |= sideArcId != arc.arcId && sideArcId != collapsingArcId
                            && sideArc.alive && sideArc.path.copyVertexPath.size() > 1;
                }
            }
            flanksKeepingExtent += keepsExtent ? 1 : 0;
        }
        return flanksKeepingExtent < 2;
    }

    /**
     * Records a patch whose boundary this collapse moves, for the relabel that
     * follows it.
     *
     * @param patchId flanking patch of the collapsing arc or of one it drags, or
     *                {@link ArcNetwork#NONE}
     */
    private void rememberTouchedPatch(int patchId) {
        if (patchId == ArcNetwork.NONE) {
            return;
        }
        for (int index = 0; index < touchedPatchCount; index++) {
            if (touchedPatches[index] == patchId) {
                return;
            }
        }
        if (touchedPatchCount == touchedPatches.length) {
            touchedPatches = Arrays.copyOf(touchedPatches,
                    Math.max(TOUCHED_PATCH_INITIAL_CAPACITY, touchedPatchCount * 2));
        }
        touchedPatches[touchedPatchCount++] = patchId;
    }

    /**
     * A flanking patch's cover flood as a plain face-id array for a diagnostic
     * group, or empty when the patch is retired or has nothing to flood from.
     *
     * @param patchId resolved flanking patch of the blocked arc, or
     *                {@link ArcNetwork#NONE}
     * @return the copy face ids its cover holds
     */
    private int[] coverFaces(int patchId) {
        if (patchId == ArcNetwork.NONE || !tmesh.patches.get(patchId).alive
                || !tmesh.corridor.hasSeedableBoundary(patchId)) {
            return new int[0];
        }
        IntIdList faces = tmesh.corridor.patchFaces(patchId);
        int[] faceIds = new int[faces.size()];
        for (int index = 0; index < faceIds.length; index++) {
            faceIds[index] = faces.get(index);
        }
        return faceIds;
    }

    /**
     * Floods the faces a route could still reach into {@link #freeRegionFaces},
     * crossing only edges no arc holds, which is the region the failed drag was
     * confined to.
     *
     * @param startVertex      vertex to flood from
     * @param targetCopyVertex vertex to look for
     * @param boundaryArcs     receives the arcs the flood stopped at
     * @return whether the flood reached a face on the target
     */
    private boolean floodFreeSpace(int startVertex, int targetCopyVertex,
            Set<Integer> boundaryArcs) {
        EmbeddedMeshTopology topology = tmesh.topology;
        HalfEdgeMesh copy = topology.copy;
        int faceIdBound = topology.sourceFaceByCopyFace.length;
        if (freeRegionStampByCopyFace.length < faceIdBound) {
            freeRegionStampByCopyFace = Arrays.copyOf(freeRegionStampByCopyFace,
                    Math.max(faceIdBound, freeRegionStampByCopyFace.length * 2));
        }
        freeRegionStamp++;
        freeRegionFaces.clear();
        for (int index = 0; index < copy.vertexFaceCount(startVertex); index++) {
            int faceId = copy.vertexFaceAt(startVertex, index);
            if (freeRegionStampByCopyFace[faceId] != freeRegionStamp) {
                freeRegionStampByCopyFace[faceId] = freeRegionStamp;
                freeRegionFaces.add(faceId);
            }
        }
        for (int cursor = 0; cursor < freeRegionFaces.size(); cursor++) {
            int faceId = freeRegionFaces.get(cursor);
            for (int corner = 0; corner < copy.faceHalfEdgeCount(faceId); corner++) {
                int edgeId = copy.faceEdgeAt(faceId, corner);
                if (topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                    boundaryArcs.add(topology.ownerArcByCopyEdge[edgeId]);
                    continue;
                }
                int neighbour = copy.faceAcrossEdge(faceId, edgeId);
                if (neighbour >= 0 && freeRegionStampByCopyFace[neighbour] != freeRegionStamp) {
                    freeRegionStampByCopyFace[neighbour] = freeRegionStamp;
                    freeRegionFaces.add(neighbour);
                }
            }
        }
        for (int index = 0; index < copy.vertexFaceCount(targetCopyVertex); index++) {
            int faceId = copy.vertexFaceAt(targetCopyVertex, index);
            if (faceId >= 0 && freeRegionStampByCopyFace[faceId] == freeRegionStamp) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fills {@link #fan} with the moving node's path ends in cyclic order, starting
     * at the spoke beside the collapsing arc, read by rotating the copy's
     * half-edges around the pivot; a loop there has two entries, and ends the
     * rotation misses (arcs embedded on the point) are appended.
     *
     * @param pivotVertex     the collapsing node's copy vertex
     * @param channelNeighbor the collapsing arc's vertex adjacent to the pivot,
     *                        whose spoke starts the fan
     * @param collapsingArc   the arc being collapsed, excluded from the fan
     * @param movingNodeId    the collapsing node id, for its full incident-arc set
     */
    private void orderFan(int pivotVertex, int channelNeighbor, int collapsingArc,
            int movingNodeId) {
        HalfEdgeMesh copy = tmesh.topology.copy;
        int rotationCap = copy.vertexEdgeCount(pivotVertex) + 2;
        int startHalfEdge = copy.vertexOutgoingHalfEdge(pivotVertex);
        int probe = startHalfEdge;
        for (int step = 0; step < rotationCap && probe >= 0; step++) {
            if (copy.halfEdgeEndVertex(probe) == channelNeighbor) {
                startHalfEdge = probe;
                break;
            }
            probe = copy.halfEdgeTwin(copy.halfEdgePrev(probe));
        }
        fan.clear();
        List<Boolean> movesStart = new ArrayList<>();
        Set<Integer> seenEnds = new HashSet<>();
        int halfEdge = startHalfEdge;
        for (int step = 0; step < rotationCap && halfEdge >= 0; step++) {
            int owner = tmesh.topology.ownerArcByCopyEdge[copy.halfEdgeEdge(halfEdge)];
            if (owner != EmbeddedMeshTopology.UNCLAIMED && owner != collapsingArc
                    && tmesh.arcs.get(owner).alive) {
                List<Integer> path = tmesh.arcs.get(owner).path.copyVertexPath;
                boolean start = path.get(0) == pivotVertex
                        && path.get(1) == copy.halfEdgeEndVertex(halfEdge);
                if (seenEnds.add(2 * owner + (start ? 1 : 0))) {
                    fan.add(owner);
                    movesStart.add(start);
                }
            }
            halfEdge = copy.halfEdgeTwin(copy.halfEdgePrev(halfEdge));
            if (halfEdge == startHalfEdge) {
                break;
            }
        }
        for (int incidentArcId : tmesh.arcEndsByNode.get(movingNodeId)) {
            EmbeddedArc incidentArc = tmesh.arcs.get(incidentArcId);
            boolean start = incidentArc.path.copyVertexPath.get(0) == pivotVertex;
            if (incidentArcId != collapsingArc && incidentArc.alive
                    && !seenEnds.contains(2 * incidentArcId)
                    && seenEnds.add(2 * incidentArcId + (start ? 1 : 0))) {
                fan.add(incidentArcId);
                movesStart.add(start);
            }
        }
        fanMovesPathStart = new boolean[fan.size()];
        for (int index = 0; index < fan.size(); index++) {
            fanMovesPathStart[index] = movesStart.get(index);
        }
    }

    /**
     * The endpoint of a zero arc that LCBK19 Def 6.2 permits to move, or
     * {@link ArcNetwork#NONE} when neither may.
     *
     * <p>
     * A loop is always collapsible: both ends already sit on one point, so nothing
     * moves. {@link #isCollapsibleFrom} would interrogate that node twice and
     * refuse every loop on a critical one.
     *
     * @param arc zero arc to test
     * @return the movable node's id, preferring the one with fewer incident arcs
     *         and the lower id, the single node when the arc is a loop, or
     *         {@link ArcNetwork#NONE} when both endpoints are fixed
     */
    private int movingEndpoint(EmbeddedArc arc) {
        if (arc.isLoop()) {
            return arc.startNodeId;
        }
        boolean startMovable = isCollapsibleFrom(arc, arc.startNodeId);
        boolean endMovable = isCollapsibleFrom(arc, arc.endNodeId);
        if (!startMovable && !endMovable) {
            return ArcNetwork.NONE;
        }
        if (startMovable != endMovable) {
            return startMovable ? arc.startNodeId : arc.endNodeId;
        }
        int startDegree = tmesh.degree(arc.startNodeId);
        int endDegree = tmesh.degree(arc.endNodeId);
        if (startDegree != endDegree) {
            return startDegree < endDegree ? arc.startNodeId : arc.endNodeId;
        }
        return Math.min(arc.startNodeId, arc.endNodeId);
    }

    /**
     * Whether a zero arc is collapsible in the direction that moves the given node,
     * per LCBK19 Def 6.2: the node must be non-critical, and either the arc is a
     * border arc or the node is not a border node.
     *
     * @param arc    zero arc
     * @param nodeId endpoint that would move
     * @return true when moving that node is permitted
     */
    private boolean isCollapsibleFrom(EmbeddedArc arc, int nodeId) {
        EmbeddedNode node = tmesh.nodes.get(nodeId);
        return !node.critical && (arc.feature || !node.border);
    }
}
