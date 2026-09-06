package benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.load.GltfMeshParser;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.nodes.api.Vector3Field;
import ixdar.platform.Platforms;

/**
 * Loads one Trellis2 crawfish scan through {@link MeshLoader} and prints vertex/face counts,
 * elapsed time and heap; pick the scan with {@code -Dbenchmark.glb}. Fails when the load boxes
 * or copies its way past the 2 GB heap budget the CRAW-1 ticket sets.
 */
public final class CrawfishScanLoadBenchmark {

    private static final String GLB_PROPERTY = "benchmark.glb";
    private static final String DEFAULT_GLB = "/home/acw/crawfish/IMG_4109.glb";
    private static final double NANOS_PER_MILLISECOND = 1.0e6;
    private static final double BYTES_PER_MEBIBYTE = 1024.0 * 1024.0;
    private static final long HEAP_BUDGET_BYTES = 2L * 1024 * 1024 * 1024;

    /**
     * Loads the scan twice: a cold run that includes Assimp's native initialization and a warm run
     * that measures the parser alone, then reports the peak heap of the process.
     *
     * @throws IOException when the scan cannot be read
     */
    @Test
    public void loadOneScan() throws IOException {
        String glbPath = System.getProperty(GLB_PROPERTY, DEFAULT_GLB);
        resetPeakHeap();

        long coldStart = System.nanoTime();
        GeometryBundle cold = MeshLoader.loadBundle(glbPath);
        long coldEnd = System.nanoTime();
        long warmStart = System.nanoTime();
        GeometryBundle warm = MeshLoader.loadBundle(glbPath);
        long warmEnd = System.nanoTime();

        ArrayMesh mesh = assertInstanceOf(ArrayMesh.class, warm.mesh());
        Vector3Field uv = assertInstanceOf(Vector3Field.class, warm.slots().get(MeshLoader.UV_SLOT),
                "the scan carries TEXCOORD_0");
        assertEquals(mesh.vertexCount(), uv.length(), "one UV per vertex");
        assertEquals(cold.mesh().vertexCount(), mesh.vertexCount(), "loads are deterministic");
        assertTrue(mesh.faceCount() > 0, "the scan has triangles");

        long peakHeap = peakHeapBytes();
        long usedHeap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        Platforms.log("[benchmark] %s V=%d F=%d UV=%d%n", glbPath, mesh.vertexCount(), mesh.faceCount(),
                uv.length());
        Platforms.log("[benchmark] cold load %.1fms, warm load %.1fms%n",
                (coldEnd - coldStart) / NANOS_PER_MILLISECOND,
                (warmEnd - warmStart) / NANOS_PER_MILLISECOND);
        Platforms.log("[benchmark] heap used after load %.1fMiB, peak heap %.1fMiB, max heap %.1fMiB%n",
                usedHeap / BYTES_PER_MEBIBYTE, peakHeap / BYTES_PER_MEBIBYTE,
                Runtime.getRuntime().maxMemory() / BYTES_PER_MEBIBYTE);
        assertTrue(peakHeap < HEAP_BUDGET_BYTES, "peak heap " + peakHeap + " bytes exceeds the 2 GB budget");
    }

    private static void resetPeakHeap() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                pool.resetPeakUsage();
            }
        }
    }

    /**
     * Sum of the peak usage of every heap pool since {@link #resetPeakHeap}; an upper bound on the
     * heap the loads needed, since the pools need not peak simultaneously.
     *
     * @return peak heap in bytes
     */
    private static long peakHeapBytes() {
        long peak = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                peak += pool.getPeakUsage().getUsed();
            }
        }
        return peak;
    }
}
