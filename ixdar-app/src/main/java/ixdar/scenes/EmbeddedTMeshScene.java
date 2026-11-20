package ixdar.scenes;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.nodegraph.nodes.quadlayout.NetworkContractionNode;
import ixdar.nodegraph.nodes.quadlayout.QuadLayoutNode;
import ixdar.geometry.mesh.quadlayout.embedding.ArrangementDiagnostic;
import ixdar.geometry.mesh.quadlayout.embedding.ArrangementDiagnosticException;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.graphics.render.model.MeshOverlayRuntime;
import ixdar.graphics.render.model.QuadLayoutOverlay;
import ixdar.graphics.render.model.SurfaceLook;
import ixdar.graphics.render.model.SurfaceLook.Light;
import ixdar.models.ModelChoice;
import ixdar.nodegraph.runtime.NodeGroup;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.model.ModelScene;
import ixdar.tools.ControlHint;

/**
 * Debug view of an embedded T-mesh: arcs as edge paths, positive orange and
 * zero red, nodes as spheres. {@code C} contracts to a fixed point; the graph
 * debugger (ctrl+D) steps the contraction one operator or one drag at a time.
 *
 * <p>
 * See also: LCBK19 Figure 9
 */
@SceneAnnotation(id = "embedded-tmesh")
public class EmbeddedTMeshScene extends ModelScene {

    private static final int DENSE_FIXTURE_SCALE = 4;

    private static final String SPLIT_DENSITY_LOOK = "split_density";

    /** Whether a full contraction (all three operators to a fixed point) was requested by keypress. */
    public volatile boolean pendingContract;

    /** Whether the refinement-density heat map was toggled by keypress. */
    public volatile boolean pendingSplitDensity;

    /** Whether the working copy's triangle outlines were toggled by keypress. */
    public volatile boolean pendingWireframe;

    /** Whether hiding or showing the node marker spheres was requested by keypress. */
    public volatile boolean pendingNodeToggle;

    /** Whether rebuilding the uncontracted T-mesh from the source surface was requested. */
    public volatile boolean pendingReset;

    private QuadLayoutOverlay quadRuntime;
    private ArcNetwork tmesh;

    /** Contraction operators bound to {@link #tmesh}; rebound whenever it is replaced. */
    private NetworkContraction contraction;

    /**
     * Registers the hand-authored layout fixtures alongside the mesh catalog.
     */
    public EmbeddedTMeshScene() {
        super();
        registerGraph(new ModelChoice("Fan collapse",
                "dsl/fixtures/fan_collapse.dsl", ModelChoice.Kind.DSL));
        registerGraph(new ModelChoice("Sliver pinch",
                "dsl/fixtures/sliver_pinch.dsl", ModelChoice.Kind.DSL));
        registerGraph(new ModelChoice("Merged-cell slot",
                "dsl/fixtures/merged_cell_slot.dsl", ModelChoice.Kind.DSL));
        registerGraph(new ModelChoice("Pinched cover",
                "dsl/fixtures/pinched_cover.dsl", ModelChoice.Kind.DSL));
        registerGraph(new ModelChoice("Twin cell",
                "dsl/fixtures/twin_cell.dsl", ModelChoice.Kind.DSL));
        registerGraph(new ModelChoice("Loop collapse",
                "dsl/fixtures/loop_collapse.dsl", ModelChoice.Kind.DSL));
        registerGraph(new ModelChoice("Torus layout",
                "dsl/fixtures/torus_layout.dsl", ModelChoice.Kind.DSL));
        registerGraph(new ModelChoice("Stacked zero row torus",
                "dsl/fixtures/stacked_zero_row_torus.dsl", ModelChoice.Kind.DSL));
        registerGraph(new ModelChoice("Scaled torus x" + DENSE_FIXTURE_SCALE,
                "dsl/fixtures/scaled_torus.dsl", ModelChoice.Kind.DSL, null, Map.of(
                        "carrier.major_segments", 12 * DENSE_FIXTURE_SCALE,
                        "carrier.minor_segments", 8 * DENSE_FIXTURE_SCALE)));
        registerGraph(new ModelChoice("Plane layout",
                "dsl/fixtures/plane_layout.dsl", ModelChoice.Kind.DSL));
    }

