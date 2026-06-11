package ixdar.geometry.mesh.quadlayout.motorcycle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.Singularity;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * Lyon 2021 §3 modified motorcycle graph T-mesh: traces, nodes, arcs, and
 * patches built from a seamless parametrization.
 */
public final class MotorcycleGraph {

    public static final int MAX_TRACE_RECORDS_PER_FACE = 4;
    public static final double PARAMETRIC_EPS = 1.0e-9;
    /**
     * Minimum cosine between consecutive alignment-edge directions for them to
     * stay in one feature chain; below this the crease turns a corner and the
     * chain (and its iso-line) breaks.
     */
    static final double CHAIN_TURN_COS = Math.cos(Math.PI / 4.0);
    /**
     * Chart-space tolerance when snapping a boundary termination point onto the
     * feature-chain segment it landed on (matches the span tolerance used by
     * {@link FaceSegmentIndex}).
     */
    static final double TERMINATION_SNAP_EPS = 1.0e-6;
    private static final int CORNERS = SeamlessParameterization.CORNERS_PER_FACE;
    private static final int DIE_SAMPLE_LIMIT = 12;
    private static final int PROGRESS_BAR_WIDTH = 30;
    private static final int PROGRESS_LOG_EVERY_EVENTS = 5000;
    /** Hard cap on processed events so a stuck queue cannot run forever. */
    private static final int MAX_SIMULATION_EVENTS = 100_000;
    /**
     * Wall-clock budget for the simulation loop. ELK converges in well under a
     * second when the parametrization is correct; anything over this cap means the
     * queue is stuck (traces drifting off-axis, iso-lines that should coincide
     * split by floating-point noise, etc.) and we should abort fast rather than
     * burn the user's CPU.
     */
    private static final long MAX_SIMULATION_NANOS = 10L * 1_000_000_000L;

    public final SeamlessParameterization seamless;
    public final float alphaRadians;

    public List<TMeshNode> nodes;
    public List<TraceArc> arcs;
    public List<TMeshPatch> patches;
    public List<Trace> traces;

    public int[] patchIdByActiveFace;
    public float[][] traceRecordsByFace;

    /**
     * Feature-chain lookup per alignment/boundary edge id: owning feature trace
     * and the chain-length interval the edge covers. Populated by the feature
     * seeding pass and consumed by {@link PatchBoundaryBuilder} to resolve
     * patch-boundary stretches that run along feature curves.
     */
    public final Map<Integer, FeatureEdgeSpan> featureSpanByEdgeId = new HashMap<>();

    /**
     * Per active edge, the trace crossings that severed it during patch
     * assembly (transversal crossings only — feature chains run along edges,
     * not across them). {@link PatchBoundaryBuilder} resolves each crossing to
     * the T-mesh arc containing its parametric length.
     */
    public List<List<EdgeCrossing>> crossingsByActiveEdge;

    /** Singularity traces that enqueued a first event during {@link #build()}. */
    public int spawnForwardCount;
    /**
     * Singularity traces that died before the first event during {@link #build()}.
     */
    public int spawnDeadCount;
    /** Priority-queue size after seeding singularity trace events. */
    public int initialEventQueueSize;
    /**
     * Diagnostics: traces that died in {@link #enqueueNextEvent} from no forward
     * edge hit.
     */
    public int dieNoForwardEdgeCount;
    /**
     * Diagnostics: traces that died in {@link #enqueueNextEvent} from a near-zero
     * edge length.
     */
    public int dieZeroEdgeLengthCount;
    /**
     * Diagnostics: traces that died in {@link #handleEdgeCrossing} re-query
     * returning null.
     */
    public int dieEdgeCrossingNullHitCount;
    /** Diagnostic category counters for silent-die classification. */
    public int dieCategoryAtCorner;
    public int dieCategoryParallelToOppositeEdge;
    public int dieCategoryAllEdgesBackward;
    public int dieCategoryUnclassified;

    private int nextNodeId;
    private int nextArcId;
    private int nextTraceId;
    private int dieSamplesPrinted;

    private int faceCount;
    private HalfEdgeMesh mesh;
    private CrossField crossField;
    private ChartWalker walker;
    private FaceSegmentIndex segmentIndex;

    /**
     * Stores inputs for a Lyon §3 motorcycle graph build.
     *
     * @param seamless     built seamless parametrization
     * @param alphaRadians Lyon stopping bound α in radians
     */
    public MotorcycleGraph(SeamlessParameterization seamless, float alphaRadians) {
        this.seamless = seamless;
        this.alphaRadians = alphaRadians;

        this.mesh = seamless.mesh;
        this.crossField = seamless.crossField;
        this.faceCount = this.mesh.faceCount();
        this.walker = new ChartWalker(seamless);
        this.segmentIndex = new FaceSegmentIndex(faceCount);

        this.nodes = new ArrayList<>();
        this.arcs = new ArrayList<>();
        this.patches = new ArrayList<>();
        this.traces = new ArrayList<>();
    }

