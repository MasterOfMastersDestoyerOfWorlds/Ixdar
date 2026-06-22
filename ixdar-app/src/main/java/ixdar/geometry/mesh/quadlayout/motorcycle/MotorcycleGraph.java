package ixdar.geometry.mesh.quadlayout.motorcycle;

import java.util.ArrayList;
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
import ixdar.geometry.mesh.quadlayout.motorcycle.records.EdgeCrossing;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.FaceSegmentIndex;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.FeatureEdgeSpan;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.MetOtherTraceEntry;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TMeshNode;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TMeshPatch;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.Trace;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceArc;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceAxis;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceEvent;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TracePort;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceSegment;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * Lyon 2021 §3 modified motorcycle graph T-mesh: traces, nodes, arcs, and
 * patches built from a seamless parametrization.
 */
public final class MotorcycleGraph {

    public static final int MAX_TRACE_RECORDS_PER_FACE = 4;
    /**
     * Minimum cosine between consecutive alignment-edge directions for them to stay
     * in one feature chain.
     */
    static final double CHAIN_TURN_COS = Math.cos(Math.PI / 4.0);
    private static final int CORNERS = SeamlessParameterization.CORNERS_PER_FACE;
    private static final int DIE_SAMPLE_LIMIT = 12;
    private static final int PROGRESS_BAR_WIDTH = 30;
    private static final int PROGRESS_LOG_EVERY_EVENTS = 5000;
    /** Hard cap on processed events so a stuck queue cannot run forever. */
    private static final int MAX_SIMULATION_EVENTS = 100_000;
    /**
     * Wall-clock budget for the simulation loop.
     */
    private static final long MAX_SIMULATION_NANOS = 10L * 1_000_000_000L;

    public final SeamlessParameterization seamless;
    public final float alphaRadians;

    public List<TMeshNode> nodes;
    public List<TraceArc> arcs;
    public List<TMeshPatch> patches;
    public List<Trace> traces;

    public float[][] traceRecordsByFace;

    /**
     * Feature-chain lookup per alignment/boundary edge id: owning feature trace and
     * the chain-length interval the edge covers. Populated by the feature seeding
     * pass and consumed by {@link PatchBoundaryBuilder} to resolve patch-boundary
     * stretches that run along feature curves.
     */
    public final Map<Integer, FeatureEdgeSpan> featureSpanByEdgeId = new HashMap<>();

    /**
     * Per active edge, the trace crossings that severed it during patch assembly
     * (transversal crossings only — feature chains run along edges, not across
     * them). {@link PatchBoundaryBuilder} resolves each crossing to the T-mesh arc
     * containing its parametric length.
     */
    public List<List<EdgeCrossing>> crossingsByActiveEdge;

    /** Priority-queue size after seeding singularity trace events. */
    public int initialEventQueueSize;

    /** Crossings noded retroactively when a freshly laid segment swept its face. */
    public int retroactiveCrossingCount;

    /** Stale events dropped while their trace was still alive (orphan risk). */
    public int staleEventDropsForAliveTraces;

    /** Traces still alive when the event queue drained (orphaned motorcycles). */
    public int aliveAtQueueEndCount;

    /** Trace chains containing the same node at two different positions. */
    public int repeatedChainNodeCount;

    /**
     * The unique T-mesh node per mesh vertex that hosts one: singularity origins,
     * feature-chain corners, and singular-vertex terminations all resolve through
     * this map so a vertex never owns two nodes (two nodes on one vertex leave
     * degree-1 dead ends in the arrangement walk).
     */
    private final Map<Integer, TMeshNode> nodeByVertexId = new HashMap<>();

    private int nextNodeId;
    private int nextArcId;
    private int nextTraceId;

