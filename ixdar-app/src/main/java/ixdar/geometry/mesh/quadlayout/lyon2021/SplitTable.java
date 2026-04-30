package ixdar.geometry.mesh.quadlayout.lyon2021;

import java.util.ArrayList;
import java.util.List;

import ixdar.geometry.mesh.quadlayout.tmesh.TArc;
import ixdar.geometry.mesh.quadlayout.tmesh.TMesh;
import ixdar.geometry.mesh.quadlayout.tmesh.TPatch;

/**
 * PATCH-64 Stage B: per-Tpatch split table. Mirrors metriko's
 * {@code visualizer::gen_split_table} (gen_qgp_table.h).
 *
 * <p>For each side {@code i ∈ {0..3}} of a Tpatch, walk the side's arcs
 * and collect every split vertex with its cumulative parametric distance
 * from the side's starting corner. The distances power Stage C's iso-line
 * tracing through the patch interior.
 *
 * <p><b>v1 simplification:</b> assumes a single arc per side (no
 * T-junctions). Metriko's {@code thids_by_side(i)} returns a list of
 * half-arcs; we use {@code TPatch.arcIds()[i]} directly. Promoting to
 * multi-arc sides is a follow-up once {@link TMesh} produces real T-mesh
 * patches with per-side half-arc lists.
 */
public final class SplitTable {

    private SplitTable() {}

    /** Output of {@link #generate}: 4 sides, each a list of split elements
     *  in order from corner i to corner (i+1) mod 4. */
    public record Result(List<List<SplitElem>> sides) {
        public List<SplitElem> side(int i) { return sides.get(i); }
    }

    /**
     * Build the split table for one Tpatch.
     *
     * @param tmesh        T-mesh containing arcs
     * @param patch        the Tpatch to split
     * @param splitsByArc  per-arc split vertices (output of {@link SplitArcs})
     */
    public static Result generate(TMesh tmesh, TPatch patch,
                                   List<List<SplitVert>> splitsByArc) {
        List<List<SplitElem>> sides = new ArrayList<>(4);
        int[] arcIds = patch.arcIds();
        int[] cornerNodeIds = patch.cornerNodeIds();
        if (arcIds.length != 4 || cornerNodeIds.length != 4) {
            // Defensive — non-4-cycle patch.
            for (int i = 0; i < 4; i++) sides.add(new ArrayList<>());
            return new Result(sides);
        }

        for (int side = 0; side < 4; side++) {
            int arcId = arcIds[side];
            int sideStartNodeId = cornerNodeIds[side];
            TArc arc = tmesh.arcs().get(arcId);
            List<SplitVert> arcSplits = splitsByArc.get(arcId);
            if (arcSplits.isEmpty()) {
                sides.add(new ArrayList<>());
                continue;
            }

            // Determine traversal direction: if the arc's startNode matches
            // the side's starting corner, walk forward; else reversed.
            boolean forward = arc.startNode() == sideStartNodeId;

            ArrayList<SplitElem> elems = new ArrayList<>(arcSplits.size());
            int n = arcSplits.size();
            float totalLen = arc.parametricLength();
            // SplitArcs places verts at uniform parametric spacing
            // (0, sum/num, 2*sum/num, ..., sum). Distance from side start:
            // forward → 0, sum/num, 2*sum/num, ..., sum
            // backward → sum, ..., 2*sum/num, sum/num, 0
            for (int k = 0; k < n; k++) {
                int idx = forward ? k : (n - 1 - k);
                SplitVert v = arcSplits.get(idx);
                float distance;
                if (n == 1) {
                    distance = 0f;
                } else {
                    float t = (float) idx / (float) (n - 1);
                    distance = forward ? t * totalLen : (1f - t) * totalLen;
                }
                elems.add(new SplitElem(v.arcId(), v.stepIndex(),
                        v.u(), v.v(), v.position(), distance));
            }
            sides.add(elems);
        }
        return new Result(sides);
    }
}
