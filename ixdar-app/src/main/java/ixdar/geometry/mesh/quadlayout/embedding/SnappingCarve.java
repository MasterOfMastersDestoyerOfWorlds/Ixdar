package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.quadlayout.embedding.records.ArcEdgePath;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TMeshNode;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.Trace;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceArc;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceSegment;

/**
 * Embeds every layout arc as one chosen vertex per crossed edge of the
 * constraint mesh, joined passage by passage by chords that mint nothing.
 *
 * <p>
 * See also: LCBK19 Section 6.1
 */
public final class SnappingCarve {

    /** Corners (and edges) of a triangle. */
    public static final int CORNERS = 3;

    /** Midpoint of an edge, which side of it an arc's last crossing prefers. */
    private static final double MIDPOINT = 0.5;

    /** Slack allowed when a rebuilt barycentric triple is checked to sum to one. */
    private static final double BARYCENTRIC_TOLERANCE = 1.0e-9;

    /**
     * Traced graph the routes come from, or {@code null} when the caller supplied
     * the routes itself — the re-carve reads them off a contracted layout instead.
     */
    public final MotorcycleGraph motorcycleGraph;

    /** Working copy the arcs are embedded into. */
    public final EmbeddedMeshTopology topology;

    /** T-mesh nodes the carve places, for the report. */
    public int nodeCount;

    /** Node each arc runs from, indexed by arc id. */
    public int[] startNodeByArc;

    /** Node each arc runs to, indexed by arc id. */
    public int[] endNodeByArc;

    /**
     * Copy vertex per T-mesh node id, or {@link EmbeddedMeshTopology#UNCLAIMED}.
     */
    public int[] vertexIdByNode;

    /** Nodes that claimed an existing mesh vertex outright. */
    public int nodesOnVertexCount;

    /** Nodes placed by splitting the edge they lie on. */
    public int nodesByEdgeSplitCount;

    /** Nodes placed by splitting the face they lie inside. */
    public int nodesByFaceSplitCount;

    /**
     * Nodes that landed within {@link FaceChordWalk#MINIMUM_SEPARATION} of a corner
     * of the child holding them and took it, rather than cutting a sliver beside
     * it.
     */
    public int nodesSnappedToCornerCount;

    /**
     * Vertices of the constraint mesh, the working copy once the nodes are placed.
     */
    public int constraintVertexCount;

    /** Faces of the constraint mesh. */
    public int constraintFaceCount;

    /** Each arc's route refined onto the constraint mesh, indexed by arc id. */
    public List<FaceStripPath> stripByArc;

    /** Copy vertex the arc takes at each of its crossings, indexed by arc id. */
    public List<List<Integer>> chosenVertexByArc;

    /** Embedded path per arc id; null for an arc that crosses no face. */
    public ArcEdgePath[] pathByArc;

    /**
     * Crossings on each constraint edge as {@code {arcId, crossingIndex}}, keyed by
     * edge.
     */
    public final Map<Long, List<int[]>> crossingsByEdge = new HashMap<>();

    /**
     * How many arc passages each source active face carries, indexed by active
     * face.
     */
    public int[] passageCountBySourceFace;

    /** Source faces more than one arc passes through. */
    public int contestedFaceCount;

    /** Most passages any one source face carries. */
    public int mostPassagesOnAFace;

    /** Crossings served by an existing corner of the edge, minting nothing. */
    public int cornersGrantedCount;

    /** Crossings served by a vertex minted on the edge. */
    public int lanesMintedCount;

    /**
     * Corners handed back because the arc had already taken them at an earlier
     * crossing.
     */
    public int repeatedCornerReleaseCount;

    /** Most lanes minted on any one constraint edge. */
    public int mostLanesOnAnEdge;

    /** Chords that had to be laid down through a passage. */
    public int chordsInsertedCount;

    /** Chords an edge of the working copy already provided. */
    public int chordsAlreadyPresentCount;

    /**
     * Copy vertex count before any chord was laid, which laying them must not
     * change.
     */
    public int verticesBeforeChords;

    /**
     * Copy face count before any chord was laid, which laying them must not change.
     */
    public int facesBeforeChords;

    /** Cross-field active index per source edge id, for tagging fragments. */
    public Map<Integer, Integer> activeEdgeBySourceEdge;

    /**
     * Stores the traced graph and builds the working copy the arcs are embedded
     * into.
     *
     * @param motorcycleGraph traced T-mesh whose arcs are embedded
     */
    public SnappingCarve(MotorcycleGraph motorcycleGraph) {
        this.motorcycleGraph = motorcycleGraph;
        this.topology = new EmbeddedMeshTopology(motorcycleGraph.seamless.mesh);
    }

