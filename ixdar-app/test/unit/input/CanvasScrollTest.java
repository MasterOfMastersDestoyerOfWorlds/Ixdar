package unit.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;
import static ixdar.platform.input.Keys.MOUSE_BUTTON_LEFT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.geometry.tsp.points.PointND;
import ixdar.geometry.tsp.points.PointSet;
import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera2D;
import ixdar.gui.ui.menu.MenuScrollBar;
import ixdar.input.CanvasScroll;
import ixdar.input.MouseTrap;
import ixdar.input.PointerDispatcher;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.input.KeyNames;
import ixdar.platform.input.PointerRegion;
import unit.GlStandIn;

/**
 * At 1.25 display scale, in a view offset as the knot editor's is, a {@link CanvasScroll} the
 * dispatcher routes to pans, turns sideways, zooms about the cursor, and with bars keeps its range.
 */
class CanvasScrollTest {

    private static final int WINDOW_WIDTH = 1024;

    private static final int WINDOW_HEIGHT = 768;

    private static final int FRAMEBUFFER_WIDTH = 1280;

    private static final int FRAMEBUFFER_HEIGHT = 960;

    private static final float CONTENT_SCALE = (float) FRAMEBUFFER_WIDTH / WINDOW_WIDTH;

    private static final float VIEW_OFFSET_Y = 195f;

    private static final float VIEW_WIDTH = 1000f;

    private static final float VIEW_HEIGHT = FRAMEBUFFER_HEIGHT - VIEW_OFFSET_Y;

    private static final String VIEW = "MAIN";

    private static final double POINT_SPAN = 100.0;

    private static final float SHORT_CONTENT_HEIGHT = 10f;

    private static final float CURSOR_X = 700f;

    private static final float CURSOR_Y = 500f;

    private static final double SIDEWAYS_DELTA = 0.5;

    private static final double WHEEL_DELTA = -2.0;

    private static final double ZOOM_DELTA = 2.0;

    private static final double DEEP_ZOOM_DELTA = 6.0;

    private static final int PINCH_STEPS = 20;

    private static final int SWIPES_PAST_THE_EDGE = 200;

    private static final double SWIPE_DELTA = 2.0;

    private static final float BAR_PRESS_X = VIEW_WIDTH - 2 * MenuScrollBar.WIDTH_PIXELS;

    private static final float BAR_PRESS_Y = VIEW_OFFSET_Y + MenuScrollBar.WIDTH_PIXELS / 2;

    private static final float PIXEL_TOLERANCE = 1f;

    private static final float ZOOM_TOLERANCE = 0.01f;

    private static final float EPSILON = 1e-3f;

    private Platform suitePlatform;

    private GL suiteGl;

    private Camera2D camera;

    private CanvasScroll canvas;

    private MouseTrap trap;

    private PointerDispatcher pointer;

