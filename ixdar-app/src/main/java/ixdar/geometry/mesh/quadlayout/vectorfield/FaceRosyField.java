package ixdar.geometry.mesh.quadlayout.vectorfield;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.ArrayMesh;

/**
 * Smooth 4-RoSy cross field on a triangulated 2-manifold via the Campen 2014
 * angle-based mixed-integer formulation (thesis Eq. 4.6):
 *
 * <pre>
 *   min  sum over interior edges  ( theta_i - theta_j + kappa_ij + m_ij * pi/2 )^2
 * </pre>
 *
 * with theta_i in R per face and m_ij in Z on a face-cycle basis (one per
 * non-spanning-tree edge of the dual graph, per Bommes-Zimmer-Kobbelt 2009).
 *
 * <h3>Solver schedule (BZK12 greedy round-and-resolve)</h3>
 * The integer DOFs live on the chord set of a dual spanning tree (one m per
 * non-tree interior edge, the cycle-space basis). Tree edges are fixed at
 * m=0 in the gauge. The greedy loop is implemented in
 * {@link MiGreedyRounding}:
 * <ol>
 *   <li>Solve the joint LSQ on (theta, m_chord) with m_chord relaxed to R.</li>
 *   <li>Pick the unpinned chord whose relaxed value is closest to an integer.</li>
 *   <li>Pin it to the nearest integer and re-solve.</li>
 *   <li>Repeat until every chord is pinned.</li>
 * </ol>
 * After convergence, integer m for tree edges is recovered as the nearest
 * multiple of pi/2 to the residual at the final theta — required by
 * downstream matching/holonomy detection ({@link CombedField},
 * {@link SingularityFinder}).
 *
 * <p>Outputs:
 * <ul>
 *   <li>{@code theta(faceId)} — per-face angle (radians) in the face's local
 *       frame defined by {@link BaseField}.
 *   <li>{@code periodJump(interiorEdgeIndex)} — integer m on each interior
 *       edge (rounded tree residual, or pinned chord value).
 *   <li>{@link Singularity} list — vertices where face-cycle holonomy is
 *       non-zero modulo 2*pi.
 * </ul>
 *
 * <p>Assumes a clean, noise-free input mesh (per thesis Sec 4.4); noisy input
 * blows up the singularity count.
 *
 * <p>Scaling note: the joint solve runs on (F + C) variables where C is the
 * chord count (= E_int - F + 1 per connected component). Each greedy step
 * pins one chord and triggers a fresh sparse LDL/LU decompose; total cost is
 * O(C * solve(F+C)). Adequate for meshes up to ~10k faces. Beyond that,
 * pattern-preserving refactor or a CHOLMOD-class solver becomes necessary.
 */
public final class FaceRosyField extends BaseField {

    private static final double PI_HALF = Math.PI * 0.5;

    private final double principalThreshold;

    private int interiorEdgeCount;
    private int[] edgeFaceA;
    private int[] edgeFaceB;
    private int[] edgeMeshId;
    private double[] kappa;
    private boolean[] isTreeEdge;
    private int[] periodJump;

    private boolean solved;

    public FaceRosyField(ArrayMesh mesh) {
        this(mesh, Double.POSITIVE_INFINITY);
    }

    /**
     * @param principalThreshold curvature anisotropy threshold above which a
     *     face's theta is hard-fixed to the principal direction. Set to
     *     {@code Double.POSITIVE_INFINITY} to disable principal-direction
     *     alignment (default; v1 keeps it disabled per the ticket).
     */
    public FaceRosyField(ArrayMesh mesh, double principalThreshold) {
        super(mesh);
        this.principalThreshold = principalThreshold;
        buildEdgeStructure();
    }

    public int interiorEdgeCount() { return interiorEdgeCount; }

    public int periodJump(int interiorEdgeIndex) {
        if (!solved) throw new IllegalStateException("solve() not called");
        return periodJump[interiorEdgeIndex];
    }

