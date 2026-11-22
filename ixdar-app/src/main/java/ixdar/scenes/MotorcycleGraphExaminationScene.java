package ixdar.scenes;

import java.io.IOException;
import java.util.List;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.data.fields.crossfield.CrossField;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.nodegraph.field.IntField;
import ixdar.nodegraph.nodes.quadlayout.QuadLayoutNode;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessUv;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.graphics.render.model.QuadLayoutOverlay;
import ixdar.nodegraph.runtime.NodeGroup;
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

    private QuadLayoutOverlay quadRuntime;
    private SeamlessUv seamless;

    /**
     * Default constructor wired by the scene annotation processor.
     */
    public MotorcycleGraphExaminationScene() {
        super();
    }

    @Override
    public HalfEdgeMeshRuntime createRuntime() {
        quadRuntime = new QuadLayoutOverlay();
        return quadRuntime;
    }

    @Override
    public String windowTitle() {
        return "Ixdar : Motorcycle Graph Examination";
    }

    @Override
    public NodeGroup sceneGraph() {
        return QuadLayoutNode.overSceneModel();
    }

    @Override
    public void loadModel(String path) throws IOException {
        super.loadModel(path);
        rebuildMotorcycleGraph();
    }

    @Override
    public void setControls(List<ControlHint> controls) {
        controls.add(new ControlHint("toggle-traces", Keys.T, "toggle traces",
                () -> quadRuntime.showTraces = !quadRuntime.showTraces));
        controls.add(new ControlHint("toggle-nodes", Keys.N, "toggle nodes",
                () -> quadRuntime.showNodes = !quadRuntime.showNodes));
        controls.add(new ControlHint("decrease-alpha", Keys.COMMA, "decrease alpha", () -> stepAlpha(-1f)));
        controls.add(new ControlHint("increase-alpha", Keys.PERIOD, "increase alpha", () -> stepAlpha(1f)));
        super.setControls(controls);
    }

    private void rebuildMotorcycleGraph() {
        runSceneGraph();
        ArcNetwork motorcycle = (ArcNetwork) sceneOutput(
                QuadLayoutNode.sceneOutput(QuadLayoutNode.MOTORCYCLE));
        seamless = (SeamlessUv) sceneOutput(QuadLayoutNode.sceneOutput(QuadLayoutNode.SEAMLESS));
        IntField singularities = (IntField) sceneOutput(
                QuadLayoutNode.sceneOutput(QuadLayoutNode.SINGULARITIES));
        // The layout's own mesh: the feature-corner split may have refined the scene's.
        HalfEdgeMesh layoutMesh = ((CrossField) sceneOutput(
                QuadLayoutNode.sceneOutput(QuadLayoutNode.FIELD))).mesh;
        quadRuntime.setSeamlessParametrization(seamless, layoutMesh);
        quadRuntime.setSingularities(singularities, layoutMesh);
        quadRuntime.setMotorcycleGraph(motorcycle);
        quadRuntime.showTraces = true;
        quadRuntime.showNodes = true;
        quadRuntime.showCrossField = false;
        quadRuntime.showFullIsoGrid = false;
        float alphaDegrees = modelCollection.overrides.get(QuadLayoutNode.ALPHA_DEGREES_OVERRIDE)
                instanceof Number alpha ? alpha.floatValue() : MotorcycleGraph.DEFAULT_ALPHA_DEGREES;
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
