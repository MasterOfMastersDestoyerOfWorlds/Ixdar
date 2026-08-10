package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.embedding.records.ArcEdgePath;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedNode;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.gridmap.PatchRegions;

/**
 * Rebuilds a contracted live T-mesh on a clean copy of the original surface
 * mesh, keeping only where its arcs cross the faces of that mesh once the
 * layout's nodes are inserted.
 *
 * <p>
 * See also: LCBK19 Section 6.1
 */
public final class EmbeddedTMeshRecarve {

    /** Nanoseconds per second, for the timing log. */
    private static final double NANOS_PER_SECOND = 1.0e9;

    public final EmbeddedTMesh source;
    public final HalfEdgeMesh originalMesh;

    /** Clean working copy of the original mesh the layout is rebuilt on. */
    public EmbeddedMeshTopology fresh;

    /** The carve that lays the arcs down, reused from the initial embedding. */
    public SnappingCarve snapping;

    /** The re-carved T-mesh over the clean copy. */
    public EmbeddedTMesh freshTmesh;

    /**
     * Dense node id per old node id, or {@link EmbeddedTMesh#NONE} for a retired
     * node.
     */
    public int[] denseNodeIdByOldId;

    /** Old node id per dense node id. */
    public int[] oldNodeIdByDenseId;

    /**
     * Dense arc id per old arc id, or {@link EmbeddedTMesh#NONE} for a retired arc.
     */
    public int[] denseArcIdByOldId;

    /** Old arc id per dense arc id. */
    public int[] oldArcIdByDenseId;

    /**
     * Layout nodes the original mesh already had a vertex for, which cost nothing.
     */
    public int reusedNodeVertexCount;

    /** Layout nodes that had to be minted on the clean copy. */
    public int placedNodeVertexCount;

    /**
     * Contracted path steps read, before the faces they cross reduce them to
     * crossings.
     */
    public int replayedStepCount;

    /**
     * End crossings dropped for sitting on an edge the route's own node already
     * sits on.
     */
    public int trimmedEndCrossingCount;

    /**
     * Interior path vertices a live node other than the arc's own endpoints holds.
     * Every one is a contracted arc touching a foreign node, which LCBK19 Section
     * 6.1 forbids.
     */
    public int foreignNodeOnPathCount;


    /**
     * Crossings removed as halves of a same-edge dip by {@link #pullStripsTaut}.
     */
    public int dipCrossingsRemovedCount;

    /**
     * Crossings slid into an adjacent through-vertex by {@link #pullStripsTaut}.
     */
    public int fanSlideCrossingsRemovedCount;

    /**
     * End crossings trimmed by {@link #pullStripsTaut} for touching their own
     * node's vertex, mostly exposed by an earlier removal.
     */
    public int endCrossingsTrimmedCount;

    /** The first foreign-node touch found, described for the report. */
    public String firstForeignNodeOnPath;

    /**
     * Stores the contracted T-mesh and the original mesh to rebuild onto.
     *
     * @param source       contracted live T-mesh whose arrangement is re-carved
     * @param originalMesh source triangle mesh the working copy is rebuilt from
     */
    public EmbeddedTMeshRecarve(EmbeddedTMesh source, HalfEdgeMesh originalMesh) {
        this.source = source;
        this.originalMesh = originalMesh;
    }

