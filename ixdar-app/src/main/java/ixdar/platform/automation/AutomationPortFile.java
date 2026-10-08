package ixdar.platform.automation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.google.gson.JsonObject;

public class AutomationPortFile {
    public static final String TMP_DIRECTORY = "tmp";
    public static final String RECORD_DIRECTORY = "automation";
    public static final String RECORD_SUFFIX = ".json";
    public static final String MODULE_DIRECTORY = "ixdar-app";
    public static final String POM_FILE = "pom.xml";
    public static final String WORKING_DIRECTORY_PROPERTY = "user.dir";

    /**
     * The checkout the JVM was launched from: the nearest ancestor of the working
     * directory holding both a {@value #POM_FILE} and an {@value #MODULE_DIRECTORY}
     * directory, else the working directory itself.
     *
     * @return absolute path to the checkout root
     */
    public static Path checkoutRoot() {
        Path workingDirectory = Paths.get(System.getProperty(WORKING_DIRECTORY_PROPERTY, "."))
                .toAbsolutePath()
                .normalize();
        for (Path candidate = workingDirectory; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve(POM_FILE))
                    && Files.isDirectory(candidate.resolve(MODULE_DIRECTORY))) {
                return candidate;
            }
        }
        return workingDirectory;
    }

    /**
     * This process's own record under {@code tmp/automation/}, one file per scene, so two
     * scenes in one checkout never overwrite each other's port.
     *
     * @return path to {@code tmp/automation/<pid>.json} under {@link #checkoutRoot()}
     */
    public static Path location() {
        return checkoutRoot().resolve(TMP_DIRECTORY).resolve(RECORD_DIRECTORY)
                .resolve(ProcessHandle.current().pid() + RECORD_SUFFIX);
    }

    /**
     * Publish the bound port with this JVM's pid, scene id and headless flag, so the CLI can
     * pick the scene it launched and never drive a window the user opened. Failures are ignored.
     *
     * @param port     the port the automation server actually bound
     * @param scene    the scene id the JVM was started with
     * @param headless whether the scene runs off-screen rather than in a desktop window
     */
    public static void write(int port, String scene, boolean headless) {
        Path file = location();
        JsonObject record = new JsonObject();
        record.addProperty("port", port);
        record.addProperty("pid", ProcessHandle.current().pid());
        record.addProperty("scene", scene == null ? "" : scene);
        record.addProperty("headless", headless);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, record + "\n", StandardCharsets.UTF_8);
            file.toFile().deleteOnExit();
        } catch (IOException unwritable) {
            // Advertising the port is best-effort; an explicit --base-url still works.
        }
    }

    /**
     * Remove this process's record so a later CLI call does not chase a dead scene.
     * Failures are ignored.
     */
    public static void delete() {
        try {
            Files.deleteIfExists(location());
        } catch (IOException undeletable) {
            // A stale record is survivable: the CLI drops records whose pid is gone.
        }
    }
}