    public int edgeFaceA(int interiorEdgeIndex) { return edgeFaceA[interiorEdgeIndex]; }
    public int edgeFaceB(int interiorEdgeIndex) { return edgeFaceB[interiorEdgeIndex]; }
    public int edgeMeshId(int interiorEdgeIndex) { return edgeMeshId[interiorEdgeIndex]; }
    public double kappa(int interiorEdgeIndex) { return kappa[interiorEdgeIndex]; }

    public boolean isTreeEdge(int interiorEdgeIndex) { return isTreeEdge[interiorEdgeIndex]; }

    public void solve() {
        int F = mesh.faceCount();
        int E = interiorEdgeCount;
        if (F == 0) {
            periodJump = new int[E];
            solved = true;
            return;
        }

        MiGreedyRounding greedy = new MiGreedyRounding(
                F, E, edgeFaceA, edgeFaceB, kappa, isTreeEdge);
        MiGreedyRounding.Result res = greedy.solve();
        for (int i = 0; i < F; i++) theta[i] = res.theta[i];
        periodJump = res.periodJump;
        solved = true;
    }

    private void buildEdgeStructure() {
        int totalEdges = mesh.edgeCount();
        int F = mesh.faceCount();
        ArrayList<int[]> interior = new ArrayList<>();
        for (int eId = 0; eId < totalEdges; eId++) {
            if (mesh.isBoundaryEdge(eId)) continue;
            int he = mesh.edgeHalfEdge(eId);
            int twin = mesh.halfEdgeTwin(he);
            int fA = mesh.halfEdgeFace(he);
            int fB = mesh.halfEdgeFace(twin);
            if (fA < 0 || fB < 0) continue;
            interior.add(new int[]{fA, fB, eId, he});
        }
        interiorEdgeCount = interior.size();
        edgeFaceA = new int[interiorEdgeCount];
        edgeFaceB = new int[interiorEdgeCount];
        edgeMeshId = new int[interiorEdgeCount];
        kappa = new double[interiorEdgeCount];

        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f edgeDir = new Vector3f();
        Vector3f uA = new Vector3f();
        Vector3f vA = new Vector3f();
        Vector3f uB = new Vector3f();
        Vector3f vB = new Vector3f();
        Vector3f nA = new Vector3f();
        Vector3f nB = new Vector3f();
        Vector3f tangentInB = new Vector3f();
        for (int e = 0; e < interiorEdgeCount; e++) {
            int[] r = interior.get(e);
            int fA = r[0], fB = r[1], eId = r[2], he = r[3];
            edgeFaceA[e] = fA;
            edgeFaceB[e] = fB;
            edgeMeshId[e] = eId;

            int v0 = mesh.halfEdgeVertex(he);
            int v1 = mesh.halfEdgeEndVertex(he);
            mesh.vertexPosition(v0, p0);
            mesh.vertexPosition(v1, p1);
            edgeDir.set(p1).sub(p0);
            float el = edgeDir.length();
            if (el > 1e-30f) edgeDir.mul(1f / el);

            frameU(fA, uA); frameV(fA, vA); frameN(fA, nA);
            frameU(fB, uB); frameV(fB, vB); frameN(fB, nB);

            double alphaA = Math.atan2(edgeDir.dot(vA), edgeDir.dot(uA));
            double dn = edgeDir.dot(nB);
            tangentInB.set(edgeDir).sub(nB.x * (float) dn, nB.y * (float) dn, nB.z * (float) dn);
            float tl = tangentInB.length();
            if (tl > 1e-30f) tangentInB.mul(1f / tl);
            double alphaB = Math.atan2(tangentInB.dot(vB), tangentInB.dot(uB));

            // Same 3D direction expressed in two frames. Smooth field requires
            // theta_A - theta_B = alpha_B - alpha_A; so kappa_AB = alpha_B - alpha_A
            // makes the residual (theta_A - theta_B + kappa_AB) zero for a
            // parallel field across the edge.
            kappa[e] = alphaB - alphaA;
        }

        // Build dual spanning tree by BFS over faces.
        isTreeEdge = new boolean[interiorEdgeCount];
        int[][] faceEdgeNbr = buildFaceAdjacency(F);

        boolean[] visited = new boolean[F];
        Deque<Integer> queue = new ArrayDeque<>();
        for (int seed = 0; seed < F; seed++) {
            if (visited[seed]) continue;
            visited[seed] = true;
            queue.add(seed);
            while (!queue.isEmpty()) {
                int f = queue.poll();
                int[] adj = faceEdgeNbr[f];
                for (int k = 0; k + 1 < adj.length; k += 2) {
                    int nbr = adj[k];
                    int eIdx = adj[k + 1];
                    if (!visited[nbr]) {
                        visited[nbr] = true;
                        isTreeEdge[eIdx] = true;
                        queue.add(nbr);
                    }
                }
            }
        }
    }

