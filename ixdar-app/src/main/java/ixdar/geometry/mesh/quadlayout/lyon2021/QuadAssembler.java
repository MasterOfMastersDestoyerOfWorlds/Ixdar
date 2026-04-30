package ixdar.geometry.mesh.quadlayout.lyon2021;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.extraction.TransitionMatrix;
import ixdar.geometry.mesh.quadlayout.tmesh.TArc;
import ixdar.geometry.mesh.quadlayout.tmesh.TMesh;
import ixdar.geometry.mesh.quadlayout.tmesh.TPatch;

/**
 * PATCH-66 Stage E: Lyon 2021 final-output orchestrator. Wires Stages A-D
 * together to produce a quad mesh from the T-mesh + quantization. Mirrors
 * the loop in metriko's example_qgp_headless.cpp:
 *
 * <pre>
 *   for tquad in tmesh.tquads:
 *       sv_quad = gen_split_table(tmesh, tquad, splits_per_arc)
 *       arcs1 = [gen_split_arc(side=0, sv_a, sv_b)  for ...]
 *       arcs2 = [gen_split_arc(side=1, sv_a, sv_b)  for ...]
 *       table = gen_intersection_table(tquad, arcs1, arcs2)
 *       emit (rows-1)*(cols-1) quads from table
 * </pre>
 *
 * <p>Output: ArrayMesh with quad faces.
 */
public final class QuadAssembler {

    private QuadAssembler() {}

    /** Result of the assembly: the produced quad mesh + per-patch quad counts. */
    public record Result(ArrayMesh quadMesh,
                         int totalQuads,
                         int totalVertices,
                         int patchesProcessed,
                         int patchesSkipped) {}

    public static Result assemble(TMesh tmesh,
                                   List<List<SplitVert>> splitsByArc,
                                   ArrayMesh mesh,
                                   float[] uCorner, float[] vCorner,
                                   TransitionMatrix trs) {
        ArrayList<Float> outPositions = new ArrayList<>();
        ArrayList<Integer> outFaces = new ArrayList<>();
        int processed = 0, skipped = 0;

        for (TPatch patch : tmesh.patches()) {
            if (patch.arcIds().length != 4 || patch.cornerNodeIds().length != 4) {
                skipped++;
                continue;
            }
            // Stage B: build split table.
            SplitTable.Result table = SplitTable.generate(tmesh, patch, splitsByArc);
            if (table.sides().stream().anyMatch(java.util.List::isEmpty)) {
                skipped++;
                continue;
            }
            // Per-side R sums (parametric length per side) — for Stage C arg calculation.
            double[] sideR = new double[4];
            for (int i = 0; i < 4; i++) {
                sideR[i] = tmesh.arcs().get(patch.arcIds()[i]).parametricLength();
            }

            // Stage C: trace arcs1 (side 0 → side 2) and arcs2 (side 1 → side 3).
            List<SplitElem> svs0 = table.side(0);
            List<SplitElem> svs2 = table.side(2);
            List<SplitElem> svs1 = table.side(1);
            List<SplitElem> svs3 = table.side(3);
            if (svs0.size() != svs2.size() || svs1.size() != svs3.size()) {
                // Side mismatch — quantization didn't satisfy patch consistency.
                skipped++;
                continue;
            }

            ArrayList<SplitArcTracer.SplitArc> arcs1 = new ArrayList<>();
            ArrayList<SplitArcTracer.SplitArc> arcs2 = new ArrayList<>();
            for (int i = 1; i < svs0.size() - 1; i++) {
                SplitElem from = svs0.get(i);
                SplitElem to = svs2.get(svs2.size() - 1 - i);   // mirrored
                arcs1.add(SplitArcTracer.trace(tmesh, mesh, patch, sideR,
                        uCorner, vCorner, trs, 0, from, to));
            }
            for (int j = 1; j < svs1.size() - 1; j++) {
                SplitElem from = svs1.get(j);
                SplitElem to = svs3.get(svs3.size() - 1 - j);
                arcs2.add(SplitArcTracer.trace(tmesh, mesh, patch, sideR,
                        uCorner, vCorner, trs, 1, from, to));
            }

            // Stage D: intersection table.
            IntersectionTable.Result it = IntersectionTable.build(tmesh, patch,
                    arcs1, arcs2, mesh, uCorner, vCorner);

            // Stage E: emit quads. Skip if any cell is null (intersection
            // failed) — that quad row/col is incomplete.
            int rows = it.rows();
            int cols = it.cols();
            int[] indexMap = new int[rows * cols];
            java.util.Arrays.fill(indexMap, -1);
            for (int i = 0; i < rows; i++) {
                for (int j = 0; j < cols; j++) {
                    Vector3f p = it.positions()[i * cols + j];
                    if (p == null) continue;
                    indexMap[i * cols + j] = outPositions.size() / 3;
                    outPositions.add(p.x);
                    outPositions.add(p.y);
                    outPositions.add(p.z);
                }
            }
            int patchQuads = 0;
            for (int i = 0; i < rows - 1; i++) {
                for (int j = 0; j < cols - 1; j++) {
                    int i00 = indexMap[i * cols + j];
                    int i10 = indexMap[(i + 1) * cols + j];
                    int i11 = indexMap[(i + 1) * cols + j + 1];
                    int i01 = indexMap[i * cols + j + 1];
                    if (i00 < 0 || i10 < 0 || i11 < 0 || i01 < 0) continue;
                    outFaces.add(i00);
                    outFaces.add(i01);
                    outFaces.add(i11);
                    outFaces.add(i10);
                    patchQuads++;
                }
            }
            if (patchQuads > 0) processed++; else skipped++;
        }

        if (outPositions.isEmpty()) {
            return new Result(null, 0, 0, processed, skipped);
        }
        float[] positions = new float[outPositions.size()];
        for (int i = 0; i < positions.length; i++) positions[i] = outPositions.get(i);
        int[] faceIndices = outFaces.stream().mapToInt(Integer::intValue).toArray();
        ArrayMesh quad = new ArrayMesh(positions, null, faceIndices, 4);
        return new Result(quad, faceIndices.length / 4, positions.length / 3,
                processed, skipped);
    }
}
