package ixdar.scenes;

import java.io.IOException;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.gridmap.GridMapIsoSurface;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.graphics.render.model.QuadLayoutRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.model.ControlHint;
import ixdar.scenes.model.ModelScene;

/**
 * Final-output view of the quad-layout pipeline: runs {@link QuadLayoutEngine}
 * to the conforming layout and shows the quad mesh it prescribes, over
 * colour-hashed layout patches. Intermediate stages have their own inspector
 * scenes.
 */
@SceneAnnotation(id = "quad-layout")
public class QuadLayoutScene extends ModelScene {

    /** Grid-map paint off; the iso surface holds the seamless parametrization. */
    private static final int GRID_MAP_VIEW_OFF = 0;

    /** Grid-map paint showing the pre-relaxation integer grid map. */
    private static final int GRID_MAP_VIEW_INITIAL = 1;

    /** Grid-map paint showing the relaxed integer grid map. */
    private static final int GRID_MAP_VIEW_RELAXED = 2;

    /** States the grid-map paint toggle cycles through. */
    private static final int GRID_MAP_VIEW_COUNT = 3;

    private QuadLayoutRuntime quadRuntime;
    private float alphaDegrees = 15f;
    private boolean coonsFill;

    /**
     * Whether the displayed grid is the pre-relaxation map instead of the relaxed
     * one.
     */
    private boolean showInitialGrid;

    /**
     * Which integer grid map is painted on the surface, one of the view constants.
     */
    private int gridMapView;

    /**
     * The engine of the last build, held so the fill can be swapped without
     * rebuilding.
     */
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
        controls.add(new ControlHint(Keys.O, "O", "toggle pre-relaxation grid",
                this::toggleInitialGrid));
        controls.add(new ControlHint(Keys.Q, "Q", "toggle quad grid",
                () -> quadRuntime.showQuadGrid = !quadRuntime.showQuadGrid));
        controls.add(new ControlHint(Keys.P, "P", "toggle patch fill",
                () -> quadRuntime.showLayoutPatches = !quadRuntime.showLayoutPatches));
        controls.add(new ControlHint(Keys.B, "B", "toggle layout boundaries",
                () -> quadRuntime.showLayoutBoundaries = !quadRuntime.showLayoutBoundaries));
        controls.add(new ControlHint(Keys.E, "E", "toggle embedded arcs",
                () -> quadRuntime.showEmbeddedArcs = !quadRuntime.showEmbeddedArcs));
        controls.add(new ControlHint(Keys.N, "N", "toggle nodes",
                () -> quadRuntime.showNodes = !quadRuntime.showNodes));
        controls.add(new ControlHint(Keys.G, "G", "cycle grid map paint (off/initial/relaxed)",
                this::cycleGridMapView));
        controls.add(new ControlHint(Keys.COMMA, ",", "decrease alpha", () -> stepAlpha(-1f)));
        controls.add(new ControlHint(Keys.PERIOD, ".", "increase alpha", () -> stepAlpha(1f)));
        super.setControls();
    }

    /**
     * Runs the pipeline through the conforming layout and its per-patch grids, and
     * uploads the result: the quad mesh over the patch fill, with the layout arcs
     * drawn on top.
     */
    private void rebuildLayout() {
        float alphaRadians = (float) Math.toRadians(alphaDegrees);
        QuadLayoutEngine engine = new QuadLayoutEngine(halfEdgeMesh, alphaRadians);
        engine.buildPatchSurfaces();
        quadRuntime.setSeamlessParametrization(engine.seamless);
        quadRuntime.setMotorcycleGraph(engine.motorcycleGraph);
        quadRuntime.setEmbeddedTMesh(engine.tmesh);
        this.engine = engine;
        uploadSurfaces();
        quadRuntime.showTraces = false;
        quadRuntime.showNodes = false;
        quadRuntime.showCrossField = false;
        quadRuntime.showFullIsoGrid = false;
        quadRuntime.showLayoutPatches = true;
        quadRuntime.showQuadGrid = true;
        quadRuntime.showLayoutBoundaries = true;
        quadRuntime.showEmbeddedArcs = false;
    }

    /**
     * Uploads the selected map's render products: the grid extraction and the arc
     * isolines, from the relaxed map or the pre-relaxation one when the comparison
     * toggle holds it, with the current fill mode.
     */
    private void uploadSurfaces() {
        quadRuntime.setLayoutPatchSurfaces(
                showInitialGrid ? engine.patchSurfacesInitial : engine.patchSurfaces, coonsFill);
    }

    /**
     * Swaps the patch fill between each patch's Coons blend and its quad grid on
     * the surface, re-uploading because the two grids share one buffer.
     */
    private void toggleCoonsFill() {
        coonsFill = !coonsFill;
        if (engine != null) {
            uploadSurfaces();
        }
    }

    /**
     * Swaps the displayed grid between the pre-relaxation map and the relaxed one,
     * so the relaxation's effect is visible in place.
     */
    private void toggleInitialGrid() {
        showInitialGrid = !showInitialGrid;
        if (engine != null) {
            uploadSurfaces();
            Platforms.get().log("[quad-layout] showing "
                    + (showInitialGrid ? "PRE-relaxation" : "relaxed") + " grid");
        }
    }

    /**
     * Cycles the surface paint through off, the pre-relaxation integer grid map,
     * and the relaxed one, so the Newton solve's effect on the map is judged
     * directly on the surface rather than through the extraction.
     */
    private void cycleGridMapView() {
        gridMapView = (gridMapView + 1) % GRID_MAP_VIEW_COUNT;
        if (engine == null) {
            return;
        }
        if (gridMapView == GRID_MAP_VIEW_OFF) {
            quadRuntime.showFullIsoGrid = false;
            quadRuntime.setSeamlessParametrization(engine.seamless);
            Platforms.get().log("[quad-layout] grid map paint off");
            return;
        }
        GridMapIsoSurface isoSurface = gridMapView == GRID_MAP_VIEW_INITIAL
                ? engine.globalGrid.isoSurfaceInitial
                : engine.globalGrid.isoSurfaceRelaxed;
        quadRuntime.uploadPatchParametrization(engine.tmesh.topology.copy,
                isoSurface.cornerU, isoSurface.cornerV, isoSurface.faceFlipped);
        quadRuntime.showFullIsoGrid = true;
        Platforms.get().log("[quad-layout] grid map paint: "
                + (gridMapView == GRID_MAP_VIEW_INITIAL ? "INITIAL" : "RELAXED")
                + " flippedFaces=" + isoSurface.flippedFaceCount);
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