    /**
     * Install a 1.25-scaled platform and a camera over a 100-unit square framed in a view 195
     * framebuffer pixels up from the bottom, its scroll subscribed over the view and the trap's
     * controller.
     */
    @BeforeEach
    void installScaledCanvas() {
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
                            return method.invoke(suitePlatform, arguments);
                    }
                });
        Platforms.init(scaled, GlStandIn.create());
        PointSet points = new PointSet();
        points.add(new PointND.Double(0.0, 0.0));
        points.add(new PointND.Double(POINT_SPAN, POINT_SPAN));
        camera = new Camera2D((int) VIEW_WIDTH, (int) VIEW_HEIGHT, 1f, 0, VIEW_OFFSET_Y, points);
        Map<String, Bounds> views = new HashMap<>();
        views.put(VIEW, new Bounds(0, VIEW_OFFSET_Y, VIEW_WIDTH, VIEW_HEIGHT, VIEW));
        camera.initCamera(views, VIEW);
        pointer = PointerDispatcher.current();
        trap = new MouseTrap(camera, null);
        trap.active = true;
        canvas = scroll(false);
    }

    /**
     * Drop the scroll's subscription and put the suite's platform back.
     */
    @AfterEach
    void restoreSuitePlatform() {
        pointer.unsubscribe(canvas);
        Platforms.init(suitePlatform, suiteGl);
    }

    @Test
    void theWheelOverTheViewPansBothAxesAndLeavesTheZoom() {
        moveTo(CURSOR_X, CURSOR_Y);
        assertSame(canvas, pointer.regionUnderCursor(trap, PointerRegion::claimsWheel));
        float panX = camera.PanX;
        float panY = camera.PanY;
        float scale = camera.ScaleFactor;
        trap.scrollCallback(SIDEWAYS_DELTA, WHEEL_DELTA);
        pointer.paintUpdate(trap, 1f);
        assertEquals(panX + SIDEWAYS_DELTA * CanvasScroll.SCROLL_PAN_PX_PER_UNIT, camera.PanX, EPSILON,
                "a swipe toward the left moves the content right");
        assertEquals(panY - WHEEL_DELTA * CanvasScroll.SCROLL_PAN_PX_PER_UNIT, camera.PanY, EPSILON,
                "the wheel toward the user moves the content up");
        assertEquals(scale, camera.ScaleFactor, "the wheel no longer zooms");
    }

    @Test
    void aCursorBelowTheViewLeavesTheCanvasAlone() {
        moveTo(CURSOR_X, VIEW_OFFSET_Y / 2);
        assertNull(pointer.regionUnderCursor(trap, PointerRegion::claimsWheel), "the panel below the view");
        float panY = camera.PanY;
        trap.scrollCallback(0, WHEEL_DELTA);
        pointer.paintUpdate(trap, 1f);
        assertEquals(panY, camera.PanY);
    }

    @Test
    void shiftTurnsTheWheelSideways() {
        float panX = camera.PanX;
        float panY = camera.PanY;
        canvas.onScrollDelta(0, WHEEL_DELTA, CURSOR_X, CURSOR_Y, KeyNames.MOD_SHIFT);
        assertEquals(panX + WHEEL_DELTA * CanvasScroll.SCROLL_PAN_PX_PER_UNIT, camera.PanX, EPSILON);
        assertEquals(panY, camera.PanY, EPSILON, "nothing moves vertically");
    }

    @Test
    void controlZoomsAboutTheCursorKeepingItsPointUnderIt() {
        camera.calculateCameraTransform(camera.ps);
        float viewX = CURSOR_X;
        float viewY = CURSOR_Y - VIEW_OFFSET_Y;
        float pointX = camera.screenTransformX(viewX);
        float pointY = camera.screenTransformY(viewY);
        float scale = camera.ScaleFactor;
        for (int step = 0; step < PINCH_STEPS; step++) {
            canvas.onScrollDelta(0, ZOOM_DELTA / PINCH_STEPS, CURSOR_X, CURSOR_Y, KeyNames.MOD_CONTROL);
        }
        camera.calculateCameraTransform(camera.ps);
        assertEquals(scale * Math.pow(CanvasScroll.ZOOM_PER_SCROLL_UNIT, ZOOM_DELTA), camera.ScaleFactor, EPSILON);
        assertEquals(viewX, camera.pointTransformX(pointX), ZOOM_TOLERANCE,
                "a pinch's many steps keep the cursor's point in x");
        assertEquals(viewY, camera.pointTransformY(pointY), ZOOM_TOLERANCE, "and in y");
    }

    @Test
    void wideShortContentAtFrameAllFillsBothBarsAndAZoomInShrinksBothThumbs() {
        CanvasScroll bars = scroll(true);
        float width = (float) POINT_SPAN;
        bars.setContent(0, 0, width, SHORT_CONTENT_HEIGHT);
        bars.frame(0, 0, width, SHORT_CONTENT_HEIGHT);
        bars.updateBars();
        assertFalse(bars.horizontalBar.isNeeded(), "frame-all shows the whole home range across");
        assertFalse(bars.verticalBar.isNeeded(), "and up: the home range is the content grown to the view's aspect");
        bars.onScrollDelta(0, ZOOM_DELTA, CURSOR_X, CURSOR_Y, KeyNames.MOD_CONTROL);
        bars.updateBars();
        assertTrue(bars.horizontalBar.thumbLength() < bars.horizontalBar.trackLength(), "zooming in shrinks the thumb");
        assertTrue(bars.verticalBar.thumbLength() < bars.verticalBar.trackLength(), "on both bars");
    }

    @Test
    void withScrollBarsTheRangeFollowsZoomScrollAndDrag() {
        CanvasScroll bars = scroll(true);
        bars.setContent(0, 0, (float) POINT_SPAN, (float) POINT_SPAN);
        bars.onScrollDelta(0, ZOOM_DELTA, CURSOR_X, CURSOR_Y, KeyNames.MOD_CONTROL);
        float rangeRight = bars.rangeRight;
        double homeRight = POINT_SPAN / 2 + POINT_SPAN / 2 * VIEW_WIDTH / VIEW_HEIGHT;
        assertEquals(Math.max(homeRight, camera.screenTransformX(VIEW_WIDTH)), rangeRight, EPSILON,
                "a zoom resets the range to the home range, the square grown to the view's aspect, and the view");
        for (int step = 0; step < SWIPES_PAST_THE_EDGE; step++) {
            bars.onScrollDelta(-SWIPE_DELTA, 0, CURSOR_X, CURSOR_Y, 0);
        }
        float pixelUnits = PIXEL_TOLERANCE / camera.width * camera.rangeX;
        assertEquals(rangeRight, bars.rangeRight, pixelUnits, "a scroll grows the range by no more than the bars' rounding");
        assertEquals(rangeRight, camera.screenTransformX(VIEW_WIDTH), pixelUnits,
                "swipes stop with the view's right edge on the range's");
        moveTo(CURSOR_X, CURSOR_Y);
        pointer.mouseButton(trap, MOUSE_BUTTON_LEFT, ACTION_PRESS, 0);
        assertNull(pointer.pressHolder, "a press off the bars is the trap's, so the controller pans");
        moveTo(CURSOR_X - VIEW_WIDTH / 2, CURSOR_Y);
        pointer.mouseButton(trap, MOUSE_BUTTON_LEFT, ACTION_RELEASE, 0);
        assertEquals(camera.screenTransformX(VIEW_WIDTH), bars.rangeRight, EPSILON,
                "a drag past the edge grows the range to hold the view");
    }

    @Test
    void theDispatcherHandsAPressOnABarToTheScroll() {
        CanvasScroll bars = scroll(true);
        bars.setContent(0, 0, (float) POINT_SPAN, (float) POINT_SPAN);
        bars.onScrollDelta(0, DEEP_ZOOM_DELTA, CURSOR_X, CURSOR_Y, KeyNames.MOD_CONTROL);
        float offset = bars.horizontalBar.scrollOffset;
        float panX = camera.PanX;
        moveTo(BAR_PRESS_X, BAR_PRESS_Y);
        pointer.mouseButton(trap, MOUSE_BUTTON_LEFT, ACTION_PRESS, 0);
        assertSame(bars, pointer.pressHolder, "the bar's region holds the press");
        assertEquals(offset + bars.horizontalBar.stepPixels, bars.horizontalBar.scrollOffset, PIXEL_TOLERANCE,
                "the right arrow steps right");
        assertEquals(panX - bars.horizontalBar.stepPixels, camera.PanX, PIXEL_TOLERANCE, "and the view follows");
        pointer.mouseButton(trap, MOUSE_BUTTON_LEFT, ACTION_RELEASE, 0);
        assertNull(bars.pressedBar, "the release lets go of the bar");
    }

    /**
     * Replace the subscribed scroll with a fresh one over the view, shown, which is also the
     * trap's controller.
     *
     * @param scrollBars whether the scroll has bars
     * @return the subscribed scroll
     */
    private CanvasScroll scroll(boolean scrollBars) {
        if (canvas != null) {
            pointer.unsubscribe(canvas);
        }
        canvas = new CanvasScroll(camera, scrollBars, () -> true);
        pointer.subscribe(camera.mainViewBounds, canvas);
        trap.controller = canvas;
        return canvas;
    }

    private void moveTo(float framebufferX, float framebufferY) {
        pointer.moveOrDrag(trap, 0L, framebufferX / CONTENT_SCALE, WINDOW_HEIGHT - framebufferY / CONTENT_SCALE);
    }
}
