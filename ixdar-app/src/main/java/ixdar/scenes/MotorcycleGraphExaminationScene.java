package ixdar.scenes;

import java.io.IOException;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessUv;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.graphics.render.model.QuadLayoutRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.model.ControlHint;
import ixdar.scenes.model.ModelScene;

/**
 * Inspector for Lyon 2021 modified motorcycle graphs: trace iso-lines, T-mesh
 * nodes, patch fills, and live α stepping.
 */
@SceneAnnotation(id = "mcg-exam")
public class MotorcycleGraphExaminationScene extends ModelScene {
    private QuadLayoutRuntime quadRuntime;
    private SeamlessUv seamless;
    private float alphaDegrees = 15f;

    /**
     * Default constructor wired by the scene annotation processor.
     */
    public MotorcycleGraphExaminationScene() {
        super();
    }

    @Override
    public HalfEdgeMeshRuntime createRuntime() {
        quadRuntime = new QuadLayoutRuntime();
        return quadRuntime;
    }

    @Override
    public String windowTitle() {
        return "Ixdar : Motorcycle Graph Examination";
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
        controls.add(new ControlHint(Keys.W, "W", "toggle witnesses",
                () -> quadRuntime.showWitnesses = !quadRuntime.showWitnesses));
        controls.add(new ControlHint(Keys.E, "E", "toggle Eppstein markers",
                () -> quadRuntime.showEppsteinMarkers = !quadRuntime.showEppsteinMarkers));
        controls.add(new ControlHint(Keys.COMMA, ",", "decrease alpha", () -> stepAlpha(-1f)));
        controls.add(new ControlHint(Keys.PERIOD, ".", "increase alpha", () -> stepAlpha(1f)));
        super.setControls();
    }

    private void rebuildMotorcycleGraph() {
        float alphaRadians = (float) Math.toRadians(alphaDegrees);
        QuadLayoutEngine engine = new QuadLayoutEngine(halfEdgeMesh, alphaRadians);
        MotorcycleGraph graph = engine.buildMotorcycleGraph();
        seamless = engine.seamless;
        quadRuntime.setSeamlessParametrization(seamless, halfEdgeMesh);
        quadRuntime.captureSingularities(engine.crossField.singularities, halfEdgeMesh);
        quadRuntime.setMotorcycleGraph(graph);
        quadRuntime.showTraces = true;
        quadRuntime.showNodes = true;
        quadRuntime.showCrossField = false;
        quadRuntime.showFullIsoGrid = false;
        String hudLine = String.format("[mcg-exam] α=%.0f° traces=%d arcs=%d nodes=%d",
                alphaDegrees, graph.traces.size(), graph.arcs.size(), graph.nodes.size());
        Platforms.get().log(hudLine);
    }

    void stepAlpha(float deltaDegrees) {
        alphaDegrees = Math.max(1f, alphaDegrees + deltaDegrees);
        rebuildMotorcycleGraph();
    }

    @Override
    public void renderScene() {
        camera.resetView();
        quadRuntime.renderOverlays(camera);
    }
}
