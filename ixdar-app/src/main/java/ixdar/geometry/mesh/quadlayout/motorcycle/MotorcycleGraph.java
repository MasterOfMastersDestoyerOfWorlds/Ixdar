package ixdar.geometry.mesh.quadlayout.motorcycle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.nodes.api.BoolField;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.IntField;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.nodes.api.UvField;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;
import ixdar.geometry.mesh.quadlayout.ChartAtlas;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedNode;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.EdgeCrossing;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.FaceSegmentIndex;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.FeatureEdgeSpan;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.MetOtherTraceEntry;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.Trace;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceAxis;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceEvent;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TracePort;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceSegment;
import ixdar.platform.Platforms;

/**
 * Lyon 2021 §3 modified motorcycle graph T-mesh: traces, nodes, arcs, and
 * patches built from a seamless parametrization. As the registered
 * {@code motorcycle_graph} node, the registry instance is inert and evaluation
 * builds a fresh graph.
 *
 * <p>See also: Lyon 2021 Section 3, Eppstein 2008
 */
@MeshNodeAnnotation(id = "motorcycle_graph", desktopOnly = true)
public final class MotorcycleGraph implements MeshNode {

    public static final float DEFAULT_ALPHA_DEGREES = 15.0f;

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort UV = new InputPort("uv", PortType.UV_FIELD, null);
    public static final InputPort SINGULARITIES = new InputPort("singularities",
            PortType.INT, null);
    public static final InputPort FEATURE_EDGES = new InputPort("feature_edges",
            PortType.BOOLEAN, null);
    public static final InputPort ALPHA_DEGREES = new InputPort("alpha_degrees", PortType.FLOAT,
            DEFAULT_ALPHA_DEGREES);
    public static final InputPort CHARTS = new InputPort("charts", PortType.CHART_ATLAS, null);
    public static final OutputPort GRAPH = new OutputPort("graph", PortType.ARC_NETWORK);
    public static final OutputPort NODE_COUNT = new OutputPort("node_count", PortType.INT);
    public static final OutputPort ARC_COUNT = new OutputPort("arc_count", PortType.INT);
    public static final OutputPort PATCH_COUNT = new OutputPort("patch_count", PortType.INT);
    public static final OutputPort ORPHANED_TRACES = new OutputPort("orphaned_traces", PortType.INT);
    public static final OutputPort REPEATED_CHAIN_NODES = new OutputPort("repeated_chain_nodes",
            PortType.INT);

    public static final int MAX_TRACE_RECORDS_PER_FACE = 4;
    /**
     * Minimum cosine between consecutive alignment-edge directions for them to stay
     * in one feature chain.
     */
    static final double CHAIN_TURN_COS = Math.cos(Math.PI / 4.0);
    /** Number of cross-field branches (a 4-RoSy field has 4). */
    private static final int BRANCH_COUNT = 4;
    private static final int DIE_SAMPLE_LIMIT = 12;
    private static final int PROGRESS_BAR_WIDTH = 30;
    private static final int PROGRESS_LOG_EVERY_EVENTS = 5000;
    /** Hard cap on processed events so a stuck queue cannot run forever. */
    /**
     * Event backstop per source face. Every event either crosses a face edge or
     * nodes a crossing, so the arrangement cannot need more than a small multiple
     * of the face count; a flat cap starves a large mesh and truncates its traces
     * mid-flight.
     */
    private static final int MAX_EVENTS_PER_FACE = 8;
    /**
     * Wall-clock budget for the simulation loop.
     */
    private static final long MAX_SIMULATION_NANOS = 300L * 1_000_000_000L;

    /** Nanoseconds per second, for the backstop message. */
    private static final double NANOS_PER_SECOND = 1.0e9;

    public final UvField uv;

    /**
     * Per-vertex index4 of the cone points the separatrices spawn from and
     * terminate at, in dense active-vertex order; 0 = not singular.
     */
    public final IntField singularityIndex4;

    /**
     * Per-edge selection of feature and boundary curves the layout must respect,
     * in dense active-edge order.
     */
    public final BoolField featureEdges;