    /**
     * Stores a working copy whose nodes and routes the caller places and refines
     * itself, then runs {@link #carve()} over them. The re-carve enters this way.
     *
     * @param topology working copy, standing as the constraint mesh
     */
    public SnappingCarve(EmbeddedMeshTopology topology) {
        this.motorcycleGraph = null;
        this.topology = topology;
    }

    /**
     * Gives every T-mesh node a copy vertex, splitting eagerly. Afterwards the
     * working copy is the constraint mesh: every node is one of its corners, so no
     * face of it holds a vertex in its interior.
     *
     * @throws IllegalStateException when two nodes land on one copy vertex
     * @return this, with the nodes placed
     */
    public SnappingCarve placeNodes() {
        nodeCount = motorcycleGraph.nodes.size();
        vertexIdByNode = new int[motorcycleGraph.nodes.size()];
        Arrays.fill(vertexIdByNode, EmbeddedMeshTopology.UNCLAIMED);
        for (TMeshNode node : motorcycleGraph.nodes) {
            int copyVertex = node.vertexId >= 0
                    ? claimSourceVertex(node)
                    : mintInside(node, chartBarycentric(node));
            int owner = topology.ownerNodeByCopyVertex[copyVertex];
            if (owner != EmbeddedMeshTopology.UNCLAIMED && owner != node.nodeId) {
                throw new IllegalStateException("T-mesh nodes " + owner + " and " + node.nodeId
                        + " both landed on copy vertex " + copyVertex
                        + "; one mesh vertex never owns two T-mesh nodes");
            }
            topology.ownerNodeByCopyVertex[copyVertex] = node.nodeId;
            vertexIdByNode[node.nodeId] = copyVertex;
        }
        constraintVertexCount = topology.copy.vertexCount();
        constraintFaceCount = topology.copy.faceCount();
        return this;
    }

    /**
     * Refines every arc's traced route onto the constraint mesh. An arc is the
     * parametric slice {@code [chainNodeLengths[k], chainNodeLengths[k+1])} of its
     * trace, so its passages are the trace segments overlapping that slice, each
     * cut further at the edges the node vertices introduced.
     *
     * @return this, with {@link #stripByArc} filled
     */
    public SnappingCarve sliceArcs() {
        stripByArc = new ArrayList<>(motorcycleGraph.arcs.size());
        startNodeByArc = new int[motorcycleGraph.arcs.size()];
        endNodeByArc = new int[motorcycleGraph.arcs.size()];
        for (int arc = 0; arc < motorcycleGraph.arcs.size(); arc++) {
            stripByArc.add(new FaceStripPath(topology, arc));
            startNodeByArc[arc] = motorcycleGraph.arcs.get(arc).startNodeId;
            endNodeByArc[arc] = motorcycleGraph.arcs.get(arc).endNodeId;
        }
        for (Trace trace : motorcycleGraph.traces) {
            for (int step = 0; step < trace.chainArcIds.size(); step++) {
                sliceArc(trace, step);
            }
        }
        passageCountBySourceFace = new int[motorcycleGraph.seamless.crossField.faceCount];
        for (FaceStripPath strip : stripByArc) {
            for (int sourceFace : strip.passageSourceFaces) {
                passageCountBySourceFace[sourceFace]++;
            }
        }
        for (int count : passageCountBySourceFace) {
            contestedFaceCount += count > 1 ? 1 : 0;
            mostPassagesOnAFace = Math.max(mostPassagesOnAFace, count);
        }
        return this;
    }

    /**
     * Walks one arc's trace segments in order, handing each to its strip as a
     * passage from where the arc entered that face to where it leaves it.
     *
     * @param trace trace carrying the arc
     * @param step  index of the arc within the trace's chain
     */
    private void sliceArc(Trace trace, int step) {
        int arcId = trace.chainArcIds.get(step);
        double from = trace.chainNodeLengths.get(step);
        double to = trace.chainNodeLengths.get(step + 1);
        List<TraceSegment> crossed = new ArrayList<>();
        for (TraceSegment segment : trace.segments) {
            double entry = segment.parametricLengthAtEntry;
            if (entry < to && entry + segment.parametricLength() > from) {
                crossed.add(segment);
            }
        }
        if (crossed.isEmpty()) {
            return;
        }
        TraceArc arc = motorcycleGraph.arcs.get(arcId);
        FaceStripPath strip = stripByArc.get(arcId);
        double[] entryPoint = nodeBarycentric(arc.startNodeId, crossed.get(0).activeFace);
        for (int index = 0; index < crossed.size(); index++) {
            TraceSegment segment = crossed.get(index);
            boolean last = index + 1 == crossed.size();
            strip.addPassage(segment.activeFace, entryPoint, last
                    ? nodeBarycentric(arc.endNodeId, segment.activeFace)
                    : exitBarycentric(segment, segment.activeFace));
            if (!last) {
                entryPoint = exitBarycentric(segment, crossed.get(index + 1).activeFace);
            }
        }
    }

