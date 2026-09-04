package ixdar.geometry.mesh.quadlayout.extraction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.EdgeKey;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedNode;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.gridmap.GlobalGridMap;
import ixdar.geometry.mesh.quadlayout.gridmap.IntegerGridMap;
import ixdar.geometry.mesh.quadlayout.gridmap.LayoutPatchMaps;
import ixdar.platform.Platforms;

/**
 * Assigns the extracted quad mesh back to the T-mesh layout: nodes to re-fitted
 * quad vertices, arcs to quad edge chains, quads to patches, patches to filled
 * grids. Purely combinatorial, anchored at the pinned critical nodes.
 */
public final class ExtractedPatchGrids {

    /** Ports around a regular quad vertex. */
    private static final int REGULAR_VALENCE = 4;

    /**
     * Port list rotation per clockwise ring step. Constant because the extraction
     * emits every vertex's ports in a fixed surface rotational order.
     */
    private static final int RING_STEP_CLOCKWISE = -1;

    public final ExtractedQuadMesh quadMesh;
    public final GlobalGridMap gridMap;
    public final ArcNetwork tmesh;
    public final IntegerGridMap frames;
    public final LayoutPatchMaps patchMaps;

    /** Grid of each live patch, row-major, indexed by patch id. */
    public Vector3f[][] gridByPatchId;

    /** Quads across each live patch in its first direction, indexed by patch id. */
    public int[] widthByPatchId;

    /**
     * Quads across each live patch in its second direction, indexed by patch id.
     */
    public int[] heightByPatchId;

    /** The layout patch owning each extracted quad. */
    public int[] patchIdByQuad;

    /** The re-fitted quad vertex realizing each live layout node. */
    public int[] quadVertexByNodeId;

    /** Arc-end ports matched directly from the initial rectangle direction. */
    public int directMatchCount;

    /** Arc-end ports completed by walking the node's arc ring. */
    public int ringCompletedCount;

    /**
     * Regular nodes discovered off their original copy vertex, re-fitted by the
     * walk.
     */
    public int refitNodeCount;

    /**
     * Direct matches whose chain walk contradicted the layout, dropped as
     * impostors.
     */
    public int rejectedMatchCount;

    /** Port of each arc end, indexed {@code 2 * arcId} at the start node end. */
    private int[] portByArcEnd;

    /**
     * Quad vertex chain of each live arc from its start node, indexed by arc id.
     */
    private int[][] chainByArc;

    /** Quad id by packed directed corner pair, for the grid strip walk. */
    private final Map<Long, Integer> quadByDirectedEdge = new HashMap<>();

    /** Quad vertex id by copy vertex id, from the extracted mesh's anchors. */
    private final Map<Integer, Integer> quadVertexByCopyVertex = new HashMap<>();

    /**
     * Stores the extracted mesh and the layout to regroup it onto.
     *
     * @param quadMesh extracted quad mesh with clockwise ports
     * @param gridMap  the relaxed grid map the mesh was extracted from
     */
    public ExtractedPatchGrids(ExtractedQuadMesh quadMesh, GlobalGridMap gridMap) {
        this.quadMesh = quadMesh;
        this.gridMap = gridMap;
        this.patchMaps = gridMap.patchMaps;
        this.tmesh = patchMaps.tmesh;
        this.frames = gridMap.frames;
    }

    /**
     * Runs the regrouping and validates every patch grid.
     *
     * @throws IllegalStateException when the layout cannot be matched onto the
     *                               extracted mesh
     * @return this, filled
     */
    public ExtractedPatchGrids build() {
        indexQuadMesh();
        anchorNodes();
        completeAnchoredNodes();
        walkArcs();
        fillPatchGrids();
        Platforms.log("[patch-grids] patches=%d directMatches=%d rejected=%d"
                + " ringCompleted=%d refitNodes=%d%n", livePatchCount(), directMatchCount,
                rejectedMatchCount, ringCompletedCount, refitNodeCount);
        return this;
    }