    /**
     * Build the modified motorcycle graph T-mesh.
     *
     * @return this graph with populated nodes, arcs, patches, and traces
     * @throws IllegalStateException when {@code seamless} has not been built
     */
    public MotorcycleGraph build() {
        long buildStartNanos = System.nanoTime();

        System.out.println("[motorcycle] seeding singularity nodes and feature traces");
        for (Singularity singularity : crossField.singularities) {
            Vector3f position = mesh.vertexPosition(singularity.vertexId());
            TMeshNode node = new TMeshNode(nextNodeId++, TMeshNode.TYPE_SINGULARITY,
                    singularity.vertexId(), singularity.index4(), 0f, 0f, position);
            nodes.add(node);
        }
        seedFeatureChains();
        int featureTraceCount = traces.size();

        List<TracePort> ports = TracePort.spawnFromSingularities(seamless);
        for (TracePort port : ports) {
            double[] cornerUv = new double[ChartWalker.CORNER_UV_FLOATS];
            walker.faceCornerUv(port.activeFace, cornerUv);
            double startU = cornerUv[port.cornerIndex * 2];
            double startV = cornerUv[port.cornerIndex * 2 + 1];

            TMeshNode origin = null;
            for (TMeshNode node : nodes) {
                if (node.type == TMeshNode.TYPE_SINGULARITY
                        && node.singularityVertexId == port.singularityVertexId) {
                    origin = node;
                    break;
                }
            }

            if (origin == null) {
                Vector3f position = new Vector3f();
                int faceId = seamless.mesh.faceIdAt(port.activeFace);
                seamless.mesh.vertexPosition(seamless.mesh.faceVertexAt(faceId, port.cornerIndex), position);
                origin = new TMeshNode(nextNodeId++, TMeshNode.TYPE_SINGULARITY,
                        port.singularityVertexId, 0, startU, startV, position);
                nodes.add(origin);
            }
            Trace trace = new Trace(nextTraceId++, origin.nodeId, port.singularityVertexId,
                    port, startU, startV, false);
            traces.add(trace);
        }
        System.out.printf("[motorcycle] traces=%d (feature=%d singularity=%d) nodes=%d%n",
                traces.size(), featureTraceCount, ports.size(), nodes.size());

        PriorityQueue<TraceEvent> queue = new PriorityQueue<>();
        int deadAtSpawn = 0;
        int forwardAtSpawn = 0;
        for (Trace trace : traces) {
            if (!trace.featureTrace) {
                enqueueNextEvent(trace, walker, segmentIndex, queue);
                if (trace.alive) {
                    forwardAtSpawn++;
                } else {
                    deadAtSpawn++;
                }
            }
        }
        int initialQueueSize = queue.size();
        spawnForwardCount = forwardAtSpawn;
        spawnDeadCount = deadAtSpawn;
        initialEventQueueSize = initialQueueSize;
        System.out.printf("[motorcycle] spawn: ports=%d forward=%d deadAtSpawn=%d%n",
                ports.size(), forwardAtSpawn, deadAtSpawn);
        System.out.printf("[motorcycle] event simulation: queue=%d%n", initialQueueSize);

        long simStartNanos = System.nanoTime();
        int eventsProcessed = 0;
        int lastAlive = -1;
        while (!queue.isEmpty()) {
            if (eventsProcessed >= MAX_SIMULATION_EVENTS) {
                System.out.printf(
                        "[motorcycle] event simulation stopped at max events=%d queue=%d",
                        MAX_SIMULATION_EVENTS, queue.size());
                break;
            }
            if (System.nanoTime() - simStartNanos > MAX_SIMULATION_NANOS) {
                System.out.printf(
                        "[motorcycle] event simulation stopped at wall-clock cap %.1fs queue=%d events=%d%n",
                        MAX_SIMULATION_NANOS / 1.0e9, queue.size(), eventsProcessed);
                dumpStallDiagnostics(segmentIndex);
                break;
            }
            TraceEvent event = queue.poll();
            eventsProcessed++;
            Trace trace = traces.get(event.traceId);
            if (!trace.alive) {
                continue;
            }
            if (event.parametricLength <= trace.parametricLengthSoFar + PARAMETRIC_EPS) {
                continue;
            }
            switch (event.type) {
            case TraceEvent.TYPE_INTERSECTION -> handleIntersection(
                    trace, event, walker, segmentIndex, queue);
            case TraceEvent.TYPE_EDGE -> handleEdgeCrossing(
                    trace, event, walker, segmentIndex, queue);
            case TraceEvent.TYPE_BOUNDARY, TraceEvent.TYPE_SINGULARITY -> handleTermination(trace, event);
            default -> {
            }
            }
            if (eventsProcessed % PROGRESS_LOG_EVERY_EVENTS == 0) {
                int alive = 0;
                for (Trace t : traces) {
                    if (t.alive && !t.featureTrace) {
                        alive++;
                    }
                }
                printSimulationProgress(eventsProcessed, initialQueueSize, queue.size(), alive,
                        lastAlive, System.nanoTime() - simStartNanos);
                lastAlive = alive;
            }
        }
        printSimulationProgress(eventsProcessed, initialQueueSize, 0, lastAlive,
                lastAlive, System.nanoTime() - simStartNanos);
        System.out.println("[motorcycle] finalizing open traces");
        finalizeOpenTraces(walker);
        System.out.println("[motorcycle] subdividing arcs at every meeting");
        subdivideArcsAtMeetings();
        System.out.println("[motorcycle] assembling patches");
        assemblePatches();
        System.out.println("[motorcycle] resolving patch boundary arcs and sides");
        new PatchBoundaryBuilder(this).build();
        buildTraceRecordBuffer();
        System.out.printf(
                "[motorcycle] done traces=%d arcs=%d nodes=%d patches=%d %.2fs%n",
                traces.size(), arcs.size(), nodes.size(), patches.size(),
                (System.nanoTime() - buildStartNanos) / 1.0e9);
        return this;
    }

    /**
     * Seed one immortal feature trace per maximal chain of alignment/boundary
     * edges (Lyon §4.4: features and boundaries become T-mesh arcs). A chain
     * extends through a vertex only when exactly two alignment edges meet
     * there, the vertex is not a singularity, and the crease continues without
     * turning (cosine test against {@link #CHAIN_TURN_COS}). Every chain edge
     * contributes one {@link TraceSegment} per incident face — both charts —
     * so motorcycles approaching from either side collide with the chain.
     */
    private void seedFeatureChains() {
        List<Integer> alignmentEdgeIds = new ArrayList<>(crossField.alignmentEdgeIds);
        Collections.sort(alignmentEdgeIds);
        if (alignmentEdgeIds.isEmpty()) {
            return;
        }

        Map<Integer, List<Integer>> alignmentEdgesByVertex = new HashMap<>();
        for (int edgeId : alignmentEdgeIds) {
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            alignmentEdgesByVertex.computeIfAbsent(mesh.halfEdgeVertex(halfEdge),
                    vertexId -> new ArrayList<>()).add(edgeId);
            alignmentEdgesByVertex.computeIfAbsent(mesh.halfEdgeEndVertex(halfEdge),
                    vertexId -> new ArrayList<>()).add(edgeId);
        }
        Set<Integer> singularVertexIds = new HashSet<>();
        for (Singularity singularity : crossField.singularities) {
            singularVertexIds.add(singularity.vertexId());
        }

        Set<Integer> visitedEdgeIds = new HashSet<>();
        int chainCount = 0;
        for (int seedEdgeId : alignmentEdgeIds) {
            if (visitedEdgeIds.contains(seedEdgeId)) {
                continue;
            }
            int seedHalfEdge = mesh.edgeHalfEdge(seedEdgeId);
            int chainStartVertexId = mesh.halfEdgeVertex(seedHalfEdge);
            int firstEdgeId = seedEdgeId;
            boolean closedLoop = false;
            while (true) {
                int previousEdgeId = chainContinuation(chainStartVertexId, firstEdgeId,
                        alignmentEdgesByVertex, singularVertexIds);
                if (previousEdgeId < 0) {
                    break;
                }
                if (previousEdgeId == seedEdgeId) {
                    closedLoop = true;
                    break;
                }
                firstEdgeId = previousEdgeId;
                chainStartVertexId = otherVertexOfEdge(previousEdgeId, chainStartVertexId);
            }
            if (closedLoop) {
                chainStartVertexId = mesh.halfEdgeVertex(seedHalfEdge);
                firstEdgeId = seedEdgeId;
            }

            List<Integer> chainVertexIds = new ArrayList<>();
            List<Integer> chainEdgeIds = new ArrayList<>();
            chainVertexIds.add(chainStartVertexId);
            int walkVertexId = chainStartVertexId;
            int walkEdgeId = firstEdgeId;
            while (true) {
                chainEdgeIds.add(walkEdgeId);
                visitedEdgeIds.add(walkEdgeId);
                walkVertexId = otherVertexOfEdge(walkEdgeId, walkVertexId);
                chainVertexIds.add(walkVertexId);
                if (closedLoop && walkVertexId == chainStartVertexId) {
                    break;
                }
                int nextEdgeId = chainContinuation(walkVertexId, walkEdgeId,
                        alignmentEdgesByVertex, singularVertexIds);
                if (nextEdgeId < 0 || visitedEdgeIds.contains(nextEdgeId)) {
                    break;
                }
                walkEdgeId = nextEdgeId;
            }
            createFeatureChainTrace(chainVertexIds, chainEdgeIds);
            chainCount++;
        }
        System.out.printf("[motorcycle] feature chains=%d over %d alignment edges%n",
                chainCount, alignmentEdgeIds.size());
    }

