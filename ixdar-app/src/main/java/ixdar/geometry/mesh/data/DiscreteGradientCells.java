package ixdar.geometry.mesh.data;
import java.util.HashMap;

import java.util.ArrayList;
import java.util.Map;

import java.util.List;

import ixdar.geometry.mesh.data.representation.ArrayMesh;

/**
 * PATCH-35: ascending/descending V-path face labelling using
 * {@link DiscreteGradient}. Each face F is assigned to a quad cell
 * identified by {@code (asc_max, desc_min)} where:
 * <ul>
 *   <li>{@code asc_max} = critical 2-cell reached from F by V-path in
 *       the gradient field of {@code +scalar}.</li>
 *   <li>{@code desc_min} = critical 2-cell reached from F by V-path in
 *       the gradient field of {@code −scalar} (which equals the original
 *       descending manifold's destination 0-cell, expressed as a
 *       triangle for symmetry with ascending).</li>
 * </ul>
 *
 * <p>Same intersection-of-manifolds logic as PATCH-29 v2 but with
 * stable combinatorial V-paths instead of steepest-walks → cells have
 * clean simply-connected boundaries.
 */
public final class DiscreteGradientCells {
    public static final int NUM_32 = 32;
    public static final long NUM_0xffffffff = 0xffffffffL;
    public static final int NUM_16 = 16;

    private DiscreteGradientCells() {}

    /**
     * Computes the discrete gradient on {@code +scalar} and {@code -scalar}, traces
     * a V-path from each face to the corresponding critical 2-cell, and assigns
     * face-cell ids by unique {@code (ascMax, descMin)} pairs.
     *
     * @param mesh source triangle mesh
     * @param scalar per-vertex scalar field
     * @return per-face cell ids (negative for orphans), the per-label triangle ids
     *         of ascending/descending criticals, and orphan count
     */
    public static Result assemble(ArrayMesh mesh, float[] scalar) {
        DiscreteGradient.Result asc = DiscreteGradient.compute(mesh, scalar);
        // Negate scalar with simulation-of-simplicity preservation.
        float[] neg = new float[scalar.length];
        for (int i = 0; i < scalar.length; i++) neg[i] = -scalar[i];
        DiscreteGradient.Result desc = DiscreteGradient.compute(mesh, neg);

        int faceCount = asc.nt();
        // Collect critical 2-cells = local maxima of the +scalar gradient.
        Map<Integer, Integer> maxLabelByTriId = new HashMap<>();
        List<Integer> maxList = new ArrayList<>();
        for (int c : asc.criticalCells()) {
            if (asc.dimOf(c) == 2) {
                int triId = asc.localIdx(c);
                maxLabelByTriId.put(triId, maxList.size());
                maxList.add(triId);
            }
        }
        // Critical 2-cells of -scalar = local minima of +scalar.
        Map<Integer, Integer> minLabelByTriId = new HashMap<>();
        List<Integer> minList = new ArrayList<>();
        for (int c : desc.criticalCells()) {
            if (desc.dimOf(c) == 2) {
                int triId = desc.localIdx(c);
                minLabelByTriId.put(triId, minList.size());
                minList.add(triId);
            }
        }

        // V-path trace per face.
        int[] ascMaxLabel = new int[faceCount];
        int[] descMinLabel = new int[faceCount];
        for (int f = 0; f < faceCount; f++) {
            ascMaxLabel[f] = traceVPath(f, asc, maxLabelByTriId);
            descMinLabel[f] = traceVPath(f, desc, minLabelByTriId);
        }

        // Quad-cell id = unique (ascMax, descMin) pair seen.
        Map<Long, Integer> pairToCell = new HashMap<>();
        int[] facePatchId = new int[faceCount];
        int orphanCount = 0;
        for (int f = 0; f < faceCount; f++) {
            int a = ascMaxLabel[f], d = descMinLabel[f];
            if (a < 0 || d < 0) { facePatchId[f] = -1; orphanCount++; continue; }
            long key = ((long) a << NUM_32) | (d & NUM_0xffffffff);
            facePatchId[f] = pairToCell.computeIfAbsent(key, k -> pairToCell.size());
        }
        int[] maxByLabel = maxList.stream().mapToInt(Integer::intValue).toArray();
        int[] minByLabel = minList.stream().mapToInt(Integer::intValue).toArray();
        return new Result(facePatchId, maxByLabel, minByLabel, orphanCount);
    }

    /**
     * Trace a V-path from face F through gradient pairs to a critical
     * 2-cell. Returns the critical 2-cell's label (index in
     * {@code criticalLabel}), or -1 if the walk fails.
     */
    private static int traceVPath(int startFace, DiscreteGradient.Result g,
                                    Map<Integer, Integer> criticalLabel) {
        int currentTri = startFace;
        int safety = g.nt() * 2 + NUM_16;  // V-paths in 2D are O(N) at most
        for (int step = 0; step < safety; step++) {
            int triCellId = g.cellId(2, currentTri);
            if (g.isCritical(triCellId)) {
                Integer lab = criticalLabel.get(currentTri);
                return lab != null ? lab : -1;
            }
            int pairedEdgeCell = g.pair()[triCellId];
            if (pairedEdgeCell < 0 || g.dimOf(pairedEdgeCell) != 1) return -1;
            int eid = g.localIdx(pairedEdgeCell);
            // Move to the OTHER triangle on this edge.
            int[] cofacets = g.trianglesByEdge()[eid];
            int next = -1;
            for (int t : cofacets) {
                if (t != currentTri) { next = t; break; }
            }
            if (next < 0) return -1;  // boundary of mesh, no other side
            currentTri = next;
        }
        return -1;
    }

    public record Result(
            int[] facePatchId,
            int[] maxTriIdByLabel,
            int[] minTriIdByLabel,
            int orphanFaces
    ) {}
}
