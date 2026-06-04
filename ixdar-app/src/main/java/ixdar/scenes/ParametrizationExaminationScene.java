package ixdar.scenes;

import org.joml.Vector3f;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.data.load.CrossFieldLoader;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.crossfield.NDirectionField;
import ixdar.geometry.mesh.quadlayout.seamless.ParameterizationMetrics;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;
import ixdar.graphics.render.model.QuadLayoutRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.gl.Platform;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.MouseTrap;
import ixdar.platform.input.OrbitCameraKeyGuy;
import ixdar.platform.input.OrbitMouseTrap;

/**
 * 3D scene for inspecting a seamless parametrization on a triangle mesh. Loads
 * the OFF specified by {@code -Dparametrization.scene.off=<path>} (default
 * {@link #DEFAULT_OFF}), runs the cross-field + seamless pipeline, and renders
 * the result as cyan u-iso-lines and yellow v-iso-lines drawn across the mesh
 * surface plus coloured spheres at the singularity vertices — the BZK09 figure
 * 1(c) visualisation style.
 */
@SceneAnnotation(id = "param-exam")
public class ParametrizationExaminationScene extends Scene {

    public static final String DEFAULT_OFF = "test/resources/quadlayout/figure_10/fandisk_in_tri.off";
    public static final String OFF_PROPERTY = "parametrization.scene.off";
    /**
     * Optional system property: path to an .ndf reference cross field. When set,
     * {@link #initGL} still runs {@link CrossField#build()} to derive the per-face
     * local frames and active-index maps, then overwrites {@code theta},
     * {@code periodJump}, {@code singularityIndexQuarter} and {@code singularities}
     * with the NDF values. Lets the same seamless pipeline run on the paper's
     * reference field so you can compare flipped-triangle counts and visual output
     * side by side against our own solver.
     */
    public static final String CROSS_FIELD_PROPERTY = "parametrization.scene.cf";
    public static final String SCENE_TITLE = "Ixdar : Parametrization Examination";
    public static final float CAMERA_AZIMUTH = (float) Math.toRadians(45.0);
    public static final float CAMERA_ELEVATION = (float) Math.toRadians(24.0);
    public static final float CAMERA_DISTANCE_MIN = 1.5f;
    public static final float CAMERA_DISTANCE_RADIUS_MUL = 2.5f;
    public static final float CAMERA_DISTANCE_DEFAULT = 3.5f;
    /**
     * Closest zoom: 1% of mesh radius — practically lets you sit on the surface.
     */
    public static final float ZOOM_MIN_RADIUS_FRACTION = 0.01f;
    /** Farthest zoom: 5× mesh radius. */
    public static final float ZOOM_MAX_RADIUS_MUL = 5.0f;
    /**
     * Floor on the closest-zoom value so we never collapse to zero on degenerate
     * meshes.
     */
    public static final float ZOOM_MIN_FLOOR = 0.0001f;

    private OrbitMouseTrap orbitMouse;
    private QuadLayoutRuntime runtime;
    private final Vector3f meshCenter = new Vector3f();

    /** Default constructor wired by the scene annotation processor. */
    public ParametrizationExaminationScene() {
        super();
    }

