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
import ixdar.platform.Platform;
import ixdar.platform.input.OrbitMouseTrap;
import ixdar.platform.input.PointerDispatcher;
import ixdar.platform.input.PointerRegion;
import ixdar.scenes.Scene;
import ixdar.tools.ControlHint;
import ixdar.tools.Tool;

/**
 * Through the {@link PointerDispatcher}, a region claiming the pointer gets the whole press; else
 * the scene's active tool is offered it, and the orbit gets what the tool does not take.
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

    private final RecordingTool tool = new RecordingTool();

    private OrbitMouseTrap orbit;

    private PointerDispatcher pointer;

    /**
     * Install a platform whose window is its framebuffer, a GL reporting {@link #leftHeld}, a
     * right-hand region, and an orbit driving a scene whose active tool records what it gets.
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
        Scene host = new Scene() {
        };
        host.activeTool = tool;
        orbit = new OrbitMouseTrap(new Camera3D(new Vector3f(), 0f, 0f, null), host);
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
        assertEquals(List.of(), tool.events, "the tool saw none of it");
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
        assertEquals(List.of(), tool.events);
    }

    @Test
    void outsideTheRegionTheToolGetsTheClickAndTheOrbitTheDrag() {
        click(OUTSIDE_X);
        assertEquals(List.of(PRESS, RELEASE_AS_CLICK), tool.events,
                "the tool was offered the press and got the click");
        float azimuth = orbit.getAzimuth();
        pressDragRelease(OUTSIDE_X);
        assertNotEquals(azimuth, orbit.getAzimuth(), "the drag turned the orbit");
        assertEquals(List.of(PRESS, RELEASE_AS_CLICK, PRESS, RELEASE_AFTER_DRAG), tool.events,
                "the tool heard the release of the orbit's drag, but none of its moves");
        assertEquals(List.of(), region.events);
    }

    @Test
    void aRegionThatDoesNotClaimLeavesThePressToTheOrbitAndTool() {
        region.claiming = false;
        click(INSIDE_X);
        float azimuth = orbit.getAzimuth();
        pressDragRelease(INSIDE_X);
        assertEquals(List.of(PRESS, RELEASE_AS_CLICK, PRESS, RELEASE_AFTER_DRAG), tool.events);
        assertNotEquals(azimuth, orbit.getAzimuth());
        assertEquals(List.of(), region.events);
    }

    @Test
    void aPressTheToolTakesDragsTheToolAndLeavesTheOrbitStill() {
        tool.taking = true;
        float azimuth = orbit.getAzimuth();
        pressDragRelease(OUTSIDE_X);
        assertEquals(List.of(PRESS, DRAG, DRAG, RELEASE_AFTER_DRAG), tool.events);
        assertEquals(azimuth, orbit.getAzimuth(), EXACT, "the tool's drag did not turn the orbit");
        assertTrue(!orbit.isCameraDragging(), "the camera never held the press");
        click(OUTSIDE_X);
        assertEquals(RELEASE_AS_CLICK, tool.events.get(tool.events.size() - 1),
                "a press the tool took and did not move is a click");
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

    /**
     * The scene's active tool, taking the presses it is offered while {@link #taking} holds, and
     * recording every pointer callback it gets.
     */
    private static final class RecordingTool implements Tool {

        public final List<String> events = new ArrayList<>();

        public boolean taking;

        @Override
        public String toolName() {
            return "recording";
        }

        @Override
        public void activate() {
        }

        @Override
        public void deactivate() {
        }

        @Override
        public void perFrame() {
        }

        @Override
        public void addControls(List<ControlHint> controls) {
        }

        @Override
        public boolean onPress(float x, float y, int mods) {
            events.add(PRESS);
            return taking;
        }

        @Override
        public void onDrag(float x, float y) {
            events.add(DRAG);
        }

        @Override
        public boolean onRelease(float x, float y, boolean click) {
            events.add(click ? RELEASE_AS_CLICK : RELEASE_AFTER_DRAG);
            return click;
        }
    }
}