    /**
     * Re-carves the live arrangement onto a clean copy and validates the result.
     *
     * @throws IllegalStateException when the rebuild is not the same arrangement
     * @return the rebuilt T-mesh
     */
    public EmbeddedTMesh build() {
        long startNanos = System.nanoTime();
        if (source.topology.sourceMesh != originalMesh) {
            throw new IllegalStateException("the contracted T-mesh was carved from a different"
                    + " mesh than the one being re-carved onto, so their vertex numbering does"
                    + " not correspond");
        }
        int oldVertices = source.topology.copy.vertexCount();
        int oldFaces = source.topology.copy.faceCount();
        numberLiveElements();

        fresh = new EmbeddedMeshTopology(originalMesh);
        snapping = new SnappingCarve(fresh);
        tagSourceEdges();
        placeLiveNodes();
        refineLiveArcs();
        pullStripsTaut();
        snapping.carve();

        fresh.copy.computeNormals();
        freshTmesh = new EmbeddedTMesh(fresh);
        addLiveNodes();
        addLiveArcs();
        addLivePatches();
        freshTmesh.resolveWalkOrientation();
        freshTmesh.validate();

        if (foreignNodeOnPathCount > 0) {
            System.out.println("[recarve] " + foreignNodeOnPathCount + " contracted path vertices"
                    + " are held by a foreign node; first: " + firstForeignNodeOnPath);
        }
        snapping.report();
        requireNoClaimConflict();
        requirePartition();
        System.out.printf("[recarve] nodes=%d arcs=%d patches=%d | copy V=%d->%d F=%d->%d"
                + " | nodes reused=%d placed=%d, path steps read=%d"
                + " | taut dipRemoved=%d fanSlid=%d endTrimmed=%d | %.2fs%n",
                oldNodeIdByDenseId.length, oldArcIdByDenseId.length, freshTmesh.patches.size(),
                oldVertices, fresh.copy.vertexCount(), oldFaces, fresh.copy.faceCount(),
                reusedNodeVertexCount, placedNodeVertexCount, replayedStepCount,
                dipCrossingsRemovedCount, fanSlideCrossingsRemovedCount,
                endCrossingsTrimmedCount,
                (System.nanoTime() - startNanos) / NANOS_PER_SECOND);
        return freshTmesh;
    }

    /**
     * Assigns dense ids to the live nodes and arcs, keeping their relative order.
     */
    private void numberLiveElements() {
        denseNodeIdByOldId = new int[source.nodes.size()];
        denseArcIdByOldId = new int[source.arcs.size()];
        List<Integer> liveNodes = new ArrayList<>();
        List<Integer> liveArcs = new ArrayList<>();
        for (EmbeddedNode node : source.nodes) {
            denseNodeIdByOldId[node.nodeId] = EmbeddedTMesh.NONE;
            if (node.alive) {
                denseNodeIdByOldId[node.nodeId] = liveNodes.size();
                liveNodes.add(node.nodeId);
            }
        }
        for (EmbeddedArc arc : source.arcs) {
            denseArcIdByOldId[arc.arcId] = EmbeddedTMesh.NONE;
            if (arc.alive) {
                denseArcIdByOldId[arc.arcId] = liveArcs.size();
                liveArcs.add(arc.arcId);
            }
        }
        oldNodeIdByDenseId = toIntArray(liveNodes);
        oldArcIdByDenseId = toIntArray(liveArcs);
    }

    /**
     * Tags every edge of the clean copy with the source edge it is, so a chord that
     * would cross one is refused rather than laid. Runs before any split; split
     * children inherit the tag.
     */
    private void tagSourceEdges() {
        for (int activeEdge = 0; activeEdge < originalMesh.edgeCount(); activeEdge++) {
            int halfEdge = originalMesh.edgeHalfEdge(originalMesh.edgeIdAt(activeEdge));
            int copyEdge = fresh.edgeBetween(
                    fresh.copyVertexForSourceVertexId(originalMesh.halfEdgeVertex(halfEdge)),
                    fresh.copyVertexForSourceVertexId(originalMesh.halfEdgeEndVertex(halfEdge)));
            if (copyEdge != EmbeddedMeshTopology.UNCLAIMED) {
                fresh.sourceEdgeByCopyEdge[copyEdge] = activeEdge;
            }
        }
    }