    @Override
    public HalfEdgeMeshRuntime createRuntime() {
        quadRuntime = new QuadLayoutOverlay();
        runtime = quadRuntime;
        return runtime;
    }

    @Override
    public String windowTitle() {
        return "Ixdar : Embedded T-Mesh";
    }

    @Override
    public NodeGroup sceneGraph() {
        return QuadLayoutNode.overSceneModel();
    }

    /**
     * Load {@code path}, then build the uncontracted T-mesh from the loaded surface.
     *
     * @param path mesh file path to load
     * @throws IOException if the mesh file cannot be read
     */
    @Override
    public void loadModel(String path) throws IOException {
        super.loadModel(path);
        rebuildTMesh();
        Platforms.get().log(String.format(
                "[embedded-tmesh] source=%s nodes=%d arcs=%d patches=%d",
                offPath, tmesh.nodes.size(), tmesh.arcs.size(), tmesh.patches.size()));
    }

    /**
     * Load a fixture graph's hand-authored T-mesh directly, skipping the pipeline: the
     * network is the executed graph's final {@code net} output, and its working copy is
     * the surface shown.
     *
     * @param choice registered fixture graph to build and show
     * @return the executed runtime
     */
    @Override
    public NodeGraphRuntime loadGraph(ModelChoice choice) {
        NodeGraphRuntime run = super.loadGraph(choice);
        tmesh = (ArcNetwork) run.lastOutput("net");
        halfEdgeMesh = tmesh.topology.copy;
        runtime.upload(halfEdgeMesh);
        frameLoadedModel();
        contraction = new NetworkContraction(tmesh);
        quadRuntime.setEmbeddedTMesh(tmesh);
        quadRuntime.clearDiagnostic();
        quadRuntime.setPatchClouds(List.of());
        Platforms.get().log(String.format(
                "[embedded-tmesh] fixture=%s nodes=%d arcs=%d patches=%d",
                choice.displayName, tmesh.nodes.size(), tmesh.arcs.size(),
                tmesh.patches.size()));
        return run;
    }

    /**
     * Builds a fresh, uncontracted T-mesh from the loaded surface and hands it to the runtime,
     * dropping any stale diagnostic or patch clouds.
     */
    private void rebuildTMesh() {
        runSceneGraph();
        tmesh = (ArcNetwork) sceneOutput(QuadLayoutNode.sceneOutput(QuadLayoutNode.EMBEDDED));
        contraction = new NetworkContraction(tmesh);
        quadRuntime.setEmbeddedTMesh(tmesh);
        quadRuntime.clearDiagnostic();
        quadRuntime.setPatchClouds(List.of());
    }

    @Override
    public void setControls(List<ControlHint> controls) {
        controls.add(new ControlHint("contract", Keys.C, "contract to a fixed point",
                () -> pendingContract = true));
        controls.add(new ControlHint("toggle-split-density", Keys.R, "toggle refinement-density heat map",
                () -> pendingSplitDensity = true));
        controls.add(new ControlHint("toggle-wireframe", Keys.W, "toggle working-copy triangle outlines",
                () -> pendingWireframe = true));
        controls.add(new ControlHint("toggle-node-markers", Keys.V, "toggle node markers",
                () -> pendingNodeToggle = true));
        controls.add(new ControlHint("reset", Keys.Z, "reset by reloading the current model",
                () -> pendingReset = true));
        super.setControls(controls);
    }