    /**
     * The single alignment edge continuing the chain through {@code vertexId},
     * or {@code -1} when the chain breaks there (junction, dead end,
     * singularity, or crease corner).
     */
    private int chainContinuation(int vertexId, int viaEdgeId,
            Map<Integer, List<Integer>> alignmentEdgesByVertex, Set<Integer> singularVertexIds) {
        List<Integer> incidentEdgeIds = alignmentEdgesByVertex.get(vertexId);
        if (incidentEdgeIds == null || incidentEdgeIds.size() != 2) {
            return -1;
        }
        if (singularVertexIds.contains(vertexId)) {
            return -1;
        }
        int nextEdgeId = incidentEdgeIds.get(0) == viaEdgeId
                ? incidentEdgeIds.get(1)
                : incidentEdgeIds.get(0);
        if (nextEdgeId == viaEdgeId) {
            return -1;
        }
        Vector3f sharedPosition = new Vector3f();
        Vector3f incomingFarPosition = new Vector3f();
        Vector3f outgoingFarPosition = new Vector3f();
        mesh.vertexPosition(vertexId, sharedPosition);
        mesh.vertexPosition(otherVertexOfEdge(viaEdgeId, vertexId), incomingFarPosition);
        mesh.vertexPosition(otherVertexOfEdge(nextEdgeId, vertexId), outgoingFarPosition);
        Vector3f incomingDirection = new Vector3f(sharedPosition).sub(incomingFarPosition).normalize();
        Vector3f outgoingDirection = new Vector3f(outgoingFarPosition).sub(sharedPosition).normalize();
        if (incomingDirection.dot(outgoingDirection) < CHAIN_TURN_COS) {
            return -1;
        }
        return nextEdgeId;
    }

    private int otherVertexOfEdge(int edgeId, int vertexId) {
        int halfEdge = mesh.edgeHalfEdge(edgeId);
        int fromVertexId = mesh.halfEdgeVertex(halfEdge);
        return fromVertexId == vertexId ? mesh.halfEdgeEndVertex(halfEdge) : fromVertexId;
    }

    /**
     * Materialize one feature chain as an immortal trace: TYPE_FEATURE end
     * nodes (shared when the chain is a closed loop), one chart-local segment
     * per chain edge per incident face, and a {@link FeatureEdgeSpan} record
     * per edge for later patch-boundary resolution. The chain's parametric
     * length accumulates per edge from the first incident face's chart.
     */
    private void createFeatureChainTrace(List<Integer> chainVertexIds, List<Integer> chainEdgeIds) {
        double[] cornerUv = new double[ChartWalker.CORNER_UV_FLOATS];

        int firstEdgeId = chainEdgeIds.get(0);
        int firstFromVertexId = chainVertexIds.get(0);
        int firstActiveEdge = crossField.edgeIdToActive.get(firstEdgeId);
        HalfEdgeMesh.EdgeFaceIds firstEdgeFaces = mesh.edgeFaceIds(firstActiveEdge);
        int firstFaceId = firstEdgeFaces.faceA >= 0 ? firstEdgeFaces.faceA : firstEdgeFaces.faceB;
        int firstActiveFace = crossField.faceIdToActive.get(firstFaceId);
        walker.faceCornerUv(firstActiveFace, cornerUv);
        int firstFromCorner = cornerOfVertexInFace(firstFaceId, firstFromVertexId);
        int firstToCorner = cornerOfVertexInFace(firstFaceId, chainVertexIds.get(1));
        double startU = cornerUv[firstFromCorner * 2];
        double startV = cornerUv[firstFromCorner * 2 + 1];
        double firstDeltaU = cornerUv[firstToCorner * 2] - startU;
        double firstDeltaV = cornerUv[firstToCorner * 2 + 1] - startV;
        TraceAxis firstAxis = TraceAxis.fromDirection(firstDeltaU, firstDeltaV);
        int firstSign = TraceAxis.signFor(firstAxis, firstDeltaU, firstDeltaV);

        TMeshNode origin = new TMeshNode(nextNodeId++, TMeshNode.TYPE_FEATURE, -1, 0,
                startU, startV, mesh.vertexPosition(firstFromVertexId));
        nodes.add(origin);
        TracePort port = new TracePort(-1, firstActiveFace, firstFromCorner, firstAxis, firstSign);
        Trace trace = new Trace(nextTraceId++, origin.nodeId, -1, port, startU, startV, true);
        traces.add(trace);

        double chainLength = 0.0;
        for (int chainIndex = 0; chainIndex < chainEdgeIds.size(); chainIndex++) {
            int edgeId = chainEdgeIds.get(chainIndex);
            int fromVertexId = chainVertexIds.get(chainIndex);
            int toVertexId = chainVertexIds.get(chainIndex + 1);
            int activeEdge = crossField.edgeIdToActive.get(edgeId);
            HalfEdgeMesh.EdgeFaceIds edgeFaces = mesh.edgeFaceIds(activeEdge);
            double edgeChartLength = 0.0;
            for (int faceId : new int[] { edgeFaces.faceA, edgeFaces.faceB }) {
                if (faceId < 0) {
                    continue;
                }
                int activeFace = crossField.faceIdToActive.get(faceId);
                walker.faceCornerUv(activeFace, cornerUv);
                int fromCorner = cornerOfVertexInFace(faceId, fromVertexId);
                int toCorner = cornerOfVertexInFace(faceId, toVertexId);
                double entryU = cornerUv[fromCorner * 2];
                double entryV = cornerUv[fromCorner * 2 + 1];
                double exitU = cornerUv[toCorner * 2];
                double exitV = cornerUv[toCorner * 2 + 1];
                TraceAxis axis = TraceAxis.fromDirection(exitU - entryU, exitV - entryV);
                int sign = TraceAxis.signFor(axis, exitU - entryU, exitV - entryV);
                TraceSegment segment = new TraceSegment(trace.traceId, activeFace,
                        entryU, entryV, exitU, exitV, axis, sign, chainLength);
                trace.segments.add(segment);
                segmentIndex.add(segment);
                if (edgeChartLength == 0.0) {
                    edgeChartLength = segment.parametricLength();
                }
            }
            featureSpanByEdgeId.put(edgeId, new FeatureEdgeSpan(trace.traceId, fromVertexId,
                    chainLength, chainLength + edgeChartLength));
            chainLength += edgeChartLength;
        }

        int lastVertexId = chainVertexIds.get(chainVertexIds.size() - 1);
        TMeshNode terminal;
        if (lastVertexId == chainVertexIds.get(0)) {
            terminal = origin;
        } else {
            int lastEdgeId = chainEdgeIds.get(chainEdgeIds.size() - 1);
            int lastActiveEdge = crossField.edgeIdToActive.get(lastEdgeId);
            HalfEdgeMesh.EdgeFaceIds lastEdgeFaces = mesh.edgeFaceIds(lastActiveEdge);
            int lastFaceId = lastEdgeFaces.faceA >= 0 ? lastEdgeFaces.faceA : lastEdgeFaces.faceB;
            int lastActiveFace = crossField.faceIdToActive.get(lastFaceId);
            walker.faceCornerUv(lastActiveFace, cornerUv);
            int lastCorner = cornerOfVertexInFace(lastFaceId, lastVertexId);
            terminal = new TMeshNode(nextNodeId++, TMeshNode.TYPE_FEATURE, -1, 0,
                    cornerUv[lastCorner * 2], cornerUv[lastCorner * 2 + 1],
                    mesh.vertexPosition(lastVertexId));
            nodes.add(terminal);
        }
        trace.arcNodeIds.add(terminal.nodeId);
        trace.currentNodeId = terminal.nodeId;
        trace.parametricLengthSoFar = chainLength;
    }