    /**
     * A T-mesh node's position as a barycentric of a source face, read from the
     * coordinate its vertex was registered with. A node on a mesh vertex has no
     * face of its own, so asking its vertex rather than its chart position covers
     * both kinds at once.
     *
     * @param nodeId     node whose position is wanted
     * @param sourceFace source active face it must be measured in
     * @throws IllegalStateException when the node's vertex has no coordinate there
     * @return its barycentric triple
     */
    private double[] nodeBarycentric(int nodeId, int sourceFace) {
        double[] barycentric = topology.barycentricOf(sourceFace, vertexIdByNode[nodeId]);
        if (barycentric == null) {
            throw new IllegalStateException("T-mesh node " + nodeId + " has no barycentric in"
                    + " source face " + sourceFace + ", where an arc reaches it");
        }
        return barycentric;
    }

    /**
     * Where a trace segment leaves its face, as a barycentric of whichever face is
     * asked for. Both faces sharing that edge can read the point, which is what
     * lets one passage end exactly where the next begins.
     *
     * @param segment segment whose recorded exit is converted
     * @param inFace  source active face to measure it in
     * @throws IllegalStateException when that face does not hold the crossed edge
     * @return its barycentric triple there
     */
    private double[] exitBarycentric(TraceSegment segment, int inFace) {
        int sourceFaceId = topology.sourceMesh.faceIdAt(segment.activeFace);
        int firstVertex = topology.sourceMesh.faceVertexAt(sourceFaceId,
                segment.exitLocalEdgeIndex);
        int secondVertex = topology.sourceMesh.faceVertexAt(sourceFaceId,
                (segment.exitLocalEdgeIndex + 1) % CORNERS);
        int inFaceId = topology.sourceMesh.faceIdAt(inFace);
        double[] barycentric = new double[CORNERS];
        double total = 0.0;
        for (int corner = 0; corner < CORNERS; corner++) {
            int vertexId = topology.sourceMesh.faceVertexAt(inFaceId, corner);
            barycentric[corner] = vertexId == firstVertex ? 1.0 - segment.exitEdgeParameter
                    : vertexId == secondVertex ? segment.exitEdgeParameter : 0.0;
            total += barycentric[corner];
        }
        if (Math.abs(total - 1.0) > BARYCENTRIC_TOLERANCE) {
            throw new IllegalStateException("source face " + inFace + " does not hold the edge"
                    + " a trace segment of source face " + segment.activeFace + " leaves by");
        }
        return barycentric;
    }

    /**
     * Tags every copy edge with the source edge it descends from, so a split edge's
     * fragments can still be recognised as lying on it.
     *
     * @return this, tagged
     */
    public SnappingCarve tagSourceEdges() {
        activeEdgeBySourceEdge = motorcycleGraph.seamless.crossField.edgeIdToActive;
        for (Map.Entry<Integer, Integer> entry : activeEdgeBySourceEdge.entrySet()) {
            int halfEdge = topology.sourceMesh.edgeHalfEdge(entry.getKey());
            int copyA = topology.copyVertexForSourceVertexId(
                    topology.sourceMesh.halfEdgeVertex(halfEdge));
            int copyB = topology.copyVertexForSourceVertexId(
                    topology.sourceMesh.halfEdgeEndVertex(halfEdge));
            int copyEdge = topology.edgeBetween(copyA, copyB);
            if (copyEdge != EmbeddedMeshTopology.UNCLAIMED) {
                topology.sourceEdgeByCopyEdge[copyEdge] = entry.getValue();
            }
        }
        return this;
    }

    /**
     * Runs the carve: one vertex chosen per crossing, minted only where no corner
     * is to be had, then one chord laid per passage.
     *
     * @return this, carved
     */
    public SnappingCarve carve() {
        collectCrossings();
        grantCorners();
        releaseRepeatedCorners();
        mintLanes();
        layChords();
        return this;
    }

