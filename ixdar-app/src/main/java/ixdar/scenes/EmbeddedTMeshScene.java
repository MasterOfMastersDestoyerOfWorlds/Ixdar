package ixdar.scenes;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.embedding.ArrangementDiagnosticException;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.embedding.fixtures.FanCollapseFixture;
import ixdar.geometry.mesh.quadlayout.embedding.fixtures.LayoutFixture;
import ixdar.geometry.mesh.quadlayout.embedding.fixtures.MergedCellSlotFixture;
import ixdar.geometry.mesh.quadlayout.embedding.fixtures.LoopCollapseFixture;
import ixdar.geometry.mesh.quadlayout.embedding.fixtures.PinchedCoverFixture;
import ixdar.geometry.mesh.quadlayout.embedding.fixtures.TwinCellFixture;
import ixdar.geometry.mesh.quadlayout.embedding.fixtures.PlaneLayoutFixture;
import ixdar.geometry.mesh.quadlayout.embedding.fixtures.ScaledTorusLayoutFixture;
import ixdar.geometry.mesh.quadlayout.embedding.fixtures.SliverPinchFixture;
import ixdar.geometry.mesh.quadlayout.embedding.fixtures.StackedZeroRowTorusFixture;
import ixdar.geometry.mesh.quadlayout.embedding.fixtures.TorusLayoutFixture;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.graphics.render.model.QuadLayoutRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.model.ControlHint;
import ixdar.scenes.model.ModelScene;

/**
 * Debug view of an embedded T-mesh: arcs as edge paths, positive orange and
 * zero red, nodes as spheres. {@code C} contracts to a fixed point, {@code N}
 * steps one operator, {@code D} steps one drag of a zero-arc collapse.
 *
 * <p>
 * See also: LCBK19 Figure 9
 */
@SceneAnnotation(id = "embedded-tmesh")
public class EmbeddedTMeshScene extends ModelScene {

    /** Refinement of the scaled torus fixture offered in the model menu. */
    private static final int DENSE_FIXTURE_SCALE = 4;

    /** Whether a full contraction (all three operators to a fixed point) was requested by keypress. */
    public volatile boolean pendingContract;

    /** Whether the refinement-density heat map was toggled by keypress. */
    public volatile boolean pendingSplitDensity;

    /** Whether the working copy's triangle outlines were toggled by keypress. */
    public volatile boolean pendingWireframe;

    /** How many single contraction steps were requested by keypress but not yet applied. */
    public volatile int pendingContractSteps;

    /** How many single drag steps were requested by keypress but not yet applied. */
    public volatile int pendingDragSteps;

    /** Whether hiding or showing the node marker spheres was requested by keypress. */
    public volatile boolean pendingNodeToggle;

    /** Whether rebuilding the uncontracted T-mesh from the source surface was requested. */
    public volatile boolean pendingReset;

    /** Whether replaying the contraction to just before the recorded failure was requested. */
    public volatile boolean pendingRewind;

    /** The angle to stop motorcycle crashes at. */
    public double alphaDegrees = 15;

    /** Contraction operators applied since the model was loaded, the replay currency of B. */
    public int contractOpsSinceLoad;

    /**
     * Operator count at the last displayed failure, so B can replay to just before
     * it; {@link ArcNetwork#NONE} before any failure.
     */
    public int failedContractOps = ArcNetwork.NONE;

    private QuadLayoutRuntime quadRuntime;
    private ArcNetwork tmesh;

    /** Contraction operators bound to {@link #tmesh}; rebound whenever it is replaced. */
    private NetworkContraction contraction;

    /**
     * Registers the hand-authored layout fixtures alongside the mesh catalog.
     */
    public EmbeddedTMeshScene() {
        super();
        registerFixture(new FanCollapseFixture());
        registerFixture(new SliverPinchFixture());
        registerFixture(new MergedCellSlotFixture());
        registerFixture(new PinchedCoverFixture());
        registerFixture(new TwinCellFixture());
        registerFixture(new LoopCollapseFixture());
        registerFixture(new TorusLayoutFixture());
        registerFixture(new StackedZeroRowTorusFixture());
        registerFixture(new ScaledTorusLayoutFixture(DENSE_FIXTURE_SCALE));
        registerFixture(new PlaneLayoutFixture());
    }