    private int faceCount;
    public HalfEdgeMesh mesh;
    private CrossField crossField;
    public ChartWalker walker;
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
            TMeshNode node = new TMeshNode(nextNodeId++, TMeshNode.Type.SINGULARITY,
                    singularity.vertexId(), -1, singularity.index4(), 0f, 0f, position);
            nodes.add(node);
            nodeByVertexId.put(singularity.vertexId(), node);
        }
        int featureTraceCount = traces.size();

        List<TracePort> ports = spawnFromSingularities();
        for (TracePort port : ports) {
            double[] cornerUv = new double[ChartWalker.CORNER_UV_FLOATS];
            seamless.faceCornerUv(port.activeFace, cornerUv);
            double startU = cornerUv[port.cornerIndex * 2];
            double startV = cornerUv[port.cornerIndex * 2 + 1];

            TMeshNode origin = nodeByVertexId.get(port.singularityVertexId);
            if (origin == null) {
                Vector3f position = new Vector3f();
                int faceId = seamless.mesh.faceIdAt(port.activeFace);
                seamless.mesh.vertexPosition(seamless.mesh.faceVertexAt(faceId, port.cornerIndex), position);
                origin = new TMeshNode(nextNodeId++, TMeshNode.Type.SINGULARITY,
                        port.singularityVertexId, -1, 0, startU, startV, position);
                nodes.add(origin);
                nodeByVertexId.put(port.singularityVertexId, origin);
            }
            Trace trace = new Trace(nextTraceId++, origin.nodeId, port.singularityVertexId,
                    port, startU, startV, false);
            traces.add(trace);
        }
        System.out.printf("[motorcycle] traces=%d (feature=%d singularity=%d) nodes=%d%n",
                traces.size(), featureTraceCount, ports.size(), nodes.size());

        PriorityQueue<TraceEvent> queue = new PriorityQueue<>();
        for (Trace trace : traces) {
            if (!trace.featureTrace) {
                enqueueNextEvent(trace, walker, segmentIndex, queue);
            }
        }
        int initialQueueSize = queue.size();
        initialEventQueueSize = initialQueueSize;
        System.out.printf("[motorcycle] event simulation: ports=%d queue=%d%n", ports.size(), initialQueueSize);

        long simStartNanos = System.nanoTime();
        int eventsProcessed = 0;
        int lastAlive = -1;
        while (!queue.isEmpty()) {
            if (eventsProcessed >= MAX_SIMULATION_EVENTS ||
                    System.nanoTime() - simStartNanos > MAX_SIMULATION_NANOS) {
                System.out.printf(
                        "[motorcycle] event simulation stopped at wall-clock cap %.1fs queue=%d events=%d%n",
                        MAX_SIMULATION_NANOS / 1.0e9, queue.size(), eventsProcessed);
                break;
            }
            TraceEvent event = queue.poll();
            eventsProcessed++;
            Trace trace = traces.get(event.traceId);
            if (!trace.alive) {
                continue;
            }
            if (event.serial != trace.pendingEventSerial) {

                if (trace.alive) {
                    staleEventDropsForAliveTraces++;
                }
                continue;
            }
            switch (event.type) {
            case TraceEvent.TYPE_INTERSECTION -> handleIntersection(
                    trace, event, walker, segmentIndex, queue);
            case TraceEvent.TYPE_EDGE -> handleEdgeCrossing(
                    trace, event, walker, segmentIndex, queue);
            case TraceEvent.TYPE_BOUNDARY, TraceEvent.TYPE_SINGULARITY -> handleTermination(trace, event, -1);
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
        for (Trace trace : traces) {
            if (!trace.alive || trace.featureTrace) {
                continue;
            }
            aliveAtQueueEndCount++;
            if (aliveAtQueueEndCount <= DIE_SAMPLE_LIMIT) {
                System.out.printf(
                        "[motorcycle-diag] orphaned trace=%d soFar=%.5f segments=%d meetings=%d"
                                + " face=%d u=%.5f v=%.5f axis=%s sign=%+d%n",
                        trace.traceId, trace.parametricLengthSoFar, trace.segments.size(),
                        trace.metOtherTraces.size(), trace.state.activeFace,
                        trace.state.u, trace.state.v, trace.state.axis, trace.state.sign);
            }
        }
        if (staleEventDropsForAliveTraces > 0 || aliveAtQueueEndCount > 0) {
            System.out.printf("[motorcycle-diag] staleDropsAlive=%d orphanedAtQueueEnd=%d%n",
                    staleEventDropsForAliveTraces, aliveAtQueueEndCount);
        }
        System.out.println("[motorcycle] finalizing open traces");
        finalizeOpenTraces();
        System.out.println("[motorcycle] subdividing arcs at every meeting");
        subdivideArcsAtMeetings();
        System.out.println("[motorcycle] assembling patches");
        assemblePatches();
        System.out.println("[motorcycle] resolving patch boundary arcs and sides");
        new PatchBoundaryBuilder(this).build();
        buildTraceRecordBuffer();
        System.out.printf(
                "[motorcycle] done traces=%d arcs=%d nodes=%d patches=%d"
                        + " retroactiveCrossings=%d %.2fs%n",
                traces.size(), arcs.size(), nodes.size(), patches.size(),
                retroactiveCrossingCount, (System.nanoTime() - buildStartNanos) / 1.0e9);
        return this;
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
        double edgeLength = edgeHit.parametricDelta;
        double exitU = edgeHit.exitU;
        double exitV = edgeHit.exitV;

        FaceSegmentIndex.IntersectionHit intersection = segmentIndex.earliestIntersection(
                trace.traceId, trace.state.activeFace,
                trace.state.u, trace.state.v, exitU, exitV, trace.state.axis,
                trace.parametricLengthSoFar, trace.faceVisitCount, trace.metOtherTraces);
        if (intersection != null && intersection.tAlongCandidate < edgeLength) {
            queue.add(new TraceEvent(TraceEvent.TYPE_INTERSECTION,
                    trace.parametricLengthSoFar + intersection.tAlongCandidate,
                    trace.traceId, intersection.otherSegment.traceId,
                    trace.state.activeFace,
                    intersection.intersectionU, intersection.intersectionV,
                    intersection.otherSegment, ++trace.pendingEventSerial));
            return;
        }
        if (edgeHit.boundary) {
            queue.add(new TraceEvent(TraceEvent.TYPE_BOUNDARY,
                    trace.parametricLengthSoFar + edgeLength,
                    trace.traceId, -1, trace.state.activeFace, exitU, exitV, null,
                    ++trace.pendingEventSerial));
            return;
        }
        queue.add(new TraceEvent(TraceEvent.TYPE_EDGE,
                trace.parametricLengthSoFar + edgeLength,
                trace.traceId, -1, trace.state.activeFace, exitU, exitV, null,
                ++trace.pendingEventSerial));
    }

    private void handleEdgeCrossing(Trace trace, TraceEvent event, ChartWalker walker,
            FaceSegmentIndex segmentIndex, PriorityQueue<TraceEvent> queue) {
        ChartWalker.EdgeHit edgeHit = walker.nextEdgeHit(trace.state);
        TraceSegment segment = new TraceSegment(trace.traceId, trace.state.activeFace,
                trace.faceVisitCount, trace.state.u, trace.state.v, edgeHit.exitU, edgeHit.exitV,
                trace.state.axis, trace.state.sign, trace.parametricLengthSoFar);
        trace.segments.add(segment);
        registerSegment(trace, segment);
        trace.parametricLengthSoFar = event.parametricLength;

        ChartWalker.State next = new ChartWalker.State(trace.state);
        if (!walker.crossEdge(trace.state, edgeHit, next)) {
            handleTermination(trace, new TraceEvent(TraceEvent.TYPE_BOUNDARY,
                    event.parametricLength, trace.traceId, -1, event.activeFace,
                    edgeHit.exitU, edgeHit.exitV, null, trace.pendingEventSerial), -1);
            return;
        }
        trace.state = next;
        trace.faceVisitCount++;
        enqueueNextEvent(trace, walker, segmentIndex, queue);
    }

    private void handleIntersection(Trace trace, TraceEvent event, ChartWalker walker,
            FaceSegmentIndex segmentIndex, PriorityQueue<TraceEvent> queue) {
        Trace other = traces.get(event.otherTraceId);
        if (meetingAlreadyRecorded(trace, event.otherSegment, trace.faceVisitCount)) {

            TraceSegment duplicate = new TraceSegment(trace.traceId, trace.state.activeFace,
                    trace.faceVisitCount, trace.state.u, trace.state.v, event.u, event.v,
                    trace.state.axis, trace.state.sign, trace.parametricLengthSoFar);
            trace.segments.add(duplicate);
            registerSegment(trace, duplicate);
            trace.parametricLengthSoFar = event.parametricLength;
            if (trace.alive) {
                advanceStateAlongLevel(trace.state, event.u, event.v);
                enqueueNextEvent(trace, walker, segmentIndex, queue);
            }
            return;
        }
        TraceSegment segment = new TraceSegment(trace.traceId, trace.state.activeFace,
                trace.faceVisitCount, trace.state.u, trace.state.v, event.u, event.v,
                trace.state.axis, trace.state.sign, trace.parametricLengthSoFar);
        trace.segments.add(segment);
        trace.parametricLengthSoFar = event.parametricLength;

        TraceSegment otherSegment = event.otherSegment;

        double distanceAlongSegment = otherSegment.axis == TraceAxis.U
                ? Math.abs(event.u - otherSegment.entryU)
                : Math.abs(event.v - otherSegment.entryV);
        double theirLength = otherSegment.parametricLengthAtEntry + distanceAlongSegment;

        TMeshNode intersectionNode = null;
        intersectionNode = new TMeshNode(nextNodeId++, TMeshNode.Type.INTERSECTION,
                -1, event.activeFace, 0, event.u, event.v,
                liftToPosition(mesh, walker, event.activeFace, event.u, event.v));
        nodes.add(intersectionNode);
        addArc(trace, intersectionNode.nodeId, event.parametricLength - segment.parametricLength());
        trace.currentNodeId = intersectionNode.nodeId;
        trace.arcNodeIds.add(intersectionNode.nodeId);
        double alphaIjForTi = Trace.computeAlphaIj(
                trace.state.axis, trace.state.sign,
                otherSegment.axis, otherSegment.sign,
                event.parametricLength, theirLength);
        double alphaJiForTj = Trace.computeAlphaIj(
                otherSegment.axis, otherSegment.sign,
                trace.state.axis, trace.state.sign,
                theirLength, event.parametricLength);
        trace.recordMeeting(other, event.parametricLength, theirLength, alphaIjForTi, alphaRadians,
                trace.state.axis, trace.state.sign, otherSegment.axis, otherSegment.sign,
                trace.faceVisitCount, otherSegment.visitId);
        other.recordMeeting(trace, theirLength, event.parametricLength, alphaJiForTj, alphaRadians,
                otherSegment.axis, otherSegment.sign, trace.state.axis, trace.state.sign,
                otherSegment.visitId, trace.faceVisitCount);

        trace.metOtherTraces.get(trace.metOtherTraces.size() - 1).intersectionNodeId = intersectionNode.nodeId;
        other.metOtherTraces.get(other.metOtherTraces.size() - 1).intersectionNodeId = intersectionNode.nodeId;

        registerSegment(trace, segment);

        if (!other.alive && other.arcNodeIds.size() < 2
                && other.currentNodeId != intersectionNode.nodeId) {
            addArc(other, intersectionNode.nodeId, theirLength);
            other.currentNodeId = intersectionNode.nodeId;
            other.arcNodeIds.add(intersectionNode.nodeId);
        }

        if (!trace.alive) {
            return;
        }
        advanceStateAlongLevel(trace.state, event.u, event.v);
        enqueueNextEvent(trace, walker, segmentIndex, queue);
    }

    /**
     * Advance a trace state to a meeting point while preserving the exact level
     * invariant: the held coordinate stays untouched (it IS the trace's level,
     * transported exactly through seams), only the varying coordinate takes the
     * constructed crossing value. Writing both from the constructed intersection
     * would drift the level by rounding and break the sign-predicate walk in
     * {@link ChartWalker#nextEdgeHit}. Resets the incoming edge since a meeting
     * point is interior to the face.
     *
     * @param state  trace state to advance in place
     * @param pointU u of the constructed meeting point
     * @param pointV v of the constructed meeting point
     */
    private static void advanceStateAlongLevel(ChartWalker.State state, double pointU, double pointV) {
        if (state.axis.holdsUConstant()) {
            state.v = pointV;
        } else {
            state.u = pointU;
        }
        state.incomingLocalEdgeIndex = -1;
    }

    /**
     * Terminate a trace at the event point. When the termination lies on a mesh
     * vertex that already owns a T-mesh node (singularity origins, feature
     * corners), that node is reused so the arriving arc joins the vertex's port fan
     * instead of dangling at a fresh degree-1 node — the dangling variant folds the
     * surrounding arrangement cycle back on itself and invalidates the patch.
     *
     * @param trace            trace to terminate
     * @param event            termination event carrying the end point
     * @param terminalVertexId mesh vertex the trace ended on, or -1 when the end
     *                         point is not a vertex
     */
    private void handleTermination(Trace trace, TraceEvent event, int terminalVertexId) {
        TraceSegment segment = new TraceSegment(trace.traceId, trace.state.activeFace,
                trace.faceVisitCount, trace.state.u, trace.state.v, event.u, event.v,
                trace.state.axis, trace.state.sign, trace.parametricLengthSoFar);
        trace.segments.add(segment);
        registerSegment(trace, segment);
        trace.parametricLengthSoFar = event.parametricLength;
        TMeshNode endNode = terminalVertexId >= 0 ? nodeByVertexId.get(terminalVertexId) : null;
        if (endNode == null) {
            endNode = new TMeshNode(nextNodeId++,
                    event.type == TraceEvent.TYPE_BOUNDARY ? TMeshNode.Type.BOUNDARY : TMeshNode.Type.SINGULARITY,
                    terminalVertexId, terminalVertexId >= 0 ? -1 : event.activeFace, 0,
                    event.u, event.v, liftToPosition(mesh, walker, event.activeFace, event.u, event.v));
            nodes.add(endNode);
            if (terminalVertexId >= 0) {
                nodeByVertexId.put(terminalVertexId, endNode);
            }
        }
        addArc(trace, endNode.nodeId, segment.parametricLength());
        trace.currentNodeId = endNode.nodeId;
        trace.arcNodeIds.add(endNode.nodeId);
        trace.alive = false;
        if (event.type == TraceEvent.TYPE_BOUNDARY) {
            attachTerminationToFeatureChain(trace, event, endNode);
        }
    }

    /**
     * Register a boundary termination as a meeting on the feature chain it landed
     * on, so the post-build subdivision splits the chain's arc at the termination
     * node — the boundary side of a patch must break exactly where separatrices end
     * on it.
     */
    private void attachTerminationToFeatureChain(Trace trace, TraceEvent event, TMeshNode endNode) {
        for (TraceSegment candidate : segmentIndex.segmentsOnFace(event.activeFace)) {
            Trace owner = traces.get(candidate.traceId);
            if (!owner.featureTrace) {
                continue;
            }
            double spanCoordinate = candidate.axis.holdsUConstant() ? event.v : event.u;
            double entrySpan = candidate.axis.holdsUConstant() ? candidate.entryV : candidate.entryU;
            double featureLength = candidate.parametricLengthAtEntry
                    + Math.abs(spanCoordinate - entrySpan);
            double alphaForFeature = Trace.computeAlphaIj(candidate.axis, candidate.sign,
                    trace.state.axis, trace.state.sign, featureLength, event.parametricLength);
            owner.recordMeeting(trace, featureLength, event.parametricLength,
                    alphaForFeature, alphaRadians,
                    candidate.axis, candidate.sign, trace.state.axis, trace.state.sign,
                    candidate.visitId, trace.faceVisitCount);
            owner.metOtherTraces.get(owner.metOtherTraces.size() - 1).intersectionNodeId = endNode.nodeId;
            return;
        }
    }

    private void finalizeOpenTraces() {
        int finalized = 0;
        for (Trace trace : traces) {
            if (trace.featureTrace) {

                continue;
            }
            if (trace.arcNodeIds.size() >= 2) {
                continue;
            }
            if (trace.segments.isEmpty()) {
                continue;
            }
            TraceSegment last = trace.segments.get(trace.segments.size() - 1);
            System.out.printf(
                    "[motorcycle-diag] truncated trace=%d alive=%b segments=%d lengthSoFar=%.5f"
                            + " meetings=%d lastFace=%d%n",
                    trace.traceId, trace.alive, trace.segments.size(), trace.parametricLengthSoFar,
                    trace.metOtherTraces.size(), last.activeFace);
            TMeshNode endNode = new TMeshNode(nextNodeId++, TMeshNode.Type.TRUNCATED,
                    -1, last.activeFace, 0, last.exitU, last.exitV,
                    liftToPosition(mesh, walker, last.activeFace, last.exitU, last.exitV));
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

    private void addArc(Trace trace, int endNodeId, double parametricLength) {
        TraceArc arc = new TraceArc(nextArcId++, trace.traceId, trace.currentNodeId, endNodeId,
                trace.state.axis, parametricLength);
        arcs.add(arc);
    }

    /**
     * Whether the trace already has a recorded meeting for this chord pair. A
     * meeting is identified combinatorially: this trace's face-visit chord and the
     * other segment's visit chord cross at most once, so the visit-id pair is an
     * exact key — no positional tolerance.
     *
     * @param trace        trace whose meetings to scan
     * @param otherSegment other trace's segment of the candidate meeting
     * @param ourVisitId   this trace's face-visit ordinal for the candidate
     * @return true when an equivalent meeting is already recorded
     */
    private static boolean meetingAlreadyRecorded(Trace trace, TraceSegment otherSegment, int ourVisitId) {
        for (MetOtherTraceEntry entry : trace.metOtherTraces) {
            if (entry.otherTraceId == otherSegment.traceId
                    && entry.ourVisitId == ourVisitId
                    && entry.otherVisitId == otherSegment.visitId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Index a freshly laid segment and retroactively node every perpendicular
     * crossing with segments already on the face. Segments register only at face
     * exit, so two traces traversing one face in the same time window never see
     * each other through the event queue — without this sweep the crossing stays
     * un-noded and the four arrangement quadrants around it fuse into one invalid
     * 12-corner cycle. Retroactive meetings are pure bookkeeping (Lyon traces
     * survive crossings): both traces get the meeting entry and the shared node,
     * and the post-build subdivision threads their chains through it; no stop test
     * is applied because the riders already drove past.
     *
     * @param trace   trace that laid the segment
     * @param segment the freshly laid segment
     */
    private void registerSegment(Trace trace, TraceSegment segment) {
        segmentIndex.add(segment);
        for (FaceSegmentIndex.IntersectionHit hit : segmentIndex.crossingsOf(segment)) {
            double ourLength = segment.parametricLengthAtEntry + hit.tAlongCandidate;
            Trace other = traces.get(hit.otherSegment.traceId);
            if (meetingAlreadyRecorded(trace, hit.otherSegment, segment.visitId)) {
                continue;
            }
            double distanceAlongOther = hit.otherSegment.axis == TraceAxis.U
                    ? Math.abs(hit.intersectionU - hit.otherSegment.entryU)
                    : Math.abs(hit.intersectionV - hit.otherSegment.entryV);
            double theirLength = hit.otherSegment.parametricLengthAtEntry + distanceAlongOther;
            TMeshNode node = new TMeshNode(nextNodeId++, TMeshNode.Type.INTERSECTION, -1,
                    segment.activeFace, 0, hit.intersectionU, hit.intersectionV,
                    liftToPosition(mesh, walker, segment.activeFace, hit.intersectionU, hit.intersectionV));
            nodes.add(node);
            double alphaIjForTi = Trace.computeAlphaIj(segment.axis, segment.sign,
                    hit.otherSegment.axis, hit.otherSegment.sign, ourLength, theirLength);
            double alphaJiForTj = Trace.computeAlphaIj(hit.otherSegment.axis, hit.otherSegment.sign,
                    segment.axis, segment.sign, theirLength, ourLength);
            MetOtherTraceEntry ourEntry = new MetOtherTraceEntry(other.traceId, alphaIjForTi,
                    ourLength, theirLength, segment.axis, segment.sign,
                    hit.otherSegment.axis, hit.otherSegment.sign,
                    segment.visitId, hit.otherSegment.visitId);
            ourEntry.intersectionNodeId = node.nodeId;
            trace.metOtherTraces.add(ourEntry);
            MetOtherTraceEntry theirEntry = new MetOtherTraceEntry(trace.traceId, alphaJiForTj,
                    theirLength, ourLength, hit.otherSegment.axis, hit.otherSegment.sign,
                    segment.axis, segment.sign,
                    hit.otherSegment.visitId, segment.visitId);
            theirEntry.intersectionNodeId = node.nodeId;
            other.metOtherTraces.add(theirEntry);
            retroactiveCrossingCount++;
        }
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

                chainNodes.add(terminalNodeId);
                chainLengths.add(terminalLength);
            } else if (terminalNodeId != originNodeId
                    && chainNodes.get(chainNodes.size() - 1) != terminalNodeId) {
                chainNodes.add(terminalNodeId);
                chainLengths.add(terminalLength);
            }
            Set<Integer> seenChainNodes = new HashSet<>(chainNodes.subList(0, chainNodes.size() - 1));
            if (seenChainNodes.size() < chainNodes.size() - 1) {
                repeatedChainNodeCount++;
                System.out.printf("[motorcycle-diag] repeated node in chain trace=%d nodes=%s%n",
                        trace.traceId, chainNodes);
                Set<Integer> reported = new HashSet<>();
                for (int position = 0; position < chainNodes.size(); position++) {
                    int nodeId = chainNodes.get(position);
                    if (chainNodes.indexOf(nodeId) == position || !reported.add(nodeId)) {
                        continue;
                    }
                    for (MetOtherTraceEntry meeting : trace.metOtherTraces) {
                        if (meeting.intersectionNodeId != nodeId) {
                            continue;
                        }
                        System.out.printf("[motorcycle-diag]   node=%d meeting other=%d"
                                + " ourLen=%.5f theirLen=%.5f collinear=%b%n",
                                nodeId, meeting.otherTraceId, meeting.ourParametricLength,
                                meeting.theirParametricLength,
                                meeting.ourAxis == meeting.otherAxis);
                    }
                }
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
     * Lift a chart-space point on one triangle to its 3D surface position.
     *
     * @param activeFace dense active face index of the containing triangle
     * @param u          chart u coordinate
     * @param v          chart v coordinate
     * @return surface position; the face's first corner position when the chart
     *         triangle is degenerate
     */
    public static Vector3f liftToPosition(HalfEdgeMesh mesh, ChartWalker walker, int activeFace, double u, double v) {
        double[] cornerUv = new double[ChartWalker.CORNER_UV_FLOATS];
        walker.seamless.faceCornerUv(activeFace, cornerUv);
        int faceId = mesh.faceIdAt(activeFace);
        Vector3f position0 = new Vector3f();
        Vector3f position1 = new Vector3f();
        Vector3f position2 = new Vector3f();
        mesh.vertexPosition(mesh.faceVertexAt(faceId, 0), position0);
        mesh.vertexPosition(mesh.faceVertexAt(faceId, 1), position1);
        mesh.vertexPosition(mesh.faceVertexAt(faceId, 2), position2);
        double u0 = cornerUv[0];
        double v0 = cornerUv[1];
        double u1 = cornerUv[2];
        double v1 = cornerUv[3];
        double u2 = cornerUv[4];
        double v2 = cornerUv[5];
        double denominator = (v1 - v2) * (u0 - u2) + (u2 - u1) * (v0 - v2);
        double w0 = ((v1 - v2) * (u - u2) + (u2 - u1) * (v - v2)) / denominator;
        double w1 = ((v2 - v0) * (u - u2) + (u0 - u2) * (v - v2)) / denominator;
        double w2 = 1.0 - w0 - w1;
        return new Vector3f(
                (float) (w0 * position0.x + w1 * position1.x + w2 * position2.x),
                (float) (w0 * position0.y + w1 * position1.y + w2 * position2.y),
                (float) (w0 * position0.z + w1 * position1.z + w2 * position2.z));
    }

    /**
     * Enumerate QEx Algorithm 4 ports at every cross-field singularity.
     *
     * @param seamless built seamless parametrization with populated UV corners
     * @return ports for every singularity; valence 3/5 counts emerge from geometry
     */
    public List<TracePort> spawnFromSingularities() {
        List<TracePort> ports = new ArrayList<>();
        for (Singularity singularity : crossField.singularities) {
            int vertexId = singularity.vertexId();
            int faceCount = mesh.vertexFaceCount(vertexId);
            for (int fanIndex = 0; fanIndex < faceCount; fanIndex++) {
                int faceId = mesh.vertexFaceAt(vertexId, fanIndex);
                int activeFace = crossField.faceIdToActive.get(faceId);
                int cornerIndex = cornerOfVertex(mesh, faceId, vertexId);
                double[] cornerUv = new double[ChartWalker.CORNER_UV_FLOATS];
                seamless.faceCornerUv(activeFace, cornerUv);
                int nextCorner = (cornerIndex + 1) % SeamlessParameterization.CORNERS_PER_FACE;
                int thirdCorner = (cornerIndex + 2) % SeamlessParameterization.CORNERS_PER_FACE;
                double uu = cornerUv[cornerIndex * 2];
                double uv = cornerUv[cornerIndex * 2 + 1];
                double vu = cornerUv[nextCorner * 2];
                double vv = cornerUv[nextCorner * 2 + 1];
                double wu = cornerUv[thirdCorner * 2];
                double wv = cornerUv[thirdCorner * 2 + 1];
                double orientation = orient2d(uu, uv, vu, vv, wu, wv);
                if (orientation > 0.0) {
                    for (int r = 0; r < SeamlessParameterization.BRANCH_COUNT; r++) {
                        double[] dir = switch (((r % 4) + 4) % 4) {
                        case 0 -> new double[] { 1.0, 0.0 };
                        case 1 -> new double[] { 0.0, 1.0 };
                        case 2 -> new double[] { -1.0, 0.0 };
                        default -> new double[] { 0.0, -1.0 };
                        };
                        boolean acceptCandidate = false;

                        double edgeU = vu - uu;
                        double edgeV = vv - uv;
                        if (orient2d(uu, uv, vu, vv, uu + dir[0], uv + dir[1]) > 0
                                && orient2d(uu, uv, uu + dir[0], uv + dir[1], wu, wv) > 0) {
                            acceptCandidate = true;
                        } else if (!(Math.abs(orient2d(0.0, 0.0, edgeU, edgeV, dir[0], dir[1])) <= 0)) {
                            acceptCandidate = false;
                        } else {
                            acceptCandidate = edgeU * dir[0] + edgeV * dir[1] > 0.0;
                        }
                        if (acceptCandidate) {
                            TraceAxis axis = TraceAxis.fromDirection(dir[0], dir[1]);
                            int sign = TraceAxis.signFor(axis, dir[0], dir[1]);
                            ports.add(new TracePort(vertexId, activeFace, cornerIndex, axis, sign));
                        }
                    }
                }
            }
        }
        return ports;
    }

    private static int cornerOfVertex(HalfEdgeMesh mesh, int faceId, int vertexId) {
        for (int corner = 0; corner < SeamlessParameterization.CORNERS_PER_FACE; corner++) {
            if (mesh.faceVertexAt(faceId, corner) == vertexId) {
                return corner;
            }
        }
        return 0;
    }

    /**
     * Signed area of triangle {@code (a, b, c)}; positive iff {@code c} lies to the
     * left of directed line {@code a → b}.
     *
     * @param ax x-coordinate of point a
     * @param ay y-coordinate of point a
     * @param bx x-coordinate of point b
     * @param by y-coordinate of point b
     * @param cx x-coordinate of point c
     * @param cy y-coordinate of point c
     * @return signed doubled triangle area
     */
    public static double orient2d(double ax, double ay, double bx, double by, double cx, double cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }
}