    /**
     * Gives every surviving node a vertex of the clean copy, reusing an original
     * vertex where the node already sits on one. Afterwards no face of the copy
     * holds a vertex inside it, so a chord across a face cannot pass anything.
     *
     * @throws IllegalStateException when two nodes land on one vertex
     */
    private void placeLiveNodes() {
        FaceChordWalk placer = new FaceChordWalk(fresh);
        snapping.nodeCount = oldNodeIdByDenseId.length;
        snapping.vertexIdByNode = new int[oldNodeIdByDenseId.length];
        Arrays.fill(snapping.vertexIdByNode, EmbeddedMeshTopology.UNCLAIMED);
        for (int denseNodeId = 0; denseNodeId < oldNodeIdByDenseId.length; denseNodeId++) {
            EmbeddedNode node = source.nodes.get(oldNodeIdByDenseId[denseNodeId]);
            int copyVertex;
            if (node.copyVertex < source.topology.originalVertexBound) {
                copyVertex = fresh.copyVertexForSourceVertexId(
                        originalMesh.vertexIdAt(node.copyVertex));
                reusedNodeVertexCount++;
            } else {
                int sourceFace = nodeSourceFace(node);
                copyVertex = placer.placeVertex(sourceFace,
                        source.topology.barycentricOf(sourceFace, node.copyVertex).clone());
                placedNodeVertexCount++;
            }
            int owner = fresh.ownerNodeByCopyVertex[copyVertex];
            if (owner != EmbeddedMeshTopology.UNCLAIMED && owner != denseNodeId) {
                throw new IllegalStateException("re-carved nodes " + owner + " and " + denseNodeId
                        + " both landed on copy vertex " + copyVertex
                        + "; one mesh vertex never owns two T-mesh nodes");
            }
            fresh.ownerNodeByCopyVertex[copyVertex] = denseNodeId;
            snapping.vertexIdByNode[denseNodeId] = copyVertex;
        }
        snapping.constraintVertexCount = fresh.copy.vertexCount();
        snapping.constraintFaceCount = fresh.copy.faceCount();
        snapping.nodesOnVertexCount = reusedNodeVertexCount;
    }

    /**
     * Refines every surviving arc's contracted path onto the node-inserted copy,
     * one step at a time. Only the faces the path crosses survive: a bend inside
     * one face records no crossing, so the arc becomes a straight run per face it
     * passes through.
     */
    private void refineLiveArcs() {
        snapping.stripByArc = new ArrayList<>(oldArcIdByDenseId.length);
        snapping.startNodeByArc = new int[oldArcIdByDenseId.length];
        snapping.endNodeByArc = new int[oldArcIdByDenseId.length];
        snapping.passageCountBySourceFace = new int[originalMesh.faceCount()];
        for (int denseArcId = 0; denseArcId < oldArcIdByDenseId.length; denseArcId++) {
            EmbeddedArc arc = source.arcs.get(oldArcIdByDenseId[denseArcId]);
            List<Integer> path = arc.path.copyVertexPath;
            requireCurve(arc.arcId, path);
            snapping.startNodeByArc[denseArcId] = denseNodeIdByOldId[arc.startNodeId];
            snapping.endNodeByArc[denseArcId] = denseNodeIdByOldId[arc.endNodeId];
            FaceStripPath strip = new FaceStripPath(fresh, denseArcId);
            snapping.stripByArc.add(strip);
            for (int step = 1; step < path.size(); step++) {
                int sourceFace = stepSourceFace(arc.arcId, path, step);
                strip.addPassage(sourceFace,
                        source.topology.barycentricOf(sourceFace, path.get(step - 1)),
                        source.topology.barycentricOf(sourceFace, path.get(step)));
                replayedStepCount++;
                if (step < path.size() - 1) {
                    auditForeignNode(arc, path.get(step));
                }
            }
            trimEndCrossings(strip, denseArcId);
            for (int sourceFace : strip.passageSourceFaces) {
                snapping.passageCountBySourceFace[sourceFace]++;
            }
        }
        for (int count : snapping.passageCountBySourceFace) {
            snapping.contestedFaceCount += count > 1 ? 1 : 0;
            snapping.mostPassagesOnAFace = Math.max(snapping.mostPassagesOnAFace, count);
        }
    }

    /**
     * Drops the crossings at either end of a route that sit on an edge its own node
     * sits on: the arc runs along that edge into the node, so a lane there would
     * collide.
     *
     * @param strip      the route to trim
     * @param denseArcId arc the route belongs to, for its endpoint vertices
     */
    private void trimEndCrossings(FaceStripPath strip, int denseArcId) {
        int startVertex = snapping.vertexIdByNode[snapping.startNodeByArc[denseArcId]];
        int endVertex = snapping.vertexIdByNode[snapping.endNodeByArc[denseArcId]];
        while (!strip.crossedEdges.isEmpty()
                && strip.crossingTouches(strip.crossedEdges.size() - 1, endVertex)) {
            strip.removeLastCrossing();
            trimmedEndCrossingCount++;
        }
        while (!strip.crossedEdges.isEmpty() && strip.crossingTouches(0, startVertex)) {
            strip.removeFirstCrossing();
            trimmedEndCrossingCount++;
        }
    }

