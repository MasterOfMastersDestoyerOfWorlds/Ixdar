package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Proxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.Platform;
import ixdar.scenes.mesh.MeshNodeViewerScene;
import ixdar.scenes.model.ModelScene;
import ixdar.scenes.ring.RingScene;
import unit.GlStandIn;

/**
 * The mesh viewer and the ring scene host the orbit tool as their default and open on it, so a
 * double-click on empty space frames the model before any tool switch.
 */
class ModelSceneDefaultToolTest {

    private static final int STAND_IN_PLATFORM_ID = 7718;

    private static final float ZOOMED_OUT = 12f;

    private static final float EXACT = 0f;

    private Platform suitePlatform;

    private GL suiteGl;

    /**
     * A platform whose window takes no callbacks, paired with a GL that does nothing.
     */
    @BeforeEach
    void installStandIns() {
        suitePlatform = Platforms.get();
        suiteGl = Platforms.gl();
        Platform window = (Platform) Proxy.newProxyInstance(Platform.class.getClassLoader(),
                new Class<?>[] {Platform.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getPlatformID")) {
                        return STAND_IN_PLATFORM_ID;
                    }
                    Class<?> type = method.getReturnType();
                    return type == int.class ? 0 : type == float.class ? 0f
                            : type == boolean.class ? false : null;
                });
        Platforms.init(window, GlStandIn.create());
    }

    /**
     * Put the suite's platform back.
     */
    @AfterEach
    void restoreSuitePlatform() {
        Platforms.init(suitePlatform, suiteGl);
    }

    @Test
    void theMeshViewerFramesOnADoubleClickRightAfterStartup() {
        assertDoubleClickFrames("mesh-viewer", new MeshNodeViewerScene());
    }

    @Test
    void theRingSceneFramesOnADoubleClickRightAfterStartup() {
        assertDoubleClickFrames("ring", new RingScene());
    }

    private static void assertDoubleClickFrames(String sceneId, ModelScene scene) {
        scene.initPoints();
        scene.setTools();
        scene.switchTool(scene.defaultTool);
        scene.initInput();
        assertSame(scene.orbitTool, scene.activeTool, sceneId + ": the orbit tool is active");
        scene.camera.setOrbit(scene.camera.azimuth, scene.camera.elevation, ZOOMED_OUT);
        assertNotEquals(scene.framedDistance, scene.camera.distance, EXACT,
                sceneId + ": zoomed away from the framed distance");
        for (int click = 0; click < 2; click++) {
            assertFalse(scene.activeTool.onPress(0f, 0f, 0), sceneId + ": the drag is the camera's");
            assertFalse(scene.activeTool.onRelease(0f, 0f, true), sceneId + ": the overlay links get it too");
        }
        assertEquals(scene.framedDistance, scene.camera.distance, EXACT,
                sceneId + ": the double-click framed the model");
    }
}