    /**
     * Apply a pending model switch, then any keypress-requested contraction or toggles, on the
     * render thread where the GL context is current.
     */
    @Override
    public void applyPendingModel() {
        super.applyPendingModel();
        if (pendingContract) {
            pendingContract = false;
            applyContract();
        }
        if (pendingNodeToggle) {
            pendingNodeToggle = false;
            quadRuntime.showEmbeddedNodes = !quadRuntime.showEmbeddedNodes;
            Platforms.get().log("[nodes] markers " + (quadRuntime.showEmbeddedNodes ? "on" : "off"));
        }
        if (pendingReset) {
            pendingReset = false;
            requestModelLoad(currentModel());
        }
        if (pendingWireframe) {
            pendingWireframe = false;
            if (quadRuntime.showCopyWireframe) {
                quadRuntime.setCopyWireframe(null);
                Platforms.get().log("[wireframe] outlines off");
            } else {
                quadRuntime.setCopyWireframe(tmesh.topology.copy);
                Platforms.get().log("[wireframe] outlines on: copy V="
                        + tmesh.topology.copy.vertexCount() + " F="
                        + tmesh.topology.copy.faceCount());
            }
        }
        if (pendingSplitDensity) {
            pendingSplitDensity = false;
            setLook(looks.isPicked(SPLIT_DENSITY_LOOK) ? DEFAULT_LOOK : SPLIT_DENSITY_LOOK, headlight);
        }
    }

    /**
     * The model scene's looks, then the refinement-density heat map R toggles.
     *
     * @param looks the scene's looks
     */
    @Override
    public void setLooks(List<SurfaceLook> looks) {
        super.setLooks(looks);
        looks.add(new SurfaceLook(SPLIT_DENSITY_LOOK, "how many times the contraction doubled each source triangle", Light.SOFT_CLAY, this::splitDensity));
    }

    /**
     * Contracts to a fixed point by stepping {@code contractStep} — the same operator order as
     * {@code contract()}. The flank validation throws at the first tear, and that diagnostic is
     * displayed rather than re-derived; the graph debugger (ctrl+D, then shift+enter) stops just before it.
     */
    private void applyContract() {
        if (contraction.collapseArc.collapsingArcId != ArcNetwork.NONE) {
            Platforms.get().log("[contract] skipped: a collapse is mid-drag; reset with Z");
            return;
        }
        try {
            tmesh.labelPatchCovers();
            while (contraction.contractStep() != null) {
                // Each step validates; the fixed point ends the loop.
            }
            contraction.conform();
            tmesh = contraction.recarve(halfEdgeMesh);
            contraction = new NetworkContraction(tmesh);
            quadRuntime.setEmbeddedTMesh(tmesh);
        } catch (ArrangementDiagnosticException failure) {
            quadRuntime.setEmbeddedTMesh(tmesh);
            displayDiagnostic(failure);
        } catch (IllegalStateException failure) {
            quadRuntime.setEmbeddedTMesh(tmesh);
            Platforms.get().log("[contract] failed: " + failure.getMessage());
        }
    }

    /**
     * Draws an embedded T-mesh the graph debugger reached, with a stepped contraction's drag or
     * touched patches over it; the scene's toggles and C then work on it.
     *
     * @param value   a node's output, or the in-flight value of a node being stepped inside
     * @param overlay a {@link ArrangementDiagnostic} drag, the operator's
     *                {@link NetworkContractionNode.UpdatedPatches}, or {@code null}
     * @return whether the value was drawn
     */
    @Override
    public boolean showGraphValue(Object value, Object overlay) {
        if (!(value instanceof ArcNetwork network)) {
            return super.showGraphValue(value, overlay);
        }
        if (tmesh != network) {
            tmesh = network;
            contraction = new NetworkContraction(network);
        }
        halfEdgeMesh = network.topology.copy;
        runtime.upload(halfEdgeMesh);
        quadRuntime.setEmbeddedTMesh(network);
        quadRuntime.clearDiagnostic();
        quadRuntime.setPatchClouds(List.of());
        if (overlay instanceof ArrangementDiagnostic drag) {
            showStepDiagnostic(drag);
        } else if (overlay instanceof NetworkContractionNode.UpdatedPatches updated) {
            quadRuntime.showPatchCovers(network, updated.patchIds());
            Platforms.get().log("[step] updated patches " + updated.patchIds() + " coloured "
                    + MeshOverlayRuntime.GROUP_PALETTE_ORDER);
        }
        return true;
    }

