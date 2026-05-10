package ixdar.geometry.mesh.quadlayout;

import java.io.IOException;
import java.nio.file.Files;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import ixdar.geometry.mesh.data.load.CrossFieldWriter;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;

/**
 * Batch driver: discover every {@code *_in_tri.off} under
 * {@code test/resources/quadlayout/figure_*}, run the cross-field pipeline on
 * each, and write the result alongside as {@code *_in_cf_generated.ndf}.
 *
 * <p>No timeout. Larger meshes that {@link unit.mesh.quadlayout.CrossFieldNdfReferenceTest}
 * cannot fit into its 10-second budget will simply take longer here. Run via
 * {@code mvn exec:java -Dexec.mainClass=ixdar.geometry.mesh.quadlayout.CrossFieldExporterCLI}
 * or directly from the IDE.
 */
public final class CrossFieldExporterCLI {

    private static final Path RESOURCES_ROOT = Path.of("test", "resources", "quadlayout");
    private static final int OFF_HEADER_PROBE_BYTES = 32;
    private static final String OFF_INPUT_SUFFIX = "_in_tri.off";
    private static final String GENERATED_SUFFIX = "_in_cf_generated.ndf";
    private static final long NS_PER_MS = 1_000_000L;

    private CrossFieldExporterCLI() {
    }

    /**
     * Entry point.
     *
     * @param args ignored
     * @throws IOException on filesystem failure
     */
    public static void main(String[] args) throws IOException {
        Path root = RESOURCES_ROOT.toAbsolutePath();
        if (!Files.isDirectory(root)) {
            // Fall back to ixdar-app subdir if running from repo root.
            root = Path.of("ixdar-app").resolve(RESOURCES_ROOT).toAbsolutePath();
        }
        if (!Files.isDirectory(root)) {
            System.err.println("[exporter] resources root not found: " + RESOURCES_ROOT);
            System.exit(1);
            return;
        }
        System.out.println("[exporter] resources root: " + root);

        List<Path> offFiles = discoverOffs(root);
        System.out.printf("[exporter] discovered %d *_in_tri.off files%n", offFiles.size());

        int succeeded = 0;
        int skipped = 0;
        int failed = 0;
        for (Path off : offFiles) {
            String modelName = off.getFileName().toString();
            Path outDir = off.getParent();
            String stem = modelName.substring(0, modelName.length() - OFF_INPUT_SUFFIX.length());
            Path outNdf = outDir.resolve(stem + GENERATED_SUFFIX);
            if (isBinaryOff(off)) {
                System.out.printf("[exporter] SKIP %s (binary OFF not supported by parser)%n", relative(root, off));
                skipped++;
                continue;
            }
            if (Files.exists(outNdf)) {
                System.out.printf("[exporter] SKIP %s (output already exists: %s)%n",
                        relative(root, off), outNdf.getFileName());
                skipped++;
                continue;
            }
            long t0 = System.nanoTime();
            try {
                ArrayMesh arr = MeshLoader.load(off.toString());
                HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                        arr.copyPositions(), arr.copyFaceIndices());
                CrossField cf = new CrossField(mesh).build();
                CrossFieldWriter.write(outNdf.toString(), cf, mesh, modelName);
                long elapsedMs = (System.nanoTime() - t0) / NS_PER_MS;
                System.out.printf("[exporter] OK   %s  (F=%d, sing=%d, %d ms) -> %s%n",
                        relative(root, off), mesh.faceCount(), cf.singularities.size(),
                        elapsedMs, outNdf.getFileName());
                succeeded++;
            } catch (RuntimeException | IOException ex) {
                long elapsedMs = (System.nanoTime() - t0) / NS_PER_MS;
                System.out.printf("[exporter] FAIL %s  (%d ms): %s%n",
                        relative(root, off), elapsedMs, ex);
                failed++;
            }
        }

        System.out.printf("[exporter] done. ok=%d skipped=%d failed=%d total=%d%n",
                succeeded, skipped, failed, offFiles.size());
    }

    private static List<Path> discoverOffs(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> figureDirs = Files.list(root)) {
            List<Path> dirs = figureDirs
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("figure_"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            for (Path dir : dirs) {
                try (Stream<Path> entries = Files.list(dir)) {
                    entries.filter(p -> p.getFileName().toString().endsWith(OFF_INPUT_SUFFIX))
                            .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                            .forEach(out::add);
                }
            }
        }
        return out;
    }

    private static String relative(Path root, Path p) {
        try {
            return root.relativize(p).toString();
        } catch (IllegalArgumentException e) {
            return p.toString();
        }
    }

    private static boolean isBinaryOff(Path off) {
        try {
            byte[] head = new byte[OFF_HEADER_PROBE_BYTES];
            int read;
            try (var in = Files.newInputStream(off)) {
                read = in.read(head);
            }
            String header = new String(head, 0, Math.max(0, read), StandardCharsets.ISO_8859_1);
            return header.contains("BINARY");
        } catch (IOException e) {
            return false;
        }
    }
}