    /**
     * Load the OFF, build the cross field and seamless parametrization, and hand
     * them to the runtime. Frame the orbit camera on the mesh. All work runs
     * synchronously here — meshes large enough to hitch the GL thread are
     * acceptable for an inspector use-case.
     *
     * @throws IllegalStateException if the mesh or parametrization fail to build
     */
    @Override
    public void initGL() {
        super.initGL();
        Platforms.gl().setWindowTitle(SCENE_TITLE);

        orbitMouse = new OrbitMouseTrap(camera, this);
        keys = new OrbitCameraKeyGuy(orbitMouse, camera, this);
        orbitMouse.setTarget(meshCenter);
        orbitMouse.setOrbit(CAMERA_AZIMUTH, CAMERA_ELEVATION, CAMERA_DISTANCE_DEFAULT);
        mouse = orbitMouse;
        bindAutomationIfAvailable(Platforms.get(), keys, mouse);
        bindInputDirect(Platforms.get(), keys, mouse);

        String offPath = System.getProperty(OFF_PROPERTY, DEFAULT_OFF);
        String cfPath = System.getProperty(CROSS_FIELD_PROPERTY, null);
        try {
            ArrayMesh arrayMesh = MeshLoader.load(offPath);
            HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                    arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());
            CrossField crossField = new NDirectionField(mesh).build();
            int ourSingCount = crossField.singularities.size();
            if (cfPath != null) {
                CrossField reference = CrossFieldLoader.load(cfPath, mesh);
                CrossFieldLoader.alignPeriodJumpsToFrame(reference, crossField);
                CrossFieldLoader.convertBceak13ThetaToQuadAxes(reference);
                crossField.theta = reference.theta;
                crossField.periodJump = reference.periodJump;
                crossField.singularities.clear();
                crossField.singularities.addAll(reference.singularities);
                Platforms.get().log("[param-exam] using reference cross field from "
                        + cfPath + " (our solver produced " + ourSingCount
                        + " singularities, reference has " + reference.singularities.size() + ")");
            }
            SeamlessParameterization seamless = new SeamlessParameterization(crossField);
            ParameterizationMetrics metrics = seamless.build();

            try {
                runtime = new QuadLayoutRuntime();
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to create QuadLayoutRuntime", ex);
            }
            runtime.upload(mesh);
            runtime.showIsoLines = true;
            runtime.showSingularities = true;
            runtime.setSeamlessParametrization(seamless);
            runtime.frameCamera(camera);

            meshCenter.set(mesh.center(new Vector3f()));
            float meshRadius = mesh.radius();
            float minZoom = Math.max(ZOOM_MIN_FLOOR, meshRadius * ZOOM_MIN_RADIUS_FRACTION);
            float maxZoom = Math.max(CAMERA_DISTANCE_MIN, meshRadius * ZOOM_MAX_RADIUS_MUL);
            orbitMouse.setDistanceBounds(minZoom, maxZoom);
            float orbitDist = Math.max(CAMERA_DISTANCE_MIN, meshRadius * CAMERA_DISTANCE_RADIUS_MUL);
            orbitMouse.setTarget(meshCenter);
            orbitMouse.setOrbit(CAMERA_AZIMUTH, CAMERA_ELEVATION, orbitDist);

            Platforms.get().log("[param-exam] " + offPath
                    + (cfPath == null ? "" : " cf=" + cfPath)
                    + " V=" + mesh.vertexCount()
                    + " F=" + mesh.faceCount()
                    + " singularities=" + crossField.singularities.size()
                    + " flipped=" + metrics.flippedTriangleCount
                    + " injective=" + seamless.injective);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to initialize parametrization scene from " + offPath, ex);
        }
    }

    /**
     * Render the iso-line surface on top of the base mesh. The iso-surface already
     * covers the mesh's geometry (it is the same triangle layout, in a
     * triangle-soup form), so we do not also draw the underlying mesh — doing so
     * would z-fight and obscure the iso-lines.
     */
    @Override
    public void drawScene() {
        if (runtime == null) {
            return;
        }
        camera.resetView();
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
     * Desktop input binding that wires platform callbacks to our handlers — same
     * fallback the cross-field examiner uses on desktop. The reflection-based
     * {@code AutomationInputBinder} path is preferred on web/TeaVM; this fires on
     * desktop.
     *
     * @param platform  current platform
     * @param keys      key handler
     * @param mouseTrap mouse handler
     */
    private static void bindInputDirect(Platform platform, KeyGuy keys,
            MouseTrap mouseTrap) {
        platform.setCursorPosCallback((window, x, y) -> {
            mouseTrap.moveOrDrag(window, (float) x, (float) y);
        });
        platform.setMouseButtonCallback((button, action, mods) -> {
            mouseTrap.mouseButton(button, action, mods);
        });
        platform.setScrollCallback((xoff, yoff) -> {
            mouseTrap.scrollCallback(yoff);
        });
        platform.setKeyCallback((key, scancode, action, mods) -> {
            keys.keyCallback(0L, key, scancode, action, mods);
        });
    }

}