    private int cornerOfVertexInFace(int faceId, int vertexId) {
        for (int corner = 0; corner < CORNERS; corner++) {
            if (mesh.faceVertexAt(faceId, corner) == vertexId) {
                return corner;
            }
        }
        return 0;
    }

    private void printSimulationProgress(int eventsProcessed, int initialQueueSize, int queueSize,
            int aliveTraces, int previousAlive, long elapsedNanos) {
        int barWidth = PROGRESS_BAR_WIDTH;
        int filled = initialQueueSize == 0 ? barWidth
                : Math.max(0, Math.min(barWidth,
                        (int) Math.round((double) eventsProcessed * barWidth
                                / Math.max(1, eventsProcessed + queueSize))));
        StringBuilder bar = new StringBuilder(barWidth + 2);
        bar.append('[');
        for (int i = 0; i < barWidth; i++) {
            bar.append(i < filled ? '#' : '.');
        }
        bar.append(']');
        String delta = previousAlive < 0 ? "(start)"
                : String.format("(%+d)", aliveTraces - previousAlive);
        System.out.printf("[motorcycle] %s events=%6d queue=%5d alive=%4d %s  %.2fs%n",
                bar.toString(), eventsProcessed, queueSize, aliveTraces, delta,
                elapsedNanos / 1.0e9);
    }

    private void enqueueNextEvent(Trace trace, ChartWalker walker,
            FaceSegmentIndex segmentIndex, PriorityQueue<TraceEvent> queue) {
        ChartWalker.State probe = new ChartWalker.State(trace.state);
        ChartWalker.EdgeHit edgeHit = walker.nextEdgeHit(probe);
        if (edgeHit == null) {
            trace.alive = false;
            dieNoForwardEdgeCount++;
            int activeFace = trace.state.activeFace;
            double[] uv = new double[ChartWalker.CORNER_UV_FLOATS];
            new ChartWalker(seamless).faceCornerUv(activeFace, uv);
            double u = trace.state.u;
            double v = trace.state.v;
            double[] dir = trace.state.axis.direction(trace.state.sign);
            int incoming = trace.state.incomingLocalEdgeIndex;

            double maxEdgeSq = 0.0;
            for (int e = 0; e < 3; e++) {
                int n = (e + 1) % 3;
                double dx = uv[n * 2] - uv[e * 2];
                double dy = uv[n * 2 + 1] - uv[e * 2 + 1];
                maxEdgeSq = Math.max(maxEdgeSq, dx * dx + dy * dy);
            }
            double cornerTolSq = 1.0e-10 * maxEdgeSq;
            boolean atCorner = false;
            for (int c = 0; c < 3; c++) {
                double du = uv[c * 2] - u;
                double dv = uv[c * 2 + 1] - v;
                if (du * du + dv * dv <= cornerTolSq * 1.0e6) {
                    atCorner = true;
                    break;
                }
            }

            boolean parallelToCandidate = false;
            boolean anyForward = false;
            for (int e = 0; e < 3; e++) {
                if (e == incoming) {
                    continue;
                }
                int n = (e + 1) % 3;
                double ax = uv[e * 2];
                double ay = uv[e * 2 + 1];
                double bx = uv[n * 2];
                double by = uv[n * 2 + 1];
                double segDx = bx - ax;
                double segDy = by - ay;
                double denom = dir[0] * segDy - dir[1] * segDx;
                if (Math.abs(denom) < 1.0e-12) {
                    parallelToCandidate = true;
                    continue;
                }
                double t = ((ax - u) * segDy - (ay - v) * segDx) / denom;
                if (t > ChartWalker.RAY_MIN_T) {
                    anyForward = true;
                }
            }

            String category;
            if (atCorner) {
                category = "AT_CORNER";
                dieCategoryAtCorner++;
            } else if (parallelToCandidate && !anyForward) {
                category = "PARALLEL_TO_OPPOSITE_EDGE";
                dieCategoryParallelToOppositeEdge++;
            } else if (!anyForward) {
                category = "ALL_EDGES_BACKWARD";
                dieCategoryAllEdgesBackward++;
            } else {
                category = "UNCLASSIFIED";
                dieCategoryUnclassified++;
            }

            if (dieSamplesPrinted < DIE_SAMPLE_LIMIT) {
                dieSamplesPrinted++;
                System.out.printf(
                        "[motorcycle-diag] %s trace=%d face=%d u=%.6f v=%.6f axis=%s sign=%+d incoming=%d uv=[(%.3f,%.3f),(%.3f,%.3f),(%.3f,%.3f)] category=%s%n",
                        "enqueueNextEvent", trace.traceId, activeFace, u, v, trace.state.axis, trace.state.sign,
                        incoming,
                        uv[0], uv[1], uv[2], uv[3], uv[4], uv[5], category);
            }
            return;
        }
        double edgeLength = edgeHit.parametricDelta;
        if (edgeLength < PARAMETRIC_EPS) {
            trace.alive = false;
            dieZeroEdgeLengthCount++;
            return;
        }
        double exitU = edgeHit.exitU;
        double exitV = edgeHit.exitV;

        FaceSegmentIndex.IntersectionHit intersection = segmentIndex.earliestIntersection(
                trace.traceId, trace.state.activeFace,
                trace.state.u, trace.state.v, exitU, exitV, trace.state.axis,
                trace.metOtherTraces);
        if (intersection != null && intersection.tAlongCandidate < edgeLength) {
            queue.add(new TraceEvent(TraceEvent.TYPE_INTERSECTION,
                    trace.parametricLengthSoFar + intersection.tAlongCandidate,
                    trace.traceId, intersection.otherSegment.traceId,
                    trace.state.activeFace,
                    (float) intersection.intersectionU, (float) intersection.intersectionV,
                    intersection.otherSegment));
            return;
        }
        if (edgeHit.boundary) {
            queue.add(new TraceEvent(TraceEvent.TYPE_BOUNDARY,
                    trace.parametricLengthSoFar + edgeLength,
                    trace.traceId, -1, trace.state.activeFace, exitU, exitV, null));
            return;
        }
        queue.add(new TraceEvent(TraceEvent.TYPE_EDGE,
                trace.parametricLengthSoFar + edgeLength,
                trace.traceId, -1, trace.state.activeFace, exitU, exitV, null));
    }

