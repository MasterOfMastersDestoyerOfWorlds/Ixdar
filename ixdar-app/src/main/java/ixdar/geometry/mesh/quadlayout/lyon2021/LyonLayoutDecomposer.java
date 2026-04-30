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

    private LyonLayoutDecomposer() {}

    /**
     * Build a {@link SemanticPatchDecomposer.DecompositionDiagnostics} from a
     * Lyon layout. The {@code patches} list contains one {@link Patch} per
     * 4-sided + 3-sided layout patch, with face assignments + golden-ratio
     * colors. All feature-edge sets are empty.
     */
    public static SemanticPatchDecomposer.DecompositionDiagnostics decompose(
            ArrayMesh mesh, TMesh tmesh, QuadLayout layout) {
        int faceCount = mesh.copyFaceIndices().length / 3;
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
            for (int s = 0; s < 4; s++) {
                for (int la : qp.arcsBySide()[s]) {
                    arcToPatchIds.computeIfAbsent(la, k -> new HashSet<>()).add(patchId);
                }
            }
        }
        int triOffset = layout.patches().size();
        for (int t = 0; t < layout.triangles().size(); t++) {
            TrianglePatch tp = layout.triangles().get(t);
            int patchId = triOffset + t;
            for (int s = 0; s < 3; s++) {
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

        // Phase 2: nearest-centroid for unlabeled faces.
        Vector3f[] patchCentroid = new Vector3f[totalPatches];
        int[] centroidCount = new int[totalPatches];
        for (int i = 0; i < totalPatches; i++) patchCentroid[i] = new Vector3f();
        Vector3f tmp = new Vector3f();
        for (int f = 0; f < faceCount; f++) {
            int p = faceLabels[f];
            if (p < 0) continue;
            faceCentroid(mesh, f, tmp);
            patchCentroid[p].add(tmp);
            centroidCount[p]++;
        }
        for (int p = 0; p < totalPatches; p++) {
            if (centroidCount[p] > 0) patchCentroid[p].mul(1f / centroidCount[p]);
        }

        // For each unlabeled face, find nearest patch centroid.
        for (int f = 0; f < faceCount; f++) {
            if (faceLabels[f] >= 0) continue;
            faceCentroid(mesh, f, tmp);
            int best = -1;
            float bestSq = Float.POSITIVE_INFINITY;
            for (int p = 0; p < totalPatches; p++) {
                if (centroidCount[p] == 0) continue;
                float d2 = tmp.distanceSquared(patchCentroid[p]);
                if (d2 < bestSq) {
                    bestSq = d2;
                    best = p;
                }
            }
            if (best >= 0) faceLabels[f] = best;
        }

        // Build Patch records.
        List<List<Integer>> facesPerPatch = new ArrayList<>(totalPatches);
        for (int i = 0; i < totalPatches; i++) facesPerPatch.add(new ArrayList<>());
        for (int f = 0; f < faceCount; f++) {
            int p = faceLabels[f];
            if (p >= 0 && p < totalPatches) facesPerPatch.get(p).add(f);
        }

        List<Patch> outPatches = new ArrayList<>(totalPatches);
        int[] faceIdxArr = mesh.copyFaceIndices();
        float[] positions = mesh.copyPositions();
        for (int p = 0; p < totalPatches; p++) {
            List<Integer> faceList = facesPerPatch.get(p);
            int[] faces = faceList.stream().mapToInt(Integer::intValue).toArray();
            // Vertex indices = unique vertices touched.
            Set<Integer> verts = new HashSet<>();
            for (int f : faces) {
                verts.add(faceIdxArr[f * 3]);
                verts.add(faceIdxArr[f * 3 + 1]);
                verts.add(faceIdxArr[f * 3 + 2]);
            }
            int[] vertexIds = verts.stream().mapToInt(Integer::intValue).sorted().toArray();
            float cx = 0, cy = 0, cz = 0;
            if (vertexIds.length > 0) {
                for (int v : vertexIds) {
                    cx += positions[v * 3];
                    cy += positions[v * 3 + 1];
                    cz += positions[v * 3 + 2];
                }
                cx /= vertexIds.length; cy /= vertexIds.length; cz /= vertexIds.length;
            }
            String color = colorForPatch(p, p >= triOffset);
            outPatches.add(new Patch(p, vertexIds, faces, /*branchId*/ p,
                    new float[]{cx, cy, cz}, /*curvatureMean*/ 0f, color));
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
                /*coonsErrorThreshold*/ 0f,
                /*morseSmale*/ null);
    }

    private static void faceCentroid(ArrayMesh mesh, int faceId, Vector3f out) {
        int[] face = mesh.copyFaceIndices();
        float[] pos = mesh.copyPositions();
        int v0 = face[faceId * 3];
        int v1 = face[faceId * 3 + 1];
        int v2 = face[faceId * 3 + 2];
        out.set((pos[v0 * 3] + pos[v1 * 3] + pos[v2 * 3]) / 3f,
                (pos[v0 * 3 + 1] + pos[v1 * 3 + 1] + pos[v2 * 3 + 1]) / 3f,
                (pos[v0 * 3 + 2] + pos[v1 * 3 + 2] + pos[v2 * 3 + 2]) / 3f);
    }

    /** Golden-ratio HSL hue for patch p. Triangle patches are desaturated. */
    private static String colorForPatch(int p, boolean isTriangle) {
        // Golden ratio conjugate.
        double phi = 0.6180339887498949;
        double hue = (p * phi) % 1.0;
        float sat = isTriangle ? 0.35f : 0.70f;
        float lum = isTriangle ? 0.55f : 0.50f;
        return hslToHex((float) hue, sat, lum);
    }

    private static String hslToHex(float h, float s, float l) {
        float c = (1 - Math.abs(2 * l - 1)) * s;
        float hp = h * 6;
        float x = c * (1 - Math.abs(hp % 2 - 1));
        float r, g, b;
        if (hp < 1)      { r = c; g = x; b = 0; }
        else if (hp < 2) { r = x; g = c; b = 0; }
        else if (hp < 3) { r = 0; g = c; b = x; }
        else if (hp < 4) { r = 0; g = x; b = c; }
        else if (hp < 5) { r = x; g = 0; b = c; }
        else             { r = c; g = 0; b = x; }
        float m = l - c / 2;
        int ri = (int) Math.round((r + m) * 255);
        int gi = (int) Math.round((g + m) * 255);
        int bi = (int) Math.round((b + m) * 255);
        return String.format("#%02X%02X%02X",
                Math.max(0, Math.min(255, ri)),
                Math.max(0, Math.min(255, gi)),
                Math.max(0, Math.min(255, bi)));
    }
}