    private int[][] buildFaceAdjacency(int F) {
        int[] degree = new int[F];
        for (int e = 0; e < interiorEdgeCount; e++) {
            degree[edgeFaceA[e]]++;
            degree[edgeFaceB[e]]++;
        }
        int[][] adj = new int[F][];
        for (int i = 0; i < F; i++) adj[i] = new int[degree[i] * 2];
        int[] cursor = new int[F];
        for (int e = 0; e < interiorEdgeCount; e++) {
            int fa = edgeFaceA[e], fb = edgeFaceB[e];
            adj[fa][cursor[fa]++] = fb;
            adj[fa][cursor[fa]++] = e;
            adj[fb][cursor[fb]++] = fa;
            adj[fb][cursor[fb]++] = e;
        }
        return adj;
    }

    /** Smoothness energy = sum over edges of residual^2. Useful for tests. */
    public double smoothnessEnergy() {
        if (!solved) throw new IllegalStateException("solve() not called");
        double E = 0.0;
        for (int e = 0; e < interiorEdgeCount; e++) {
            double r = theta[edgeFaceA[e]] - theta[edgeFaceB[e]] + kappa[e]
                    + periodJump[e] * PI_HALF;
            E += r * r;
        }
        return E;
    }

    public List<Singularity> findSingularities() {
        if (!solved) throw new IllegalStateException("solve() not called");
        return SingularityFinder.find(this);
    }

    public double principalThreshold() { return principalThreshold; }

    /**
     * Bypass the BZK12 solver and inject externally-computed per-face theta and
     * per-interior-edge integer periodJump values. Used by
     * {@code ixdar.geometry.mesh.quadlayout.field.PrecomputedFieldImporter} (PATCH-51) to
     * load metriko's known-good stage1 cross field for downstream regression
     * testing of PATCH-40 / PATCH-41 in parallel with PATCH-50 (the in-flight
     * solver rewrite). The returned field's edge structure (interior edge list,
     * kappa, tree flags) is built from {@code mesh} the same way the regular
     * solver does, so all downstream getters remain valid.
     *
     * @param mesh        triangle mesh
     * @param theta       per-face angle in the face's local frame (length F)
     * @param periodJump  per-interior-edge integer rotation count (length E,
     *                    where E = built {@code interiorEdgeCount()})
     */
    public static FaceRosyField fromExternal(ArrayMesh mesh, double[] theta, int[] periodJump) {
        FaceRosyField f = new FaceRosyField(mesh);
        if (theta.length != f.faceCount()) {
            throw new IllegalArgumentException("theta length " + theta.length
                    + " != face count " + f.faceCount());
        }
        if (periodJump.length != f.interiorEdgeCount) {
            throw new IllegalArgumentException("periodJump length " + periodJump.length
                    + " != interiorEdgeCount " + f.interiorEdgeCount);
        }
        for (int i = 0; i < theta.length; i++) f.theta[i] = theta[i];
        f.periodJump = new int[periodJump.length];
        System.arraycopy(periodJump, 0, f.periodJump, 0, periodJump.length);
        f.solved = true;
        return f;
    }
}
