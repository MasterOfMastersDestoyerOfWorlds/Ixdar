package ixdar.scenes;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.data.load.CrossFieldLoader;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.graphics.render.model.QuadLayoutRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.model.ControlHint;
import ixdar.scenes.model.ModelScene;

/**
 * 3D scene for inspecting a cross field on the triangle mesh named by
 * {@code -DcrossFieldScene.off=<path>}, drawing cross glyphs and singularity
 * spheres.
 *
 * <p>
 * {@code R} swaps in a reference NDF, which needs a sibling {@code *_in_cf.ndf}
 * beside the OFF; {@code C} toggles constraint glyphs, {@code X} the cross
 * field.
 */
@SceneAnnotation(id = "cross-field-exam")
public class CrossFieldExaminationScene extends ModelScene {

    public static final String IN_TRI_OFF_SUFFIX = "_in_tri.off";
    public static final String IN_CF_NDF_SUFFIX = "_in_cf.ndf";
    public static final float CROSS_SCALE = 1f;
    private CrossField oursField;
    private CrossField referenceField;
    private boolean showingReference;
    private QuadLayoutRuntime quadRuntime;

    /** Default constructor wired by the scene annotation processor. */
    public CrossFieldExaminationScene() {
        super();
    }

    @Override
    public HalfEdgeMeshRuntime createRuntime() {
        quadRuntime = new QuadLayoutRuntime();
        return quadRuntime;
    }

    @Override
    public String windowTitle() {
        return "Ixdar : Cross Field Examination";
    }

    /**
     * Load {@code path}: build our cross field, load the sibling reference NDF if
     * present, upload the surface, and re-frame the orbit camera.
     *
     * @param path mesh file path to load
     * @throws IOException if the mesh file cannot be read
     */
    @Override
    public void loadModel(String path) throws IOException {
        super.loadModel(path);
        oursField = new QuadLayoutEngine(halfEdgeMesh, QuadLayoutEngine.DEFAULT_ALPHA_RADIANS).buildCrossField();
        referenceField = null;
        showingReference = false;

        String ndfPath = inferNdfPath(path);
        if (ndfPath != null) {
            try {
                referenceField = CrossFieldLoader.load(ndfPath, halfEdgeMesh);
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
        quadRuntime.showCrossField = true;
        quadRuntime.showSingularities = true;
        quadRuntime.setCrossField(oursField, CROSS_SCALE);
        quadRuntime.uploadConstraints(oursField, CROSS_SCALE);

        Platforms.get().log("[cross-field-exam] " + offPath + " V=" + halfEdgeMesh.vertexCount()
                + " F=" + halfEdgeMesh.faceCount() + " singularities=" + oursField.singularities.size());
    }

    @Override
    public void setControls() {
        super.setControls();
        controls.add(new ControlHint(Keys.R, "R", "toggle reference field", this::toggleReferenceField));
        controls.add(new ControlHint(Keys.C, "C", "toggle constraints", this::toggleConstraints));
        controls.add(new ControlHint(Keys.X, "X", "toggle cross field", this::toggleCrossField));
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
        return Platforms.get().fileExists(candidate) ? candidate : null;
    }

    /** Reload the cross-field overlay using the reference field if available. */
    void toggleReferenceField() {
        if (referenceField == null) {
            Platforms.get().log("[cross-field-exam] no reference NDF available — R is a no-op");
            return;
        }
        showingReference = !showingReference;
        CrossField active = showingReference ? referenceField : oursField;
        quadRuntime.setCrossField(active, CROSS_SCALE);
        quadRuntime.uploadConstraints(active, CROSS_SCALE);
    }

    /**
     * Toggle the per-face constraint glyph overlay and log the per-source counts.
     */
    void toggleConstraints() {
        quadRuntime.showConstraints = !quadRuntime.showConstraints;
    }

    /**
     * Toggle the cross-field glyph overlay (so the constraint overlay can be viewed
     * alone).
     */
    void toggleCrossField() {
        quadRuntime.showCrossField = !quadRuntime.showCrossField;
        Platforms.get().log("[cross-field-exam] cross-field overlay "
                + (quadRuntime.showCrossField ? "on" : "off"));
    }

    /**
     * Render the translucent surface, then layer the cross-field overlay on top.
     */
    @Override
    public void renderScene() {
        camera.resetView();
        quadRuntime.render(camera);
        quadRuntime.renderOverlays(camera);
    }
}
