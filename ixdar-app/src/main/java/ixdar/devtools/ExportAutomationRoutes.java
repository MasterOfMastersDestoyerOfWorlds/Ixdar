package ixdar.devtools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import ixdar.automation.documentation.AutomationRouteCatalog;
import ixdar.nodegraph.catalog.MeshNodeCatalog;

/**
 * CLI entry point that writes the automation routes manifest and the mesh-node catalog JSON. Bound
 * to the Maven build (see {@code ixdar-app/pom.xml}) so the committed
 * {@code ixdar_automation_cli/automation_routes.json} and {@code docs/mesh-node-catalog.json} are
 * regenerated on every build; also runnable standalone for debugging.
 *
 * Usage: java ixdar.devtools.ExportAutomationRoutes {@code <routes-path> <catalog-path>}
 */
public final class ExportAutomationRoutes {

    private ExportAutomationRoutes() {
    }

    /**
     * Write the routes manifest to {@code args[0]} and the node catalog to {@code args[1]}, both
     * built from the annotation registries. A file whose content is already current is left alone.
     *
     * @param args the routes manifest path, then the node catalog path; parent directories are
     *             created on demand
     * @throws IOException if an output file cannot be created or written
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: ExportAutomationRoutes <routes-path> <catalog-path>");
            System.exit(1);
        }
        Path[] outputs = {Path.of(args[0]), Path.of(args[1])};
        String[] contents = {
            AutomationRouteCatalog.toJsonFromAnnotationRegistry() + "\n",
            MeshNodeCatalog.toJsonFromAnnotationRegistry() + "\n",
        };
        for (int i = 0; i < outputs.length; i++) {
            Path out = outputs[i];
            Files.createDirectories(out.toAbsolutePath().getParent());
            if (Files.exists(out) && Files.readString(out).equals(contents[i])) {
                continue;
            }
            Files.writeString(out, contents[i]);
            System.out.println("Exported " + out.toAbsolutePath());
        }
    }
}
