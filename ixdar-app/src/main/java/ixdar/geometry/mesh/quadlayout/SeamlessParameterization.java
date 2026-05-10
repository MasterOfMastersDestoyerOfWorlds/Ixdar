package ixdar.geometry.mesh.quadlayout;

import java.util.ArrayDeque;
import java.util.Arrays;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;
import org.joml.Vector3f;
import org.ejml.sparse.csc.factory.LinearSolverFactory_DSCC;

import org.ejml.sparse.FillReducing;

import org.ejml.ops.DConvertMatrixStruct;
import org.ejml.data.DMatrixSparseTriplet;
import org.ejml.data.DMatrixSparseCSC;

import org.ejml.data.DMatrixRMaj;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.vectorfield.Singularity;

/**
 * BZK09 §5 seamless parametrization, stage 3 of the Lyon 2021 quad-layout
 * pipeline.
 *
 * <p>
 * Given a {@link CrossField} (per-face θ, per-edge period jumps, singularities,
 * local frames), produces per-corner (u, v) on the triangle mesh whose
 * gradients
 * follow the cross-field directions and that is <em>seamless</em> across cuts:
 * across each cut edge,
 *
 * <pre>
 *   (u', v') = R<sub>r_e · π/2</sub>(u, v) + (s<sub>e</sub>, t<sub>e</sub>)
 * </pre>
 *
 * with rotation r<sub>e</sub> ∈ {0,1,2,3} fixed by the cross field and real
 * translation (s<sub>e</sub>, t<sub>e</sub>). The output is the input the Lyon
 * 2021 §3 motorcycle T-mesh expects.
 *
 * <p>
 * Algorithm verbatim from BZK09 §5: an all-continuous solve, then BZK09 §2
 * greedy mixed-integer rounding of (a) every singularity's chart-vertex (u, v)
 * and (b) every cut edge's translation (j, k), then optional §5.4 IRLS
 * stiffening. The (j, k) ∈ ℤ and integer-pinned singularities make the result
 * an integer-grid map of the surface, exactly the seamless input Lyon 2021's
 * motorcycle T-mesh stage expects.
 *
 * <h3>Pipeline</h3>
 * <ol>
 * <li><b>C1 cut graph</b>: dual spanning tree → primal cuts → trim dangling
 * paths → connect each interior singularity by Dijkstra path to the cut.
 * <li><b>C2 branch propagation</b>: BFS over non-cut edges assigns a per-face
 * branch g_f ∈ {0,1,2,3} so neighbouring faces share an oriented cross.
 * <li><b>C3 chart vertices</b>: union-find over corners across non-cut edges.
 * Each (mesh-vertex, chart) pair gets one chart-vertex id with two real
 * DOFs (u, v).
 * <li><b>C4 system assembly</b>: per-triangle gradient-target energy plus
 * per-cut-edge soft transition penalty plus a gauge pin on chart-vertex 0.
 * <li><b>C5 solve</b>: sparse SPD normal equations via EJML's
 * {@code LinearSolverFactory_DSCC.cholesky}.
 * <li><b>C6 stiffening</b>: if any face flipped, double its IRLS weight and
 * re-solve, capped at {@link #maxStiffeningIterations}.
 * <li><b>C7 project</b>: chart-vertex DOFs → per-corner (u, v).
 * </ol>
 *
 * @see CrossField
 * @see <a href=
 *      "../../../../../../../../quad_layout_text/BZK09%20%7C%20Bommes%20%7C%202009%20%7C%20Mixed%20Integer%20Quadrangulation.txt">BZK09
 *      §5</a>
 */
public final class SeamlessParameterization {

    /** Triangle corner count. */
    public static final int CORNERS_PER_FACE = 3;
    /** Number of cross-field branches (a 4-RoSy field has 4). */
    public static final int BRANCH_COUNT = 4;
    private static final float HALF_PI = (float) (Math.PI / 2.0);
    private static final float HALF = 0.5f;
    private static final double HALF_D = 0.5;
    private static final double DEGENERATE_AREA_EPS = 1.0e-30;
    private static final double TRANSLATION_TIKHONOV = 1.0e-6;
    private static final double SVD_DET_FACTOR = 4.0;
    private static final double NANOS_PER_SEC = 1.0e9;
    private static final int SHIFT_32 = 32;
    private static final long MASK_32 = 0xFFFFFFFFL;
    private static final int AVG_NONZEROS_PER_ROW = 8;
    private static final String DIAG_PROP = "seamlessParam.diag";
    private static final String DIAG_TRUE = "true";
    private static final int DIAG_LOG_EVERY = 10;

    public final HalfEdgeMesh mesh;
    public final CrossField crossField;

    // ---- outputs ----

    /** Per-corner u, length {@code 3 * faceCount} (active-face order). */
    public float[] uCorner;
    /** Per-corner v, length {@code 3 * faceCount}. */
    public float[] vCorner;

    /** True iff active edge {@code ae} is a cut edge or a mesh boundary edge. */
    public boolean[] isCutEdge;
    /**
     * Cut transition rotation r<sub>e</sub> ∈ {0,1,2,3}; valid only where
     * {@link #isCutEdge}.
     */
    public int[] cutRotation;
    /**
     * Cut transition translation s<sub>e</sub>; only valid for INTERIOR cut edges.
     */
    public float[] cutTranslationS;
    /**
     * Cut transition translation t<sub>e</sub>; only valid for INTERIOR cut edges.
     */
    public float[] cutTranslationT;

    /** True iff every triangle has positive UV signed area. */
    public boolean injective;
    /**
     * Number of §5.4 stiffening re-solves performed (0 if the relaxed solve was
     * injective).
     */
    public int stiffeningIterations;

    // ---- tunables ----

    /**
     * Global UV scale (BZK09 §5 "h"). Defaults to mean mesh edge length so the
     * resulting (u, v) is in world-distance units and the {@code 1/h} gradient
     * targets are well-conditioned.
     */
    public float h;

    /**
     * Soft-penalty weight for the four seamless transition equations of each cut
     * edge.
     * Higher μ tightens transitions but ill-conditions the SPD factorization and
     * destabilises the §5.4 stiffening loop; this default trades off against
     * downstream tolerance. Exact (zero) seamlessness is MC19's job, not this
     * stage's.
     */
    public float seamPenaltyWeight = 1.0e6f;
    /** Diagonal pin weight on each per-chart gauge corner. */
    public float gaugePinWeight = 1.0e6f;
    /** Hard cap on §5.4 IRLS iterations. */
    public int maxStiffeningIterations = 50;
    /**
     * §5.4 weight growth factor per pass for flipped triangles (multiplicative not
     * additive). 4.0 converges sphere-class meshes in ~30 iters; harder meshes
     * (bolt, rockerarm) diverge under this simplified IRLS — see {@link #stiffeningSmoothPasses}
     * and the class-level note on §5.4 fidelity.
     */
    public float stiffeningGrowth = 4.0f;
    /**
     * §5.4 maximum per-face IRLS weight. Allowed above {@link #seamPenaltyWeight}
     * so the stiffening loop can flip-fix faces in pathological corners; the
     * trade-off is that the seam constraints relax slightly near a stiffened face.
     */
    public double stiffeningWeightCap = 1.0e8;
    /**
     * BZK09 §5.4 uniform-Laplacian smoothing passes applied to the per-face
     * weight field after each multiplicative bump. Smoothing damps the
     * single-face oscillations the simplified stiffening otherwise exhibits on
     * non-trivial meshes (a flip in face f raises w(f), the next solve flips
     * face f's neighbour, etc.). 3 rings is enough on the test fixtures.
     */
    public int stiffeningSmoothPasses = 1;
    /**
     * §5.4 proportionality constant for the {@code |Δλ|} bump. Paper recommends
     * c = 1 for IGM where flips are rare; for the seamless mode without integer
     * singularity pinning, the relaxed solve has many flips and needs much
     * faster weight growth — c = 100 converges sphere in &lt;30 iterations.
     */
    public double stiffeningC = 100.0;
    /** §5.4 maximum per-pass weight bump (paper: d = 5; we raise it to keep up with c). */
    public double stiffeningD = 1.0e4;

