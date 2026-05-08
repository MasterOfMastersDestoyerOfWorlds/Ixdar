package ixdar.geometry.mesh.documentation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI entry point that dumps the mesh-node catalog JSON to a file.
 * Used by Maven exec goal to generate documentation for Daud agent context.
 *
 * Usage: java ixdar.geometry.mesh.documentation.ExportMeshNodeCatalog {@code <output-path>}
 */
public final class ExportMeshNodeCatalog {

    /**
     * TODO: document {@code main}.
     *
     * @param args TODO: describe
     * @throws IOException TODO: describe
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: ExportMeshNodeCatalog <output-path>");
            System.exit(1);
        }
        Path out = Path.of(args[0]);
        Files.createDirectories(out.getParent());
        String json = MeshNodeCatalog.toJsonFromAnnotationRegistry();
        Files.writeString(out, json);
        System.out.println("Exported mesh node catalog to " + out.toAbsolutePath());
    }
}
