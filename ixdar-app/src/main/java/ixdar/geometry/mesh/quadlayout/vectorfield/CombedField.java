package ixdar.geometry.mesh.quadlayout.vectorfield;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Single-valued (combed) direction field derived from a {@link FaceRosyField}.
 * The cross field assigns four equivalent directions per face; combing chooses
 * one branch index per face such that adjacent faces' chosen branches agree
 * across spanning-tree dual edges. The non-tree dual edges form the SEAM —
 * cuts along which the chosen branch flips by a fixed integer rotation
 * (the per-edge matching).
 *
 * <p>Output API used by downstream stages (PATCH-40 motorcycle T-mesh,
 * PATCH-41 IGM):
 * <ul>
 *   <li>{@link #branch(int)} — branch index in {0,1,2,3} per face.
 *   <li>{@link #combedAngle(int)} — theta + branch * pi/2 (the unique chosen
 *       angle in the face's local frame).
 *   <li>{@link #matching(int)} — integer rotation r_e in {0,1,2,3} such that
 *       direction (theta_A + branch_A * pi/2) corresponds to direction
 *       (theta_B + branch_B * pi/2 + r_e * pi/2) modulo 2*pi (i.e. the
 *       residual matching after combing).
 *   <li>{@link #isSeamEdge(int)} — true for interior edges where matching != 0.
 * </ul>
 */
public final class CombedField {

    private final FaceRosyField field;
    private final int[] branch;
    private final int[] matching;
    private final boolean[] seamEdge;

    private CombedField(FaceRosyField field, int[] branch, int[] matching, boolean[] seamEdge) {
        this.field = field;
        this.branch = branch;
        this.matching = matching;
        this.seamEdge = seamEdge;
    }

    public FaceRosyField field() { return field; }

    public int branch(int faceId) { return branch[faceId]; }

    public double combedAngle(int faceId) {
        return field.theta(faceId) + branch[faceId] * (Math.PI * 0.5);
    }

    public int matching(int interiorEdgeIndex) { return matching[interiorEdgeIndex]; }

    public boolean isSeamEdge(int interiorEdgeIndex) { return seamEdge[interiorEdgeIndex]; }

    public int[] copyBranch() { return Arrays.copyOf(branch, branch.length); }
    public int[] copyMatching() { return Arrays.copyOf(matching, matching.length); }
    public boolean[] copySeamEdge() { return Arrays.copyOf(seamEdge, seamEdge.length); }

    public List<Integer> seamEdgeIndices() {
        ArrayList<Integer> out = new ArrayList<>();
        for (int i = 0; i < seamEdge.length; i++) if (seamEdge[i]) out.add(i);
        return out;
    }

    public int seamEdgeCount() {
        int c = 0;
        for (boolean b : seamEdge) if (b) c++;
        return c;
    }

    /**
     * Bypass the BFS-comb and inject externally-computed branch / matching /
     * seam arrays. Used by
     * {@code ixdar.geometry.mesh.quadlayout.field.PrecomputedFieldImporter} (PATCH-51) to
     * adopt metriko's stage1 combed-field output verbatim. Caller is responsible
     * for arrays being length-consistent with the field
     * ({@code branch.length == field.faceCount()},
     * {@code matching.length == seamEdge.length == field.interiorEdgeCount()}).
     */
    public static CombedField fromExternal(FaceRosyField field, int[] branch,
                                           int[] matching, boolean[] seamEdge) {
        if (branch.length != field.faceCount()) {
            throw new IllegalArgumentException("branch length mismatch");
        }
        if (matching.length != field.interiorEdgeCount()
                || seamEdge.length != field.interiorEdgeCount()) {
            throw new IllegalArgumentException("matching/seam length mismatch");
        }
        return new CombedField(field,
                Arrays.copyOf(branch, branch.length),
                Arrays.copyOf(matching, matching.length),
                Arrays.copyOf(seamEdge, seamEdge.length));
    }

    /**
     * Comb the field by BFS from face 0 along dual spanning-tree edges,
     * propagating the branch choice. Non-tree edges retain the residual
     * matching as seam edges.
     */
    public static CombedField comb(FaceRosyField field) {
        int F = field.faceCount();
        int E = field.interiorEdgeCount();
        int[] branch = new int[F];
        int[] matching = new int[E];
        boolean[] seamEdge = new boolean[E];
        Arrays.fill(branch, -1);

        // Per-face adjacency: list of (neighbour face, interior edge index).
        int[] degree = new int[F];
        for (int e = 0; e < E; e++) {
            degree[field.edgeFaceA(e)]++;
            degree[field.edgeFaceB(e)]++;
        }
        int[][] adj = new int[F][];
        for (int i = 0; i < F; i++) adj[i] = new int[degree[i] * 2];
        int[] cursor = new int[F];
        for (int e = 0; e < E; e++) {
            int fa = field.edgeFaceA(e), fb = field.edgeFaceB(e);
            adj[fa][cursor[fa]++] = fb;
            adj[fa][cursor[fa]++] = e;
            adj[fb][cursor[fb]++] = fa;
            adj[fb][cursor[fb]++] = e;
        }

        // BFS from each connected component, fixing branch[seed] = 0 and
        // propagating r_e to neighbours so that the residual matching across
        // each tree edge is zero.
        Deque<Integer> queue = new ArrayDeque<>();
        for (int seed = 0; seed < F; seed++) {
            if (branch[seed] >= 0) continue;
            branch[seed] = 0;
            queue.add(seed);
            // Track which interior edges were used as tree edges in this BFS
            // (independent of the LSQ tree).
            boolean[] visitedFromTree = new boolean[E];
            while (!queue.isEmpty()) {
                int f = queue.poll();
                int[] a = adj[f];
                for (int k = 0; k + 1 < a.length; k += 2) {
                    int nbr = a[k];
                    int e = a[k + 1];
                    if (visitedFromTree[e]) continue;
                    if (branch[nbr] >= 0) continue;
                    visitedFromTree[e] = true;
                    int r = matchingFor(field, e, branch[f], f);
                    branch[nbr] = ((branch[f] + r) % 4 + 4) % 4;
                    if (field.edgeFaceA(e) == f) {
                        // Going A->B: residual = (branch_B - branch_A - r_AB) mod 4
                        // We chose branch_B = branch_A + r so residual = 0.
                    }
                    queue.add(nbr);
                }
            }
        }

        // Compute matching on every interior edge with the chosen branches;
        // non-zero entries are seam edges.
        for (int e = 0; e < E; e++) {
            int fa = field.edgeFaceA(e);
            int fb = field.edgeFaceB(e);
            // For interior edge oriented A->B, the period jump m_e relates
            // theta in A to theta in B as: theta_A - theta_B + kappa + m*pi/2 ~ 0.
            // After combing with branches b_A, b_B, the matching is
            //   r = ((m + b_A - b_B) mod 4)  in {0,1,2,3}.
            int r = ((field.periodJump(e) + branch[fa] - branch[fb]) % 4 + 4) % 4;
            matching[e] = r;
            seamEdge[e] = (r != 0);
        }

        return new CombedField(field, branch, matching, seamEdge);
    }

    /**
     * Given an interior edge {@code e} and the currently-fixed branch {@code b}
     * on face {@code from} (which is one endpoint of the edge), return the
     * branch index for the OTHER face such that the matching residual is zero.
     */
    private static int matchingFor(FaceRosyField field, int e, int b, int from) {
        int m = field.periodJump(e);
        if (field.edgeFaceA(e) == from) {
            // theta_B = theta_A + kappa + m*pi/2; branch_B = branch_A + m  (mod 4)
            return ((m % 4) + 4) % 4;
        } else {
            // Reversed traversal: from = B, going to A.
            return (((-m) % 4) + 4) % 4;
        }
    }
}