    private void handleEdgeCrossing(Trace trace, TraceEvent event, ChartWalker walker,
            FaceSegmentIndex segmentIndex, PriorityQueue<TraceEvent> queue) {
        ChartWalker.EdgeHit edgeHit = walker.nextEdgeHit(trace.state);
        if (edgeHit == null) {
            trace.alive = false;
            dieEdgeCrossingNullHitCount++;
            return;
        }
        TraceSegment segment = new TraceSegment(trace.traceId, trace.state.activeFace,
                trace.state.u, trace.state.v, edgeHit.exitU, edgeHit.exitV,
                trace.state.axis, trace.state.sign, trace.parametricLengthSoFar);
        trace.segments.add(segment);
        segmentIndex.add(segment);
        trace.parametricLengthSoFar = event.parametricLength;

        if (edgeHit.cornerLocalIndex >= 0) {
            handleVertexCrossing(trace, event, edgeHit, walker, segmentIndex, queue);
            return;
        }

        ChartWalker.State next = new ChartWalker.State(trace.state);
        if (!walker.crossEdge(trace.state, edgeHit, next)) {
            handleTermination(trace, new TraceEvent(TraceEvent.TYPE_BOUNDARY,
                    event.parametricLength, trace.traceId, -1, event.activeFace,
                    edgeHit.exitU, edgeHit.exitV, null));
            return;
        }
        trace.state = next;
        enqueueNextEvent(trace, walker, segmentIndex, queue);
    }

    private void handleVertexCrossing(Trace trace, TraceEvent event, ChartWalker.EdgeHit edgeHit,
            ChartWalker walker, FaceSegmentIndex segmentIndex, PriorityQueue<TraceEvent> queue) {
        ChartWalker.State next = new ChartWalker.State(trace.state);
        ChartWalker.CrossVertexResult result = walker.crossVertex(trace.state, edgeHit, next);
        switch (result) {
        case FAN_TRANSITION -> {
            trace.state = next;
            enqueueNextEvent(trace, walker, segmentIndex, queue);
        }
        case HIT_SINGULARITY -> handleTermination(trace, new TraceEvent(TraceEvent.TYPE_SINGULARITY,
                event.parametricLength, trace.traceId, -1, event.activeFace,
                edgeHit.exitU, edgeHit.exitV, null));
        case HIT_BOUNDARY -> handleTermination(trace, new TraceEvent(TraceEvent.TYPE_BOUNDARY,
                event.parametricLength, trace.traceId, -1, event.activeFace,
                edgeHit.exitU, edgeHit.exitV, null));
        case HIT_SINGULARITY_GAP -> handleTermination(trace, new TraceEvent(TraceEvent.TYPE_SINGULARITY,
                event.parametricLength, trace.traceId, -1, event.activeFace,
                edgeHit.exitU, edgeHit.exitV, null));
        default -> trace.alive = false;
        }
    }

    private void handleIntersection(Trace trace, TraceEvent event, ChartWalker walker,
            FaceSegmentIndex segmentIndex, PriorityQueue<TraceEvent> queue) {
        Trace other = traces.get(event.otherTraceId);
        TraceSegment segment = new TraceSegment(trace.traceId, trace.state.activeFace,
                trace.state.u, trace.state.v, event.u, event.v,
                trace.state.axis, trace.state.sign, trace.parametricLengthSoFar);
        trace.segments.add(segment);
        segmentIndex.add(segment);
        trace.parametricLengthSoFar = event.parametricLength;

        TMeshNode intersectionNode = new TMeshNode(nextNodeId++, TMeshNode.TYPE_INTERSECTION,
                -1, 0, event.u, event.v, liftTo3D(event.activeFace, event.u, event.v));
        nodes.add(intersectionNode);
        addArc(trace, intersectionNode.nodeId, event.parametricLength - segment.parametricLength());
        trace.currentNodeId = intersectionNode.nodeId;
        trace.arcNodeIds.add(intersectionNode.nodeId);

        TraceSegment otherSegment = event.otherSegment;
        // Distance from the other segment's entry, measured along the
        // coordinate the OTHER trace varies (a U-axis trace varies u). The
        // moving trace's axis is perpendicular, so measuring along its own
        // axis would read the other chord's constant coordinate (always ~0).
        double distanceAlongSegment = otherSegment.axis == TraceAxis.U
                ? Math.abs(event.u - otherSegment.entryU)
                : Math.abs(event.v - otherSegment.entryV);
        double theirLength = otherSegment.parametricLengthAtEntry + distanceAlongSegment;
        double alphaIjForTi = Trace.computeAlphaIj(
                trace.state.axis, trace.state.sign,
                otherSegment.axis, otherSegment.sign,
                event.parametricLength, theirLength);
        double alphaJiForTj = Trace.computeAlphaIj(
                otherSegment.axis, otherSegment.sign,
                trace.state.axis, trace.state.sign,
                theirLength, event.parametricLength);
        trace.recordMeeting(other, event.parametricLength, theirLength, alphaIjForTi, alphaRadians,
                trace.state.axis, trace.state.sign, otherSegment.axis, otherSegment.sign);
        other.recordMeeting(trace, theirLength, event.parametricLength, alphaJiForTj, alphaRadians,
                otherSegment.axis, otherSegment.sign, trace.state.axis, trace.state.sign);

        // Stamp the shared intersection node id onto the just-added meeting
        // entries on both sides so the post-build pass can rebuild each
        // trace's arc chain through every meeting (not just the ones where
        // it was the active processor).
        trace.metOtherTraces.get(trace.metOtherTraces.size() - 1).intersectionNodeId = intersectionNode.nodeId;
        other.metOtherTraces.get(other.metOtherTraces.size() - 1).intersectionNodeId = intersectionNode.nodeId;

        // When Lyon stopping fires on `other` here, this intersection is its
        // terminal node. Without recording the node and arc on `other`, its
        // `arcNodeIds` stays size 1 and `finalizeOpenTraces` mislabels it as
        // TYPE_TRUNCATED. The post-build subdivision pass would also fix this,
        // but doing it inline keeps the safety net coherent.
        if (!other.alive && other.arcNodeIds.size() < 2) {
            addArc(other, intersectionNode.nodeId, theirLength);
            other.currentNodeId = intersectionNode.nodeId;
            other.arcNodeIds.add(intersectionNode.nodeId);
        }

        if (!trace.alive) {
            return;
        }
        trace.state.u = event.u;
        trace.state.v = event.v;
        trace.state.incomingLocalEdgeIndex = -1;
        enqueueNextEvent(trace, walker, segmentIndex, queue);
    }

