package ixdar.scenes;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.joml.Vector3f;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.data.load.CrossFieldLoader;

import ixdar.canvas.Canvas3D;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.crossfield.constraint.ConstraintSource;
import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.color.ColorRGB;
import ixdar.graphics.render.model.QuadLayoutRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.gl.Platform;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.Keys;
import ixdar.platform.input.OrbitCameraKeyGuy;
import ixdar.platform.input.OrbitMouseTrap;

import ixdar.platform.input.MouseTrap;

/**
 * 3D scene for inspecting a cross field on the triangle mesh named by
 * {@code -DcrossFieldScene.off=<path>}, drawing cross glyphs and singularity spheres.
 *
 * <p>{@code R} swaps in a reference NDF, which needs a sibling {@code *_in_cf.ndf} beside the
 * OFF; {@code C} toggles constraint glyphs, {@code X} the cross field.
 */
@SceneAnnotation(id = "cross-field-exam")
public class CrossFieldExaminationScene extends Scene {

    public static final String DEFAULT_OFF = "test/resources/quadlayout/figure_10/fandisk_in_tri.off";
    public static final String OFF_PROPERTY = "crossFieldScene.off";
    public static final String IN_TRI_OFF_SUFFIX = "_in_tri.off";
    public static final String IN_CF_NDF_SUFFIX = "_in_cf.ndf";
    public static final String SCENE_TITLE = "Ixdar : Cross Field Examination";
    public static final String LOG_DASH = " — ";
    public static final String LABEL_ON = "on";
    public static final String LABEL_OFF = "off";
    public static final float CAMERA_AZIMUTH = (float) Math.toRadians(45.0);
    public static final float CAMERA_ELEVATION = (float) Math.toRadians(24.0);
    public static final float CAMERA_DISTANCE_MIN = 1.5f;
    public static final float CAMERA_DISTANCE_RADIUS_MUL = 2.5f;
    public static final float CAMERA_DISTANCE_DEFAULT = 3.5f;

    public static final float CROSS_SCALE = 1f;
    /**
     * Closest zoom: 1% of mesh radius — practically lets you sit on the surface.
     */
    public static final float ZOOM_MIN_RADIUS_FRACTION = 0.01f;
    /**
     * Farthest zoom: 5× mesh radius. With FOV ≈ 45° (half-angle 22.5°, tan ≈
     * 0.414), the mesh diameter spans ≈ 2r / (5r·tan) ≈ 0.97 of the screen at this
     * distance — i.e. takes up roughly half the viewport (slightly less, leaves a
     * bit of margin).
     */
    public static final float ZOOM_MAX_RADIUS_MUL = 5.0f;
    /**
     * Floor on the closest-zoom value so we never collapse to zero on degenerate
     * meshes.
     */
    public static final float ZOOM_MIN_FLOOR = 0.0001f;

    private OrbitMouseTrap orbitMouse;
    private QuadLayoutRuntime runtime;
    private CrossField oursField;
    private CrossField referenceField;
    private boolean showingReference;
    private final Vector3f meshCenter = new Vector3f();

    /** Default constructor wired by the scene annotation processor. */
    public CrossFieldExaminationScene() {
        super();
    }

