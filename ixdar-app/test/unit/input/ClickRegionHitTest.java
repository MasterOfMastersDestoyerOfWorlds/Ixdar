package unit.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;
import static ixdar.platform.input.Keys.MOUSE_BUTTON_LEFT;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;

import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera3D;
import ixdar.input.MouseTrap;
import ixdar.input.PointerDispatcher;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.input.PointerRegion;

/**
 * On a 1.25-scaled display a click region, a framebuffer viewport, takes only clicks inside its
 * drawn rectangle: a shader code line beside the preview half must not restore the shader.
 */
class ClickRegionHitTest {

    private static final int STAND_IN_PLATFORM_ID = 7717;

    private static final int WINDOW_WIDTH = 800;

    private static final int WINDOW_HEIGHT = 600;

    private static final int FRAMEBUFFER_WIDTH = 1000;

    private static final int FRAMEBUFFER_HEIGHT = 750;

    private static final float PREVIEW_WIDTH = FRAMEBUFFER_WIDTH / 2f;

    private static final float BOTTOM_BOX_HEIGHT = 100f;

    private static final float WINDOW_X_ON_THE_CODE_LEFT_EDGE = 440f;

    private static final float WINDOW_X_INSIDE_THE_PREVIEW = 380f;

    private static final float WINDOW_Y_MIDDLE = 300f;

    private static final float WINDOW_Y_INSIDE_BOTTOM_BOX = 530f;

    private static final float WINDOW_Y_ABOVE_BOTTOM_BOX = 515f;

    private static final String GET_PLATFORM_ID = "getPlatformID";

    private Platform suitePlatform;

    private GL suiteGl;

    private final CountsClicks preview = new CountsClicks();

    private final CountsClicks bottomBox = new CountsClicks();

    private MouseTrap trap;

    private PointerDispatcher pointer;

    /**
     * Install a scaled platform and subscribe the preview half for clicks.
     */
    @BeforeEach
    void installScaledPlatform() {
        suitePlatform = Platforms.get();
        suiteGl = Platforms.gl();
        Platform scaled = (Platform) Proxy.newProxyInstance(Platform.class.getClassLoader(),
                new Class<?>[] {Platform.class}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "getWindowWidth":
                            return WINDOW_WIDTH;
                        case "getWindowHeight":
                            return WINDOW_HEIGHT;
                        case "getFrameBufferWidth":
                            return FRAMEBUFFER_WIDTH;
                        case "getFrameBufferHeight":
                            return FRAMEBUFFER_HEIGHT;
                        case GET_PLATFORM_ID:
                            return STAND_IN_PLATFORM_ID;
                        default:
                            return method.getReturnType() == int.class ? 0 : null;
                    }
                });
        GL mouseGl = (GL) Proxy.newProxyInstance(GL.class.getClassLoader(),
                new Class<?>[] {GL.class}, (proxy, method, arguments) -> {
                    if (GET_PLATFORM_ID.equals(method.getName())) {
                        return STAND_IN_PLATFORM_ID;
                    }
                    return method.getReturnType() == int.class ? 0
                            : method.getReturnType() == boolean.class ? false : null;
                });
        Platforms.init(scaled, mouseGl);
        pointer = PointerDispatcher.forPlatform(STAND_IN_PLATFORM_ID);
        pointer.clickBounds.add(new Bounds(0, 0, PREVIEW_WIDTH, BOTTOM_BOX_HEIGHT, "BOX"));
        pointer.clickRegions.add(bottomBox);
        pointer.clickBounds.add(new Bounds(0, 0, PREVIEW_WIDTH, FRAMEBUFFER_HEIGHT, "PREVIEW"));
        pointer.clickRegions.add(preview);
        trap = new MouseTrap(new Camera3D(new Vector3f(), 0f, 0f), null);
    }

    /**
     * Drop the click regions and put the suite's platform back.
     */
    @AfterEach
    void restoreSuitePlatform() {
        pointer.unsubscribe(preview);
        pointer.unsubscribe(bottomBox);
        Platforms.init(suitePlatform, suiteGl);
    }

    @Test
    void aClickOnTheCodeBesideThePreviewDoesNotReachThePreview() {
        click(WINDOW_X_ON_THE_CODE_LEFT_EDGE, WINDOW_Y_MIDDLE);
        assertEquals(0, preview.clicks + bottomBox.clicks);
    }

    @Test
    void aClickInsideThePreviewReachesIt() {
        click(WINDOW_X_INSIDE_THE_PREVIEW, WINDOW_Y_MIDDLE);
        assertEquals(1, preview.clicks);
        assertEquals(0, bottomBox.clicks);
    }

    @Test
    void theBottomBoxIsTestedYUpInFramebufferPixels() {
        click(WINDOW_X_INSIDE_THE_PREVIEW, WINDOW_Y_INSIDE_BOTTOM_BOX);
        assertEquals(1, bottomBox.clicks);
        click(WINDOW_X_INSIDE_THE_PREVIEW, WINDOW_Y_ABOVE_BOTTOM_BOX);
        assertEquals(1, bottomBox.clicks, "above the box the click is the preview's");
        assertEquals(1, preview.clicks);
    }

    private void click(float windowX, float windowY) {
        pointer.moveOrDrag(trap, 0L, windowX, windowY);
        pointer.mouseButton(trap, MOUSE_BUTTON_LEFT, ACTION_PRESS, 0);
        pointer.mouseButton(trap, MOUSE_BUTTON_LEFT, ACTION_RELEASE, 0);
    }

    /**
     * A click region counting the clicks it is handed.
     */
    private static final class CountsClicks implements PointerRegion {

        public int clicks;

        @Override
        public void onScroll(boolean scrollUp, double deltaSeconds) {
        }

        @Override
        public void onClick(int button) {
            clicks++;
        }
    }
}
