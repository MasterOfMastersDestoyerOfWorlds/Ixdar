package ixdar.scenes;

import java.io.IOException;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.data.load.CrossFieldLoader;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.seamless.ParameterizationMetrics;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.graphics.render.model.QuadLayoutRuntime;
import ixdar.platform.Platforms;
import ixdar.scenes.model.ModelScene;

/**
 * 3D scene for inspecting a seamless parametrization on a triangle mesh: loads
 * the OFF given by {@code -Dparametrization.scene.off=<path>}, runs the
 * cross-field and seamless pipeline, and draws cyan u-iso-lines, yellow
 * v-iso-lines, and singularity spheres.
 *
 * <p>
 * See also: BZK09 Figure 1(c)
 */
@SceneAnnotation(id = "param-exam")
public class ParametrizationExaminationScene extends ModelScene {
    /**
     * Optional system property naming an .ndf reference cross field. When set,
     * {@link #loadModel} still runs {@link CrossField#build()} for the per-face
     * frames and active-index maps, then overwrites {@code theta},
     * {@code periodJump}, {@code singularityIndexQuarter} and {@code singularities}
     * from the NDF.
     */
    public static final String CROSS_FIELD_PROPERTY = "parametrization.scene.cf";
    public String cfPath = System.getProperty(CROSS_FIELD_PROPERTY);
    public QuadLayoutRuntime quadRuntime;

    /** Default constructor wired by the scene annotation processor. */
    public ParametrizationExaminationScene() {
        super();
    }

    @Override
    public HalfEdgeMeshRuntime createRuntime() {
        quadRuntime = new QuadLayoutRuntime();
        return quadRuntime;
    }

    @Override
    public String windowTitle() {
        return "Ixdar : Parametrization Examination";
    }

    /**
     * Load {@code path}: build the cross field (optionally overridden by the
     * {@code -D…cf} reference), run the seamless parametrization, and hand it to
     * the runtime.
     *
     * @param path mesh file path to load
     * @throws IOException if the mesh file cannot be read
     */
    @Override
    public void loadModel(String path) throws IOException {
        super.loadModel(path);
        QuadLayoutEngine engine = new QuadLayoutEngine(halfEdgeMesh, QuadLayoutEngine.DEFAULT_ALPHA_RADIANS);
        CrossField crossField = engine.buildCrossField();
        int ourSingCount = crossField.singularities.size();
        if (cfPath != null) {
            try {
                CrossField reference = CrossFieldLoader.load(cfPath, halfEdgeMesh);
                CrossFieldLoader.alignPeriodJumpsToFrame(reference, crossField);
                CrossFieldLoader.convertBceak13ThetaToQuadAxes(reference);
                crossField.theta = reference.theta;
                crossField.periodJump = reference.periodJump;
                crossField.singularities.clear();
                crossField.singularities.addAll(reference.singularities);
                Platforms.get().log("[param-exam] using reference cross field from "
                        + cfPath + " (our solver produced " + ourSingCount
                        + " singularities, reference has " + reference.singularities.size() + ")");
            } catch (Exception ex) {
                Platforms.get().log("[param-exam] failed to apply reference cross field "
                        + cfPath + ": " + ex.getMessage());
            }
        }
        SeamlessParameterization seamless = engine.buildSeamless();
        ParameterizationMetrics metrics = engine.seamlessMetrics;

        quadRuntime.showIsoLines = true;
        quadRuntime.showSingularities = true;
        quadRuntime.setSeamlessParametrization(seamless);

        Platforms.get().log("[param-exam] " + offPath
                + (cfPath == null ? "" : " cf=" + cfPath)
                + " V=" + halfEdgeMesh.vertexCount()
                + " F=" + halfEdgeMesh.faceCount()
                + " singularities=" + crossField.singularities.size()
                + " flipped=" + metrics.flippedTriangleCount
                + " injective=" + seamless.injective);
    }

    /**
     * Render the iso-line surface on top of the base mesh. The iso-surface already
     * covers the mesh's geometry (it is the same triangle layout, in a
     * triangle-soup form), so we do not also draw the underlying mesh — doing so
     * would z-fight and obscure the iso-lines.
     */
    @Override
    public void renderScene() {
        camera.resetView();
        quadRuntime.renderOverlays(camera);
    }
}
