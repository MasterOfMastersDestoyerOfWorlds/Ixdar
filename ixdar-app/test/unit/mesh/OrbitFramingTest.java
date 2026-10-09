package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.graphics.cameras.Camera3D;
import ixdar.platform.automation.endpoints.ui.Frame;
import ixdar.platform.automation.endpoints.ui.SelectionBounds;
import ixdar.platform.input.OrbitMouseTrap;

/**
 * The orbit camera's framing: a framed sphere fills the narrower view angle on any aspect and at
 * any elevation, elevation runs through both poles with a continuous up vector, and the point,
 * box and ring selections resolve to the sphere the camera frames.
 */
class OrbitFramingTest {
    private static final float WIDE_ASPECT = 16f / 9f;
    private static final float TALL_ASPECT = 9f / 16f;
    private static final float THREE_QUARTER_ELEVATION = 0.6f;
    private static final float HALF_PI = (float) (Math.PI / 2.0);
    private static final float NEAR_PLANE = 1e-3f;
    private static final float FAR_PLANE = 1e3f;
    private static final int SPHERE_SAMPLES = 4000;
    private static final float GOLDEN_ANGLE = (float) (Math.PI * (3.0 - Math.sqrt(5.0)));
    private static final float NDC_TOLERANCE = 1e-3f;
    private static final float TOUCH_FLOOR = 0.99f;
    private static final float ANGLE_TOLERANCE = 1e-5f;
    private static final float POSITION_TOLERANCE = 1e-4f;
    private static final float POLE_SWEEP_START = 1.3f;
    private static final float POLE_SWEEP_END = 1.85f;
    private static final float POLE_SWEEP_STEP = 0.01f;
    private static final float CONTINUOUS_UP_DOT = 0.999f;
    private static final float ORBIT_DISTANCE = 3f;
    private static final float OVER_THE_POLE = 0.2f;
    private static final float TUBE_RADIUS = 0.4f;
    private static final float TUBE_SAMPLING_TOLERANCE = 0.02f;

    private Camera3D camera;

    private OrbitMouseTrap orbit;

    /**
     * A bare orbit camera, no scene or platform behind it.
     */
    @BeforeEach
    void buildOrbit() {
        camera = new Camera3D(new Vector3f(), 0f, 0f, null);
        orbit = new OrbitMouseTrap(camera, null);
    }

    @Test
    void aFramedSphereTouchesTheNarrowerEdgeOfAWideView() {
        assertFramedSphereFits(new Vector3f(0.3f, -1.2f, 2f), 0.05f, 0.25f, WIDE_ASPECT,
                1.1f, THREE_QUARTER_ELEVATION);
    }

    @Test
    void aFramedSphereTouchesTheNarrowerEdgeOfATallView() {
        assertFramedSphereFits(new Vector3f(-4f, 0f, 1f), 2.5f, 0.15f, TALL_ASPECT, 0f, 0f);
    }

    @Test
    void aSphereFramedFromAboveFitsTheTopView() {
        assertFramedSphereFits(new Vector3f(1f, 1f, 1f), 0.4f, 0.1f, WIDE_ASPECT, 0.7f, HALF_PI);
    }

    @Test
    void framingWidensTheZoomBoundsToReachATinySphere() {
        float distance = orbit.frame(new Vector3f(), 1e-3f, 0f, 1f);
        assertTrue(distance < OrbitMouseTrap.DEFAULT_MIN_DISTANCE, "fit distance " + distance);
        assertEquals(distance, orbit.getDistance(), POSITION_TOLERANCE);
    }