    @Override
    public HalfEdgeMeshRuntime createRuntime() {
        quadRuntime = new QuadLayoutRuntime();
        runtime = quadRuntime;
        return runtime;
    }

    @Override
    public String windowTitle() {
        return "Ixdar : Embedded T-Mesh";
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
        contractOpsSinceLoad = 0;
        rebuildTMesh();
        Platforms.get().log(String.format(
                "[embedded-tmesh] source=%s nodes=%d arcs=%d patches=%d",
                offPath, tmesh.nodes.size(), tmesh.arcs.size(), tmesh.patches.size()));
    }

    /**
     * Load a fixture's hand-authored T-mesh directly, skipping the pipeline.
     *
     * @param fixture registered fixture to build and show
     * @return the freshly built T-mesh
     */
    @Override
    public ArcNetwork loadFixture(LayoutFixture fixture) {
        tmesh = super.loadFixture(fixture);
        contraction = new NetworkContraction(tmesh);
        contractOpsSinceLoad = 0;
        quadRuntime.setEmbeddedTMesh(tmesh);
        quadRuntime.clearDiagnostic();
        quadRuntime.setPatchClouds(List.of(), new float[0]);
        Platforms.get().log(String.format(
                "[embedded-tmesh] fixture=%s nodes=%d arcs=%d patches=%d",
                fixture.displayName(), tmesh.nodes.size(), tmesh.arcs.size(),
                tmesh.patches.size()));
        return tmesh;
    }

    /**
     * Builds a fresh, uncontracted T-mesh from the loaded surface and hands it to the runtime,
     * dropping any stale diagnostic or patch clouds.
     */
    private void rebuildTMesh() {
        QuadLayoutEngine engine = new QuadLayoutEngine(
                halfEdgeMesh, (float) Math.toRadians(alphaDegrees));
        tmesh = engine.buildTMesh();
        contraction = new NetworkContraction(tmesh);
        quadRuntime.setEmbeddedTMesh(tmesh);
        quadRuntime.clearDiagnostic();
        quadRuntime.setPatchClouds(List.of(), new float[0]);
    }

    @Override
    public void setControls() {
        controls.add(new ControlHint(Keys.C, "C", "contract to a fixed point",
                () -> pendingContract = true));
        controls.add(new ControlHint(Keys.R, "R", "toggle refinement-density heat map",
                () -> pendingSplitDensity = true));
        controls.add(new ControlHint(Keys.N, "N", "advance one contraction step",
                () -> pendingContractSteps++));
        controls.add(new ControlHint(Keys.D, "D", "advance one drag of a zero-arc collapse",
                () -> pendingDragSteps++));
        controls.add(new ControlHint(Keys.W, "W", "toggle working-copy triangle outlines",
                () -> pendingWireframe = true));
        controls.add(new ControlHint(Keys.V, "V", "toggle node markers",
                () -> pendingNodeToggle = true));
        controls.add(new ControlHint(Keys.Z, "Z", "reset by reloading the current model",
                () -> pendingReset = true));
        controls.add(new ControlHint(Keys.B, "B", "rewind to just before the recorded failure",
                () -> pendingRewind = true));
        super.setControls();
    }

