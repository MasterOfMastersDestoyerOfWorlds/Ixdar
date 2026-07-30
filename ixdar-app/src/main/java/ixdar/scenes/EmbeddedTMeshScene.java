package ixdar.scenes;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.PatchRectangleMap;
import ixdar.geometry.mesh.quadlayout.embedding.PatchRegionMapper;
import ixdar.geometry.mesh.quadlayout.embedding.PatchRegions;
import ixdar.geometry.mesh.quadlayout.embedding.ThreeConnectivityRefinement;
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

    /** How many patches to map between fold-check progress log lines. */
    private static final int PATCH_PROGRESS_INTERVAL = 64;

    /** Bit shift packing a dense edge's low vertex into an undirected-edge key. */
    private static final int FOLD_EDGE_KEY_SHIFT = 32;

    /** Low-word mask recovering a dense edge's high vertex from its key. */
    private static final long FOLD_EDGE_KEY_MASK = 0xFFFFFFFFL;
 
    /** Corners of a source triangle. */
    private static final int TRIANGLE_CORNERS = 3;

    /** Powers-of-two buckets the refinement-density histogram reports. */
    private static final int DENSITY_BUCKETS = 16;

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
        tmesh = engine.buildContractedTMesh();
        quadRuntime.setEmbeddedTMesh(tmesh);
        Platforms.get().log(String.format(
                "[embedded-tmesh] source=%s nodes=%d arcs=%d patches=%d",
                offPath, tmesh.nodes.size(), tmesh.arcs.size(), tmesh.patches.size()));
    }

    @Override
    public void setControls() {
        controls.add(new ControlHint(Keys.C, "C", "contract to a fixed point",
                () -> pendingContract = true));
        controls.add(new ControlHint(Keys.M, "M", "toggle fold-flip view",
                () -> pendingFoldFlip = true));
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
            quadRuntime.setEmbeddedTMesh(tmesh);
            Platforms.get().log("[embedded-tmesh] contracted to fixed point: "
                    + tmesh.arcCollapseCount + " collapse(s), "
                    + tmesh.patchSplitCount + " split(s), "
                    + tmesh.patchCollapseCount + " patch-collapse(s), copy V="
                    + tmesh.topology.copy.vertexCount());
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
        if (pendingFoldFlip) {
            pendingFoldFlip = false;
            if (quadRuntime.showIsoLines) {
                quadRuntime.showIsoLines = false;
                Platforms.get().log("[foldcheck] flip view off");
            } else {
                try {
                    showFoldFlips();
                } catch (IllegalStateException notReady) {
                    Platforms.get().log("[foldcheck] cannot show flips (contract to a fixed point"
                            + " first with C): " + notReady.getMessage());
                }
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
        HalfEdgeMesh copy = tmesh.topology.copy;
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
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                Integer dense = denseByVertexId.get(halfEdgeMesh.faceVertexAt(faceId, corner));
                if (dense != null) {
                    childrenByVertex[dense] = Math.max(childrenByVertex[dense], doublings);
                }
            }
        }
        int[] histogram = new int[DENSITY_BUCKETS];
        for (int children : childrenByFace) {
            int bucket = 0;
            while (bucket < DENSITY_BUCKETS - 1 && children > (1 << bucket)) {
                bucket++;
            }
            histogram[bucket]++;
        }
        StringBuilder report = new StringBuilder("[refinement] source faces by copy-face count:");
        for (int bucket = 0; bucket < DENSITY_BUCKETS; bucket++) {
            if (histogram[bucket] > 0) {
                report.append(" <=").append(1 << bucket).append(':').append(histogram[bucket]);
            }
        }
        report.append(" worstSourceFace=").append(worstFace)
                .append(" children=").append(childrenByFace[worstFace])
                .append(" copyV=").append(copy.vertexCount())
                .append(" sourceV=").append(tmesh.topology.originalVertexBound);
        Platforms.get().log(report.toString());
        quadRuntime.setShaderMode(HalfEdgeMeshRuntime.ShaderMode.SCALAR);
        quadRuntime.setPerVertexScalar(childrenByVertex, 0f, Float.NaN);
        Platforms.get().log("[refinement] density map on");
    }

    /**
     * Judges the contracted layout and shows it: refines to 3-connectivity, builds
     * patch regions, maps every patch, logs the fold count, and paints each folded
     * patch magenta on the iso-surface. Only meaningful once no zero-patches
     * remain.
     */
    private void showFoldFlips() {
        int sameSidePatchArcs = 0;
        for (EmbeddedArc arc : tmesh.arcs) {
            if (arc.alive && arc.leftPatchId == arc.rightPatchId) {
                sameSidePatchArcs++;
            }
        }
        try {
            new PatchRegions(tmesh).build();
            Platforms.get().log("[foldcheck] pre-refine: regions OK");
        } catch (IllegalStateException tornBeforeRefine) {
            Platforms.get().log("[foldcheck] pre-refine: TORN: " + tornBeforeRefine.getMessage());
        }
        Platforms.get().log("[foldcheck] refining to 3-connectivity (a large mesh takes a minute)...");
        int chords = new ThreeConnectivityRefinement(tmesh).refine();
        Platforms.get().log("[foldcheck] refined chords=" + chords + " faces="
                + tmesh.topology.copy.faceCount() + "; mapping patches...");
        PatchRegions regions;
        try {
            regions = new PatchRegions(tmesh).build();
        } catch (IllegalStateException torn) {
            StringBuilder patchArcs = new StringBuilder();
            for (EmbeddedPatch patch : tmesh.patches) {
                if (!patch.alive) {
                    continue;
                }
                Set<Integer> boundary = new TreeSet<>();
                for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
                    boundary.addAll(patch.sideArcIds.get(side));
                }
                patchArcs.append(" P").append(patch.patchId).append(boundary);
            }
            Platforms.get().log("[foldcheck] TORN after refine (chords=" + chords
                    + " sameSidePatchArcs=" + sameSidePatchArcs + "): " + torn.getMessage()
                    + " | live patches:" + patchArcs);
            return;
        }
        PatchRegionMapper mapper = new PatchRegionMapper(tmesh, regions);
        HalfEdgeMesh copy = tmesh.topology.copy;
        int faceCount = copy.faceCount();
        Map<Integer, Integer> activeByFaceId = new HashMap<>();
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            activeByFaceId.put(copy.faceIdAt(activeFace), activeFace);
        }
        double[] cornerU = new double[faceCount * PatchRegionMapper.TRIANGLE_CORNERS];
        double[] cornerV = new double[faceCount * PatchRegionMapper.TRIANGLE_CORNERS];
        boolean[] faceFlipped = new boolean[faceCount];
        int mapped = 0;
        int folded = 0;
        int flippedTotal = 0;
        StringBuilder foldedIds = new StringBuilder();
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            mapped++;
            if (mapped % PATCH_PROGRESS_INTERVAL == 0) {
                Platforms.get().log("[foldcheck] mapped " + mapped + " patches...");
            }
            PatchRectangleMap map = mapper.mapPatch(patch.patchId);
            int flipped = map.flippedTriangleCount();
            if (flipped > 0) {
                folded++;
                flippedTotal += flipped;
                foldedIds.append(' ').append(patch.patchId).append('(').append(flipped).append(')');
                Platforms.get().log("[foldcheck] fold P" + patch.patchId + " flips=" + flipped
                        + diagnosePatchFold(map));
            }
            List<Integer> regionFaces = regions.copyFacesByPatch.get(patch.patchId);
            for (int faceIndex = 0; faceIndex < regionFaces.size(); faceIndex++) {
                Integer activeFace = activeByFaceId.get(regionFaces.get(faceIndex));
                if (activeFace == null) {
                    continue;
                }
                int[] triangle = map.triangles[faceIndex];
                faceFlipped[activeFace] = flipped > 0;
                int base = activeFace * PatchRegionMapper.TRIANGLE_CORNERS;
                for (int corner = 0; corner < PatchRegionMapper.TRIANGLE_CORNERS; corner++) {
                    cornerU[base + corner] = map.rectangleU[triangle[corner]];
                    cornerV[base + corner] = map.rectangleV[triangle[corner]];
                }
            }
        }
        quadRuntime.uploadPatchParametrization(copy, cornerU, cornerV, faceFlipped);
        quadRuntime.showTraces = false;
        quadRuntime.showIsoLines = true;
        Platforms.get().log("[foldcheck] flip-surface uploaded isoIdx=" + quadRuntime.isoSurfaceIndexCount
                + " showIsoLines=" + quadRuntime.showIsoLines);
        Platforms.get().log("[foldcheck] regions OK: " + mapped + " patches, chords=" + chords
                + " sameSidePatchArcs=" + sameSidePatchArcs + " folded=" + folded
                + (folded == 0 ? " (all fold-free)"
                        : " flippedTriangles=" + flippedTotal
                                + " patches[" + foldedIds.toString().trim() + "]"));
    }

    /**
     * Tests Tutte's fold-free preconditions on a folded patch's region: a repeated
     * boundary-loop vertex is a non-simple boundary pinned to two rectangle spots,
     * a vertex with more than two boundary edges is a non-manifold pinch, and an
     * Euler characteristic other than one means the region is not a disk.
     *
     * @param map the folded patch's solved rectangle map
     * @return a compact report of which preconditions the region violates
     */
    private static String diagnosePatchFold(PatchRectangleMap map) {
        int vertexCount = map.positions.length;
        int[] boundaryLoopCount = new int[vertexCount];
        for (int dense : map.boundaryLoop) {
            boundaryLoopCount[dense]++;
        }
        int repeatedBoundary = 0;
        for (int count : boundaryLoopCount) {
            if (count > 1) {
                repeatedBoundary++;
            }
        }
        Map<Long, Integer> edgeUse = new HashMap<>();
        for (int[] triangle : map.triangles) {
            countEdge(edgeUse, triangle[0], triangle[1]);
            countEdge(edgeUse, triangle[1], triangle[2]);
            countEdge(edgeUse, triangle[2], triangle[0]);
        }
        int[] boundaryEdgesAt = new int[vertexCount];
        int nonManifoldEdges = 0;
        for (Map.Entry<Long, Integer> entry : edgeUse.entrySet()) {
            if (entry.getValue() == 1) {
                long key = entry.getKey();
                boundaryEdgesAt[(int) (key >>> FOLD_EDGE_KEY_SHIFT)]++;
                boundaryEdgesAt[(int) (key & FOLD_EDGE_KEY_MASK)]++;
            } else if (entry.getValue() > 2) {
                nonManifoldEdges++;
            }
        }
        int pinchVertices = 0;
        for (int count : boundaryEdgesAt) {
            if (count > 2) {
                pinchVertices++;
            }
        }
        int euler = vertexCount - edgeUse.size() + map.triangles.length;
        return " repeatedBoundaryVerts=" + repeatedBoundary + " pinchVerts=" + pinchVertices
                + " nonManifoldEdges=" + nonManifoldEdges + " euler=" + euler + " (disk=1)"
                + " V=" + vertexCount + " E=" + edgeUse.size() + " F=" + map.triangles.length;
    }

    /**
     * Increments the shared use-count of the undirected dense edge between two
     * vertices.
     *
     * @param edgeUse map from packed undirected edge key to the number of triangles
     *                using it
     * @param first   one dense vertex of the edge
     * @param second  the other dense vertex
     */
    private static void countEdge(Map<Long, Integer> edgeUse, int first, int second) {
        int low = Math.min(first, second);
        int high = Math.max(first, second);
        edgeUse.merge(((long) low << FOLD_EDGE_KEY_SHIFT) | high, 1, Integer::sum);
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