    public final float alphaRadians;

    /** The arrangement being built; every durable product lands here. */
    public ArcNetwork network;

    public List<EmbeddedNode> nodes;
    public List<EmbeddedArc> arcs;
    public List<EmbeddedPatch> patches;
    public List<Trace> traces;

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

    /** Crossing events whose chord pair was already met, so no node was made. */
    public int dedupedMeetingCount;

    /**
     * Crossings where the other trace was still alive, so the intersection node
     * joined only the candidate's chain. The other trace's boundary is left to the
     * subdivision pass.
     */
    public int liveOtherNoArcCount;

    /**
     * Traces whose last {@code arcNodeIds} entry is really an interior crossing,
     * because its node was appended after the trace had terminated. Such a node is
     * no longer treated as the trace's end, so the arc spanning that crossing keeps
     * a boundary at it.
     */
    public int droppedInteriorMeetingCount;

    /**
     * Traces whose chain node lengths are not monotonic. A node appended to the
     * chain after the trace has run past its crossing lands out of order, and the
     * arc spanning that crossing then keeps no boundary at it.
     */
    public int nonMonotonicChainCount;

    public HalfEdgeMesh mesh;
    public ChartWalker walker;

    /**
     * The unique T-mesh node per mesh vertex that hosts one: singularity origins,
     * feature-chain corners, and singular-vertex terminations all resolve through
     * this map so a vertex never owns two nodes (two nodes on one vertex leave
     * degree-1 dead ends in the arrangement walk).
     */
    private final Map<Integer, EmbeddedNode> nodeByVertexId = new HashMap<>();

    private int nextNodeId;
    private int nextArcId;
    private int nextTraceId;

    private int faceCount;
    private FaceSegmentIndex segmentIndex;

    /** Inert node-registry instance; evaluation builds a fresh graph. */
    public MotorcycleGraph() {
        this.uv = null;
        this.singularityIndex4 = null;
        this.featureEdges = null;
        this.alphaRadians = 0f;
    }