    /**
     * Live patches in the T-mesh.
     *
     * @return the count
     */
    private int livePatchCount() {
        int count = 0;
        for (EmbeddedPatch patch : tmesh.patches) {
            count += patch.alive ? 1 : 0;
        }
        return count;
    }

    /**
     * Builds the copy-vertex and directed-edge lookups over the extracted mesh, and
     * refuses loop arcs, which the ring walk does not support.
     *
     * @throws IllegalStateException when a live arc is a loop
     */
    private void indexQuadMesh() {
        for (int quadVertex = 0; quadVertex < quadMesh.quadVertexCount; quadVertex++) {
            if (quadMesh.vertexKind[quadVertex] == ExtractedQuadMesh.KIND_MESH_VERTEX) {
                quadVertexByCopyVertex.put(quadMesh.anchorEntityId[quadVertex], quadVertex);
            }
        }
        for (int quad = 0; quad < quadMesh.quadCount; quad++) {
            for (int corner = 0; corner < ExtractedQuadMesh.QUAD_CORNERS; corner++) {
                int from = quadMesh.quadCorner[quad * ExtractedQuadMesh.QUAD_CORNERS + corner];
                int to = quadMesh.quadCorner[quad * ExtractedQuadMesh.QUAD_CORNERS
                        + (corner + 1) % ExtractedQuadMesh.QUAD_CORNERS];
                quadByDirectedEdge.put(EdgeKey.directed(from, to), quad);
            }
        }
        for (EmbeddedArc arc : tmesh.arcs) {
            if (arc.alive && arc.isLoop()) {
                throw new IllegalStateException("arc " + arc.arcId + " is a loop, which the"
                        + " layout regrouping does not support yet");
            }
        }
        portByArcEnd = new int[tmesh.arcs.size() * 2];
        Arrays.fill(portByArcEnd, ExtractedQuadMesh.NONE);
        chainByArc = new int[tmesh.arcs.size()][];
        quadVertexByNodeId = new int[tmesh.nodes.size()];
        Arrays.fill(quadVertexByNodeId, ExtractedQuadMesh.NONE);
        patchIdByQuad = new int[quadMesh.quadCount];
        Arrays.fill(patchIdByQuad, ArcNetwork.NONE);
    }

    /**
     * Anchors nodes to quad vertices and matches arc ends to ports directly: the
     * arc's initial rectangle direction at the node, read in a bounding patch's
     * grid frame, names the port. A non-pinned node only anchors when its quad
     * vertex still sits at its initial grid position.
     *
     * @throws IllegalStateException when a critical node has no quad vertex
     */
    private void anchorNodes() {
        int[] localStart = new int[2];
        int[] localEnd = new int[2];
        int[] gridStart = new int[2];
        int[] gridEnd = new int[2];
        for (EmbeddedNode node : tmesh.nodes) {
            if (!node.alive) {
                continue;
            }
            boolean pinned = node.critical || node.border;
            Integer quadVertex = quadVertexByCopyVertex.get(node.copyVertex);
            if (quadVertex == null) {
                if (pinned) {
                    throw new IllegalStateException("critical node " + node.nodeId + " has no"
                            + " quad vertex at copy vertex " + node.copyVertex);
                }
                continue;
            }
            for (int arcId : tmesh.arcEndsByNode.get(node.nodeId)) {
                EmbeddedArc arc = tmesh.arcs.get(arcId);
                if (!arc.alive) {
                    continue;
                }
                boolean atStart = arc.startNodeId == node.nodeId;
                int arcEnd = arcEndIndex(arcId, atStart);
                if (portByArcEnd[arcEnd] != ExtractedQuadMesh.NONE) {
                    continue;
                }
                for (int side = 0; side < 2; side++) {
                    int patchId = side == 0 ? arc.leftPatchId : arc.rightPatchId;
                    if (patchId == ArcNetwork.NONE || !tmesh.patches.get(patchId).alive
                            || !frames.arcLocalCoordinates(patchId, arcId, localStart,
                                    localEnd)) {
                        continue;
                    }
                    frames.toGrid(patchId, localStart, gridStart);
                    frames.toGrid(patchId, localEnd, gridEnd);
                    int fromU = atStart ? gridStart[0] : gridEnd[0];
                    int fromV = atStart ? gridStart[1] : gridEnd[1];
                    int toU = atStart ? gridEnd[0] : gridStart[0];
                    int toV = atStart ? gridEnd[1] : gridStart[1];
                    Integer dense = gridMap.denseByCopyVertexByPatchId[patchId]
                            .get(node.copyVertex);
                    if (dense == null) {
                        continue;
                    }
                    double[] uv = gridMap.uvByPatchId[patchId];
                    if (!pinned && !(uv[dense * GlobalGridMap.GRID_COORDINATES] == fromU
                            && uv[dense * GlobalGridMap.GRID_COORDINATES + 1] == fromV)) {
                        continue;
                    }
                    int turns = directionTurns(toU - fromU, toV - fromV);
                    int port = findPortInPatch(quadVertex, patchId, turns);
                    if (port != ExtractedQuadMesh.NONE) {
                        if (!chainArrivalConsistent(arc, atStart, port)) {
                            rejectedMatchCount++;
                            continue;
                        }
                        quadVertexByNodeId[node.nodeId] = quadVertex;
                        assignPort(arcEnd, port);
                        directMatchCount++;
                        break;
                    }
                }
            }
            if (pinned) {
                quadVertexByNodeId[node.nodeId] = quadVertex;
            }
        }
    }

