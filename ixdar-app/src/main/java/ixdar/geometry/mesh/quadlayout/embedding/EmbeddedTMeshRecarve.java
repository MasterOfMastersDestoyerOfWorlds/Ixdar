package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * Rebuilds a contracted live T-mesh on a clean copy of the original surface
 * mesh, preserving patch combinatorics and surface curves while discarding
 * contraction triangulation debris.
 */
public final class EmbeddedTMeshRecarve {

    /** Nanoseconds per second, for the timing log. */
    private static final double NANOS_PER_SECOND = 1.0e9;

    private final EmbeddedTMesh source;
    private final HalfEdgeMesh originalMesh;
    private final EmbeddedMeshTopology sourceTopology;
    private final Set<Integer> routedOldArcIds;

    /**
     * Old path steps whose two ends are one point, left behind by the contraction
     * and not replayed onto the fresh copy.
     */
    private int collapsedReplayStepCount;

    private EmbeddedMeshTopology freshTopology;
    private EmbeddedTMesh freshTmesh;
    private FaceChordWalk chordWalk;
    private ArcRerouter rerouter;
    private PatchRegions sourceRegions;
    private List<Set<Integer>> sourceFacesByPatch;

    private int[] nodeByOldIdLive;
    private int[] arcByOldIdLive;
    private int[] oldArcByNewId;

    /**
     * Stores the contracted T-mesh and the original mesh to rebuild onto.
     *
     * @param source       contracted live T-mesh whose arrangement is replayed
     * @param originalMesh source triangle mesh the working copy is rebuilt from
     */
    public EmbeddedTMeshRecarve(EmbeddedTMesh source, HalfEdgeMesh originalMesh) {
        this(source, originalMesh, Set.of());
    }

    /**
     * Stores a rebuild attempt and the old arcs that must be routed afresh.
     *
     * @param source          contracted live T-mesh whose arrangement is replayed
     * @param originalMesh    source triangle mesh the working copy is rebuilt from
     * @param routedOldArcIds old arc ids routed instead of replayed exactly
     */
    private EmbeddedTMeshRecarve(EmbeddedTMesh source, HalfEdgeMesh originalMesh,
            Set<Integer> routedOldArcIds) {
        this.source = source;
        this.originalMesh = originalMesh;
        this.sourceTopology = source.topology;
        this.routedOldArcIds = routedOldArcIds;
    }

