package ixdar.geometry.mesh.quadlayout.lyon2021;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.Patch;
import ixdar.geometry.mesh.data.PatchDecomposition;
import ixdar.geometry.mesh.data.SemanticPatchDecomposer;
import ixdar.geometry.mesh.quadlayout.tmesh.TArc;
import ixdar.geometry.mesh.quadlayout.tmesh.TMesh;

/**
 * Goal #2: bridge a Lyon 2021 {@link QuadLayout} into the engine's existing
 * {@link SemanticPatchDecomposer.DecompositionDiagnostics} format so
 * {@code PatchRenderer} and {@code MeshNodeViewerScene}'s overlay
 * infrastructure can color-code each layout patch on the underlying
 * triangle mesh.
 *
 * <p><b>Face-to-patch assignment.</b> v1 uses a two-phase heuristic:
 * <ol>
 *   <li>Mark every mesh face crossed by any layout arc as a boundary face
 *       of the patch(es) the arc bounds.</li>
 *   <li>For unmarked (interior) faces, assign by nearest-centroid distance
 *       to each patch's 3D centroid.</li>
 * </ol>
 *
 * <p>Color: golden-ratio HSL hues; triangles get desaturated tints. Skipped
 * mesh faces (no patch reachable) render light grey.
 */
public final class LyonLayoutDecomposer {
    public static final int NUM_3 = 3;
    public static final int NUM_4 = 4;
    public static final float NUM_0 = 0f;
    public static final float NUM_3_2 = 3f;
    public static final double NUM_0_6180339887498949 = 0.6180339887498949;
    public static final float NUM_0_35 = 0.35f;
    public static final float NUM_0_70 = 0.70f;
    public static final float NUM_0_55 = 0.55f;
    public static final float NUM_0_50 = 0.50f;
    public static final int NUM_6 = 6;
    public static final int NUM_5 = 5;
    public static final int NUM_255 = 255;

    private LyonLayoutDecomposer() {}

