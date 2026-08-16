package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ixdar.geometry.mesh.data.representation.ActiveIdSet;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.IntIdList;
import ixdar.geometry.mesh.quadlayout.embedding.records.ArcEdgePath;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedNode;

/**
 * Operator (1), the zero-arc collapse: one endpoint node is embedded onto the
 * other, dragging its incident arcs with it.
 *
 * <p>
 * An endpoint may move when non-critical and either a non-border node or on a
 * border arc; with neither movable this throws. A loop is exempt.
 *
 * <p>
 * See also: LCBK19 Def 6.2
 */
public final class ZeroArcCollapseOperator {

    /** Starting capacity of the zero-arc candidate list; grows by doubling. */
    private static final int CANDIDATE_INITIAL_CAPACITY = 256;

    /** Diagnostic group name of the collapsing arc's vacated path. */
    private static final String GROUP_CHANNEL = "channel";

    /** Diagnostic group name of the moving node's vertex marker. */
    private static final String GROUP_MOVED_VERTEX = "moved vertex";

    /** Diagnostic group name of the surviving node's vertex marker. */
    private static final String GROUP_TARGET_VERTEX = "target vertex";

    /** First allocation of {@link #touchedPatches}. */
    private static final int TOUCHED_PATCH_INITIAL_CAPACITY = 8;

    public final EmbeddedTMesh tmesh;
    public final ArcRerouter rerouter;

    public int collapsedCount;

    /**
     * Drags with no route inside the patches their arc separates; a non-zero count
     * means the covers or the fan order no longer describe the surface, and the
     * collapse throws.
     */
    public int blockedDragCount;

    /**
     * Arrival wedges banned before the last drag's search because their bounding lanes' flanks
     * contradict the dragged arc's; reset per drag.
     */
    public int bannedArrivalWedgeCount;

    /** Departure wedges the last drag banned as flank-inconsistent at its fixed vertex. */
    public int bannedDepartureWedgeCount;

    /** Faces the last {@link #floodFreeSpace} reached, in flood order. */
    public final IntIdList freeRegionFaces = new IntIdList(0);

    /** Visit stamps of {@link #floodFreeSpace}, indexed by copy face. */
    public int[] freeRegionStampByCopyFace = new int[0];

    /** Current stamp generation of {@link #freeRegionStampByCopyFace}. */
    public int freeRegionStamp;

    /**
     * Patches the running collapse touches: the flanks of the arc it collapses and
     * of every arc it drags. They are what a mid-collapse route may be admitted to.
     */
    public int[] touchedPatches = new int[0];

    /** Live entry count of {@link #touchedPatches}. */
    public int touchedPatchCount;

    /**
     * Of {@link #touchedPatches}, the flanks of arcs a route actually moved — the
     * only covers whose faces changed, so the only ones {@link #finishCollapse}
     * re-reads. Alias-only merges keep their faces and resolve through the covers.
     */
    public int[] dirtyPatches = new int[0];

    /** Live entry count of {@link #dirtyPatches}. */
    public int dirtyPatchCount;

    /**
     * Arc the in-flight collapse is collapsing, or {@link EmbeddedTMesh#NONE} when
     * no collapse is between {@link #beginCollapse} and {@link #finishCollapse}.
     */
    public int collapsingArcId = EmbeddedTMesh.NONE;

    /** The in-flight collapse's moving node. */
    public int movedNodeId;

    /** The in-flight collapse's surviving node. */
    public int survivingNodeId;

    /** Copy vertex the in-flight collapse's moving node stands on. */
    public int movedVertex;

    /** Copy vertex the in-flight collapse merges the moving node onto. */
    public int targetVertex;

    /** The collapsing arc's path, snapshotted before any drag. */
    public final List<Integer> channel = new ArrayList<>();

    /** The moving node's incident arcs in cyclic fan order. */
    public final List<Integer> fan = new ArrayList<>();

    /** Fan entries already consumed, indexing the oscillating drag order. */
    public int fanCursor;

    /** Fan arcs whose pre-ban left no allowed wedge, awaiting a sibling's lane. */
    public final List<Integer> deferredArcIds = new ArrayList<>();

    /** Deferred attempts since the last landed drag, bounding the retry rounds. */
    public int deferredAttemptsSinceProgress;

    /**
     * Arc the last {@link #dragNextArc} dragged, or {@link EmbeddedTMesh#NONE}
     * before the first drag.
     */
    public int lastDraggedArcId = EmbeddedTMesh.NONE;

    /** The last dragged arc's path before its drag, for the step view. */
    public final List<Integer> lastDraggedPreviousPath = new ArrayList<>();

