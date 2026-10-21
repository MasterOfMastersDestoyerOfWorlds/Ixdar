package benchmark;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Callable;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.CornerUvField;
import ixdar.geometry.mesh.data.bundle.CornerUvSplit;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.graphics.render.model.HalfEdgeCompiledMeshData;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.graphics.render.model.LineSet;
import ixdar.nodegraph.api.MapNodeContext;
import ixdar.nodegraph.nodes.modifier.RepairMeshNode;
import ixdar.platform.Platforms;
import unit.mesh.HalfEdgeBulkBuildTest;

/**
 * Warm wall time of {@link HalfEdgeMeshOps#buildFromIndexedMesh} on fertility and on one
 * repaired full-resolution crawfish scan (MESH-52), each checked id for id against the
 * face-at-a-time build, and the two mesh representations side by side on the largest models
 * (MESH-60). Pick the scan with {@code -Dbenchmark.glb}.
 */
public final class HalfEdgeBuildBenchmark {

    private static final String GLB_PROPERTY = "benchmark.glb";
    private static final String FERTILITY = "fertility";
    private static final String DEFAULT_GLB = "/home/acw/crawfish/IMG_4109.glb";
    private static final String FERTILITY_OFF =
            "test/resources/quadlayout/figure_8/fertility_in_tri.off";
    private static final String BUDDHA_OFF =
            "test/resources/quadlayout/figure_10/buddha_in_tri.off";
    private static final int WARMUP_BUILDS = 4;
    private static final int TIMED_BUILDS = 9;
    private static final int WARMUP_ROUNDS = 2;
    private static final int TIMED_ROUNDS = 7;
    private static final int REPAIR_RUNS = 3;
    private static final int GC_PASSES = 3;
    private static final int METRICS = 6;
    private static final int LOAD = 0;
    private static final int BUILD = 1;
    private static final int RETAINED = 2;
    private static final int COMPILE = 3;
    private static final int EDGE_LINES = 4;
    private static final int TEXTURED_SPLIT = 5;
    private static final String[] REPRESENTATION_NAMES = {"ArrayMesh", "HalfEdgeMesh"};
    private static final double NANOS_PER_MILLISECOND = 1.0e6;
    private static final double BYTES_PER_MEBIBYTE = 1024.0 * 1024.0;

    /**
     * Times the bulk build on both meshes and checks each result matches the reference build.
     *
     * @throws IOException when a mesh file cannot be read
     */
    @Test
    public void buildFertilityAndRepairedScan() throws IOException {
        HalfEdgeMesh fertility = assertInstanceOf(HalfEdgeMesh.class, MeshLoader.load(FERTILITY_OFF));
        measure(FERTILITY, Arrays.copyOf(fertility.vertexPositions, fertility.vertexCount() * 3),
                triangleCorners(fertility));

        String glbPath = System.getProperty(GLB_PROPERTY, DEFAULT_GLB);
        HalfEdgeMesh repaired = repairScan(MeshLoader.loadBundle(glbPath));
        float[] positions = Arrays.copyOf(repaired.vertexPositions, repaired.vertexCount() * 3);
        measure("repaired " + glbPath, positions, triangleCorners(repaired));
    }

