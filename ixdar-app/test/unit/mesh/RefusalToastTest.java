package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.graphics.cameras.Camera3D;
import ixdar.platform.input.Keys;
import ixdar.platform.input.MouseTrap;
import ixdar.platform.input.OrbitMouseTrap;
import ixdar.scenes.connection.ConnectionTool;
import ixdar.scenes.model.ControlHint;
import ixdar.scenes.regions.RingRegionTool;
import ixdar.scenes.ring.ErrorToasts;
import ixdar.scenes.ring.RingScene;
import ixdar.scenes.ring.RingTool;

/**
 * Refused clicks and keys in the ring editing scene raise one red toast each, saying what to do;
 * the same refusal again restarts its fade instead of stacking, and a toast drops once faded.
 */
class RefusalToastTest {

    private static final float RADIUS = 0.4f;

    private static final float AGED_SECONDS = 20f;

    private static final float FRESH_OPACITY = 0.99f;

    private static final float AGED_OPACITY = 0.5f;

    @Test
    void aRefusedClickShowsOneToastAndARepeatRestartsItsFade() {
        RingScene scene = sceneOn(RingAnchorOrderTest.tube(0f, RADIUS));
        RingTool tool = scene.ringTool;
        ErrorToasts toasts = scene.errorToasts;

        tool.requestClick();
        scene.updateScene();

        assertEquals(List.of(RingScene.NO_SURFACE_REFUSAL), toasts.messages,
                "a click with no mesh shown raised exactly one toast saying to wait");
        assertEquals(List.of(RingTool.TOOL_NAME), toasts.sources);
        assertEquals(RingScene.NO_SURFACE_REFUSAL, tool.lastError);

        toasts.colors.get(0).startSeconds -= AGED_SECONDS;
        assertTrue(alpha(toasts, 0) < AGED_OPACITY, "the toast fades with age");

        tool.requestClick();
        scene.updateScene();

        assertEquals(List.of(RingScene.NO_SURFACE_REFUSAL), toasts.messages,
                "the repeated refusal did not stack a second toast");
        assertTrue(alpha(toasts, 0) > FRESH_OPACITY, "the repeat restarted the fade");
    }

    @Test
    void refusedKeysStackNewestOnTopAndFadeOut() {
        RingScene scene = sceneOn(RingAnchorOrderTest.tube(0f, RADIUS));
        ErrorToasts toasts = scene.errorToasts;

        press(scene, Keys.ENTER);
        press(scene, Keys.X);

        assertEquals(2, toasts.messages.size(), "each refused key raised its own toast");
        assertEquals(RingTool.NO_DRAFT_TO_DISCARD, toasts.messages.get(0), "the newest is on top");
        assertTrue(toasts.messages.get(1).startsWith("no draft to confirm - click a ring"),
                toasts.messages.get(1));

        toasts.colors.get(1).startSeconds -= ErrorToasts.FADE_SECONDS;
        scene.updateScene();

        assertEquals(List.of(RingTool.NO_DRAFT_TO_DISCARD), toasts.messages,
                "the toast older than the fade dropped");
    }

    @Test
    void everyToolRoutesItsRefusalsToTheSceneToastsUnderItsOwnName() {
        RingScene scene = sceneOn(RingAnchorOrderTest.tube(0f, RADIUS));
        ErrorToasts toasts = scene.errorToasts;

        scene.switchTool(scene.connectionTool);
        press(scene, Keys.ENTER);
        scene.switchTool(scene.regionTool);
        press(scene, Keys.O);

        assertEquals(List.of(RingRegionTool.TOOL_NAME, ConnectionTool.TOOL_NAME), toasts.sources,
                "each refusal names the tool that raised it, newest first");
        assertTrue(toasts.messages.get(0).contains("press E"), toasts.messages.get(0));
        assertTrue(toasts.messages.get(1).contains("click two places"), toasts.messages.get(1));
    }

    @Test
    void aClickWhileTheCameraMovesIsRefusedWithWhatToDo() {
        RingScene scene = sceneOn(RingAnchorOrderTest.tube(0f, RADIUS));
        OrbitMouseTrap orbit = new OrbitMouseTrap(new Camera3D(new Vector3f(), 0f, 0f, null), null);
        scene.orbitMouse = orbit;
        scene.mouse = orbit;

        assertEquals(scene.pickRefusal(), scene.cursorPickRefusal(null),
                "with the camera still and no mesh runtime, the view's own refusal stands");

        orbit.zoomSettleFramesLeft = MouseTrap.ZOOM_SETTLE_FRAMES;

        assertEquals(RingScene.CAMERA_MOVING_REFUSAL, scene.cursorPickRefusal(null),
                "a settling zoom refuses the pick until the camera stops");
    }

    private static float alpha(ErrorToasts toasts, int toast) {
        return toasts.colors.get(toast).toVector4f().w;
    }

    private static void press(RingScene scene, int keyCode) {
        for (ControlHint hint : scene.controls()) {
            if (hint.keyCode == keyCode && !hint.controlHeld && hint.action != null) {
                hint.action.perform();
                return;
            }
        }
        throw new AssertionError("no hint for key " + keyCode);
    }

    private static RingScene sceneOn(MeshTopology tube) {
        RingScene scene = new RingScene() {
            @Override
            public MeshTopology getMesh() {
                return tube;
            }

            @Override
            public MeshTopology halfEdgeSurface() {
                return tube;
            }
        };
        scene.switchTool(scene.ringTool);
        return scene;
    }
}
