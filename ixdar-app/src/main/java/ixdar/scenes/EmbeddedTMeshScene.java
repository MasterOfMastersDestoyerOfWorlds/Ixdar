package ixdar.scenes;

import org.joml.Vector3f;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.quadlayout.embedding.TorusLayoutFixture;
import ixdar.graphics.render.model.QuadLayoutRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.gl.Platform;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.MouseTrap;
import ixdar.platform.input.OrbitCameraKeyGuy;
import ixdar.platform.input.OrbitMouseTrap;

/**
 * Debug view of an embedded T-mesh, drawn on its mesh the way LCBK19 Figure 9 draws one:
 * the surface underneath, each arc as the jagged edge path it snapped onto (positive arcs
 * orange, zero arcs red so the collapse targets are obvious), and each node as a sphere
 * (critical nodes tinted apart, since the operators may never move those).
 *
 * <p>It renders the hand-authored {@link TorusLayoutFixture} — the same Figure-9
 * configuration the operators are tested against — so that when the collapse operators are
 * added, their effect on the T-mesh can be watched rather than only asserted. The torus is
 * closed and genus 1, which is what lets the fixture stand in for Figure 9 without any of
 * the free-boundary machinery.
 *
 * <p>Launch with {@code IxdarWindow embedded-tmesh}.
 */
@SceneAnnotation(id = "embedded-tmesh")
public class EmbeddedTMeshScene extends Scene {

    /** Window title. */
    public static final String SCENE_TITLE = "Ixdar : Embedded T-Mesh";

    /** Orbit azimuth the camera starts at, looking down onto the torus. */
    public static final float CAMERA_AZIMUTH = (float) Math.toRadians(35.0);

    /** Orbit elevation the camera starts at. */
    public static final float CAMERA_ELEVATION = (float) Math.toRadians(35.0);

    /** Nearest the camera may zoom, as a floor independent of mesh size. */
    public static final float CAMERA_DISTANCE_MIN = 0.5f;

    /** Camera distance as a multiple of the mesh radius. */
    public static final float CAMERA_DISTANCE_RADIUS_MUL = 2.5f;

    /** Farthest the camera may zoom, as a multiple of the mesh radius. */
    public static final float ZOOM_MAX_RADIUS_MUL = 5.0f;

    /** Nearest zoom as a fraction of the mesh radius. */
    public static final float ZOOM_MIN_RADIUS_FRACTION = 0.02f;

    private OrbitMouseTrap orbitMouse;
    private QuadLayoutRuntime runtime;
    private TorusLayoutFixture fixture;
    private final Vector3f meshCenter = new Vector3f();

    /**
     * Default constructor wired by the scene annotation processor.
     */
    public EmbeddedTMeshScene() {
        super();
    }

    @Override
    public void initGL() {
        super.initGL();
        Platforms.gl().setWindowTitle(SCENE_TITLE);

        orbitMouse = new OrbitMouseTrap(camera, this);
        keys = new OrbitCameraKeyGuy(orbitMouse, camera, this);
        mouse = orbitMouse;
        bindAutomationIfAvailable(Platforms.get(), keys, mouse);
        bindInputDirect(Platforms.get(), keys, mouse);

        try {
            fixture = new TorusLayoutFixture();
            fixture.tmesh.validate(TorusLayoutFixture.TORUS_EULER_CHARACTERISTIC);

            runtime = new QuadLayoutRuntime();
            runtime.upload(fixture.torus);
            runtime.frameCamera(camera);
            runtime.setEmbeddedTMesh(fixture.tmesh);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize embedded T-mesh scene", ex);
        }

        meshCenter.set(fixture.torus.center(new Vector3f()));
        float meshRadius = fixture.torus.radius();
        float minZoom = Math.max(CAMERA_DISTANCE_MIN, meshRadius * ZOOM_MIN_RADIUS_FRACTION);
        float maxZoom = Math.max(CAMERA_DISTANCE_MIN, meshRadius * ZOOM_MAX_RADIUS_MUL);
        orbitMouse.setDistanceBounds(minZoom, maxZoom);
        float orbitDistance = Math.max(CAMERA_DISTANCE_MIN, meshRadius * CAMERA_DISTANCE_RADIUS_MUL);
        orbitMouse.setTarget(meshCenter);
        orbitMouse.setOrbit(CAMERA_AZIMUTH, CAMERA_ELEVATION, orbitDistance);

        Platforms.get().log(String.format(
                "[embedded-tmesh] nodes=%d arcs=%d patches=%d",
                fixture.tmesh.nodes.size(), fixture.tmesh.arcs.size(),
                fixture.tmesh.patches.size()));
    }

    @Override
    public void drawScene() {
        if (runtime == null) {
            return;
        }
        camera.resetView();
        runtime.render(camera);
        runtime.renderOverlays(camera);
    }

    @Override
    public void activate(boolean state) {
        super.activate(state);
        if (!state) {
            disposeRuntime();
        }
    }

    @Override
    public void shutdown() {
        disposeRuntime();
        super.shutdown();
    }

    private void disposeRuntime() {
        if (runtime != null) {
            runtime.dispose();
            runtime = null;
        }
    }

    /**
     * Route raw platform input to the scene's key and mouse handlers.
     *
     * @param platform  active platform
     * @param keyGuy    key handler
     * @param mouseTrap mouse handler
     */
    private static void bindInputDirect(Platform platform, KeyGuy keyGuy, MouseTrap mouseTrap) {
        platform.setCursorPosCallback(
                (window, x, y) -> mouseTrap.moveOrDrag(window, (float) x, (float) y));
        platform.setMouseButtonCallback(
                (button, action, mods) -> mouseTrap.mouseButton(button, action, mods));
        platform.setScrollCallback((xoff, yoff) -> mouseTrap.scrollCallback(yoff));
        platform.setKeyCallback(
                (key, scancode, action, mods) -> keyGuy.keyCallback(0L, key, scancode, action, mods));
    }
}