    /**
     * Adds every surviving node onto the vertex the carve placed it on.
     */
    private void addLiveNodes() {
        for (int denseNodeId = 0; denseNodeId < oldNodeIdByDenseId.length; denseNodeId++) {
            EmbeddedNode node = source.nodes.get(oldNodeIdByDenseId[denseNodeId]);
            requireStableId("node", denseNodeId, freshTmesh.addNode(node.sourceNodeId,
                    snapping.vertexIdByNode[denseNodeId], node.critical, node.border));
        }
    }

    /**
     * Adds every surviving arc along the path the carve laid for it.
     *
     * @throws IllegalStateException when an arc was never laid or its id shifts
     */
    private void addLiveArcs() {
        for (int denseArcId = 0; denseArcId < oldArcIdByDenseId.length; denseArcId++) {
            EmbeddedArc arc = source.arcs.get(oldArcIdByDenseId[denseArcId]);
            ArcEdgePath path = snapping.pathByArc[denseArcId];
            if (path == null) {
                throw new IllegalStateException("re-carved arc " + denseArcId + " (old "
                        + arc.arcId + ") was never laid down; its path crossed no face");
            }
            requireStableId("arc", denseArcId, freshTmesh.addArc(arc.sourceArcId,
                    snapping.startNodeByArc[denseArcId], snapping.endNodeByArc[denseArcId],
                    arc.quantizedLength, arc.feature, path.copyVertexPath));
        }
    }

    /**
     * Rebuilds every surviving patch's side wiring against the dense ids.
     */
    private void addLivePatches() {
        for (EmbeddedPatch patch : source.patches) {
            if (!patch.alive) {
                continue;
            }
            List<List<Integer>> sideArcIds = new ArrayList<>(EmbeddedPatch.SIDES);
            for (List<Integer> side : patch.sideArcIds) {
                List<Integer> remapped = new ArrayList<>(side.size());
                for (int oldArcId : side) {
                    remapped.add(denseArcIdByOldId[oldArcId]);
                }
                sideArcIds.add(remapped);
            }
            freshTmesh.addPatch(patch.sourcePatchId, sideArcIds,
                    denseNodeIdByOldId[patch.cornerNodeId(0)]);
        }
    }

    /**
     * The source face a path step runs in.
     *
     * @param oldArcId arc the path belongs to, for the message
     * @param path     the arc's contracted copy-vertex path
     * @param step     index of the step's far end
     * @throws IllegalStateException when no source face holds the step
     * @return that source face
     */
    private int stepSourceFace(int oldArcId, List<Integer> path, int step) {
        int sourceFace = source.topology.sharedSourceFace(path.get(step - 1), path.get(step));
        if (sourceFace == EmbeddedMeshTopology.UNCLAIMED) {
            throw new IllegalStateException("arc " + oldArcId + " steps from copy vertex "
                    + path.get(step - 1) + " to " + path.get(step) + " with no source face"
                    + " holding both, so that step is not a chord of any source triangle");
        }
        return sourceFace;
    }

    /**
     * The source face a node can be measured in, read from a step of one of its
     * arcs.
     *
     * @param node surviving node to locate
     * @throws IllegalStateException when no live arc reaches it
     * @return that source face
     */
    private int nodeSourceFace(EmbeddedNode node) {
        for (int oldArcId : source.arcEndsByNode.get(node.nodeId)) {
            EmbeddedArc arc = source.arcs.get(oldArcId);
            if (!arc.alive) {
                continue;
            }
            List<Integer> path = arc.path.copyVertexPath;
            requireCurve(oldArcId, path);
            return stepSourceFace(oldArcId, path,
                    path.get(0) == node.copyVertex ? 1 : path.size() - 1);
        }
        throw new IllegalStateException("live node " + node.nodeId + " has no live arc, so there"
                + " is no source face to measure its position in");
    }

