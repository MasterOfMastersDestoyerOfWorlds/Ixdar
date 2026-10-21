package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.graphics.cameras.Camera3D;

/**
 * The one 3D projection: the orthographic view is as tall as the perspective one at the orbit
 * target, so toggling it keeps the target plane where it was. Projecting points to pixels is
 * covered through the runtime by {@link PixelPickProjectTest}.
 */
class CameraProjectionTest {
    private static final float NEAR_PLANE = 0.05f;
    private static final float FAR_PLANE = 50f;
    private static final float NDC_TOLERANCE = 1e-5f;
    private static final float ORBIT_DISTANCE = 4f;
    private static final float AZIMUTH = 0.7f;
    private static final float ELEVATION = 0.3f;

    private Camera3D camera;

    /** An orbit camera looking at a point off the origin. */
    @BeforeEach
    void buildOrbit() {
        camera = new Camera3D(new Vector3f(), 0f, 0f);
        camera.setTarget(new Vector3f(0.5f, -0.2f, 1f));
        camera.setOrbit(AZIMUTH, ELEVATION, ORBIT_DISTANCE);
    }

    @Test
    void theOrthographicViewMatchesThePerspectiveViewOnTheTargetPlane() {
        Vector3f right = new Vector3f(camera.position).sub(camera.target).cross(camera.up).normalize();
        Vector3f onTargetPlane = new Vector3f(camera.target).add(new Vector3f(camera.up).mul(0.6f))
                .add(right.mul(-0.4f));
        Vector4f perspective = clip(false, onTargetPlane);
        Vector4f orthographic = clip(true, onTargetPlane);
        assertEquals(perspective.x / perspective.w, orthographic.x / orthographic.w, NDC_TOLERANCE);
        assertEquals(perspective.y / perspective.w, orthographic.y / orthographic.w, NDC_TOLERANCE);
    }

    @Test
    void updateProjectionRebuildsTheCameraMatrix() {
        assertSame(camera.projection, camera.updateProjection(true, NEAR_PLANE, FAR_PLANE));
    }

    private Vector4f clip(boolean orthographic, Vector3f world) {
        Vector4f point = new Vector4f(world, 1f);
        camera.view.transform(point);
        return camera.updateProjection(orthographic, NEAR_PLANE, FAR_PLANE).transform(point);
    }
}