    private void handleTermination(Trace trace, TraceEvent event) {
        TraceSegment segment = new TraceSegment(trace.traceId, trace.state.activeFace,
                trace.state.u, trace.state.v, event.u, event.v,
                trace.state.axis, trace.state.sign, trace.parametricLengthSoFar);
        trace.segments.add(segment);
        trace.parametricLengthSoFar = event.parametricLength;
        TMeshNode endNode = new TMeshNode(nextNodeId++,
                event.type == TraceEvent.TYPE_BOUNDARY ? TMeshNode.TYPE_BOUNDARY : TMeshNode.TYPE_SINGULARITY,
                -1, 0, event.u, event.v, liftTo3D(event.activeFace, event.u, event.v));
        nodes.add(endNode);
        addArc(trace, endNode.nodeId, segment.parametricLength());
        trace.currentNodeId = endNode.nodeId;
        trace.arcNodeIds.add(endNode.nodeId);
        trace.alive = false;
        if (event.type == TraceEvent.TYPE_BOUNDARY) {
            attachTerminationToFeatureChain(trace, event, endNode);
        }
    }

    /**
     * Register a boundary termination as a meeting on the feature chain it
     * landed on, so the post-build subdivision splits the chain's arc at the
     * termination node — the boundary side of a patch must break exactly where
     * separatrices end on it.
     */
    private void attachTerminationToFeatureChain(Trace trace, TraceEvent event, TMeshNode endNode) {
        for (TraceSegment candidate : segmentIndex.segmentsOnFace(event.activeFace)) {
            Trace owner = traces.get(candidate.traceId);
            if (!owner.featureTrace) {
                continue;
            }
            double isoCoordinate = candidate.axis.holdsUConstant() ? event.u : event.v;
            double spanCoordinate = candidate.axis.holdsUConstant() ? event.v : event.u;
            if (Math.abs(isoCoordinate - candidate.isoValue) > TERMINATION_SNAP_EPS) {
                continue;
            }
            if (spanCoordinate < candidate.spanStart - TERMINATION_SNAP_EPS
                    || spanCoordinate > candidate.spanEnd + TERMINATION_SNAP_EPS) {
                continue;
            }
            double entrySpan = candidate.axis.holdsUConstant() ? candidate.entryV : candidate.entryU;
            double featureLength = candidate.parametricLengthAtEntry
                    + Math.abs(spanCoordinate - entrySpan);
            double alphaForFeature = Trace.computeAlphaIj(candidate.axis, candidate.sign,
                    trace.state.axis, trace.state.sign, featureLength, event.parametricLength);
            owner.recordMeeting(trace, featureLength, event.parametricLength,
                    alphaForFeature, alphaRadians,
                    candidate.axis, candidate.sign, trace.state.axis, trace.state.sign);
            owner.metOtherTraces.get(owner.metOtherTraces.size() - 1).intersectionNodeId = endNode.nodeId;
            return;
        }
    }

    private void finalizeOpenTraces(ChartWalker walker) {
        int finalized = 0;
        for (Trace trace : traces) {
            if (trace.featureTrace) {
                // Feature chains never run through the event loop; they get
                // their origin and terminal nodes at seeding time.
                continue;
            }
            if (trace.arcNodeIds.size() >= 2) {
                continue;
            }
            if (trace.segments.isEmpty()) {
                continue;
            }
            TraceSegment last = trace.segments.get(trace.segments.size() - 1);
            TMeshNode endNode = new TMeshNode(nextNodeId++, TMeshNode.TYPE_TRUNCATED,
                    -1, 0, last.exitU, last.exitV,
                    liftTo3D(last.activeFace, last.exitU, last.exitV));
            nodes.add(endNode);
            addArc(trace, endNode.nodeId, last.parametricLength());
            trace.currentNodeId = endNode.nodeId;
            trace.arcNodeIds.add(endNode.nodeId);
            trace.alive = false;
            finalized++;
        }
        if (finalized > 0) {
            System.out.printf("[motorcycle] finalizeOpenTraces patched %d unfinished traces%n", finalized);
        }
    }

    private Vector3f liftTo3D(int activeFace, double u, double v) {
        double[] cornerUv = new double[ChartWalker.CORNER_UV_FLOATS];
        new ChartWalker(seamless).faceCornerUv(activeFace, cornerUv);
        int faceId = seamless.mesh.faceIdAt(activeFace);
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        seamless.mesh.vertexPosition(seamless.mesh.faceVertexAt(faceId, 0), p0);
        seamless.mesh.vertexPosition(seamless.mesh.faceVertexAt(faceId, 1), p1);
        seamless.mesh.vertexPosition(seamless.mesh.faceVertexAt(faceId, 2), p2);
        double u0 = cornerUv[0];
        double v0 = cornerUv[1];
        double u1 = cornerUv[2];
        double v1 = cornerUv[3];
        double u2 = cornerUv[4];
        double v2 = cornerUv[5];
        double denom = (v1 - v2) * (u0 - u2) + (u2 - u1) * (v0 - v2);
        if (Math.abs(denom) < 1.0e-12) {
            return new Vector3f(p0);
        }
        double w0 = ((v1 - v2) * (u - u2) + (u2 - u1) * (v - v2)) / denom;
        double w1 = ((v2 - v0) * (u - u2) + (u0 - u2) * (v - v2)) / denom;
        double w2 = 1.0 - w0 - w1;
        return new Vector3f(
                (float) (w0 * p0.x + w1 * p1.x + w2 * p2.x),
                (float) (w0 * p0.y + w1 * p1.y + w2 * p2.y),
                (float) (w0 * p0.z + w1 * p1.z + w2 * p2.z));
    }

    private void addArc(Trace trace, int endNodeId, double parametricLength) {
        TraceArc arc = new TraceArc(nextArcId++, trace.traceId, trace.currentNodeId, endNodeId,
                trace.state.axis, parametricLength);
        arcs.add(arc);
    }