    /**
     * Build a {@link SemanticPatchDecomposer.DecompositionDiagnostics} from a
     * Lyon layout. The {@code patches} list contains one {@link Patch} per
     * 4-sided + 3-sided layout patch, with face assignments + golden-ratio
     * colors. All feature-edge sets are empty.
     *
     * @param mesh TODO: describe
     * @param tmesh TODO: describe
     * @param layout TODO: describe
     * @return TODO: describe
     */
    public static SemanticPatchDecomposer.DecompositionDiagnostics decompose(
            ArrayMesh mesh, TMesh tmesh, QuadLayout layout) {
        int faceCount = mesh.copyFaceIndices().length / NUM_3;
        int totalPatches = layout.patches().size() + layout.triangles().size();
        int[] faceLabels = new int[faceCount];
        Arrays.fill(faceLabels, -1);

        // Phase 1: walk each layout arc's underlying TArc faces and flag them
        // as "on the boundary of this patch".
        Map<Integer, int[]> patchToBoundaryFaces = new HashMap<>();
        Map<Integer, int[]> trianglePatchBoundaryFaces = new HashMap<>();

        // For each LayoutArc, collect the set of faces it touches.
        // Build a reverse map: arcId -> patches that include it.
        Map<Integer, Set<Integer>> arcToPatchIds = new HashMap<>();
        for (int p = 0; p < layout.patches().size(); p++) {
            QuadLayoutPatch qp = layout.patches().get(p);
            int patchId = p;
            for (int s = 0; s < NUM_4; s++) {
                for (int la : qp.arcsBySide()[s]) {
                    arcToPatchIds.computeIfAbsent(la, k -> new HashSet<>()).add(patchId);
                }
            }
        }
        int triOffset = layout.patches().size();
        for (int t = 0; t < layout.triangles().size(); t++) {
            TrianglePatch tp = layout.triangles().get(t);
            int patchId = triOffset + t;
            for (int s = 0; s < NUM_3; s++) {
                for (int la : tp.arcsBySide()[s]) {
                    arcToPatchIds.computeIfAbsent(la, k -> new HashSet<>()).add(patchId);
                }
            }
        }

        // Walk each LayoutArc → each face → assign to one of the patches
        // that include this arc (we tie-break by lowest patch id).
        for (LayoutArc la : layout.layoutArcs()) {
            Set<Integer> patches = arcToPatchIds.getOrDefault(la.id(), Set.of());
            if (patches.isEmpty()) continue;
            int target = patches.stream().min(Integer::compare).orElse(-1);
            if (target < 0) continue;
            if (la.variant() == LayoutArc.Variant.INTERIOR) {
                for (SplitEdge e : la.interiorPolyline()) {
                    if (e.faceId() >= 0 && e.faceId() < faceCount && faceLabels[e.faceId()] < 0) {
                        faceLabels[e.faceId()] = target;
                    }
                }
            } else {
                TArc tarc = tmesh.arcs().get(la.underlyingTArcId());
                for (int[] cross : tarc.meshFaceCrossings()) {
                    int f = cross[0];
                    if (f >= 0 && f < faceCount && faceLabels[f] < 0) {
                        faceLabels[f] = target;
                    }
                }
            }
        }

        // PATCH-81: Phase 2 — flood-fill from labeled boundary faces, blocked
        // by mesh half-edges any layout arc crosses. Each unlabeled face
        // inherits the label of the boundary face it floods from. This
        // replaces the prior nearest-centroid heuristic which produced
        // visible "blob streaks" across patch boundaries.

        // Build the blocked half-edge set: for each layout arc, walk its
        // underlying TArc's per-step exitEdgeIndex; both the exit half-edge
        // and its twin are blocked (the arc crosses through that mesh edge).
        java.util.HashSet<Integer> blockedHalfEdges = new java.util.HashSet<>();
        for (LayoutArc la : layout.layoutArcs()) {
            if (la.variant() == LayoutArc.Variant.INTERIOR) {
                // INTERIOR arcs traverse face interiors, not edges — but they
                // do cross face-to-face boundaries via SplitEdge transitions.
                // For now we don't block on INTERIOR boundaries; PATCH-77
                // INTERIOR arcs are rare and tiny.
                continue;
            }
            TArc tarc = tmesh.arcs().get(la.underlyingTArcId());
            for (int[] cross : tarc.meshFaceCrossings()) {
                int faceId = cross[0];
                int exitEdge = cross[1];
                if (faceId < 0 || exitEdge < 0 || exitEdge > 2) continue;
                int he = faceId * NUM_3 + exitEdge;
                blockedHalfEdges.add(he);
                int twin = mesh.halfEdgeTwin(he);
                if (twin >= 0) blockedHalfEdges.add(twin);
            }
        }

        // BFS flood-fill from each labeled face.
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        for (int f = 0; f < faceCount; f++) {
            if (faceLabels[f] >= 0) queue.add(f);
        }
        while (!queue.isEmpty()) {
            int f = queue.poll();
            int label = faceLabels[f];
            if (label < 0) continue;
            // Try each of f's 3 half-edges to find unlabeled neighbors.
            for (int c = 0; c < NUM_3; c++) {
                int he = f * NUM_3 + c;
                if (blockedHalfEdges.contains(he)) continue;
                int twin = mesh.halfEdgeTwin(he);
                if (twin < 0) continue;
                int nbr = mesh.halfEdgeFace(twin);
                if (nbr < 0 || nbr >= faceCount) continue;
                if (faceLabels[nbr] >= 0) continue;   // already labeled
                faceLabels[nbr] = label;
                queue.add(nbr);
            }
        }

        // Phase 3 fallback: any face still unlabeled after flood-fill is in
        // a "topology gap" — no layout arc reachable. Fall back to nearest
        // labeled-face label (BFS from labeled faces ignoring blocked edges).
        // Without this, the visual has large grey holes which obscures the
        // patch structure of the rest. Once topology is paper-rectangular
        // (no gaps), this fallback should rarely fire.
        java.util.ArrayDeque<Integer> fallbackQueue = new java.util.ArrayDeque<>();
        for (int f = 0; f < faceCount; f++) {
            if (faceLabels[f] >= 0) fallbackQueue.add(f);
        }
        while (!fallbackQueue.isEmpty()) {
            int f = fallbackQueue.poll();
            int label = faceLabels[f];
            if (label < 0) continue;
            for (int c = 0; c < NUM_3; c++) {
                int he = f * NUM_3 + c;
                int twin = mesh.halfEdgeTwin(he);
                if (twin < 0) continue;
                int nbr = mesh.halfEdgeFace(twin);
                if (nbr < 0 || nbr >= faceCount) continue;
                if (faceLabels[nbr] >= 0) continue;
                faceLabels[nbr] = label;
                fallbackQueue.add(nbr);
            }
        }

        // PATCH-88: remap raw patch IDs to merged-component IDs (Lyon §6 ¶1
        // arc-collapse). Patches connected via q=0 layout arcs share a
        // component; the visual shows the conforming layout, not the raw
        // T-mesh.
        var merge = layout.mergedPatchAssignment();
        int totalComponents = merge.distinctCount();
        int[] rawToComponent = new int[totalPatches];
        for (int i = 0; i < merge.quadPatchToComponent().length; i++) {
            rawToComponent[i] = merge.quadPatchToComponent()[i];
        }
        for (int i = 0; i < merge.trianglePatchToComponent().length; i++) {
            rawToComponent[triOffset + i] = merge.trianglePatchToComponent()[i];
        }
        // Remap face labels in-place.
        for (int f = 0; f < faceCount; f++) {
            int raw = faceLabels[f];
            if (raw >= 0 && raw < rawToComponent.length) {
                faceLabels[f] = rawToComponent[raw];
            }
        }
        // Track which components contain a triangle for color desaturation.
        boolean[] componentHasTriangle = new boolean[totalComponents];
        for (int i = 0; i < merge.trianglePatchToComponent().length; i++) {
            componentHasTriangle[merge.trianglePatchToComponent()[i]] = true;
        }

        // Build Patch records — one per merged component.
        List<List<Integer>> facesPerPatch = new ArrayList<>(totalComponents);
        for (int i = 0; i < totalComponents; i++) facesPerPatch.add(new ArrayList<>());
        for (int f = 0; f < faceCount; f++) {
            int c = faceLabels[f];
            if (c >= 0 && c < totalComponents) facesPerPatch.get(c).add(f);
        }

        List<Patch> outPatches = new ArrayList<>(totalComponents);
        int[] faceIdxArr = mesh.copyFaceIndices();
        float[] positions = mesh.copyPositions();
        for (int c = 0; c < totalComponents; c++) {
            List<Integer> faceList = facesPerPatch.get(c);
            int[] faces = faceList.stream().mapToInt(Integer::intValue).toArray();
            // Vertex indices = unique vertices touched.
            Set<Integer> verts = new HashSet<>();
            for (int f : faces) {
                verts.add(faceIdxArr[f * NUM_3]);
                verts.add(faceIdxArr[f * NUM_3 + 1]);
                verts.add(faceIdxArr[f * NUM_3 + 2]);
            }
            int[] vertexIds = verts.stream().mapToInt(Integer::intValue).sorted().toArray();
            float cx = 0, cy = 0, cz = 0;
            if (vertexIds.length > 0) {
                for (int v : vertexIds) {
                    cx += positions[v * NUM_3];
                    cy += positions[v * NUM_3 + 1];
                    cz += positions[v * NUM_3 + 2];
                }
                cx /= vertexIds.length; cy /= vertexIds.length; cz /= vertexIds.length;
            }
            String color = colorForPatch(c, componentHasTriangle[c]);
            outPatches.add(new Patch(c, vertexIds, faces, /*branchId*/ c,
                    new float[]{cx, cy, cz}, /*curvatureMean*/ NUM_0, color));
        }

        PatchDecomposition decomposition =
                new PatchDecomposition(mesh.vertexCount(), outPatches);
        return new SemanticPatchDecomposer.DecompositionDiagnostics(
                decomposition,
                /*facePatchId*/ faceLabels,
                /*dihedral*/ Set.of(),
                /*principal*/ Set.of(),
                /*crest*/ Set.of(),
                /*saddleSep*/ Set.of(),
                /*union*/ Set.of(),
                /*patchBoundary*/ Set.of(),
                /*coonsError*/ new float[mesh.vertexCount()],
                /*coonsErrorThreshold*/ NUM_0,
                /*morseSmale*/ null);
    }

