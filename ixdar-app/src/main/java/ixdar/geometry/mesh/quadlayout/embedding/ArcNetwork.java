package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.ActiveIdSet;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.IntIdList;
import ixdar.geometry.mesh.quadlayout.embedding.records.PatchCorridor;
import ixdar.geometry.mesh.quadlayout.embedding.records.ArcEdgePath;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedNode;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.gridmap.PatchRegions;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.FeatureEdgeSpan;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.Trace;
import ixdar.platform.Platforms;

/**
 * The quad layout's nodes, arcs and patches together with their realization on
 * the working copy of the triangle mesh.
 *
 * <p>
 * Elements are retired by clearing {@code alive}, never removed, so an id is
 * always its index. This is the only writer of {@link EmbeddedMeshTopology}'s
 * claim arrays.
 *
 * <p>
 * See also: LCBK19 Section 6
 */
public class ArcNetwork {

    /** Absent id, for elements with no source and for unset patch references. */
    public static final int NONE = -1;

    /**
     * Divisor turning elapsed nanoseconds into the seconds the log lines report.
     */
    private static final double NANOS_PER_SECOND = 1.0e9;

    /** Split position for a midpoint edge split. */
    private static final double EDGE_MIDPOINT = 0.5;

    /** First allocation of {@link #changedPatches}. */
    private static final int CHANGED_PATCH_INITIAL_CAPACITY = 16;

    public EmbeddedMeshTopology topology;

    /** The source triangle mesh the layout covers. */
    public final HalfEdgeMesh sourceMesh;

    /** Motorcycle traces, arrangement-phase data carried for quantization. */
    public List<Trace> traces;

    /**
     * Feature-chain span per alignment/boundary edge id, arrangement-phase data
     * carried for quantization diagnostics.
     */
    public Map<Integer, FeatureEdgeSpan> featureSpanByEdgeId;

    /**
     * Whether a patch lies left of the direction {@link #addPatch} walks its
     * boundary.
     *
     * <p>
     * Which way the walk runs is the caller's side ordering, not a property of the
     * surface, so {@link #resolveWalkOrientation} measures it rather than assuming
     * it.
     */
    public boolean interiorLeftOfWalk = true;

    /** Every node ever created, including those since merged away. */
    public final List<EmbeddedNode> nodes;

    /** Every arc ever created, including those since collapsed. */
    public final List<EmbeddedArc> arcs;

    /** Every patch ever created, including those since removed. */
    public final List<EmbeddedPatch> patches;

    /** Arc ends incident to each node, indexed by node id. */
    public final List<List<Integer>> arcEndsByNode;

    /**
     * Patches whose boundary or boundary-arc endpoints changed since operator (3)
     * last looked, so a patch absent here cannot have newly become a bigon. May
     * hold retired patches.
     */
    public int[] changedPatches = new int[0];

    /** Live entry count of {@link #changedPatches}. */
    public int changedPatchCount;

    /** Membership stamp for {@link #changedPatches}, indexed by patch id. */
    public boolean[] patchIsChanged = new boolean[0];

    /**
     * Recorder of every {@link #markPatchChanged} call, with duplicates, cleared at the start of
     * each {@link #contractStep}; {@link #stepUpdatedPatches} reads it.
     */
    public final IntIdList stepPatchLog = new IntIdList(0);

    /**
     * The corridor of the re-route attempt that just failed, held for the failure
     * diagnostic. Refinement splits an edge only when both endpoints are corridor
     * members.
     */
    public ActiveIdSet diagnosticCorridor;

    /** Flood query answering which copy faces a live patch covers. */
    public final PatchCorridor corridor = new PatchCorridor(this);

    /** Cover refloods {@link #relabelPatchCover} performed, counting only calls that flooded. */
    public int relabelCallCount;

    /** Total faces repainted across all {@link #relabelPatchCover} floods. */
    public int relabelFacesFlooded;

    /** Wall nanos spent inside {@link #relabelPatchCover} floods. */
    public long relabelNanos;

    /**
     * Working-copy vertices the carve and the operators minted, filled by
     * {@link #measureDensity}.
     */
    public int mintedVertexCount;

    /** Of those, vertices a live node owns. */
    public int nodeMintedVertexCount;

    /** Of those, vertices a live arc owns but no live node does. */
    public int arcMintedVertexCount;

    /**
     * Of those, vertices no live node and no live arc owns — refinement the
     * finished layout does not touch, which the re-carve exists to drive to zero.
     */
    public int debrisVertexCount;

    /** Working-copy faces beyond the source mesh's own. */
    public int faceGrowthCount;

    /**
     * Source faces no live arc passes through, filled by
     * {@link #measureFaceContention}.
     */
    public int untouchedSourceFaceCount;

    /**
     * Source faces exactly one live arc passes through, where a chord needs no
     * lane.
     */
    public int singleArcSourceFaceCount;

    /** Source faces exactly two live arcs pass through. */
    public int twoArcSourceFaceCount;

    /** Source faces three or more live arcs pass through. */
    public int crowdedSourceFaceCount;

    /** Most live arcs any one source face carries. */
    public int mostArcsOnASourceFace;

    /** The source face carrying {@link #mostArcsOnASourceFace}. */
    public int worstArcSourceFace = NONE;

    /**
     * Source faces holding at least one live node vertex, which subdivides them.
     */
    public int nodeBearingSourceFaceCount;

    /** Most live node vertices any one source face holds. */
    public int mostNodesOnASourceFace;

    /**
     * The source surface's {@code V - E + F}, which every {@link #validate} checks.
     */
    public final int expectedEulerCharacteristic;

    /**
     * Creates an empty T-mesh over a working copy. Production callers assemble it
     * from a carve with {@link #build}; fixtures add nodes, arcs and patches by
     * hand. The expected Euler characteristic is read off the source mesh.
     *
     * @param topology working copy the T-mesh is embedded in
     */
    public ArcNetwork(EmbeddedMeshTopology topology) {
        this.topology = topology;
        this.sourceMesh = topology.sourceMesh;
        this.nodes = new ArrayList<>();
        this.arcs = new ArrayList<>();
        this.patches = new ArrayList<>();
        this.arcEndsByNode = new ArrayList<>();
        this.expectedEulerCharacteristic = sourceMesh.vertexCount() - sourceMesh.edgeCount()
                + sourceMesh.faceCount();
    }

    /**
     * Creates an arrangement-phase T-mesh over the source mesh, with no working
     * copy yet: the motorcycle stage mints nodes, arcs and patches into it, and
     * the layout-embedding assembly later fills {@link #topology} and the paths.
     *
     * @param sourceMesh source triangle mesh the layout covers
     */
    public ArcNetwork(HalfEdgeMesh sourceMesh) {
        this.topology = null;
        this.sourceMesh = sourceMesh;
        this.nodes = new ArrayList<>();
        this.arcs = new ArrayList<>();
        this.patches = new ArrayList<>();
        this.arcEndsByNode = new ArrayList<>();
        this.expectedEulerCharacteristic = sourceMesh.vertexCount() - sourceMesh.edgeCount()
                + sourceMesh.faceCount();
    }