    /**
     * Checks a surviving arc is embedded as a curve rather than a point.
     *
     * @param oldArcId arc being read
     * @param path     its contracted copy-vertex path
     * @throws IllegalStateException when the path is a single point
     */
    private void requireCurve(int oldArcId, List<Integer> path) {
        if (path.size() < 2) {
            throw new IllegalStateException("live arc " + oldArcId + " is embedded as the single"
                    + " point " + path + "; the contraction should have retired it");
        }
    }

    /**
     * Records an arc whose contracted path runs through a live node that is not one
     * of its own endpoints — a touch LCBK19 Section 6.1 forbids.
     *
     * @param arc       arc whose path is being read
     * @param oldVertex interior path vertex to test
     */
    private void auditForeignNode(EmbeddedArc arc, int oldVertex) {
        int nodeId = source.topology.ownerNodeByCopyVertex[oldVertex];
        if (nodeId == EmbeddedMeshTopology.UNCLAIMED || !source.nodes.get(nodeId).alive
                || nodeId == arc.startNodeId || nodeId == arc.endNodeId) {
            return;
        }
        foreignNodeOnPathCount++;
        if (firstForeignNodeOnPath == null) {
            firstForeignNodeOnPath = "arc " + arc.arcId + " (nodes " + arc.startNodeId + ".."
                    + arc.endNodeId + ", quantized " + arc.quantizedLength
                    + ") runs through copy vertex " + oldVertex + " held by node " + nodeId;
        }
    }

    /**
     * Checks no mesh element ended up claimed by two arcs, which
     * {@code claimEdgeBetween} counts rather than throws on.
     *
     * @throws IllegalStateException when any element is doubly claimed
     */
    private void requireNoClaimConflict() {
        if (fresh.claimConflictCount != 0) {
            throw new IllegalStateException(fresh.claimConflictCount + " re-carved copy elements"
                    + " are claimed by two T-mesh elements at once, so two arcs were laid over"
                    + " one another; first: " + fresh.firstClaimConflict
                    + describeArcEnds(fresh.firstClaimConflictHolder)
                    + describeArcEnds(fresh.firstClaimConflictClaimant));
        }
    }

    /**
     * Names an arc's endpoint nodes and the vertices they sit on, so a conflict
     * between two arcs shows at once whether they share an end.
     *
     * @param denseArcId re-carved arc id, or {@link EmbeddedMeshTopology#UNCLAIMED}
     * @return the description, or an empty string when there is no such arc
     */
    private String describeArcEnds(int denseArcId) {
        if (denseArcId == EmbeddedMeshTopology.UNCLAIMED) {
            return "";
        }
        int startNode = snapping.startNodeByArc[denseArcId];
        int endNode = snapping.endNodeByArc[denseArcId];
        FaceStripPath strip = snapping.stripByArc.get(denseArcId);
        StringBuilder detail = new StringBuilder("\n  arc ").append(denseArcId)
                .append(" runs from node ").append(startNode).append(" (vertex ")
                .append(snapping.vertexIdByNode[startNode]).append(") to node ").append(endNode)
                .append(" (vertex ").append(snapping.vertexIdByNode[endNode])
                .append("), chosen ").append(snapping.chosenVertexByArc.get(denseArcId))
                .append("\n    path ").append(snapping.pathByArc[denseArcId] == null ? "none"
                        : snapping.pathByArc[denseArcId].copyVertexPath);
        for (int crossing = 0; crossing < strip.crossedEdges.size(); crossing++) {
            int[] edge = strip.crossedEdges.get(crossing);
            detail.append("\n    crossing ").append(crossing).append(" in source face ")
                    .append(strip.passageSourceFaces.get(crossing)).append(edge == null
                            ? " through vertex " + strip.crossedVertices.get(crossing)
                            : " on edge " + edge[0] + ".." + edge[1] + " at "
                                    + strip.crossingParameters.get(crossing));
        }
        return detail.toString();
    }