    /**
     * Wire orbit + key handlers, load the OFF, build the cross field, and (if a
     * sibling NDF exists) load it as the reference. All work runs synchronously
     * here — meshes large enough to hitch the GL thread are fine for the inspector
     * use-case.
     *
     * @throws IllegalStateException if mesh-runtime construction fails
     */
    @Override
    public void initGL() {
        super.initGL();
        Platforms.gl().setWindowTitle(SCENE_TITLE);

        orbitMouse = new OrbitMouseTrap(camera, this);
        keys = new ToggleKeyGuy(this, orbitMouse, camera, this);
        orbitMouse.setTarget(meshCenter);
        orbitMouse.setOrbit(CAMERA_AZIMUTH, CAMERA_ELEVATION, CAMERA_DISTANCE_DEFAULT);
        mouse = orbitMouse;
        bindAutomationIfAvailable(Platforms.get(), keys, mouse);
        bindInputDirect(Platforms.get(), keys, mouse);

        String offPath = System.getProperty(OFF_PROPERTY, DEFAULT_OFF);
        try {
            ArrayMesh am = MeshLoader.load(offPath);
            HalfEdgeMesh he = HalfEdgeMeshEngine.buildFromIndexedMesh(
                    am.copyPositions(), am.copyFaceIndices());
            oursField = new QuadLayoutEngine(he, QuadLayoutEngine.DEFAULT_ALPHA_RADIANS).buildCrossField();

            String ndfPath = inferNdfPath(offPath);
            if (ndfPath != null) {
                try {
                    referenceField = CrossFieldLoader.load(ndfPath, he);
                    referenceField.faceX = oursField.faceX;
                    referenceField.faceY = oursField.faceY;
                    referenceField.faceIdToActive = oursField.faceIdToActive;
                    referenceField.edgeIdToActive = oursField.edgeIdToActive;
                    referenceField.kappa = oursField.kappa;
                    CrossFieldLoader.alignPeriodJumpsToFrame(referenceField, oursField);
                    CrossFieldLoader.convertBceak13ThetaToQuadAxes(referenceField);
                    Platforms.get().log("[cross-field-exam] reference NDF loaded from " + ndfPath
                            + " (press R to toggle)");
                } catch (Exception ex) {
                    referenceField = null;
                    Platforms.get().log("[cross-field-exam] failed to load reference NDF "
                            + ndfPath + ": " + ex.getMessage());
                }
            } else {
                Platforms.get().log("[cross-field-exam] no sibling _in_cf.ndf reference found");
            }

            try {
                runtime = new QuadLayoutRuntime();
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to create QuadLayoutRuntime", ex);
            }
            runtime.upload(he);
            runtime.setSolidColor(ColorRGB.BLUE_GRAY.toVector4f());
            runtime.frameCamera(camera);
            runtime.showCrossField = true;
            runtime.showSingularities = true;
            runtime.setCrossField(oursField, CROSS_SCALE);
            runtime.uploadConstraints(oursField, CROSS_SCALE);

            meshCenter.set(he.center(new Vector3f()));
            float meshRadius = he.radius();
            float minZoom = Math.max(ZOOM_MIN_FLOOR, meshRadius * ZOOM_MIN_RADIUS_FRACTION);
            float maxZoom = Math.max(CAMERA_DISTANCE_MIN, meshRadius * ZOOM_MAX_RADIUS_MUL);
            orbitMouse.setDistanceBounds(minZoom, maxZoom);
            float orbitDist = Math.max(CAMERA_DISTANCE_MIN, meshRadius * CAMERA_DISTANCE_RADIUS_MUL);
            orbitMouse.setTarget(meshCenter);
            orbitMouse.setOrbit(CAMERA_AZIMUTH, CAMERA_ELEVATION, orbitDist);

            Platforms.get().log("[cross-field-exam] " + offPath + " V=" + he.vertexCount()
                    + " F=" + he.faceCount() + " singularities=" + oursField.singularities.size());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize cross-field scene from "
                    + offPath, ex);
        }
    }

    /**
     * Replace {@code _in_tri.off} → {@code _in_cf.ndf} in the off path; return the
     * candidate NDF path iff it exists on disk. Returns {@code null} when either
     * the file isn't present or the off path doesn't match the BCEAK13 naming
     * convention.
     *
     * @param offPath source OFF path
     * @return sibling NDF path that exists, or {@code null}
     */
    private static String inferNdfPath(String offPath) {
        if (offPath == null || !offPath.endsWith(IN_TRI_OFF_SUFFIX)) {
            return null;
        }
        String stem = offPath.substring(0, offPath.length() - IN_TRI_OFF_SUFFIX.length());
        String candidate = stem + IN_CF_NDF_SUFFIX;
        Path p = new File(candidate).toPath();
        return Files.exists(p) ? candidate : null;
    }

    /** Reload the cross-field overlay using the reference field if available. */
    void toggleReferenceField() {
        if (referenceField == null) {
            Platforms.get().log("[cross-field-exam] no reference NDF available — R is a no-op");
            return;
        }
        showingReference = !showingReference;
        CrossField active = showingReference ? referenceField : oursField;
        runtime.setCrossField(active, CROSS_SCALE);
        runtime.uploadConstraints(active, CROSS_SCALE);
        Platforms.get().log("[cross-field-exam] now showing "
                + (showingReference ? "reference (BCEAK13)" : "ours")
                + LOG_DASH + active.singularities.size() + " singularities");
    }

    /** Toggle the per-face constraint glyph overlay and log the per-source counts. */
    void toggleConstraints() {
        runtime.showConstraints = !runtime.showConstraints;
        CrossField active = showingReference ? referenceField : oursField;
        int boundary = 0;
        int feature = 0;
        int curvature = 0;
        int anchor = 0;
        if (active.faceConstraintSource != null) {
            for (ConstraintSource source : active.faceConstraintSource) {
                switch (source) {
                    case BOUNDARY -> boundary++;
                    case FEATURE -> feature++;
                    case CURVATURE -> curvature++;
                    case ANCHOR -> anchor++;
                    default -> { }
                }
            }
        }
        Platforms.get().log("[cross-field-exam] constraint overlay "
                + (runtime.showConstraints ? LABEL_ON : LABEL_OFF)
                + LOG_DASH + "boundary=" + boundary + " feature=" + feature
                + " curvature=" + curvature + " anchor=" + anchor);
    }

    /** Toggle the cross-field glyph overlay (so the constraint overlay can be viewed alone). */
    void toggleCrossField() {
        runtime.showCrossField = !runtime.showCrossField;
        Platforms.get().log("[cross-field-exam] cross-field overlay "
                + (runtime.showCrossField ? LABEL_ON : LABEL_OFF));
    }

    /**
     * Render the translucent surface, then layer the cross-field overlay on top.
     */
    @Override
    public void drawScene() {
        if (runtime == null) {
            return;
        }
        camera.resetView();
        // Opaque pass: surface writes to depth buffer so back-side cross arms
        // are correctly occluded by the front-facing surface. Cross arms then
        // render on top with a small depth bias to beat z-fight (handled
        // render on top with a small depth bias to beat z-fight (handled in
        // QuadLayoutRuntime.renderCrossFieldOverlay).
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
     * Direct input binding that mirrors the desktop fallback in
     * {@link ixdar.scenes.mesh.MeshNodeViewerScene} — the reflection-based
     * {@code AutomationInputBinder} is the preferred path on web/TeaVM, but this
     * fallback fires on desktop.
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

    /** Inline {@link OrbitCameraKeyGuy} that adds the {@code R}/{@code C}/{@code X} toggles. */
    private static final class ToggleKeyGuy extends OrbitCameraKeyGuy {
        private final CrossFieldExaminationScene scene;

        ToggleKeyGuy(CrossFieldExaminationScene scene, OrbitMouseTrap orbitMouse, Camera camera,
                Canvas3D canvas) {
            super(orbitMouse, camera, canvas);
            this.scene = scene;
        }

        @Override
        protected void handleSceneKeys(int key, int mods) {
            if (key == Keys.R) {
                scene.toggleReferenceField();
            } else if (key == Keys.C) {
                scene.toggleConstraints();
            } else if (key == Keys.X) {
                scene.toggleCrossField();
            }
        }
    }
}
