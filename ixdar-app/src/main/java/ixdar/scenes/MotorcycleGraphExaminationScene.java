package ixdar.scenes;

import java.io.IOException;
import java.util.List;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.nodegraph.field.IntField;
import ixdar.geometry.mesh.quadlayout.nodes.QuadLayoutNode;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessUv;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.graphics.render.model.MeshOverlayRuntime;
import ixdar.nodegraph.syntax.PythonParser;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.model.ModelScene;
import ixdar.tools.ControlHint;

/**
 * Inspector for Lyon 2021 modified motorcycle graphs: trace iso-lines, T-mesh
 * nodes, patch fills, and live α stepping. It pulls the motorcycle output of
 * {@link QuadLayoutNode}, so only the stages the motorcycle graph depends on run.
 */
@SceneAnnotation(id = "mcg-exam")
public class MotorcycleGraphExaminationScene extends ModelScene {

    private MeshOverlayRuntime quadRuntime;
    private SeamlessUv seamless;

    /**
     * Default constructor wired by the scene annotation processor.
     */
    public MotorcycleGraphExaminationScene() {
        super();
    }

    @Override
    public HalfEdgeMeshRuntime createRuntime() {
        quadRuntime = new MeshOverlayRuntime();
        return quadRuntime;
    }

    @Override
    public String windowTitle() {
        return "Ixdar : Motorcycle Graph Examination";
    }

    @Override
    public List<PythonParser.ParsedNode> sceneGraph() {
        return QuadLayoutNode.overSceneModel();
    }

    @Override
    public void loadModel(String path) throws IOException {
        super.loadModel(path);
        rebuildMotorcycleGraph();
    }

    @Override
    public void setControls() {
        controls.add(new ControlHint(Keys.T, "T", "toggle traces",
                () -> quadRuntime.showTraces = !quadRuntime.showTraces));
        controls.add(new ControlHint(Keys.N, "N", "toggle nodes",
                () -> quadRuntime.showNodes = !quadRuntime.showNodes));
        controls.add(new ControlHint(Keys.COMMA, ",", "decrease alpha", () -> stepAlpha(-1f)));
        controls.add(new ControlHint(Keys.PERIOD, ".", "increase alpha", () -> stepAlpha(1f)));
        super.setControls();
    }

    private void rebuildMotorcycleGraph() {
        runSceneGraph();
        ArcNetwork motorcycle = (ArcNetwork) sceneOutput(
                QuadLayoutNode.sceneOutput(QuadLayoutNode.MOTORCYCLE));
        seamless = (SeamlessUv) sceneOutput(QuadLayoutNode.sceneOutput(QuadLayoutNode.SEAMLESS));
        IntField singularities = (IntField) sceneOutput(
                QuadLayoutNode.sceneOutput(QuadLayoutNode.SINGULARITIES));
        quadRuntime.setSeamlessParametrization(seamless, halfEdgeMesh);
        quadRuntime.setSingularities(singularities, halfEdgeMesh);
        quadRuntime.setMotorcycleGraph(motorcycle);
        quadRuntime.showTraces = true;
        quadRuntime.showNodes = true;
        quadRuntime.showCrossField = false;
        quadRuntime.showFullIsoGrid = false;
        float alphaDegrees = overrides.get(QuadLayoutNode.ALPHA_DEGREES_OVERRIDE) instanceof Number alpha
                ? alpha.floatValue() : MotorcycleGraph.DEFAULT_ALPHA_DEGREES;
        String hudLine = String.format("[mcg-exam] α=%.0f° traces=%d arcs=%d nodes=%d",
                alphaDegrees, motorcycle.traces.size(), motorcycle.arcs.size(),
                motorcycle.nodes.size());
        Platforms.get().log(hudLine);
    }

    void stepAlpha(float deltaDegrees) {
        stepOverride(QuadLayoutNode.ALPHA_DEGREES_OVERRIDE, deltaDegrees, 1f,
                MotorcycleGraph.DEFAULT_ALPHA_DEGREES);
        rebuildMotorcycleGraph();
    }

    @Override
    public void renderScene() {
        camera.resetView();
        quadRuntime.renderOverlays(camera);
    }
}
