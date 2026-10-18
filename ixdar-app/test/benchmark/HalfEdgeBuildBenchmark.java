package benchmark;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.nodegraph.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.modifier.RepairMeshNode;
import ixdar.platform.Platforms;
import unit.mesh.HalfEdgeBulkBuildTest;

/**
 * Warm wall time of {@link HalfEdgeMeshEngine#buildFromIndexedMesh} on fertility and on one
 * repaired full-resolution crawfish scan (MESH-52), each checked id for id against the
 * face-at-a-time build. Pick the scan with {@code -Dbenchmark.glb}.
 */
public final class HalfEdgeBuildBenchmark {

    private static final String GLB_PROPERTY = "benchmark.glb";
    private static final String DEFAULT_GLB = "/home/acw/crawfish/IMG_4109.glb";
    private static final String FERTILITY_OFF =
            "test/resources/quadlayout/figure_8/fertility_in_tri.off";
    private static final int WARMUP_BUILDS = 4;
    private static final int TIMED_BUILDS = 9;
    private static final double NANOS_PER_MILLISECOND = 1.0e6;

    /**
     * Times the bulk build on both meshes and checks each result matches the reference build.
     *
     * @throws IOException when a mesh file cannot be read
     */
    @Test
    public void buildFertilityAndRepairedScan() throws IOException {
        ArrayMesh fertility = MeshLoader.load(FERTILITY_OFF);
        measure("fertility", fertility.copyPositions(), fertility.copyFaceIndices());

        String glbPath = System.getProperty(GLB_PROPERTY, DEFAULT_GLB);
        RepairMeshNode node = new RepairMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput(RepairMeshNode.GEOMETRY.name, MeshLoader.loadBundle(glbPath));
        context.setInput(RepairMeshNode.MAX_HOLE_EDGES.name, RepairMeshNode.DEFAULT_MAX_HOLE_EDGES);
        context.setInput(RepairMeshNode.MIN_SHELL_FACES.name, CrawfishMeshRepairBenchmark.MIN_SHELL_FACES);
        node.evaluate(context);
        HalfEdgeMesh repaired = assertInstanceOf(HalfEdgeMesh.class,
                context.getOutput(RepairMeshNode.GEOMETRY.name, GeometryBundle.class).mesh());
        float[] positions = Arrays.copyOf(repaired.vertexPositions, repaired.vertexCount() * 3);
        int[] triangles = new int[repaired.faceCount() * 3];
        for (int faceId = 0; faceId < repaired.faceCount(); faceId++) {
            for (int corner = 0; corner < 3; corner++) {
                triangles[faceId * 3 + corner] = repaired.faceVertexAt(faceId, corner);
            }
        }
        measure("repaired " + glbPath, positions, triangles);
    }

    /**
     * Builds one mesh repeatedly, logs the warm minimum and median, and checks the last build
     * against the face-at-a-time reference.
     *
     * @param label     name for the log line
     * @param positions packed xyz triples
     * @param triangles triangle corners
     */
    private static void measure(String label, float[] positions, int[] triangles) {
        long[] times = new long[TIMED_BUILDS];
        HalfEdgeMesh built = null;
        for (int run = 0; run < WARMUP_BUILDS + TIMED_BUILDS; run++) {
            built = null;
            long start = System.nanoTime();
            built = HalfEdgeMeshEngine.buildFromIndexedMesh(positions, triangles);
            long elapsed = System.nanoTime() - start;
            if (run >= WARMUP_BUILDS) {
                times[run - WARMUP_BUILDS] = elapsed;
            }
        }
        Arrays.sort(times);
        Platforms.log("[build] %s V=%d F=%d buildFromIndexedMesh warm min %.1fms median %.1fms%n",
                label, built.vertexCount(), built.faceCount(), times[0] / NANOS_PER_MILLISECOND,
                times[TIMED_BUILDS / 2] / NANOS_PER_MILLISECOND);

        HalfEdgeMesh reference = HalfEdgeBulkBuildTest.incrementalBuild(positions, triangles, 3);
        reference.computeNormals();
        assertNull(HalfEdgeBulkBuildTest.firstTopologyDifference(reference, built),
                label + " differs from the face-at-a-time build");
    }
}