    /**
     * MESH-60: loads fertility, Buddha and the crawfish scan, raw and repaired, then builds,
     * measures and compiles each as an {@link ArrayMesh} and as a {@link HalfEdgeMesh} in
     * alternating order, logging every round and the per-metric medians.
     *
     * @throws Exception when a mesh file cannot be read or a measured step fails
     */
    @Test
    public void representationsOnLargestModels() throws Exception {
        compareRepresentations(FERTILITY,() -> MeshLoader.load(FERTILITY_OFF), null);
        compareRepresentations("buddha", () -> MeshLoader.load(BUDDHA_OFF), null);

        String glbPath = System.getProperty(GLB_PROPERTY, DEFAULT_GLB);
        CornerUvField uv = CornerUvField.of(MeshLoader.loadBundle(glbPath));
        compareRepresentations("crawfish raw", () -> MeshLoader.load(glbPath), uv);

        double[] repairMilliseconds = new double[REPAIR_RUNS];
        HalfEdgeMesh repaired = null;
        for (int run = 0; run < REPAIR_RUNS; run++) {
            GeometryBundle raw = MeshLoader.loadBundle(glbPath);
            long start = System.nanoTime();
            repaired = repairScan(raw);
            repairMilliseconds[run] = (System.nanoTime() - start) / NANOS_PER_MILLISECOND;
        }
        Platforms.log("[representation] %s repair_mesh median %.1fms over %d runs %s%n", glbPath,
                median(repairMilliseconds), REPAIR_RUNS, Arrays.toString(repairMilliseconds));
        float[] positions = Arrays.copyOf(repaired.vertexPositions, repaired.vertexCount() * 3);
        float[] normals = Arrays.copyOf(repaired.vertexNormals, repaired.vertexCount() * 3);
        int[] triangles = triangleCorners(repaired);
        repaired = null;
        compareRepresentations("crawfish repaired", () -> MeshLoader.indexedMesh(positions.clone(),
                normals.clone(), triangles.clone(), HalfEdgeMesh.TRIANGLE_CORNERS), null);
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
            built = HalfEdgeMeshOps.buildFromIndexedMesh(positions, triangles);
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

    /**
     * One bundle through {@code repair_mesh} with the defaults the crawfish repair benchmark uses.
     *
     * @param raw loaded scan
     * @return the repaired half-edge mesh
     */
    private static HalfEdgeMesh repairScan(GeometryBundle raw) {
        RepairMeshNode node = new RepairMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput(RepairMeshNode.GEOMETRY.name, raw);
        context.setInput(RepairMeshNode.MAX_HOLE_EDGES.name, RepairMeshNode.DEFAULT_MAX_HOLE_EDGES);
        context.setInput(RepairMeshNode.MIN_SHELL_FACES.name, CrawfishMeshRepairBenchmark.MIN_SHELL_FACES);
        node.evaluate(context);
        return assertInstanceOf(HalfEdgeMesh.class,
                context.getOutput(RepairMeshNode.GEOMETRY.name, GeometryBundle.class).mesh());
    }

    /**
     * Corner list of a triangle mesh whose face ids are dense.
     *
     * @param mesh triangle mesh
     * @return three vertex ids per face, in face order
     */
    private static int[] triangleCorners(MeshTopology mesh) {
        int[] triangles = new int[mesh.faceCount() * HalfEdgeMesh.TRIANGLE_CORNERS];
        for (int faceId = 0; faceId < mesh.faceCount(); faceId++) {
            for (int corner = 0; corner < HalfEdgeMesh.TRIANGLE_CORNERS; corner++) {
                triangles[faceId * HalfEdgeMesh.TRIANGLE_CORNERS + corner] = mesh.faceVertexAt(faceId, corner);
            }
        }
        return triangles;
    }

    /**
     * Alternating A/B rounds over one model: the shipped load, then the file's arrays built as each
     * representation, with retained heap, render compile, edge lines and UV split; the
     * representation going first swaps every round.
     *
     * @param label  model name for the log lines
     * @param source produces a freshly loaded mesh on every call, as the viewer loads it
     * @param uv     per-corner UVs to split for the textured draw, or null for none
     * @throws Exception when loading or a measured step fails
     */
    private static void compareRepresentations(String label, Callable<MeshTopology> source,
            CornerUvField uv) throws Exception {
        double[][][] samples = new double[2][TIMED_ROUNDS][];
        String refusal = null;
        for (int round = 0; round < WARMUP_ROUNDS + TIMED_ROUNDS; round++) {
            for (int order = 0; order < 2; order++) {
                int representation = (round + order) % 2;
                boolean halfEdge = representation == 1;
                if (halfEdge && refusal != null) {
                    continue;
                }
                double[] sample = new double[METRICS];
                long start = System.nanoTime();
                MeshTopology loaded = source.call();
                long loadedAt = System.nanoTime();
                float[] positions = new float[loaded.vertexCount() * 3];
                float[] normals = new float[positions.length];
                Vector3f normal = new Vector3f();
                Vector3f position = new Vector3f();
                for (int index = 0; index < loaded.vertexCount(); index++) {
                    loaded.vertexPosition(loaded.vertexIdAt(index), position);
                    loaded.vertexNormal(loaded.vertexIdAt(index), normal);
                    positions[index * 3] = position.x;
                    positions[index * 3 + 1] = position.y;
                    positions[index * 3 + 2] = position.z;
                    normals[index * 3] = normal.x;
                    normals[index * 3 + 1] = normal.y;
                    normals[index * 3 + 2] = normal.z;
                }
                int[] triangles = triangleCorners(loaded);
                loaded = null;
                long heapBefore = usedHeapAfterGc();
                long buildStart = System.nanoTime();
                MeshTopology mesh;
                if (halfEdge) {
                    mesh = MeshLoader.indexedMesh(positions, normals, triangles,
                            HalfEdgeMesh.TRIANGLE_CORNERS);
                    if (!(mesh instanceof HalfEdgeMesh)) {
                        refusal = "no half-edge form (non-manifold, degenerate or unoriented edges)";
                        Platforms.log("[representation] %s HalfEdgeMesh refused: %.200s%n", label, refusal);
                        continue;
                    }
                } else {
                    mesh = new ArrayMesh(positions, normals, triangles, HalfEdgeMesh.TRIANGLE_CORNERS);
                    assertTrue(mesh.edgeCount() > 0, "the dense topology has edges");
                }
                long builtAt = System.nanoTime();
                positions = null;
                normals = null;
                triangles = null;
                sample[LOAD] = (loadedAt - start) / NANOS_PER_MILLISECOND;
                sample[BUILD] = (builtAt - buildStart) / NANOS_PER_MILLISECOND;
                sample[RETAINED] = (usedHeapAfterGc() - heapBefore) / BYTES_PER_MEBIBYTE;

                long compileStart = System.nanoTime();
                HalfEdgeCompiledMeshData compiled = HalfEdgeMeshRuntime.compileSurface(mesh);
                long compiledAt = System.nanoTime();
                LineSet lines = new LineSet(mesh.edgeCount());
                for (int edge = 0; edge < mesh.edgeCount(); edge++) {
                    lines.edge(mesh, mesh.edgeIdAt(edge));
                }
                long linesAt = System.nanoTime();
                if (uv != null && uv.faceCount() == mesh.faceCount()) {
                    int corners = compiled.indices.length;
                    int splitCount = CornerUvSplit.split(compiled.indices, uv, null,
                            new float[CornerUvSplit.maxSplitUvLength(mesh.faceCount())], new int[corners],
                            new int[corners]);
                    assertTrue(splitCount >= mesh.vertexCount(), "splitting never merges vertices");
                }
                long splitAt = System.nanoTime();
                assertTrue(compiled.indices.length > 0 && lines.vertices.length > 0, "the mesh compiled");
                sample[COMPILE] = (compiledAt - compileStart) / NANOS_PER_MILLISECOND;
                sample[EDGE_LINES] = (linesAt - compiledAt) / NANOS_PER_MILLISECOND;
                sample[TEXTURED_SPLIT] = (splitAt - linesAt) / NANOS_PER_MILLISECOND;
                if (round >= WARMUP_ROUNDS) {
                    samples[representation][round - WARMUP_ROUNDS] = sample;
                    Platforms.log("[representation] %s round %d %s %s%n", label, round - WARMUP_ROUNDS,
                            REPRESENTATION_NAMES[representation], Arrays.toString(sample));
                }
            }
        }
        for (int representation = 0; representation < 2; representation++) {
            if (samples[representation][0] == null) {
                continue;
            }
            double[] medians = new double[METRICS];
            for (int metric = 0; metric < METRICS; metric++) {
                double[] values = new double[TIMED_ROUNDS];
                for (int round = 0; round < TIMED_ROUNDS; round++) {
                    values[round] = samples[representation][round][metric];
                }
                medians[metric] = median(values);
            }
            Platforms.log("[representation] %s %s median of %d: load %.1fms build %.1fms retained %.1fMiB"
                    + " compile %.1fms edgeLines %.1fms texturedSplit %.1fms%n", label,
                    REPRESENTATION_NAMES[representation], TIMED_ROUNDS, medians[LOAD], medians[BUILD],
                    medians[RETAINED], medians[COMPILE], medians[EDGE_LINES], medians[TEXTURED_SPLIT]);
        }
    }

    /**
     * Heap in use after a few full collections, so the difference of two readings is what the
     * objects alive between them retain.
     *
     * @return used heap in bytes
     */
    private static long usedHeapAfterGc() {
        for (int pass = 0; pass < GC_PASSES; pass++) {
            System.gc();
        }
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /**
     * The middle value of a copy of {@code values}, the upper middle for an even count.
     *
     * @param values samples
     * @return their median
     */
    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