    @Test
    void theTopAndBottomViewsLookStraightAlongTheVerticalAxis() {
        Vector3f target = new Vector3f(0.5f, 2f, -1f);
        orbit.moveTarget(target);
        for (float elevation : new float[] { HALF_PI, -HALF_PI }) {
            orbit.setOrbit(1f, elevation, ORBIT_DISTANCE);
            assertEquals(elevation, orbit.getElevation(), ANGLE_TOLERANCE, "elevation is not clamped");
            Vector3f offset = new Vector3f(camera.position).sub(target);
            assertEquals(0f, offset.x, POSITION_TOLERANCE);
            assertEquals(Math.signum(elevation) * ORBIT_DISTANCE, offset.y, POSITION_TOLERANCE);
            assertEquals(0f, offset.z, POSITION_TOLERANCE);
            assertEquals(0f, camera.up.dot(offset.normalize()), POSITION_TOLERANCE,
                    "up stays perpendicular to the view at the pole");
            assertEquals(1f, camera.up.length(), POSITION_TOLERANCE);
            assertTrue(camera.view.isFinite(), "the view matrix is finite at the pole");
        }
    }

    @Test
    void theUpVectorTurnsSmoothlyAsTheCameraCrossesThePole() {
        orbit.setOrbit(0.4f, POLE_SWEEP_START, ORBIT_DISTANCE);
        Vector3f previousUp = new Vector3f(camera.up);
        Vector3f previousPosition = new Vector3f(camera.position);
        for (float elevation = POLE_SWEEP_START + POLE_SWEEP_STEP; elevation <= POLE_SWEEP_END;
                elevation += POLE_SWEEP_STEP) {
            orbit.setOrbit(0.4f, elevation, ORBIT_DISTANCE);
            assertTrue(previousUp.dot(camera.up) > CONTINUOUS_UP_DOT,
                    "up flips at elevation " + elevation);
            assertTrue(previousPosition.distance(camera.position)
                    < ORBIT_DISTANCE * POLE_SWEEP_STEP * 2f, "camera jumps at elevation " + elevation);
            previousUp.set(camera.up);
            previousPosition.set(camera.position);
        }
    }

    @Test
    void theEquatorKeepsWorldUpAndElevationWrapsPastThePole() {
        orbit.setOrbit(2f, 0f, ORBIT_DISTANCE);
        assertEquals(1f, camera.up.y, POSITION_TOLERANCE);
        orbit.setOrbit(0f, HALF_PI + OVER_THE_POLE, ORBIT_DISTANCE);
        assertEquals(HALF_PI + OVER_THE_POLE, orbit.getElevation(), ANGLE_TOLERANCE);
        assertTrue(camera.position.x < 0f, "past the pole the camera comes down the far side");
        orbit.setOrbit(0f, (float) (2.0 * Math.PI) + OVER_THE_POLE, ORBIT_DISTANCE);
        assertEquals(OVER_THE_POLE, orbit.getElevation(), ANGLE_TOLERANCE);
    }

    @Test
    void aPointAndRadiusFramesThatSphere() {
        JsonObject request = new JsonObject();
        request.addProperty(Frame.POINT, "1, 2, 3");
        request.addProperty(Frame.RADIUS, "0.5");
        SelectionBounds bounds = new SelectionBounds();
        assertTrue(bounds.resolve(null, Frame.selectionExpression(request)), bounds.error);
        Vector3f center = new Vector3f();
        assertEquals(0.5f, bounds.sphere(center), POSITION_TOLERANCE);
        assertEquals(0f, center.distance(1f, 2f, 3f), POSITION_TOLERANCE);
    }

    @Test
    void aPointWithoutARadiusIsRefused() {
        JsonObject request = new JsonObject();
        request.addProperty(Frame.POINT, "1,2,3");
        assertNull(Frame.selectionExpression(request));
    }

    @Test
    void anExplicitBoxFramesItsCircumscribedSphere() {
        JsonObject request = new JsonObject();
        request.addProperty(Frame.BOUNDS, "-1,-2,-2,1,2,2");
        request.addProperty(Frame.REGION, "3");
        SelectionBounds bounds = new SelectionBounds();
        assertTrue(bounds.resolve(null, Frame.selectionExpression(request)), bounds.error);
        Vector3f center = new Vector3f();
        assertEquals(3f, bounds.sphere(center), POSITION_TOLERANCE, "bounds win over a region");
        assertEquals(0f, center.length(), POSITION_TOLERANCE);
    }

