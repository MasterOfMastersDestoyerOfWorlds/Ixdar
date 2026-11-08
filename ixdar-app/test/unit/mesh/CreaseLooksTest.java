package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Proxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.paths.query.SurfaceCreases;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.graphics.render.model.MeshOverlayRuntime;
import ixdar.graphics.render.model.SurfaceLook;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.gl.lwjgl.DefaultBuffer;
import ixdar.scenes.ring.RingScene;

/**
 * The ring scene's crease looks on {@link RingRegionsTest}'s capped cylinder: both show the ring
 * tool's own crease lookup, measured once.
 */
class CreaseLooksTest {

    private static final int STAND_IN_PLATFORM_ID = 7719;

    private static final int WINDOW_SIZE = 64;

    private static final String GET_PLATFORM_ID = "getPlatformID";

    private static final String MEASURED_TWICE = "the look measured the grooves again";

    private Platform suitePlatform;

    private GL suiteGl;

    private MeshTopology cylinder;

    private RingScene scene;

    /** A ring scene over the cylinder whose overlay runtime runs on a stand-in GL. */
    @BeforeEach
    void installScene() {
        suitePlatform = Platforms.get();
        suiteGl = Platforms.gl();
        Platform window = (Platform) Proxy.newProxyInstance(Platform.class.getClassLoader(),
                new Class<?>[] {Platform.class}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "getWindowWidth":
                        case "getFrameBufferWidth":
                        case "getWindowHeight":
                        case "getFrameBufferHeight":
                            return WINDOW_SIZE;
                        case GET_PLATFORM_ID:
                            return STAND_IN_PLATFORM_ID;
                        case "allocateFloats":
                            return new DefaultBuffer((Integer) arguments[0]);
                        default:
                            return method.getReturnType() == int.class ? 0
                                    : method.getReturnType() == String.class ? "" : null;
                    }
                });
        GL standInGl = (GL) Proxy.newProxyInstance(GL.class.getClassLoader(),
                new Class<?>[] {GL.class}, (proxy, method, arguments) -> {
                    if (GET_PLATFORM_ID.equals(method.getName())) {
                        return STAND_IN_PLATFORM_ID;
                    }
                    Class<?> type = method.getReturnType();
                    return type == int.class ? 1 : type == boolean.class ? false
                            : type == float.class ? 0f : type == String.class ? "" : null;
                });
        Platforms.init(window, standInGl);
        cylinder = RingRegionsTest.cappedCylinder();
        scene = new RingScene() {
            @Override
            public MeshTopology getMesh() {
                return cylinder;
            }

            @Override
            public MeshTopology halfEdgeSurface() {
                return cylinder;
            }
        };
        scene.initPoints();
        scene.setTools();
        scene.switchTool(scene.defaultTool);
        scene.meshRuntime = new MeshOverlayRuntime();
        scene.meshRuntime.upload(cylinder);
        scene.refreshControls();
    }

    /** Put the suite's platform back. */
    @AfterEach
    void restoreSuitePlatform() {
        Platforms.init(suitePlatform, suiteGl);
    }

    @Test
    void theCreaseLookShowsTheRingToolsOwnValleyStrength() {
        SurfaceCreases shared = SurfaceCreases.of(cylinder);
        scene.ringTool.creases = shared;
        SurfaceLook look = scene.looks.named(RingScene.CREASES_LOOK);
        assertEquals(SurfaceLook.Colour.FIELD, look.colour, "the crease look is no field look");
        float[] activeOrdered = new float[cylinder.vertexCount()];
        for (int index = 0; index < activeOrdered.length; index++) {
            activeOrdered[index] = shared.valleyStrength[cylinder.vertexIdAt(index)];
        }
        assertArrayEquals(activeOrdered, look.field.values(),
                "the look's field is not the lookup's valley strength in vertex order");
        assertSame(shared, scene.ringTool.creases, MEASURED_TWICE);
    }

    /** The along look is the lookup's along curvature over its saturation, on a fixed -1..1 ramp. */
    @Test
    void theAlongLookShowsTheLookupsBendAlongTheGroove() {
        SurfaceCreases shared = SurfaceCreases.of(cylinder);
        scene.ringTool.creases = shared;
        SurfaceLook look = scene.looks.named(RingScene.ALONG_LOOK);
        assertEquals(-1f, look.fieldMin, "the along ramp does not start at -1");
        assertEquals(1f, look.fieldMax, "the along ramp does not end at 1");
        float[] activeOrdered = new float[cylinder.vertexCount()];
        for (int index = 0; index < activeOrdered.length; index++) {
            activeOrdered[index] = (float) (shared.alongCurvature[cylinder.vertexIdAt(index)]
                    / shared.saturation);
        }
        assertArrayEquals(activeOrdered, look.field.values(),
                "the look's field is not the lookup's along curvature over its saturation");
        assertSame(shared, scene.ringTool.creases, MEASURED_TWICE);
    }
}
