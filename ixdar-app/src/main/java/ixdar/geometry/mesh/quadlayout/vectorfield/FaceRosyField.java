package ixdar.geometry.mesh.quadlayout.vectorfield;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.vectorfield.alignment.DirectionalConstraints;
import ixdar.geometry.mesh.quadlayout.vectorfield.alignment.GeodesicCurvature;
import ixdar.geometry.mesh.quadlayout.vectorfield.alignment.PrincipalCurvatureField;
import ixdar.geometry.mesh.quadlayout.vectorfield.alignment.SmoothRegions;
import ixdar.geometry.mesh.quadlayout.vectorfield.dvpsh.DvpshCrossFieldSolver;
import ixdar.geometry.mesh.quadlayout.vectorfield.greedy.GreedyRounding;
import ixdar.geometry.mesh.quadlayout.vectorfield.solver.BzkAdaptiveSolver;
import ixdar.geometry.mesh.quadlayout.vectorfield.solver.BzkSystem;

/**
 * Smooth 4-RoSy cross field on a triangulated 2-manifold via Bommes-Zimmer-Kobbelt
 * 2009 ("Mixed-Integer Quadrangulation"):
 *
 * <pre>
 *   min  Σ_{e ∈ E_int} ( θ_a − θ_b + κ_e + (π/2)·m_e )²
 * </pre>
 *
 * with θ ∈ ℝ^F per face and m ∈ ℤ on the chord set of a dual spanning tree
 * (one integer per non-tree interior edge, the cycle-space basis). Tree
 * edges are gauged to m=0; their integer values are recovered post-greedy
 * from the residual at the final θ.
 *
 * <p>This class is the orchestrator. It builds:
 * <ul>
 *   <li>per-face local frames (via {@link BaseField}),</li>
 *   <li>interior-edge adjacency,</li>
 *   <li>per-edge κ_e (parallel-transport angle between adjacent face frames),</li>
 *   <li>a dual spanning tree to identify chord vs tree edges,</li>
 * </ul>
 * then hands off to {@link BzkSystem} (immutable CSR matrix) and
 * {@link GreedyRounding} (mixed-integer outer loop, BZK09 §2.1 adaptive
 * solver ladder).
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
    private GreedyRounding.Result lastResult;

    public FaceRosyField(ArrayMesh mesh) {
        this(mesh, Double.POSITIVE_INFINITY);
    }

    /**
     * @param principalThreshold CIE*16 §3.2 ¶4 significance angle threshold,
     *     in degrees (default 70°). When finite, activates the CIE*16
     *     directional-constraint chain
     *     ({@link PrincipalCurvatureField} → {@link GeodesicCurvature} →
     *     {@link SmoothRegions} → {@link DirectionalConstraints}).
     *     Set to {@link Double#POSITIVE_INFINITY} to disable directional
     *     alignment entirely (constraints become optional per CIE*16 §4.1
     *     {0, ∞} weight semantics).
     *     <p>NOTE: this parameter was previously the BZK09 §3 anisotropy
     *     threshold τ_min ∈ [0,1] (PATCH-96, deleted). The constructor
     *     signature is preserved for backward compatibility but the value's
     *     meaning has changed.
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

        BzkSystem sys = new BzkSystem(F, E, edgeFaceA, edgeFaceB, kappa, isTreeEdge);

        double[] thetaConstraint = null;
        boolean[] constrained = null;
        if (Double.isFinite(principalThreshold)) {
            // CIE*16 §3 + §4.1 directional alignment chain:
            //   1. ACDLD03 §2.1 + CIE*16 §3.2 ¶1: per-face principal curvature.
            //   2. CIE*16 §3.2 ¶3: per-edge geodesic curvature κ^g.
            //   3. CIE*16 §3.1 + §3.2 ¶4: smooth regions + significance filter (∠F ≥ threshold).
            //   4. CIE*16 §4.1: hard θ-constraints inside kept regions (line-field projected into face frame).
            // Replaces PATCH-96's half-implementation of BZK09 §3 (single-radius shape
            // operator without validation interval) — the failure mode Campen-thesis
            // §4.4 ¶4 "Noise" describes (excess singularities on noisy or scale-dependent
            // input — observed 1019 vs paper's 36 on rocker-arm in May 2026 bench).
            double rGeoFraction = Double.parseDouble(
                    System.getProperty("ixdar.cie16.geoRadiusFraction", "0.01"));
            double bbox = boundingBoxDiagonal();
            double rGeo = bbox * rGeoFraction;                                  // ACDLD03 §2.1 ¶3 default = bbox/100
            var pdf = PrincipalCurvatureField.compute(mesh, rGeo);
            double[] kappaG = GeodesicCurvature.computePerEdge(mesh, pdf);
            boolean[] smoothFaces = GeodesicCurvature.computeSmoothFaces(mesh, kappaG, pdf);
            double significanceDeg = principalThreshold;                        // re-purposed: now degrees
            int[] regionId = SmoothRegions.detect(mesh, smoothFaces, pdf, significanceDeg);
            DirectionalConstraints.Result dc = DirectionalConstraints.compute(mesh, this, pdf, regionId);
            thetaConstraint = dc.thetaConstraint;
            constrained = dc.constrained;
        }

        // PATCH-125: route through DVPSH14 complex-polynomial solver when
        //   -Dixdar.lyon.crossFieldSolver=dvpsh. Default still BZK09. DVPSH is
        //   the paper-faithful cross-field path for CIE*16 §4.1 hard
        //   constraints (4-RoSy invariant by construction); BZK09's
        //   θ-pin formulation leaks m_e=±1 jumps at smooth-region predicate
        //   boundaries on rocker-arm-scale meshes.
        String solverChoice = System.getProperty("ixdar.lyon.crossFieldSolver", "bzk09");
        if ("dvpsh".equals(solverChoice)) {
            DvpshCrossFieldSolver.Result dres = DvpshCrossFieldSolver.solve(
                    F, E, edgeFaceA, edgeFaceB, kappa, thetaConstraint, constrained);
            for (int i = 0; i < F; i++) theta[i] = dres.theta[i];
            periodJump = dres.periodJump;
            // The bench's lastResult() introspection accessor expects a
            // GreedyRounding.Result; supply a placeholder for DVPSH.
            lastResult = null;
            solved = true;
            return;
        }

        BzkAdaptiveSolver.Options solverOpts = readSolverOptions();
        GreedyRounding.Options opts = new GreedyRounding.Options(
                solverOpts, thetaConstraint, constrained);
        GreedyRounding.Result res = GreedyRounding.solve(sys, opts);
        for (int i = 0; i < F; i++) theta[i] = res.theta[i];
        periodJump = res.periodJump;
        lastResult = res;
        solved = true;
    }

    private static BzkAdaptiveSolver.Options readSolverOptions() {
        BzkAdaptiveSolver.Options o = new BzkAdaptiveSolver.Options();
        o.useGs = "true".equals(System.getProperty("ixdar.bzk09.useGs"));
        o.useIcc = !"false".equals(System.getProperty("ixdar.bzk09.useIcc"));   // PATCH-103 default
        o.useCg = !"false".equals(System.getProperty("ixdar.bzk09.useCg"));
        o.cgMaxIter = Integer.getInteger("ixdar.bzk09.cgMaxIter", o.cgMaxIter);
        o.cgTolerance = Double.parseDouble(
                System.getProperty("ixdar.bzk09.cgTol", String.valueOf(o.cgTolerance)));
        o.iccMaxIter = Integer.getInteger("ixdar.bzk09.iccMaxIter", o.iccMaxIter);
        o.iccTolerance = Double.parseDouble(
                System.getProperty("ixdar.bzk09.iccTol", String.valueOf(o.iccTolerance)));
        return o;
    }

    /** Solver convergence stats from the last {@link #solve()} call. Null
     *  before solve(), or for the no-op F=0 case. Used by bench introspection. */
    public GreedyRounding.Result lastResult() { return lastResult; }

    /** Mesh bounding box diagonal — used by ACDLD03 §2.1 ¶3 default radius. */
    private double boundingBoxDiagonal() {
        int V = mesh.vertexCount();
        if (V == 0) return 0.0;
        Vector3f p = new Vector3f();
        mesh.vertexPosition(0, p);
        float minX = p.x, minY = p.y, minZ = p.z;
        float maxX = p.x, maxY = p.y, maxZ = p.z;
        for (int v = 1; v < V; v++) {
            mesh.vertexPosition(v, p);
            if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x;
            if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y;
            if (p.z < minZ) minZ = p.z; if (p.z > maxZ) maxZ = p.z;
        }
        double dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
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

            kappa[e] = alphaB - alphaA;
        }

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
     * Bypass the BZK solver and inject externally-computed per-face θ and
     * per-interior-edge integer periodJump. Used by
     * {@code ixdar.geometry.mesh.quadlayout.field.PrecomputedFieldImporter} to
     * load metriko's known-good stage1 cross field for downstream regression.
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