    /**
     * Takes back a corner an arc chose at two crossings that are not consecutive,
     * so its path cannot revisit that vertex and pinch a region off the layout. A
     * crossing the route runs exactly through keeps its vertex: it is on no edge,
     * so no lane can replace it.
     */
    private void releaseRepeatedCorners() {
        Map<Integer, Integer> lastCrossingByVertex = new HashMap<>();
        for (int arcId = 0; arcId < chosenVertexByArc.size(); arcId++) {
            List<Integer> chosen = chosenVertexByArc.get(arcId);
            FaceStripPath strip = stripByArc.get(arcId);
            lastCrossingByVertex.clear();
            for (int crossing = 0; crossing < chosen.size(); crossing++) {
                int vertex = chosen.get(crossing);
                if (vertex == EmbeddedMeshTopology.UNCLAIMED) {
                    continue;
                }
                Integer previous = lastCrossingByVertex.get(vertex);
                if (previous != null && previous < crossing - 1
                        && strip.crossedEdges.get(crossing) != null) {
                    chosen.set(crossing, EmbeddedMeshTopology.UNCLAIMED);
                    repeatedCornerReleaseCount++;
                    continue;
                }
                lastCrossingByVertex.put(vertex, crossing);
            }
        }
    }

    /**
     * Gathers the crossings each constraint edge carries, which is what the
     * assignment along that edge is ordered over.
     */
    private void collectCrossings() {
        chosenVertexByArc = new ArrayList<>(stripByArc.size());
        for (FaceStripPath strip : stripByArc) {
            List<Integer> chosen = new ArrayList<>(strip.crossedEdges.size());
            for (int crossing = 0; crossing < strip.crossedEdges.size(); crossing++) {
                if (strip.crossedEdges.get(crossing) == null) {
                    chosen.add(claimPassedVertex(strip.arcId, strip.crossedVertices.get(crossing)));
                    continue;
                }
                chosen.add(EmbeddedMeshTopology.UNCLAIMED);
                crossingsByEdge.computeIfAbsent(edgeKey(strip.crossedEdges.get(crossing)),
                        key -> new ArrayList<>()).add(new int[] { strip.arcId, crossing });
            }
            chosenVertexByArc.add(chosen);
        }
    }

    /**
     * Takes the vertex an arc's route runs exactly through. No lane can be minted
     * for such a crossing, so a vertex another element already holds is a tear, not
     * a contention.
     *
     * @param arcId      arc whose route passes through the vertex
     * @param copyVertex the vertex it passes through
     * @throws IllegalStateException when a node or another arc already holds it
     * @return the same vertex, now claimed
     */
    private int claimPassedVertex(int arcId, int copyVertex) {
        int nodeOwner = topology.ownerNodeByCopyVertex[copyVertex];
        int arcOwner = topology.ownerArcByCopyVertex[copyVertex];
        if (nodeOwner != EmbeddedMeshTopology.UNCLAIMED
                && (vertexIdByNode[startNodeByArc[arcId]] == copyVertex
                        || vertexIdByNode[endNodeByArc[arcId]] == copyVertex)) {
            cornersGrantedCount++;
            return copyVertex;
        }
        if (nodeOwner != EmbeddedMeshTopology.UNCLAIMED
                || arcOwner != EmbeddedMeshTopology.UNCLAIMED && arcOwner != arcId) {
            throw new IllegalStateException("arc " + arcId + " runs exactly through copy vertex "
                    + copyVertex + (copyVertex < topology.originalVertexBound ? " (an original"
                            + " mesh vertex)" : " (minted)")
                    + ", which node " + nodeOwner
                    + " / arc " + arcOwner
                    + " already holds; two T-mesh elements cannot share one mesh vertex");
        }
        topology.ownerArcByCopyVertex[copyVertex] = arcId;
        cornersGrantedCount++;
        return copyVertex;
    }

    /**
     * Gives an existing corner of a constraint edge to the crossings at either end
     * of its traced order, which are the only two that can take one without the
     * assignment along the edge ceasing to be monotone and the chords in a face
     * crossing each other.
     */
    private void grantCorners() {
        for (Map.Entry<Long, List<int[]>> entry : crossingsByEdge.entrySet()) {
            List<int[]> crossings = entry.getValue();
            crossings.sort(Comparator.comparingDouble(this::tracedPositionOf));
            int[] endpoints = stripByArc.get(crossings.get(0)[0]).crossedEdges
                    .get(crossings.get(0)[1]);
            grantCorner(crossings.get(0), endpoints[0]);
            grantCorner(crossings.get(crossings.size() - 1), endpoints[1]);
        }
    }