    /**
     * Draws a failure's geometry groups when it carries them, spotlighting the orbit on them.
     *
     * @param failure what the debugger's failed move threw
     */
    @Override
    public void showGraphFailure(Exception failure) {
        if (failure instanceof ArrangementDiagnosticException diagnostic) {
            displayDiagnostic(diagnostic);
        }
    }

    /**
     * Resolves one diagnostic's id-space groups against the working copy and hands the
     * world-space clouds and polylines to the runtime.
     *
     * @param diagnostic groups to show
     * @return the face-group centre clouds, for framing
     */
    private List<PackedPoints> showStepDiagnostic(ArrangementDiagnostic diagnostic) {
        HalfEdgeMesh copy = tmesh.topology.copy;
        List<PackedPoints> faceGroupCenters = diagnostic.faceGroupCenters(copy);
        quadRuntime.setDiagnostic(faceGroupCenters, diagnostic.markerGroupPositions(copy),
                diagnostic.pathPolylines(copy));
        return faceGroupCenters;
    }

    /**
     * Shows a failure's geometry groups: uploads them, spotlights the orbit on the face groups,
     * and logs the message with the group-to-colour key.
     *
     * @param failure failure whose payload is shown
     */
    private void displayDiagnostic(ArrangementDiagnosticException failure) {
        focusOrbitOn(showStepDiagnostic(failure.diagnostic));
        Platforms.get().log("[diagnostic] " + failure.getMessage());
        Platforms.get().log("[diagnostic] " + failure.diagnostic.describeGroups()
                + "; palette " + MeshOverlayRuntime.GROUP_PALETTE_ORDER);
    }

    /**
     * How many times the contraction doubled each source triangle, per vertex: the larger count of
     * the triangles round it, the field the split-density look shows.
     *
     * <p>Refinement never leaves a source triangle, so this is where the splits landed.
     * The scale counts doublings because the tail spans four orders of magnitude.
     *
     * @return doublings per active vertex of the source mesh
     */
    private float[] splitDensity() {
        int sourceFaceCount = halfEdgeMesh.faceCount();
        Map<Integer, Integer> denseByVertexId = new HashMap<>(halfEdgeMesh.vertexCount() * 2);
        for (int dense = 0; dense < halfEdgeMesh.vertexCount(); dense++) {
            denseByVertexId.put(halfEdgeMesh.vertexIdAt(dense), dense);
        }
        float[] childrenByVertex = new float[halfEdgeMesh.vertexCount()];
        int[] childrenByFace = new int[sourceFaceCount];
        int worstFace = 0;
        for (int sourceFace = 0; sourceFace < sourceFaceCount; sourceFace++) {
            childrenByFace[sourceFace] = tmesh.topology.copyFacesBySourceFace.get(sourceFace).size();
            if (childrenByFace[sourceFace] > childrenByFace[worstFace]) {
                worstFace = sourceFace;
            }
            int faceId = halfEdgeMesh.faceIdAt(sourceFace);
            float doublings = (float) (Math.log(childrenByFace[sourceFace]) / Math.log(2));
            for (int corner = 0; corner < HalfEdgeMesh.TRIANGLE_CORNERS; corner++) {
                Integer dense = denseByVertexId.get(halfEdgeMesh.faceVertexAt(faceId, corner));
                if (dense != null) {
                    childrenByVertex[dense] = Math.max(childrenByVertex[dense], doublings);
                }
            }
        }
        return childrenByVertex;
    }

    @Override
    public void renderScene() {
        camera.resetView();
        if (!quadRuntime.showIsoLines) {
            quadRuntime.render(camera);
        }
        quadRuntime.renderOverlays(camera);
        quadRuntime.renderHighlights(camera);
    }
}
