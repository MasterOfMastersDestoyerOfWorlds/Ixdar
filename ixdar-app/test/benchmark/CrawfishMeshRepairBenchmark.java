package benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.ops.MeshRepairReport;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.modifier.RepairMeshNode;
import ixdar.platform.Platforms;

/**
 * What {@code repair_mesh} does to one full-resolution Trellis2 crawfish scan (CRAW-26): the
 * report's every class count, the wall time, and the half-edge build the quad-layout pipeline
 * needs. Pick the scan with {@code -Dbenchmark.glb}.
 */
public final class CrawfishMeshRepairBenchmark {

    /** System property naming the scan to repair. */
    public static final String GLB_PROPERTY = "benchmark.glb";

    /** Scan the benchmark repairs when {@link #GLB_PROPERTY} is unset. */
    public static final String DEFAULT_GLB = "/home/acw/crawfish/IMG_4109.glb";

    /**
     * Safety cap the benchmark fills up to. Trellis closes the back surface badly rather than
     * leaving it open, so every loop on a scan is an artefact and the node's own default cap is
     * what this uses.
     */
    public static final int MAX_HOLE_EDGES = RepairMeshNode.DEFAULT_MAX_HOLE_EDGES;

    /** Debris threshold: anything under this many faces is scanner noise. */
    public static final int MIN_SHELL_FACES = 100;

    /** Nanoseconds per second, for the reported times. */
    private static final double NANOS_PER_SECOND = 1.0e9;

    /**
     * Repairs one scan, logs the whole report and the stage times, and checks the result is what
     * the half-edge build accepts.
     *
     * @throws IOException when the scan cannot be read
     */
    @Test
    public void repairOneScan() throws IOException {
        String glbPath = System.getProperty(GLB_PROPERTY, DEFAULT_GLB);

        long loadStart = System.nanoTime();
        GeometryBundle bundle = MeshLoader.loadBundle(glbPath);
        ArrayMesh scan = assertInstanceOf(ArrayMesh.class, bundle.mesh(), "the scan is triangles");
        Platforms.log(String.format("[repair] load %s  %.2fs  V=%d F=%d%n", glbPath,
                (System.nanoTime() - loadStart) / NANOS_PER_SECOND, scan.vertexCount(),
                scan.faceCount()));

        long repairStart = System.nanoTime();
        GeometryBundle repaired = repair(bundle, false);
        double repairSeconds = (System.nanoTime() - repairStart) / NANOS_PER_SECOND;
        MeshRepairReport report =
                (MeshRepairReport) repaired.slots().get(MeshRepairReport.SLOT);
        Platforms.log(String.format("[repair] repair_mesh %.2fs%n", repairSeconds));
        Platforms.log(report.toText());

        HalfEdgeMesh halfEdge = assertInstanceOf(HalfEdgeMesh.class, repaired.mesh(),
                "the repair hands the pipeline a half-edge mesh");
        Platforms.log(String.format("[repair] half-edge mesh V=%d E=%d F=%d%n",
                halfEdge.vertexCount(), halfEdge.edgeCount(), halfEdge.faceCount()));

        assertEquals(report.outputFaceCount, halfEdge.faceCount(),
                "the half-edge build accepts every repaired face");
        assertTrue(report.shellKept[0], "the main shell survives");
        assertEquals(0, report.openHoleCount, "every boundary loop on a scan is fillable");
        assertEquals(0, report.outputBoundaryEdgeCount, "so the repaired scan has no boundary");
        assertEquals(0, report.unorientedEdgeCount, "and no contradiction survives the split");
        Platforms.log(String.format("[repair] euler V-E+F = %d%n",
                halfEdge.vertexCount() - halfEdge.edgeCount() + halfEdge.faceCount()));

        repair(bundle, true);
        Platforms.log("[repair] strict mode passes");
    }

    /**
     * Runs the {@code repair_mesh} node over one bundle.
     *
     * @param bundle the loaded scan
     * @param strict whether the node should fail on anything left open or dropped
     * @return the repaired bundle
     */
    private static GeometryBundle repair(GeometryBundle bundle, boolean strict) {
        RepairMeshNode node = new RepairMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput(RepairMeshNode.GEOMETRY.name, bundle);
        context.setInput(RepairMeshNode.MAX_HOLE_EDGES.name, MAX_HOLE_EDGES);
        context.setInput(RepairMeshNode.MIN_SHELL_FACES.name, MIN_SHELL_FACES);
        context.setInput(RepairMeshNode.STRICT.name, strict);
        node.evaluate(context);
        return context.getOutput(RepairMeshNode.GEOMETRY.name, GeometryBundle.class);
    }
}