    /**
     * Checks the re-carved arcs cut the clean copy into exactly the patches the
     * contracted layout has.
     *
     * @throws IllegalStateException when any region matches no live patch
     */
    private void requirePartition() {
        Set<Integer> boundaryArcs = new HashSet<>();
        List<Integer> unmatched = new PatchRegions(freshTmesh)
                .findFirstUnmatchedRegion(boundaryArcs);
        if (!unmatched.isEmpty()) {
            StringBuilder detail = new StringBuilder("the re-carved arrangement leaves a region of "
                    + unmatched.size() + " faces " + unmatched + " bounded by arcs " + boundaryArcs
                    + " that matches no live patch");
            for (int denseArcId : boundaryArcs) {
                detail.append(describeArcEnds(denseArcId));
            }
            throw new IllegalStateException(detail.toString());
        }
        new PatchRegions(freshTmesh).build();
    }

    /**
     * Checks a re-carved element kept the dense id everything else was built
     * against.
     *
     * @param kind     element kind, for the message
     * @param expected dense id it was numbered with
     * @param added    id the fresh T-mesh handed back
     * @throws IllegalStateException when the two differ
     */
    private void requireStableId(String kind, int expected, int added) {
        if (added != expected) {
            throw new IllegalStateException("re-carved " + kind + " id changed from " + expected
                    + " to " + added + " while being added");
        }
    }

    /**
     * Copies a list of integers into a primitive array.
     *
     * @param values integer list
     * @return an {@code int[]} with the same values in order
     */
    private static int[] toIntArray(List<Integer> values) {
        int[] array = new int[values.size()];
        for (int index = 0; index < values.size(); index++) {
            array[index] = values.get(index);
        }
        return array;
    }

    /**
     * Pulls every strip taut before the carve, removing dips, fan winds and exposed
     * end crossings whose swept span is clear. Replayed contracted paths wiggle,
     * traced routes never do.
     *
     * @throws IllegalStateException when a dip or fan-wind pair survives because
     *                               another crossing blocks its span, which no
     *                               chord can lay
     */
    public void pullStripsTaut() {
        boolean removed = true;
        List<FaceStripPath> stripByArc = snapping.stripByArc;
        while (removed) {
            Map<Long, List<int[]>> view = tracedCrossingsByEdge();
            removed = false;
            for (FaceStripPath strip : stripByArc) {
                if (!strip.crossedEdges.isEmpty()) {
                    if (strip.crossingTouches(0, snapping.vertexIdByNode[snapping.startNodeByArc[strip.arcId]])) {
                        strip.removeFirstCrossing();
                        endCrossingsTrimmedCount++;
                        removed = true;
                        break;
                    }
                    if (strip.crossingTouches(strip.crossedEdges.size() - 1,
                            snapping.vertexIdByNode[snapping.endNodeByArc[strip.arcId]])) {
                        strip.removeLastCrossing();
                        endCrossingsTrimmedCount++;
                        removed = true;
                        break;
                    }
                }
                for (int crossing = 0; crossing + 1 < strip.crossedEdges.size(); crossing++) {
                    double[] span = slackSpanAt(strip, crossing);

                    int[] edge = strip.crossedEdges.get(crossing);
                    edge = edge != null ? edge : strip.crossedEdges.get(crossing + 1);
                    if (span == null || blockingCrossingInside(
                            view.get(SnappingCarve.edgeKey(edge)),
                            span[0], span[1]) != null) {
                        continue;
                    }
                    if (strip.crossedEdges.get(crossing) == null) {
                        strip.removeCrossingWithPassageBefore(crossing + 1);
                        fanSlideCrossingsRemovedCount++;
                    } else if (strip.crossedEdges.get(crossing + 1) == null) {
                        strip.removeCrossingWithPassageAfter(crossing);
                        fanSlideCrossingsRemovedCount++;
                    } else {
                        strip.removeCrossingWithPassageAfter(crossing);
                        strip.removeCrossingWithPassageAfter(crossing);
                        dipCrossingsRemovedCount += 2;
                    }
                    removed = true;
                    break;
                }
                if (removed) {
                    break;
                }
            }
        }
        Map<Long, List<int[]>> view = tracedCrossingsByEdge();
        for (FaceStripPath strip : stripByArc) {
            for (int crossing = 0; crossing + 1 < strip.crossedEdges.size(); crossing++) {
                double[] span = slackSpanAt(strip, crossing);
                if (span == null) {
                    continue;
                }
                int[] edge = strip.crossedEdges.get(crossing);
                edge = edge != null ? edge : strip.crossedEdges.get(crossing + 1);
                int[] blocker = blockingCrossingInside(view.get(SnappingCarve.edgeKey(edge)), span[0],
                        span[1]);
                throw new IllegalStateException("arc " + strip.arcId + " crossings " + crossing
                        + " and " + (crossing + 1) + " sweep constraint edge " + edge[0] + ".."
                        + edge[1] + " over [" + span[0] + ", " + span[1] + "]"
                        + (blocker == null
                                ? ", which the taut pass failed to remove"
                                : ", where arc " + blocker[0] + " crossing " + blocker[1]
                                        + " at " + snapping.tracedPositionOf(blocker) + " blocks it")
                        + "; laying it would run a chord along the edge over another arc's"
                        + " lanes");
            }
        }
    }

