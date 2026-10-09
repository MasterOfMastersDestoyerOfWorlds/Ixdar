package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.SurfacePicker;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.gl.Platform;
import ixdar.platform.gl.headless.HeadlessBuffer;
import ixdar.scenes.ring.RingScene;

/**
 * TOOL-16: the pick and project routes' scene calls under a fixed camera over a round tube. A
 * picked pixel projects back to itself, a point on the far wall is reported hidden by the near
 * wall, a pixel beside the tube misses, and no tool or cursor state moves.
 */
class PixelPickProjectTest {

    private static final int STAND_IN_PLATFORM_ID = 7716;

    private static final int FRAMEBUFFER_WIDTH = 800;

    private static final int FRAMEBUFFER_HEIGHT = 600;

    private static final float RADIUS = 0.4f;

    private static final Vector3f EYE = new Vector3f(0.15f, 0.1f, 3f);

    private static final int GRID_STEP = 37;

    private static final float ROUND_TRIP_PIXELS = 1f;

    private static final float SAME_POINT = 1e-3f;

    private static final int MINIMUM_HITS = 40;

    private static final float ABOVE_THE_TUBE_Y = 20f;

    private static final String GET_PLATFORM_ID = "getPlatformID";

    private Platform suitePlatform;

    private GL suiteGl;

    private RingScene scene;

