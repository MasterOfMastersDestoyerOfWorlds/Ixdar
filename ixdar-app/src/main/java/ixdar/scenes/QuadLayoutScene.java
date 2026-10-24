package ixdar.scenes;

import java.io.IOException;
import java.util.List;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.nodegraph.field.BoolField;
import ixdar.nodegraph.nodes.quadlayout.QuadLayoutNode;
import ixdar.geometry.mesh.quadlayout.QuadLayout;
import ixdar.geometry.mesh.quadlayout.extraction.PatchSurfaceGeometry;
import ixdar.geometry.mesh.quadlayout.gridmap.GridMapIsoSurface;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.graphics.render.model.QuadLayoutOverlay;
import ixdar.nodegraph.runtime.NodeGroup;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.model.ModelScene;
import ixdar.tools.ControlHint;

/**
 * Final-output view of the quad-layout pipeline: runs the model through {@link QuadLayoutNode}
 * to the conforming layout and shows the quad mesh it prescribes, over colour-hashed layout
 * patches. Intermediate stages have their own inspector scenes.
 */
@SceneAnnotation(id = "quad-layout")
public class QuadLayoutScene extends ModelScene {

    private static final int GRID_MAP_VIEW_OFF = 0;

    private static final int GRID_MAP_VIEW_INITIAL = 1;

    private static final int GRID_MAP_VIEW_RELAXED = 2;

    private static final int GRID_MAP_VIEW_COUNT = 3;

    /** Render runtime of this scene; the diagnosis route highlights through it. */
    public QuadLayoutOverlay quadRuntime;

    /**
     * The layout of the last build, held so the fill can be swapped without rebuilding; the
     * diagnosis route reads its seamless map.
     */
    public QuadLayout layout;

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
     * Surface-grid geometry of the pre-relaxation extraction, built on first
     * toggle for the comparison view.
     */
    private GeometryBundle patchSurfacesInitial;

    /** Coons-blend variant of {@link #patchSurfacesInitial}, built with it. */
    private GeometryBundle patchCoonsInitial;

    /**
     * Default constructor wired by the scene annotation processor.
     */
    public QuadLayoutScene() {
        super();
    }

    @Override
    public HalfEdgeMeshRuntime createRuntime() {
        quadRuntime = new QuadLayoutOverlay();
        return quadRuntime;
    }

    @Override
    public String windowTitle() {
        return "Ixdar : Quad Layout";
    }

    @Override
    public NodeGroup sceneGraph() {
        return QuadLayoutNode.overSceneModel();
    }

    @Override
    public void loadModel(String path) throws IOException {
        super.loadModel(path);
        rebuildLayout();
    }

    @Override
    public void setControls(List<ControlHint> controls) {
        controls.add(new ControlHint("toggle-coons-fill", Keys.C, "C", "toggle Coons fill", this::toggleCoonsFill));
        controls.add(new ControlHint("toggle-initial-grid", Keys.O, "O", "toggle pre-relaxation grid",
                this::toggleInitialGrid));
        controls.add(new ControlHint("toggle-quad-grid", Keys.Q, "Q", "toggle quad grid",
                () -> quadRuntime.showQuadGrid = !quadRuntime.showQuadGrid));
        controls.add(new ControlHint("toggle-patch-fill", Keys.P, "P", "toggle patch fill",
                () -> quadRuntime.showLayoutPatches = !quadRuntime.showLayoutPatches));
        controls.add(new ControlHint("toggle-layout-boundaries", Keys.B, "B", "toggle layout boundaries",
                () -> quadRuntime.showLayoutBoundaries = !quadRuntime.showLayoutBoundaries));
        controls.add(new ControlHint("toggle-embedded-arcs", Keys.E, "E", "toggle embedded arcs",
                () -> quadRuntime.showEmbeddedArcs = !quadRuntime.showEmbeddedArcs));
        controls.add(new ControlHint("toggle-nodes", Keys.N, "N", "toggle nodes",
                () -> quadRuntime.showNodes = !quadRuntime.showNodes));
        controls.add(new ControlHint("cycle-grid-map", Keys.G, "G", "cycle grid map paint (off/initial/relaxed)",
                this::cycleGridMapView));
        controls.add(new ControlHint("decrease-alpha", Keys.COMMA, ",", "decrease alpha", () -> stepAlpha(-1f)));
        controls.add(new ControlHint("increase-alpha", Keys.PERIOD, ".", "increase alpha", () -> stepAlpha(1f)));
        super.setControls(controls);
    }