    /**
     * The quarter turns of an axis-aligned grid step.
     *
     * @param stepU grid u component
     * @param stepV grid v component
     * @throws IllegalStateException when the step is not axis-aligned
     * @return quarter turns from {@code +u}
     */
    private static int directionTurns(int stepU, int stepV) {
        if (stepV == 0 && stepU != 0) {
            return stepU > 0 ? 0 : 2;
        }
        if (stepU == 0 && stepV != 0) {
            return stepV > 0 ? 1 : IntegerGridMap.QUARTER_TURNS - 1;
        }
        throw new IllegalStateException("arc step (" + stepU + ", " + stepV
                + ") is not axis-aligned in its rectangle");
    }

    /**
     * The port of a quad vertex whose face lies in one patch and holds one
     * direction.
     *
     * @param quadVertex quad vertex whose ports are scanned
     * @param patchId    required patch of the port's face
     * @param turns      required direction as quarter turns in that chart
     * @return the port id, or {@link ExtractedQuadMesh#NONE}
     */
    private int findPortInPatch(int quadVertex, int patchId, int turns) {
        for (int port = quadMesh.portStart[quadVertex]; port < quadMesh.portStart[quadVertex + 1]; port++) {
            Integer facePatch = patchMaps.regions.patchIdByCopyFace
                    .get(quadMesh.portFace[port]);
            if (facePatch != null && facePatch == patchId
                    && quadMesh.portDirectionTurns[port] == turns) {
                return port;
            }
        }
        return ExtractedQuadMesh.NONE;
    }

    /**
     * Whether walking an arc's quad chain from a candidate port stays regular and
     * arrives at the far node's quad vertex when that vertex is known. A relaxed
     * separatrix can leave a vertex through a neighbouring region, so a
     * face-and-direction match can name an impostor; the walk is the arbiter.
     *
     * @param arc     arc whose chain is walked
     * @param atStart whether the walk leaves the arc's start node
     * @param port    candidate port at the walked end
     * @return true when the chain is consistent with the layout
     */
    private boolean chainArrivalConsistent(EmbeddedArc arc, boolean atStart, int port) {
        if (arc.quadCount == 0) {
            return true;
        }
        int current = port;
        int arrival = ExtractedQuadMesh.NONE;
        for (int step = 1; step <= arc.quadCount; step++) {
            arrival = quadMesh.portConnection[current];
            if (arrival == ExtractedQuadMesh.NONE) {
                return false;
            }
            if (step < arc.quadCount) {
                int owner = quadMesh.portOwner[arrival];
                int span = quadMesh.portStart[owner + 1] - quadMesh.portStart[owner];
                if (span != REGULAR_VALENCE) {
                    return false;
                }
                current = clockwiseStep(clockwiseStep(arrival, 1), 1);
            }
        }
        int farNodeId = atStart ? arc.endNodeId : arc.startNodeId;
        int farVertex = quadMesh.portOwner[arrival];
        if (quadVertexByNodeId[farNodeId] != ExtractedQuadMesh.NONE) {
            return quadVertexByNodeId[farNodeId] == farVertex;
        }
        EmbeddedNode farNode = tmesh.nodes.get(farNodeId);
        if (farNode.critical || farNode.border) {
            Integer pinnedVertex = quadVertexByCopyVertex.get(farNode.copyVertex);
            return pinnedVertex != null && pinnedVertex == farVertex;
        }
        return true;
    }

