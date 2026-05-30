package ixdar.geometry.mesh.quadlayout.motorcycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * Integration tests for {@link MotorcycleGraph#build()} on paper meshes.
 */
public class MotorcycleGraphTest {

    private static final String ELK_OFF = "test/resources/quadlayout/figure_8/elk_in_tri.off";
    private static final float ALPHA_RADIANS = (float) Math.toRadians(15.0);
    private static final long BUILD_TIMEOUT_MS = 10_000L;

    /**
     * Lyon motorcycle graph on figure-8 elk should finish with all singularity
     * traces terminated and at least one patch assembled.
     *
     * @throws Exception when mesh loading or pipeline build fails
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void buildOnElkCompletesWithOnlyFeatureTracesAlive() throws Exception {
        ArrayMesh arrayMesh = MeshLoader.load(ELK_OFF);
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());
        CrossField crossField = new CrossField(mesh).build();
        SeamlessParameterization seamless = new SeamlessParameterization(crossField);
        seamless.build();

        long startNanos = System.nanoTime();
        MotorcycleGraph graph = new MotorcycleGraph(seamless, ALPHA_RADIANS).build();
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        assertTrue(elapsedMs < BUILD_TIMEOUT_MS,
                "motorcycle build exceeded " + BUILD_TIMEOUT_MS + "ms, took " + elapsedMs);
        int aliveNonFeature = 0;
        for (Trace t : graph.traces) {
            if (t.alive && !t.featureTrace) {
                aliveNonFeature++;
            }
        }
        assertEquals(0, aliveNonFeature,
                "singularity traces should terminate during simulation");
        assertTrue(graph.patches.size() > 1,
                "Lyon §3 patches are bounded by motorcycle arcs; expected many on ELK, got "
                        + graph.patches.size());
        assertTrue(graph.initialEventQueueSize > 100,
                "most singularity traces should enqueue a first event, got "
                        + graph.initialEventQueueSize);
        assertTrue(graph.spawnDeadCount < graph.spawnForwardCount,
                "forward spawn count should dominate deadAtSpawn");
        assertObservedMeetingAnglesAreGeometric(graph);
        logNodeTypeBreakdown(graph);
        assertArcSubdivisionInvariants(graph);
    }

    /**
     * After the post-build subdivision pass, every non-feature trace's
     * {@code arcNodeIds} chain matches the start/end node ids of its arcs in order,
     * and the chain crosses every meeting recorded on that trace.
     *
     * @param graph built motorcycle graph
     */
    private static void assertArcSubdivisionInvariants(MotorcycleGraph graph) {
        Map<Integer, List<TraceArc>> arcsByTrace = new HashMap<>();
        for (TraceArc arc : graph.arcs) {
            arcsByTrace.computeIfAbsent(arc.traceId, id -> new ArrayList<>()).add(arc);
        }
        int chainsChecked = 0;
        int meetingsCoveredTotal = 0;
        for (Trace trace : graph.traces) {
            if (trace.featureTrace) {
                continue;
            }
            if (trace.arcNodeIds.size() < 2) {
                continue;
            }
            java.util.List<TraceArc> arcs = arcsByTrace.getOrDefault(trace.traceId, java.util.List.of());
            assertEquals(trace.arcNodeIds.size() - 1, arcs.size(),
                    "trace " + trace.traceId + " should have arcNodeIds.size-1 arcs");
            for (int k = 0; k < arcs.size(); k++) {
                TraceArc arc = arcs.get(k);
                assertEquals((int) trace.arcNodeIds.get(k), arc.startNodeId,
                        "arc " + arc.arcId + " startNode must match arcNodeIds[" + k + "]");
                assertEquals((int) trace.arcNodeIds.get(k + 1), arc.endNodeId,
                        "arc " + arc.arcId + " endNode must match arcNodeIds[" + (k + 1) + "]");
            }
            java.util.Set<Integer> chainNodeSet = new java.util.HashSet<>(trace.arcNodeIds);
            for (MetOtherTraceEntry meeting : trace.metOtherTraces) {
                if (meeting.intersectionNodeId < 0) {
                    continue;
                }
                if (!chainNodeSet.contains(meeting.intersectionNodeId)) {
                    StringBuilder report = new StringBuilder();
                    report.append("trace ").append(trace.traceId)
                            .append(" arc chain missing meeting node ").append(meeting.intersectionNodeId)
                            .append("\n  parametricLengthSoFar=").append(trace.parametricLengthSoFar)
                            .append(" arcNodeIds=").append(trace.arcNodeIds)
                            .append("\n  meetings (sorted by ourLength):");
                    java.util.List<MetOtherTraceEntry> sorted = new java.util.ArrayList<>(trace.metOtherTraces);
                    sorted.sort(java.util.Comparator.comparingDouble(e -> e.ourParametricLength));
                    for (MetOtherTraceEntry e : sorted) {
                        report.append("\n    nodeId=").append(e.intersectionNodeId)
                                .append(" ours=").append(e.ourParametricLength)
                                .append(" theirs=").append(e.theirParametricLength)
                                .append(" otherTraceId=").append(e.otherTraceId);
                    }
                    org.junit.jupiter.api.Assertions.fail(report.toString());
                }
                meetingsCoveredTotal++;
            }
            chainsChecked++;
        }
        System.out.printf("[motorcycle-test] subdivision: chainsChecked=%d meetingsCovered=%d%n",
                chainsChecked, meetingsCoveredTotal);
    }

    private static void logNodeTypeBreakdown(MotorcycleGraph graph) {
        int singularity = 0;
        int intersection = 0;
        int boundary = 0;
        int feature = 0;
        int truncated = 0;
        for (TMeshNode node : graph.nodes) {
            switch (node.type) {
            case TMeshNode.TYPE_SINGULARITY -> singularity++;
            case TMeshNode.TYPE_INTERSECTION -> intersection++;
            case TMeshNode.TYPE_BOUNDARY -> boundary++;
            case TMeshNode.TYPE_FEATURE -> feature++;
            case TMeshNode.TYPE_TRUNCATED -> truncated++;
            default -> {
            }
            }
        }
        System.out.printf(
                "[motorcycle-test] nodes: singularity=%d intersection=%d boundary=%d feature=%d truncated=%d%n",
                singularity, intersection, boundary, feature, truncated);
        assertEquals(0, truncated,
                "every singularity trace must terminate via intersection / singularity / boundary, "
                        + "not the finalizeOpenTraces safety net; got " + truncated + " TYPE_TRUNCATED nodes");
    }

    /**
     * Lyon §3 αij is the right-triangle angle at the singularity i, so its recorded
     * value lives in {@code (-π/2, π/2)} and routinely takes non-axis-aligned
     * values. The pre-fix code stored {@code signedAngleTo} (spawn-direction
     * difference) instead, which is always exactly in {@code {0, ±π/2, π}} for
     * axis-aligned traces — this assertion would have been impossible to satisfy
     * before the fix.
     *
     * @param graph built motorcycle graph
     */
    private static void assertObservedMeetingAnglesAreGeometric(MotorcycleGraph graph) {
        int offAxisMeetings = 0;
        for (Trace trace : graph.traces) {
            for (MetOtherTraceEntry meeting : trace.metOtherTraces) {
                double abs = Math.abs(meeting.signedAngle);
                assertTrue(abs <= Math.PI / 2.0 + 1.0e-9,
                        "αij must lie in (-π/2, π/2], got " + meeting.signedAngle);
                double nearestAxis = Math.min(abs, Math.min(
                        Math.abs(abs - Math.PI / 2.0), Math.abs(abs - Math.PI)));
                if (nearestAxis > 1.0e-3) {
                    offAxisMeetings++;
                }
            }
        }
        assertTrue(offAxisMeetings > 0,
                "expected at least one off-axis αij meeting; got 0 (pre-fix behavior)");
    }
}
