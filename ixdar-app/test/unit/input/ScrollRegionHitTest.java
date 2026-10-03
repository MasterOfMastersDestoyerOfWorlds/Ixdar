package unit.input;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Proxy;

import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera3D;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.gl.Platform;
import ixdar.platform.input.MouseTrap;
import ixdar.platform.input.OrbitMouseTrap;

/**
 * A subscribed scroll region is a framebuffer viewport, so on a scaled display (window 800x600,
 * framebuffer 1000x750, a 1.25 content scale) the wheel over any point of its drawn rectangle,
 * its left edge included, must reach its handler rather than the orbit camera.
 */
class ScrollRegionHitTest {

    private static final int WINDOW_WIDTH = 800;

    private static final int WINDOW_HEIGHT = 600;

    private static final int FRAMEBUFFER_WIDTH = 1000;

    private static final int FRAMEBUFFER_HEIGHT = 750;

    private static final float MENU_STRIP_WIDTH = 420f;

    private static final float MENU_STRIP_LEFT = FRAMEBUFFER_WIDTH - MENU_STRIP_WIDTH;

    private static final float BOTTOM_BOX_HEIGHT = 100f;

    private static final float WINDOW_X_JUST_RIGHT_OF_STRIP_LEFT = 465f;

    private static final float WINDOW_X_JUST_LEFT_OF_STRIP_LEFT = 463f;

    private static final float WINDOW_X_MIDDLE_OF_STRIP = 630f;

    private static final float WINDOW_X_RIGHT_EDGE = 799f;

    private static final float WINDOW_Y_MIDDLE = 300f;

    private static final float WINDOW_Y_INSIDE_BOTTOM_BOX = 530f;

    private static final float WINDOW_Y_ABOVE_BOTTOM_BOX = 515f;

    private Platform suitePlatform;

    private GL suiteGl;

    private final MouseTrap.ScrollHandler stripHandler = new TakesWheel();

    private final MouseTrap.ScrollHandler bottomBoxHandler = new TakesWheel();

    private OrbitMouseTrap orbit;

    /**
     * Install a scaled platform and subscribe a right-hand strip and a bottom box inside it.
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
                        default:
                            return method.getReturnType() == int.class ? 0 : null;
                    }
                });
        Platforms.init(scaled, suiteGl);
        MouseTrap.subscribeScrollRegion(
                new Bounds(MENU_STRIP_LEFT, 0, MENU_STRIP_WIDTH, BOTTOM_BOX_HEIGHT, "BOX"),
                bottomBoxHandler);
        MouseTrap.subscribeScrollRegion(
                new Bounds(MENU_STRIP_LEFT, 0, MENU_STRIP_WIDTH, FRAMEBUFFER_HEIGHT, "STRIP"),
                stripHandler);
        orbit = new OrbitMouseTrap(new Camera3D(new Vector3f(), 0f, 0f, null), null);
    }

    /**
     * Drop the subscriptions and put the suite's platform back.
     */
    @AfterEach
    void restoreSuitePlatform() {
        MouseTrap.unsubscribeScrollRegion(stripHandler);
        MouseTrap.unsubscribeScrollRegion(bottomBoxHandler);
        Platforms.init(suitePlatform, suiteGl);
    }

    @Test
    void wheelBeforeAnyCursorMoveReachesNoRegion() {
        assertNull(orbit.wheelClaimerUnderCursor());
    }

    @Test
    void everyPointAcrossTheDrawnStripTakesTheWheel() {
        for (float windowX : new float[] {WINDOW_X_JUST_RIGHT_OF_STRIP_LEFT, WINDOW_X_MIDDLE_OF_STRIP,
                WINDOW_X_RIGHT_EDGE}) {
            orbit.mousePos(windowX, WINDOW_Y_MIDDLE);
            assertSame(stripHandler, orbit.wheelClaimerUnderCursor(), "window x " + windowX);
        }
    }

    @Test
    void justLeftOfTheDrawnStripTheWheelStaysWithTheCamera() {
        orbit.mousePos(WINDOW_X_JUST_LEFT_OF_STRIP_LEFT, WINDOW_Y_MIDDLE);
        assertNull(orbit.wheelClaimerUnderCursor());
    }

    @Test
    void theBottomBoxIsTestedYUpInFramebufferPixels() {
        orbit.mousePos(WINDOW_X_JUST_RIGHT_OF_STRIP_LEFT, WINDOW_Y_INSIDE_BOTTOM_BOX);
        assertSame(bottomBoxHandler, orbit.wheelClaimerUnderCursor());
        orbit.mousePos(WINDOW_X_JUST_RIGHT_OF_STRIP_LEFT, WINDOW_Y_ABOVE_BOTTOM_BOX);
        assertSame(stripHandler, orbit.wheelClaimerUnderCursor());
    }

    /**
     * A region that always takes the wheel and ignores the scroll itself.
     */
    private static final class TakesWheel implements OrbitMouseTrap.WheelClaimer {

        @Override
        public void onScroll(boolean scrollUp, double deltaSeconds) {
        }

        @Override
        public boolean claimsWheel() {
            return true;
        }
    }
}
