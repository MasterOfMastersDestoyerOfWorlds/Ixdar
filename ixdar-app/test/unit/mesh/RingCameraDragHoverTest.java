package unit.mesh;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;
import static ixdar.platform.input.Keys.MOUSE_BUTTON_LEFT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;

import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.AuthoredSplineRing;
import ixdar.geometry.mesh.data.paths.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.SurfaceSplineTracer;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.cameras.Camera3D;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.gl.Platform;
import ixdar.platform.input.MouseTrap;
import ixdar.platform.input.OrbitMouseTrap;
import ixdar.platform.input.PointerDispatcher;
import ixdar.platform.input.Scene2DMousePanTrap;
import ixdar.scenes.ring.RingScene;
import ixdar.scenes.ring.RingTool;

/**
 * The ring tool's hover pauses while the input layer moves the camera: a drag held still or moving
 * until its release, and a wheel zoom until it settles; an unmoved press and an anchor drag never
 * pause it, and a 2D trap's drag reports the same drag state.
 */
class RingCameraDragHoverTest {

    private static final int STAND_IN_PLATFORM_ID = 7717;

    private static final int WINDOW_WIDTH = 800;

    private static final int WINDOW_HEIGHT = 600;

    private static final float START_X = 400f;

    private static final float MIDDLE_Y = 300f;

    private static final float DRAG_STEP = 60f;

    private static final float CLICK_WOBBLE = 2f;

    private static final int STILL_FRAMES = 30;

    private static final int WHEEL_BURST = 12;

    private static final double TRACKPAD_DELTA = 0.25;

    private static final float SPEED = 1f;

    private static final float EXACT = 0f;

    private static final int XYZ = 3;

    private static final float RADIUS = 0.4f;

    private static final int QUARTERS = 4;

    private static final float[] TUBE_AXIS = { 0.998f, 0.05f, 0.03f };

    private static final String GET_PLATFORM_ID = "getPlatformID";

    private Platform suitePlatform;

    private GL suiteGl;

    private boolean leftHeld;

    private RingScene scene;

    private RingTool tool;

    private OrbitMouseTrap orbit;

    private PointerDispatcher pointer;

    /**
     * A ring scene over a round tube with the ring tool active and the orbit trap as its mouse,
     * on a platform whose window is its framebuffer and whose GL reports {@link #leftHeld}.
     */
    @BeforeEach
    void installSceneAndPlatform() {
        MeshTopology tube = RingAnchorOrderTest.tube(0f, RADIUS);
        scene = new RingScene() {
            @Override
            public MeshTopology getMesh() {
                return tube;
            }

            @Override
            public MeshTopology halfEdgeSurface() {
                return tube;
            }
        };
        tool = scene.ringTool;
        tool.geodesics = SurfaceGeodesics.over(tube);
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
        orbit = new OrbitMouseTrap(new Camera3D(new Vector3f(), 0f, 0f, null), null);
        scene.orbitMouse = orbit;
        scene.mouse = orbit;
        scene.switchTool(tool);
    }

    /**
     * Put the suite's platform back.
     */
    @AfterEach
    void restoreSuitePlatform() {
        Platforms.init(suitePlatform, suiteGl);
    }

    @Test
    void aCameraDragHeldStillPausesTheHoverUntilItsRelease() {
        pointer.moveOrDrag(orbit, 0L, START_X, MIDDLE_Y);
        frame();
        assertFalse(scene.cameraMoving(), "the cursor rests with no button held");
        press(orbit);
        frame();
        assertFalse(scene.cameraMoving(), "a press that has not moved may still be a click");
        pointer.moveOrDrag(orbit, 0L, START_X + CLICK_WOBBLE, MIDDLE_Y);
        frame();
        assertFalse(scene.cameraMoving(), "a wobble within the click threshold is no drag");
        float azimuth = orbit.getAzimuth();
        pointer.moveOrDrag(orbit, 0L, START_X - DRAG_STEP, MIDDLE_Y);
        frame();
        assertNotEquals(azimuth, orbit.getAzimuth(), "the drag turned the orbit");
        assertTrue(scene.cameraMoving(), "the camera drags");
        for (int still = 0; still < STILL_FRAMES; still++) {
            frame();
            assertTrue(scene.cameraMoving(), "the button is held, still frame " + still);
        }
        pointer.moveOrDrag(orbit, 0L, START_X, MIDDLE_Y);
        frame();
        assertTrue(scene.cameraMoving(), "back over the press point, the drag is still held");
        release(orbit);
        frame();
        assertFalse(scene.cameraMoving(), "the release brings the hover back");
    }