    /**
     * Assembles the T-mesh in place from a finished carve: every record keeps its
     * arrangement id; nodes gain their copy vertices, arcs their carved paths, and
     * patches their node chains, while arrangement leftovers that bound no patch
     * are retired. Validates against the surface's Euler characteristic.
     *
     * @param embedding construction-half embedding whose carve is assembled; its
     *                  working copy becomes this T-mesh's {@link #topology}
     * @throws IllegalStateException when a patch is not a valid rectangle, an arc
     *                               in a patch was not carved, a node in an arc was
     *                               not placed, or the assembled complex is not a
     *                               cell decomposition of the surface
     * @return this, assembled and validated
     */
    public ArcNetwork assemble(LayoutEmbedding embedding) {
        this.topology = embedding.topology;
        arcEndsByNode.clear();
        for (int node = 0; node < nodes.size(); node++) {
            arcEndsByNode.add(new ArrayList<>());
        }
        for (EmbeddedNode node : nodes) {
            node.alive = false;
        }
        for (EmbeddedArc arc : arcs) {
            arc.alive = false;
        }
        for (EmbeddedPatch patch : patches) {
            if (!patch.validRectangle) {
                throw new IllegalStateException("patch " + patch.patchId + " is not a valid"
                        + " rectangle");
            }
            for (List<Integer> side : patch.sideArcIds) {
                for (int arcId : side) {
                    EmbeddedArc arc = arcs.get(arcId);
                    if (arc.alive) {
                        continue;
                    }
                    reviveNode(embedding, arc.startNodeId);
                    reviveNode(embedding, arc.endNodeId);
                    ArcEdgePath carved = embedding.pathByArc[arcId];
                    if (carved == null) {
                        throw new IllegalStateException("arc " + arcId + " bounds a patch but was"
                                + " never carved; the carve and the patch structure disagree");
                    }
                    int startVertex = embedding.vertexIdByNode[arc.startNodeId];
                    int endVertex = embedding.vertexIdByNode[arc.endNodeId];
                    List<Integer> path = carved.copyVertexPath;
                    int first = path.get(0);
                    int last = path.get(path.size() - 1);
                    List<Integer> vertexPath;
                    if (first == startVertex && last == endVertex) {
                        vertexPath = new ArrayList<>(path);
                    } else if (first == endVertex && last == startVertex) {
                        vertexPath = new ArrayList<>(path);
                        Collections.reverse(vertexPath);
                    } else {
                        throw new IllegalStateException("arc " + arcId + " carved path runs "
                                + first + ".." + last + " but its nodes sit on vertices "
                                + startVertex + " and " + endVertex);
                    }
                    List<Integer> edges = new ArrayList<>(vertexPath.size() - 1);
                    for (int index = 1; index < vertexPath.size(); index++) {
                        edges.add(requireEdge(arcId, vertexPath.get(index - 1),
                                vertexPath.get(index)));
                    }
                    arc.path = new ArcEdgePath(arcId, vertexPath, edges);
                    arc.feature = embedding.featureByArc[arcId];
                    arc.alive = true;
                    arcEndsByNode.get(arc.startNodeId).add(arcId);
                    arcEndsByNode.get(arc.endNodeId).add(arcId);
                    topology.claimPath(arcId, arc.path);
                }
            }
        }
        for (EmbeddedPatch patch : patches) {
            List<Integer> firstSide = patch.sideArcIds.get(0);
            List<Integer> lastSide = patch.sideArcIds.get(EmbeddedPatch.SIDES - 1);
            EmbeddedArc entering = arcs.get(firstSide.get(0));
            EmbeddedArc leaving = arcs.get(lastSide.get(lastSide.size() - 1));
            int firstCorner = entering.endNodeId;
            if (entering.startNodeId == leaving.startNodeId
                    || entering.startNodeId == leaving.endNodeId) {
                firstCorner = entering.startNodeId;
            }
            int walkNode = firstCorner;
            for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
                List<Integer> sideNodes = patch.sideNodeIds.get(side);
                sideNodes.clear();
                sideNodes.add(walkNode);
                for (int arcId : patch.sideArcIds.get(side)) {
                    EmbeddedArc arc = arcs.get(arcId);
                    if (arc.startNodeId != walkNode && arc.endNodeId != walkNode) {
                        throw new IllegalStateException("patch " + patch.patchId + " side " + side
                                + ": arc " + arcId + " does not touch node " + walkNode);
                    }
                    if ((arc.startNodeId == walkNode) == interiorLeftOfWalk) {
                        arc.leftPatchId = patch.patchId;
                    } else {
                        arc.rightPatchId = patch.patchId;
                    }
                    walkNode = arc.otherNode(walkNode);
                    sideNodes.add(walkNode);
                }
            }
            if (walkNode != firstCorner) {
                throw new IllegalStateException("patch " + patch.patchId
                        + " boundary does not close: walked back to node " + walkNode
                        + " instead of " + firstCorner);
            }
            patch.alive = true;
            markPatchChanged(patch.patchId);
        }
        resolveWalkOrientation();
        validate();
        return this;
    }

    /**
     * Revives one arrangement node into the assembled T-mesh, giving it its copy
     * vertex, criticality and border flag.
     *
     * @param embedding construction-half embedding being assembled
     * @param nodeId    node to revive
     * @throws IllegalStateException when the node was never placed on a copy vertex
     */
    private void reviveNode(LayoutEmbedding embedding, int nodeId) {
        EmbeddedNode node = nodes.get(nodeId);
        if (node.alive) {
            return;
        }
        int copyVertex = embedding.vertexIdByNode[nodeId];
        if (copyVertex < 0) {
            throw new IllegalStateException("node " + nodeId + " bounds an arc but was never"
                    + " placed on a copy vertex");
        }
        node.copyVertex = copyVertex;
        node.critical = embedding.criticalByNode[nodeId];
        node.alive = true;
        topology.ownerNodeByCopyVertex[copyVertex] = nodeId;
    }

    /**
     * Adds a node on a copy vertex.
     *
     * @param sourceNodeId originating {@code TMeshNode} id, or {@link #NONE}
     * @param copyVertex   vertex of the working copy the node sits on
     * @param critical     whether the node's position is prescribed (LCBK19 Def
     *                     6.2)
     * @param border       whether the node lies in the surface boundary (LCBK19 Def
     *                     6.1)
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
     * Adds an arc between two nodes, realized by a path of copy vertices, and
     * claims the mesh elements it runs along.
     *
     * <p>
     * The edges are looked up from the vertices, so a path that does not walk the
     * mesh is rejected here.
     *
     * @param sourceArcId     originating {@code TraceArc} id, or {@link #NONE}
     * @param startNodeId     node the arc runs from
     * @param endNodeId       node the arc runs to
     * @param quantizedLength prescribed parametric length, never negative
     * @param feature         whether the arc lies on a feature or boundary curve
     * @param vertexPath      copy vertices the arc passes through, from its start
     *                        node's vertex to its end node's vertex
     * @throws IllegalStateException when consecutive vertices of the path are not
     *                               joined by an edge of the working copy
     * @return the new arc's id
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
     * Adds a patch whose sides are given as chains of arcs, walking the boundary in
     * one consistent cyclic direction. The node chain of each side is derived from
     * the arcs, so the caller does not state it twice and cannot state it
     * inconsistently.
     *
     * @param sourcePatchId originating {@code TMeshPatch} id, or {@link #NONE}
     * @param sideArcIds    four sides, each a list of arc ids in the side's walking
     *                      order
     * @param firstCornerId node the first side starts at, which fixes the walk's
     *                      direction
     * @throws IllegalStateException when the given arcs do not chain into a closed
     *                               boundary
     * @return the new patch's id
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
                    throw new IllegalStateException("patch " + patchId + " side " + side
                            + ": arc " + arcId + " does not touch node " + walkNode);
                }
                if ((arc.startNodeId == walkNode) == interiorLeftOfWalk) {
                    arc.leftPatchId = patchId;
                } else {
                    arc.rightPatchId = patchId;
                }
                walkNode = arc.otherNode(walkNode);
                patch.sideNodeIds.get(side).add(walkNode);
            }
        }
        if (walkNode != firstCornerId) {
            throw new IllegalStateException("patch " + patchId
                    + " boundary does not close: walked back to node " + walkNode
                    + " instead of " + firstCornerId);
        }
        markPatchChanged(patchId);
        return patchId;
    }

    /**
     * Stamps a patch for operator (3) to re-test, because its side arcs or their
     * endpoints changed. Every mutator that can create a bigon must call this: an
     * unstamped patch is never looked at again.
     *
     * @param patchId patch to re-test, or {@link #NONE} to record nothing
     */
    public void markPatchChanged(int patchId) {
        if (patchId == NONE) {
            return;
        }
        stepPatchLog.add(patchId);
        if (patchId >= patchIsChanged.length) {
            patchIsChanged = Arrays.copyOf(patchIsChanged,
                    Math.max(patchId + 1, patchIsChanged.length * 2));
        }
        if (patchIsChanged[patchId]) {
            return;
        }
        patchIsChanged[patchId] = true;
        if (changedPatchCount == changedPatches.length) {
            changedPatches = Arrays.copyOf(changedPatches,
                    Math.max(CHANGED_PATCH_INITIAL_CAPACITY, changedPatchCount * 2));
        }
        changedPatches[changedPatchCount++] = patchId;
    }

    /**
     * Measures which side of a boundary walk the patches lie on, and restates every
     * arc's left and right patch in those terms.
     *
     * <p>
     * Call once the layout is complete: the test needs each patch bounded by its
     * own arcs alone, true of a fresh arrangement but not of one mid-contraction.
     *
     * @throws IllegalStateException when patches disagree, since the walk is one
     *                               convention
     */
    public void resolveWalkOrientation() {
        boolean decided = false;
        boolean leftIsInterior = true;
        int decidedBy = NONE;
        for (EmbeddedPatch patch : patches) {
            if (!patch.alive) {
                continue;
            }
            boolean vote = interiorLiesLeftOfWalk(patch.patchId);
            if (!decided) {
                leftIsInterior = vote;
                decidedBy = patch.patchId;
                decided = true;
            } else if (vote != leftIsInterior) {
                throw new IllegalStateException("patch " + patch.patchId + " lies on the "
                        + (vote ? "left" : "right") + " of its boundary walk but patch " + decidedBy
                        + " lies on the other side: the layout's patch sides are not all ordered the"
                        + " same way round, so no single convention describes them");
            }
        }
        if (!decided) {
            return;
        }
        interiorLeftOfWalk = leftIsInterior;
        for (EmbeddedArc arc : arcs) {
            arc.leftPatchId = NONE;
            arc.rightPatchId = NONE;
        }
        for (EmbeddedPatch patch : patches) {
            if (!patch.alive) {
                continue;
            }
            for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
                List<Integer> sideArcs = patch.sideArcIds.get(side);
                List<Integer> sideNodes = patch.sideNodeIds.get(side);
                for (int index = 0; index < sideArcs.size(); index++) {
                    EmbeddedArc arc = arcs.get(sideArcs.get(index));
                    if ((arc.startNodeId == sideNodes.get(index)) == interiorLeftOfWalk) {
                        arc.leftPatchId = patch.patchId;
                    } else {
                        arc.rightPatchId = patch.patchId;
                    }
                }
            }
        }
    }

    /**
     * Whether a patch covers the faces left of the direction its boundary was
     * walked.
     *
     * <p>
     * A patch's interior is bounded by its own arcs alone, so a flood that reaches
     * an edge claimed by another arc started outside it.
     *
     * @param patchId patch to test
     * @throws IllegalStateException when no boundary arc settles the question
     * @return true when the patch lies left of its walk
     */
    private boolean interiorLiesLeftOfWalk(int patchId) {
        EmbeddedPatch patch = patches.get(patchId);
        Set<Integer> wall = new HashSet<>();
        Set<Integer> ownArcs = new HashSet<>();
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            for (int arcId : patch.sideArcIds.get(side)) {
                ownArcs.add(arcId);
                wall.addAll(arcs.get(arcId).path.copyEdgePath);
            }
        }
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            List<Integer> sideArcs = patch.sideArcIds.get(side);
            List<Integer> sideNodes = patch.sideNodeIds.get(side);
            for (int index = 0; index < sideArcs.size(); index++) {
                EmbeddedArc arc = arcs.get(sideArcs.get(index));
                List<Integer> path = arc.path.copyVertexPath;
                if (!arc.alive || path.size() < 2) {
                    continue;
                }
                boolean forward = arc.startNodeId == sideNodes.get(index);
                int from = forward ? path.get(0) : path.get(path.size() - 1);
                int to = forward ? path.get(1) : path.get(path.size() - 2);
                int halfEdge = topology.copy.edgeHalfEdge(topology.edgeBetween(from, to));
                if (topology.copy.halfEdgeVertex(halfEdge) != from) {
                    halfEdge = topology.copy.halfEdgeTwin(halfEdge);
                }
                int leftFace = topology.copy.halfEdgeFace(halfEdge);
                if (leftFace != EmbeddedMeshTopology.UNCLAIMED) {
                    return floodStaysInside(wall, ownArcs, leftFace);
                }
            }
        }
        throw new IllegalStateException("patch " + patchId
                + " has no embedded boundary arc to take a side from");
    }

    /**
     * Whether a flood from a seed reaches only edges the patch is bounded by.
     *
     * @param wall    the patch's own boundary edges, which the flood stops at
     * @param ownArcs the patch's boundary arcs
     * @param seed    face to flood from
     * @return true when the flood stayed inside the patch
     */
    private boolean floodStaysInside(Set<Integer> wall, Set<Integer> ownArcs, int seed) {
        Set<Integer> visited = new HashSet<>();
        Deque<Integer> frontier = new ArrayDeque<>();
        visited.add(seed);
        frontier.add(seed);
        while (!frontier.isEmpty()) {
            int faceId = frontier.poll();
            for (int corner = 0; corner < topology.copy.faceHalfEdgeCount(faceId); corner++) {
                int edgeId = topology.copy.faceEdgeAt(faceId, corner);
                if (wall.contains(edgeId)) {
                    continue;
                }
                int owner = topology.ownerArcByCopyEdge[edgeId];
                if (owner != EmbeddedMeshTopology.UNCLAIMED && !ownArcs.contains(owner)) {
                    return false;
                }
                int halfEdge = topology.copy.edgeHalfEdge(edgeId);
                int neighbour = topology.copy.halfEdgeFace(halfEdge) == faceId
                        ? topology.copy.halfEdgeFace(topology.copy.halfEdgeTwin(halfEdge))
                        : topology.copy.halfEdgeFace(halfEdge);
                if (neighbour != EmbeddedMeshTopology.UNCLAIMED && visited.add(neighbour)) {
                    frontier.add(neighbour);
                }
            }
        }
        return true;
    }

    /**
     * The number of live arc ends at a node, read straight off the node's fan since
     * a retiring arc always leaves it.
     *
     * @param nodeId node to measure
     * @throws IllegalStateException under {@link NetworkContraction#VALIDATE_EVERY_COLLAPSE} when a
     *                               dead arc is still in the fan, which would
     *                               overstate the degree
     * @return the node's degree, a loop counting twice
     */
    public int degree(int nodeId) {
        List<Integer> ends = arcEndsByNode.get(nodeId);
        if (NetworkContraction.VALIDATE_EVERY_COLLAPSE) {
            int live = 0;
            for (int index = 0; index < ends.size(); index++) {
                if (arcs.get(ends.get(index)).alive) {
                    live++;
                }
            }
            if (live != ends.size()) {
                throw new IllegalStateException("node " + nodeId + " has a fan of " + ends.size()
                        + " arcs but only " + live + " are live; every retiring arc is meant to"
                        + " leave its nodes' fans, and the O(1) degree relies on that");
            }
        }
        return ends.size();
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
     * The total quad count of one side of a patch, the extent its rectangle is
     * built at.
     *
     * @param patchId patch to measure
     * @param side    side index in {@code [0, 4)}
     * @return the sum of the side's arcs' quad counts; zero for an empty side
     */
    public int sideQuadCount(int patchId, int side) {
        int total = 0;
        for (int arcId : patches.get(patchId).sideArcIds.get(side)) {
            total += arcs.get(arcId).quadCount;
        }
        return total;
    }

    /**
     * The offset on the opposite side of a patch matching an offset on one side.
     * Sides {@code i} and {@code i + 2} are walked in opposite directions, so the
     * result is a subtraction rather than an identity.
     *
     * <p>
     * See also: LCBK19 Section 6.1
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
     * The number of a patch's live boundary arcs whose quantized length is
     * positive.
     *
     * <p>
     * Counted over arcs, not sides: a single side carrying both positive and zero
     * arcs makes a zero-patch non-simple, and counting sides would miss it.
     *
     * <p>
     * See also: LCBK19 Section 6.1
     *
     * @param patchId patch to measure
     * @return the count: zero for a point patch, two for a simple zero-patch, more
     *         for a non-simple one
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
     * Whether the quantization gives a patch zero parametric area, so that it must
     * be re-embedded onto a curve or a point before a per-patch map can exist.
     *
     * <p>
     * See also: LCBK19 Section 6.2
     *
     * @param patchId patch to test
     * @return true when either of the patch's two dimensions is zero
     */
    public boolean isZeroPatch(int patchId) {
        return sideQuantizedLength(patchId, 0) == 0 || sideQuantizedLength(patchId, 1) == 0;
    }

    /**
     * Re-routes an arc along a new path of copy vertices, releasing the mesh
     * elements it held before claiming the new ones — an arc keeping most of its
     * old lane would otherwise collide with itself.
     *
     * @param arcId      arc to re-route
     * @param vertexPath copy vertices the arc now passes through, end to end
     * @throws IllegalStateException when consecutive vertices are not joined by an
     *                               edge
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
     * Embeds one node onto another: arcs ending at the discarded node are
     * re-pointed at the kept one, whose paths must already reach it. Only the
     * incident arcs' patches are relabelled — every side-referenced node is an
     * endpoint of a boundary arc.
     *
     * @param keepNodeId    node that stays, and that everything is re-pointed at
     * @param discardNodeId node that is embedded onto it
     * @throws IllegalStateException when an arc at the discarded node has not been
     *                               re-routed
     */
    public void mergeNodeInto(int keepNodeId, int discardNodeId) {
        if (keepNodeId == discardNodeId) {
            return;
        }
        EmbeddedNode keep = nodes.get(keepNodeId);
        EmbeddedNode discard = nodes.get(discardNodeId);
        List<Integer> touchedPatchIds = new ArrayList<>();
        for (int arcId : arcEndsByNode.get(discardNodeId)) {
            EmbeddedArc arc = arcs.get(arcId);
            for (int patchId : new int[] { arc.leftPatchId, arc.rightPatchId }) {
                if (patchId != NONE && patches.get(patchId).alive
                        && !touchedPatchIds.contains(patchId)) {
                    touchedPatchIds.add(patchId);
                }
            }
        }
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
        for (int touchedIndex = 0; touchedIndex < touchedPatchIds.size(); touchedIndex++) {
            EmbeddedPatch patch = patches.get(touchedPatchIds.get(touchedIndex));
            markPatchChanged(patch.patchId);
            for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
                List<Integer> sideNodes = patch.sideNodeIds.get(side);
                for (int index = 0; index < sideNodes.size(); index++) {
                    if (sideNodes.get(index) == discardNodeId) {
                        sideNodes.set(index, keepNodeId);
                    }
                }
            }
        }
        if (NetworkContraction.VALIDATE_EVERY_COLLAPSE) {
            for (EmbeddedPatch patch : patches) {
                if (!patch.alive) {
                    continue;
                }
                for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
                    if (patch.sideNodeIds.get(side).contains(discardNodeId)) {
                        throw new IllegalStateException("patch " + patch.patchId
                                + " still references merged-away node " + discardNodeId
                                + " but no live arc at that node borders it");
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
     * Removes a zero arc that {@link #mergeNodeInto} has closed into a loop. Each
     * flank loses it from its side; a flank left with an empty boundary retires,
     * aliased into the opposite flank so its cover resolves there.
     *
     * <p>
     * See also: LCBK19 Section 6.1
     *
     * @param arcId       arc to remove
     * @param mergedANode whether collapsing this arc merged one node into another,
     *                    which is false exactly when the arc was already a loop
     *                    before the collapse
     * @throws IllegalStateException when the arc's ends are still two different
     *                               nodes
     */
    public void removeCollapsedArc(int arcId, boolean mergedANode) {
        EmbeddedArc arc = arcs.get(arcId);
        if (!arc.isLoop()) {
            throw new IllegalStateException("arc " + arcId + " cannot be removed as collapsed:"
                    + " its ends are still nodes " + arc.startNodeId + " and " + arc.endNodeId);
        }
        releaseClaims(arc);
        markPatchChanged(arc.leftPatchId);
        markPatchChanged(arc.rightPatchId);
        int pinchedPatchId = mergedANode ? NONE : pinchedPatchOf(arcId);
        if (pinchedPatchId != NONE) {
            int farPatchId = arc.leftPatchId == pinchedPatchId ? arc.rightPatchId : arc.leftPatchId;
            int resolvedFarId = farPatchId == NONE ? NONE : topology.resolvePatch(farPatchId);
            if (resolvedFarId != NONE && resolvedFarId != pinchedPatchId
                    && patches.get(resolvedFarId).alive) {
                spliceIntoPatch(resolvedFarId, arcId, pinchedPatchId,
                        boundaryPathAround(pinchedPatchId, arcId));
                topology.aliasPatchInto(pinchedPatchId, resolvedFarId, patches.size());
            }
            removePatch(pinchedPatchId);
            arcEndsByNode.get(arc.startNodeId).removeIf(id -> id == arcId);
            arc.alive = false;
            return;
        }
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
                int otherFlankId = patchId == arc.leftPatchId ? arc.rightPatchId : arc.leftPatchId;
                int absorberId = otherFlankId == NONE ? NONE : topology.resolvePatch(otherFlankId);
                if (absorberId != NONE && absorberId != patchId
                        && patches.get(absorberId).alive) {
                    topology.aliasPatchInto(patchId, absorberId, patches.size());
                }
                patches.get(patchId).alive = false;
            }
        }
    }

    /**
     * Retires a live arc whose embedding degenerated to a point, merging or retiring its
     * flanks — a point separates nothing, so its flanks are already one cell.
     *
     * @param arcId arc embedded on a single vertex, its nodes already merged
     * @throws IllegalStateException when a flank keeps non-zero boundary, where the arc had
     *                               to be embedded as a real loop instead
     */
    public void retirePointEmbeddedArc(int arcId) {
        EmbeddedArc arc = arcs.get(arcId);
        // Flank records can be stale on a degenerated arc; the side lists are ground truth
        // for which cells the point still pretends to bound.
        List<Integer> owners = new ArrayList<>();
        for (EmbeddedPatch patch : patches) {
            if (patch.alive
                    && patch.sideArcIds.stream().anyMatch(side -> side.contains(arcId))) {
                owners.add(patch.patchId);
            }
        }
        arc.leftPatchId = owners.isEmpty() ? NONE : owners.get(0);
        arc.rightPatchId = owners.size() < 2 ? NONE : owners.get(1);
        if (pinchedPatchOf(arcId) != NONE) {
            removeCollapsedArc(arcId, false);
            return;
        }
        List<Integer> realFlanks = new ArrayList<>();
        for (int patchId : owners) {
            if (!boundaryArcsExcluding(patchId, arcId).isEmpty()) {
                realFlanks.add(patchId);
            }
        }
        if (realFlanks.size() > 1) {
            throw new IllegalStateException("arc " + arcId + " is embedded on a point but both"
                    + " its flanks " + realFlanks + " keep non-zero boundary: it had to be"
                    + " embedded as a loop enclosing its cell, not collapsed to a point");
        }
        releaseClaims(arc);
        if (realFlanks.size() == 1) {
            EmbeddedPatch real = patches.get(realFlanks.get(0));
            for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
                int index = real.sideArcIds.get(side).indexOf(arcId);
                while (index >= 0) {
                    real.sideArcIds.get(side).remove(index);
                    real.sideNodeIds.get(side).remove(index + 1);
                    index = real.sideArcIds.get(side).indexOf(arcId);
                }
            }
            markPatchChanged(realFlanks.get(0));
        }
        // With no real flank the point's cells vanish entirely; the cover label beside the
        // point names the enclosing cell their stale labels must resolve into.
        int enclosingPatchId = NONE;
        if (realFlanks.isEmpty() && topology.patchByCopyFace.length > 0) {
            int pointVertex = arc.path.copyVertexPath.get(0);
            for (int spoke = 0; spoke < topology.copy.vertexEdgeCount(pointVertex)
                    && enclosingPatchId == NONE; spoke++) {
                int halfEdge = topology.copy.edgeHalfEdge(
                        topology.copy.vertexEdgeAt(pointVertex, spoke));
                for (int faceId : new int[] { topology.copy.halfEdgeFace(halfEdge),
                        topology.copy.halfEdgeFace(topology.copy.halfEdgeTwin(halfEdge)) }) {
                    int labelId = faceId < 0 ? NONE
                            : topology.resolvePatch(topology.patchLabelOf(faceId));
                    if (labelId >= 0 && labelId < patches.size() && !owners.contains(labelId)
                            && patches.get(labelId).alive) {
                        enclosingPatchId = labelId;
                        break;
                    }
                }
            }
        }
        for (int patchId : owners) {
            if (realFlanks.contains(patchId)) {
                continue;
            }
            if (realFlanks.size() == 1) {
                topology.aliasPatchInto(patchId, realFlanks.get(0), patches.size());
            } else if (enclosingPatchId != NONE) {
                topology.aliasPatchInto(patchId, enclosingPatchId, patches.size());
            }
            markPatchChanged(patchId);
            removePatch(patchId);
        }
        arcEndsByNode.get(arc.startNodeId).removeIf(id -> id == arcId);
        arc.alive = false;
    }

    /**
     * The patch a collapsing loop pinches out of existence, or {@link #NONE} when
     * it pinches none.
     *
     * <p>
     * A loop has one node, so {@link #mergeNodeInto} retires nothing and the arc
     * must be paid for with a face instead: the patch whose remaining boundary is
     * all zero arcs.
     *
     * <p>
     * See also: LCBK19 Section 6.1
     *
     * @param arcId the loop being collapsed
     * @return the patch it pinches away, or {@link #NONE}
     */
    private int pinchedPatchOf(int arcId) {
        EmbeddedArc arc = arcs.get(arcId);
        for (int patchId : new int[] { arc.leftPatchId, arc.rightPatchId }) {
            if (patchId == NONE || !patches.get(patchId).alive) {
                continue;
            }
            List<Integer> remaining = boundaryArcsExcluding(patchId, arcId);
            if (!remaining.isEmpty()
                    && remaining.stream().allMatch(id -> arcs.get(id).quantizedLength == 0)) {
                return patchId;
            }
        }
        return NONE;
    }

    /**
     * The pinched patch's boundary read as a path from the collapsing loop's node
     * back to itself, going round the patch the other way.
     *
     * <p>
     * The arcs come out in cyclic order starting immediately <em>after</em> the
     * loop and wrapping, not in side order; side order would splice a boundary that
     * runs backwards.
     *
     * @param patchId the patch being pinched away
     * @param arcId   the loop being collapsed
     * @return its remaining boundary arcs, ordered from the loop's node round to it
     *         again
     */
    private List<Integer> boundaryPathAround(int patchId, int arcId) {
        List<Integer> cycle = new ArrayList<>();
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            for (int sideArcId : patches.get(patchId).sideArcIds.get(side)) {
                if (arcs.get(sideArcId).alive) {
                    cycle.add(sideArcId);
                }
            }
        }
        int loopIndex = cycle.indexOf(arcId);
        List<Integer> path = new ArrayList<>(cycle.size() - 1);
        for (int step = 1; step < cycle.size(); step++) {
            path.add(cycle.get((loopIndex + step) % cycle.size()));
        }
        return path;
    }

    /**
     * Puts a run of arcs into the slot another arc occupied on a patch's boundary.
     *
     * <p>
     * A side carries one more node than it carries arcs, so replacing one arc with
     * {@code k} of them also inserts the {@code k - 1} nodes they meet at.
     *
     * @param patchId        patch whose boundary is being extended
     * @param oldArcId       arc giving up its slot
     * @param pinchedPatchId patch the replacements are coming from, whose side of
     *                       them is being re-pointed
     * @param replacements   the arcs to put in its place, in boundary order
     */
    private void spliceIntoPatch(int patchId, int oldArcId, int pinchedPatchId,
            List<Integer> replacements) {
        int[] position = sidePosition(patchId, oldArcId);
        List<Integer> sideArcs = patches.get(patchId).sideArcIds.get(position[0]);
        List<Integer> sideNodes = patches.get(patchId).sideNodeIds.get(position[0]);
        sideArcs.remove(position[1]);
        sideArcs.addAll(position[1], replacements);
        markPatchChanged(patchId);
        for (int step = 0; step + 1 < replacements.size(); step++) {
            sideNodes.add(position[1] + 1 + step,
                    sharedNode(replacements.get(step), replacements.get(step + 1)));
        }
        for (int replacementArcId : replacements) {
            EmbeddedArc replacement = arcs.get(replacementArcId);
            if (replacement.leftPatchId == pinchedPatchId) {
                replacement.leftPatchId = patchId;
            } else if (replacement.rightPatchId == pinchedPatchId) {
                replacement.rightPatchId = patchId;
            }
        }
    }

    /**
     * The node two consecutive boundary arcs meet at.
     *
     * @param firstArcId  earlier arc along the boundary
     * @param secondArcId arc following it
     * @throws IllegalStateException when they share no node, so the boundary is not
     *                               a path
     * @return the node they share
     */
    private int sharedNode(int firstArcId, int secondArcId) {
        EmbeddedArc first = arcs.get(firstArcId);
        EmbeddedArc second = arcs.get(secondArcId);
        for (int candidate : new int[] { first.startNodeId, first.endNodeId }) {
            if (candidate == second.startNodeId || candidate == second.endNodeId) {
                return candidate;
            }
        }
        throw new IllegalStateException("arc " + firstArcId + " and " + secondArcId
                + " are consecutive on a patch boundary but share no node");
    }

    /**
     * A patch's live boundary arcs other than one of them.
     *
     * @param patchId patch to read
     * @param arcId   arc to leave out
     * @return the remaining live boundary arcs, in side order
     */
    private List<Integer> boundaryArcsExcluding(int patchId, int arcId) {
        List<Integer> remaining = new ArrayList<>();
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            for (int sideArcId : patches.get(patchId).sideArcIds.get(side)) {
                if (sideArcId != arcId && arcs.get(sideArcId).alive) {
                    remaining.add(sideArcId);
                }
            }
        }
        return remaining;
    }

    /**
     * Retires an arc whose embedding has been abandoned, releasing its mesh claims
     * and dropping it from its nodes' incidence lists. No patch boundary is
     * changed, so the caller must already have re-pointed the patch that used the
     * dying arc onto the survivor.
     *
     * @param arcId arc whose embedding is discarded
     */
    public void discardArc(int arcId) {
        EmbeddedArc arc = arcs.get(arcId);
        releaseClaims(arc);
        arcEndsByNode.get(arc.startNodeId).removeIf(id -> id == arcId);
        arcEndsByNode.get(arc.endNodeId).removeIf(id -> id == arcId);
        arc.alive = false;
        markPatchChanged(arc.leftPatchId);
        markPatchChanged(arc.rightPatchId);
    }

    /**
     * Splits an arc at an interior point of its path, inserting a node there and
     * replacing the arc with the two halves in both of the patches it bounds. The
     * children carry the two halves of the parent's edge path, so both patches see
     * the split at the same vertex.
     *
     * @param arcId           arc to split
     * @param quantizedOffset prescribed length of the first half, measured from the
     *                        arc's start node; the second half takes the remainder
     * @param pathVertexIndex index into the arc's vertex path of the vertex the
     *                        node lands on; must be strictly interior, so that both
     *                        halves are real
     * @throws IllegalStateException when the offset or the vertex would make a half
     *                               empty
     * @return the ids of the two child arcs, in the parent's direction
     */
    public int[] splitArc(int arcId, int quantizedOffset, int pathVertexIndex) {
        EmbeddedArc arc = arcs.get(arcId);
        List<Integer> vertices = arc.path.copyVertexPath;
        if (pathVertexIndex <= 0 || pathVertexIndex >= vertices.size() - 1) {
            throw new IllegalStateException("arc " + arcId + " cannot be split at path vertex "
                    + pathVertexIndex + ": the split must be strictly inside a path of "
                    + vertices.size() + " vertices");
        }
        if (quantizedOffset < 0 || quantizedOffset > arc.quantizedLength) {
            throw new IllegalStateException("arc " + arcId + " cannot be split at offset "
                    + quantizedOffset + ": it lies outside the arc's length "
                    + arc.quantizedLength);
        }
        // The inserted node inherits the split arc's feature status, so it may never be
        // moved
        // off that curve. See also: LCBK19 Section 6.1
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
            markPatchChanged(patchId);
        }
        return new int[] { firstArcId, secondArcId };
    }

    /**
     * The node at a quantized offset along a patch side, splitting an arc to insert
     * one when no node sits there. A single-edge arc is refined first, so the new
     * node has a vertex to sit on.
     *
     * <p>
     * See also: LCBK19 Section 6.1
     *
     * @param patchId patch the side belongs to
     * @param side    side to walk, in {@code [0, 4)}
     * @param offset  quantized offset from the side's start
     * @throws IllegalStateException when the offset lies outside the side
     * @return the node id at that offset
     */
    public int nodeAtOffsetOrSplit(int patchId, int side, int offset) {
        EmbeddedPatch patch = patches.get(patchId);
        List<Integer> sideArcs = patch.sideArcIds.get(side);
        List<Integer> sideNodes = patch.sideNodeIds.get(side);
        int cumulative = 0;
        if (offset == 0) {
            return sideNodes.get(0);
        }
        for (int index = 0; index < sideArcs.size(); index++) {
            int arcId = sideArcs.get(index);
            EmbeddedArc arc = arcs.get(arcId);
            int nextCumulative = cumulative + arc.quantizedLength;
            if (offset == nextCumulative) {
                return sideNodes.get(index + 1);
            }
            if (offset < nextCumulative) {
                int offsetIntoArc = offset - cumulative;
                boolean forward = sideNodes.get(index) == arc.startNodeId;
                int arcOffset = forward ? offsetIntoArc : arc.quantizedLength - offsetIntoArc;
                List<Integer> path = arc.path.copyVertexPath;
                if (path.size() == 2) {
                    int minted = topology.splitEdgeAtParameter(arc.path.copyEdgePath.get(0),
                            EDGE_MIDPOINT);
                    path.add(1, minted);
                    arc.path.copyEdgePath.set(0, topology.edgeBetween(path.get(0), minted));
                    arc.path.copyEdgePath.add(1, topology.edgeBetween(minted, path.get(2)));
                }
                int pathVertexIndex = interiorPathVertexAtFraction(arc,
                        (double) arcOffset / arc.quantizedLength);
                int[] halves = splitArc(arcId, arcOffset, pathVertexIndex);
                return arcs.get(halves[0]).endNodeId;
            }
            cumulative = nextCumulative;
        }
        throw new IllegalStateException("offset " + offset + " lies beyond side " + side
                + " of patch " + patchId);
    }

    /**
     * The interior path vertex of an arc nearest a fraction of its 3D arc length —
     * LCBK19 operator (2) places the split node "at the corresponding location",
     * and 3D arc length is the only intrinsic parameter a rerouted arc still
     * carries.
     *
     * @param arc      arc to split
     * @param fraction fraction of the arc's length, in {@code (0, 1)}
     * @throws IllegalStateException when the arc is collapsed to a point, so it has
     *                               no interior vertex and refining it would mint
     *                               nothing
     * @return the index of the nearest strictly interior path vertex
     */
    private int interiorPathVertexAtFraction(EmbeddedArc arc, double fraction) {
        List<Integer> vertices = arc.path.copyVertexPath;
        if (vertices.size() < 3) {
            throw new IllegalStateException("arc " + arc.arcId + " has a path of "
                    + vertices.size() + " vertices, so it is collapsed and cannot host a"
                    + " split node");
        }
        double[] cumulative = new double[vertices.size()];
        Vector3f here = new Vector3f();
        Vector3f previous = new Vector3f();
        topology.copy.vertexPosition(vertices.get(0), previous);
        for (int index = 1; index < vertices.size(); index++) {
            topology.copy.vertexPosition(vertices.get(index), here);
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
     * Cuts a patch in two along an arc that already runs across it, from a node on
     * one side to a node on the opposite side. The originating patch is retired and
     * the two four-sided halves are added, with the dividing arc bounding both.
     *
     * <p>
     * See also: LCBK19 Section 6.1
     *
     * @param patchId    patch to cut
     * @param dividerArc arc running from a node on one side to a node on the
     *                   opposite side
     * @throws IllegalStateException when the arc's endpoints do not lie on opposite
     *                               sides of the patch's boundary
     * @return the ids of the two halves
     */
    public int[] splitPatchByArc(int patchId, int dividerArc) {
        EmbeddedPatch patch = patches.get(patchId);
        EmbeddedArc divider = arcs.get(dividerArc);
        int[] endA = null;
        int[] endB = null;
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            List<Integer> sideNodes = patch.sideNodeIds.get(side);
            for (int index = 0; index < sideNodes.size() - 1; index++) {
                if (sideNodes.get(index) == divider.startNodeId && endA == null) {
                    endA = new int[] { side, index };
                }
                if (sideNodes.get(index) == divider.endNodeId && endB == null) {
                    endB = new int[] { side, index };
                }
            }
        }
        if ((endA[0] + 2) % EmbeddedPatch.SIDES != endB[0]) {
            throw new IllegalStateException("arc " + dividerArc + " does not divide " + "patch "
                    + patchId + ": its ends lie on sides " + endA[0] + " and " + endB[0]
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
        topology.aliasPatchInto(patchId, firstPatch, patches.size());
        if (topology.patchByCopyFace.length > 0) {
            IntIdList secondFaces = corridor.patchFaces(secondPatch);
            for (int index = 0; index < secondFaces.size(); index++) {
                topology.patchByCopyFace[secondFaces.get(index)] = secondPatch;
            }
        }
        return new int[] { firstPatch, secondPatch };
    }

    /**
     * Swaps one arc for another on a patch's boundary. The two must run between the
     * same nodes, because the patch's node chain is not touched.
     *
     * <p>
     * The surviving arc inherits the neighbour the dying one had.
     *
     * <p>
     * See also: LCBK19 Section 6.1
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
            throw new IllegalStateException("arc " + newArcId + " cannot replace arc " + oldArcId
                    + " in " + "patch " + patchId + ": they run between different nodes");
        }
        int[] position = sidePosition(patchId, oldArcId);
        patches.get(patchId).sideArcIds.get(position[0]).set(position[1], newArcId);
        markPatchChanged(patchId);
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
     * @throws IllegalStateException when the arc is not on that patch's boundary
     * @return the side index and the position within that side
     */
    private int[] sidePosition(int patchId, int arcId) {
        EmbeddedPatch patch = patches.get(patchId);
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            int index = patch.sideArcIds.get(side).indexOf(arcId);
            if (index >= 0) {
                return new int[] { side, index };
            }
        }
        throw new IllegalStateException("arc " + arcId + " is not on the boundary of " + "patch " + patchId);
    }

    /**
     * The connected set of copy vertices reachable from a start through unclaimed
     * edges without passing through a claimed vertex — the arc-walled region a
     * re-route may use.
     *
     * @param startVertex vertex to flood from
     * @return the reachable unclaimed component, including the start
     */
    Set<Integer> unclaimedComponent(int startVertex) {
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
     * Hands back every mesh element an arc holds, so the elements are free for
     * another arc to take. The arc's own nodes keep their vertices: those belong to
     * the nodes, not to the arc.
     *
     * @param arc arc whose claims are released
     */
    private void releaseClaims(EmbeddedArc arc) {
        releaseClaims(arc.path);
    }

    /**
     * Releases the claims of a specific path, used both to free an arc's current
     * embedding and to undo a prefix claimed during a failed re-route back-off.
     *
     * @param path path whose edges and interior vertices are released
     */
    public void releaseClaims(ArcEdgePath path) {
        for (int edgeId : path.copyEdgePath) {
            topology.ownerArcByCopyEdge[edgeId] = EmbeddedMeshTopology.UNCLAIMED;
        }
        List<Integer> vertices = path.copyVertexPath;
        for (int index = 1; index < vertices.size() - 1; index++) {
            topology.ownerArcByCopyVertex[vertices.get(index)] = EmbeddedMeshTopology.UNCLAIMED;
        }
    }

    /**
     * Checks that an end of an arc's path has been re-routed onto the vertex its
     * node is about to move to.
     *
     * @param arc            arc being re-pointed
     * @param pathEndVertex  vertex the arc's path currently ends on
     * @param nodeCopyVertex vertex the node it is being re-pointed at sits on
     * @throws IllegalStateException when the path does not reach the node
     */
    private void requireEndOfPathIsAt(EmbeddedArc arc, int pathEndVertex, int nodeCopyVertex) {
        if (pathEndVertex != nodeCopyVertex) {
            throw new IllegalStateException("arc " + arc.arcId + " was re-pointed at a node on "
                    + "copy vertex " + nodeCopyVertex + " but its path still ends on "
                    + pathEndVertex + "; re-route the arc before merging its node");
        }
    }

    /**
     * Checks the T-mesh is still a cell decomposition of the surface, and throws if
     * it is not.
     *
     * <p>
     * Counting live nodes, arcs and patches, {@code V - E + F} must equal the
     * surface's characteristic. Cheap enough to run after every operator, unlike
     * {@link #validateArcPaths()}. {@code 2 - 2g}
     * 
     * @throws IllegalStateException when the T-mesh is no longer a cell
     *                               decomposition
     */
    public void validate() {
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
                throw new IllegalStateException("arc " + arc.arcId
                        + " has " + "negative quantized length " + arc.quantizedLength);
            }
            if (!nodes.get(arc.startNodeId).alive || !nodes.get(arc.endNodeId).alive) {
                throw new IllegalStateException("arc " + arc.arcId + " ends on a retired node");
            }
            requirePathRunsBetweenItsNodes(arc);
        }

        int livePatches = 0;
        for (EmbeddedPatch patch : patches) {
            if (patch.alive) {
                livePatches++;
                for (int side1 = 0; side1 < EmbeddedPatch.SIDES; side1++) {
                    List<Integer> sideNodes = patch.sideNodeIds.get(side1);
                    List<Integer> sideArcs = patch.sideArcIds.get(side1);
                    if (sideNodes.size() != sideArcs.size() + 1) {
                        throw new IllegalStateException("patch " + patch.patchId + " side " + side1
                                + " has " + sideArcs.size() + " arcs but " + sideNodes.size()
                                + " nodes; a side has one more node than it has arcs");
                    }
                    int nextSide = (side1 + 1) % EmbeddedPatch.SIDES;
                    int endOfThisSide = sideNodes.get(sideNodes.size() - 1);
                    int startOfNextSide = patch.sideNodeIds.get(nextSide).get(0);
                    if (endOfThisSide != startOfNextSide) {
                        throw new IllegalStateException("patch " + patch.patchId + " side " + side1
                                + " ends on node " + endOfThisSide + " but side " + nextSide
                                + " starts on node " + startOfNextSide);
                    }
                }
                for (int side = 0; side < 2; side++) {
                    int here = sideQuantizedLength(patch.patchId, side);
                    int opposite = sideQuantizedLength(patch.patchId, side + 2);
                    if (here != opposite) {
                        throw new IllegalStateException("patch " + patch.patchId
                                + " is not a rectangle: side " + side + " has quantized length " + here
                                + " but the opposite side " + (side + 2) + " has " + opposite);
                    }
                }
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
     * Checks an arc's edge path really does run between its two nodes' vertices,
     * and that every step of it is a real edge of the working copy.
     *
     * @param arc arc to check
     */
    private void requirePathRunsBetweenItsNodes(EmbeddedArc arc) {
        List<Integer> vertices = arc.path.copyVertexPath;
        int expectedStart = nodes.get(arc.startNodeId).copyVertex;
        int expectedEnd = nodes.get(arc.endNodeId).copyVertex;
        if (vertices.get(0) != expectedStart
                || vertices.get(vertices.size() - 1) != expectedEnd) {
            throw new IllegalStateException("arc " + arc.arcId + " path runs from "
                    + vertices.get(0) + " to " + vertices.get(vertices.size() - 1)
                    + " but its nodes sit on " + expectedStart + " and " + expectedEnd);
        }
    }

    /**
     * The edge of the working copy joining two consecutive vertices of an arc's
     * path.
     *
     * <p>
     * An arc that does not walk the mesh is not an embedding of anything, so a
     * missing edge is refused here rather than being discovered later by whatever
     * tries to use the path.
     *
     * @param arcId      arc whose path is being checked, for the message
     * @param fromVertex vertex the step leaves
     * @param toVertex   vertex the step arrives at
     * @throws IllegalStateException when the two vertices are not joined by an edge
     * @return the edge between them
     */
    private int requireEdge(int arcId, int fromVertex, int toVertex) {
        int edgeId = topology.edgeBetween(fromVertex, toVertex);
        if (edgeId == EmbeddedMeshTopology.UNCLAIMED) {
            throw new IllegalStateException("arc " + arcId + " path steps from " + fromVertex
                    + " to " + toVertex + " with no edge between them");
        }
        return edgeId;
    }

    /**
     * Checks the live arcs still cut the copy into exactly the live patches, naming
     * the operator that broke it.
     *
     * @param operator description of the operator just applied
     * @throws IllegalStateException when a region matches no live patch
     */
    public void requireArrangementMatchesPatches(String operator) {
        Set<Integer> boundaryArcs = new HashSet<>();
        List<Integer> unmatched = new PatchRegions(this).findFirstUnmatchedRegion(boundaryArcs);
        if (!unmatched.isEmpty()) {
            throw new IllegalStateException("after " + operator
                    + " the arrangement leaves a region of "
                    + unmatched.size() + " faces bounded by arcs " + boundaryArcs
                    + " that matches no live patch");
        }
    }

    /**
     * Checks every live arc lies between the patches it claims to separate, naming the operator
     * that left one saying otherwise. A drag reads those two ids to bound its search, so an arc
     * that names a patch it no longer touches searches half a region.
     *
     * @param operator description of the operator just applied
     * @throws ArrangementDiagnosticException when an arc's flanks disagree with the covers
     *                                        beside it
     */
    public void requireArcFlanksMatchCovers(String operator) {
        ArrangementDiagnosticException tear = flankTearFailure(operator);
        if (tear != null) {
            throw tear;
        }
    }

    /**
     * Scans every live arc for the first one whose recorded flanks disagree with the covers
     * along its path, packaged as a throwable failure carrying the covers and paths to show.
     *
     * @param operator description of the operator just applied
     * @return the first tear found, or {@code null} when the arrangement is coherent
     */
    public ArrangementDiagnosticException flankTearFailure(String operator) {
        if (topology.patchByCopyFace.length == 0) {
            return null;
        }
        for (EmbeddedArc arc : arcs) {
            if (!arc.alive || arc.path.copyVertexPath.size() < 2) {
                continue;
            }
            int left = topology.resolvePatch(arc.leftPatchId);
            int right = topology.resolvePatch(arc.rightPatchId);
            for (int hop = 0; hop < arc.path.copyVertexPath.size() - 1; hop++) {
                int halfEdge = orientedHopHalfEdge(arc, hop);
                int coverLeft = topology.resolvePatch(
                        topology.patchLabelOf(topology.copy.halfEdgeFace(halfEdge)));
                int coverRight = topology.resolvePatch(topology.patchLabelOf(
                        topology.copy.halfEdgeFace(topology.copy.halfEdgeTwin(halfEdge))));
                if (coverLeft != left || coverRight != right) {
                    return flankTearException(operator, arc, hop, coverLeft, coverRight);
                }
            }
        }
        return null;
    }

    /**
     * Packages one flank tear as message text plus the geometry groups that show it: the named,
     * actual and intact covers, the torn arc, and the boundary between named and actual.
     *
     * @param operator   description of the operator just applied
     * @param arc        arc whose flanks disagree with the covers beside it
     * @param hop        index of the first disagreeing hop along its path
     * @param coverLeft  resolved patch covering the face left of that hop
     * @param coverRight resolved patch covering the face right of that hop
     * @return the throwable failure
     */
    private ArrangementDiagnosticException flankTearException(String operator, EmbeddedArc arc,
            int hop, int coverLeft, int coverRight) {
        int left = topology.resolvePatch(arc.leftPatchId);
        int right = topology.resolvePatch(arc.rightPatchId);
        int besideFace = topology.copy.halfEdgeFace(orientedHopHalfEdge(arc, hop));
        int namedPatchId = coverLeft != left ? left : right;
        int intactPatchId = coverLeft != left ? right : left;
        int actualPatchId = coverLeft != left ? coverLeft : coverRight;
        ArrangementDiagnostic diagnostic = new ArrangementDiagnostic();
        diagnostic.addFaceGroup("named cover", patchCoverFaces(namedPatchId));
        diagnostic.addFaceGroup("actual cover", patchCoverFaces(actualPatchId));
        diagnostic.addFaceGroup("intact cover", patchCoverFaces(intactPatchId));
        diagnostic.addPathGroup("torn arc", arc.path.copyVertexPath);
        diagnostic.addPathGroup("patch boundary",
                boundaryPathBetween(actualPatchId, namedPatchId));
        diagnostic.addMarkerGroup("torn arc start",
                new int[] { arc.path.copyVertexPath.get(0) });
        diagnostic.addMarkerGroup("torn arc end",
                new int[] { arc.path.copyVertexPath.get(arc.path.copyVertexPath.size() - 1) });
        return new ArrangementDiagnosticException("after " + operator + " arc " + arc.arcId
                + " says it separates patches " + left + "|" + right
                + " but the covers beside hop " + hop + " of "
                + (arc.path.copyVertexPath.size() - 1) + " are " + coverLeft + "|"
                + coverRight + "; alive: " + left + "=" + patchAliveText(left)
                + ", " + right + "=" + patchAliveText(right) + ", " + coverLeft
                + "=" + patchAliveText(coverLeft) + ", " + coverRight + "="
                + patchAliveText(coverRight)
                + floodReport(left, besideFace)
                + floodReport(right, besideFace)
                + floodReport(coverLeft, besideFace)
                + floodReport(coverRight, besideFace)
                + sideReport(left) + sideReport(right) + sideReport(coverLeft), diagnostic);
    }

    /**
     * A patch's liveness for the tear message, tolerating an unlabeled cover.
     *
     * @param patchId patch to describe, or {@link #NONE}
     * @return {@code true}/{@code false}, or {@code unlabeled} for {@link #NONE}
     */
    private String patchAliveText(int patchId) {
        return patchId == NONE ? "unlabeled" : String.valueOf(patches.get(patchId).alive);
    }

    /**
     * A patch's cover flood as a plain face-id array, for a diagnostic group; empty when the
     * patch is unlabeled, retired, or has nothing to flood from.
     *
     * @param patchId patch to flood, or {@link #NONE}
     * @return the copy face ids its cover holds
     */
    private int[] patchCoverFaces(int patchId) {
        if (patchId == NONE || !patches.get(patchId).alive
                || !corridor.hasSeedableBoundary(patchId)) {
            return new int[0];
        }
        IntIdList faces = corridor.patchFaces(patchId);
        int[] faceIds = new int[faces.size()];
        for (int index = 0; index < faceIds.length; index++) {
            faceIds[index] = faces.get(index);
        }
        return faceIds;
    }

    /**
     * The embedded path of a boundary arc between two patches, taken from the first patch's
     * sides.
     *
     * @param patchId      patch whose sides are searched
     * @param otherPatchId patch the wanted side arc must flank
     * @return the boundary arc's copy-vertex path, or an empty list when no side arc flanks both
     */
    private List<Integer> boundaryPathBetween(int patchId, int otherPatchId) {
        if (patchId == NONE) {
            return List.of();
        }
        EmbeddedPatch patch = patches.get(patchId);
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            for (int sideArcId : patch.sideArcIds.get(side)) {
                EmbeddedArc sideArc = arcs.get(sideArcId);
                if (topology.resolvePatch(sideArc.leftPatchId) == otherPatchId
                        || topology.resolvePatch(sideArc.rightPatchId) == otherPatchId) {
                    return sideArc.path.copyVertexPath;
                }
            }
        }
        return List.of();
    }

    /**
     * The half-edge of one hop of an arc's path, oriented along the path so its face is the one
     * on the arc's left.
     *
     * @param arc arc whose path is being walked
     * @param hop index of the hop along the path
     * @return the half-edge from the hop's start vertex to its end vertex
     */
    private int orientedHopHalfEdge(EmbeddedArc arc, int hop) {
        int halfEdge = topology.copy.edgeHalfEdge(topology.edgeBetween(
                arc.path.copyVertexPath.get(hop), arc.path.copyVertexPath.get(hop + 1)));
        if (topology.copy.halfEdgeVertex(halfEdge) != arc.path.copyVertexPath.get(hop)) {
            halfEdge = topology.copy.halfEdgeTwin(halfEdge);
        }
        return halfEdge;
    }

    /**
     * How big a patch floods and whether that flood holds a given face, which says whether a
     * disagreement is the arc naming the wrong patch or the labels having been painted over.
     *
     * @param patchId patch to flood
     * @param faceId  face to look for in it
     * @return a one-line description
     */
    private String floodReport(int patchId, int faceId) {
        if (patchId == NONE || !patches.get(patchId).alive
                || !corridor.hasSeedableBoundary(patchId)) {
            return "; patch " + patchId + " floods nothing";
        }
        IntIdList faces = corridor.patchFaces(patchId);
        boolean holdsFace = false;
        for (int index = 0; index < faces.size(); index++) {
            holdsFace |= faces.get(index) == faceId;
        }
        int foreignArcId = corridor.foreignArcOnLastFlood(patchId);
        String leak = "";
        if (foreignArcId != NONE) {
            EmbeddedArc foreignArc = arcs.get(foreignArcId);
            leak = ", flood runs round its side " + foreignArcId + " (patches "
                    + foreignArc.leftPatchId + "|" + foreignArc.rightPatchId + ", path size "
                    + foreignArc.path.copyVertexPath.size() + ", alive=" + foreignArc.alive + ")";
        }
        return "; patch " + patchId + " floods " + faces.size() + " faces, holds " + faceId + "="
                + holdsFace + ", bounded by arcs " + corridor.boundingArcsOfLastFlood()
                + leak;
    }

    /**
     * One patch's boundary arcs with each arc's flanks, liveness and embedded path length, which
     * is what says whether its cover can leak through a side that claims no edges.
     *
     * @param patchId patch whose sides are described
     * @return a one-line description
     */
    private String sideReport(int patchId) {
        if (patchId == NONE) {
            return "";
        }
        StringBuilder text = new StringBuilder("; patch " + patchId + " sides [");
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            for (int sideArcId : patches.get(patchId).sideArcIds.get(side)) {
                EmbeddedArc sideArc = arcs.get(sideArcId);
                int ownedEdges = 0;
                for (int edgeId : sideArc.path.copyEdgePath) {
                    if (edgeId < topology.ownerArcByCopyEdge.length
                            && topology.ownerArcByCopyEdge[edgeId] == sideArcId) {
                        ownedEdges++;
                    }
                }
                text.append(" arc ").append(sideArcId).append('(')
                        .append(sideArc.leftPatchId).append('|').append(sideArc.rightPatchId)
                        .append(", path ").append(sideArc.path.copyVertexPath.size())
                        .append(", owns ").append(ownedEdges).append('/')
                        .append(sideArc.path.copyEdgePath.size())
                        .append(sideArc.alive ? "" : ", dead").append(')');
            }
        }
        return text.append(" ]").toString();
    }

    /**
     * Labels every copy face with the patch covering it, so a re-route can be held
     * to a patch union by id instead of flooding. Unusable covers are dropped,
     * which leaves the re-routes unrestricted.
     */
    public void labelPatchCovers() {
        long startNanos = System.nanoTime();
        int[] labels = new int[topology.sourceFaceByCopyFace.length];
        Arrays.fill(labels, NONE);
        topology.patchByCopyFace = new int[0];
        topology.patchAliasByPatch = new int[patches.size()];
        for (int patchId = 0; patchId < patches.size(); patchId++) {
            topology.patchAliasByPatch[patchId] = patchId;
        }
        int labeledCount = 0;
        int overlapCount = 0;
        for (EmbeddedPatch patch : patches) {
            if (!patch.alive) {
                continue;
            }
            IntIdList faces = corridor.patchFaces(patch.patchId);
            for (int index = 0; index < faces.size(); index++) {
                int copyFace = faces.get(index);
                overlapCount += labels[copyFace] == NONE ? 0 : 1;
                labels[copyFace] = patch.patchId;
                labeledCount++;
            }
        }
        // Patch interiors tile the copy, so a face claimed twice means a flood leaked
        // its patch and every label is then suspect.
        if (overlapCount > 0) {
            topology.patchAliasByPatch = new int[0];
            Platforms.log("[contract] patch covers unusable: %d of %d faces claimed twice%n",
                    overlapCount, topology.copy.faceCount());
            return;
        }
        topology.patchByCopyFace = labels;
        Platforms.log("[contract] patch covers labeled: faces=%d of %d patches=%d | %.3fs%n",
                labeledCount, topology.copy.faceCount(), patches.size(),
                (System.nanoTime() - startNanos) / NANOS_PER_SECOND);
    }

    /**
     * Re-reads one patch's cover from the arrangement, so an operator that moved its boundary
     * leaves the labels saying what a flood of the patch would say. Call only once the
     * arrangement is whole again — mid-collapse cells are transient merges no label fits.
     *
     * @param patchId patch whose boundary moved, live or already absorbed, or {@link #NONE}
     * @throws IllegalStateException when the flood escapes the patch, which means its boundary
     *                               no longer encloses it
     */
    public void relabelPatchCover(int patchId) {
        if (topology.patchByCopyFace.length == 0 || patchId == NONE) {
            return;
        }
        int resolved = topology.resolvePatch(patchId);
        if (resolved == NONE || !patches.get(resolved).alive) {
            return;
        }
        // A patch pinched until every side is a point encloses nothing to read; one whose
        // sides still run along edges must enclose exactly what its boundary walls in.
        if (!corridor.hasSeedableBoundary(resolved)) {
            return;
        }
        long startNanos = System.nanoTime();
        IntIdList faces = corridor.patchFaces(resolved);
        int foreignArc = corridor.foreignArcOnLastFlood(resolved);
        if (foreignArc != NONE) {
            throw new IllegalStateException("the " + faces.size() + " faces flooded for patch "
                    + resolved + " are not its cover: the flood runs round its side " + foreignArc
                    + " (patches " + arcs.get(foreignArc).leftPatchId + "|"
                    + arcs.get(foreignArc).rightPatchId + ") instead of stopping at it — the"
                    + " patch's sides are " + patches.get(resolved).sideArcIds
                    + pointEmbeddedArcReport(foreignArc));
        }
        for (int index = 0; index < faces.size(); index++) {
            topology.patchByCopyFace[faces.get(index)] = resolved;
        }
        relabelCallCount++;
        relabelFacesFlooded += faces.size();
        relabelNanos += System.nanoTime() - startNanos;
    }

    /**
     * The arcs meeting a leaked-past arc's nodes that carry no edges, since an arc embedded as a
     * point merges the two sectors it used to separate and lets a cover flood round it.
     *
     * @param arcId arc a cover flood ran around
     * @return a description of the point-embedded arcs at its ends
     */
    private String pointEmbeddedArcReport(int arcId) {
        StringBuilder detail = new StringBuilder("; point-embedded arcs at its nodes:");
        for (int nodeId : new int[] { arcs.get(arcId).startNodeId, arcs.get(arcId).endNodeId }) {
            for (int incidentArcId : arcEndsByNode.get(nodeId)) {
                EmbeddedArc incidentArc = arcs.get(incidentArcId);
                if (incidentArc.alive && incidentArc.path.copyVertexPath.size() < 2) {
                    detail.append(' ').append(incidentArcId).append("(node ").append(nodeId)
                            .append(')');
                }
            }
        }
        return detail.toString();
    }

    /**
     * Counts how far the working copy has drifted from the source mesh and who owns
     * the difference.
     *
     * @return this, with the density counters filled
     */
    public ArcNetwork measureDensity() {
        mintedVertexCount = 0;
        nodeMintedVertexCount = 0;
        arcMintedVertexCount = 0;
        debrisVertexCount = 0;
        faceGrowthCount = topology.copy.faceCount() - topology.sourceMesh.faceCount();
        for (int index = 0; index < topology.copy.vertexCount(); index++) {
            int copyVertex = topology.copy.vertexIdAt(index);
            if (copyVertex < topology.originalVertexBound) {
                continue;
            }
            mintedVertexCount++;
            if (ownedByLiveNode(copyVertex)) {
                nodeMintedVertexCount++;
            } else if (ownedByLiveArc(copyVertex)) {
                arcMintedVertexCount++;
            } else {
                debrisVertexCount++;
            }
        }
        return this;
    }

    /**
     * Whether a live T-mesh node holds a working-copy vertex.
     *
     * @param copyVertex working-copy vertex to test
     * @return true when its owning node exists and is alive
     */
    private boolean ownedByLiveNode(int copyVertex) {
        if (copyVertex >= topology.ownerNodeByCopyVertex.length) {
            return false;
        }
        int nodeId = topology.ownerNodeByCopyVertex[copyVertex];
        return nodeId != EmbeddedMeshTopology.UNCLAIMED && nodes.get(nodeId).alive;
    }

    /**
     * Whether a live T-mesh arc holds a working-copy vertex.
     *
     * @param copyVertex working-copy vertex to test
     * @return true when its owning arc exists and is alive
     */
    private boolean ownedByLiveArc(int copyVertex) {
        if (copyVertex >= topology.ownerArcByCopyVertex.length) {
            return false;
        }
        int arcId = topology.ownerArcByCopyVertex[copyVertex];
        return arcId != EmbeddedMeshTopology.UNCLAIMED && arcs.get(arcId).alive;
    }

    /**
     * Measures the working copy and prints one {@code [density]} line naming the
     * stage.
     *
     * @param stage pipeline stage the measurement was taken at
     * @return this, measured
     */
    public ArcNetwork reportDensity(String stage) {
        measureDensity();
        Platforms.log("[density] %s: V=%d (source %d, minted %d = %.1f%%) F=%d (source %d,"
                + " +%d) | minted by node=%d arc=%d debris=%d%n",
                stage, topology.copy.vertexCount(), topology.originalVertexBound,
                mintedVertexCount,
                100.0 * mintedVertexCount / Math.max(1, topology.originalVertexBound),
                topology.copy.faceCount(), topology.sourceMesh.faceCount(), faceGrowthCount,
                nodeMintedVertexCount, arcMintedVertexCount, debrisVertexCount);
        return this;
    }

    /**
     * Counts how many live arcs share each source face, which decides whether
     * re-carving that face needs lanes or only a single chord.
     *
     * @return this, with the contention counters filled
     */
    public ArcNetwork measureFaceContention() {
        int sourceFaceCount = topology.sourceMesh.faceCount();
        int[] arcCountBySourceFace = new int[sourceFaceCount];
        int[] lastArcSeenBySourceFace = new int[sourceFaceCount];
        Arrays.fill(lastArcSeenBySourceFace, NONE);
        for (EmbeddedArc arc : arcs) {
            if (!arc.alive) {
                continue;
            }
            List<Integer> path = arc.path.copyVertexPath;
            for (int step = 1; step < path.size(); step++) {
                int sourceFace = topology.sharedSourceFace(path.get(step - 1), path.get(step));
                if (sourceFace == EmbeddedMeshTopology.UNCLAIMED
                        || lastArcSeenBySourceFace[sourceFace] == arc.arcId) {
                    continue;
                }
                lastArcSeenBySourceFace[sourceFace] = arc.arcId;
                arcCountBySourceFace[sourceFace]++;
            }
        }
        untouchedSourceFaceCount = 0;
        singleArcSourceFaceCount = 0;
        twoArcSourceFaceCount = 0;
        crowdedSourceFaceCount = 0;
        mostArcsOnASourceFace = 0;
        worstArcSourceFace = NONE;
        for (int sourceFace = 0; sourceFace < sourceFaceCount; sourceFace++) {
            int count = arcCountBySourceFace[sourceFace];
            untouchedSourceFaceCount += count == 0 ? 1 : 0;
            singleArcSourceFaceCount += count == 1 ? 1 : 0;
            twoArcSourceFaceCount += count == 2 ? 1 : 0;
            crowdedSourceFaceCount += count > 2 ? 1 : 0;
            if (count > mostArcsOnASourceFace) {
                mostArcsOnASourceFace = count;
                worstArcSourceFace = sourceFace;
            }
        }
        measureNodesPerSourceFace(sourceFaceCount);
        return this;
    }

    /**
     * Counts the live node vertices each source face holds, which is how much
     * inserting the nodes subdivides it before any arc is laid.
     *
     * @param sourceFaceCount number of source active faces
     */
    private void measureNodesPerSourceFace(int sourceFaceCount) {
        int[] nodeCountBySourceFace = new int[sourceFaceCount];
        int[] lastNodeSeenBySourceFace = new int[sourceFaceCount];
        Arrays.fill(lastNodeSeenBySourceFace, NONE);
        for (EmbeddedNode node : nodes) {
            if (!node.alive) {
                continue;
            }
            for (int index = 0; index < topology.copy.vertexFaceCount(node.copyVertex); index++) {
                int sourceFace = topology.sourceFaceByCopyFace[topology.copy.vertexFaceAt(node.copyVertex, index)];
                if (sourceFace == EmbeddedMeshTopology.UNCLAIMED
                        || lastNodeSeenBySourceFace[sourceFace] == node.nodeId) {
                    continue;
                }
                lastNodeSeenBySourceFace[sourceFace] = node.nodeId;
                nodeCountBySourceFace[sourceFace]++;
            }
        }
        nodeBearingSourceFaceCount = 0;
        mostNodesOnASourceFace = 0;
        for (int count : nodeCountBySourceFace) {
            nodeBearingSourceFaceCount += count > 0 ? 1 : 0;
            mostNodesOnASourceFace = Math.max(mostNodesOnASourceFace, count);
        }
    }

    /**
     * Measures arc contention and prints one {@code [contention]} line naming the
     * stage.
     *
     * @param stage pipeline stage the measurement was taken at
     * @return this, measured
     */
    public ArcNetwork reportFaceContention(String stage) {
        measureFaceContention();
        Platforms.log("[contention] %s: of %d source faces, %d carry no live arc, %d carry"
                + " one, %d carry two, %d carry three or more (worst face %d with %d)"
                + " | %d faces hold a live node, most %d%n",
                stage, topology.sourceMesh.faceCount(), untouchedSourceFaceCount,
                singleArcSourceFaceCount, twoArcSourceFaceCount, crowdedSourceFaceCount,
                worstArcSourceFace, mostArcsOnASourceFace, nodeBearingSourceFaceCount,
                mostNodesOnASourceFace);
        return this;
    }

}