    private static void faceCentroid(ArrayMesh mesh, int faceId, Vector3f out) {
        int[] face = mesh.copyFaceIndices();
        float[] pos = mesh.copyPositions();
        int v0 = face[faceId * NUM_3];
        int v1 = face[faceId * NUM_3 + 1];
        int v2 = face[faceId * NUM_3 + 2];
        out.set((pos[v0 * NUM_3] + pos[v1 * NUM_3] + pos[v2 * NUM_3]) / NUM_3_2,
                (pos[v0 * NUM_3 + 1] + pos[v1 * NUM_3 + 1] + pos[v2 * NUM_3 + 1]) / NUM_3_2,
                (pos[v0 * NUM_3 + 2] + pos[v1 * NUM_3 + 2] + pos[v2 * NUM_3 + 2]) / NUM_3_2);
    }

    /**
     * Golden-ratio HSL hue for patch p. Triangle patches are desaturated.
     *
     * @param p TODO: describe
     * @param isTriangle TODO: describe
     * @return TODO: describe
     */
    private static String colorForPatch(int p, boolean isTriangle) {
        // Golden ratio conjugate.
        double phi = NUM_0_6180339887498949;
        double hue = (p * phi) % 1.0;
        float sat = isTriangle ? NUM_0_35 : NUM_0_70;
        float lum = isTriangle ? NUM_0_55 : NUM_0_50;
        return hslToHex((float) hue, sat, lum);
    }

    private static String hslToHex(float h, float s, float l) {
        float c = (1 - Math.abs(2 * l - 1)) * s;
        float hp = h * NUM_6;
        float x = c * (1 - Math.abs(hp % 2 - 1));
        float r, g, b;
        if (hp < 1)      { r = c; g = x; b = 0; }
        else if (hp < 2) { r = x; g = c; b = 0; }
        else if (hp < NUM_3) { r = 0; g = c; b = x; }
        else if (hp < NUM_4) { r = 0; g = x; b = c; }
        else if (hp < NUM_5) { r = x; g = 0; b = c; }
        else             { r = c; g = 0; b = x; }
        float m = l - c / 2;
        int ri = (int) Math.round((r + m) * NUM_255);
        int gi = (int) Math.round((g + m) * NUM_255);
        int bi = (int) Math.round((b + m) * NUM_255);
        return String.format("#%02X%02X%02X",
                Math.max(0, Math.min(NUM_255, ri)),
                Math.max(0, Math.min(NUM_255, gi)),
                Math.max(0, Math.min(NUM_255, bi)));
    }
}
