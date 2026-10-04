package unit.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;
import static ixdar.platform.input.Keys.MOUSE_BUTTON_LEFT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import ixdar.platform.input.OrbitMouseTrap;
import ixdar.platform.input.PointerDispatcher;
import ixdar.platform.input.PointerRegion;

/**
 * A left press, drag and release sent through the {@link PointerDispatcher} over a subscribed
 * region that claims the pointer reach only that region, never the orbit camera or the scene tool
 * behind it; outside it, or while it does not claim, the dispatcher hands them to the orbit.
 */
class PointerRegionPressTest {

    private static final int STAND_IN_PLATFORM_ID = 7716;

    private static final int WINDOW_WIDTH = 800;

    private static final int WINDOW_HEIGHT = 600;

    private static final float REGION_LEFT = 500f;

    private static final float INSIDE_X = 650f;

    private static final float OUTSIDE_X = 200f;

    private static final float MIDDLE_Y = 300f;

    private static final float DRAG_STEP = 100f;

    private static final float EXACT = 0f;

    private static final String GET_PLATFORM_ID = "getPlatformID";

    private static final String PRESS = "press";

    private static final String DRAG = "drag";

    private static final String RELEASE_AFTER_DRAG = "release drag";

    private static final String RELEASE_AS_CLICK = "release click";

    private Platform suitePlatform;

    private GL suiteGl;

    private boolean leftHeld;

    private final RecordingRegion region = new RecordingRegion();

    private int toolGrabs;

    private int toolClicks;

    private int toolReleases;

    private OrbitMouseTrap orbit;

    private PointerDispatcher pointer;

    /**
     * Install a platform whose window is its framebuffer, a GL reporting {@link #leftHeld}, a
     * right-hand region, and an orbit with a tool that grabs every press and counts what it gets.
     */
    @BeforeEach
    void installRegionAndTool() {
        suitePlatform = Platforms.get();
        suiteGl = Platforms.gl();
        Platform window = (Platform) Proxy.newProxyInstance(Platform.class.getClassLoader(),
                new Class<?>[] {Platform.class}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "getWindowWidth":
                        case "getFrameBufferWidth":
                            return WINDOW_WIDTH;
                        case "getWindowHeight":
                        case "getFrameBufferHeight":
                            return WINDOW_HEIGHT;
                        case GET_PLATFORM_ID:
                            return STAND_IN_PLATFORM_ID;
                        default:
                            return method.getReturnType() == int.class ? 0 : null;
                    }
                });
        GL mouseGl = (GL) Proxy.newProxyInstance(GL.class.getClassLoader(),
                new Class<?>[] {GL.class}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case GET_PLATFORM_ID:
                            return STAND_IN_PLATFORM_ID;
                        case "getMouseButton":
                            return leftHeld;
                        default:
                            return method.getReturnType() == int.class ? 0
                                    : method.getReturnType() == boolean.class ? false : null;
                    }
                });
        Platforms.init(window, mouseGl);
        pointer = PointerDispatcher.forPlatform(STAND_IN_PLATFORM_ID);
        pointer.subscribe(
                new Bounds(REGION_LEFT, 0, WINDOW_WIDTH - REGION_LEFT, WINDOW_HEIGHT, "REGION"),
                region);
        orbit = new OrbitMouseTrap(new Camera3D(new Vector3f(), 0f, 0f, null), null);
        orbit.toolGrab = () -> {
            toolGrabs++;
            return false;
        };
        orbit.toolClick = button -> toolClicks++;
        orbit.toolRelease = () -> toolReleases++;
    }

    /**
     * Drop the region and put the suite's platform back.
     */
    @AfterEach
    void restoreSuitePlatform() {
        pointer.unsubscribe(region);
        Platforms.init(suitePlatform, suiteGl);
    }

    @Test
    void aPressDragAndReleaseOverTheRegionReachOnlyTheRegion() {
        float azimuth = orbit.getAzimuth();
        float elevation = orbit.getElevation();
        pressDragRelease(INSIDE_X);
        assertEquals(List.of(PRESS, DRAG, DRAG, RELEASE_AFTER_DRAG), region.events);
        assertEquals(0, toolGrabs + toolClicks + toolReleases, "the tool saw none of it");
        assertEquals(azimuth, orbit.getAzimuth(), EXACT, "the orbit did not turn");
        assertEquals(elevation, orbit.getElevation(), EXACT);
        assertTrue(region.lastX < REGION_LEFT, "the drag left the region");
        assertEquals(INSIDE_X - 2 * DRAG_STEP, region.lastX, EXACT,
                "a drag leaving the region stays the region's");
    }

    @Test
    void aClickOverTheRegionIsTheRegionsClickNotTheTools() {
        click(INSIDE_X);
        assertEquals(List.of(PRESS, RELEASE_AS_CLICK), region.events);
        assertEquals(0, toolGrabs + toolClicks + toolReleases);
    }

    @Test
    void outsideTheRegionTheToolGetsTheClickAndTheOrbitTheDrag() {
        click(OUTSIDE_X);
        assertEquals(1, toolGrabs, "the tool was asked whether it grabs the press");
        assertEquals(1, toolClicks, "the tool got the click");
        float azimuth = orbit.getAzimuth();
        pressDragRelease(OUTSIDE_X);
        assertNotEquals(azimuth, orbit.getAzimuth(), "the drag turned the orbit");
        assertEquals(List.of(), region.events);
    }

    @Test
    void aRegionThatDoesNotClaimLeavesThePressToTheOrbitAndTool() {
        region.claiming = false;
        click(INSIDE_X);
        float azimuth = orbit.getAzimuth();
        pressDragRelease(INSIDE_X);
        assertEquals(1, toolClicks);
        assertNotEquals(azimuth, orbit.getAzimuth());
        assertEquals(List.of(), region.events);
    }

    private void click(float windowX) {
        pointer.moveOrDrag(orbit, 0L, windowX, MIDDLE_Y);
        pointer.mouseButton(orbit, MOUSE_BUTTON_LEFT, ACTION_PRESS, 0);
        pointer.mouseButton(orbit, MOUSE_BUTTON_LEFT, ACTION_RELEASE, 0);
    }

    private void pressDragRelease(float windowX) {
        pointer.moveOrDrag(orbit, 0L, windowX, MIDDLE_Y);
        pointer.mouseButton(orbit, MOUSE_BUTTON_LEFT, ACTION_PRESS, 0);
        leftHeld = true;
        pointer.moveOrDrag(orbit, 0L, windowX - DRAG_STEP, MIDDLE_Y);
        pointer.mouseDragged(orbit, windowX - 2 * DRAG_STEP, MIDDLE_Y);
        leftHeld = false;
        pointer.mouseButton(orbit, MOUSE_BUTTON_LEFT, ACTION_RELEASE, 0);
    }

    /**
     * A region that claims the pointer while {@link #claiming} holds and records what it gets.
     */
    private static final class RecordingRegion implements PointerRegion {

        public final List<String> events = new ArrayList<>();

        public boolean claiming = true;

        public float lastX;

        @Override
        public void onScroll(boolean scrollUp, double deltaSeconds) {
        }

        @Override
        public boolean claimsPointer() {
            return claiming;
        }

        @Override
        public void onPress(float x, float y) {
            events.add(PRESS);
        }

        @Override
        public void onDrag(float x, float y) {
            events.add(DRAG);
            lastX = x;
        }

        @Override
        public void onRelease(float x, float y, boolean click) {
            events.add(click ? RELEASE_AS_CLICK : RELEASE_AFTER_DRAG);
        }
    }
}
