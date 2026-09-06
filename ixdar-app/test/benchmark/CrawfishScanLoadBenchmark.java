package benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.CornerUvField;
import ixdar.geometry.mesh.data.CornerUvSplit;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.load.GltfMeshParser;
import ixdar.geometry.mesh.data.load.GltfModel;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
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
    private static final int CORNERS_PER_FACE = 3;

    /**
     * Loads the scan twice: a cold run that includes class loading and a warm run that measures the
     * parser alone, then reports the peak heap of the process.
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
        CornerUvField uv = assertInstanceOf(CornerUvField.class,
                warm.slots().get(CornerUvField.SLOT), "the scan carries TEXCOORD_0");
        assertEquals(mesh.faceCount(), uv.faceCount(), "one UV triple per face");
        assertEquals(cold.mesh().vertexCount(), mesh.vertexCount(), "loads are deterministic");
        assertTrue(mesh.faceCount() > 0, "the scan has triangles");

        long peakHeap = peakHeapBytes();
        long usedHeap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        Platforms.log("[benchmark] %s V=%d F=%d cornerUV=%d%n", glbPath,
                mesh.vertexCount(), mesh.faceCount(),
                uv.faceCount() * CornerUvField.CORNERS_PER_FACE);
        Platforms.log("[benchmark] cold load %.1fms, warm load %.1fms%n",
                (coldEnd - coldStart) / NANOS_PER_MILLISECOND,
                (warmEnd - warmStart) / NANOS_PER_MILLISECOND);
        Platforms.log("[benchmark] heap used after load %.1fMiB, peak heap %.1fMiB, max heap %.1fMiB%n",
                usedHeap / BYTES_PER_MEBIBYTE, peakHeap / BYTES_PER_MEBIBYTE,
                Runtime.getRuntime().maxMemory() / BYTES_PER_MEBIBYTE);
        assertTrue(peakHeap < HEAP_BUDGET_BYTES, "peak heap " + peakHeap + " bytes exceeds the 2 GB budget");
    }

    /**
     * The whole-file read exposes the scan's embedded textures and its material's factors, so
     * CRAW-3 can build a material slot without reaching for Assimp.
     *
     * @throws IOException when the scan cannot be read
     */
    @Test
    public void readExposesImagesAndMaterials() throws IOException {
        String glbPath = System.getProperty(GLB_PROPERTY, DEFAULT_GLB);

        GltfModel model = GltfMeshParser.read(glbPath);

        assertTrue(model.imageCount() > 0, "the scan embeds at least one texture");
        for (int image = 0; image < model.imageCount(); image++) {
            assertTrue(model.imageBytes[image].length > 0,
                    "image " + model.imageName[image] + " has bytes");
            assertTrue(model.imageMimeType[image].startsWith("image/"),
                    "image " + model.imageName[image] + " declares an image MIME type, got "
                            + model.imageMimeType[image]);
        }
        assertTrue(model.materialCount() > 0, "the scan declares a material");
        assertEquals(model.materialCount() * GltfModel.COLOR_COMPONENTS,
                model.baseColorFactor.length, "the base colour factor is RGBA per material");
        assertTrue(model.baseColorTexture[0] >= 0, "the material samples a base colour texture");
        assertTrue(model.baseColorTexture[0] < model.textureImage.length,
                "the base colour texture index is in range");
        assertTrue(model.textureImage[model.baseColorTexture[0]] >= 0,
                "the base colour texture resolves to an image");
        assertTrue(model.primitiveCount() > 0, "the scan reports its primitive ranges");
        Platforms.log("[benchmark] images=%d materials=%d textures=%d primitives=%d%n",
                model.imageCount(), model.materialCount(), model.textureImage.length,
                model.primitiveCount());
        Platforms.log("[benchmark] sourceVertices=%d weldedVertices=%d normalConflicts=%d%n",
                model.sourceVertexCount, model.weldedVertexCount, model.normalConflicts);

        ArrayMesh welded = assertInstanceOf(ArrayMesh.class, model.bundle.mesh());
        CornerUvField uv = assertInstanceOf(CornerUvField.class,
                model.bundle.slots().get(CornerUvField.SLOT));
        ArrayMesh unwelded = CornerUvSplit.split(welded, uv,
                new float[CornerUvSplit.maxSplitUvLength(welded)]);
        Platforms.log("[benchmark] welded V=%d | split V=%d%n", welded.vertexCount(),
                unwelded.vertexCount());
        logEdgeStats("split (pre-weld)", unwelded.copyFaceIndices());
        logEdgeStats("welded", welded.copyFaceIndices());
        assertTrue(unwelded.vertexCount() >= welded.vertexCount(),
                "splitting never merges vertices");
    }

    /**
     * Count undirected edges by how many faces use them, straight from the index array.
     * {@link ArrayMesh} never populates its own edge arrays, and a half-edge build refuses a
     * non-manifold mesh, so the multiplicities are counted here by sorting packed endpoint pairs.
     *
     * @param label name printed with the counts
     * @param triangleIndices triangle corner indices
     */
    private static void logEdgeStats(String label, int[] triangleIndices) {
        long[] edges = new long[triangleIndices.length];
        for (int face = 0; face < triangleIndices.length / CORNERS_PER_FACE; face++) {
            for (int corner = 0; corner < CORNERS_PER_FACE; corner++) {
                int from = triangleIndices[face * CORNERS_PER_FACE + corner];
                int to = triangleIndices[face * CORNERS_PER_FACE + (corner + 1) % CORNERS_PER_FACE];
                edges[face * CORNERS_PER_FACE + corner] =
                        ((long) Math.min(from, to) << Integer.SIZE) | Math.max(from, to);
            }
        }
        Arrays.sort(edges);
        int distinct = 0;
        int boundary = 0;
        int nonManifold = 0;
        int run = 0;
        for (int slot = 0; slot < edges.length; slot++) {
            run++;
            if (slot + 1 == edges.length || edges[slot] != edges[slot + 1]) {
                distinct++;
                if (run == 1) {
                    boundary++;
                } else if (run > 2) {
                    nonManifold++;
                }
                run = 0;
            }
        }
        Platforms.log("[benchmark] %s edges=%d boundary=%d nonManifold=%d%n", label, distinct,
                boundary, nonManifold);
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