    /**
     * First arc id the collapsible scan starts at. Only ever advanced past dead or
     * non-zero arcs, which can never become collapsible ({@code alive} is set only
     * in the constructor and {@code quantizedLength} never changes), so the scan
     * skips the growing retired prefix without missing a candidate.
     */
    public int collapsibleScanStart;

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
    public ZeroArcCollapseOperator(EmbeddedTMesh tmesh) {
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
     * @return the chosen zero arc id, or {@link EmbeddedTMesh#NONE} when none
     *         remains
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

        int found = EmbeddedTMesh.NONE;
        int bestValence = 0;
        int keep = 0;
        for (int index = 0; index < zeroArcCandidateCount; index++) {
            int arcId = zeroArcCandidates[index];
            EmbeddedArc arc = tmesh.arcs.get(arcId);
            if (!arc.alive) {
                continue;
            }
            zeroArcCandidates[keep++] = arcId;
            int movedNodeId = movingEndpoint(arc);
            if (movedNodeId == EmbeddedTMesh.NONE) {
                continue;
            }
            int valence = tmesh.arcEndsByNode.get(movedNodeId).size();
            if (found == EmbeddedTMesh.NONE || valence > bestValence) {
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
     * channel, orders the fan, and records the touched patches. Drags run through
     * {@link #dragNextArc} and the merge lands in {@link #finishCollapse}.
     *
     * @param arcId zero arc to collapse
     * @throws IllegalStateException when the arc is not a collapsible zero arc
     */
    public void beginCollapse(int arcId) {
        EmbeddedArc arc = tmesh.arcs.get(arcId);
        if (!arc.alive || arc.quantizedLength != 0) {
            throw new IllegalStateException(EmbeddedTMesh.NONE == arcId ? "no arc"
                    : "arc " + arcId
                            + " is not a live zero arc");
        }
        movedNodeId = movingEndpoint(arc);
        if (movedNodeId == EmbeddedTMesh.NONE) {
            throw new IllegalStateException("zero arc " + arcId + " is not collapsible: both of its"
                    + " nodes " + arc.startNodeId + " and " + arc.endNodeId + " are critical, so the"
                    + " quantization has placed two prescribed points at zero distance");
        }
        survivingNodeId = arc.otherNode(movedNodeId);
        movedVertex = tmesh.nodes.get(movedNodeId).copyVertex;
        targetVertex = tmesh.nodes.get(survivingNodeId).copyVertex;
        channel.clear();
        channel.addAll(arc.path.copyVertexPath);
        int channelNeighbor = channel.size() < 2 ? EmbeddedTMesh.NONE
                : channel.get(channel.size() - 1) == movedVertex
                        ? channel.get(channel.size() - 2)
                        : channel.get(1);
        fan.clear();
        fan.addAll(incidentArcsInFanOrder(movedVertex, channelNeighbor, arcId, movedNodeId));
        fanCursor = 0;
        deferredArcIds.clear();
        deferredAttemptsSinceProgress = 0;
        lastDraggedArcId = EmbeddedTMesh.NONE;
        lastDraggedPreviousPath.clear();
        touchedPatchCount = 0;
        dirtyPatchCount = 0;
        rememberTouchedPatch(arc.leftPatchId);
        rememberTouchedPatch(arc.rightPatchId);
        for (int incidentArcId : fan) {
            EmbeddedArc incidentArc = tmesh.arcs.get(incidentArcId);
            if (incidentArc.alive) {
                rememberTouchedPatch(incidentArc.leftPatchId);
                rememberTouchedPatch(incidentArc.rightPatchId);
            }
        }
        collapsingArcId = arcId;
    }

    /**
     * Drags one fan arc onto the surviving node in oscillating order — ends inward; only
     * the last drag may transit the moved vertex. A pre-ban-blocked drag defers until a
     * sibling's lane supplies its flank witness. A collapsing loop contracts in place.
     *
     * @return true when an arc was dragged, false when the fan is exhausted
     */
    public boolean dragNextArc() {
        if (movedNodeId == survivingNodeId) {
            return false;
        }
        int size = fan.size();
        while (fanCursor < size) {
            int index = fanCursor++;
            int oscillatingIndex = index % 2 == 0 ? index / 2 : size - (index / 2) - 1;
            int incidentArcId = fan.get(oscillatingIndex);
            EmbeddedArc incidentArc = tmesh.arcs.get(incidentArcId);
            if (!incidentArc.alive) {
                continue;
            }
            lastDraggedArcId = incidentArcId;
            lastDraggedPreviousPath.clear();
            lastDraggedPreviousPath.addAll(incidentArc.path.copyVertexPath);
            boolean lastLiveArc = deferredArcIds.isEmpty();
            for (int probe = fanCursor; probe < size && lastLiveArc; probe++) {
                int probeOscillating = probe % 2 == 0 ? probe / 2 : size - (probe / 2) - 1;
                lastLiveArc = !tmesh.arcs.get(fan.get(probeOscillating)).alive;
            }
            boolean isLoop = incidentArc.isLoop() && incidentArc.startNodeId == movedNodeId;
            if (!dragArcEndOntoVertex(incidentArcId, movedVertex, targetVertex, rerouter,
                    channel, lastLiveArc, !lastLiveArc)) {
                deferredArcIds.add(incidentArcId);
                continue;
            }
            if (isLoop) {
                dragArcEndOntoVertex(incidentArcId, movedVertex, targetVertex, rerouter,
                        channel, lastLiveArc, false);
            }
            return true;
        }
        while (!deferredArcIds.isEmpty()) {
            boolean allowDefer = deferredAttemptsSinceProgress < deferredArcIds.size();
            int deferredArcId = deferredArcIds.remove(0);
            EmbeddedArc deferredArc = tmesh.arcs.get(deferredArcId);
            if (!deferredArc.alive) {
                continue;
            }
            lastDraggedArcId = deferredArcId;
            lastDraggedPreviousPath.clear();
            lastDraggedPreviousPath.addAll(deferredArc.path.copyVertexPath);
            boolean lastLiveArc = deferredArcIds.isEmpty();
            boolean isLoop = deferredArc.isLoop() && deferredArc.startNodeId == movedNodeId;
            if (!dragArcEndOntoVertex(deferredArcId, movedVertex, targetVertex, rerouter,
                    channel, lastLiveArc, allowDefer)) {
                deferredArcIds.add(deferredArcId);
                deferredAttemptsSinceProgress++;
                continue;
            }
            if (isLoop) {
                dragArcEndOntoVertex(deferredArcId, movedVertex, targetVertex, rerouter,
                        channel, lastLiveArc, false);
            }
            deferredAttemptsSinceProgress = 0;
            return true;
        }
        return false;
    }

    /**
     * Finishes the collapse once the fan is drained: embeds the arc onto the
     * surviving vertex, merges the nodes, retires the arc and any point-embedded
     * fan arc, and re-reads the touched covers. The T-mesh's
     * {@code arcCollapseCount} is the caller's to bump.
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
        // so every cover a route moved is re-read here, once the arrangement is whole again.
        for (int index = 0; index < dirtyPatchCount; index++) {
            tmesh.relabelPatchCover(dirtyPatches[index]);
        }
        collapsedCount++;
        collapsingArcId = EmbeddedTMesh.NONE;
    }

    /**
     * The in-flight collapse as geometry groups: the channel, the last drag's
     * previous and new paths, and the moving and target vertices.
     *
     * @return groups for the renderer; meaningful only between
     *         {@link #beginCollapse} and {@link #finishCollapse}
     */
    public ArrangementDiagnostic stepDiagnostic() {
        ArrangementDiagnostic diagnostic = new ArrangementDiagnostic();
        diagnostic.addPathGroup(GROUP_CHANNEL, List.copyOf(channel));
        if (lastDraggedArcId != EmbeddedTMesh.NONE) {
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
     * Admits one flanking patch to the router's restriction, resolved through the
     * cover aliases and skipped when retired.
     *
     * @param patchId flanking patch of the released arc, or
     *                {@link EmbeddedTMesh#NONE}
     */
    private void admitAlivePatch(int patchId) {
        int resolved = tmesh.topology.resolvePatch(patchId);
        if (resolved != EmbeddedTMesh.NONE && tmesh.patches.get(resolved).alive) {
            rerouter.admitPatch(resolved);
        }
    }

    /**
     * Records a patch whose boundary this collapse moves, for the relabel that
     * follows it.
     *
     * @param patchId flanking patch of the collapsing arc or of one it drags, or
     *                {@link EmbeddedTMesh#NONE}
     */
    private void rememberTouchedPatch(int patchId) {
        if (patchId == EmbeddedTMesh.NONE) {
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
     * Records a patch whose faces a routed drag moved, for the relabel that follows
     * the collapse.
     *
     * @param patchId flanking patch of the rerouted arc, or {@link EmbeddedTMesh#NONE}
     */
    private void rememberDirtyPatch(int patchId) {
        if (patchId == EmbeddedTMesh.NONE) {
            return;
        }
        for (int index = 0; index < dirtyPatchCount; index++) {
            if (dirtyPatches[index] == patchId) {
                return;
            }
        }
        if (dirtyPatchCount == dirtyPatches.length) {
            dirtyPatches = Arrays.copyOf(dirtyPatches,
                    Math.max(TOUCHED_PATCH_INITIAL_CAPACITY, dirtyPatchCount * 2));
        }
        dirtyPatches[dirtyPatchCount++] = patchId;
    }

    /**
     * Re-routes an arc onto its moving node's new vertex, searching only the patches the
     * in-flight collapse touches — its own two flanks when no collapse is in flight. That
     * restriction is also what pins the arrival slot: an unadmitted sector crosses no claim.
     *
     * <p>
     * See also: LCBK19 Section 6.1
     *
     * @param arcId                  arc whose end is being dragged
     * @param movedVertex            the moving node's old copy vertex, an endpoint
     *                               of the arc's path
     * @param targetVertex           the moving node's new copy vertex
     * @param rerouter               the claims-respecting router
     * @param channel                the collapsing arc's path vertices, for
     *                               diagnostics
     * @param allowMovedVertexTransit whether the route may pass through the moved
     *                               vertex; only the fan's last live drag may, or
     *                               a re-claimed corner there pinches the
     *                               transferred sliver off its patch
     * @param deferBlocked           whether a pre-ban leaving no allowed wedge defers the
     *                               drag — claims restored, false returned — instead of
     *                               throwing; a later sibling's lane may supply the missing
     *                               flank witness
     * @return true when the arc reached its final state, false when the drag deferred
     * @throws IllegalStateException          when the arc's path does not end at
     *                                        the moved vertex
     * @throws ArrangementDiagnosticException when no route exists inside the two
     *                                        patches, or the route would fence in
     *                                        the rest of the fan
     */
    public boolean dragArcEndOntoVertex(int arcId, int movedVertex, int targetVertex,
            ArcRerouter rerouter, List<Integer> channel, boolean allowMovedVertexTransit,
            boolean deferBlocked) {
        EmbeddedArc arc = tmesh.arcs.get(arcId);
        List<Integer> vertices = new ArrayList<>(arc.path.copyVertexPath);
        if (vertices.size() == 1) {
            if (vertices.get(0) == targetVertex) {
                return true;
            }
            if (vertices.get(0) == movedVertex) {
                tmesh.setPath(arcId, List.of(targetVertex));
                return true;
            }
            throw new IllegalStateException("arc " + arcId + " is embedded as the point "
                    + vertices.get(0) + ", which is neither the moving node's vertex "
                    + movedVertex + " nor its target " + targetVertex
                    + "; a point-embedded arc must sit on the node it belongs to");
        }
        boolean reversed = vertices.get(0) == movedVertex;
        if (reversed) {
            Collections.reverse(vertices);
        }
        if (vertices.get(vertices.size() - 1) != movedVertex) {
            throw new IllegalStateException("arc " + arcId + " path does not end at the moved node's"
                    + " vertex " + movedVertex);
        }
        // The far node is already the survivor: the collapse closes this arc into a loop
        // embedded on a point — no search; finishCollapse retires it and merges its flanks.
        if (vertices.get(0) == targetVertex) {
            tmesh.setPath(arcId, List.of(targetVertex));
            return true;
        }
        ArcEdgePath releasedPath = arc.path;
        tmesh.releaseClaims(arc.path);
        rerouter.beginPatchRestriction();
        // Mid-collapse the labels are a pre-collapse snapshot — drags never relabel, because
        // the transient merged cells fit no single label — so a drag must be admitted to the
        // whole neighbourhood the collapse touches, not only its own two flanks.
        if (collapsingArcId != EmbeddedTMesh.NONE) {
            for (int index = 0; index < touchedPatchCount; index++) {
                admitAlivePatch(touchedPatches[index]);
            }
        } else {
            admitAlivePatch(arc.leftPatchId);
            admitAlivePatch(arc.rightPatchId);
        }
        bannedArrivalWedgeCount = 0;
        bannedDepartureWedgeCount = 0;
        int allowedArrivalWedges = banInconsistentWedges(arc, targetVertex, false);
        int allowedDepartureWedges = banInconsistentWedges(arc, vertices.get(0), true);
        if (deferBlocked && (allowedArrivalWedges == 0 || allowedDepartureWedges == 0)) {
            rerouter.clearPatchRestriction();
            tmesh.setPath(arcId, releasedPath.copyVertexPath);
            return false;
        }
        // The pre-bans above read the channel lane as the exempt witness of the collapsing
        // arc's flanks, so the channel is freed only now: the last drag searches the
        // vacated corridor instead of splicing it.
        if (allowMovedVertexTransit && collapsingArcId != EmbeddedTMesh.NONE) {
            tmesh.setPath(collapsingArcId, List.of(targetVertex));
        }
        boolean routed;
        try {
            routed = allowedArrivalWedges > 0 && allowedDepartureWedges > 0
                    && routeDraggedTail(arcId, vertices, reversed, movedVertex, targetVertex,
                            allowMovedVertexTransit);
        } finally {
            rerouter.clearPatchRestriction();
        }
        boolean departureConsistent = !routed
                || departureWedgeConsistent(arcId, vertices.get(0));
        if (routed && departureConsistent && fanStillReachesTarget(movedVertex, targetVertex)) {
            rememberDirtyPatch(arc.leftPatchId);
            rememberDirtyPatch(arc.rightPatchId);
            return true;
        }
        List<Integer> discardedRoute = routed ? List.copyOf(arc.path.copyVertexPath) : List.of();
        if (routed) {
            tmesh.releaseClaims(arc.path);
            arc.path = releasedPath;
        }
        blockedDragCount++;
        Set<Integer> boundaryArcs = new HashSet<>();
        boolean reachedTarget = floodFreeSpace(vertices.get(0), targetVertex, boundaryArcs);
        int[] freeFaces = new int[freeRegionFaces.size()];
        for (int index = 0; index < freeFaces.length; index++) {
            freeFaces[index] = freeRegionFaces.get(index);
        }
        ArrangementDiagnostic diagnostic = new ArrangementDiagnostic();
        diagnostic.addFaceGroup("free region", freeFaces);
        diagnostic.addFaceGroup("left cover", coverFaces(arc.leftPatchId));
        diagnostic.addFaceGroup("right cover", coverFaces(arc.rightPatchId));
        diagnostic.addPathGroup(GROUP_CHANNEL, List.copyOf(channel));
        diagnostic.addPathGroup("released arc path", releasedPath.copyVertexPath);
        diagnostic.addMarkerGroup(GROUP_MOVED_VERTEX, new int[] { movedVertex });
        diagnostic.addMarkerGroup(GROUP_TARGET_VERTEX, new int[] { targetVertex });
        throw new ArrangementDiagnosticException("arc " + arcId
                + " could not be re-routed onto vertex "
                + targetVertex + " inside patches " + tmesh.topology.resolvePatch(arc.leftPatchId)
                + " and " + tmesh.topology.resolvePatch(arc.rightPatchId) + ", which it separates,"
                + " without fencing in the rest of the fan; moved vertex " + movedVertex + " path "
                + vertices + " channel " + channel
                + coverReport(vertices.get(0)) + coverReport(targetVertex)
                + fanReport(vertices.get(0)) + fanReport(targetVertex)
                + "\n free region from " + vertices.get(0) + " reaches " + freeRegionFaces.size()
                + " faces, target reached " + reachedTarget
                + ", bounded by arcs " + boundaryArcs
                + "\n arrival wedges allowed " + allowedArrivalWedges + ", banned as"
                + " flank-inconsistent " + bannedArrivalWedgeCount
                + "; departure wedges allowed " + allowedDepartureWedges + ", banned "
                + bannedDepartureWedgeCount
                + (departureConsistent ? ""
                        : "; the routed departure wedge contradicts the arc's flanks;"
                                + " discarded route " + discardedRoute),
                diagnostic);
    }

    /**
     * Bans every ring wedge at one route endpoint whose bounding lanes' facing flanks
     * contradict the dragged arc's, so a route cannot use a slot the arrangement cannot
     * absorb. The collapsing arc's lane is exempt: later fan arrivals fill its side.
     *
     * @param arc           arc being dragged
     * @param ringVertex    endpoint vertex whose ring is walked
     * @param departureSide whether the wedges gate the route's departure rather than its
     *                      arrival
     * @return how many wedges stay allowed
     */
    private int banInconsistentWedges(EmbeddedArc arc, int ringVertex, boolean departureSide) {
        HalfEdgeMesh copy = tmesh.topology.copy;
        int spokeCount = copy.vertexEdgeCount(ringVertex);
        int[] spokeHalfEdges = new int[spokeCount];
        int[] spokeOwners = new int[spokeCount];
        int[] gapFaces = new int[spokeCount];
        int halfEdge = copy.edgeHalfEdge(copy.vertexEdgeAt(ringVertex, 0));
        if (copy.halfEdgeVertex(halfEdge) != ringVertex) {
            halfEdge = copy.halfEdgeTwin(halfEdge);
        }
        if (halfEdge < 0) {
            return 1;
        }
        int start = halfEdge;
        int walked = 0;
        do {
            spokeHalfEdges[walked] = halfEdge;
            spokeOwners[walked] = tmesh.topology.ownerArcByCopyEdge[copy.halfEdgeEdge(halfEdge)];
            gapFaces[walked] = copy.halfEdgeFace(halfEdge);
            halfEdge = ringNextSpoke(halfEdge);
            walked++;
        } while (halfEdge != start && halfEdge >= 0 && walked < spokeCount);
        if (halfEdge != start) {
            return 1;
        }
        int claimedCount = 0;
        for (int index = 0; index < walked; index++) {
            claimedCount += spokeOwners[index] == EmbeddedMeshTopology.UNCLAIMED ? 0 : 1;
        }
        if (claimedCount == 0) {
            return 1;
        }
        int arcLeft = tmesh.topology.resolvePatch(arc.leftPatchId);
        int arcRight = tmesh.topology.resolvePatch(arc.rightPatchId);
        int allowed = 0;
        int wedgeStart = EmbeddedTMesh.NONE;
        for (int index = 0; index < walked; index++) {
            if (spokeOwners[index] != EmbeddedMeshTopology.UNCLAIMED && wedgeStart == EmbeddedTMesh.NONE) {
                wedgeStart = index;
            }
        }
        int boundA = wedgeStart;
        for (int step = 0; step < walked; step++) {
            int index = (wedgeStart + step + 1) % walked;
            if (spokeOwners[index] == EmbeddedMeshTopology.UNCLAIMED) {
                continue;
            }
            boolean exemptA = spokeOwners[boundA] == collapsingArcId;
            boolean exemptB = spokeOwners[index] == collapsingArcId;
            int facingA = facingFlank(spokeOwners[boundA], spokeHalfEdges[boundA], true);
            int facingB = facingFlank(spokeOwners[index], spokeHalfEdges[index], false);
            boolean consistent =
                    (exemptA || facingA == arcLeft) && (exemptB || facingB == arcRight)
                    || (exemptA || facingA == arcRight) && (exemptB || facingB == arcLeft);
            if (consistent) {
                allowed++;
            } else {
                if (departureSide) {
                    bannedDepartureWedgeCount++;
                } else {
                    bannedArrivalWedgeCount++;
                }
                int gap = boundA;
                do {
                    if (departureSide) {
                        rerouter.banDepartureFace(gapFaces[gap]);
                    } else {
                        rerouter.banApproachFace(gapFaces[gap]);
                    }
                    gap = (gap + 1) % walked;
                } while (gap != index);
            }
            boundA = index;
        }
        return allowed;
    }

    /**
     * The next outgoing spoke half-edge around a vertex, or negative at a surface border where
     * the pinwheel does not close.
     *
     * @param outgoingHalfEdge spoke half-edge oriented out of the vertex
     * @return the next outgoing spoke, or a negative id when the walk leaves the surface
     */
    private int ringNextSpoke(int outgoingHalfEdge) {
        HalfEdgeMesh copy = tmesh.topology.copy;
        int acrossCorner = copy.halfEdgeNext(outgoingHalfEdge);
        if (acrossCorner < 0) {
            return acrossCorner;
        }
        int intoVertex = copy.halfEdgeNext(acrossCorner);
        if (intoVertex < 0) {
            return intoVertex;
        }
        return copy.halfEdgeTwin(intoVertex);
    }

    /**
     * The resolved patch a claimed ring lane shows to one side of its spoke.
     *
     * @param ownerArcId       arc claiming the spoke
     * @param outgoingHalfEdge spoke half-edge oriented out of the ring vertex
     * @param towardLeftFace   whether the asked side is the half-edge's left face
     * @return the resolved facing patch
     */
    private int facingFlank(int ownerArcId, int outgoingHalfEdge, boolean towardLeftFace) {
        EmbeddedArc lane = tmesh.arcs.get(ownerArcId);
        List<Integer> path = lane.path.copyVertexPath;
        int ringVertex = tmesh.topology.copy.halfEdgeVertex(outgoingHalfEdge);
        int spokeFar = tmesh.topology.copy.halfEdgeEndVertex(outgoingHalfEdge);
        boolean pathLeavesRing = path.get(0) == ringVertex && path.get(1) == spokeFar;
        boolean askLeftOfPath = pathLeavesRing == towardLeftFace;
        return tmesh.topology.resolvePatch(askLeftOfPath ? lane.leftPatchId : lane.rightPatchId);
    }

    /**
     * Whether the routed arc's first hop sits between ring neighbors whose facing flanks agree
     * with its own — the departure-side twin of the arrival pre-ban, checked after the fact.
     *
     * @param arcId       arc that was just routed and claimed
     * @param fixedVertex the arc's unmoved endpoint vertex
     * @return true when the departure wedge is flank-consistent or has no claimed neighbors
     */
    private boolean departureWedgeConsistent(int arcId, int fixedVertex) {
        HalfEdgeMesh copy = tmesh.topology.copy;
        int spokeCount = copy.vertexEdgeCount(fixedVertex);
        int halfEdge = copy.edgeHalfEdge(copy.vertexEdgeAt(fixedVertex, 0));
        if (copy.halfEdgeVertex(halfEdge) != fixedVertex) {
            halfEdge = copy.halfEdgeTwin(halfEdge);
        }
        if (halfEdge < 0) {
            return true;
        }
        int start = halfEdge;
        int ownSpoke = EmbeddedMeshTopology.UNCLAIMED;
        int walked = 0;
        int[] spokeHalfEdges = new int[spokeCount];
        int[] spokeOwners = new int[spokeCount];
        do {
            spokeHalfEdges[walked] = halfEdge;
            spokeOwners[walked] = tmesh.topology.ownerArcByCopyEdge[copy.halfEdgeEdge(halfEdge)];
            if (spokeOwners[walked] == arcId) {
                ownSpoke = walked;
            }
            halfEdge = ringNextSpoke(halfEdge);
            walked++;
        } while (halfEdge != start && halfEdge >= 0 && walked < spokeCount);
        if (halfEdge != start || ownSpoke == EmbeddedMeshTopology.UNCLAIMED) {
            return true;
        }
        int after = EmbeddedTMesh.NONE;
        for (int step = 1; step < walked && after == EmbeddedTMesh.NONE; step++) {
            int index = (ownSpoke + step) % walked;
            after = spokeOwners[index] == EmbeddedMeshTopology.UNCLAIMED ? EmbeddedTMesh.NONE : index;
        }
        int before = EmbeddedTMesh.NONE;
        for (int step = 1; step < walked && before == EmbeddedTMesh.NONE; step++) {
            int index = (ownSpoke - step + walked) % walked;
            before = spokeOwners[index] == EmbeddedMeshTopology.UNCLAIMED ? EmbeddedTMesh.NONE : index;
        }
        boolean afterConsistent = after == EmbeddedTMesh.NONE
                || spokeOwners[after] == collapsingArcId
                || facingFlank(spokeOwners[after], spokeHalfEdges[after], false)
                        == facingFlank(arcId, spokeHalfEdges[ownSpoke], true);
        boolean beforeConsistent = before == EmbeddedTMesh.NONE
                || spokeOwners[before] == collapsingArcId
                || facingFlank(spokeOwners[before], spokeHalfEdges[before], true)
                        == facingFlank(arcId, spokeHalfEdges[ownSpoke], false);
        return afterConsistent && beforeConsistent;
    }

    /**
     * A flanking patch's cover flood as a plain face-id array for a diagnostic
     * group, or empty when the patch is retired or has nothing to flood from.
     *
     * @param patchId flanking patch of the blocked arc, or
     *                {@link EmbeddedTMesh#NONE}
     * @return the copy face ids its cover holds
     */
    private int[] coverFaces(int patchId) {
        int resolved = tmesh.topology.resolvePatch(patchId);
        if (resolved == EmbeddedTMesh.NONE || !tmesh.patches.get(resolved).alive
                || !tmesh.splitPatch.corridor.hasSeedableBoundary(resolved)) {
            return new int[0];
        }
        IntIdList faces = tmesh.splitPatch.corridor.patchFaces(resolved);
        int[] faceIds = new int[faces.size()];
        for (int index = 0; index < faceIds.length; index++) {
            faceIds[index] = faces.get(index);
        }
        return faceIds;
    }

    /**
     * Whether the arcs still waiting on the moving node can follow it: each has to
     * reach the surviving node through routable space. A node left carrying only
     * the collapsing arc has no one to fence in.
     *
     * @param movedVertex  the moving node's copy vertex
     * @param targetVertex the vertex it is being merged onto
     * @return true when nothing is fenced in
     */
    private boolean fanStillReachesTarget(int movedVertex, int targetVertex) {
        int movedNodeId = tmesh.topology.ownerNodeByCopyVertex[movedVertex];
        if (movedVertex == targetVertex || movedNodeId == EmbeddedMeshTopology.UNCLAIMED
                || tmesh.arcEndsByNode.get(movedNodeId).size() <= 1) {
            return true;
        }
        return reachesThroughFreeSpace(movedVertex, targetVertex);
    }

    /**
     * Re-routes the whole arc from its fixed node to the moving node's new vertex,
     * claiming the route on success.
     *
     * @param arcId                  arc whose end is being dragged
     * @param vertices               the arc's old path, normalized to end at the
     *                               moved vertex
     * @param reversed               whether the stored path was reversed for
     *                               normalization
     * @param movedVertex            the moving node's old copy vertex
     * @param targetVertex           the moving node's new copy vertex
     * @param allowMovedVertexTransit whether a second pass may route through the
     *                               moved vertex, reserved for the fan's last drag
     * @return whether the search routed and claimed the dragged path
     */
    private boolean routeDraggedTail(int arcId, List<Integer> vertices, boolean reversed,
            int movedVertex, int targetVertex, boolean allowMovedVertexTransit) {
        EmbeddedArc arc = tmesh.arcs.get(arcId);
        int[] passThroughChoices = allowMovedVertexTransit
                ? new int[] { EmbeddedMeshTopology.UNCLAIMED, movedVertex }
                : new int[] { EmbeddedMeshTopology.UNCLAIMED };
        for (int passThrough : passThroughChoices) {
            rerouter.clearFailureMemory();
            List<Integer> prefix = new ArrayList<>(vertices.subList(0, 1));
            List<Integer> prefixEdges = new ArrayList<>();
            if (!rerouter.tryLegEdges(prefix, prefixEdges)) {
                continue;
            }
            ArcEdgePath prefixPath = new ArcEdgePath(arcId, prefix, prefixEdges);
            tmesh.topology.claimPath(arcId, prefixPath);
            List<Integer> attempt = new ArrayList<>(prefix);
            ActiveIdSet corridor = rerouter.freshCorridor();
            if (rerouter.tryRoute(arcId, attempt, vertices.get(0), targetVertex, corridor,
                    passThrough)) {
                List<Integer> edges = new ArrayList<>(prefixEdges);
                rerouter.rebuildLegEdges(attempt, edges);
                if (reversed) {
                    Collections.reverse(attempt);
                    Collections.reverse(edges);
                }
                arc.path = new ArcEdgePath(arcId, attempt, edges);
                tmesh.topology.claimPath(arcId, arc.path);
                return true;
            }
            tmesh.releaseClaims(prefixPath);
        }
        return false;
    }

    /**
     * Neighborhood report of a blocked drag endpoint: every incident edge with the
     * arc holding it and what stands at its far end, which is what decides whether
     * a route can pass.
     *
     * @param vertexId blocked endpoint
     * @return a multi-line diagnostic block
     */
    private String fanReport(int vertexId) {
        EmbeddedMeshTopology topology = tmesh.topology;
        StringBuilder detail = new StringBuilder("\n vertex ").append(vertexId)
                .append(" ownerNode ").append(topology.ownerNodeByCopyVertex[vertexId])
                .append(" ownerArc ").append(topology.ownerArcByCopyVertex[vertexId]);
        for (int index = 0; index < topology.copy.vertexEdgeCount(vertexId); index++) {
            int edgeId = topology.copy.vertexEdgeAt(vertexId, index);
            int farVertex = topology.otherEndpoint(edgeId, vertexId);
            detail.append("\n  edge ").append(edgeId)
                    .append(" arc ").append(topology.ownerArcByCopyEdge[edgeId])
                    .append(" -> vertex ").append(farVertex)
                    .append(" (n").append(topology.ownerNodeByCopyVertex[farVertex])
                    .append(",a").append(topology.ownerArcByCopyVertex[farVertex])
                    .append(')');
        }
        return detail.toString();
    }

    /**
     * The patches covering the faces around a blocked drag's endpoint, which is
     * what the restriction reads to decide where the search may walk.
     *
     * @param vertexId endpoint to report
     * @return a one-line diagnostic block
     */
    private String coverReport(int vertexId) {
        EmbeddedMeshTopology topology = tmesh.topology;
        Set<Integer> covers = new HashSet<>();
        for (int index = 0; index < topology.copy.vertexFaceCount(vertexId); index++) {
            covers.add(topology.resolvePatch(
                    topology.patchLabelOf(topology.copy.vertexFaceAt(vertexId, index))));
        }
        return "\n cover around " + vertexId + ": patches " + covers;
    }

    /**
     * Whether a route could still run between two vertices at all, ignoring how
     * short or how split it would be.
     *
     * @param startVertex  vertex to leave from
     * @param targetVertex vertex to reach
     * @return true when routable space connects them
     */
    private boolean reachesThroughFreeSpace(int startVertex, int targetVertex) {
        return floodFreeSpace(startVertex, targetVertex, null);
    }

    /**
     * Floods the faces a route could still reach into {@link #freeRegionFaces},
     * crossing only edges no arc holds.
     *
     * @param startVertex  vertex to flood from
     * @param targetVertex vertex to look for
     * @param boundaryArcs receives the arcs the flood stopped at, or null to stop at
     *                     first target contact, leaving {@link #freeRegionFaces} partial
     * @return whether the flood reached a face on the target
     */
    private boolean floodFreeSpace(int startVertex, int targetVertex, Set<Integer> boundaryArcs) {
        EmbeddedMeshTopology topology = tmesh.topology;
        HalfEdgeMesh copy = topology.copy;
        int faceIdBound = topology.sourceFaceByCopyFace.length;
        if (freeRegionStampByCopyFace.length < faceIdBound) {
            freeRegionStampByCopyFace = Arrays.copyOf(freeRegionStampByCopyFace,
                    Math.max(faceIdBound, freeRegionStampByCopyFace.length * 2));
        }
        freeRegionStamp++;
        freeRegionFaces.clear();
        boolean stopAtTarget = boundaryArcs == null;
        for (int index = 0; index < copy.vertexFaceCount(startVertex); index++) {
            int faceId = copy.vertexFaceAt(startVertex, index);
            if (freeRegionStampByCopyFace[faceId] != freeRegionStamp) {
                freeRegionStampByCopyFace[faceId] = freeRegionStamp;
                freeRegionFaces.add(faceId);
                if (stopAtTarget && faceTouchesVertex(faceId, targetVertex)) {
                    return true;
                }
            }
        }
        for (int cursor = 0; cursor < freeRegionFaces.size(); cursor++) {
            int faceId = freeRegionFaces.get(cursor);
            for (int corner = 0; corner < copy.faceHalfEdgeCount(faceId); corner++) {
                int edgeId = copy.faceEdgeAt(faceId, corner);
                if (topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                    if (boundaryArcs != null) {
                        boundaryArcs.add(topology.ownerArcByCopyEdge[edgeId]);
                    }
                    continue;
                }
                int halfEdge = copy.edgeHalfEdge(edgeId);
                int neighbour = copy.halfEdgeFace(halfEdge) == faceId
                        ? copy.halfEdgeFace(copy.halfEdgeTwin(halfEdge))
                        : copy.halfEdgeFace(halfEdge);
                if (neighbour >= 0 && freeRegionStampByCopyFace[neighbour] != freeRegionStamp) {
                    freeRegionStampByCopyFace[neighbour] = freeRegionStamp;
                    freeRegionFaces.add(neighbour);
                    if (stopAtTarget && faceTouchesVertex(neighbour, targetVertex)) {
                        return true;
                    }
                }
            }
        }
        for (int index = 0; index < copy.vertexFaceCount(targetVertex); index++) {
            int faceId = copy.vertexFaceAt(targetVertex, index);
            if (faceId >= 0 && faceId < freeRegionStampByCopyFace.length
                    && freeRegionStampByCopyFace[faceId] == freeRegionStamp) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether one face has a vertex, read off the copy's face adjacency.
     *
     * @param faceId   face to test
     * @param vertexId vertex looked for
     * @return true when the face touches the vertex
     */
    private boolean faceTouchesVertex(int faceId, int vertexId) {
        HalfEdgeMesh copy = tmesh.topology.copy;
        for (int index = 0; index < copy.faceVertexCount(faceId); index++) {
            if (copy.faceVertexAt(faceId, index) == vertexId) {
                return true;
            }
        }
        return false;
    }

    /**
     * The pivot's incident arcs in cyclic fan order, starting from the spoke
     * adjacent to the collapsing arc's channel, so a dragged arc cannot fence in a
     * later one.
     *
     * <p>
     * Rotates the copy mesh's half-edges around the pivot rather than reading
     * {@code arcEndsByNode}; arcs the rotation misses are appended.
     *
     * @param pivotVertex     the collapsing node's copy vertex
     * @param channelNeighbor the channel vertex adjacent to the pivot, whose spoke
     *                        starts the fan
     * @param collapsingArcId the arc being collapsed, excluded from the fan
     * @param movedNodeId     the collapsing node id, for its full incident-arc set
     * @return the incident arcs (excluding the collapsing arc) in fan order
     */
    private List<Integer> incidentArcsInFanOrder(int pivotVertex, int channelNeighbor,
            int collapsingArcId, int movedNodeId) {
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
        List<Integer> ordered = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        int halfEdge = startHalfEdge;
        for (int step = 0; step < rotationCap && halfEdge >= 0; step++) {
            int owner = tmesh.topology.ownerArcByCopyEdge[copy.halfEdgeEdge(halfEdge)];
            if (owner != EmbeddedMeshTopology.UNCLAIMED && owner != collapsingArcId
                    && tmesh.arcs.get(owner).alive && seen.add(owner)) {
                ordered.add(owner);
            }
            halfEdge = copy.halfEdgeTwin(copy.halfEdgePrev(halfEdge));
            if (halfEdge == startHalfEdge) {
                break;
            }
        }
        for (int incidentArcId : tmesh.arcEndsByNode.get(movedNodeId)) {
            if (incidentArcId != collapsingArcId && tmesh.arcs.get(incidentArcId).alive
                    && seen.add(incidentArcId)) {
                ordered.add(incidentArcId);
            }
        }
        return ordered;
    }

    /**
     * The endpoint of a zero arc that LCBK19 Def 6.2 permits to move, or
     * {@link EmbeddedTMesh#NONE} when neither may.
     *
     * <p>
     * A loop is always collapsible: both ends already sit on one point, so nothing
     * moves. {@link #isCollapsibleFrom} would interrogate that node twice and
     * refuse every loop on a critical one.
     *
     * @param arc zero arc to test
     * @return the movable node's id, preferring the one with fewer incident arcs
     *         and the lower id, the single node when the arc is a loop, or
     *         {@link EmbeddedTMesh#NONE} when both endpoints are fixed
     */
    private int movingEndpoint(EmbeddedArc arc) {
        if (arc.isLoop()) {
            return arc.startNodeId;
        }
        boolean startMovable = isCollapsibleFrom(arc, arc.startNodeId);
        boolean endMovable = isCollapsibleFrom(arc, arc.endNodeId);
        if (!startMovable && !endMovable) {
            return EmbeddedTMesh.NONE;
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