    @Test
    void aBurstOfWheelEventsPausesTheHoverUntilTheZoomSettles() {
        pointer.moveOrDrag(orbit, 0L, START_X, MIDDLE_Y);
        frame();
        assertFalse(scene.cameraMoving(), "the cursor rests");
        float distance = orbit.getDistance();
        for (int burst = 0; burst < WHEEL_BURST; burst++) {
            if (burst > 0) {
                frame();
                assertTrue(scene.cameraMoving(), "a frame between wheel events, burst " + burst);
            }
            orbit.scrollCallback(TRACKPAD_DELTA);
            orbit.scrollCallback(TRACKPAD_DELTA);
            frame();
            assertTrue(scene.cameraMoving(), "the wheel zooms, burst frame " + burst);
        }
        assertNotEquals(distance, orbit.getDistance(), "the wheel zoomed");
        for (int quiet = 1; quiet < MouseTrap.ZOOM_SETTLE_FRAMES; quiet++) {
            frame();
            assertTrue(scene.cameraMoving(), "the zoom settles, quiet frame " + quiet);
        }
        frame();
        assertFalse(scene.cameraMoving(), "the hover is back once the zoom has settled");
    }

    @Test
    void aDragOnATwoDimensionalTrapReportsADragUntilItsRelease() {
        Camera2D flat = new Camera2D(WINDOW_WIDTH, WINDOW_HEIGHT, 1f, 0, 0, null);
        Scene2DMousePanTrap pan = new Scene2DMousePanTrap(flat, scene);
        pointer.moveOrDrag(pan, 0L, START_X, MIDDLE_Y);
        press(pan);
        pointer.moveOrDrag(pan, 0L, START_X + CLICK_WOBBLE, MIDDLE_Y);
        assertFalse(pan.isCameraDragging(), "a press within the click threshold is no drag");
        float panX = flat.PanX;
        pointer.moveOrDrag(pan, 0L, START_X + DRAG_STEP, MIDDLE_Y);
        assertNotEquals(panX, flat.PanX, "the drag panned the view");
        assertTrue(pan.isCameraDragging(), "the 2D trap reports its drag");
        pointer.paintUpdate(pan, SPEED);
        assertTrue(pan.isCameraDragging(), "held still, the drag goes on");
        release(pan);
        assertFalse(pan.isCameraDragging(), "the release ends the drag");
    }

    @Test
    void anAnchorDragIsTheToolsAndNeverPausesTheHover() {
        // A draft ring around the tube through four authored anchors, the cursor on the first as
        // the hover pick leaves it, so the next press grabs that anchor.
        MeshTopology tube = scene.halfEdgeSurface();
        float[] xyz = new float[XYZ * QUARTERS];
        for (int quarter = 0; quarter < QUARTERS; quarter++) {
            double angle = 2.0 * Math.PI * quarter / QUARTERS;
            xyz[XYZ * quarter + 1] = (float) (RADIUS * Math.cos(angle));
            xyz[XYZ * quarter + 2] = (float) (RADIUS * Math.sin(angle));
        }
        int[] quarters = SurfaceWaypoints.snap(tube, xyz, QUARTERS);
        AuthoredSplineRing ring = new AuthoredSplineRing(tool.geodesics);
        assertTrue(ring.trace(quarters, QUARTERS, TUBE_AXIS,
                SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH), ring.failure);
        System.arraycopy(TUBE_AXIS, 0, tool.draftBaseNormal, 0, XYZ);
        tool.draftAuthoredVertexId = ring.authoredVertexId;
        tool.draftDepth = SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH;
        tool.draft = SurfaceSpline.of(ring.tracer);
        tool.hoveredAnchor = 0;
        pointer.moveOrDrag(orbit, 0L, START_X, MIDDLE_Y);
        press(orbit);
        assertTrue(tool.draggingAnchor, "the tool grabbed the press");
        float azimuth = orbit.getAzimuth();
        for (int step = 1; step <= 2; step++) {
            pointer.moveOrDrag(orbit, 0L, START_X - step * DRAG_STEP, MIDDLE_Y);
            frame();
            assertFalse(scene.cameraMoving(), "an anchor drag keeps picking, step " + step);
        }
        for (int still = 0; still < STILL_FRAMES; still++) {
            frame();
            assertFalse(scene.cameraMoving(), "an anchor held still keeps picking");
        }
        assertEquals(azimuth, orbit.getAzimuth(), EXACT, "the anchor drag did not turn the orbit");
        release(orbit);
        assertFalse(tool.draggingAnchor, "the release ended the anchor drag");
        assertFalse(scene.cameraMoving());
    }

    private void press(MouseTrap trap) {
        pointer.mouseButton(trap, MOUSE_BUTTON_LEFT, ACTION_PRESS, 0);
        leftHeld = true;
    }

    private void release(MouseTrap trap) {
        leftHeld = false;
        pointer.mouseButton(trap, MOUSE_BUTTON_LEFT, ACTION_RELEASE, 0);
    }

    private void frame() {
        pointer.paintUpdate(orbit, SPEED);
    }
}
