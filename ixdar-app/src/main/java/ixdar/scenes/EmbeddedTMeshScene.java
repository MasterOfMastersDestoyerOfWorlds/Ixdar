package ixdar.scenes;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.graphics.render.model.QuadLayoutRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.model.ControlHint;
import ixdar.scenes.model.ModelScene;

/**
 * Debug view of an embedded T-mesh: arcs as edge paths, positive orange and
 * zero red, nodes as spheres. {@code C} contracts to a fixed point; {@code M}
 * toggles the folded-patch view.
 *
 * <p>
 * See also: LCBK19 Figure 9
 */
@SceneAnnotation(id = "embedded-tmesh")
public class EmbeddedTMeshScene extends ModelScene {

    /** Whether a full contraction (all three operators to a fixed point) was requested by keypress. */
    public volatile boolean pendingContract;

    /** Whether the folded-patch magenta view was toggled by keypress. */
    public volatile boolean pendingFoldFlip;

    /** Whether the refinement-density heat map was toggled by keypress. */
    public volatile boolean pendingSplitDensity;

    /** Whether the working copy's triangle outlines were toggled by keypress. */
    public volatile boolean pendingWireframe;

    /** How many single contraction steps were requested by keypress but not yet applied. */
    public volatile int pendingContractSteps;

    /** The angle to stop motorcycle crashes at. */
    public double alphaDegrees = 15;

    private QuadLayoutRuntime quadRuntime;
    private EmbeddedTMesh tmesh;

    /**
     * Default constructor wired by the scene annotation processor.
     */
    public EmbeddedTMeshScene() {
        super();
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
     * Load {@code path}, then build and contract the embedded T-mesh from the loaded surface.
     *
     * @param path mesh file path to load
     * @throws IOException if the mesh file cannot be read
     */
    @Override
    public void loadModel(String path) throws IOException {
        super.loadModel(path);
        QuadLayoutEngine engine = new QuadLayoutEngine(
                halfEdgeMesh, (float) Math.toRadians(alphaDegrees));
        tmesh = engine.buildTMesh();
        quadRuntime.setEmbeddedTMesh(tmesh);
        Platforms.get().log(String.format(
                "[embedded-tmesh] source=%s nodes=%d arcs=%d patches=%d",
                offPath, tmesh.nodes.size(), tmesh.arcs.size(), tmesh.patches.size()));
    }

    @Override
    public void setControls() {
        controls.add(new ControlHint(Keys.C, "C", "contract to a fixed point",
                () -> pendingContract = true));
        controls.add(new ControlHint(Keys.R, "R", "toggle refinement-density heat map",
                () -> pendingSplitDensity = true));
        controls.add(new ControlHint(Keys.N, "N", "advance one contraction step",
                () -> pendingContractSteps++));
        controls.add(new ControlHint(Keys.W, "W", "toggle working-copy triangle outlines",
                () -> pendingWireframe = true));
        super.setControls();
    }

    /**
     * Apply a pending model switch, then any keypress-requested contraction or fold-flip toggle,
     * on the render thread where the GL context is current.
     */
    @Override
    public void applyPendingModel() {
        super.applyPendingModel();
        if (pendingContract) {
            pendingContract = false;
            tmesh.contract();
            tmesh.conform();
            tmesh = tmesh.recarve(halfEdgeMesh);
            quadRuntime.setEmbeddedTMesh(tmesh);
            if (quadRuntime.showCopyWireframe) {
                quadRuntime.setCopyWireframe(tmesh.topology.copy);
            }
        }
        while (pendingContractSteps > 0) {
            pendingContractSteps--;
            String applied = tmesh.contractStep();
            quadRuntime.setEmbeddedTMesh(tmesh);
            if (quadRuntime.showCopyWireframe) {
                quadRuntime.setCopyWireframe(tmesh.topology.copy);
            }
            Platforms.get().log("[step] " + (applied == null ? "fixed point reached" : applied));
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
