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
    private static final int PROGRESS_BAR_WIDTH = 30;
    private static final int PROGRESS_LOG_EVERY_EVENTS = 5000;
    private static final long PROGRESS_LOG_EVERY_NANOS = 2_000_000_000L;
    private static final double PARAMETRIC_EPS = 1.0e-9;
    /** Hard cap on processed events so a stuck queue cannot run forever. */
    private static final int MAX_SIMULATION_EVENTS = 100_000;

    public final SeamlessParameterization seamless;
    public final float alphaRadians;

    public final List<TMeshNode> nodes = new ArrayList<>();
    public final List<TraceArc> arcs = new ArrayList<>();
    public final List<TMeshPatch> patches = new ArrayList<>();
    public final List<Trace> traces = new ArrayList<>();

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

    private int nextNodeId;
    private int nextArcId;
    private int nextTraceId;

    /**
     * Stores inputs for a Lyon §3 motorcycle graph build.
     *
     * @param seamless     built seamless parametrization
     * @param alphaRadians Lyon stopping bound α in radians
     */
    public MotorcycleGraph(SeamlessParameterization seamless, float alphaRadians) {
        this.seamless = seamless;
        this.alphaRadians = alphaRadians;
    }

    /**
     * Build the modified motorcycle graph T-mesh.
     *
     * @return this graph with populated nodes, arcs, patches, and traces
     * @throws IllegalStateException when {@code seamless} has not been built
     */
    public MotorcycleGraph build() {
        if (seamless.uCorner == null || seamless.vCorner == null || seamless.cutGraph == null) {
            throw new IllegalStateException("SeamlessParameterization must be built before MotorcycleGraph");
        }
        long buildStartNanos = System.nanoTime();
        HalfEdgeMesh mesh = seamless.mesh;
        CrossField crossField = seamless.crossField;
        int faceCount = mesh.faceCount();
        ChartWalker walker = new ChartWalker(seamless);
        FaceSegmentIndex segmentIndex = new FaceSegmentIndex(faceCount);

        System.out.println("[motorcycle] seeding singularity nodes and feature traces");
        seedSingularityNodes(crossField, mesh);
        seedFeatureTraces(walker, segmentIndex, mesh);
        int featureTraceCount = traces.size();

        List<TracePort> ports = TracePort.spawnFromSingularities(seamless);
        for (TracePort port : ports) {
            spawnTrace(port, walker, false);
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
        long lastLogNanos = simStartNanos;
        int eventsProcessed = 0;
        int lastAlive = -1;
        while (!queue.isEmpty()) {
            if (eventsProcessed >= MAX_SIMULATION_EVENTS) {
                System.out.printf(
                        "[motorcycle] event simulation stopped at max events=%d queue=%d alive=%d%n",
                        MAX_SIMULATION_EVENTS, queue.size(), aliveNonFeatureTraceCount());
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
            long now = System.nanoTime();
            if (eventsProcessed % PROGRESS_LOG_EVERY_EVENTS == 0
                    || now - lastLogNanos >= PROGRESS_LOG_EVERY_NANOS) {
                int alive = aliveNonFeatureTraceCount();
                printSimulationProgress(eventsProcessed, initialQueueSize, queue.size(), alive,
                        lastAlive, now - simStartNanos);
                lastAlive = alive;
                lastLogNanos = now;
            }
        }
        printSimulationProgress(eventsProcessed, initialQueueSize, 0, aliveNonFeatureTraceCount(),
                lastAlive, System.nanoTime() - simStartNanos);

        System.out.println("[motorcycle] finalizing open traces");
        finalizeOpenTraces(walker);
        System.out.println("[motorcycle] assembling patches");
        assemblePatches(faceCount);
        buildTraceRecordBuffer(faceCount);
        System.out.printf(
                "[motorcycle] done traces=%d arcs=%d nodes=%d patches=%d alive=%d (non-feature=%d)  %.2fs%n",
                traces.size(), arcs.size(), nodes.size(), patches.size(),
                aliveTraceCount(), aliveNonFeatureTraceCount(),
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

    /**
     * Counts non-feature traces whose {@link Trace#alive} flag is still set.
     *
     * @return number of singularity traces still marked alive (feature traces
     *         excluded)
     */
    public int aliveNonFeatureTraceCount() {
        int alive = 0;
        for (Trace trace : traces) {
            if (trace.alive && !trace.featureTrace) {
                alive++;
            }
        }
        return alive;
    }

    /**
     * Counts traces whose {@link Trace#alive} flag is still set.
     *
     * @return number of traces still marked alive (includes immortal feature
     *         traces)
     */
    public int aliveTraceCount() {
        int alive = 0;
        for (Trace trace : traces) {
            if (trace.alive) {
                alive++;
            }
        }
        return alive;
    }

    private void seedSingularityNodes(CrossField crossField, HalfEdgeMesh mesh) {
        Vector3f position = new Vector3f();
        for (Singularity singularity : crossField.singularities) {
            mesh.vertexPosition(singularity.vertexId(), position);
            TMeshNode node = new TMeshNode(nextNodeId++, TMeshNode.TYPE_SINGULARITY,
                    singularity.vertexId(), singularity.index4(), 0f, 0f, new Vector3f(position));
            nodes.add(node);
        }
    }

    private TMeshNode nodeForSingularity(int vertexId) {
        for (TMeshNode node : nodes) {
            if (node.type == TMeshNode.TYPE_SINGULARITY && node.singularityVertexId == vertexId) {
                return node;
            }
        }
        return null;
    }

    private void seedFeatureTraces(ChartWalker walker, FaceSegmentIndex segmentIndex, HalfEdgeMesh mesh) {
        for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
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
            float[] uv = new float[6];
            walker.faceCornerUv(activeFace, uv);
            TracePort port = new TracePort(-1, activeFace, 0, TraceAxis.U, 1);
            Trace trace = spawnFeatureTrace(port, uv);
            TraceSegment segment = new TraceSegment(trace.traceId, activeFace,
                    uv[0], uv[1], uv[2], uv[3], TraceAxis.U, 1, 0.0);
            trace.segments.add(segment);
            segmentIndex.add(segment);
        }
    }

    private Trace spawnFeatureTrace(TracePort port, float[] cornerUv) {
        float startU = cornerUv[port.cornerIndex * 2];
        float startV = cornerUv[port.cornerIndex * 2 + 1];
        TMeshNode origin = new TMeshNode(nextNodeId++, TMeshNode.TYPE_FEATURE,
                -1, 0, startU, startV, vertexPosition(port));
        nodes.add(origin);
        Trace trace = new Trace(nextTraceId++, origin.nodeId, -1, port, startU, startV, true);
        traces.add(trace);
        return trace;
    }

    private Trace spawnTrace(TracePort port, ChartWalker walker, boolean featureTrace) {
        float[] cornerUv = new float[6];
        walker.faceCornerUv(port.activeFace, cornerUv);
        float startU = cornerUv[port.cornerIndex * 2];
        float startV = cornerUv[port.cornerIndex * 2 + 1];
        TMeshNode origin = featureTrace
                ? new TMeshNode(nextNodeId++, TMeshNode.TYPE_FEATURE, -1, 0, startU, startV, vertexPosition(port))
                : nodeForSingularity(port.singularityVertexId);
        if (origin == null) {
            origin = new TMeshNode(nextNodeId++, TMeshNode.TYPE_SINGULARITY,
                    port.singularityVertexId, 0, startU, startV, vertexPosition(port));
            nodes.add(origin);
        }
        Trace trace = new Trace(nextTraceId++, origin.nodeId, port.singularityVertexId,
                port, startU, startV, featureTrace);
        traces.add(trace);
        return trace;
    }

    private Vector3f vertexPosition(TracePort port) {
        Vector3f position = new Vector3f();
        int faceId = seamless.mesh.faceIdAt(port.activeFace);
        seamless.mesh.vertexPosition(seamless.mesh.faceVertexAt(faceId, port.cornerIndex), position);
        return position;
    }

    private void enqueueNextEvent(Trace trace, ChartWalker walker,
            FaceSegmentIndex segmentIndex, PriorityQueue<TraceEvent> queue) {
        ChartWalker.State probe = new ChartWalker.State(trace.state);
        ChartWalker.EdgeHit edgeHit = walker.nextEdgeHit(probe);
        if (edgeHit == null) {
            trace.alive = false;
            return;
        }
        double edgeLength = edgeHit.parametricDelta;
        if (edgeLength < PARAMETRIC_EPS) {
            trace.alive = false;
            return;
        }
        float exitU = edgeHit.exitU;
        float exitV = edgeHit.exitV;

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
            return;
        }
        TraceSegment segment = new TraceSegment(trace.traceId, trace.state.activeFace,
                trace.state.u, trace.state.v, edgeHit.exitU, edgeHit.exitV,
                trace.state.axis, trace.state.sign, trace.parametricLengthSoFar);
        trace.segments.add(segment);
        segmentIndex.add(segment);
        trace.parametricLengthSoFar = event.parametricLength;

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

    private void handleIntersection(Trace trace, TraceEvent event, ChartWalker walker,
            FaceSegmentIndex segmentIndex, PriorityQueue<TraceEvent> queue) {
        Trace other = traces.get(event.otherTraceId);
        TraceSegment segment = new TraceSegment(trace.traceId, trace.state.activeFace,
                trace.state.u, trace.state.v, event.u, event.v,
                trace.state.axis, trace.state.sign, trace.parametricLengthSoFar);
        trace.segments.add(segment);
        segmentIndex.add(segment);
        trace.parametricLengthSoFar = event.parametricLength;

        TMeshNode intersectionNode = createIntersectionNode(event.activeFace, event.u, event.v);
        addArc(trace, intersectionNode.nodeId, event.parametricLength - segment.parametricLength());
        trace.currentNodeId = intersectionNode.nodeId;
        trace.arcNodeIds.add(intersectionNode.nodeId);

        TraceSegment otherSegment = event.otherSegment;
        double theirLength = otherSegment.parametricLengthAtEntry
                + distanceAlongSegment(otherSegment, event.u, event.v);
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

        if (!trace.alive) {
            return;
        }
        trace.state.u = event.u;
        trace.state.v = event.v;
        trace.state.incomingLocalEdgeIndex = -1;
        enqueueNextEvent(trace, walker, segmentIndex, queue);
    }

    private static double distanceAlongSegment(TraceSegment segment, float u, float v) {
        if (segment.axis == TraceAxis.U) {
            return Math.abs(u - segment.entryU);
        }
        return Math.abs(v - segment.entryV);
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

    private TMeshNode createIntersectionNode(int activeFace, float u, float v) {
        TMeshNode node = new TMeshNode(nextNodeId++, TMeshNode.TYPE_INTERSECTION,
                -1, 0, u, v, liftTo3D(activeFace, u, v));
        nodes.add(node);
        return node;
    }

    private Vector3f liftTo3D(int activeFace, float u, float v) {
        float[] cornerUv = new float[6];
        new ChartWalker(seamless).faceCornerUv(activeFace, cornerUv);
        int faceId = seamless.mesh.faceIdAt(activeFace);
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        seamless.mesh.vertexPosition(seamless.mesh.faceVertexAt(faceId, 0), p0);
        seamless.mesh.vertexPosition(seamless.mesh.faceVertexAt(faceId, 1), p1);
        seamless.mesh.vertexPosition(seamless.mesh.faceVertexAt(faceId, 2), p2);
        float u0 = cornerUv[0];
        float v0 = cornerUv[1];
        float u1 = cornerUv[2];
        float v1 = cornerUv[3];
        float u2 = cornerUv[4];
        float v2 = cornerUv[5];
        float denom = (v1 - v2) * (u0 - u2) + (u2 - u1) * (v0 - v2);
        if (Math.abs(denom) < 1.0e-12f) {
            return new Vector3f(p0);
        }
        float w0 = ((v1 - v2) * (u - u2) + (u2 - u1) * (v - v2)) / denom;
        float w1 = ((v2 - v0) * (u - u2) + (u0 - u2) * (v - v2)) / denom;
        float w2 = 1.0f - w0 - w1;
        return new Vector3f(
                w0 * p0.x + w1 * p1.x + w2 * p2.x,
                w0 * p0.y + w1 * p1.y + w2 * p2.y,
                w0 * p0.z + w1 * p1.z + w2 * p2.z);
    }

    private void addArc(Trace trace, int endNodeId, double parametricLength) {
        TraceArc arc = new TraceArc(nextArcId++, trace.traceId, trace.currentNodeId, endNodeId,
                trace.state.axis, parametricLength);
        arcs.add(arc);
    }

    private void assemblePatches(int faceCount) {
        boolean[] traceCrossesActiveEdge = markTraceCrossedEdges();
        patchIdByActiveFace = new int[faceCount];
        Arrays.fill(patchIdByActiveFace, -1);
        int nextPatchId = 0;
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            if (patchIdByActiveFace[activeFace] >= 0) {
                continue;
            }
            floodPatch(activeFace, nextPatchId, traceCrossesActiveEdge);
            patches.add(new TMeshPatch(nextPatchId));
            nextPatchId++;
        }
    }

    /**
     * Lyon §3 patches are bounded by motorcycle arcs, not by the seamless cut
     * graph. An arc enters one face and exits another across some mesh edge; we
     * recover those crossings by walking each trace's consecutive segments and
     * marking the shared active edge between adjacent faces.
     *
     * @return per-active-edge flag, true iff at least one trace crossed it
     */
    private boolean[] markTraceCrossedEdges() {
        int edgeCount = seamless.mesh.edgeCount();
        boolean[] crossed = new boolean[edgeCount];
        for (Trace trace : traces) {
            for (int segmentIndex = 1; segmentIndex < trace.segments.size(); segmentIndex++) {
                int fromFace = trace.segments.get(segmentIndex - 1).activeFace;
                int toFace = trace.segments.get(segmentIndex).activeFace;
                if (fromFace == toFace) {
                    continue;
                }
                int sharedEdge = sharedActiveEdge(fromFace, toFace);
                if (sharedEdge >= 0) {
                    crossed[sharedEdge] = true;
                }
            }
        }
        return crossed;
    }

    private int sharedActiveEdge(int activeFaceA, int activeFaceB) {
        int faceIdA = seamless.mesh.faceIdAt(activeFaceA);
        int faceIdB = seamless.mesh.faceIdAt(activeFaceB);
        for (int edge = 0; edge < CORNERS; edge++) {
            int edgeId = seamless.mesh.faceEdgeAt(faceIdA, edge);
            int activeEdge = seamless.crossField.edgeIdToActive.get(edgeId);
            HalfEdgeMesh.EdgeFaceIds edgeFaces = seamless.mesh.edgeFaceIds(activeEdge);
            int other = edgeFaces.faceA == faceIdA ? edgeFaces.faceB : edgeFaces.faceA;
            if (other == faceIdB) {
                return activeEdge;
            }
        }
        return -1;
    }

    private void floodPatch(int seedFace, int patchId, boolean[] traceCrossesActiveEdge) {
        ArrayList<Integer> frontier = new ArrayList<>();
        frontier.add(seedFace);
        patchIdByActiveFace[seedFace] = patchId;
        int head = 0;
        while (head < frontier.size()) {
            int activeFace = frontier.get(head++);
            int faceId = seamless.mesh.faceIdAt(activeFace);
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
                patchIdByActiveFace[neighborActive] = patchId;
                frontier.add(neighborActive);
            }
        }
    }

    private void buildTraceRecordBuffer(int faceCount) {
        traceRecordsByFace = new float[faceCount][MAX_TRACE_RECORDS_PER_FACE * 4];
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
                row[base + 1] = segment.isoValue;
                row[base + 2] = segment.spanStart;
                row[base + 3] = segment.spanEnd;
                counts.put(face, slot + 1);
            }
        }
    }
}