    /**
     * Gives one crossing a corner, when that is the corner its route prefers and no
     * node or other arc holds it.
     *
     * @param crossing the crossing as {@code {arcId, crossingIndex}}
     * @param corner   copy vertex of the corner on offer
     */
    private void grantCorner(int[] crossing, int corner) {
        List<Integer> chosen = chosenVertexByArc.get(crossing[0]);
        if (chosen.get(crossing[1]) != EmbeddedMeshTopology.UNCLAIMED
                || preferredCorner(crossing) != corner
                || topology.ownerNodeByCopyVertex[corner] != EmbeddedMeshTopology.UNCLAIMED
                || topology.ownerArcByCopyVertex[corner] != EmbeddedMeshTopology.UNCLAIMED
                        && topology.ownerArcByCopyVertex[corner] != crossing[0]) {
            return;
        }
        topology.ownerArcByCopyVertex[corner] = crossing[0];
        chosen.set(crossing[1], corner);
        cornersGrantedCount++;
    }

    /**
     * The corner an arc would rather take at one crossing: the one shared with its
     * next crossed edge, or at its last crossing the end its traced route passes
     * nearer.
     *
     * @param crossing the crossing as {@code {arcId, crossingIndex}}
     * @return that corner's copy vertex
     */
    private int preferredCorner(int[] crossing) {
        FaceStripPath strip = stripByArc.get(crossing[0]);
        int shared = strip.sharedCornerAt(crossing[1]);
        if (shared != EmbeddedMeshTopology.UNCLAIMED) {
            return shared;
        }
        int[] endpoints = strip.crossedEdges.get(crossing[1]);
        return tracedPositionOf(crossing) < MIDPOINT ? endpoints[0] : endpoints[1];
    }

    /**
     * Mints a vertex on each constraint edge for every crossing no corner could
     * serve, evenly spaced so the smallest fragment is {@code 1/(demand+1)}
     * whatever the traced positions were, and hands them out in traced order so the
     * assignment stays monotone.
     */
    private void mintLanes() {
        for (Map.Entry<Long, List<int[]>> entry : crossingsByEdge.entrySet()) {
            List<int[]> demanding = new ArrayList<>();
            for (int[] crossing : entry.getValue()) {
                if (chosenVertexByArc.get(crossing[0]).get(crossing[1]) == EmbeddedMeshTopology.UNCLAIMED) {
                    demanding.add(crossing);
                }
            }
            if (demanding.isEmpty()) {
                continue;
            }
            int[] endpoints = stripByArc.get(demanding.get(0)[0]).crossedEdges
                    .get(demanding.get(0)[1]);
            List<Integer> lanes = splitEdgeIntoLanes(endpoints[0], endpoints[1],
                    demanding.size());
            for (int index = 0; index < demanding.size(); index++) {
                int[] crossing = demanding.get(index);
                chosenVertexByArc.get(crossing[0]).set(crossing[1], lanes.get(index));
                topology.ownerArcByCopyVertex[lanes.get(index)] = crossing[0];
            }
            lanesMintedCount += lanes.size();
            mostLanesOnAnEdge = Math.max(mostLanesOnAnEdge, lanes.size());
        }
    }

    /**
     * Splits one constraint edge into {@code count + 1} uniform fragments, minting
     * a carve point at each {@code i/(count+1)}.
     *
     * @param fromVertex copy vertex the edge runs from, which positions are
     *                   measured from
     * @param toVertex   copy vertex it runs to
     * @param count      carve points to place along it
     * @throws IllegalStateException when the edge is already broken, so its
     *                               fragments would not divide evenly
     * @return the minted copy vertices, ordered from {@code fromVertex}
     */
    public List<Integer> splitEdgeIntoLanes(int fromVertex, int toVertex, int count) {
        int head = fromVertex;
        List<Integer> lanes = new ArrayList<>(count);
        double placed = 0.0;
        for (int lane = 1; lane <= count; lane++) {
            int fragment = topology.edgeBetween(head, toVertex);
            if (fragment == EmbeddedMeshTopology.UNCLAIMED) {
                throw new IllegalStateException("the constraint edge from copy vertex "
                        + fromVertex + " to " + toVertex + " was already broken, so its lanes"
                        + " cannot be spaced evenly");
            }
            double target = lane / (count + 1.0);
            double local = (target - placed) / (1.0 - placed);
            int canonicalStart = topology.copy.halfEdgeVertex(
                    topology.copy.edgeHalfEdge(fragment));
            lanes.add(topology.splitEdgeAtParameter(fragment,
                    canonicalStart == head ? local : 1.0 - local));
            head = lanes.get(lanes.size() - 1);
            placed = target;
        }
        return lanes;
    }