    /**
     * Records an arc end's port, refusing conflicts.
     *
     * @param arcEnd packed arc end index
     * @param port   port realizing the arc's first quad edge at that end
     * @throws IllegalStateException when the end or port is already taken
     */
    private void assignPort(int arcEnd, int port) {
        if (portByArcEnd[arcEnd] != ExtractedQuadMesh.NONE && portByArcEnd[arcEnd] != port) {
            throw new IllegalStateException("arc end " + arcEnd + " already holds port "
                    + portByArcEnd[arcEnd] + ", refusing " + port);
        }
        portByArcEnd[arcEnd] = port;
    }

    /**
     * The packed index of one arc end.
     *
     * @param arcId   the arc
     * @param atStart whether the end at the start node is meant
     * @return the packed index
     */
    private static int arcEndIndex(int arcId, boolean atStart) {
        return arcId * 2 + (atStart ? 0 : 1);
    }

    /**
     * The ring-adjacent arc end at a node, one rotational step from an arc through
     * the patch they bound together.
     *
     * @param nodeId  node whose ring is stepped
     * @param arcId   arc being left
     * @param atStart whether the arc's start-node end sits at the node
     * @throws IllegalStateException when the bounding patch does not hold the pair
     * @return the packed arc end index of the ring neighbour
     */
    private int ringNextArcEnd(int nodeId, int arcId, boolean atStart) {
        EmbeddedArc arc = tmesh.arcs.get(arcId);
        int patchId = atStart ? arc.leftPatchId : arc.rightPatchId;
        EmbeddedPatch patch = tmesh.patches.get(patchId);
        List<Integer> boundaryArcs = new ArrayList<>();
        List<Integer> entryNodes = new ArrayList<>();
        List<Integer> exitNodes = new ArrayList<>();
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            List<Integer> sideArcs = patch.sideArcIds.get(side);
            List<Integer> sideNodes = patch.sideNodeIds.get(side);
            for (int arcIndex = 0; arcIndex < sideArcs.size(); arcIndex++) {
                boundaryArcs.add(sideArcs.get(arcIndex));
                entryNodes.add(sideNodes.get(arcIndex));
                exitNodes.add(sideNodes.get(arcIndex + 1));
            }
        }
        int count = boundaryArcs.size();
        for (int index = 0; index < count; index++) {
            if (boundaryArcs.get(index) != arcId) {
                continue;
            }
            int neighborArcId;
            if (entryNodes.get(index) == nodeId) {
                neighborArcId = boundaryArcs.get((index + count - 1) % count);
            } else if (exitNodes.get(index) == nodeId) {
                neighborArcId = boundaryArcs.get((index + 1) % count);
            } else {
                continue;
            }
            EmbeddedArc neighbor = tmesh.arcs.get(neighborArcId);
            return arcEndIndex(neighborArcId, neighbor.startNodeId == nodeId);
        }
        throw new IllegalStateException("patch " + patchId + " does not hold arc " + arcId
                + " at node " + nodeId);
    }

    /**
     * Completes every anchored node's remaining arc ends by walking its ring from
     * an assigned end, stepping the clockwise port list by the ring orientation.
     */
    private void completeAnchoredNodes() {
        for (EmbeddedNode node : tmesh.nodes) {
            if (node.alive && quadVertexByNodeId[node.nodeId] != ExtractedQuadMesh.NONE
                    && hasAssignedEnd(node.nodeId)) {
                completeNode(node.nodeId);
            }
        }
    }

    /**
     * Whether any of a node's live arc ends already holds a port.
     *
     * @param nodeId node to check
     * @return whether an assigned end exists
     */
    private boolean hasAssignedEnd(int nodeId) {
        for (int arcId : tmesh.arcEndsByNode.get(nodeId)) {
            EmbeddedArc arc = tmesh.arcs.get(arcId);
            if (arc.alive && portByArcEnd[arcEndIndex(arcId,
                    arc.startNodeId == nodeId)] != ExtractedQuadMesh.NONE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Completes one node's arc-end ports by ring propagation from any assigned end.
     *
     * @param nodeId node to complete
     * @throws IllegalStateException when no end is assigned or counts disagree
     */
    private void completeNode(int nodeId) {
        List<Integer> fan = tmesh.arcEndsByNode.get(nodeId);
        int anchorArcId = ArcNetwork.NONE;
        boolean anchorAtStart = false;
        int liveEnds = 0;
        for (int arcId : fan) {
            EmbeddedArc arc = tmesh.arcs.get(arcId);
            if (!arc.alive) {
                continue;
            }
            liveEnds++;
            boolean atStart = arc.startNodeId == nodeId;
            if (anchorArcId == ArcNetwork.NONE
                    && portByArcEnd[arcEndIndex(arcId, atStart)] != ExtractedQuadMesh.NONE) {
                anchorArcId = arcId;
                anchorAtStart = atStart;
            }
        }
        if (anchorArcId == ArcNetwork.NONE) {
            throw new IllegalStateException("node " + nodeId + " has no anchored arc end to"
                    + " walk its ring from");
        }
        int quadVertex = quadVertexByNodeId[nodeId];
        int span = quadMesh.portStart[quadVertex + 1] - quadMesh.portStart[quadVertex];
        if (span != liveEnds) {
            throw new IllegalStateException("node " + nodeId + " has " + liveEnds
                    + " live arc ends but its quad vertex has " + span + " ports");
        }
        int arcId = anchorArcId;
        boolean atStart = anchorAtStart;
        int port = portByArcEnd[arcEndIndex(arcId, atStart)];
        for (int step = 0; step < liveEnds; step++) {
            int nextEnd = ringNextArcEnd(nodeId, arcId, atStart);
            int base = quadMesh.portStart[quadVertex];
            port = base + Math.floorMod(port - base + RING_STEP_CLOCKWISE, span);
            if (portByArcEnd[nextEnd] == ExtractedQuadMesh.NONE) {
                assignPort(nextEnd, port);
                ringCompletedCount++;
            } else if (portByArcEnd[nextEnd] != port) {
                throw new IllegalStateException("ring walk at node " + nodeId + " expects port "
                        + port + " for arc end " + nextEnd + " but it holds "
                        + portByArcEnd[nextEnd]);
            }
            arcId = nextEnd / 2;
            atStart = nextEnd % 2 == 0;
        }
    }

    /**
     * Walks every arc's separatrix from an assigned end for its quantized length,
     * discovering and completing the far node, breadth-first over the layout.
     *
     * @throws IllegalStateException when a walk contradicts an earlier discovery
     */
    private void walkArcs() {
        Deque<Integer> frontier = new ArrayDeque<>();
        for (EmbeddedNode node : tmesh.nodes) {
            if (node.alive && quadVertexByNodeId[node.nodeId] != ExtractedQuadMesh.NONE) {
                frontier.add(node.nodeId);
            }
        }
        while (!frontier.isEmpty()) {
            int nodeId = frontier.removeFirst();
            for (int arcId : tmesh.arcEndsByNode.get(nodeId)) {
                EmbeddedArc arc = tmesh.arcs.get(arcId);
                if (!arc.alive || chainByArc[arcId] != null) {
                    continue;
                }
                boolean atStart = arc.startNodeId == nodeId;
                int port = portByArcEnd[arcEndIndex(arcId, atStart)];
                if (port == ExtractedQuadMesh.NONE) {
                    continue;
                }
                walkArc(arc, atStart, port);
                int farNodeId = atStart ? arc.endNodeId : arc.startNodeId;
                frontier.add(farNodeId);
            }
        }
        for (EmbeddedArc arc : tmesh.arcs) {
            if (arc.alive && chainByArc[arc.arcId] == null) {
                throw new IllegalStateException("arc " + arc.arcId + " was never reached from"
                        + " a critical node; the layout is not anchored");
            }
        }
    }

    /**
     * Walks one arc's chain of quad edges and registers the far node's quad vertex,
     * completing its ring when it is met for the first time.
     *
     * @param arc     arc to walk
     * @param atStart whether the walk leaves the arc's start node
     * @param port    the arc's port at the walked end
     * @throws IllegalStateException when the chain passes a non-regular vertex or
     *                               the far node was discovered elsewhere
     */
    private void walkArc(EmbeddedArc arc, boolean atStart, int port) {
        int[] chain = new int[arc.quadCount + 1];
        chain[0] = quadMesh.portOwner[port];
        int current = port;
        int arrival = ExtractedQuadMesh.NONE;
        for (int step = 1; step <= arc.quadCount; step++) {
            arrival = quadMesh.portConnection[current];
            chain[step] = quadMesh.portOwner[arrival];
            if (step < arc.quadCount) {
                int owner = quadMesh.portOwner[arrival];
                int span = quadMesh.portStart[owner + 1] - quadMesh.portStart[owner];
                if (span != REGULAR_VALENCE) {
                    throw new IllegalStateException("arc " + arc.arcId + " chain passes quad"
                            + " vertex " + owner + " of valence " + span + " at step " + step);
                }
                current = clockwiseStep(clockwiseStep(arrival, 1), 1);
            }
        }
        if (!atStart) {
            for (int low = 0, high = chain.length - 1; low < high; low++, high--) {
                int swap = chain[low];
                chain[low] = chain[high];
                chain[high] = swap;
            }
        }
        chainByArc[arc.arcId] = chain;
        int farNodeId = atStart ? arc.endNodeId : arc.startNodeId;
        int farVertex = quadMesh.portOwner[arrival];
        int farEnd = arcEndIndex(arc.arcId, !atStart);
        if (quadVertexByNodeId[farNodeId] == ExtractedQuadMesh.NONE) {
            quadVertexByNodeId[farNodeId] = farVertex;
            EmbeddedNode farNode = tmesh.nodes.get(farNodeId);
            if (quadVertexByCopyVertex.get(farNode.copyVertex) == null
                    || quadVertexByCopyVertex.get(farNode.copyVertex) != farVertex) {
                refitNodeCount++;
            }
        } else if (quadVertexByNodeId[farNodeId] != farVertex) {
            throw new IllegalStateException("arc " + arc.arcId + " walked to quad vertex "
                    + farVertex + " but node " + farNodeId + " was discovered at "
                    + quadVertexByNodeId[farNodeId]);
        }
        assignPort(farEnd, arrival);
        completeNode(farNodeId);
    }

    /**
     * A cyclic step along a port's owner's clockwise port list.
     *
     * @param port  port stepped from
     * @param steps clockwise steps to take
     * @return the reached port id
     */
    private int clockwiseStep(int port, int steps) {
        int owner = quadMesh.portOwner[port];
        int base = quadMesh.portStart[owner];
        int span = quadMesh.portStart[owner + 1] - base;
        return base + Math.floorMod(port - base + steps, span);
    }

    /**
     * Fills every live patch's grid from its side chains and the quad strips
     * between them, assigning each quad to its patch.
     *
     * @throws IllegalStateException when a strip contradicts the side chains or a
     *                               quad is claimed twice
     */
    private void fillPatchGrids() {
        gridByPatchId = new Vector3f[tmesh.patches.size()][];
        widthByPatchId = new int[tmesh.patches.size()];
        heightByPatchId = new int[tmesh.patches.size()];
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            fillPatchGrid(patch);
        }
        for (int quad = 0; quad < quadMesh.quadCount; quad++) {
            if (patchIdByQuad[quad] == ArcNetwork.NONE) {
                throw new IllegalStateException("quad " + quad + " belongs to no patch");
            }
        }
    }

    /**
     * The quad vertex chain along one patch side, walking the side's arcs in order.
     *
     * @param patch patch whose side is walked
     * @param side  side index in {@code [0, 4)}
     * @return the side's quad vertex ids, corner to corner
     */
    private int[] sideChain(EmbeddedPatch patch, int side) {
        List<Integer> sideArcs = patch.sideArcIds.get(side);
        List<Integer> sideNodes = patch.sideNodeIds.get(side);
        int length = 1;
        for (int arcId : sideArcs) {
            length += tmesh.arcs.get(arcId).quadCount;
        }
        int[] result = new int[length];
        int cursor = 0;
        for (int arcIndex = 0; arcIndex < sideArcs.size(); arcIndex++) {
            EmbeddedArc arc = tmesh.arcs.get(sideArcs.get(arcIndex));
            int[] chain = chainByArc[arc.arcId];
            boolean forward = arc.startNodeId == sideNodes.get(arcIndex);
            for (int step = 0; step <= arc.quadCount; step++) {
                int vertex = chain[forward ? step : arc.quadCount - step];
                if (cursor > 0 && step == 0) {
                    if (result[cursor - 1] != vertex) {
                        throw new IllegalStateException("side " + side + " of patch "
                                + patch.patchId + " breaks between arcs at vertex " + vertex);
                    }
                    continue;
                }
                result[cursor++] = vertex;
            }
        }
        return result;
    }

    /**
     * Fills one patch's grid: the border from its side chains, the interior by
     * walking quad strips row by row, each strip's quads claimed for the patch.
     *
     * @param patch patch to fill
     * @throws IllegalStateException when strips and side chains disagree
     */
    private void fillPatchGrid(EmbeddedPatch patch) {
        int width = tmesh.sideQuadCount(patch.patchId, 0);
        int height = tmesh.sideQuadCount(patch.patchId, 1);
        widthByPatchId[patch.patchId] = width;
        heightByPatchId[patch.patchId] = height;
        int columns = width + 1;
        int rows = height + 1;
        int[] vertexGrid = new int[columns * rows];
        Arrays.fill(vertexGrid, ExtractedQuadMesh.NONE);
        int[] bottom = sideChain(patch, 0);
        int[] right = sideChain(patch, 1);
        int[] top = sideChain(patch, 2);
        int[] left = sideChain(patch, 3);
        for (int column = 0; column < columns; column++) {
            vertexGrid[column] = bottom[column];
            vertexGrid[(rows - 1) * columns + (columns - 1 - column)] = top[column];
        }
        for (int row = 0; row < rows; row++) {
            vertexGrid[row * columns + columns - 1] = right[row];
            vertexGrid[(rows - 1 - row) * columns] = left[row];
        }
        boolean forwardStrips = quadByDirectedEdge
                .containsKey(EdgeKey.directed(vertexGrid[0], vertexGrid[1]))
                && stripMatches(vertexGrid, 0, columns, true);
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int from = vertexGrid[row * columns + column];
                int to = vertexGrid[row * columns + column + 1];
                Integer quad = quadByDirectedEdge.get(forwardStrips ? EdgeKey.directed(from, to)
                        : EdgeKey.directed(to, from));
                if (quad == null) {
                    throw new IllegalStateException("patch " + patch.patchId + " row " + row
                            + " column " + column + " has no quad over its edge");
                }
                if (patchIdByQuad[quad] != ArcNetwork.NONE
                        && patchIdByQuad[quad] != patch.patchId) {
                    throw new IllegalStateException("quad " + quad + " is claimed by patches "
                            + patchIdByQuad[quad] + " and " + patch.patchId);
                }
                patchIdByQuad[quad] = patch.patchId;
                int[] opposite = oppositeCorners(quad, forwardStrips ? from : to,
                        forwardStrips ? to : from);
                int aboveFrom = forwardStrips ? opposite[1] : opposite[0];
                int aboveTo = forwardStrips ? opposite[0] : opposite[1];
                setGridVertex(vertexGrid, (row + 1) * columns + column, aboveFrom, patch);
                setGridVertex(vertexGrid, (row + 1) * columns + column + 1, aboveTo, patch);
            }
        }
        Vector3f[] grid = new Vector3f[columns * rows];
        for (int index = 0; index < grid.length; index++) {
            int vertex = vertexGrid[index];
            grid[index] = new Vector3f(
                    quadMesh.positions[vertex * ExtractedQuadMesh.POSITION_FLOATS],
                    quadMesh.positions[vertex * ExtractedQuadMesh.POSITION_FLOATS + 1],
                    quadMesh.positions[vertex * ExtractedQuadMesh.POSITION_FLOATS + 2]);
        }
        gridByPatchId[patch.patchId] = grid;
    }

    /**
     * Whether the first strip above the bottom side, walked with the given edge
     * direction, lands on the left side chain's second vertex.
     *
     * @param vertexGrid the grid with its border filled
     * @param row        row the strip sits on
     * @param columns    the grid's column count
     * @param forward    whether the strip reads edges bottom-forward
     * @return whether the strip's first above-corner matches the border
     */
    private boolean stripMatches(int[] vertexGrid, int row, int columns, boolean forward) {
        int from = vertexGrid[row * columns];
        int to = vertexGrid[row * columns + 1];
        Integer quad = quadByDirectedEdge.get(forward ? EdgeKey.directed(from, to)
                : EdgeKey.directed(to, from));
        if (quad == null) {
            return false;
        }
        int[] opposite = oppositeCorners(quad, forward ? from : to, forward ? to : from);
        return (forward ? opposite[1] : opposite[0]) == vertexGrid[(row + 1) * columns];
    }

    /**
     * The two corners of a quad opposite a directed corner pair, in cycle order
     * after the pair.
     *
     * @param quad the quad
     * @param from corner the pair walks from
     * @param to   corner the pair walks to
     * @throws IllegalStateException when the pair is not consecutive in the quad
     * @return the following two corners in cycle order
     */
    private int[] oppositeCorners(int quad, int from, int to) {
        for (int corner = 0; corner < ExtractedQuadMesh.QUAD_CORNERS; corner++) {
            int base = quad * ExtractedQuadMesh.QUAD_CORNERS;
            if (quadMesh.quadCorner[base + corner] == from && quadMesh.quadCorner[base
                    + (corner + 1) % ExtractedQuadMesh.QUAD_CORNERS] == to) {
                return new int[] {
                        quadMesh.quadCorner[base + (corner + 2) % ExtractedQuadMesh.QUAD_CORNERS],
                        quadMesh.quadCorner[base + (corner + 3)
                                % ExtractedQuadMesh.QUAD_CORNERS] };
            }
        }
        throw new IllegalStateException("quad " + quad + " does not walk corners " + from
                + " -> " + to);
    }

    /**
     * Writes one grid site, requiring agreement with anything already there.
     *
     * @param vertexGrid the grid being filled
     * @param index      row-major site index
     * @param vertex     quad vertex to place
     * @param patch      patch being filled, for the message
     * @throws IllegalStateException when the site already holds a different vertex
     */
    private void setGridVertex(int[] vertexGrid, int index, int vertex, EmbeddedPatch patch) {
        if (vertexGrid[index] == ExtractedQuadMesh.NONE) {
            vertexGrid[index] = vertex;
        } else if (vertexGrid[index] != vertex) {
            throw new IllegalStateException("patch " + patch.patchId + " grid site " + index
                    + " holds vertex " + vertexGrid[index] + " but the strip walk found "
                    + vertex);
        }
    }
}