    /**
     * Replays the live arrangement on a fresh working copy and validates the
     * result.
     *
     * @throws IllegalStateException when no routed restart produces a valid patch
     *                               partition
     * @return the rebuilt T-mesh over a clean copy of {@code originalMesh}
     */
    public EmbeddedTMesh build() {
        long startNanos = System.nanoTime();
        int oldVertices = sourceTopology.copy.vertexCount();
        int oldFaces = sourceTopology.copy.faceCount();
        sourceRegions = new PatchRegions(source).build();
        indexSourceFacesByPatch();

        freshTopology = new EmbeddedMeshTopology(originalMesh);
        freshTmesh = new EmbeddedTMesh(freshTopology);
        chordWalk = new FaceChordWalk(freshTopology);
        rerouter = new ArcRerouter(freshTopology);
        rerouter.sourceFaceStampBySourceFace = new int[originalMesh.faceCount()];

        nodeByOldIdLive = new int[source.nodes.size()];
        Arrays.fill(nodeByOldIdLive, EmbeddedTMesh.NONE);
        placeLiveNodes();

        arcByOldIdLive = new int[source.arcs.size()];
        Arrays.fill(arcByOldIdLive, EmbeddedTMesh.NONE);
        oldArcByNewId = new int[source.arcs.size()];
        Arrays.fill(oldArcByNewId, EmbeddedTMesh.NONE);
        carveLiveArcs();

        wireLivePatches();

        freshTmesh.resolveWalkOrientation();
        freshTmesh.validate();
        Set<Integer> unmatchedBoundaryArcs = new HashSet<>();
        List<Integer> unmatchedFaces = new PatchRegions(freshTmesh)
                .findFirstUnmatchedRegion(unmatchedBoundaryArcs);
        if (!unmatchedFaces.isEmpty()) {
            int candidateNewArcId = unmatchedBoundaryArcs.stream()
                    .mapToInt(Integer::intValue).max().orElseThrow();
            int candidateOldArcId = oldArcByNewId[candidateNewArcId];
            if (routedOldArcIds.contains(candidateOldArcId)) {
                throw new IllegalStateException("freshly routed old arc " + candidateOldArcId
                        + " still bounds an unmatched region of " + unmatchedFaces.size()
                        + " faces with arcs " + unmatchedBoundaryArcs);
            }
            Set<Integer> nextRoutedOldArcIds = new HashSet<>(routedOldArcIds);
            nextRoutedOldArcIds.add(candidateOldArcId);
            System.out.println("[recarve] restarting with old arc " + candidateOldArcId
                    + " routed to remove unmatched region of " + unmatchedFaces.size() + " faces");
            return new EmbeddedTMeshRecarve(source, originalMesh, nextRoutedOldArcIds).build();
        }
        new PatchRegions(freshTmesh).build();

        System.out.printf(
                "[recarve] nodes=%d arcs=%d patches=%d | copy V=%d->%d F=%d->%d"
                        + " (splits face=%d edge=%d)"
                        + " snapped=%d unsplittable=%d collapsedCrossings=%d collapsedSteps=%d",
                liveNodeCount(), liveArcCount(), livePatchCount(),
                oldVertices, freshTopology.copy.vertexCount(),
                oldFaces, freshTopology.copy.faceCount(),
                freshTopology.faceSplitCount, freshTopology.edgeSplitCount,
                chordWalk.snappedCrossingCount, chordWalk.unsplittableCrossingCount,
                chordWalk.collapsedCrossingCount, collapsedReplayStepCount);
        return freshTmesh;
    }

    /**
     * Places every live node on the fresh working copy.
     */
    private void placeLiveNodes() {
        for (EmbeddedNode node : source.nodes) {
            if (!node.alive) {
                continue;
            }
            int sourceFace = anySourceFace(sourceTopology, node.copyVertex);
            double[] barycentric = sourceTopology.barycentricOf(sourceFace, node.copyVertex);
            int copyVertex = chordWalk.placeVertex(sourceFace, barycentric);
            int newNodeId = freshTmesh.addNode(node.sourceNodeId, copyVertex, node.critical,
                    node.border);
            nodeByOldIdLive[node.nodeId] = newNodeId;
        }
    }

