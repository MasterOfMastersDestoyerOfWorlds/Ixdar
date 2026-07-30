package ixdar.scenes;

import java.io.IOException;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.quantization.LayoutExtraction;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.graphics.render.model.QuadLayoutRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.model.ControlHint;
import ixdar.scenes.model.ModelScene;

/**
 * Final-output view of the quad-layout pipeline: runs the staged
 * {@link QuadLayoutEngine} to its deepest implemented stage and shows the
 * resulting coarse layout structure. Intermediate stages have their own
 * inspector scenes.
 */
@SceneAnnotation(id = "quad-layout")
public class QuadLayoutScene extends ModelScene {

    private QuadLayoutRuntime quadRuntime;
    private float alphaDegrees = 15f;

    /**
     * Default constructor wired by the scene annotation processor.
     */
    public QuadLayoutScene() {
        super();
    }

    @Override
    public HalfEdgeMeshRuntime createRuntime() {
        quadRuntime = new QuadLayoutRuntime();
        return quadRuntime;
    }

    @Override
    public String windowTitle() {
        return "Ixdar : Quad Layout";
    }

    @Override
    public void loadModel(String path) throws IOException {
        super.loadModel(path);
        rebuildLayout();
    }

    @Override
    public void setControls() {
        controls.add(new ControlHint(Keys.C, "C", "toggle layout patches",
                () -> quadRuntime.showLayoutPatches = !quadRuntime.showLayoutPatches));
        controls.add(new ControlHint(Keys.B, "B", "toggle layout boundaries",
                () -> quadRuntime.showLayoutBoundaries = !quadRuntime.showLayoutBoundaries));
        controls.add(new ControlHint(Keys.T, "T", "toggle traces",
                () -> quadRuntime.showTraces = !quadRuntime.showTraces));
        controls.add(new ControlHint(Keys.E, "E", "toggle embedded arcs",
                () -> quadRuntime.showEmbeddedArcs = !quadRuntime.showEmbeddedArcs));
        controls.add(new ControlHint(Keys.N, "N", "toggle nodes",
                () -> quadRuntime.showNodes = !quadRuntime.showNodes));
        controls.add(new ControlHint(Keys.COMMA, ",", "decrease alpha", () -> stepAlpha(-1f)));
        controls.add(new ControlHint(Keys.PERIOD, ".", "increase alpha", () -> stepAlpha(1f)));
        super.setControls();
    }

    /**
     * Run the full pipeline (through quantization and zero-arc collapse) and
     * display the resulting layout: the positive-quantized separatrix skeleton
     * replaces the full trace web in the runtime's trace records, drawn over the
     * patch fill.
     */
    private void rebuildLayout() {
        float alphaRadians = (float) Math.toRadians(alphaDegrees);
        QuadLayoutEngine engine = new QuadLayoutEngine(halfEdgeMesh, alphaRadians);
        engine.buildContractedTMesh();
        LayoutExtraction layout = engine.layout;
        MotorcycleGraph graph = engine.motorcycleGraph;
        graph.traceRecordsByFace = layout.layoutRecordsByFace;
        quadRuntime.setSeamlessParametrization(engine.seamless);
        quadRuntime.setMotorcycleGraph(graph);
        quadRuntime.setEmbeddedTMesh(engine.tmesh);
        quadRuntime.showTraces = true;
        quadRuntime.showNodes = false;
        quadRuntime.showCrossField = false;
        quadRuntime.showFullIsoGrid = false;
        quadRuntime.showLayoutPatches = false;
        quadRuntime.showLayoutBoundaries = false;
        quadRuntime.showEmbeddedArcs = true;
        String hudLine = String.format(
                "[quad-layout] α=%.0f° skeletonArcs=%d layoutNodes=%d nodes=%d arcs=%d patches=%d"
                        + " collapses=%d embedArcs=%d/%d copyV=%d",
                alphaDegrees, layout.layoutArcs.size(), layout.singularClusterCount,
                engine.tmesh.nodes.size(), engine.tmesh.arcs.size(), engine.tmesh.patches.size(),
                engine.tmesh.arcCollapseCount,
                engine.embedding.carve.carvedArcCount, engine.embedding.pathByArc.length,
                engine.tmesh.topology.copy.vertexCount());
        Platforms.get().log(hudLine);
    }

    void stepAlpha(float deltaDegrees) {
        alphaDegrees = Math.max(1f, alphaDegrees + deltaDegrees);
        rebuildLayout();
    }

    @Override
    public void renderScene() {
        camera.resetView();
        quadRuntime.renderOverlays(camera);
    }
}