    @Test
    void aRingsMarkedEdgesBoundItsCrossSection() {
        MeshTopology tube = RingAnchorOrderTest.tube(0f, TUBE_RADIUS);
        int maximumEdgeId = 0;
        for (int index = 0; index < tube.edgeCount(); index++) {
            maximumEdgeId = Math.max(maximumEdgeId, tube.edgeIdAt(index));
        }
        boolean[] ring = new boolean[maximumEdgeId + 1];
        Vector3f start = new Vector3f();
        Vector3f end = new Vector3f();
        for (int index = 0; index < tube.edgeCount(); index++) {
            int edgeId = tube.edgeIdAt(index);
            int halfEdge = tube.edgeHalfEdge(edgeId);
            tube.vertexPosition(tube.halfEdgeVertex(halfEdge), start);
            tube.vertexPosition(tube.halfEdgeEndVertex(halfEdge), end);
            ring[edgeId] = Math.abs(start.x) < POSITION_TOLERANCE && Math.abs(end.x) < POSITION_TOLERANCE;
        }
        SelectionBounds bounds = new SelectionBounds();
        assertTrue(bounds.markedEdges(tube, ring));
        Vector3f center = new Vector3f();
        float radius = bounds.sphere(center);
        assertEquals(0f, center.length(), TUBE_SAMPLING_TOLERANCE);
        assertEquals(0f, bounds.maximum.x - bounds.minimum.x, POSITION_TOLERANCE);
        assertEquals(TUBE_RADIUS * (float) Math.sqrt(2.0), radius, TUBE_SAMPLING_TOLERANCE);
    }

    /**
     * Frame a sphere from the given angles, project the padded sphere through the camera's view and
     * a perspective with its field of view, and check it fills the narrower axis while staying
     * inside both.
     *
     * @param center    centre of the sphere
     * @param radius    radius of the sphere
     * @param padding   framing margin as a fraction of the radius
     * @param aspect    viewport width over height
     * @param azimuth   orbit azimuth to frame from
     * @param elevation orbit elevation to frame from
     */
    private void assertFramedSphereFits(Vector3f center, float radius, float padding, float aspect,
            float azimuth, float elevation) {
        orbit.setOrbit(azimuth, elevation, orbit.getDistance());
        orbit.frame(center, radius, padding, aspect);
        Matrix4f viewProjection = new Matrix4f()
                .perspective((float) Math.toRadians(camera.fov), aspect, NEAR_PLANE, FAR_PLANE)
                .mul(camera.view);
        float paddedRadius = radius * (1f + padding);
        float widestX = 0f;
        float widestY = 0f;
        Vector4f clip = new Vector4f();
        for (int sample = 0; sample < SPHERE_SAMPLES; sample++) {
            float height = 1f - 2f * (sample + 0.5f) / SPHERE_SAMPLES;
            float ring = (float) Math.sqrt(1f - height * height);
            float turn = GOLDEN_ANGLE * sample;
            clip.set(center.x + paddedRadius * ring * (float) Math.cos(turn),
                    center.y + paddedRadius * height,
                    center.z + paddedRadius * ring * (float) Math.sin(turn), 1f);
            viewProjection.transform(clip);
            assertTrue(clip.w > 0f, "the sphere is in front of the camera");
            widestX = Math.max(widestX, Math.abs(clip.x / clip.w));
            widestY = Math.max(widestY, Math.abs(clip.y / clip.w));
        }
        float narrow = aspect >= 1f ? widestY : widestX;
        float wide = aspect >= 1f ? widestX : widestY;
        assertTrue(narrow <= 1f + NDC_TOLERANCE, "the sphere overflows the narrow axis: " + narrow);
        assertTrue(narrow >= TOUCH_FLOOR, "the sphere does not fill the narrow axis: " + narrow);
        assertTrue(wide < 1f, "the sphere overflows the wide axis: " + wide);
        assertEquals(0f, new Vector3f(camera.target).distance(center), POSITION_TOLERANCE);
    }
}
