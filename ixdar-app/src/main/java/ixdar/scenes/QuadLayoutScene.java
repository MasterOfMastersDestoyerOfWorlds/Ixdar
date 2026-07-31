package ixdar.scenes;

import java.io.IOException;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.graphics.render.model.QuadLayoutRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.model.ControlHint;
import ixdar.scenes.model.ModelScene;

/**
 * Final-output view of the quad-layout pipeline: runs {@link QuadLayoutEngine} to the conforming
 * layout and shows the quad mesh it prescribes, over colour-hashed layout patches. Intermediate
 * stages have their own inspector scenes.
 */
@SceneAnnotation(id = "quad-layout")
public class QuadLayoutScene extends ModelScene {

    private QuadLayoutRuntime quadRuntime;
    private float alphaDegrees = 15f;
    private boolean coonsFill;

    /** The engine of the last build, held so the fill can be swapped without rebuilding. */
    private QuadLayoutEngine engine;

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
        controls.add(new ControlHint(Keys.C, "C", "toggle Coons fill", this::toggleCoonsFill));
        controls.add(new ControlHint(Keys.Q, "Q", "toggle quad grid",
                () -> quadRuntime.showQuadGrid = !quadRuntime.showQuadGrid));
        controls.add(new ControlHint(Keys.P, "P", "toggle patch fill",
                () -> quadRuntime.showLayoutPatches = !quadRuntime.showLayoutPatches));
        controls.add(new ControlHint(Keys.B, "B", "toggle layout arcs",
                () -> quadRuntime.showLayoutBoundaries = !quadRuntime.showLayoutBoundaries));
        controls.add(new ControlHint(Keys.E, "E", "toggle embedded arcs",
                () -> quadRuntime.showEmbeddedArcs = !quadRuntime.showEmbeddedArcs));
        controls.add(new ControlHint(Keys.N, "N", "toggle nodes",
                () -> quadRuntime.showNodes = !quadRuntime.showNodes));
        controls.add(new ControlHint(Keys.COMMA, ",", "decrease alpha", () -> stepAlpha(-1f)));
        controls.add(new ControlHint(Keys.PERIOD, ".", "increase alpha", () -> stepAlpha(1f)));
        super.setControls();
    }

    /**
     * Runs the pipeline through the conforming layout and its per-patch grids, and uploads the
     * result: the quad mesh over the patch fill, with the layout arcs drawn on top.
     */
    private void rebuildLayout() {
        float alphaRadians = (float) Math.toRadians(alphaDegrees);
        QuadLayoutEngine engine = new QuadLayoutEngine(halfEdgeMesh, alphaRadians);
        engine.buildPatchSurfaces();
        quadRuntime.setSeamlessParametrization(engine.seamless);
        quadRuntime.setMotorcycleGraph(engine.motorcycleGraph);
        quadRuntime.setEmbeddedTMesh(engine.tmesh);
        quadRuntime.setLayoutPatchSurfaces(engine.patchSurfaces, coonsFill);
        quadRuntime.showTraces = false;
        quadRuntime.showNodes = false;
        quadRuntime.showCrossField = false;
        quadRuntime.showFullIsoGrid = false;
        quadRuntime.showLayoutPatches = true;
        quadRuntime.showQuadGrid = true;
        quadRuntime.showLayoutBoundaries = true;
        quadRuntime.showEmbeddedArcs = false;
        String hudLine = String.format(
                "[quad-layout] α=%.0f° patches=%d quads=%d extensions=%d collapses=%d"
                        + " nodes=%d arcs=%d copyV=%d",
                alphaDegrees, engine.livePatchCount(), engine.quadGrid.quadCount,
                engine.tmesh.extendTJunction.extensionCount, engine.tmesh.arcCollapseCount,
                engine.tmesh.nodes.size(), engine.tmesh.arcs.size(),
                engine.tmesh.topology.copy.vertexCount());
        Platforms.get().log(hudLine);
        this.engine = engine;
    }

    /**
     * Swaps the patch fill between each patch's Coons blend and its quad grid on the surface,
     * re-uploading because the two grids share one buffer.
     */
    private void toggleCoonsFill() {
        coonsFill = !coonsFill;
        if (engine != null) {
            quadRuntime.setLayoutPatchSurfaces(engine.patchSurfaces, coonsFill);
        }
    }

    void stepAlpha(float deltaDegrees) {
        alphaDegrees = Math.max(1f, alphaDegrees + deltaDegrees);
        rebuildLayout();
    }

    @Override
    public void renderScene() {
        camera.resetView();
        quadRuntime.render(camera);
        quadRuntime.renderOverlays(camera);
    }
}