    /**
     * Rebuild each trace's arc chain so it passes through every meeting (not only
     * those where the trace was the active processor). Lyon §5.1 consistency
     * constraints and §4.2 validity constraints reference arcs as the unit of
     * quantization, so the chain must split at every T-mesh node a trace passes
     * through.
     *
     * <p>
     * Existing {@code motorcycle.arcs} are discarded and rebuilt with fresh ids;
     * node ids are preserved (the intersection node created for a meeting is reused
     * on both sides via {@code MetOtherTraceEntry.intersectionNodeId}).
     */
    private void subdivideArcsAtMeetings() {
        List<TraceArc> rebuilt = new ArrayList<>();
        int nextId = 0;
        for (Trace trace : traces) {
            if (trace.arcNodeIds.size() < 2) {
                continue;
            }
            int originNodeId = trace.arcNodeIds.get(0);
            int terminalNodeId = trace.arcNodeIds.get(trace.arcNodeIds.size() - 1);
            double terminalLength = trace.parametricLengthSoFar;

            List<MetOtherTraceEntry> sortedMeetings = new ArrayList<>(trace.metOtherTraces);
            sortedMeetings.sort((a, b) -> {
                int cmp = Double.compare(a.ourParametricLength, b.ourParametricLength);
                if (cmp != 0) {
                    return cmp;
                }
                return Integer.compare(a.otherTraceId, b.otherTraceId);
            });

            List<Integer> chainNodes = new ArrayList<>();
            List<Double> chainLengths = new ArrayList<>();
            chainNodes.add(originNodeId);
            chainLengths.add(0.0);
            for (MetOtherTraceEntry meeting : sortedMeetings) {
                if (meeting.intersectionNodeId < 0) {
                    continue;
                }
                if (meeting.intersectionNodeId == originNodeId
                        || meeting.intersectionNodeId == terminalNodeId) {
                    // Origin/terminal are added explicitly; co-located meetings
                    // sharing those node ids are emitted via the explicit appends.
                    continue;
                }
                int prev = chainNodes.get(chainNodes.size() - 1);
                if (meeting.intersectionNodeId == prev) {
                    continue;
                }
                chainNodes.add(meeting.intersectionNodeId);
                chainLengths.add(meeting.ourParametricLength);
            }
            boolean closedLoop = trace.featureTrace && terminalNodeId == originNodeId;
            if (closedLoop) {
                // A closed feature loop ends where it began: append the origin
                // again so the loop-closing arc back to it is emitted.
                chainNodes.add(terminalNodeId);
                chainLengths.add(terminalLength);
            } else if (terminalNodeId != originNodeId
                    && chainNodes.get(chainNodes.size() - 1) != terminalNodeId) {
                chainNodes.add(terminalNodeId);
                chainLengths.add(terminalLength);
            }

            trace.arcNodeIds.clear();
            trace.arcNodeIds.addAll(chainNodes);
            trace.currentNodeId = terminalNodeId;

            trace.chainArcIds.clear();
            trace.chainNodeLengths.clear();
            trace.chainNodeLengths.addAll(chainLengths);
            for (int k = 0; k < chainNodes.size() - 1; k++) {
                double length = chainLengths.get(k + 1) - chainLengths.get(k);
                TraceArc arc = new TraceArc(nextId++, trace.traceId,
                        chainNodes.get(k), chainNodes.get(k + 1),
                        trace.spawnAxis, length);
                rebuilt.add(arc);
                trace.chainArcIds.add(arc.arcId);
            }
        }
        arcs.clear();
        arcs.addAll(rebuilt);
        nextArcId = nextId;
    }

