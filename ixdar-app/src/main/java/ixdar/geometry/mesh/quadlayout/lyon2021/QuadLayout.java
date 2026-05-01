package ixdar.geometry.mesh.quadlayout.lyon2021;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * The output of Lyon 2021's algorithm: a conforming quad layout
 * (paper §6 first paragraph).
 *
 * <p>Most patches are 4-sided ({@link QuadLayoutPatch}); a small number are
 * 3-sided wedges around 3-valent singularities ({@link TrianglePatch},
 * PATCH-79). Layout arcs align with the seamless parametrization's
 * iso-lines within the user-specified angular bound α.
 *
 * <p>{@link #patchCount()} returns paper Table 1's {@code #P} = quads +
 * triangles combined. {@link #mergedPatchCount()} (PATCH-88) counts after
 * collapsing arcs with {@code q=0} per Lyon §6 ¶1 — patches connected
 * through zero-quantized arcs merge into one conforming layout patch.
 *
 * <p>{@code layoutArcs} is the registry that {@link QuadLayoutPatch#arcsBySide()}
 * indexes into. {@code tArcQuantization[a]} carries the original ILP
 * solution for the underlying {@link ixdar.geometry.mesh.quadlayout.tmesh.TArc}s
 * (consumers like {@link LyonMetrics} that walk T-arcs need this);
 * {@code layoutArcQuantization[la]} carries the integer length of each
 * {@link LayoutArc} (the same as {@code tArcQuantization[underlying]}
 * for INHERITED arcs, computed for INTERIOR/DERIVED).
 */
public record QuadLayout(List<QuadLayoutPatch> patches,
                          List<TrianglePatch> triangles,
                          List<LayoutArc> layoutArcs,
                          int[] tArcQuantization,
                          int[] layoutArcQuantization,
                          int tJunctionsResolved) {

    /** Pre-merge patch count = quad TPatches + triangle wedges. */
    public int patchCount() { return patches.size() + triangles.size(); }

    /** Resolve a layout-arc id to its record. */
    public LayoutArc arc(int layoutArcId) { return layoutArcs.get(layoutArcId); }

    /**
     * PATCH-88 — Lyon §6 ¶1 conforming-layout patch count. Patches that share
     * a {@code q=0} layout arc merge into one. This is what Lyon Table 1's
     * {@code #P} column counts (e.g. 159 on ROCKERARM at α=15°).
     */
    public int mergedPatchCount() {
        return mergedPatchAssignment().distinctCount();
    }

    /** PATCH-88 merge result: per-quad-patch component id + total count. */
    public record MergedAssignment(int[] quadPatchToComponent,
                                    int[] trianglePatchToComponent,
                                    int distinctCount) {}

    /**
     * Compute merged-component assignment via union-find over {@code q=0}
     * arcs. Component ids are dense in {@code [0, distinctCount)}.
     */
    public MergedAssignment mergedPatchAssignment() {
        int totalNonMerged = patches.size() + triangles.size();
        int[] parent = new int[totalNonMerged];
        for (int i = 0; i < totalNonMerged; i++) parent[i] = i;

        // Build arc → list of patches (quad or triangle) that contain it.
        // Each interior layout arc appears in exactly 2 patches; boundary
        // arcs in 1.
        HashMap<Integer, java.util.ArrayList<Integer>> arcToPatches = new HashMap<>();
        for (int p = 0; p < patches.size(); p++) {
            QuadLayoutPatch qp = patches.get(p);
            for (int s = 0; s < 4; s++) {
                for (int la : qp.arcsBySide()[s]) {
                    arcToPatches.computeIfAbsent(la, k -> new java.util.ArrayList<>()).add(p);
                }
            }
        }
        int triOffset = patches.size();
        for (int t = 0; t < triangles.size(); t++) {
            TrianglePatch tp = triangles.get(t);
            int patchId = triOffset + t;
            for (int s = 0; s < 3; s++) {
                for (int la : tp.arcsBySide()[s]) {
                    arcToPatches.computeIfAbsent(la, k -> new java.util.ArrayList<>()).add(patchId);
                }
            }
        }

        // For each q=0 arc, union the patches sharing it.
        for (var entry : arcToPatches.entrySet()) {
            int laId = entry.getKey();
            if (laId < 0 || laId >= layoutArcQuantization.length) continue;
            if (layoutArcQuantization[laId] != 0) continue;   // arc not collapsed
            var ps = entry.getValue();
            for (int i = 1; i < ps.size(); i++) {
                union(parent, ps.get(0), ps.get(i));
            }
        }

        // Compress + reindex.
        int[] component = new int[totalNonMerged];
        HashMap<Integer, Integer> rootToComp = new HashMap<>();
        int distinctCount = 0;
        for (int i = 0; i < totalNonMerged; i++) {
            int r = find(parent, i);
            Integer c = rootToComp.get(r);
            if (c == null) {
                c = distinctCount++;
                rootToComp.put(r, c);
            }
            component[i] = c;
        }

        int[] quadAssign = java.util.Arrays.copyOfRange(component, 0, patches.size());
        int[] triAssign = java.util.Arrays.copyOfRange(component,
                patches.size(), totalNonMerged);
        return new MergedAssignment(quadAssign, triAssign, distinctCount);
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) parent[ra] = rb;
    }
}