    /**
     * Joins each arc's chosen vertices passage by passage, one chord each. Chords
     * mint no vertex and rebuild every crossed strip into as many triangles as it
     * had, so neither count moves from here on.
     */
    private void layChords() {
        verticesBeforeChords = topology.copy.vertexCount();
        facesBeforeChords = topology.copy.faceCount();
        pathByArc = new ArcEdgePath[stripByArc.size()];
        for (int arcId = 0; arcId < stripByArc.size(); arcId++) {
            FaceStripPath strip = stripByArc.get(arcId);
            if (strip.passageFaces.isEmpty()) {
                continue;
            }
            List<Integer> chosen = chosenVertexByArc.get(arcId);
            List<Integer> path = new ArrayList<>();
            int previous = vertexIdByNode[startNodeByArc[arcId]];
            path.add(previous);
            for (int crossing = 0; crossing < chosen.size(); crossing++) {
                if (chosen.get(crossing) != previous) {
                    appendChord(path, strip.passageSourceFaces.get(crossing), previous,
                            chosen.get(crossing), arcId);
                    previous = chosen.get(crossing);
                }
            }
            int endVertex = vertexIdByNode[endNodeByArc[arcId]];
            if (endVertex != previous) {
                appendChord(path, strip.passageSourceFaces.get(
                        strip.passageSourceFaces.size() - 1), previous, endVertex, arcId);
            }
            pathByArc[arcId] = recordPath(arcId, path);
        }
    }

    /**
     * Lays one passage's chord and appends the copy vertices it runs through.
     *
     * @param path       the arc's path so far, appended to
     * @param sourceFace source active face holding the passage, its barycentric
     *                   frame
     * @param from       copy vertex the passage enters at
     * @param to         copy vertex the passage leaves at
     * @param arcId      arc the passage belongs to
     */
    private void appendChord(List<Integer> path, int sourceFace, int from, int to, int arcId) {
        if (topology.edgeBetween(from, to) == EmbeddedMeshTopology.UNCLAIMED) {
            chordsInsertedCount++;
        } else {
            chordsAlreadyPresentCount++;
        }
        List<Integer> chain = topology.insertChord(sourceFace, from, to, arcId);
        path.addAll(chain.subList(1, chain.size()));
    }

    /**
     * Records a laid arc's path and claims the vertices along it, leaving its two
     * node vertices to the nodes.
     *
     * @param arcId    arc that was laid
     * @param vertices its copy vertex path
     * @throws IllegalStateException when a hop of the path has no copy edge behind
     *                               it
     * @return the recorded path
     */
    private ArcEdgePath recordPath(int arcId, List<Integer> vertices) {
        List<Integer> edges = new ArrayList<>(vertices.size() - 1);
        for (int step = 1; step < vertices.size(); step++) {
            int edgeId = topology.edgeBetween(vertices.get(step - 1), vertices.get(step));
            if (edgeId == EmbeddedMeshTopology.UNCLAIMED) {
                throw new IllegalStateException("arc " + arcId + " hop " + step
                        + " has no copy edge behind it, so its chord was never laid");
            }
            topology.ownerArcByCopyEdge[edgeId] = arcId;
            edges.add(edgeId);
        }
        for (int index = 1; index < vertices.size() - 1; index++) {
            if (topology.ownerNodeByCopyVertex[vertices.get(index)] == EmbeddedMeshTopology.UNCLAIMED) {
                topology.ownerArcByCopyVertex[vertices.get(index)] = arcId;
            }
        }
        return new ArcEdgePath(arcId, vertices, edges);
    }

    /**
     * Where an arc's traced route crossed a constraint edge, used only to order the
     * arcs along it. Ordering must follow the trace or two arcs' chords would cross
     * inside a face; the positions themselves come from the count, never from this.
     *
     * @param crossing the crossing as {@code {arcId, crossingIndex}}
     * @return the traced parameter from the edge's first recorded endpoint
     */
    double tracedPositionOf(int[] crossing) {
        return stripByArc.get(crossing[0]).crossingParameters.get(crossing[1]);
    }