    /**
     * Re-carves every live arc once, claiming each stretch before another walk can
     * flip or split its edges.
     */
    private void carveLiveArcs() {
        Set<Integer> carved = new HashSet<>();
        for (EmbeddedPatch patch : source.patches) {
            if (!patch.alive) {
                continue;
            }
            for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
                for (int oldArcId : patch.sideArcIds.get(side)) {
                    if (carved.add(oldArcId)) {
                        carveArc(oldArcId);
                    }
                }
            }
        }
    }

    /**
     * Replays one contracted arc's barycentric polyline. Interior waypoints are
     * materialized afresh; only the terminal T-mesh node is passed as a pre-placed
     * target, so fan recovery cannot jump to an unrelated interior vertex.
     *
     * @param oldArcId arc id in the contracted mesh
     */
    private void carveArc(int oldArcId) {
        EmbeddedArc arc = source.arcs.get(oldArcId);
        int newArcId = freshTmesh.arcs.size();
        int startNode = nodeByOldIdLive[arc.startNodeId];
        int endNode = nodeByOldIdLive[arc.endNodeId];
        int startVertex = freshTmesh.nodes.get(startNode).copyVertex;
        int endVertex = freshTmesh.nodes.get(endNode).copyVertex;
        List<Integer> freshPath;
        if (routedOldArcIds.contains(oldArcId)) {
            freshPath = new ArrayList<>();
            admitSourceFaces(arc);
            if (!rerouter.tryRoute(newArcId, freshPath, startVertex, endVertex,
                    rerouter.freshCorridor(), EmbeddedMeshTopology.UNCLAIMED)) {
                throw new IllegalStateException("could not route old arc " + oldArcId
                        + " while rebuilding an unmatched patch boundary");
            }
        } else {
            freshPath = replayExactPath(arc, newArcId, startVertex, endVertex);
        }
        requireConnectedPath(newArcId, freshPath);
        requireSeparatedPath(oldArcId, newArcId, freshPath,
                routedOldArcIds.contains(oldArcId));
        int addedArcId = freshTmesh.addArc(arc.sourceArcId, startNode, endNode,
                arc.quantizedLength, arc.feature, freshPath);
        if (addedArcId != newArcId) {
            throw new IllegalStateException("re-carved arc id changed from " + newArcId
                    + " to " + addedArcId + " while being added");
        }
        arcByOldIdLive[oldArcId] = addedArcId;
        oldArcByNewId[addedArcId] = oldArcId;
    }

    /**
     * Replays one contracted arc's barycentric polyline, trimming any zero-area
     * tail after the curve first reaches its end node.
     *
     * @param arc         contracted arc to replay
     * @param newArcId    fresh arc id reserved for claims
     * @param startVertex fresh start-node vertex
     * @param endVertex   fresh end-node vertex
     * @return connected fresh copy-vertex path
     */
    private List<Integer> replayExactPath(EmbeddedArc arc, int newArcId, int startVertex,
            int endVertex) {
        List<Integer> oldPath = arc.path.copyVertexPath;
        int oldEndVertex = source.nodes.get(arc.endNodeId).copyVertex;
        List<Integer> freshPath = new ArrayList<>();
        freshPath.add(startVertex);
        int head = startVertex;
        for (int step = 1; step < oldPath.size(); step++) {
            int oldFrom = oldPath.get(step - 1);
            int oldTo = oldPath.get(step);
            int sourceFace = sharedSourceFace(sourceTopology, oldFrom, oldTo);
            if (sourceFace == EmbeddedMeshTopology.UNCLAIMED) {
                throw new IllegalStateException("arc " + arc.arcId + " old path step "
                        + oldFrom + ".." + oldTo + " has no shared source face");
            }
            double[] fromBarycentric = sourceTopology.barycentricOf(sourceFace, oldFrom);
            double[] toBarycentric = sourceTopology.barycentricOf(sourceFace, oldTo);
            if (step < oldPath.size() - 1
                    && FaceChordWalk.isWithinSeparation(fromBarycentric, toBarycentric,
                            FaceChordWalk.COINCIDENT_SEPARATION)) {
                collapsedReplayStepCount++;
                continue;
            }
            double[] endBarycentric = sourceTopology.barycentricOf(sourceFace, oldEndVertex);
            boolean reachesEnd = endBarycentric != null
                    && liesOnSegment(fromBarycentric, endBarycentric, toBarycentric);
            boolean terminal = reachesEnd || step == oldPath.size() - 1;
            double[] targetBarycentric = reachesEnd ? endBarycentric : toBarycentric;
            int claimFrom = freshPath.size();
            int targetVertex = terminal ? endVertex : EmbeddedMeshTopology.UNCLAIMED;
            try {
                head = walkStretch(newArcId, sourceFace, head, targetVertex,
                        targetBarycentric, freshPath);
            } catch (IllegalStateException blockedChord) {
                while (freshPath.size() > claimFrom) {
                    freshPath.remove(freshPath.size() - 1);
                }
                refreshClaims(newArcId, freshPath);
                boolean detoured = false;
                for (int candidateStep = step; candidateStep < oldPath.size(); candidateStep++) {
                    int candidateOldVertex = oldPath.get(candidateStep);
                    int candidateSourceFace = sharedSourceFace(sourceTopology,
                            oldPath.get(candidateStep - 1), candidateOldVertex);
                    if (candidateSourceFace == EmbeddedMeshTopology.UNCLAIMED) {
                        continue;
                    }
                    boolean candidateTerminal = candidateStep == oldPath.size() - 1;
                    int materializedTarget;
                    try {
                        materializedTarget = candidateTerminal ? endVertex
                                : chordWalk.placeVertex(candidateSourceFace,
                                        sourceTopology.barycentricOf(candidateSourceFace,
                                                candidateOldVertex));
                    } catch (IllegalStateException occupiedTarget) {
                        continue;
                    }
                    admitSourceFaces(arc);
                    List<Integer> detour = new ArrayList<>();
                    if (!rerouter.tryRoute(newArcId, detour, head, materializedTarget,
                            rerouter.freshCorridor(), EmbeddedMeshTopology.UNCLAIMED)) {
                        continue;
                    }
                    for (int index = 1; index < detour.size(); index++) {
                        freshPath.add(detour.get(index));
                    }
                    head = materializedTarget;
                    step = candidateStep;
                    reachesEnd = candidateTerminal;
                    detoured = true;
                    break;
                }
                if (!detoured) {
                    throw new IllegalStateException("could not detour re-carved arc " + arc.arcId
                            + " around a blocked stretch in source face " + sourceFace,
                            blockedChord);
                }
            }
            int reachedEndAt = freshPath.subList(claimFrom, freshPath.size()).indexOf(endVertex);
            if (reachedEndAt >= 0) {
                reachedEndAt += claimFrom;
                while (freshPath.size() > reachedEndAt + 1) {
                    freshPath.remove(freshPath.size() - 1);
                }
                head = endVertex;
                reachesEnd = true;
            }
            eraseLoops(freshPath);
            refreshClaims(newArcId, freshPath);
            if (reachesEnd) {
                break;
            }
        }
        requireConnectedPath(newArcId, freshPath);
        return freshPath;
    }

    /**
     * Indexes the original source faces touched by each contracted patch region.
     */
    private void indexSourceFacesByPatch() {
        sourceFacesByPatch = new ArrayList<>(source.patches.size());
        for (int patchId = 0; patchId < source.patches.size(); patchId++) {
            Set<Integer> sourceFaces = new HashSet<>();
            List<Integer> copyFaces = sourceRegions.copyFacesByPatch.get(patchId);
            if (copyFaces != null) {
                for (int copyFace : copyFaces) {
                    sourceFaces.add(sourceTopology.sourceFaceByCopyFace[copyFace]);
                }
            }
            sourceFacesByPatch.add(sourceFaces);
        }
    }

    /**
     * Restricts a blocked stretch to the source faces of its adjacent patches.
     *
     * @param arc contracted arc whose patch corridor is admitted
     */
    private void admitSourceFaces(EmbeddedArc arc) {
        rerouter.sourceFaceStamp++;
        admitPatchSourceFaces(arc.leftPatchId);
        admitPatchSourceFaces(arc.rightPatchId);
    }

    /**
     * Admits one patch's source faces to the current route.
     *
     * @param patchId contracted patch id, or {@link EmbeddedTMesh#NONE}
     */
    private void admitPatchSourceFaces(int patchId) {
        if (patchId == EmbeddedTMesh.NONE) {
            return;
        }
        for (int sourceFace : sourceFacesByPatch.get(patchId)) {
            rerouter.sourceFaceStampBySourceFace[sourceFace] = rerouter.sourceFaceStamp;
        }
    }

    /**
     * Releases provisional claims left by an exact replay that encountered another
     * established lane.
     *
     * @param arcId provisional fresh arc id
     */
    private void clearClaims(int arcId) {
        for (int edgeId = 0; edgeId < freshTopology.ownerArcByCopyEdge.length; edgeId++) {
            if (freshTopology.ownerArcByCopyEdge[edgeId] == arcId) {
                freshTopology.ownerArcByCopyEdge[edgeId] = EmbeddedMeshTopology.UNCLAIMED;
            }
        }
        for (int vertexId = 0; vertexId < freshTopology.ownerArcByCopyVertex.length; vertexId++) {
            if (freshTopology.ownerArcByCopyVertex[vertexId] == arcId) {
                freshTopology.ownerArcByCopyVertex[vertexId] = EmbeddedMeshTopology.UNCLAIMED;
            }
        }
    }

    /**
     * Rebuilds patch side wiring on the fresh T-mesh.
     */
    private void wireLivePatches() {
        for (EmbeddedPatch patch : source.patches) {
            if (!patch.alive) {
                continue;
            }
            List<List<Integer>> sideArcIds = new ArrayList<>(EmbeddedPatch.SIDES);
            for (List<Integer> side : patch.sideArcIds) {
                List<Integer> remapped = new ArrayList<>(side.size());
                for (int oldArcId : side) {
                    remapped.add(arcByOldIdLive[oldArcId]);
                }
                sideArcIds.add(remapped);
            }
            int firstCorner = nodeByOldIdLive[patch.cornerNodeId(0)];
            freshTmesh.addPatch(patch.sourcePatchId, sideArcIds, firstCorner);
        }
    }

    /**
     * A source face whose closure contains both copy vertices.
     *
     * @param topology   working copy carrying provenance
     * @param fromVertex copy vertex the step leaves
     * @param toVertex   copy vertex the step arrives at
     * @return the shared source face, or {@link EmbeddedMeshTopology#UNCLAIMED}
     */
    private static int sharedSourceFace(EmbeddedMeshTopology topology, int fromVertex,
            int toVertex) {
        HalfEdgeMesh copy = topology.copy;
        int copyEdge = topology.edgeBetween(fromVertex, toVertex);
        if (copyEdge == EmbeddedMeshTopology.UNCLAIMED) {
            return EmbeddedMeshTopology.UNCLAIMED;
        }
        int halfEdge = copy.edgeHalfEdge(copyEdge);
        for (int side = 0; side < 2; side++) {
            int copyFace = copy.halfEdgeFace(side == 0 ? halfEdge : copy.halfEdgeTwin(halfEdge));
            if (copyFace == EmbeddedMeshTopology.UNCLAIMED) {
                continue;
            }
            int sourceFace = topology.sourceFaceByCopyFace[copyFace];
            if (topology.barycentricOf(sourceFace, fromVertex) != null
                    && topology.barycentricOf(sourceFace, toVertex) != null) {
                return sourceFace;
            }
        }
        return EmbeddedMeshTopology.UNCLAIMED;
    }

    /**
     * Any source face registering a barycentric for a copy vertex.
     *
     * @param topology   working copy carrying provenance
     * @param copyVertex vertex to locate
     * @return a source face index
     */
    private static int anySourceFace(EmbeddedMeshTopology topology, int copyVertex) {
        for (int sourceFace = 0; sourceFace < topology.copyFacesBySourceFace.size(); sourceFace++) {
            if (topology.barycentricOf(sourceFace, copyVertex) != null) {
                return sourceFace;
            }
        }
        throw new IllegalStateException("copy vertex " + copyVertex
                + " carries no registered barycentric on any source face");
    }

    /**
     * Walks one chord stretch between consecutive carve points.
     *
     * @param arcId             arc being carved
     * @param sourceFace        source active face the stretch lies in
     * @param startVertex       copy vertex the stretch leaves
     * @param targetVertex      pre-placed target, or
     *                          {@link EmbeddedMeshTopology#UNCLAIMED}
     * @param targetBarycentric target barycentric in the source face
     * @param vertexPath        path vertices, extended in place
     * @return the copy vertex the walk ended on
     */
    private int walkStretch(int arcId, int sourceFace, int startVertex, int targetVertex,
            double[] targetBarycentric, List<Integer> vertexPath) {
        if (startVertex == targetVertex) {
            return targetVertex;
        }
        int reached = chordWalk.walk(arcId, sourceFace, startVertex, targetBarycentric,
                targetVertex, vertexPath);
        if (targetVertex != EmbeddedMeshTopology.UNCLAIMED && reached != targetVertex) {
            throw new IllegalStateException("arc " + arcId + " chord walk ended on copy vertex "
                    + reached + " instead of " + targetVertex + " in source face " + sourceFace);
        }
        return reached;
    }

    /**
     * Whether the middle point lies on the closed segment between the endpoints.
     *
     * @param from   segment start barycentric
     * @param middle point tested
     * @param to     segment end barycentric
     * @return true when the three points are collinear and {@code middle} is between
     */
    private static boolean liesOnSegment(double[] from, double[] middle, double[] to) {
        if (ExactBarycentricOrient.sign(from, middle, to) != 0) {
            return false;
        }
        double dot = 0.0;
        for (int coordinate = 0; coordinate < from.length; coordinate++) {
            dot += (middle[coordinate] - from[coordinate])
                    * (middle[coordinate] - to[coordinate]);
        }
        return dot <= 0.0;
    }

    /**
     * Erases cycles whenever a walk revisits an earlier path vertex.
     *
     * @param path path simplified in place
     */
    private static void eraseLoops(List<Integer> path) {
        List<Integer> simple = new ArrayList<>(path.size());
        Map<Integer, Integer> indexByVertex = new HashMap<>();
        for (int vertexId : path) {
            Integer repeatedAt = indexByVertex.get(vertexId);
            if (repeatedAt == null) {
                indexByVertex.put(vertexId, simple.size());
                simple.add(vertexId);
                continue;
            }
            while (simple.size() > repeatedAt + 1) {
                indexByVertex.remove(simple.remove(simple.size() - 1));
            }
        }
        path.clear();
        path.addAll(simple);
    }

    /**
     * Replaces provisional claims with the current loop-erased path.
     *
     * @param arcId fresh arc id that owns the path
     * @param path  current connected path
     */
    private void refreshClaims(int arcId, List<Integer> path) {
        repairClaimedSplits(arcId, path);
        requireConnectedPath(arcId, path);
        clearClaims(arcId);
        for (int index = 1; index < path.size(); index++) {
            int fromVertex = path.get(index - 1);
            int toVertex = path.get(index);
            freshTopology.claimEdgeBetween(fromVertex, toVertex, arcId);
            if (freshTopology.ownerNodeByCopyVertex[toVertex]
                    == EmbeddedMeshTopology.UNCLAIMED) {
                freshTopology.ownerArcByCopyVertex[toVertex] = arcId;
            }
        }
    }

    /**
     * Inserts replacement lane vertices when a later stretch split an earlier edge
     * owned by the same provisional arc.
     *
     * @param arcId provisional fresh arc id
     * @param path  path repaired in place
     */
    private void repairClaimedSplits(int arcId, List<Integer> path) {
        for (int index = 1; index < path.size(); index++) {
            int fromVertex = path.get(index - 1);
            int toVertex = path.get(index);
            if (freshTopology.edgeBetween(fromVertex, toVertex)
                    != EmbeddedMeshTopology.UNCLAIMED) {
                continue;
            }
            List<Integer> replacement = ownedPath(arcId, fromVertex, toVertex);
            if (replacement.isEmpty()) {
                throw new IllegalStateException("arc " + arcId + " lost its claimed lane from "
                        + fromVertex + " to " + toVertex);
            }
            path.remove(index);
            path.addAll(index, replacement);
            index += replacement.size() - 1;
        }
    }

    /**
     * Finds a path through edges still owned by one provisional arc.
     *
     * @param arcId      provisional fresh arc id
     * @param fromVertex search start
     * @param toVertex   search target
     * @return vertices after the start through the target, or an empty list
     */
    private List<Integer> ownedPath(int arcId, int fromVertex, int toVertex) {
        Map<Integer, Integer> parentByVertex = new HashMap<>();
        ArrayDeque<Integer> frontier = new ArrayDeque<>();
        parentByVertex.put(fromVertex, EmbeddedMeshTopology.UNCLAIMED);
        frontier.add(fromVertex);
        while (!frontier.isEmpty() && !parentByVertex.containsKey(toVertex)) {
            int vertexId = frontier.removeFirst();
            for (int index = 0; index < freshTopology.copy.vertexEdgeCount(vertexId); index++) {
                int edgeId = freshTopology.copy.vertexEdgeAt(vertexId, index);
                if (freshTopology.ownerArcByCopyEdge[edgeId] != arcId) {
                    continue;
                }
                int neighbor = freshTopology.otherEndpoint(edgeId, vertexId);
                if (parentByVertex.putIfAbsent(neighbor, vertexId) == null) {
                    frontier.addLast(neighbor);
                }
            }
        }
        if (!parentByVertex.containsKey(toVertex)) {
            return List.of();
        }
        List<Integer> reverse = new ArrayList<>();
        for (int vertexId = toVertex; vertexId != fromVertex;
                vertexId = parentByVertex.get(vertexId)) {
            reverse.add(vertexId);
        }
        List<Integer> replacement = new ArrayList<>(reverse.size());
        for (int index = reverse.size() - 1; index >= 0; index--) {
            replacement.add(reverse.get(index));
        }
        return replacement;
    }

    /**
     * Checks no two consecutive vertices of a re-carved path are the same point,
     * which nothing downstream could tell apart, and names the surrounding path so
     * the producing step is identifiable.
     *
     * @param oldArcId  arc id in the contracted mesh
     * @param newArcId  arc id on the fresh copy
     * @param path      the re-carved path
     * @param wasRouted whether the path came from a fresh route rather than a replay
     * @throws IllegalStateException when two consecutive vertices coincide
     */
    private void requireSeparatedPath(int oldArcId, int newArcId, List<Integer> path,
            boolean wasRouted) {
        for (int index = 1; index < path.size(); index++) {
            int fromVertex = path.get(index - 1);
            int toVertex = path.get(index);
            int sourceFace = sharedSourceFace(freshTopology, fromVertex, toVertex);
            if (sourceFace == EmbeddedMeshTopology.UNCLAIMED) {
                continue;
            }
            double[] fromBarycentric = freshTopology.barycentricOf(sourceFace, fromVertex);
            double[] toBarycentric = freshTopology.barycentricOf(sourceFace, toVertex);
            if (!FaceChordWalk.isWithinSeparation(fromBarycentric, toBarycentric,
                    FaceChordWalk.COINCIDENT_SEPARATION)) {
                continue;
            }
            throw new IllegalStateException("re-carved arc " + newArcId + " (old " + oldArcId
                    + (wasRouted ? ", routed" : ", replayed") + ") puts copy vertices "
                    + fromVertex + " and " + toVertex + " at one point of source face "
                    + sourceFace + " at path step " + index + " of " + path.size()
                    + "; neighbours " + pathNeighbourhood(path, index)
                    + "; barycentrics " + Arrays.toString(fromBarycentric) + " and "
                    + Arrays.toString(toBarycentric));
        }
    }

    /**
     * The path vertices around one step, for locating which carve step produced a
     * pair of coincident vertices.
     *
     * @param path  the re-carved path
     * @param index step index the pair ends at
     * @return the surrounding vertex ids
     */
    private String pathNeighbourhood(List<Integer> path, int index) {
        int from = Math.max(0, index - 2);
        int to = Math.min(path.size(), index + 3);
        return path.subList(from, to).toString() + " at " + from + ".." + (to - 1);
    }

    /**
     * Checks every consecutive pair in an arc path is joined by a copy edge.
     *
     * @param arcId      arc whose path is being checked
     * @param vertexPath vertex path on the fresh working copy
     */
    private void requireConnectedPath(int arcId, List<Integer> vertexPath) {
        for (int index = 1; index < vertexPath.size(); index++) {
            int fromVertex = vertexPath.get(index - 1);
            int toVertex = vertexPath.get(index);
            if (freshTopology.edgeBetween(fromVertex, toVertex) == EmbeddedMeshTopology.UNCLAIMED) {
                throw new IllegalStateException("arc " + arcId + " replay path steps from "
                        + fromVertex + " to " + toVertex + " with no edge between them");
            }
        }
    }

    /**
     * Counts live nodes in the contracted source T-mesh.
     *
     * @return number of live nodes
     */
    private int liveNodeCount() {
        int live = 0;
        for (EmbeddedNode node : source.nodes) {
            if (node.alive) {
                live++;
            }
        }
        return live;
    }

    /**
     * Counts live arcs in the contracted source T-mesh.
     *
     * @return number of live arcs
     */
    private int liveArcCount() {
        int live = 0;
        for (EmbeddedArc arc : source.arcs) {
            if (arc.alive) {
                live++;
            }
        }
        return live;
    }

    /**
     * Counts live patches in the contracted source T-mesh.
     *
     * @return number of live patches
     */
    private int livePatchCount() {
        int live = 0;
        for (EmbeddedPatch patch : source.patches) {
            if (patch.alive) {
                live++;
            }
        }
        return live;
    }
}