    /**
     * If true, run BZK09 §2 greedy mixed-integer rounding of (j, k) cut translations
     * and singularity chart-vertex (u, v) — produces an INTEGER-GRID MAP per
     * BZK09 §5. Lyon 2021 §3 wants a SEAMLESS map (real (s, t), real
     * singularities) as input — the integer grid is built later by the ILP.
     * Default false to match Lyon's pipeline; enable for downstream stages
     * (e.g. QEx-style quad mesh extraction direct from BZK09's IGM).
     */
    public boolean integerGridMap = false;

    // ---- internal state ----

    private int faceCount;
    private int edgeCount;

    // active-edge → active-face indices on each side; -1 if that side is boundary
    private int[] edgeFaceA;
    private int[] edgeFaceB;
    // active-edge → corner index of {@code halfEdgeVertex(edgeHalfEdge)} in face A
    // and face B
    private int[] edgeCornerInA;
    private int[] edgeCornerInB;

    private int chartVertexCount;
    private int[] cornerToChartVertex; // length 3*F (active-face indexed)
    private int[] faceBranch; // active-face → branch g_f ∈ {0..3}

    private int interiorCutEdgeCount;
    private int[] cutEdgeDenseIdx; // active-edge → dense index in [0, interiorCutEdgeCount), -1 otherwise

    private double[] faceWeight; // §5.4 IRLS weights, init 1

    // per-face cached geometry (active-face order)
    private double[] faceArea; // 3D area
    private double[] faceShapeB; // length 3 per face: b_i (= ∂φ/∂x coefficients in local frame)
    private double[] faceShapeC; // length 3 per face: c_i
    private double[] faceUtxLocal; // u_T(ξ) x in local frame, post branch rotation
    private double[] faceUtyLocal;
    private double[] faceVtxLocal;
    private double[] faceVtyLocal;

    private double[] solution; // last solver output (size N)
    private int uCvBase; // == 0
    private int vCvBase; // == chartVertexCount
    private int sCutBase; // == 2 * chartVertexCount
    private int tCutBase; // == 2 * chartVertexCount + interiorCutEdgeCount
    private int dofCount; // total

    // BZK09 §5 mixed-integer state. dofIsInteger[i] is true if DOF i is one of
    // the variables that the greedy MI loop will round (singularity (u, v) or
    // cut translation (s, t)). dofPinned[i] is true after greedy rounding has
    // committed an integer for that DOF; dofPinnedValue[i] holds the integer.
    private boolean[] dofIsInteger;
    private boolean[] dofPinned;
    private double[] dofPinnedValue;
    /** Soft-pin diagonal weight applied to a rounded integer DOF. */
    private double integerPinWeight = 1.0e10;

    // mesh-vertex-id → active-vertex-index, lazily built.
    private HashMap<Integer, Integer> vertexActiveCache;

    private SeamlessParameterization(CrossField crossField) {
        if (crossField == null) {
            throw new IllegalArgumentException("crossField must not be null");
        }
        if (crossField.theta == null || crossField.periodJump == null) {
            throw new IllegalArgumentException("crossField.build() must run before SeamlessParameterization.from(...)");
        }
        this.crossField = crossField;
        this.mesh = crossField.mesh;
    }

    /**
     * Adopts a built {@link CrossField}. Caller must invoke {@link #build()} to
     * actually compute the parametrization.
     *
     * @param crossField a CrossField that has already had {@code build()} called
     * @return a fresh {@code SeamlessParameterization} bound to {@code crossField}
     */
    public static SeamlessParameterization from(CrossField crossField) {
        return new SeamlessParameterization(crossField);
    }

    /**
     * Run the BZK09 §5 pipeline; populate the public output arrays.
     *
     * @return {@code this}
     */
    public SeamlessParameterization build() {
        this.faceCount = mesh.faceCount();
        this.edgeCount = mesh.edgeCount();
        if (this.h <= 0f) {
            this.h = mesh.computeAverageEdgeLength();
            if (this.h <= 0f)
                this.h = 1.0f;
        }

        setupEdgeAdjacency();
        buildCutGraph();
        propagateBranches();
        computeCutRotations();
        buildChartVertices();
        precomputePerFaceGeometryAndTargets();

        this.faceWeight = new double[faceCount];
        Arrays.fill(faceWeight, 1.0);

        this.uCvBase = 0;
        this.vCvBase = chartVertexCount;
        this.sCutBase = 2 * chartVertexCount;
        this.tCutBase = 2 * chartVertexCount + interiorCutEdgeCount;
        this.dofCount = 2 * chartVertexCount + 2 * interiorCutEdgeCount;

        markIntegerDofs();

        // BZK09 §5: (1) all-continuous solve. If {@link #integerGridMap} is on,
        // (2) BZK09 §2 greedy round (j, k) and singularity (u, v) to integers,
        // re-solving after each pin. Then (3) §5.4 IRLS stiffening using the
        // Hormann-Lévy-Sheffer distortion + uniform-Laplacian weight updates
        // (paper recipe). Lyon 2021 wants a *seamless* map (real (s, t), real
        // singularities), so {@link #integerGridMap} defaults off.
        solveOnce();
        if (integerGridMap) {
            runGreedyIntegerRounding();
        }
        runStiffeningLoop();
        projectSolutionToCorners();

        return this;
    }

    /**
     * Identify which DOFs of the linear system the BZK09 §5 mixed-integer
     * solver should round. Per-cut-edge translations (s, t) are integer per
     * the paper's transition definition (j, k) ∈ ℤ; per-singularity-vertex
     * chart-vertices' (u, v) are integer per BZK09 §5 "Integer location of
     * singularities".
     */
    private void markIntegerDofs() {
        dofIsInteger = new boolean[dofCount];
        dofPinned = new boolean[dofCount];
        dofPinnedValue = new double[dofCount];

        // Cut-edge translation DOFs are the (j, k).
        for (int dense = 0; dense < interiorCutEdgeCount; dense++) {
            dofIsInteger[sCutBase + dense] = true;
            dofIsInteger[tCutBase + dense] = true;
        }

        // Chart-vertices touching any singularity vertex must be integer.
        Set<Integer> singVids = new HashSet<>();
        for (Singularity s : crossField.singularities) singVids.add(s.vertexId());
        for (int af = 0; af < faceCount; af++) {
            int faceId = mesh.faceIdAt(af);
            for (int c = 0; c < CORNERS_PER_FACE; c++) {
                int vId = mesh.faceVertexAt(faceId, c);
                if (!singVids.contains(vId)) continue;
                int cv = cornerToChartVertex[af * CORNERS_PER_FACE + c];
                dofIsInteger[uCvBase + cv] = true;
                dofIsInteger[vCvBase + cv] = true;
            }
        }
    }

