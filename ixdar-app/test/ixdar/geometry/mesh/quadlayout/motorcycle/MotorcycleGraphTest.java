package ixdar.geometry.mesh.quadlayout.motorcycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    private static final long BUILD_TIMEOUT_MS = 120_000L;

    /**
     * Lyon motorcycle graph on figure-8 elk should finish with all singularity
     * traces terminated and at least one patch assembled.
     *
     * @throws Exception when mesh loading or pipeline build fails
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
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
        assertEquals(0, graph.aliveNonFeatureTraceCount(),
                "singularity traces should terminate during simulation");
        assertTrue(graph.patches.size() > 0, "expected at least one patch");
        assertTrue(graph.initialEventQueueSize > 100,
                "most singularity traces should enqueue a first event, got "
                        + graph.initialEventQueueSize);
        assertTrue(graph.spawnDeadCount < graph.spawnForwardCount,
                "forward spawn count should dominate deadAtSpawn");
        assertObservedMeetingAnglesAreGeometric(graph);
    }

    /**
     * Lyon §3 αij is the right-triangle angle at the singularity i, so its
     * recorded value lives in {@code (-π/2, π/2)} and routinely takes
     * non-axis-aligned values. The pre-fix code stored {@code signedAngleTo}
     * (spawn-direction difference) instead, which is always exactly in
     * {@code {0, ±π/2, π}} for axis-aligned traces — this assertion would have
     * been impossible to satisfy before the fix.
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