    /**
     * The slack an adjacent crossing pair carries: a dip crosses one edge twice, a
     * fan wind pairs a through-vertex crossing with a crossing on an edge incident
     * to that vertex.
     *
     * @param strip    strip being read
     * @param crossing index of the pair's first crossing
     * @return the swept stretch of the edge as {@code {lowBound, highBound}}, or
     *         null when the pair is taut
     */
    private double[] slackSpanAt(FaceStripPath strip, int crossing) {
        int[] here = strip.crossedEdges.get(crossing);
        int[] next = strip.crossedEdges.get(crossing + 1);
        if (here != null && next != null) {
            if (here[0] != next[0] || here[1] != next[1]) {
                return null;
            }
            double first = strip.crossingParameters.get(crossing);
            double second = strip.crossingParameters.get(crossing + 1);
            return new double[] { Math.min(first, second), Math.max(first, second) };
        }
        int[] edge = here == null ? next : here;
        int vertex = strip.crossedVertices.get(here == null ? crossing : crossing + 1);
        if (edge == null || edge[0] != vertex && edge[1] != vertex) {
            return null;
        }
        double parameter = strip.crossingParameters.get(here == null ? crossing + 1 : crossing);
        double vertexEnd = edge[0] == vertex ? 0.0 : 1.0;
        return new double[] { Math.min(vertexEnd, parameter), Math.max(vertexEnd, parameter) };
    }

    /**
     * Gathers every strip's edge crossings, the view the taut pass checks spans
     * against, in the {@code {arcId, crossingIndex}} shape
     * {@link #collectCrossings} later rebuilds.
     *
     * @return the crossings keyed by constraint edge
     */
    private Map<Long, List<int[]>> tracedCrossingsByEdge() {
        Map<Long, List<int[]>> view = new HashMap<>();
        for (FaceStripPath strip : snapping.stripByArc) {
            for (int crossing = 0; crossing < strip.crossedEdges.size(); crossing++) {
                int[] endpoints = strip.crossedEdges.get(crossing);
                if (endpoints != null) {
                    view.computeIfAbsent(SnappingCarve.edgeKey(endpoints), key -> new ArrayList<>())
                            .add(new int[] { strip.arcId, crossing });
                }
            }
        }
        return view;
    }

    /**
     * The first crossing sitting strictly inside an open stretch of one edge. The
     * strict bounds exclude the slack pair itself, which sits exactly at them.
     *
     * @param crossings the edge's crossings as {@code {arcId, crossingIndex}}
     * @param lowBound  lower end of the stretch, excluded
     * @param highBound upper end of the stretch, excluded
     * @return the blocking crossing, or null when the stretch is clear
     */
    private int[] blockingCrossingInside(List<int[]> crossings, double lowBound,
            double highBound) {
        for (int[] crossing : crossings) {
            double position = snapping.tracedPositionOf(crossing);
            if (position > lowBound && position < highBound) {
                return crossing;
            }
        }
        return null;
    }
}
