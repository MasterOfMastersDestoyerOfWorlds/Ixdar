package benchmark;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ixdar.geometry.mesh.MeshCanonicalFingerprint;
import ixdar.geometry.mesh.data.CornerUvField;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.load.GltfMeshWriter;
import ixdar.geometry.mesh.data.load.MeshExport;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.load.PlyMeshWriter;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.api.UvField;
import ixdar.geometry.mesh.nodes.modifier.RepairMeshNode;
import ixdar.platform.Platforms;

/**
 * The CRAW-29 round trip at scan scale: repair one Trellis2 crawfish, export it as GLB and PLY,
 * reload both, and check the reload fingerprints equal with the same per-corner normals and UVs
 * and that two GLB writes are byte-identical. Pick the scan with {@code -Dbenchmark.glb}.
 */
public final class CrawfishMeshExportBenchmark {

    private static final double NANOS_PER_SECOND = 1.0e9;

    private static final double RELOADED_COMPONENT_TOLERANCE = 1.0e-6;

    /**
     * Repairs, exports, reloads and compares one scan, logging one summary line of sizes and
     * times.
     *
     * @param directory scratch directory the exports are written to
     * @throws IOException when the scan cannot be read or an export cannot be written
     */
    @Test
    public void exportRoundTripsOneRepairedScan(@TempDir Path directory) throws IOException {
        String glbPath = System.getProperty(CrawfishMeshRepairBenchmark.GLB_PROPERTY,
                CrawfishMeshRepairBenchmark.DEFAULT_GLB);
        // repair_mesh with the settings dsl/crawfish_repair_export.dsl uses.
        RepairMeshNode node = new RepairMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput(RepairMeshNode.GEOMETRY.name, MeshLoader.loadBundle(glbPath));
        context.setInput(RepairMeshNode.MAX_HOLE_EDGES.name,
                CrawfishMeshRepairBenchmark.MAX_HOLE_EDGES);
        context.setInput(RepairMeshNode.MIN_SHELL_FACES.name,
                CrawfishMeshRepairBenchmark.MIN_SHELL_FACES);
        node.evaluate(context);
        GeometryBundle repaired = context.getOutput(RepairMeshNode.GEOMETRY.name,
                GeometryBundle.class);

        Path first = directory.resolve("first.glb");
        Path second = directory.resolve("second.glb");
        long exportStart = System.nanoTime();
        MeshExport written = GltfMeshWriter.write(repaired, first);
        double exportSeconds = (System.nanoTime() - exportStart) / NANOS_PER_SECOND;
        GltfMeshWriter.write(repaired, second);
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second),
                "two exports of one bundle are byte-identical");

        long reloadStart = System.nanoTime();
        GeometryBundle reloaded = MeshLoader.loadBundle(first.toString());
        double reloadSeconds = (System.nanoTime() - reloadStart) / NANOS_PER_SECOND;
        assertSameSurface(repaired, reloaded, "GLB");
        // Every corner UV came back, a non-finite one as the zero the export writes.
        UvField beforeUv = assertInstanceOf(UvField.class,
                repaired.slots().get(CornerUvField.SLOT), "the repaired scan carries corner UVs");
        UvField afterUv = assertInstanceOf(UvField.class,
                reloaded.slots().get(CornerUvField.SLOT), "so does its reload");
        MeshTopology beforeMesh = repaired.mesh();
        MeshTopology afterMesh = reloaded.mesh();
        for (int face = 0; face < beforeMesh.faceCount(); face++) {
            int beforeFace = beforeMesh.faceIdAt(face);
            int afterFace = afterMesh.faceIdAt(face);
            for (int corner = 0; corner < CornerUvField.CORNERS_PER_FACE; corner++) {
                double u = beforeUv.u(beforeFace, corner);
                double v = beforeUv.v(beforeFace, corner);
                boolean finite = Double.isFinite(u) && Double.isFinite(v);
                assertEquals(finite ? u : 0, afterUv.u(afterFace, corner),
                        RELOADED_COMPONENT_TOLERANCE, "u of face " + face + " corner " + corner);
                assertEquals(finite ? v : 0, afterUv.v(afterFace, corner),
                        RELOADED_COMPONENT_TOLERANCE, "v of face " + face + " corner " + corner);
            }
        }

        Path ply = directory.resolve("repaired.ply");
        PlyMeshWriter.write(repaired, ply);
        assertSameSurface(repaired, MeshLoader.loadBundle(ply.toString()), "PLY");

        Platforms.log(String.format("[export] round trip V=%d F=%d glb=%d bytes export=%.2fs"
                + " reload=%.2fs split vertices=%d zeroed uv corners=%d%n",
                repaired.mesh().vertexCount(), repaired.mesh().faceCount(), Files.size(first),
                exportSeconds, reloadSeconds, written.vertexCount(), written.nonFiniteUvCount));
    }

    /**
     * Assert that a reload has the source's vertex and face counts, fingerprint and per-corner
     * normals, reading the source's faces in the order the export fans them.
     *
     * @param source bundle that was written
     * @param reloaded bundle read back from the file
     * @param format file format, for the messages
     */
    private static void assertSameSurface(GeometryBundle source, GeometryBundle reloaded,
            String format) {
        MeshTopology before = source.mesh();
        MeshTopology after = reloaded.mesh();
        assertEquals(before.vertexCount(), after.vertexCount(), format + " vertex count");
        assertEquals(before.faceCount(), after.faceCount(), format + " face count");
        assertEquals(MeshCanonicalFingerprint.sha256Hex(before),
                MeshCanonicalFingerprint.sha256Hex(after), format + " fingerprint");
        Vector3f left = new Vector3f();
        Vector3f right = new Vector3f();
        for (int face = 0; face < before.faceCount(); face++) {
            int beforeFace = before.faceIdAt(face);
            int afterFace = after.faceIdAt(face);
            for (int corner = 0; corner < CornerUvField.CORNERS_PER_FACE; corner++) {
                before.vertexNormal(before.faceVertexAt(beforeFace, corner), left);
                after.vertexNormal(after.faceVertexAt(afterFace, corner), right);
                assertEquals(0f, left.distance(right), RELOADED_COMPONENT_TOLERANCE,
                        format + " normal of face " + face + " corner " + corner);
            }
        }
    }
}
