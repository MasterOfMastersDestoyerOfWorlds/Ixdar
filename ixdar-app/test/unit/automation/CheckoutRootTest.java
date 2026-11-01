package unit.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import ixdar.automation.AutomationEndpoint;
import ixdar.geometry.mesh.data.io.MeshLoader;

/**
 * The checkout every path the program reads or writes resolves against, found from the JVM's
 * working directory (the module): a stray {@code ixdar-app/ixdar-app/} folder must not make the
 * module pass for the checkout, and a new file must land in the checkout's one {@code tmp/}.
 */
class CheckoutRootTest {

    private static final String RESOURCE = "dsl/shape.dsl";

    private static final String NEW_FILE = "tmp/out.ply";

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void pathsResolveFromTheCheckoutWhateverTheWorkingDirectory(@TempDir Path folder) throws IOException {
        Path checkout = folder.resolve("checkout");
        Path module = checkout.resolve(MeshLoader.MODULE_DIRECTORY);
        Path resource = module.resolve(MeshLoader.RESOURCE_DIRECTORY).resolve(RESOURCE);
        Files.createDirectories(module.resolve(MeshLoader.MODULE_DIRECTORY));
        Files.createDirectories(resource.getParent());
        Files.createFile(checkout.resolve(MeshLoader.POM_FILE));
        Files.createFile(module.resolve(MeshLoader.POM_FILE));
        Files.createFile(resource);
        String workingDirectory = System.getProperty(MeshLoader.WORKING_DIRECTORY_PROPERTY);
        try {
            System.setProperty(MeshLoader.WORKING_DIRECTORY_PROPERTY, module.toString());
            Path root = checkout.toAbsolutePath().normalize();
            assertEquals(root, MeshLoader.checkoutRoot());
            assertEquals(root.resolve(NEW_FILE), MeshLoader.resolve(NEW_FILE),
                    "a file that does not exist yet lands in the checkout's tmp/, not the module's");
            assertEquals(resource.toAbsolutePath(), MeshLoader.resolve(RESOURCE), "a resource name finds the resource");
            assertNull(AutomationEndpoint.resolvePath(NEW_FILE), "a path naming nothing resolves to null");
        } finally {
            System.setProperty(MeshLoader.WORKING_DIRECTORY_PROPERTY, workingDirectory);
        }
    }
}
