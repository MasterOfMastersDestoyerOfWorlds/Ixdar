package ixdar.platform.automation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AutomationPortFile {
    public static final String TMP_DIRECTORY = "tmp";
    public static final String PORT_FILE_NAME = "automation.port";
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
     * Where this checkout advertises its automation server, so a CLI run from the same
     * checkout finds the right scene without being told a port.
     *
     * @return path to {@code tmp/automation.port} under {@link #checkoutRoot()}
     */
    public static Path location() {
        return checkoutRoot().resolve(TMP_DIRECTORY).resolve(PORT_FILE_NAME);
    }

    /**
     * Publish the bound port and this JVM's process id, so a caller can both reach the
     * server and wait for the process to die. Failures are ignored.
     *
     * @param port the port the automation server actually bound
     */
    public static void write(int port) {
        Path file = location();
        String contents = "{\"port\": " + port
                + ", \"pid\": " + ProcessHandle.current().pid() + "}\n";
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, contents, StandardCharsets.UTF_8);
            file.toFile().deleteOnExit();
        } catch (IOException unwritable) {
            // Advertising the port is best-effort; an explicit --base-url still works.
        }
    }

    /**
     * Remove this checkout's port file so a later CLI call does not chase a dead scene.
     * Failures are ignored.
     */
    public static void delete() {
        try {
            Files.deleteIfExists(location());
        } catch (IOException undeletable) {
            // A stale file is survivable: the CLI falls back when nothing answers.
        }
    }
}