    /**
     * Apply a pending model switch, then any keypress-requested contraction, stepping or
     * toggles, on the render thread where the GL context is current.
     */
    @Override
    public void applyPendingModel() {
        super.applyPendingModel();
        if (pendingContract) {
            pendingContract = false;
            applyContract();
        }
        while (pendingContractSteps > 0) {
            pendingContractSteps--;
            applyContractStep();
        }
        while (pendingDragSteps > 0) {
            pendingDragSteps--;
            applyDragStep();
        }
        if (pendingNodeToggle) {
            pendingNodeToggle = false;
            quadRuntime.showEmbeddedNodes = !quadRuntime.showEmbeddedNodes;
            Platforms.get().log("[nodes] markers " + (quadRuntime.showEmbeddedNodes ? "on" : "off"));
        }
        if (pendingReset) {
            pendingReset = false;
            requestModelLoad(currentModel().path);
        }
        if (pendingRewind) {
            pendingRewind = false;
            applyRewind();
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
            if (quadRuntime.hasPerVertexScalar()) {
                quadRuntime.clearPerVertexScalar();
                quadRuntime.setShaderMode(HalfEdgeMeshRuntime.ShaderMode.LAMBERT);
                Platforms.get().log("[refinement] density map off");
            } else {
                showSplitDensity();
            }
        }
    }