    /**
     * The key a constraint edge has in the per-crossing maps.
     *
     * @param endpoints the edge's endpoint copy vertices, lower first
     * @return the packed key
     */
    static long edgeKey(int[] endpoints) {
        return (long) endpoints[0] << Integer.SIZE | endpoints[1] & 0xFFFFFFFFL;
    }

    /**
     * The copy vertex of a node the tracer already put on a source mesh vertex.
     *
     * @param node node to place
     * @throws IllegalStateException when the source vertex has no copy
     * @return its copy vertex
     */
    private int claimSourceVertex(TMeshNode node) {
        int copyVertex = topology.copyVertexForSourceVertexId(node.vertexId);
        if (copyVertex == EmbeddedMeshTopology.UNCLAIMED) {
            throw new IllegalStateException("T-mesh node " + node.nodeId
                    + " references source vertex " + node.vertexId + " which has no copy vertex");
        }
        nodesOnVertexCount++;
        return copyVertex;
    }

    /**
     * Mints a copy vertex at a node's position inside its source face: a face
     * split, an edge split, or the corner it lands on. A node a rounding step off
     * an edge is placed on that edge rather than cutting a sliver beside it.
     *
     * @param node        node to place
     * @param barycentric its barycentric in its source face
     * @throws IllegalStateException when no child face of the source face holds it
     * @return the minted or reused copy vertex
     */
    private int mintInside(TMeshNode node, double[] barycentric) {
        for (int childFace : topology.copyFacesBySourceFace.get(node.activeFace)) {
            double[][] corner = childCorners(node.activeFace, childFace);
            boolean inside = true;
            for (int edge = 0; edge < CORNERS && inside; edge++) {
                inside = ExactBarycentricOrient.sign(corner[edge],
                        corner[(edge + 1) % CORNERS], barycentric) >= 0;
            }
            if (!inside) {
                continue;
            }
            double childArea = ExactBarycentricOrient.area(corner[0], corner[1], corner[2]);
            if (childArea <= 0.0) {
                continue;
            }
            int onEdge = EmbeddedMeshTopology.UNCLAIMED;
            int offEdge = EmbeddedMeshTopology.UNCLAIMED;
            int touching = 0;
            for (int edge = 0; edge < CORNERS; edge++) {
                double local = ExactBarycentricOrient.area(corner[edge],
                        corner[(edge + 1) % CORNERS], barycentric) / childArea;
                touching += local < FaceChordWalk.MINIMUM_SEPARATION ? 1 : 0;
                onEdge = local < FaceChordWalk.MINIMUM_SEPARATION ? edge : onEdge;
                offEdge = local < FaceChordWalk.MINIMUM_SEPARATION ? offEdge : edge;
            }
            if (touching >= 2) {
                nodesSnappedToCornerCount++;
                return topology.copy.faceVertexAt(childFace, (offEdge + 2) % CORNERS);
            }
            if (touching == 1) {
                return splitChildEdge(childFace, onEdge, corner, barycentric);
            }
            nodesByFaceSplitCount++;
            return topology.splitFaceAtBarycentric(childFace, barycentric.clone());
        }
        throw new IllegalStateException("T-mesh node " + node.nodeId + " at barycentric "
                + Arrays.toString(barycentric) + " lies in no child face of source face "
                + node.activeFace + "; its chart position and its face disagree");
    }

    /**
     * Splits a child face's edge where a node lands on it. A node within
     * {@link FaceChordWalk#MINIMUM_SEPARATION} of an end takes that end instead:
     * splitting there mints a vertex on top of an existing one.
     *
     * @param childFace child face carrying the edge
     * @param localEdge local edge index the node lies on
     * @param corner    the child face's three corner barycentrics
     * @param at        the node's barycentric
     * @return the minted copy vertex, or the end it snapped to
     */
    private int splitChildEdge(int childFace, int localEdge, double[][] corner, double[] at) {
        int from = topology.copy.faceVertexAt(childFace, localEdge);
        int to = topology.copy.faceVertexAt(childFace, (localEdge + 1) % CORNERS);
        int copyEdge = topology.edgeBetween(from, to);
        double span = 0.0;
        double along = 0.0;
        for (int index = 0; index < CORNERS; index++) {
            double reach = corner[(localEdge + 1) % CORNERS][index] - corner[localEdge][index];
            double step = at[index] - corner[localEdge][index];
            span += reach * reach;
            along += reach * step;
        }
        double parameter = along / span;
        if (parameter <= FaceChordWalk.MINIMUM_SEPARATION
                || parameter >= 1.0 - FaceChordWalk.MINIMUM_SEPARATION) {
            nodesSnappedToCornerCount++;
            return parameter <= FaceChordWalk.MINIMUM_SEPARATION ? from : to;
        }
        nodesByEdgeSplitCount++;
        int canonicalStart = topology.copy.halfEdgeVertex(topology.copy.edgeHalfEdge(copyEdge));
        return topology.splitEdgeAtParameter(copyEdge,
                canonicalStart == from ? parameter : 1.0 - parameter);
    }