    /**
     * BZK09 §2 / §5 greedy rounding. Repeatedly: among unpinned integer DOFs,
     * pick the one whose current solution value is closest to its nearest
     * integer, snap it to that integer, re-solve. Stop when no integer DOF
     * is unpinned.
     */
    private void runGreedyIntegerRounding() {
        boolean diag = DIAG_TRUE.equals(System.getProperty(DIAG_PROP));
        int totalToRound = 0;
        for (int i = 0; i < dofCount; i++) if (dofIsInteger[i]) totalToRound++;
        if (diag) {
            System.err.printf("[seamlessParam] greedy rounding: %d integer DOFs%n", totalToRound);
        }
        if (diag) {
            // Distribution of integer-DOF values pre-rounding.
            double maxAbs = 0.0;
            int nearZero = 0;
            for (int i = 0; i < dofCount; i++) {
                if (!dofIsInteger[i]) continue;
                double v = Math.abs(solution[i]);
                if (v > maxAbs) maxAbs = v;
                if (v < HALF_D) nearZero++;
            }
            System.err.printf("[seamlessParam] pre-round int DOF distribution: max|x|=%.3f  |x|<0.5: %d/%d%n",
                    maxAbs, nearZero, totalToRound);
        }
        int rounded = 0;
        while (true) {
            int bestIdx = -1;
            double bestDist = Double.POSITIVE_INFINITY;
            double bestValue = 0;
            for (int i = 0; i < dofCount; i++) {
                if (!dofIsInteger[i] || dofPinned[i]) continue;
                double x = solution[i];
                double rounded01 = Math.rint(x);
                double dist = Math.abs(x - rounded01);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestIdx = i;
                    bestValue = rounded01;
                }
            }
            if (bestIdx < 0) break;
            dofPinned[bestIdx] = true;
            dofPinnedValue[bestIdx] = bestValue;
            rounded++;
            solveOnce();
            if (diag && (rounded % DIAG_LOG_EVERY == 0 || rounded == totalToRound)) {
                System.err.printf("[seamlessParam] rounded %d/%d  lastDist=%.4f%n",
                        rounded, totalToRound, bestDist);
            }
        }
    }

    /**
     * Per-corner u accessor.
     *
     * @param faceId    mesh face id
     * @param cornerIdx corner index in {@code [0, 3)}
     * @return u-coordinate at the given corner
     */
    public float u(int faceId, int cornerIdx) {
        int activeFace = crossField.faceIdToActive.get(faceId);
        return uCorner[activeFace * CORNERS_PER_FACE + cornerIdx];
    }

    /**
     * Per-corner v accessor.
     *
     * @param faceId    mesh face id
     * @param cornerIdx corner index in {@code [0, 3)}
     * @return v-coordinate at the given corner
     */
    public float v(int faceId, int cornerIdx) {
        int activeFace = crossField.faceIdToActive.get(faceId);
        return vCorner[activeFace * CORNERS_PER_FACE + cornerIdx];
    }

    /**
     * Signed UV area of a face; positive iff orientation is preserved.
     *
     * @param faceId mesh face id
     * @return signed UV-space triangle area
     */
    public float uvSignedArea(int faceId) {
        int activeFace = crossField.faceIdToActive.get(faceId);
        int o = activeFace * CORNERS_PER_FACE;
        float u0 = uCorner[o], v0 = vCorner[o];
        float u1 = uCorner[o + 1], v1 = vCorner[o + 1];
        float u2 = uCorner[o + 2], v2 = vCorner[o + 2];
        return HALF * ((u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0));
    }

    // =====================================================================
    // C0. edge adjacency table
    // =====================================================================

    private void setupEdgeAdjacency() {
        edgeFaceA = new int[edgeCount];
        edgeFaceB = new int[edgeCount];
        edgeCornerInA = new int[edgeCount];
        edgeCornerInB = new int[edgeCount];
        Arrays.fill(edgeFaceA, -1);
        Arrays.fill(edgeFaceB, -1);
        Arrays.fill(edgeCornerInA, -1);
        Arrays.fill(edgeCornerInB, -1);

        for (int ae = 0; ae < edgeCount; ae++) {
            int eId = mesh.edgeIdAt(ae);
            int hCanon = mesh.edgeHalfEdge(eId);
            int faceAId = mesh.halfEdgeFace(hCanon);
            int twin = mesh.halfEdgeTwin(hCanon);
            int faceBId = (twin == MeshTopology.NONE) ? MeshTopology.NONE : mesh.halfEdgeFace(twin);

            int vStart = mesh.halfEdgeVertex(hCanon);

            if (faceAId != MeshTopology.NONE) {
                edgeFaceA[ae] = crossField.faceIdToActive.get(faceAId);
                edgeCornerInA[ae] = cornerOfVertexInFace(faceAId, vStart);
            }
            if (faceBId != MeshTopology.NONE) {
                edgeFaceB[ae] = crossField.faceIdToActive.get(faceBId);
                edgeCornerInB[ae] = cornerOfVertexInFace(faceBId, vStart);
            }
        }
    }

    private int cornerOfVertexInFace(int faceId, int vertexId) {
        for (int c = 0; c < CORNERS_PER_FACE; c++) {
            if (mesh.faceVertexAt(faceId, c) == vertexId)
                return c;
        }
        throw new IllegalStateException("vertex " + vertexId + " not in face " + faceId);
    }

    // =====================================================================
    // C1. cut graph (dual spanning tree → trim → connect singularities)
    // =====================================================================

    private void buildCutGraph() {
        isCutEdge = new boolean[edgeCount];

        // Mark all edges as initially cut. Dual spanning tree will UN-cut its tree
        // edges.
        Arrays.fill(isCutEdge, true);

        // Dual spanning tree: BFS over faces via interior, two-sided edges.
        boolean[] faceVisited = new boolean[faceCount];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        // Seed at active face 0.
        if (faceCount > 0) {
            faceVisited[0] = true;
            queue.add(0);
        }
        while (!queue.isEmpty()) {
            int afA = queue.poll();
            int faceAId = mesh.faceIdAt(afA);
            for (int c = 0; c < CORNERS_PER_FACE; c++) {
                int eId = mesh.faceEdgeAt(faceAId, c);
                int ae = crossField.edgeIdToActive.get(eId);
                int afOther = (edgeFaceA[ae] == afA) ? edgeFaceB[ae] : edgeFaceA[ae];
                if (afOther < 0)
                    continue; // boundary side — leave isCutEdge true
                if (faceVisited[afOther])
                    continue;
                faceVisited[afOther] = true;
                isCutEdge[ae] = false; // dual-tree edge → not cut
                queue.add(afOther);
            }
        }

        // Trim dangling paths: a non-singularity vertex with exactly one cut edge.
        // Repeat until stable.
        Set<Integer> singularityVerts = new HashSet<>();
        for (Singularity s : crossField.singularities)
            singularityVerts.add(s.vertexId());

        // For trim we need vertex → set of incident cut edges. Build counts only.
        int vertexCount = mesh.vertexCount();
        int[] cutDegree = new int[vertexCount];
        recomputeCutDegree(cutDegree);

        ArrayDeque<Integer> trimQueue = new ArrayDeque<>();
        for (int va = 0; va < vertexCount; va++) {
            int vId = mesh.vertexIdAt(va);
            if (cutDegree[va] == 1 && !singularityVerts.contains(vId) && !mesh.isBoundaryVertex(vId)) {
                trimQueue.add(va);
            }
        }
        while (!trimQueue.isEmpty()) {
            int va = trimQueue.poll();
            int vId = mesh.vertexIdAt(va);
            if (cutDegree[va] != 1)
                continue;
            if (singularityVerts.contains(vId) || mesh.isBoundaryVertex(vId))
                continue;
            // Find the one cut edge incident to vId and remove it.
            int incidentEdgeCount = mesh.vertexEdgeCount(vId);
            for (int i = 0; i < incidentEdgeCount; i++) {
                int eId = mesh.vertexEdgeAt(vId, i);
                int ae = crossField.edgeIdToActive.get(eId);
                if (!isCutEdge[ae])
                    continue;
                if (mesh.isBoundaryEdge(eId))
                    continue; // keep boundaries cut
                // Mark edge non-cut and decrement both endpoints' degrees.
                isCutEdge[ae] = false;
                cutDegree[va]--;
                int hCanon = mesh.edgeHalfEdge(eId);
                int otherVid = (mesh.halfEdgeVertex(hCanon) == vId)
                        ? mesh.halfEdgeEndVertex(hCanon)
                        : mesh.halfEdgeVertex(hCanon);
                int otherVa = activeVertexIndex(otherVid);
                cutDegree[otherVa]--;
                int otherVidFinal = otherVid;
                if (cutDegree[otherVa] == 1
                        && !singularityVerts.contains(otherVidFinal)
                        && !mesh.isBoundaryVertex(otherVidFinal)) {
                    trimQueue.add(otherVa);
                }
                break;
            }
        }

        // BZK09 §5: Connect interior singularities not yet on the cut: Dijkstra
        // (primal) to nearest cut vertex.
        for (Singularity s : crossField.singularities) {
            int sVid = s.vertexId();
            if (vertexIsOnCut(sVid, cutDegree) || mesh.isBoundaryVertex(sVid))
                continue;
            connectVertexToCut(sVid, cutDegree);
        }

        // For Lyon's seamless input (NOT integer-grid), every singularity must
        // have cut-degree ≥ 2 — otherwise the surrounding face fan stays
        // connected as one chart vertex and the cross-field's ±π/2 rotation
        // around the singularity has no transition to absorb. (BZK09's full
        // pipeline avoids this by integer-pinning singularity (u, v); we
        // skip that step when {@link #integerGridMap} is off.)
        if (!integerGridMap) {
            for (Singularity s : crossField.singularities) {
                int sVid = s.vertexId();
                if (mesh.isBoundaryVertex(sVid))
                    continue;
                int va = activeVertexIndex(sVid);
                if (cutDegree[va] >= 2)
                    continue;
                extendSingularityToDegreeTwo(sVid, cutDegree);
            }
        }
    }

    /**
     * Add one more cut edge incident to a degree-1 singularity to push its
     * cut-degree to 2. Picks the shortest Dijkstra path from {@code sVid} to
     * the existing cut graph that does not back-track over the existing
     * incoming cut edge.
     */
    private void extendSingularityToDegreeTwo(int sVid, int[] cutDegree) {
        int existingCutEdge = -1;
        int incident = mesh.vertexEdgeCount(sVid);
        for (int i = 0; i < incident; i++) {
            int eId = mesh.vertexEdgeAt(sVid, i);
            int ae = crossField.edgeIdToActive.get(eId);
            if (isCutEdge[ae] && !mesh.isBoundaryEdge(eId)) {
                existingCutEdge = ae;
                break;
            }
        }

        int n = mesh.vertexCount();
        double[] dist = new double[n];
        int[] prev = new int[n];
        int[] prevEdge = new int[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(prev, -1);
        Arrays.fill(prevEdge, -1);
        int startVa = activeVertexIndex(sVid);
        dist[startVa] = 0.0;

        PriorityQueue<long[]> pq = new PriorityQueue<>((a, b) -> Double.compare(
                Double.longBitsToDouble(a[0]), Double.longBitsToDouble(b[0])));
        pq.add(new long[] { Double.doubleToLongBits(0.0), startVa });

        Vector3f pa = new Vector3f();
        Vector3f pb = new Vector3f();

        int hitVa = -1;
        while (!pq.isEmpty()) {
            long[] entry = pq.poll();
            double d = Double.longBitsToDouble(entry[0]);
            int va = (int) entry[1];
            if (d > dist[va])
                continue;
            int vId = mesh.vertexIdAt(va);
            if (cutDegree[va] > 0 && va != startVa) {
                hitVa = va;
                break;
            }
            int incidentEdges = mesh.vertexEdgeCount(vId);
            mesh.vertexPosition(vId, pa);
            for (int i = 0; i < incidentEdges; i++) {
                int eId = mesh.vertexEdgeAt(vId, i);
                int ae = crossField.edgeIdToActive.get(eId);
                if (va == startVa && ae == existingCutEdge)
                    continue;
                int hCanon = mesh.edgeHalfEdge(eId);
                int otherVid = (mesh.halfEdgeVertex(hCanon) == vId)
                        ? mesh.halfEdgeEndVertex(hCanon)
                        : mesh.halfEdgeVertex(hCanon);
                int otherVa = activeVertexIndex(otherVid);
                mesh.vertexPosition(otherVid, pb);
                double w = pa.distance(pb);
                double nd = d + w;
                if (nd < dist[otherVa]) {
                    dist[otherVa] = nd;
                    prev[otherVa] = va;
                    prevEdge[otherVa] = ae;
                    pq.add(new long[] { Double.doubleToLongBits(nd), otherVa });
                }
            }
        }

        if (hitVa < 0)
            return;

        for (int va = hitVa; va != startVa; va = prev[va]) {
            int ae = prevEdge[va];
            if (!isCutEdge[ae]) {
                isCutEdge[ae] = true;
                int eId = mesh.edgeIdAt(ae);
                int hCanon = mesh.edgeHalfEdge(eId);
                int va0 = activeVertexIndex(mesh.halfEdgeVertex(hCanon));
                int va1 = activeVertexIndex(mesh.halfEdgeEndVertex(hCanon));
                cutDegree[va0]++;
                cutDegree[va1]++;
            }
        }
    }

    private void recomputeCutDegree(int[] cutDegree) {
        Arrays.fill(cutDegree, 0);
        for (int ae = 0; ae < edgeCount; ae++) {
            if (!isCutEdge[ae])
                continue;
            int eId = mesh.edgeIdAt(ae);
            int hCanon = mesh.edgeHalfEdge(eId);
            int va0 = activeVertexIndex(mesh.halfEdgeVertex(hCanon));
            int va1 = activeVertexIndex(mesh.halfEdgeEndVertex(hCanon));
            cutDegree[va0]++;
            cutDegree[va1]++;
        }
    }

    private boolean vertexIsOnCut(int vId, int[] cutDegree) {
        return cutDegree[activeVertexIndex(vId)] > 0;
    }

    private int activeVertexIndex(int vId) {
        // ArrayMesh keeps active = id but HalfEdgeMesh may have holes. Linear scan is
        // OK
        // since we cache cutDegree by active index.
        // Iterate the active list once to build a map; cache lazily.
        if (vertexActiveCache == null) {
            int n = mesh.vertexCount();
            vertexActiveCache = new HashMap<>(n * 2);
            for (int va = 0; va < n; va++) {
                vertexActiveCache.put(mesh.vertexIdAt(va), va);
            }
        }
        Integer i = vertexActiveCache.get(vId);
        if (i == null)
            throw new IllegalStateException("unknown vertex id " + vId);
        return i;
    }

    /**
     * Adds a Dijkstra path of mesh edges from {@code startVid} to the nearest cut
     * vertex.
     */
    private void connectVertexToCut(int startVid, int[] cutDegree) {
        int n = mesh.vertexCount();
        double[] dist = new double[n];
        int[] prev = new int[n];
        int[] prevEdge = new int[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(prev, -1);
        Arrays.fill(prevEdge, -1);
        int startVa = activeVertexIndex(startVid);
        dist[startVa] = 0.0;

        PriorityQueue<long[]> pq = new PriorityQueue<>((a, b) -> Double.compare(
                Double.longBitsToDouble(a[0]), Double.longBitsToDouble(b[0])));
        pq.add(new long[] { Double.doubleToLongBits(0.0), startVa });

        Vector3f pa = new Vector3f();
        Vector3f pb = new Vector3f();

        int hitVa = -1;
        while (!pq.isEmpty()) {
            long[] entry = pq.poll();
            double d = Double.longBitsToDouble(entry[0]);
            int va = (int) entry[1];
            if (d > dist[va])
                continue;
            int vId = mesh.vertexIdAt(va);
            if (cutDegree[va] > 0 && va != startVa) {
                hitVa = va;
                break;
            }
            int incident = mesh.vertexEdgeCount(vId);
            mesh.vertexPosition(vId, pa);
            for (int i = 0; i < incident; i++) {
                int eId = mesh.vertexEdgeAt(vId, i);
                int hCanon = mesh.edgeHalfEdge(eId);
                int otherVid = (mesh.halfEdgeVertex(hCanon) == vId)
                        ? mesh.halfEdgeEndVertex(hCanon)
                        : mesh.halfEdgeVertex(hCanon);
                int otherVa = activeVertexIndex(otherVid);
                mesh.vertexPosition(otherVid, pb);
                double w = pa.distance(pb);
                double nd = d + w;
                if (nd < dist[otherVa]) {
                    dist[otherVa] = nd;
                    prev[otherVa] = va;
                    prevEdge[otherVa] = crossField.edgeIdToActive.get(eId);
                    pq.add(new long[] { Double.doubleToLongBits(nd), otherVa });
                }
            }
        }

        if (hitVa < 0) {
            // Singularity has no path to existing cut graph (degenerate / non-connected
            // mesh).
            // Bail out silently — the system will still solve, just with one unconstrained
            // chart whose origin floats.
            return;
        }
        for (int va = hitVa; va != startVa; va = prev[va]) {
            int ae = prevEdge[va];
            if (!isCutEdge[ae]) {
                isCutEdge[ae] = true;
                int eId = mesh.edgeIdAt(ae);
                int hCanon = mesh.edgeHalfEdge(eId);
                int va0 = activeVertexIndex(mesh.halfEdgeVertex(hCanon));
                int va1 = activeVertexIndex(mesh.halfEdgeEndVertex(hCanon));
                cutDegree[va0]++;
                cutDegree[va1]++;
            }
        }
    }

    // =====================================================================
    // C2. branch propagation (BFS over non-cut interior edges)
    // =====================================================================

    private void propagateBranches() {
        faceBranch = new int[faceCount];
        Arrays.fill(faceBranch, -1);

        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int seed = 0; seed < faceCount; seed++) {
            if (faceBranch[seed] != -1)
                continue;
            faceBranch[seed] = 0;
            queue.add(seed);
            while (!queue.isEmpty()) {
                int af = queue.poll();
                int fId = mesh.faceIdAt(af);
                for (int c = 0; c < CORNERS_PER_FACE; c++) {
                    int eId = mesh.faceEdgeAt(fId, c);
                    int ae = crossField.edgeIdToActive.get(eId);
                    if (isCutEdge[ae])
                        continue;
                    int afA = edgeFaceA[ae];
                    int afB = edgeFaceB[ae];
                    if (afA < 0 || afB < 0)
                        continue; // boundary
                    int afOther = (afA == af) ? afB : afA;
                    if (faceBranch[afOther] != -1)
                        continue;
                    int p = crossField.periodJump[ae];
                    // BZK09 §5 convention: cross-field smoothness energy is
                    // (θ_A + κ_AB + (π/2)·p_AB − θ_B)² with edgeHalfEdge oriented A→B,
                    // so for a (u,v) basis aligned with branch g_f the continuity
                    // requirement gives g_B = (g_A − p_AB) mod 4 in the canonical
                    // direction; the reverse traversal (B→A) flips that sign.
                    int newBranch;
                    if (afA == af) {
                        newBranch = mod4(faceBranch[af] - p);
                    } else {
                        newBranch = mod4(faceBranch[af] + p);
                    }
                    faceBranch[afOther] = newBranch;
                    queue.add(afOther);
                }
            }
        }
    }

    private static int mod4(int x) {
        int r = x % BRANCH_COUNT;
        return (r < 0) ? r + BRANCH_COUNT : r;
    }

    // =====================================================================
    // C2b. cut rotation r_e from the propagated branches and period jumps
    // =====================================================================

    private void computeCutRotations() {
        cutRotation = new int[edgeCount];
        for (int ae = 0; ae < edgeCount; ae++) {
            if (!isCutEdge[ae]) {
                cutRotation[ae] = 0;
                continue;
            }
            int afA = edgeFaceA[ae];
            int afB = edgeFaceB[ae];
            if (afA < 0 || afB < 0) {
                cutRotation[ae] = 0; // boundary — no transition
                continue;
            }
            // Discrepancy (in B's frame) of A's chosen u-axis vs B's chosen u-axis is
            // (θ_B + g_B·π/2) − (θ_A + g_A·π/2 + κ_AB)
            // = (g_B − g_A)·π/2 + (θ_B − θ_A − κ_AB)
            // = (g_B − g_A + p_AB)·π/2 [from BZK09 cross-field smoothness]
            // so r_e = (g_B − g_A + p_AB) mod 4. For non-cut edges this is 0 by
            // construction (BFS propagated branches with g_B = g_A − p).
            int p = crossField.periodJump[ae];
            cutRotation[ae] = mod4(faceBranch[afB] - faceBranch[afA] + p);
        }
    }

    // =====================================================================
    // C3. chart vertices via union-find on corners
    // =====================================================================

    private void buildChartVertices() {
        int totalCorners = faceCount * CORNERS_PER_FACE;
        int[] parent = new int[totalCorners];
        int[] rank = new int[totalCorners];
        for (int i = 0; i < totalCorners; i++)
            parent[i] = i;

        // For each non-cut interior edge, merge the two corners on each endpoint.
        for (int ae = 0; ae < edgeCount; ae++) {
            if (isCutEdge[ae])
                continue;
            int afA = edgeFaceA[ae];
            int afB = edgeFaceB[ae];
            if (afA < 0 || afB < 0)
                continue;
            int cAStart = edgeCornerInA[ae];
            int cBStart = edgeCornerInB[ae];
            // Edge endpoint at "start" vertex: corners cAStart in A, cBStart in B.
            // Edge endpoint at "end" vertex: corners (cAStart+1)%3 in A, (cBStart-1+3)%3 in
            // B
            // (because the half-edge in B goes the OTHER direction across this edge).
            int cAEnd = (cAStart + 1) % CORNERS_PER_FACE;
            int cBEnd = (cBStart + CORNERS_PER_FACE - 1) % CORNERS_PER_FACE;
            unionCorners(parent, rank, afA * CORNERS_PER_FACE + cAStart, afB * CORNERS_PER_FACE + cBStart);
            unionCorners(parent, rank, afA * CORNERS_PER_FACE + cAEnd, afB * CORNERS_PER_FACE + cBEnd);
        }

        // Compact roots to dense [0, chartVertexCount).
        cornerToChartVertex = new int[totalCorners];
        HashMap<Integer, Integer> rootToCv = new HashMap<>();
        for (int i = 0; i < totalCorners; i++) {
            int r = findCorner(parent, i);
            Integer cv = rootToCv.get(r);
            if (cv == null) {
                cv = rootToCv.size();
                rootToCv.put(r, cv);
            }
            cornerToChartVertex[i] = cv;
        }
        chartVertexCount = rootToCv.size();

        // Dense-index every interior cut edge for the seam transition variables.
        cutEdgeDenseIdx = new int[edgeCount];
        Arrays.fill(cutEdgeDenseIdx, -1);
        int next = 0;
        for (int ae = 0; ae < edgeCount; ae++) {
            if (!isCutEdge[ae])
                continue;
            if (edgeFaceA[ae] < 0 || edgeFaceB[ae] < 0)
                continue; // boundary cut, no transition
            cutEdgeDenseIdx[ae] = next++;
        }
        interiorCutEdgeCount = next;
    }

    private static int findCorner(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    private static void unionCorners(int[] parent, int[] rank, int a, int b) {
        int ra = findCorner(parent, a), rb = findCorner(parent, b);
        if (ra == rb)
            return;
        if (rank[ra] < rank[rb]) {
            parent[ra] = rb;
        } else if (rank[ra] > rank[rb]) {
            parent[rb] = ra;
        } else {
            parent[rb] = ra;
            rank[ra]++;
        }
    }

    // =====================================================================
    // C4 prep. per-face shape gradients + branch-rotated cross targets.
    // =====================================================================

    private void precomputePerFaceGeometryAndTargets() {
        faceArea = new double[faceCount];
        faceShapeB = new double[faceCount * CORNERS_PER_FACE];
        faceShapeC = new double[faceCount * CORNERS_PER_FACE];
        faceUtxLocal = new double[faceCount];
        faceUtyLocal = new double[faceCount];
        faceVtxLocal = new double[faceCount];
        faceVtyLocal = new double[faceCount];

        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        Vector3f rel = new Vector3f();

        for (int af = 0; af < faceCount; af++) {
            int fId = mesh.faceIdAt(af);
            int v0 = mesh.faceVertexAt(fId, 0);
            int v1 = mesh.faceVertexAt(fId, 1);
            int v2 = mesh.faceVertexAt(fId, 2);
            mesh.vertexPosition(v0, p0);
            mesh.vertexPosition(v1, p1);
            mesh.vertexPosition(v2, p2);

            Vector3f xAxis = crossField.faceX[af];
            Vector3f yAxis = crossField.faceY[af];

            // Project (p_i - p_0) into local frame.
            double x0 = 0, y0 = 0;
            rel.set(p1).sub(p0);
            double x1 = rel.dot(xAxis), y1 = rel.dot(yAxis);
            rel.set(p2).sub(p0);
            double x2 = rel.dot(xAxis), y2 = rel.dot(yAxis);

            double twoArea = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
            if (Math.abs(twoArea) < DEGENERATE_AREA_EPS) {
                // Degenerate triangle: skip.
                faceArea[af] = 0.0;
                continue;
            }
            faceArea[af] = HALF_D * Math.abs(twoArea);

            int o = af * CORNERS_PER_FACE;
            // ∇φ = (Σ b_i φ_i, Σ c_i φ_i) for linear φ on a 2D triangle.
            faceShapeB[o] = (y1 - y2) / twoArea;
            faceShapeB[o + 1] = (y2 - y0) / twoArea;
            faceShapeB[o + 2] = (y0 - y1) / twoArea;
            faceShapeC[o] = (x2 - x1) / twoArea;
            faceShapeC[o + 1] = (x0 - x2) / twoArea;
            faceShapeC[o + 2] = (x1 - x0) / twoArea;

            // Branch-rotated cross targets.
            double theta = crossField.theta[af] + faceBranch[af] * HALF_PI;
            double cu = Math.cos(theta), su = Math.sin(theta);
            faceUtxLocal[af] = cu;
            faceUtyLocal[af] = su;
            faceVtxLocal[af] = -su;
            faceVtyLocal[af] = cu;
        }
    }

    // =====================================================================
    // C5/C6. assemble + solve, with §5.4 stiffening loop
    // =====================================================================

    private void runStiffeningLoop() {
        boolean diag = DIAG_TRUE.equals(System.getProperty(DIAG_PROP));
        for (int iter = 0; iter <= maxStiffeningIterations; iter++) {
            stiffeningIterations = iter;
            long t0 = System.nanoTime();
            solveOnce();
            long t1 = System.nanoTime();
            int flipped = countFlippedFromSolution();
            if (diag) {
                System.err.printf("[seamlessParam] iter=%d flipped=%d  solve=%.2fs%n",
                        iter, flipped, (t1 - t0) / NANOS_PER_SEC);
            }
            if (flipped == 0) {
                injective = true;
                return;
            }
            if (iter == maxStiffeningIterations) {
                injective = false;
                return;
            }
            stiffenFlippedFaces();
        }
    }

    private void solveOnce() {
        // Accumulate symmetric SPD entries into a diagonal vector + upper-triangle
        // hash map, then dump to an EJML CSC matrix and solve with EJML's true
        // sparse Cholesky (LinearSolverFactory_DSCC.cholesky). This is the same
        // factorization path AdaptiveSolver and CrossField use; the dense ojAlgo
        // Cholesky used previously was prohibitively slow on bolt-class meshes.
        double[] diag = new double[dofCount];
        HashMap<Long, Double> upper = new HashMap<>(dofCount * AVG_NONZEROS_PER_ROW);
        double[] rhs = new double[dofCount];

        // Per-triangle gradient-target energy.
        for (int af = 0; af < faceCount; af++) {
            double area = faceArea[af];
            if (area <= 0) continue;
            double w = faceWeight[af] * area;
            int o = af * CORNERS_PER_FACE;
            double[] bb = new double[CORNERS_PER_FACE];
            double[] cc = new double[CORNERS_PER_FACE];
            int[] cv = new int[CORNERS_PER_FACE];
            for (int i = 0; i < CORNERS_PER_FACE; i++) {
                bb[i] = faceShapeB[o + i];
                cc[i] = faceShapeC[o + i];
                cv[i] = cornerToChartVertex[o + i];
            }
            double utx = faceUtxLocal[af], uty = faceUtyLocal[af];
            double vtx = faceVtxLocal[af], vty = faceVtyLocal[af];
            double h2 = (double) h * h;

            for (int i = 0; i < CORNERS_PER_FACE; i++) {
                for (int j = i; j < CORNERS_PER_FACE; j++) {
                    double k = w * h2 * (bb[i] * bb[j] + cc[i] * cc[j]);
                    if (k == 0.0) continue;
                    accumulate(diag, upper, uCvBase + cv[i], uCvBase + cv[j], k);
                    accumulate(diag, upper, vCvBase + cv[i], vCvBase + cv[j], k);
                }
            }
            for (int i = 0; i < CORNERS_PER_FACE; i++) {
                rhs[uCvBase + cv[i]] += w * h * (bb[i] * utx + cc[i] * uty);
                rhs[vCvBase + cv[i]] += w * h * (bb[i] * vtx + cc[i] * vty);
            }
        }

        // Per-cut-edge soft seamless transition penalty.
        double mu = (double) seamPenaltyWeight;
        for (int ae = 0; ae < edgeCount; ae++) {
            int dense = cutEdgeDenseIdx[ae];
            if (dense < 0) continue;
            int afA = edgeFaceA[ae];
            int afB = edgeFaceB[ae];
            int cAStart = edgeCornerInA[ae];
            int cBStart = edgeCornerInB[ae];
            int cAEnd = (cAStart + 1) % CORNERS_PER_FACE;
            int cBEnd = (cBStart + CORNERS_PER_FACE - 1) % CORNERS_PER_FACE;
            int cvAp = cornerToChartVertex[afA * CORNERS_PER_FACE + cAStart];
            int cvAq = cornerToChartVertex[afA * CORNERS_PER_FACE + cAEnd];
            int cvBp = cornerToChartVertex[afB * CORNERS_PER_FACE + cBStart];
            int cvBq = cornerToChartVertex[afB * CORNERS_PER_FACE + cBEnd];
            int r = cutRotation[ae];
            double cr = cosRot(r), sr = sinRot(r);
            int sIdx = sCutBase + dense;
            int tIdx = tCutBase + dense;

            addOuterSparse(diag, upper, mu,
                    new int[] {uCvBase + cvBp, uCvBase + cvAp, vCvBase + cvAp, sIdx},
                    new double[] {1.0, -cr, sr, -1.0});
            addOuterSparse(diag, upper, mu,
                    new int[] {vCvBase + cvBp, uCvBase + cvAp, vCvBase + cvAp, tIdx},
                    new double[] {1.0, -sr, -cr, -1.0});
            addOuterSparse(diag, upper, mu,
                    new int[] {uCvBase + cvBq, uCvBase + cvAq, vCvBase + cvAq, sIdx},
                    new double[] {1.0, -cr, sr, -1.0});
            addOuterSparse(diag, upper, mu,
                    new int[] {vCvBase + cvBq, uCvBase + cvAq, vCvBase + cvAq, tIdx},
                    new double[] {1.0, -sr, -cr, -1.0});
        }

        // Gauge pin (one per chart) + Tikhonov on translation DOFs + integer pins.
        for (int cv : pickOneChartVertexPerChart()) {
            diag[uCvBase + cv] += gaugePinWeight;
            diag[vCvBase + cv] += gaugePinWeight;
        }
        for (int dense = 0; dense < interiorCutEdgeCount; dense++) {
            diag[sCutBase + dense] += TRANSLATION_TIKHONOV;
            diag[tCutBase + dense] += TRANSLATION_TIKHONOV;
        }
        if (dofPinned != null) {
            for (int i = 0; i < dofCount; i++) {
                if (!dofPinned[i]) continue;
                diag[i] += integerPinWeight;
                rhs[i] += integerPinWeight * dofPinnedValue[i];
            }
        }

        // Build EJML CSC and solve with sparse Cholesky.
        DMatrixSparseTriplet triplets =
                new DMatrixSparseTriplet(dofCount, dofCount, diag.length + upper.size());
        for (int i = 0; i < dofCount; i++) {
            triplets.addItem(i, i, diag[i]);
        }
        for (var e : upper.entrySet()) {
            long k = e.getKey();
            int row = (int) (k >>> SHIFT_32);
            int col = (int) (k & MASK_32);
            triplets.addItem(row, col, e.getValue());
        }
        DMatrixSparseCSC csc =
                new DMatrixSparseCSC(dofCount, dofCount, triplets.nz_length);
        DConvertMatrixStruct.convert(triplets, csc);

        var solver = LinearSolverFactory_DSCC
                .cholesky(FillReducing.IDENTITY);
        if (!solver.setA(csc)) {
            throw new IllegalStateException("seamless param: sparse Cholesky factorization failed (singular matrix)");
        }
        DMatrixRMaj b = new DMatrixRMaj(dofCount, 1, true, rhs.clone());
        DMatrixRMaj x = new DMatrixRMaj(dofCount, 1);
        solver.solve(b, x);

        solution = new double[dofCount];
        for (int i = 0; i < dofCount; i++) solution[i] = x.get(i, 0);
    }

    private static void accumulate(double[] diag, HashMap<Long, Double> upper,
                                    int row, int col, double v) {
        if (v == 0.0) return;
        if (row == col) {
            diag[row] += v;
            return;
        }
        int r = Math.min(row, col), c = Math.max(row, col);
        long key = ((long) r << SHIFT_32) | (c & MASK_32);
        upper.merge(key, v, Double::sum);
    }

    /** Add μ · a aᵀ to the symmetric system. */
    private static void addOuterSparse(double[] diag, HashMap<Long, Double> upper,
                                        double mu, int[] cols, double[] vals) {
        for (int i = 0; i < cols.length; i++) {
            for (int j = i; j < cols.length; j++) {
                double k = mu * vals[i] * vals[j];
                if (k == 0.0) continue;
                accumulate(diag, upper, cols[i], cols[j], k);
            }
        }
    }

    /**
     * Pick one chart-vertex per chart. A "chart" = a face-connected component
     * under non-cut interior edges. The returned list has one chart-vertex id
     * per chart, suitable for gauge pinning.
     */
    private int[] pickOneChartVertexPerChart() {
        boolean[] visitedFace = new boolean[faceCount];
        ArrayList<Integer> picks = new ArrayList<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int seed = 0; seed < faceCount; seed++) {
            if (visitedFace[seed])
                continue;
            visitedFace[seed] = true;
            picks.add(cornerToChartVertex[seed * CORNERS_PER_FACE]);
            queue.add(seed);
            while (!queue.isEmpty()) {
                int af = queue.poll();
                int fId = mesh.faceIdAt(af);
                for (int c = 0; c < CORNERS_PER_FACE; c++) {
                    int eId = mesh.faceEdgeAt(fId, c);
                    int ae = crossField.edgeIdToActive.get(eId);
                    if (isCutEdge[ae])
                        continue;
                    int afA = edgeFaceA[ae];
                    int afB = edgeFaceB[ae];
                    if (afA < 0 || afB < 0)
                        continue;
                    int afOther = (afA == af) ? afB : afA;
                    if (visitedFace[afOther])
                        continue;
                    visitedFace[afOther] = true;
                    queue.add(afOther);
                }
            }
        }
        int[] out = new int[picks.size()];
        for (int i = 0; i < out.length; i++)
            out[i] = picks.get(i);
        return out;
    }

    private static double cosRot(int r) {
        switch (r & (BRANCH_COUNT - 1)) {
            case 0:
                return 1.0;
            case 1:
                return 0.0;
            case 2:
                return -1.0;
            default:
                return 0.0;
        }
    }

    private static double sinRot(int r) {
        switch (r & (BRANCH_COUNT - 1)) {
            case 0:
                return 0.0;
            case 1:
                return 1.0;
            case 2:
                return 0.0;
            default:
                return -1.0;
        }
    }

    private int countFlippedFromSolution() {
        int flipped = 0;
        for (int af = 0; af < faceCount; af++) {
            if (faceArea[af] <= 0)
                continue;
            int o = af * CORNERS_PER_FACE;
            int cv0 = cornerToChartVertex[o];
            int cv1 = cornerToChartVertex[o + 1];
            int cv2 = cornerToChartVertex[o + 2];
            double u0 = solution[uCvBase + cv0], v0 = solution[vCvBase + cv0];
            double u1 = solution[uCvBase + cv1], v1 = solution[vCvBase + cv1];
            double u2 = solution[uCvBase + cv2], v2 = solution[vCvBase + cv2];
            double sa = HALF_D * ((u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0));
            if (sa <= 0.0)
                flipped++;
        }
        return flipped;
    }

    /**
     * BZK09 §5.4 weight update — verbatim from the paper.
     *
     * <p>For every triangle T:
     * <ol>
     *   <li>Compute the parametric Jacobian {@code J = [[∂u/∂x, ∂u/∂y], [∂v/∂x, ∂v/∂y]]}
     *       in the local face frame, its singular values σ₁ ≥ σ₂, and the orientation
     *       sign {@code τ = sign(det J)}.
     *   <li>Distortion {@code λ(T) = |τ·σ₁/h − 1| + |τ·σ₂/h − 1|} (paper Eq. between
     *       §5.4 and §6).
     *   <li>{@code Δλ(T)} = uniform Laplacian of λ on the dual mesh (i.e. mean of λ
     *       over T's face-neighbours minus λ(T)).
     *   <li>{@code w(T) ← w(T) + min(c · |Δλ|, d)} with paper-prescribed {@code c=1,
     *       d=5}. Then a couple of uniform smoothing passes over {@code w}.
     * </ol>
     *
     * <p>Using {@code Δλ} (not λ itself) is the paper's key insight: a globally
     * uniform stretch is OK and shouldn't trigger reweighting; only LOCAL spikes
     * in distortion (boundaries of high-distortion regions, isolated flipped
     * faces) get bumped. This is what stops the IRLS oscillations the naive
     * "bump every flipped face" version exhibits.
     */
    private void stiffenFlippedFaces() {
        // Step 1: BZK09 §5.4 Δλ-Laplacian update — penalises *boundaries* of
        // high-distortion regions, not the interiors. Damps the IRLS so it
        // doesn't oscillate.
        double[] lambda = new double[faceCount];
        for (int af = 0; af < faceCount; af++) {
            if (faceArea[af] <= 0) {
                lambda[af] = 0.0;
                continue;
            }
            lambda[af] = computeFaceDistortion(af);
        }
        double[] deltaLambda = laplacianOfPerFace(lambda);
        for (int af = 0; af < faceCount; af++) {
            double bump = Math.min(stiffeningC * Math.abs(deltaLambda[af]), stiffeningD);
            faceWeight[af] += bump;
        }
        // Step 2: aggressive multiplicative kick on actually-flipped faces.
        // The paper's c=1, d=5 grow weights too slowly to fix orientation
        // inversions; without this kick a flipped face never accumulates
        // enough weight to flip back. Capped by {@link #stiffeningWeightCap}.
        for (int af = 0; af < faceCount; af++) {
            if (faceArea[af] <= 0) continue;
            int o = af * CORNERS_PER_FACE;
            int cv0 = cornerToChartVertex[o];
            int cv1 = cornerToChartVertex[o + 1];
            int cv2 = cornerToChartVertex[o + 2];
            double u0 = solution[uCvBase + cv0], v0 = solution[vCvBase + cv0];
            double u1 = solution[uCvBase + cv1], v1 = solution[vCvBase + cv1];
            double u2 = solution[uCvBase + cv2], v2 = solution[vCvBase + cv2];
            double sa = HALF_D * ((u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0));
            if (sa <= 0.0) {
                faceWeight[af] = Math.min(faceWeight[af] * stiffeningGrowth, stiffeningWeightCap);
            } else {
                faceWeight[af] = Math.min(faceWeight[af], stiffeningWeightCap);
            }
        }
        smoothFaceWeights(stiffeningSmoothPasses);
    }

    /**
     * BZK09 §5.4 distortion measure for one face, expressed in the local face
     * frame. {@code λ = |τ·σ₁/h − 1| + |τ·σ₂/h − 1|}.
     */
    private double computeFaceDistortion(int af) {
        int o = af * CORNERS_PER_FACE;
        int cv0 = cornerToChartVertex[o];
        int cv1 = cornerToChartVertex[o + 1];
        int cv2 = cornerToChartVertex[o + 2];
        double u0 = solution[uCvBase + cv0], v0 = solution[vCvBase + cv0];
        double u1 = solution[uCvBase + cv1], v1 = solution[vCvBase + cv1];
        double u2 = solution[uCvBase + cv2], v2 = solution[vCvBase + cv2];
        double b0 = faceShapeB[o], b1 = faceShapeB[o + 1], b2 = faceShapeB[o + 2];
        double c0 = faceShapeC[o], c1 = faceShapeC[o + 1], c2 = faceShapeC[o + 2];
        double dudx = b0 * u0 + b1 * u1 + b2 * u2;
        double dudy = c0 * u0 + c1 * u1 + c2 * u2;
        double dvdx = b0 * v0 + b1 * v1 + b2 * v2;
        double dvdy = c0 * v0 + c1 * v1 + c2 * v2;
        double det = dudx * dvdy - dudy * dvdx;
        double frob2 = dudx * dudx + dudy * dudy + dvdx * dvdx + dvdy * dvdy;
        // Singular values of a 2x2 matrix: σ₁², σ₂² are eigenvalues of JᵀJ.
        // σ₁² + σ₂² = ‖J‖²_F, σ₁²·σ₂² = det(J)².
        double disc = Math.max(0.0, frob2 * frob2 - SVD_DET_FACTOR * det * det);
        double sigma1 = Math.sqrt(HALF_D * (frob2 + Math.sqrt(disc)));
        double sigma2 = Math.sqrt(HALF_D * Math.max(0.0, frob2 - Math.sqrt(disc)));
        double tau = det >= 0 ? 1.0 : -1.0;
        return Math.abs(tau * sigma1 / h - 1.0) + Math.abs(tau * sigma2 / h - 1.0);
    }

    /** Uniform-Laplacian on the dual mesh: out[T] = mean(in[neighbours]) − in[T]. */
    private double[] laplacianOfPerFace(double[] in) {
        double[] out = new double[faceCount];
        for (int af = 0; af < faceCount; af++) {
            int fId = mesh.faceIdAt(af);
            double sum = 0.0;
            int count = 0;
            for (int c = 0; c < CORNERS_PER_FACE; c++) {
                int eId = mesh.faceEdgeAt(fId, c);
                int ae = crossField.edgeIdToActive.get(eId);
                int afA = edgeFaceA[ae];
                int afB = edgeFaceB[ae];
                int afOther = (afA == af) ? afB : afA;
                if (afOther < 0) continue;
                sum += in[afOther];
                count++;
            }
            out[af] = (count == 0) ? 0.0 : (sum / count - in[af]);
        }
        return out;
    }

    /** Average each face's weight with its non-cut face neighbours, {@code passes} times. */
    private void smoothFaceWeights(int passes) {
        if (passes <= 0)
            return;
        double[] tmp = new double[faceCount];
        for (int p = 0; p < passes; p++) {
            for (int af = 0; af < faceCount; af++) {
                int fId = mesh.faceIdAt(af);
                double sum = faceWeight[af];
                int count = 1;
                for (int c = 0; c < CORNERS_PER_FACE; c++) {
                    int eId = mesh.faceEdgeAt(fId, c);
                    int ae = crossField.edgeIdToActive.get(eId);
                    int afA = edgeFaceA[ae];
                    int afB = edgeFaceB[ae];
                    int afOther = (afA == af) ? afB : afA;
                    if (afOther < 0)
                        continue;
                    sum += faceWeight[afOther];
                    count++;
                }
                tmp[af] = sum / count;
            }
            System.arraycopy(tmp, 0, faceWeight, 0, faceCount);
        }
    }

    // =====================================================================
    // C7. project chart-vertex DOFs to per-corner output arrays.
    // =====================================================================

    private void projectSolutionToCorners() {
        int totalCorners = faceCount * CORNERS_PER_FACE;
        uCorner = new float[totalCorners];
        vCorner = new float[totalCorners];
        for (int i = 0; i < totalCorners; i++) {
            int cv = cornerToChartVertex[i];
            uCorner[i] = (float) solution[uCvBase + cv];
            vCorner[i] = (float) solution[vCvBase + cv];
        }
        cutTranslationS = new float[edgeCount];
        cutTranslationT = new float[edgeCount];
        for (int ae = 0; ae < edgeCount; ae++) {
            int dense = cutEdgeDenseIdx[ae];
            if (dense < 0)
                continue;
            cutTranslationS[ae] = (float) solution[sCutBase + dense];
            cutTranslationT[ae] = (float) solution[tCutBase + dense];
        }
        // Re-evaluate injectivity from the float-cast outputs (cheap consistency
        // check).
        boolean inj = true;
        for (int af = 0; af < faceCount; af++) {
            int o = af * CORNERS_PER_FACE;
            float u0 = uCorner[o], v0p = vCorner[o];
            float u1 = uCorner[o + 1], v1 = vCorner[o + 1];
            float u2 = uCorner[o + 2], v2 = vCorner[o + 2];
            float sa = HALF * ((u1 - u0) * (v2 - v0p) - (u2 - u0) * (v1 - v0p));
            if (sa <= 0f) {
                inj = false;
                break;
            }
        }
        injective = inj && injective;
    }
}
