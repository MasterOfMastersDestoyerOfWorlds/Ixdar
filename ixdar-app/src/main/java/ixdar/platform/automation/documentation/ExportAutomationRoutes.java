package ixdar.platform.automation.documentation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI entry point that dumps the automation routes manifest JSON to a file. Bound to the Maven build
 * (see {@code ixdar-app/pom.xml}) so the committed {@code ixdar_automation_cli/automation_routes.json}
 * is regenerated on every build; also runnable standalone for debugging.
 *
 * Usage: java ixdar.platform.automation.documentation.ExportAutomationRoutes {@code <output-path>}
 */
public final class ExportAutomationRoutes {

    private ExportAutomationRoutes() {
    }

    /**
     * Write the automation routes manifest JSON (built from the annotation registry) to {@code args[0]}.
     *
     * @param args single element: output file path. Parent directories are created on demand.
     * @throws IOException if the output file cannot be created or written
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: ExportAutomationRoutes <output-path>");
            System.exit(1);
        }
        Path out = Path.of(args[0]);
        Files.createDirectories(out.toAbsolutePath().getParent());
        String json = AutomationRouteCatalog.toJsonFromAnnotationRegistry();
        Files.writeString(out, json + System.lineSeparator());
        System.out.println("Exported automation routes manifest to " + out.toAbsolutePath());
    }
}