    private void assemblePatches() {

        int edgeCount = seamless.edgeCount;
        boolean[] traceCrossesActiveEdge = new boolean[edgeCount];
        crossingsByActiveEdge = new ArrayList<>(edgeCount);
        for (int activeEdge = 0; activeEdge < edgeCount; activeEdge++) {
            crossingsByActiveEdge.add(new ArrayList<>());
        }
        for (Trace trace : traces) {
            if (trace.featureTrace) {
                // Feature chains run along edges (and are duplicated per
                // incident face), so the consecutive-segment walk below would
                // mark fan spokes as crossed; their edges are marked wholesale
                // after this loop instead.
                continue;
            }
            for (int segmentIndex = 1; segmentIndex < trace.segments.size(); segmentIndex++) {
                int fromFace = trace.segments.get(segmentIndex - 1).activeFace;
                int toFace = trace.segments.get(segmentIndex).activeFace;
                if (fromFace == toFace) {
                    continue;
                }
                int sharedEdge = -1;
                int faceIdA = seamless.mesh.faceIdAt(fromFace);
                int faceIdB = seamless.mesh.faceIdAt(toFace);
                for (int edge = 0; edge < CORNERS; edge++) {
                    int edgeId = seamless.mesh.faceEdgeAt(faceIdA, edge);
                    int activeEdge = seamless.crossField.edgeIdToActive.get(edgeId);
                    HalfEdgeMesh.EdgeFaceIds edgeFaces = seamless.mesh.edgeFaceIds(activeEdge);
                    int other = edgeFaces.faceA == faceIdA ? edgeFaces.faceB : edgeFaces.faceA;
                    if (other == faceIdB) {
                        sharedEdge = activeEdge;
                        break;
                    }
                }
                if (sharedEdge >= 0) {
                    traceCrossesActiveEdge[sharedEdge] = true;
                    crossingsByActiveEdge.get(sharedEdge).add(new EdgeCrossing(
                            trace.traceId, trace.segments.get(segmentIndex).parametricLengthAtEntry));
                }
            }
        }
        for (int alignmentEdgeId : seamless.crossField.alignmentEdgeIds) {
            Integer activeEdge = seamless.crossField.edgeIdToActive.get(alignmentEdgeId);
            if (activeEdge != null) {
                traceCrossesActiveEdge[activeEdge] = true;
            }
        }
        patchIdByActiveFace = new int[faceCount];
        Arrays.fill(patchIdByActiveFace, -1);
        int nextPatchId = 0;
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            if (patchIdByActiveFace[activeFace] >= 0) {
                continue;
            }
            ArrayList<Integer> frontier = new ArrayList<>();
            frontier.add(activeFace);
            patchIdByActiveFace[activeFace] = nextPatchId;
            int head = 0;
            while (head < frontier.size()) {
                int frontierActiveFace = frontier.get(head++);
                int faceId = seamless.mesh.faceIdAt(frontierActiveFace);
                for (int edge = 0; edge < CORNERS; edge++) {
                    int edgeId = seamless.mesh.faceEdgeAt(faceId, edge);
                    int activeEdge = seamless.crossField.edgeIdToActive.get(edgeId);
                    if (traceCrossesActiveEdge[activeEdge]) {
                        continue;
                    }
                    HalfEdgeMesh.EdgeFaceIds edgeFaces = seamless.mesh.edgeFaceIds(activeEdge);
                    int neighborFaceId = edgeFaces.faceA == faceId ? edgeFaces.faceB : edgeFaces.faceA;
                    if (neighborFaceId < 0) {
                        continue;
                    }
                    int neighborActive = seamless.crossField.faceIdToActive.get(neighborFaceId);
                    if (patchIdByActiveFace[neighborActive] >= 0) {
                        continue;
                    }
                    patchIdByActiveFace[neighborActive] = nextPatchId;
                    frontier.add(neighborActive);
                }
            }
            nextPatchId++;
        }
        // patchIdByActiveFace is a display-only face coloring; the canonical
        // patch list is rebuilt from the arrangement walk afterwards, since
        // triangle-level regions collapse once several traces cross one face.
    }

    private void buildTraceRecordBuffer() {
        traceRecordsByFace = new float[this.faceCount][MAX_TRACE_RECORDS_PER_FACE * 4];
        HashMap<Integer, Integer> counts = new HashMap<>();
        for (Trace trace : traces) {
            for (TraceSegment segment : trace.segments) {
                int face = segment.activeFace;
                int slot = counts.getOrDefault(face, 0);
                if (slot >= MAX_TRACE_RECORDS_PER_FACE) {
                    continue;
                }
                float[] row = traceRecordsByFace[face];
                int base = slot * 4;
                row[base] = segment.axis.holdsUConstant() ? 1f : 0f;
                row[base + 1] = (float) segment.isoValue;
                row[base + 2] = (float) segment.spanStart;
                row[base + 3] = (float) segment.spanEnd;
                counts.put(face, slot + 1);
            }
        }
    }

    /**
     * Dump alive-trace bookkeeping when the simulation hits its wall-clock cap.
     * Prints the per-face distribution of alive traces and the per-face segment
     * histogram so we can tell whether the queue is stuck on a single runaway trace
     * or fanned out across many traces with no cross-axis neighbors.
     *
     * @param segmentIndex per-face trace segment index from the active simulation
     */
    private void dumpStallDiagnostics(FaceSegmentIndex segmentIndex) {
        final int dumpLimit = 16;
        int alivePrinted = 0;
        int aliveCount = 0;
        Map<Integer, List<Integer>> tracesByFace = new HashMap<>();
        for (Trace trace : traces) {
            if (!trace.alive || trace.featureTrace) {
                continue;
            }
            aliveCount++;
            tracesByFace.computeIfAbsent(trace.state.activeFace, f -> new ArrayList<>())
                    .add(trace.traceId);
        }
        System.out.printf("[motorcycle-stall] alive non-feature traces: %d, distinct faces: %d%n",
                aliveCount, tracesByFace.size());

        int facesWithMultipleTraces = 0;
        int facesWithCrossAxisAlive = 0;
        for (Map.Entry<Integer, List<Integer>> entry : tracesByFace.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            facesWithMultipleTraces++;
            boolean seenU = false;
            boolean seenV = false;
            for (int traceId : entry.getValue()) {
                Trace t = traces.get(traceId);
                if (t.state.axis == TraceAxis.U) {
                    seenU = true;
                } else {
                    seenV = true;
                }
            }
            if (seenU && seenV) {
                facesWithCrossAxisAlive++;
            }
        }
        System.out.printf(
                "[motorcycle-stall] faces with >=2 alive traces: %d, with cross-axis alive: %d%n",
                facesWithMultipleTraces, facesWithCrossAxisAlive);

        int totalSegments = 0;
        int maxSegmentsPerFace = 0;
        int facesWithSegments = 0;
        int facesWithCrossAxisSegments = 0;
        for (int face = 0; face < faceCount; face++) {
            List<TraceSegment> faceSegments = segmentIndex.segmentsOnFace(face);
            if (faceSegments.isEmpty()) {
                continue;
            }
            facesWithSegments++;
            totalSegments += faceSegments.size();
            if (faceSegments.size() > maxSegmentsPerFace) {
                maxSegmentsPerFace = faceSegments.size();
            }
            boolean uSeen = false;
            boolean vSeen = false;
            for (TraceSegment s : faceSegments) {
                if (s.axis == TraceAxis.U) {
                    uSeen = true;
                } else {
                    vSeen = true;
                }
            }
            if (uSeen && vSeen) {
                facesWithCrossAxisSegments++;
            }
        }
        System.out.printf(
                "[motorcycle-stall] segments indexed: total=%d facesWithSegments=%d maxPerFace=%d crossAxisFaces=%d totalFaces=%d%n",
                totalSegments, facesWithSegments, maxSegmentsPerFace, facesWithCrossAxisSegments, faceCount);

        int totalTraceSegments = 0;
        int maxTraceSegments = 0;
        int runawayTraceId = -1;
        for (Trace t : traces) {
            if (t.featureTrace) {
                continue;
            }
            totalTraceSegments += t.segments.size();
            if (t.segments.size() > maxTraceSegments) {
                maxTraceSegments = t.segments.size();
                runawayTraceId = t.traceId;
            }
        }
        System.out.printf(
                "[motorcycle-stall] trace.segments: total=%d maxPerTrace=%d (alive=%d)%n",
                totalTraceSegments, maxTraceSegments, aliveCount);

        final String segLineFmt = "[motorcycle-stall]   seg[%d] face=%d entry=(%.6f,%.6f) exit=(%.6f,%.6f) axis=%s sign=%+d L=%.6f%n";
        if (runawayTraceId >= 0) {
            Trace runaway = traces.get(runawayTraceId);
            System.out.printf(
                    "[motorcycle-stall] runaway trace=%d alive=%b feature=%b origin=%d spawnAxis=%s spawnSign=%+d"
                            + " currentFace=%d u=%.12f v=%.12f axis=%s sign=%+d incoming=%d parametricLength=%.6f"
                            + " arcNodes=%d metOther=%d%n",
                    runaway.traceId, runaway.alive, runaway.featureTrace, runaway.originNodeId,
                    runaway.spawnAxis, runaway.spawnSign,
                    runaway.state.activeFace, runaway.state.u, runaway.state.v,
                    runaway.state.axis, runaway.state.sign, runaway.state.incomingLocalEdgeIndex,
                    runaway.parametricLengthSoFar,
                    runaway.arcNodeIds.size(), runaway.metOtherTraces.size());
            int show = Math.min(runaway.segments.size(), 24);
            int total = runaway.segments.size();
            System.out.printf("[motorcycle-stall] runaway first %d segments (of %d):%n", show, total);
            for (int i = 0; i < show; i++) {
                TraceSegment seg = runaway.segments.get(i);
                System.out.printf(segLineFmt,
                        i, seg.activeFace, seg.entryU, seg.entryV, seg.exitU, seg.exitV,
                        seg.axis, seg.sign, seg.parametricLength());
            }
            if (total > show * 2) {
                System.out.printf("[motorcycle-stall] runaway last %d segments (of %d):%n", show, total);
                for (int i = total - show; i < total; i++) {
                    TraceSegment seg = runaway.segments.get(i);
                    System.out.printf(segLineFmt,
                            i, seg.activeFace, seg.entryU, seg.entryV, seg.exitU, seg.exitV,
                            seg.axis, seg.sign, seg.parametricLength());
                }
            }
        }

        for (Trace trace : traces) {
            if (!trace.alive || trace.featureTrace) {
                continue;
            }
            if (alivePrinted >= dumpLimit) {
                break;
            }
            alivePrinted++;
            int face = trace.state.activeFace;
            int segmentCount = segmentIndex.segmentsOnFace(face).size();
            int crossAxisCount = 0;
            for (TraceSegment seg : segmentIndex.segmentsOnFace(face)) {
                if (seg.axis != trace.state.axis) {
                    crossAxisCount++;
                }
            }
            System.out.printf(
                    "[motorcycle-stall] trace=%d face=%d u=%.12f v=%.12f axis=%s sign=%+d"
                            + " segmentsOnFace=%d crossAxis=%d arc=%d incoming=%d%n",
                    trace.traceId, face, trace.state.u, trace.state.v,
                    trace.state.axis, trace.state.sign,
                    segmentCount, crossAxisCount,
                    trace.arcNodeIds.size(), trace.state.incomingLocalEdgeIndex);
        }
    }
}