    /**
     * Runs the pipeline to the conforming layout and uploads it: the quad mesh over
     * the patch fill, layout arcs on top. The previous build's fields are dropped
     * first, so a throw part-way leaves nothing indexed by the old mesh.
     */
    private void rebuildLayout() {
        layout = null;
        patchSurfacesInitial = null;
        patchCoonsInitial = null;
        gridMapView = GRID_MAP_VIEW_OFF;
        runSceneGraph();
        QuadLayout built = (QuadLayout) sceneOutput(QuadLayoutNode.sceneOutput(QuadLayoutNode.LAYOUT));
        quadRuntime.setSeamlessParametrization(built.seamless, halfEdgeMesh);
        quadRuntime.setSingularities(built.crossField.singularityIndex4, halfEdgeMesh);
        quadRuntime.setMotorcycleGraph(built.skeleton);
        quadRuntime.setEmbeddedTMesh(built.tmesh);
        layout = built;
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
     * Uploads the selected map's patch-grid geometry: from the relaxed extraction
     * or the pre-relaxation one when the comparison toggle holds it, with the
     * current fill mode picking the surface or Coons bundle. The pre-relaxation
     * bundles are built on first use and cached until the next rebuild.
     */
    private void uploadSurfaces() {
        if (showInitialGrid && patchSurfacesInitial == null) {
            patchSurfacesInitial =
                    PatchSurfaceGeometry.surfaceBundle(layout.globalGrid.quadGridInitial);
            patchCoonsInitial =
                    PatchSurfaceGeometry.coonsBundle(layout.globalGrid.quadGridInitial);
        }
        GeometryBundle chosen = showInitialGrid
                ? (coonsFill ? patchCoonsInitial : patchSurfacesInitial)
                : (coonsFill ? layout.patchCoons : layout.patchSurfaces);
        quadRuntime.setLayoutPatchSurfaces(chosen, layout.tmesh);
    }

    /**
     * Swaps the patch fill between each patch's Coons blend and its quad grid on
     * the surface, re-uploading because the two grids share one buffer.
     */
    private void toggleCoonsFill() {
        coonsFill = !coonsFill;
        if (layout != null) {
            uploadSurfaces();
        }
    }

    /**
     * Swaps the displayed grid between the pre-relaxation map and the relaxed one,
     * so the relaxation's effect is visible in place.
     */
    private void toggleInitialGrid() {
        showInitialGrid = !showInitialGrid;
        if (layout != null) {
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
        if (layout == null) {
            return;
        }
        if (gridMapView == GRID_MAP_VIEW_OFF) {
            quadRuntime.showFullIsoGrid = false;
            quadRuntime.setSeamlessParametrization(layout.seamless, halfEdgeMesh);
            Platforms.get().log("[quad-layout] grid map paint off");
            return;
        }
        GridMapIsoSurface isoSurface = gridMapView == GRID_MAP_VIEW_INITIAL
                ? layout.globalGrid.isoSurfaceInitial
                : layout.globalGrid.isoSurfaceRelaxed;
        quadRuntime.setGridMapParametrization(isoSurface, layout.tmesh.topology.copy,
                new BoolField(isoSurface.faceFlipped));
        quadRuntime.showFullIsoGrid = true;
        Platforms.get().log("[quad-layout] grid map paint: "
                + (gridMapView == GRID_MAP_VIEW_INITIAL ? "INITIAL" : "RELAXED")
                + " flippedFaces=" + isoSurface.flippedFaceCount);
    }

    void stepAlpha(float deltaDegrees) {
        stepOverride(QuadLayoutNode.ALPHA_DEGREES_OVERRIDE, deltaDegrees, 1f,
                MotorcycleGraph.DEFAULT_ALPHA_DEGREES);
        rebuildLayout();
    }

    @Override
    public void renderScene() {
        camera.resetView();
        quadRuntime.render(camera);
        quadRuntime.renderOverlays(camera);
    }
}