    /**
     * Stores inputs for a Lyon §3 motorcycle graph build.
     *
     * @param mesh              the parametrized mesh
     * @param uv                built seamless per-corner UV field over the mesh
     * @param charts            the parametrization's charts and cut transitions
     * @param singularityIndex4 the field's per-vertex cone-point index4 attribute
     * @param featureEdges      per-edge selection of feature and boundary curves
     * @param alphaRadians      Lyon stopping bound α in radians
     */
    public MotorcycleGraph(HalfEdgeMesh mesh, UvField uv, ChartAtlas charts,
            IntField singularityIndex4,
            BoolField featureEdges, float alphaRadians) {
        this.uv = uv;
        this.singularityIndex4 = singularityIndex4;
        this.featureEdges = featureEdges;
        this.alphaRadians = alphaRadians;

        this.mesh = mesh;
        this.faceCount = mesh.faceCount();
        this.walker = new ChartWalker(mesh, uv, charts, singularityIndex4);
        this.segmentIndex = new FaceSegmentIndex(faceCount);

        this.network = new ArcNetwork(mesh);
        this.nodes = network.nodes;
        this.arcs = network.arcs;
        this.patches = network.patches;
        this.traces = new ArrayList<>();
        network.traces = traces;
        network.featureSpanByEdgeId = featureSpanByEdgeId;
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, UV, CHARTS, SINGULARITIES, FEATURE_EDGES, ALPHA_DEGREES);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GRAPH, NODE_COUNT, ARC_COUNT, PATCH_COUNT, ORPHANED_TRACES,
                REPEATED_CHAIN_NODES);
    }

    @Override
    public String description() {
        return "Traces the motorcycle-graph T-mesh over a seamless parametrization: the"
                + " separatrix arrangement whose cells are the layout's patches.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.ofEntries(
                Map.entry(GEOMETRY.name, "Geometry carrying the parametrized triangle mesh."),
                Map.entry(UV.name, "Seamless per-corner UV field to trace, from a seamless_uv node."),
                Map.entry(SINGULARITIES.name,
                        "Per-vertex index4 of the cone points the separatrices spawn from"
                                + " (0 = not singular), from a cross_field node."),
                Map.entry(FEATURE_EDGES.name,
                        "Per-edge selection of feature and boundary curves, from a"
                                + " cross_field node."),
                Map.entry(ALPHA_DEGREES.name,
                        "Maximum separatrix deviation in degrees, the quality knob."),
                Map.entry(CHARTS.name,
                        "The parametrization's charts and cut transitions, from a seamless_uv"
                                + " node."),
                Map.entry(GRAPH.name, "The T-mesh arrangement: nodes, arcs, traces, and patches."),
                Map.entry(NODE_COUNT.name, "Number of T-mesh nodes in the arrangement."),
                Map.entry(ARC_COUNT.name, "Number of T-mesh arcs in the arrangement."),
                Map.entry(PATCH_COUNT.name, "Number of T-mesh patches (arrangement cells)."),
                Map.entry(ORPHANED_TRACES.name,
                        "Traces still alive when the event queue drained (0 expected)."),
                Map.entry(REPEATED_CHAIN_NODES.name,
                        "Trace chains containing one node twice (0 expected)."));
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle bundle = ctx.getInput(GEOMETRY.name, GeometryBundle.class);
        UvField inputUv = (UvField) ctx.getInput(UV.name, Object.class);
        IntField cones = (IntField) ctx.getInput(SINGULARITIES.name, Object.class);
        BoolField features = (BoolField) ctx.getInput(FEATURE_EDGES.name, Object.class);
        float alphaDegrees = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, ALPHA_DEGREES.name, ALPHA_DEGREES.defaultValue),
                DEFAULT_ALPHA_DEGREES);
        ChartAtlas chartsInput = (ChartAtlas) ctx.getInput(CHARTS.name, Object.class);
        HalfEdgeMesh inputMesh = HalfEdgeMeshEngine.fromMeshTopology(bundle.mesh());
        MotorcycleGraph graph = new MotorcycleGraph(inputMesh, inputUv, chartsInput, cones,
                features, (float) Math.toRadians(alphaDegrees));
        graph.build();
        ctx.setOutput(GRAPH.name, graph.network);
        ctx.setOutput(NODE_COUNT.name, graph.network.nodes.size());
        ctx.setOutput(ARC_COUNT.name, graph.network.arcs.size());
        ctx.setOutput(PATCH_COUNT.name, graph.network.patches.size());
        ctx.setOutput(ORPHANED_TRACES.name, graph.aliveAtQueueEndCount);
        ctx.setOutput(REPEATED_CHAIN_NODES.name, graph.repeatedChainNodeCount);
    }

    /**
     * Build the modified motorcycle graph T-mesh.
     *
     * @throws IllegalStateException when {@code seamless} has not been built
     * @return this graph with populated nodes, arcs, patches, and traces
     */
    public MotorcycleGraph build() {
        long buildStartNanos = System.nanoTime();

        Platforms.log("[motorcycle] seeding singularity nodes and feature traces");
        for (int v = 0; v < singularityIndex4.length(); v++) {
            int index4 = singularityIndex4.get(v);
            if (index4 == 0) {
                continue;
            }
            int vertexId = mesh.vertexIdAt(v);
            Vector3f position = mesh.vertexPosition(vertexId);
            EmbeddedNode node = new EmbeddedNode(nextNodeId++,
                    vertexId, -1, index4, 0f, 0f, position);
            node.critical = true;
            nodes.add(node);
            nodeByVertexId.put(vertexId, node);
        }
        int featureTraceCount = traces.size();

        List<TracePort> ports = spawnFromSingularities();
        for (TracePort port : ports) {
            double[] cornerUv = new double[ChartWalker.CORNER_UV_FLOATS];
            uv.faceCornerUv(mesh.faceIdAt(port.activeFace), cornerUv);
            double startU = cornerUv[port.cornerIndex * 2];
            double startV = cornerUv[port.cornerIndex * 2 + 1];

            EmbeddedNode origin = nodeByVertexId.get(port.singularityVertexId);
            if (origin == null) {
                Vector3f position = new Vector3f();
                int faceId = mesh.faceIdAt(port.activeFace);
                mesh.vertexPosition(mesh.faceVertexAt(faceId, port.cornerIndex), position);
                origin = new EmbeddedNode(nextNodeId++,
                        port.singularityVertexId, -1, 0, startU, startV, position);
                origin.critical = true;
                nodes.add(origin);
                nodeByVertexId.put(port.singularityVertexId, origin);
            }
            Trace trace = new Trace(nextTraceId++, origin.nodeId, port.singularityVertexId,
                    port, startU, startV, false);
            traces.add(trace);
        }
        Platforms.log("[motorcycle] traces=%d (feature=%d singularity=%d) nodes=%d%n",
                traces.size(), featureTraceCount, ports.size(), nodes.size());

        PriorityQueue<TraceEvent> queue = new PriorityQueue<>();
        for (Trace trace : traces) {
            if (!trace.featureTrace) {
                enqueueNextEvent(trace, walker, segmentIndex, queue);
            }
        }
        int initialQueueSize = queue.size();
        initialEventQueueSize = initialQueueSize;
        Platforms.log("[motorcycle] event simulation: ports=%d queue=%d%n", ports.size(), initialQueueSize);

        long simStartNanos = System.nanoTime();
        int eventsProcessed = 0;
        int lastAlive = -1;
        while (!queue.isEmpty()) {
            boolean outOfEvents = eventsProcessed >= MAX_EVENTS_PER_FACE * faceCount;
            long elapsedNanos = System.nanoTime() - simStartNanos;
            if (outOfEvents || elapsedNanos > MAX_SIMULATION_NANOS) {
                throw new IllegalStateException("motorcycle simulation hit its "
                        + (outOfEvents ? "event" : "wall-clock") + " backstop after "
                        + eventsProcessed + " events and " + elapsedNanos / NANOS_PER_SECOND
                        + "s with " + queue.size() + " still queued, from " + traces.size()
                        + " traces over " + faceCount + " faces; every trace still in flight"
                        + " would be truncated into a dangling arc, and LCBK19 Section 3.1"
                        + " guarantees a closed surface partitions into four-sided patches"
                        + " only, so continuing would silently build a non-rectangular"
                        + " arrangement. Raise MAX_EVENTS_PER_FACE or MAX_SIMULATION_NANOS,"
                        + " or find why the simulation is not converging");
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
        int aliveAtEnd = 0;
        for (Trace trace : traces) {
            if (trace.alive && !trace.featureTrace) {
                aliveAtEnd++;
            }
        }
        printSimulationProgress(eventsProcessed, initialQueueSize, queue.size(), aliveAtEnd,
                lastAlive, System.nanoTime() - simStartNanos);
        for (Trace trace : traces) {
            if (!trace.alive || trace.featureTrace) {
                continue;
            }
            aliveAtQueueEndCount++;
            if (aliveAtQueueEndCount <= DIE_SAMPLE_LIMIT) {
                Platforms.log(
                        "[motorcycle-diag] orphaned trace=%d soFar=%.5f segments=%d meetings=%d"
                                + " face=%d u=%.5f v=%.5f axis=%s sign=%+d%n",
                        trace.traceId, trace.parametricLengthSoFar, trace.segments.size(),
                        trace.metOtherTraces.size(), trace.state.activeFace,
                        trace.state.u, trace.state.v, trace.state.axis, trace.state.sign);
            }
        }
        if (staleEventDropsForAliveTraces > 0 || aliveAtQueueEndCount > 0) {
            Platforms.log("[motorcycle-diag] staleDropsAlive=%d orphanedAtQueueEnd=%d%n",
                    staleEventDropsForAliveTraces, aliveAtQueueEndCount);
        }
        Platforms.log("[motorcycle] finalizing open traces");
        finalizeOpenTraces();
        Platforms.log("[motorcycle] subdividing arcs at every meeting");
        subdivideArcsAtMeetings();
        Platforms.log("[motorcycle] assembling patches");
        assemblePatches();
        Platforms.log("[motorcycle] resolving patch boundary arcs and sides");
        new PatchBoundaryBuilder(this).build();
        buildTraceRecordBuffer();
        Platforms.log(
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
        Platforms.log("[motorcycle] %s events=%6d queue=%5d alive=%4d %s  %.2fs%n",
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
        segment.exitLocalEdgeIndex = edgeHit.localEdgeIndex;
        segment.exitEdgeParameter = edgeHit.edgeParameter;
        segment.exitAtCorner = edgeHit.cornerLocalIndex;
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

        EmbeddedNode intersectionNode = null;
        intersectionNode = new EmbeddedNode(nextNodeId++,
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
        if (other == trace) {
            // A self-crossing appends both meeting entries to the one list, so the
            // first call's entry is now second-to-last; stamp it too.
            trace.metOtherTraces.get(trace.metOtherTraces.size() - 2).intersectionNodeId = intersectionNode.nodeId;
        } else {
            other.metOtherTraces.get(other.metOtherTraces.size() - 1).intersectionNodeId = intersectionNode.nodeId;
        }

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
     * Advances a trace state to a meeting point, writing only the varying
     * coordinate: the held coordinate is the trace's exactly-transported level, and
     * rounding it to the constructed intersection would break the sign-predicate
     * walk in {@link ChartWalker#nextEdgeHit}. Resets the incoming edge, since a
     * meeting point is interior to the face.
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
     * Terminate a trace at the event point. A termination on a mesh vertex that
     * already owns a T-mesh node reuses that node, so the arriving arc joins the
     * vertex's port fan rather than dangling at a fresh degree-1 node.
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
        EmbeddedNode endNode = terminalVertexId >= 0 ? nodeByVertexId.get(terminalVertexId) : null;
        if (endNode == null) {
            endNode = new EmbeddedNode(nextNodeId++,
                    terminalVertexId, terminalVertexId >= 0 ? -1 : event.activeFace, 0,
                    event.u, event.v, liftToPosition(mesh, walker, event.activeFace, event.u, event.v));
            if (event.type == TraceEvent.TYPE_BOUNDARY) {
                endNode.border = true;
            } else {
                endNode.critical = true;
            }
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
    private void attachTerminationToFeatureChain(Trace trace, TraceEvent event, EmbeddedNode endNode) {
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
            Platforms.log(
                    "[motorcycle-diag] truncated trace=%d alive=%b segments=%d lengthSoFar=%.5f"
                            + " meetings=%d lastFace=%d%n",
                    trace.traceId, trace.alive, trace.segments.size(), trace.parametricLengthSoFar,
                    trace.metOtherTraces.size(), last.activeFace);
            EmbeddedNode endNode = new EmbeddedNode(nextNodeId++,
                    -1, last.activeFace, 0, last.exitU, last.exitV,
                    liftToPosition(mesh, walker, last.activeFace, last.exitU, last.exitV));
            endNode.truncated = true;
            nodes.add(endNode);
            addArc(trace, endNode.nodeId, last.parametricLength());
            trace.currentNodeId = endNode.nodeId;
            trace.arcNodeIds.add(endNode.nodeId);
            trace.alive = false;
            finalized++;
        }
        if (finalized > 0) {
            Platforms.log("[motorcycle] finalizeOpenTraces patched %d unfinished traces%n", finalized);
        }
    }

    private void addArc(Trace trace, int endNodeId, double parametricLength) {
        EmbeddedArc arc = new EmbeddedArc(nextArcId++, trace.traceId, trace.currentNodeId,
                endNodeId, parametricLength);
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
     * Indexes a freshly laid segment and retroactively nodes every perpendicular
     * crossing with segments already on the face, which the event queue cannot see
     * because segments register only at face exit. Both traces get a meeting entry
     * and share the node; no stop test is applied.
     *
     * @param trace   trace that laid the segment
     * @param segment the freshly laid segment
     */
    private void registerSegment(Trace trace, TraceSegment segment) {
        segmentIndex.add(segment);
        for (FaceSegmentIndex.IntersectionHit hit : segmentIndex.contactsOf(segment)) {
            double ourLength = segment.parametricLengthAtEntry + hit.tAlongCandidate;
            Trace other = traces.get(hit.otherSegment.traceId);
            if (meetingAlreadyRecorded(trace, hit.otherSegment, segment.visitId)) {
                continue;
            }
            double distanceAlongOther = hit.otherSegment.axis == TraceAxis.U
                    ? Math.abs(hit.intersectionU - hit.otherSegment.entryU)
                    : Math.abs(hit.intersectionV - hit.otherSegment.entryV);
            double theirLength = hit.otherSegment.parametricLengthAtEntry + distanceAlongOther;
            EmbeddedNode node = new EmbeddedNode(nextNodeId++, -1,
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
     * Rebuilds each trace's arc chain so it splits at every T-mesh node the trace
     * passes through, since arcs are the unit of quantization. Existing arcs are
     * discarded and rebuilt with fresh ids; node ids are preserved.
     *
     * <p>
     * See also: Lyon 2021 Section 5.1
     */
    private void subdivideArcsAtMeetings() {
        List<EmbeddedArc> rebuilt = new ArrayList<>();
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

            boolean terminalIsInterior = false;
            for (MetOtherTraceEntry meeting : sortedMeetings) {
                if (meeting.intersectionNodeId == terminalNodeId
                        && meeting.otherTraceId != trace.traceId
                        && meeting.ourParametricLength < terminalLength) {
                    terminalIsInterior = true;
                    droppedInteriorMeetingCount++;
                }
            }

            List<Integer> chainNodes = new ArrayList<>();
            List<Double> chainLengths = new ArrayList<>();
            chainNodes.add(originNodeId);
            chainLengths.add(0.0);
            for (MetOtherTraceEntry meeting : sortedMeetings) {
                if (meeting.intersectionNodeId < 0) {
                    continue;
                }
                boolean selfMeeting = meeting.otherTraceId == trace.traceId;
                boolean atOrigin = meeting.intersectionNodeId == originNodeId
                        && meeting.ourParametricLength <= 0.0;
                boolean atTerminal = meeting.intersectionNodeId == terminalNodeId
                        && meeting.ourParametricLength >= terminalLength;
                if (!selfMeeting && (atOrigin || atTerminal)) {
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
            } else if (terminalNodeId != originNodeId && !terminalIsInterior
                    && chainNodes.get(chainNodes.size() - 1) != terminalNodeId) {
                chainNodes.add(terminalNodeId);
                chainLengths.add(terminalLength);
            }
            Set<Integer> selfCrossingNodes = new HashSet<>();
            for (MetOtherTraceEntry meeting : trace.metOtherTraces) {
                if (meeting.otherTraceId == trace.traceId && meeting.intersectionNodeId >= 0) {
                    selfCrossingNodes.add(meeting.intersectionNodeId);
                }
            }
            List<Integer> nonSelfChain = new ArrayList<>(chainNodes.subList(0, chainNodes.size() - 1));
            nonSelfChain.removeIf(selfCrossingNodes::contains);
            if (new HashSet<>(nonSelfChain).size() < nonSelfChain.size()) {
                repeatedChainNodeCount++;
                Platforms.log("[motorcycle-diag] repeated node in chain trace=%d nodes=%s%n",
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
                        Platforms.log("[motorcycle-diag]   node=%d meeting other=%d"
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
                EmbeddedArc arc = new EmbeddedArc(nextId++, trace.traceId,
                        chainNodes.get(k), chainNodes.get(k + 1), length);
                rebuilt.add(arc);
                trace.chainArcIds.add(arc.arcId);
            }
        }
        arcs.clear();
        arcs.addAll(rebuilt);
        nextArcId = nextId;
    }

    private void assemblePatches() {

        int edgeCount = mesh.edgeCount();
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
                int faceIdA = mesh.faceIdAt(fromFace);
                int faceIdB = mesh.faceIdAt(toFace);
                for (int edge = 0; edge < HalfEdgeMesh.TRIANGLE_CORNERS; edge++) {
                    int edgeId = mesh.faceEdgeAt(faceIdA, edge);
                    int activeEdge = mesh.activeEdgeIndexOf(edgeId);
                    HalfEdgeMesh.EdgeFaceIds edgeFaces = mesh.edgeFaceIds(activeEdge);
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
        for (int activeEdge = 0; activeEdge < featureEdges.length(); activeEdge++) {
            if (featureEdges.get(activeEdge)) {
                traceCrossesActiveEdge[activeEdge] = true;
            }
        }
    }

    private void buildTraceRecordBuffer() {
        network.traceRecordsByFace = new float[this.faceCount][MAX_TRACE_RECORDS_PER_FACE * 4];
        float[][] traceRecordsByFace = network.traceRecordsByFace;
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
     * @param mesh       half-edge mesh supplying the triangle's vertex positions
     * @param walker     chart walker whose seamless map gives the corner UVs
     * @param activeFace dense active face index of the containing triangle
     * @param u          chart u coordinate
     * @param v          chart v coordinate
     * @return surface position; the face's first corner position when the chart
     *         triangle is degenerate
     */
    public static Vector3f liftToPosition(HalfEdgeMesh mesh, ChartWalker walker, int activeFace, double u, double v) {
        int faceId = mesh.faceIdAt(activeFace);
        double[] cornerUv = new double[ChartWalker.CORNER_UV_FLOATS];
        walker.uv.faceCornerUv(faceId, cornerUv);
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
     * @return ports for every singularity; valence 3/5 counts emerge from geometry
     */
    public List<TracePort> spawnFromSingularities() {
        List<TracePort> ports = new ArrayList<>();
        for (int v = 0; v < singularityIndex4.length(); v++) {
            if (singularityIndex4.get(v) == 0) {
                continue;
            }
            int vertexId = mesh.vertexIdAt(v);
            int faceCount = mesh.vertexFaceCount(vertexId);
            for (int fanIndex = 0; fanIndex < faceCount; fanIndex++) {
                int faceId = mesh.vertexFaceAt(vertexId, fanIndex);
                int activeFace = mesh.activeFaceIndexOf(faceId);
                int cornerIndex = cornerOfVertex(mesh, faceId, vertexId);
                double[] cornerUv = new double[ChartWalker.CORNER_UV_FLOATS];
                uv.faceCornerUv(faceId, cornerUv);
                int nextCorner = (cornerIndex + 1) % HalfEdgeMesh.TRIANGLE_CORNERS;
                int thirdCorner = (cornerIndex + 2) % HalfEdgeMesh.TRIANGLE_CORNERS;
                double au = cornerUv[cornerIndex * 2];
                double av = cornerUv[cornerIndex * 2 + 1];
                double bu = cornerUv[nextCorner * 2];
                double bv = cornerUv[nextCorner * 2 + 1];
                double cu = cornerUv[thirdCorner * 2];
                double cv = cornerUv[thirdCorner * 2 + 1];
                double orientation = orient2d(au, av, bu, bv, cu, cv);
                if (orientation > 0.0) {
                    for (int r = 0; r < BRANCH_COUNT; r++) {
                        double[] dir = switch (((r % 4) + 4) % 4) {
                        case 0 -> new double[] { 1.0, 0.0 };
                        case 1 -> new double[] { 0.0, 1.0 };
                        case 2 -> new double[] { -1.0, 0.0 };
                        default -> new double[] { 0.0, -1.0 };
                        };
                        boolean acceptCandidate = false;

                        double edgeU = bu - au;
                        double edgeV = bv - av;
                        if (orient2d(au, av, bu, bv, au + dir[0], av + dir[1]) > 0
                                && orient2d(au, av, au + dir[0], av + dir[1], cu, cv) > 0) {
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
        for (int corner = 0; corner < HalfEdgeMesh.TRIANGLE_CORNERS; corner++) {
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
