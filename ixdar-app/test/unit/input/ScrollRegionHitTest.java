package unit.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

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

    private static final int TRACKPAD_EVENTS = 50;

    private static final double TRACKPAD_DELTA = 0.05;

    private static final double TRACKPAD_TOTAL = 2.5;

    private static final double EXACT = 1e-12;

    private static final double ZOOM_BASE = 0.97;

    private static final double ZOOM_TOLERANCE = 1e-4;

    private Platform suitePlatform;

    private GL suiteGl;

    private final TakesWheel stripHandler = new TakesWheel();

    private final TakesWheel bottomBoxHandler = new TakesWheel();

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

    @Test
    void trackpadSizedDeltasReachTheRegionUntruncatedOneFrameAtATime() {
        orbit.mousePos(WINDOW_X_JUST_RIGHT_OF_STRIP_LEFT, WINDOW_Y_INSIDE_BOTTOM_BOX);
        for (int event = 0; event < TRACKPAD_EVENTS; event++) {
            orbit.scrollCallback(TRACKPAD_DELTA);
            orbit.paintUpdate(1f);
        }
        assertEquals(TRACKPAD_EVENTS, bottomBoxHandler.deltas.size());
        for (double delta : bottomBoxHandler.deltas) {
            assertEquals(TRACKPAD_DELTA, delta, "each delta arrives as sent");
        }
        assertEquals(TRACKPAD_TOTAL, bottomBoxHandler.total(), EXACT);
        assertEquals(0, orbit.queuedScrollDelta);
        assertEquals(0, stripHandler.deltas.size());
    }

    @Test
    void deltasQueuedBetweenFramesReachTheRegionOnceAsTheirExactSum() {
        orbit.mousePos(WINDOW_X_JUST_RIGHT_OF_STRIP_LEFT, WINDOW_Y_INSIDE_BOTTOM_BOX);
        for (int event = 0; event < TRACKPAD_EVENTS; event++) {
            orbit.scrollCallback(-TRACKPAD_DELTA);
        }
        orbit.paintUpdate(1f);
        orbit.paintUpdate(1f);
        assertEquals(1, bottomBoxHandler.deltas.size());
        assertEquals(-TRACKPAD_TOTAL, bottomBoxHandler.total(), EXACT);
    }

    @Test
    void aSlowTrackpadZoomOffEveryRegionMovesTheCameraByTheWheelRatePerUnit() {
        orbit.mousePos(WINDOW_X_JUST_LEFT_OF_STRIP_LEFT, WINDOW_Y_MIDDLE);
        float start = orbit.getDistance();
        orbit.scrollCallback(TRACKPAD_DELTA);
        orbit.paintUpdate(1f);
        assertTrue(orbit.getDistance() < start, "one 0.05 delta zooms in");
        for (int event = 1; event < TRACKPAD_EVENTS; event++) {
            orbit.scrollCallback(TRACKPAD_DELTA);
            orbit.paintUpdate(1f);
        }
        double perUnit = Math.pow(ZOOM_BASE, MouseTrap.SCROLL_TICKS_PER_UNIT);
        assertEquals(start * Math.pow(perUnit, TRACKPAD_TOTAL), orbit.getDistance(), ZOOM_TOLERANCE);
        assertEquals(0, bottomBoxHandler.deltas.size() + stripHandler.deltas.size());
    }

    /**
     * A region that always takes the wheel and records every delta it is handed.
     */
    private static final class TakesWheel implements MouseTrap.ScrollHandler {

        public final List<Double> deltas = new ArrayList<>();

        @Override
        public void onScroll(boolean scrollUp, double deltaSeconds) {
        }

        @Override
        public void onScrollDelta(double delta) {
            deltas.add(delta);
        }

        @Override
        public boolean claimsWheel() {
            return true;
        }

        /**
         * Sum of the deltas handed over so far.
         *
         * @return their total
         */
        public double total() {
            double sum = 0;
            for (double delta : deltas) {
                sum += delta;
            }
            return sum;
        }
    }
}
