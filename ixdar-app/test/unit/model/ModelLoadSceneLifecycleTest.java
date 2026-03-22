package unit.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import ixdar.platform.Platforms;
import ixdar.platform.file.FileManagement;
import ixdar.platform.gl.headless.HeadlessGL;
import ixdar.platform.gl.headless.HeadlessPlatform;
import ixdar.scenes.anatomy.ModelLoadScene;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

public class ModelLoadSceneLifecycleTest {

    @Test
    public void sceneCanInitDrawDeactivateAndShutdownWithoutLeaksOrCrashes() throws Exception {
        Path assetRepo = Files.createTempDirectory("ixdar-assets-");
        Files.writeString(assetRepo.resolve(FileManagement.DEFAULT_TEST_MODEL_FILE), simpleTriangleObj());
        System.setProperty(FileManagement.ASSET_REPO_PROP, assetRepo.toAbsolutePath().toString());

        try {
            Platforms.init(new HeadlessPlatform(), new HeadlessGL());
            ModelLoadScene scene = new ModelLoadScene();

            assertDoesNotThrow(scene::initGL);
            assertDoesNotThrow(scene::drawScene);
            assertDoesNotThrow(() -> scene.activate(false));
            assertDoesNotThrow(scene::shutdown);
        } finally {
            System.clearProperty(FileManagement.ASSET_REPO_PROP);
        }
    }

    private static String simpleTriangleObj() {
        return ""
                + "o Triangle\n"
                + "v 0.0 0.0 0.0\n"
                + "v 1.0 0.0 0.0\n"
                + "v 0.0 1.0 0.0\n"
                + "vn 0.0 0.0 1.0\n"
                + "f 1//1 2//1 3//1\n";
    }
}
