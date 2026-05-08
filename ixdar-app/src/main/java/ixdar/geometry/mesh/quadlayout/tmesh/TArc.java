package ixdar.geometry.mesh.quadlayout.tmesh;

import java.util.List;

/**
 * A T-mesh arc — a maximal motorcycle-trace segment running between two
 * {@link TNode}s along the same iso-line of the seamless parametrization.
 *
 * <p>{@code meshFaceCrossings} records the per-face traversal of the
 * underlying mesh as int pairs {@code [faceId, directionInFace]} so that
 * downstream stages (quantization, T-mesh rendering) can recover the
 * geometric path.
 *
 * <p>{@code stepUvs} parallels {@code meshFaceCrossings} 1:1 — entry
 * {@code i} is the float quad {@code [uIn, vIn, uOut, vOut]} for step
 * {@code i} of the arc. PATCH-64's split-arc extractor walks these entries
 * to compute integer-quantized split vertex positions along the arc.
 *
 * <p>{@code parametricLength} is the cumulative {@code |Δu|} or {@code |Δv|}
 * traversed by this arc in the seamless parametrization (which axis depends
 * on {@link #direction}). This is the real-valued target {@code r_i} the
 * PATCH-42 quantization ILP rounds to a non-negative integer length.
 */
public record TArc(int id,
                   int startNode,
                   int endNode,
                   List<int[]> meshFaceCrossings,
                   List<float[]> stepUvs,
                   int direction,
                   float parametricLength) {
    public static final int NUM_3 = 3;
    public static final float NUM_1e_9 = 1e-9f;

    /**
     * Test-friendly factory: defaults {@code stepUvs} to an empty list.
     *  Production code (TMesh.build) always populates stepUvs alongside
     *  meshFaceCrossings; tests that hand-construct synthetic T-meshes
     */
    public static TArc simple(int id, int startNode, int endNode,
                              List<int[]> meshFaceCrossings,
                              int direction, float parametricLength) {
        return new TArc(id, startNode, endNode, meshFaceCrossings,
                new java.util.ArrayList<>(), direction, parametricLength);
    }

    /**
     * Cardinal {0..3} = {+u, +v, -u, -v} of this arc IN ITS FIRST STEP'S
     * face frame (i.e. at {@link #startNode}). Inferred from the first
     * step's UV delta. Falls back to {@link #direction} for synthetic
     * arcs with no stepUvs (test fixtures).
     */
    public int directionAtStart() {
        if (stepUvs == null || stepUvs.isEmpty()) return direction;
        float[] s = stepUvs.get(0);
        return classifyCardinal(s[2] - s[0], s[NUM_3] - s[1], direction);
    }

    /**
     * Cardinal {0..3} of this arc IN ITS LAST STEP'S face frame (at
     * {@link #endNode}, after all TRS rotations across seams). PATCH-91
     * critical: planar dual face walks must use this at the end node,
     * because {@link #direction} is the launch-frame cardinal which
     * generally disagrees with the end-frame after seam crossings.
     */
    public int directionAtEnd() {
        if (stepUvs == null || stepUvs.isEmpty()) return direction;
        float[] s = stepUvs.get(stepUvs.size() - 1);
        return classifyCardinal(s[2] - s[0], s[NUM_3] - s[1], direction);
    }

    /** Classify a UV delta into {0..3} = {+u, +v, -u, -v}. */
    private static int classifyCardinal(float du, float dv, int fallback) {
        if (Math.abs(du) < NUM_1e_9 && Math.abs(dv) < NUM_1e_9) return fallback;
        if (Math.abs(du) >= Math.abs(dv)) return du >= 0 ? 0 : 2;
        return dv >= 0 ? 1 : NUM_3;
    }
}