    /**
     * The three corner barycentrics of a child face, read in its source face's
     * frame.
     *
     * @param sourceFace source active face the child descends from
     * @param childFace  child copy face
     * @throws IllegalStateException when a corner has no barycentric there
     * @return the corner barycentrics, in the child's winding
     */
    private double[][] childCorners(int sourceFace, int childFace) {
        double[][] corner = new double[CORNERS][];
        for (int index = 0; index < CORNERS; index++) {
            corner[index] = topology.barycentricOf(sourceFace,
                    topology.copy.faceVertexAt(childFace, index));
            if (corner[index] == null) {
                throw new IllegalStateException("copy face " + childFace + " corner " + index
                        + " has no barycentric in source face " + sourceFace);
            }
        }
        return corner;
    }

    /**
     * The barycentric of a node's recorded chart position in its own source face.
     *
     * @param node node whose chart position is inverted
     * @throws IllegalStateException when that face's chart is degenerate
     * @return its barycentric there
     */
    private double[] chartBarycentric(TMeshNode node) {
        double[] cornerUv = new double[2 * CORNERS];
        motorcycleGraph.seamless.faceCornerUv(node.activeFace, cornerUv);
        double firstU = cornerUv[2] - cornerUv[0];
        double firstV = cornerUv[3] - cornerUv[1];
        double secondU = cornerUv[4] - cornerUv[0];
        double secondV = cornerUv[5] - cornerUv[1];
        double determinant = firstU * secondV - firstV * secondU;
        if (determinant == 0.0) {
            throw new IllegalStateException(
                    "source active face " + node.activeFace + " has a degenerate chart");
        }
        double offsetU = node.u - cornerUv[0];
        double offsetV = node.v - cornerUv[1];
        double second = (offsetU * secondV - offsetV * secondU) / determinant;
        double third = (firstU * offsetV - firstV * offsetU) / determinant;
        return new double[] { 1.0 - second - third, second, third };
    }

    /**
     * Reports what the carve minted and what it only rearranged, which is the first
     * check that the copy mesh is being refined no further than the layout needs.
     */
    public void report() {
        System.out.printf("[snap] nodes=%d onVertex=%d edgeSplit=%d faceSplit=%d snapped=%d |"
                + " constraint mesh V=%d F=%d (source V=%d F=%d)%n",
                nodeCount, nodesOnVertexCount, nodesByEdgeSplitCount,
                nodesByFaceSplitCount, nodesSnappedToCornerCount, constraintVertexCount,
                constraintFaceCount,
                topology.sourceMesh.vertexCount(), topology.sourceMesh.faceCount());
        if (stripByArc == null) {
            return;
        }
        int passages = 0;
        int longestArc = 0;
        for (FaceStripPath strip : stripByArc) {
            passages += strip.passageFaces.size();
            longestArc = Math.max(longestArc, strip.passageFaces.size());
        }
        System.out.printf("[snap] arcs=%d passages=%d longestArc=%d | contestedFaces=%d of %d"
                + " mostPassagesOnAFace=%d%n", stripByArc.size(), passages, longestArc,
                contestedFaceCount, passageCountBySourceFace.length, mostPassagesOnAFace);
        if (pathByArc == null) {
            return;
        }
        System.out.printf("[snap] crossings served by corner=%d by minted lane=%d (most=%d on"
                + " one edge, over %d constraint edges)%n", cornersGrantedCount, lanesMintedCount,
                mostLanesOnAnEdge, crossingsByEdge.size());
        System.out.printf("[snap] chords laid=%d alreadyPresent=%d | copy V=%d F=%d, unchanged"
                + " by chords: V %s F %s%n", chordsInsertedCount, chordsAlreadyPresentCount,
                topology.copy.vertexCount(), topology.copy.faceCount(),
                verticesBeforeChords == topology.copy.vertexCount(),
                facesBeforeChords == topology.copy.faceCount());
    }
}