    /**
     * Contracts to a fixed point by stepping {@code contractStep} — the same operator order as
     * {@code contract()} — counting operators so a failure is replayable with B. The flank
     * validation throws at the first tear, and that diagnostic is displayed rather than
     * re-derived.
     */
    private void applyContract() {
        if (contraction.collapseArc.collapsingArcId != ArcNetwork.NONE) {
            Platforms.get().log("[contract] skipped: a collapse is mid-drag; finish it with D"
                    + " or reset with Z");
            return;
        }
        try {
            tmesh.labelPatchCovers();
            while (contraction.contractStep() != null) {
                contractOpsSinceLoad++;
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
     * Applies one contraction operator and shows the covers of the patches it touched.
     */
    private void applyContractStep() {
        if (contraction.collapseArc.collapsingArcId != ArcNetwork.NONE) {
            Platforms.get().log("[step] skipped: a collapse is mid-drag; finish it with D"
                    + " or reset with Z");
            return;
        }
        int arcCollapsesBefore = contraction.arcCollapseCount;
        try {
            String applied = contraction.contractStep();
            if (applied != null) {
                contractOpsSinceLoad++;
            }
            quadRuntime.setEmbeddedTMesh(tmesh);
            List<Integer> updated = contraction.stepUpdatedPatches(arcCollapsesBefore);
            quadRuntime.showPatchCovers(tmesh, updated);
            Platforms.get().log("[step] " + (applied == null ? "fixed point reached" : applied)
                    + " | updated patches " + updated
                    + " coloured " + QuadLayoutRuntime.GROUP_PALETTE_ORDER);
        } catch (ArrangementDiagnosticException failure) {
            quadRuntime.setEmbeddedTMesh(tmesh);
            displayDiagnostic(failure);
        } catch (IllegalStateException failure) {
            quadRuntime.setEmbeddedTMesh(tmesh);
            Platforms.get().log("[step] failed: " + failure.getMessage());
        }
    }

    /**
     * Steps a zero-arc collapse one drag per press: begin, one fan arc per drag, then the node
     * merge, drawing the in-flight state through the shared diagnostic overlay. See also:
     * LCBK19 Figure 9 e-f
     */
    private void applyDragStep() {
        try {
            if (contraction.collapseArc.collapsingArcId == ArcNetwork.NONE) {
                if (tmesh.topology.patchByCopyFace.length == 0) {
                    tmesh.labelPatchCovers();
                }
                int arcId = contraction.collapseArc.mostContendedArc();
                if (arcId == ArcNetwork.NONE) {
                    Platforms.get().log("[drag] no collapsible zero arc remains");
                    return;
                }
                contraction.collapseArc.beginCollapse(arcId);
                Platforms.get().log("[drag] began collapse of arc " + arcId + "; fan of "
                        + contraction.collapseArc.fan.size() + " arcs waits on node "
                        + contraction.collapseArc.movedNodeId);
                quadRuntime.setDiagnostic(tmesh.topology.copy, contraction.collapseArc.stepDiagnostic());
                quadRuntime.capDiagnosticRegion(quadRuntime.diagnosticRegionRadius);
            } else if (contraction.collapseArc.dragNextArc()) {
                quadRuntime.setEmbeddedTMesh(tmesh);
                quadRuntime.setDiagnostic(tmesh.topology.copy, contraction.collapseArc.stepDiagnostic());
                quadRuntime.capDiagnosticRegion(quadRuntime.diagnosticRegionRadius);
                Platforms.get().log("[drag] dragged arc " + contraction.collapseArc.lastDraggedArcId
                        + " onto vertex " + contraction.collapseArc.targetVertex);
            } else {
                int collapsedArcId = contraction.collapseArc.collapsingArcId;
                contraction.collapseArc.finishCollapse();
                contraction.arcCollapseCount++;
                contractOpsSinceLoad++;
                quadRuntime.setEmbeddedTMesh(tmesh);
                quadRuntime.clearDiagnostic();
                Platforms.get().log("[drag] collapse finished; " + contraction.arcCollapseCount
                        + " arc collapses so far");
                // Stepping is the debug path: the same check contractStep runs — without it a
                // stepped collapse sails silently past the tear the C run failed on.
                ArrangementDiagnosticException tear = tmesh.flankTearFailure(
                        "arc collapse " + collapsedArcId);
                if (tear != null) {
                    // The torn collapse is the failing operator; it must not count, so B
                    // replays to just before it.
                    contractOpsSinceLoad--;
                    throw tear;
                }
            }
        } catch (ArrangementDiagnosticException failure) {
            pendingDragSteps = 0;
            quadRuntime.setEmbeddedTMesh(tmesh);
            displayDiagnostic(failure);
        }
    }

    /**
     * Shows a failure's geometry groups: uploads them, spotlights the orbit on the face groups,
     * and logs the message with the group-to-colour key.
     *
     * @param failure failure whose payload is shown
     */
    private void displayDiagnostic(ArrangementDiagnosticException failure) {
        failedContractOps = contractOpsSinceLoad;
        quadRuntime.setDiagnostic(tmesh.topology.copy, failure.diagnostic);
        float regionRadius = focusOrbitOn(quadRuntime.diagnosticFaceGroupCenters);
        quadRuntime.capDiagnosticRegion(regionRadius);
        Platforms.get().log("[diagnostic] " + failure.getMessage());
        Platforms.get().log("[diagnostic] " + failure.diagnostic.describeGroups()
                + "; palette " + QuadLayoutRuntime.GROUP_PALETTE_ORDER);
        Platforms.get().log("[diagnostic] failed after " + failedContractOps + " operators; press"
                + " B to rewind to just before this one, then D (arc collapse) or N to step it");
    }

    /**
     * Reloads the current model and replays the contraction to just before the operator the
     * last failure recorded, leaving D or N to step through the failure itself. Repeatable:
     * the recorded count survives the rewind, and so does the camera pose.
     */
    private void applyRewind() {
        if (failedContractOps == ArcNetwork.NONE) {
            Platforms.get().log("[rewind] no recorded failure; press C first");
            return;
        }
        boolean reloaded = preserveOrbit(() -> {
            try {
                loadModelOrFixture(currentModel().path);
                return true;
            } catch (IOException failure) {
                Platforms.get().log("[rewind] reload failed: " + failure.getMessage());
                return false;
            }
        });
        if (!reloaded) {
            return;
        }
        tmesh.labelPatchCovers();
        for (int op = 0; op < failedContractOps; op++) {
            contraction.contractStep();
        }
        contractOpsSinceLoad = failedContractOps;
        quadRuntime.setEmbeddedTMesh(tmesh);
        Platforms.get().log("[rewind] replayed " + failedContractOps + " operators; the next one"
                + " fails — step it with D (arc collapse) or N");
    }

    /**
     * Paints each source triangle by how many times the contraction doubled it, and logs
     * that distribution with the worst offender.
     *
     * <p>Refinement never leaves a source triangle, so this is where the splits landed.
     * The scale counts doublings because the tail spans four orders of magnitude.
     */
    private void showSplitDensity() {
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
        quadRuntime.setShaderMode(HalfEdgeMeshRuntime.ShaderMode.SCALAR);
        quadRuntime.setPerVertexScalar(childrenByVertex, 0f, Float.NaN);
        Platforms.get().log("[refinement] density map on");
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
