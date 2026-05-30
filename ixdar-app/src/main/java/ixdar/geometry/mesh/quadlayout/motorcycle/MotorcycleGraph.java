package ixdar.geometry.mesh.quadlayout.motorcycle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;

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
    private static final int CORNERS = SeamlessParameterization.CORNERS_PER_FACE;
    private static final int DIE_SAMPLE_LIMIT = 12;
    private static final int PROGRESS_BAR_WIDTH = 30;
    private static final int PROGRESS_LOG_EVERY_EVENTS = 5000;
    private static final double PARAMETRIC_EPS = 1.0e-9;
    /** Hard cap on processed events so a stuck queue cannot run forever. */
    private static final int MAX_SIMULATION_EVENTS = 100_000;
    /**
     * Wall-clock budget for the simulation loop. ELK converges in well under a
     * second when the parametrization is correct; anything over this cap means
     * the queue is stuck (traces drifting off-axis, iso-lines that should
     * coincide split by floating-point noise, etc.) and we should abort fast
     * rather than burn the user's CPU.
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
    private int edgeCount;
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
        this.edgeCount = this.mesh.edgeCount();
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
        for (int activeEdge = 0; activeEdge < edgeCount; activeEdge++) {
            int edgeId = mesh.edgeIdAt(activeEdge);
            boolean boundary = mesh.isBoundaryEdge(edgeId);
            boolean alignment = seamless.crossField.alignmentEdgeIds.contains(edgeId);
            if (!boundary && !alignment) {
                continue;
            }
            HalfEdgeMesh.EdgeFaceIds edgeFaces = mesh.edgeFaceIds(activeEdge);
            if (edgeFaces.faceA < 0) {
                continue;
            }
            int activeFace = seamless.crossField.faceIdToActive.get(edgeFaces.faceA);
            double[] uv = new double[ChartWalker.CORNER_UV_FLOATS];
            walker.faceCornerUv(activeFace, uv);
            TracePort port = new TracePort(-1, activeFace, 0, TraceAxis.U, 1);
            double startU = uv[port.cornerIndex * 2];
            double startV = uv[port.cornerIndex * 2 + 1];
            int faceId = seamless.mesh.faceIdAt(port.activeFace);
            Vector3f position = seamless.mesh.vertexPosition(
                    seamless.mesh.faceVertexAt(faceId, port.cornerIndex));
            TMeshNode origin = new TMeshNode(nextNodeId++, TMeshNode.TYPE_FEATURE,
                    -1, 0, startU, startV, position);
            nodes.add(origin);
            Trace trace = new Trace(nextTraceId++, origin.nodeId, -1, port, startU, startV, true);
            traces.add(trace);
            TraceSegment segment = new TraceSegment(trace.traceId, activeFace,
                    uv[0], uv[1], uv[2], uv[3], TraceAxis.U, 1, 0.0);
            trace.segments.add(segment);
            segmentIndex.add(segment);
        }
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
        buildTraceRecordBuffer();
        System.out.printf(
                "[motorcycle] done traces=%d arcs=%d nodes=%d patches=%d %.2fs%n",
                traces.size(), arcs.size(), nodes.size(), patches.size(),
                (System.nanoTime() - buildStartNanos) / 1.0e9);
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
                trace.state.u, trace.state.v, exitU, exitV, trace.state.axis);
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
        double distanceAlongSegment = Math.abs(event.v - otherSegment.entryV);
        if (segment.axis == TraceAxis.U) {
            distanceAlongSegment = Math.abs(event.u - otherSegment.entryU);
        }
        double theirLength = otherSegment.parametricLengthAtEntry + distanceAlongSegment;
        double alphaIjForTi = Trace.computeAlphaIj(
                trace.state.axis, trace.state.sign,
                otherSegment.axis, otherSegment.sign,
                event.parametricLength, theirLength);
        double alphaJiForTj = Trace.computeAlphaIj(
                otherSegment.axis, otherSegment.sign,
                trace.state.axis, trace.state.sign,
                theirLength, event.parametricLength);
        trace.recordMeeting(other, event.parametricLength, theirLength, alphaIjForTi, alphaRadians);
        other.recordMeeting(trace, theirLength, event.parametricLength, alphaJiForTj, alphaRadians);

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
    }

    private void finalizeOpenTraces(ChartWalker walker) {
        int finalized = 0;
        for (Trace trace : traces) {
            if (trace.featureTrace) {
                // Feature traces seeded by seedFeatureTraces never run through the
                // event loop (#3 still pending — feature seeding is broken). Their
                // single synthetic segment exists only to seed FaceSegmentIndex.
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
            if (trace.featureTrace) {
                continue;
            }
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
            if (terminalNodeId != originNodeId
                    && chainNodes.get(chainNodes.size() - 1) != terminalNodeId) {
                chainNodes.add(terminalNodeId);
                chainLengths.add(terminalLength);
            }

            trace.arcNodeIds.clear();
            trace.arcNodeIds.addAll(chainNodes);
            trace.currentNodeId = terminalNodeId;

            for (int k = 0; k < chainNodes.size() - 1; k++) {
                double length = chainLengths.get(k + 1) - chainLengths.get(k);
                TraceArc arc = new TraceArc(nextId++, trace.traceId,
                        chainNodes.get(k), chainNodes.get(k + 1),
                        trace.spawnAxis, length);
                rebuilt.add(arc);
            }
        }
        arcs.clear();
        arcs.addAll(rebuilt);
        nextArcId = nextId;
    }

    private void assemblePatches() {

        int edgeCount = seamless.edgeCount;
        boolean[] traceCrossesActiveEdge = new boolean[edgeCount];
        for (Trace trace : traces) {
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
                }
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
            patches.add(new TMeshPatch(nextPatchId));
            nextPatchId++;
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
}