    /**
     * A ring scene over a round tube along x, drawn through a runtime with no GL behind it, its
     * camera fixed a little off the z axis looking at the origin.
     */
    @BeforeEach
    void installSceneAndCamera() {
        suitePlatform = Platforms.get();
        suiteGl = Platforms.gl();
        Platform window = (Platform) Proxy.newProxyInstance(Platform.class.getClassLoader(),
                new Class<?>[] {Platform.class}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "getWindowWidth":
                        case "getFrameBufferWidth":
                            return FRAMEBUFFER_WIDTH;
                        case "getWindowHeight":
                        case "getFrameBufferHeight":
                            return FRAMEBUFFER_HEIGHT;
                        case GET_PLATFORM_ID:
                            return STAND_IN_PLATFORM_ID;
                        case "allocateFloats":
                            return new HeadlessBuffer((int) arguments[0]);
                        default:
                            return standInDefault(method);
                    }
                });
        GL noGl = (GL) Proxy.newProxyInstance(GL.class.getClassLoader(),
                new Class<?>[] {GL.class}, (proxy, method, arguments) ->
                        GET_PLATFORM_ID.equals(method.getName()) ? STAND_IN_PLATFORM_ID
                                : standInDefault(method));
        Platforms.init(window, noGl);
        MeshTopology tube = RingAnchorOrderTest.tube(0f, RADIUS);
        // Named apart from the scene's inherited runtime field, which would shadow it inside.
        HalfEdgeMeshRuntime drawnRuntime = new HalfEdgeMeshRuntime();
        scene = new RingScene() {
            @Override
            public MeshTopology getMesh() {
                return tube;
            }

            @Override
            public MeshTopology halfEdgeSurface() {
                return tube;
            }

            @Override
            public HalfEdgeMeshRuntime surfaceRuntime() {
                return drawnRuntime;
            }
        };
        scene.camera.position.set(EYE);
        scene.camera.target.set(0f, 0f, 0f);
        scene.camera.view.setLookAt(EYE, scene.camera.target, new Vector3f(0f, 1f, 0f));
    }

    /** Hands the suite's platform back. */
    @AfterEach
    void restoreSuitePlatform() {
        Platforms.init(suitePlatform, suiteGl);
    }

    @Test
    void everyPickedPixelProjectsBackToItselfAndIsVisible() {
        SurfacePicker picker = new SurfacePicker();
        SurfacePicker occluder = new SurfacePicker();
        float[] pixel = new float[2];
        Vector3f corner = new Vector3f();
        int hits = 0;
        for (int pixelY = 0; pixelY < FRAMEBUFFER_HEIGHT; pixelY += GRID_STEP) {
            for (int pixelX = 0; pixelX < FRAMEBUFFER_WIDTH; pixelX += GRID_STEP) {
                if (!scene.pickPixel(pixelX, pixelY, picker)) {
                    continue;
                }
                hits++;
                assertTrue(scene.projectPoint(picker.pointX, picker.pointY, picker.pointZ, pixel,
                        occluder), "a picked point projected behind the camera");
                assertEquals(pixelX, pixel[0], ROUND_TRIP_PIXELS,
                        "pixel " + pixelX + "," + pixelY + " came back at x " + pixel[0]);
                assertEquals(pixelY, pixel[1], ROUND_TRIP_PIXELS,
                        "pixel " + pixelX + "," + pixelY + " came back at y " + pixel[1]);
                assertEquals(-1, occluder.faceId, "the picked point at " + pixelX + ","
                        + pixelY + " was reported hidden by face " + occluder.faceId);
                MeshTopology tube = scene.halfEdgeSurface();
                int vertexId = picker.nearestCornerVertexId(tube);
                boolean isCorner = false;
                double nearest = Double.POSITIVE_INFINITY;
                for (int slot = 0; slot < tube.faceVertexCount(picker.faceId); slot++) {
                    int cornerVertexId = tube.faceVertexAt(picker.faceId, slot);
                    isCorner |= cornerVertexId == vertexId;
                    nearest = Math.min(nearest, tube.vertexPosition(cornerVertexId, corner)
                            .distance(picker.pointX, picker.pointY, picker.pointZ));
                }
                assertTrue(isCorner, "the nearest vertex is not a corner of the picked face");
                assertEquals(nearest, tube.vertexPosition(vertexId, corner)
                        .distance(picker.pointX, picker.pointY, picker.pointZ), SAME_POINT,
                        "the reported vertex is not the face's nearest corner");
            }
        }
        assertTrue(hits >= MINIMUM_HITS, "only " + hits + " grid pixels landed on the tube");
    }

    @Test
    void aPointOnTheFarWallIsHiddenByTheNearWall() {
        SurfacePicker picker = new SurfacePicker();
        assertTrue(scene.pickPixel(FRAMEBUFFER_WIDTH / 2f, FRAMEBUFFER_HEIGHT / 2f, picker),
                "the middle of the view missed the tube");
        assertTrue(picker.pointZ > 0f, "the middle pixel landed on the far wall");
        float[] pixel = new float[2];
        SurfacePicker occluder = new SurfacePicker();
        assertTrue(scene.projectPoint(picker.pointX, picker.pointY, -picker.pointZ, pixel,
                occluder), "the far-wall point projected behind the camera");
        assertTrue(occluder.faceId >= 0, "the far-wall point was reported visible");
        assertTrue(occluder.pointZ > 0f, "the hiding hit is not on the near wall");
    }

    @Test
    void aPixelBesideTheTubeMissesAndNoToolStateMoves() {
        SurfacePicker picker = new SurfacePicker();
        assertFalse(scene.pickPixel(FRAMEBUFFER_WIDTH / 2f, ABOVE_THE_TUBE_Y, picker),
                "a pixel above the tube hit face " + picker.faceId);
        assertEquals(-1, picker.faceId, "a miss left a face behind");
        scene.pickPixel(FRAMEBUFFER_WIDTH / 2f, FRAMEBUFFER_HEIGHT / 2f, picker);
        scene.projectPoint(picker.pointX, picker.pointY, picker.pointZ, new float[2],
                new SurfacePicker());
        assertSame(scene.orbitTool, scene.activeTool, "picking switched the active tool");
        assertEquals(-1, scene.cursorFaceId, "picking moved the tools' cursor pick");
        assertEquals(0f, scene.cursorPoint[0], 0f, "picking moved the tools' cursor point");
    }

    /**
     * What a stand-in platform or GL answers for a call it does not script: zero, false or null.
     *
     * @param method the called method
     * @return the neutral value of its return type
     */
    private static Object standInDefault(Method method) {
        Class<?> type = method.getReturnType();
        if (type == int.class) {
            return 0;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == long.class) {
            return 0L;
        }
        return type == double.class ? 0.0 : null;
    }
}
