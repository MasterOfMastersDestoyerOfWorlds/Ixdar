package ixdar.geometry.mesh.quadlayout.crossfield;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh.EdgeFaceIds;
import ixdar.geometry.mesh.quadlayout.NormalMatrix;
import ixdar.geometry.mesh.quadlayout.Singularity;
import ixdar.geometry.mesh.quadlayout.crossfield.constraint.BoundaryConstraints;
import ixdar.geometry.mesh.quadlayout.crossfield.constraint.CurvatureConstraints;
import ixdar.geometry.mesh.quadlayout.crossfield.constraint.FeatureEdgeConstraints;
import ixdar.geometry.mesh.quadlayout.solver.AdaptiveSolver;
import ixdar.geometry.mesh.quadlayout.solver.DirectSolver;
import ixdar.geometry.mesh.quadlayout.solver.OrderingMethod;

/**
 * A Cross Field is a set of angles and period jumps for each face and edge of a
 * mesh that follow the curvature of the mesh. A properly structured cross has
 * two direction vectors per face, in the face's local x, y basis. This "cross"
 * on each face should tell us how we'd like to dissect the underlying triangle
 * mesh into quads. Singularities are vertices where there are fewer or more
 * than 4 edges incident to the singular vertex.
 */
public class CrossField {
    /**
     * A small value used to avoid division by zero and other numerical issues.
     */
    public static final float EPSILON = 1e-12f;
    public static final float BASIS_AXIS_PICK_THRESHOLD = 0.9f;
    public static final long LOCAL_SEARCH_BUDGET_MS = 300000L;
    /**
     * The distances to search for local minima in the smoothness energy.
     */
    public static final int[] LOCAL_SEARCH_DELTAS = { -1, 1 };

    public static final int CURVATURE_INTERVAL_VALID = 0;
    public static final int CURVATURE_INTERVAL_FAIL_EMPTY = 1;
    public static final int CURVATURE_INTERVAL_FAIL_TAU = 2;
    public static final int CURVATURE_INTERVAL_FAIL_MEAN = 3;

    /** Max geodesic (primal-edge-count) radius for pair-annihilation candidates. */
    public static final int PAIR_ANNIHILATION_MAX_HOPS = 4;
    /**
     * Energy must drop by at least this (relative) amount to accept an
     * annihilation. TODO this is a magic fing number pulled out of ass
     */
    public static final double PAIR_ANNIHILATION_MIN_REL_GAIN = 0.3;

    public static volatile String lastDiagnostics = "[cross-field] no diagnostics recorded";

    private static final String LOCAL_SEARCH_TIMEOUT_MESSAGE = "local search singularity optimization timed out";

    public final float halfPi = (float) (Math.PI / 2.0);

    /**
     * The mesh that the cross field is built from.
     */
    public final HalfEdgeMesh mesh;

    /**
     * The Voronoi forest of the cross field.
     */
    public VornoiForest vornoiForest;

    /**
     * Angle from this face's local x-axis to the cross field's representative
     * x-axis, in radians.
     */
    public float[] theta;

    /**
     * Number of quarter-turns needed to match this edge's two neighboring cross
     * fields after transporting them into a common frame.
     */
    public int[] periodJump;

    /**
     * Per-face representative x-axis of the cross field.
     */
    public Vector3f[] faceX;
    /**
     * Per-face representative y-axis of the cross field.
     */
    public Vector3f[] faceY;

    /**
     * Per-edge angle from the source triangle's transported local x-axis to the
     * neighboring triangle's local x-axis, in radians.
     */
    public float[] kappa;

    /**
     * All of the vertices in the cross field that have more or less than 4 edges
     * incident to them.
     */
    public List<Singularity> singularities = new ArrayList<>();

    /**
     * How far the angle between two faces can be from flat to be considered a
     * feature edge (cos 90° = 0). Feature edges are aligned with the cross field.
     */
    public float featureDihedralCos = 0.2f;

    /**
     * Maximum number of local Gauss-Seidel iterations on the AdaptiveSolver before
     * falling back to a conjugate gradient solve.
     */
    public int solverLocalMaxIterations = 5000;

    /**
     * Maximum number of conjugate gradient iterations on the AdaptiveSolver before
     * falling back to a sparse Cholesky factorization.
     */
    public int solverCgMaxIterations = 50;

    /**
     * Map from face id to active index.
     */
    public Map<Integer, Integer> faceIdToActive;
    /**
     * Map from edge id to active index.
     */
    public Map<Integer, Integer> edgeIdToActive;

    /**
     * Raw mesh edge ids that should become quad edges: sharp feature edges and
     * boundary edges. The seamless stage uses them to keep cuts away from these
     * edges and to pin the matching parameter coordinate to an integer iso-line.
     */
    public Set<Integer> alignmentEdgeIds = new HashSet<>();

    public boolean[] faceConstrained;
    public float[] faceConstraintAngle;

    public int edgeCount;
    public int faceCount;
    public int vertexCount;

    /**
     * Target quad edge length, expressed as a fraction of the bounding-box
     * diagonal.
     */
    public float targetEdgeLengthFractionOfBounds = 0.04f;

    /**
     * Target quad edge length.
     */
    public float targetQuadEdgeLength;

    /**
     * The curvature constraints that are applied to the cross field. builds a multi
     * radius geodesic disk and integrates the curvature tensor over the disk to get
     * the principal curvatures.
     */
    public CurvatureConstraints curvatureConstraints;

    final int[] rowFaceA;
    final int[] rowFaceB;
    final double[] rowKappaPlusHalfPiP;
    final int[] rowOfEdge;

    private int interiorRowCount;

    /**
     * 
     * Cross field construction.
     * 
     * @param mesh half-edge mesh providing geometry, topology, and active-id
     *             mapping
     */
    public CrossField(HalfEdgeMesh mesh) {
        this.mesh = mesh;
        this.edgeCount = mesh.edgeCount();
        this.faceCount = mesh.faceCount();
        this.vertexCount = mesh.vertexCount();

        this.faceConstrained = new boolean[faceCount];
        this.faceConstraintAngle = new float[faceCount];
        Arrays.fill(faceConstraintAngle, Float.NaN);

        this.theta = new float[faceCount];
        this.periodJump = new int[edgeCount];
        this.kappa = new float[edgeCount];
        this.faceX = new Vector3f[faceCount];
        this.faceY = new Vector3f[faceCount];

        this.alignmentEdgeIds = new HashSet<>();

        this.interiorRowCount = 0;
        for (int i = 0; i < edgeCount; i++) {
            if (!mesh.isBoundaryEdge(mesh.edgeIdAt(i))) {
                this.interiorRowCount++;
            }
        }
        this.rowFaceA = new int[interiorRowCount];
        this.rowFaceB = new int[interiorRowCount];
        this.rowKappaPlusHalfPiP = new double[interiorRowCount];
        this.rowOfEdge = new int[edgeCount];
        Arrays.fill(rowOfEdge, -1);

        this.targetQuadEdgeLength = targetEdgeLengthFractionOfBounds * mesh.computeBoundingBoxDiagonal();
        this.curvatureConstraints = new CurvatureConstraints(mesh, this);
    }

    /**
     * Run the BZK09 pipeline (A1 frames + κ, A2 constraints, A3 Voronoi forest, A4
     * greedy mixed-integer LSQ) and extract singularities.
     *
     * @return {@code this}, with field arrays populated and singularities filled
     */
    public CrossField build() {

        faceIdToActive = new HashMap<>(mesh.faceCount() * 2);
        for (int i = 0; i < mesh.faceCount(); i++) {
            faceIdToActive.put(mesh.faceIdAt(i), i);
        }
        edgeIdToActive = new HashMap<>(mesh.edgeCount() * 2);
        for (int i = 0; i < mesh.edgeCount(); i++) {
            edgeIdToActive.put(mesh.edgeIdAt(i), i);
        }
        /*
         * A1. Local face frames Convention: x_f = first half-edge of f, projected onto
         * the tangent plane. y_f = n_f × x_f. Right-handed.
         */

        for (int faceIndex = 0; faceIndex < mesh.faceCount(); faceIndex++) {
            int faceId = mesh.faceIdAt(faceIndex);
            int halfEdge = mesh.faceHalfEdge(faceId);
            int v0 = mesh.halfEdgeVertex(halfEdge);
            int v1 = mesh.halfEdgeEndVertex(halfEdge);

            Vector3f position1 = mesh.vertexPosition(v0);
            Vector3f position2 = mesh.vertexPosition(v1);
            Vector3f xAxis = new Vector3f(position2).sub(position1);

            Vector3f normal = mesh.faceNormal(faceId);

            float xDotN = xAxis.dot(normal);
            xAxis.x -= xDotN * normal.x;
            xAxis.y -= xDotN * normal.y;
            xAxis.z -= xDotN * normal.z;
            float xLen = xAxis.length();
            if (xLen < EPSILON) {
                arbitraryTangent(normal, xAxis);
            } else {
                xAxis.div(xLen);
            }
            Vector3f yAxis = new Vector3f();
            normal.cross(xAxis, yAxis).normalize();

            faceX[faceIndex] = xAxis;
            faceY[faceIndex] = yAxis;
        }

        /*
         * A1. Transport angles κ_ij Rotate face-i's x-axis about the shared edge by the
         * dihedral angle so it lies in face-j's tangent plane. Express the rotated
         * vector in face-j's frame (faceX[j], faceY[j]): κ_ij = atan2(y-component,
         * x-component).
         */

        for (int i = 0; i < mesh.edgeCount(); i++) {
            EdgeFaceIds edgeFaceIds = mesh.edgeFaceIds(i);
            if (mesh.isBoundaryEdge(edgeFaceIds.edgeId)) {
                kappa[i] = 0f;
                continue;
            }
            Vector3f position1 = mesh.vertexPosition(edgeFaceIds.edgeStartVertex);
            Vector3f position2 = mesh.vertexPosition(edgeFaceIds.edgeEndVertex);
            Vector3f edgeDir = new Vector3f(position2).sub(position1);
            float edgeLen = edgeDir.length();
            if (edgeLen < EPSILON) {
                kappa[i] = 0f;
                continue;
            }
            edgeDir.div(edgeLen);

            Vector3f faceNormalU = mesh.faceNormal(edgeFaceIds.faceA);
            Vector3f faceNormalV = mesh.faceNormal(edgeFaceIds.faceB);

            Vector3f cross = new Vector3f(faceNormalU).cross(faceNormalV);
            float dihedral = (float) Math.atan2(cross.dot(edgeDir),
                    Math.max(-1f, Math.min(1f, faceNormalU.dot(faceNormalV))));
            float dihedralCos = (float) Math.cos(dihedral);
            float dihedralSin = (float) Math.sin(dihedral);
            Vector3f xiTransported = new Vector3f(faceX[edgeFaceIds.faceA]);
            Vector3f kCrossV = new Vector3f(edgeDir).cross(xiTransported);
            float kDotV = edgeDir.dot(xiTransported);
            float oneMinusC = 1f - dihedralCos;
            xiTransported.x = xiTransported.x * dihedralCos + kCrossV.x * dihedralSin + edgeDir.x * kDotV * oneMinusC;
            xiTransported.y = xiTransported.y * dihedralCos + kCrossV.y * dihedralSin + edgeDir.y * kDotV * oneMinusC;
            xiTransported.z = xiTransported.z * dihedralCos + kCrossV.z * dihedralSin + edgeDir.z * kDotV * oneMinusC;
            float crossDirX = xiTransported.dot(faceX[edgeFaceIds.faceB]);
            float crossDirY = xiTransported.dot(faceY[edgeFaceIds.faceB]);
            kappa[i] = (float) Math.atan2(crossDirY, crossDirX);
        }

        FeatureEdgeConstraints.applyFeatureEdgeConstraints(mesh, this);
        BoundaryConstraints.applyBoundaryConstraints(mesh, this);
        curvatureConstraints.applyCurvatureConstraints();

        int totalConstraints = 0;
        for (boolean constrained : faceConstrained) {
            if (constrained) {
                totalConstraints++;
            }
        }
        if (totalConstraints == 0 && faceCount > 0) {
            faceConstrained[0] = true;
            faceConstraintAngle[0] = 0f;
            totalConstraints = 1;
        }

        VornoiForest vornoiForest = new VornoiForest(mesh, this);
        vornoiForest.buildVoronoiSpanningForest();
        SmoothEnergySystem system = new SmoothEnergySystem(faceCount, edgeCount,
                faceConstrained, faceConstraintAngle, vornoiForest);
        system.assemble(mesh, faceIdToActive, kappa, solverLocalMaxIterations, solverCgMaxIterations);
        system.solveGreedyMIP(lastDiagnostics);
        system.unpackInto(mesh, this);
        extractSingularities();
        localSearchSingularityOptimization();
        extractSingularities();

        printSolutionDiagnostics(system);
        return this;
    }

    /**
     * BZK09 §4.2 "Local Search Singularity Optimization" post-process. For each
     * edge incident to a current singularity, try changing the period jump by ±1
     * and re-solve theta. The Laplacian-style theta-only matrix is factorized once
     * and reused across all candidate solves (the matrix is unchanged when only
     * period jumps shift; only the RHS changes).
     *
     * <p>
     * Per the paper this often eliminates spurious singularities that the greedy
     * rounding placed in flat regions, dramatically reducing the count.
     *
     * @param faceConstrained     per-face flag; constrained faces are excluded from
     *                            theta DOFs
     * @param faceConstraintAngle theta value held at constrained faces
     */
    private void localSearchSingularityOptimization() {
        long deadlineMs = System.currentTimeMillis() + LOCAL_SEARCH_BUDGET_MS;

        if (interiorRowCount == 0) {
            return;
        }
        int row = 0;
        for (int i = 0; i < edgeCount; i++) {
            int edgeId = mesh.edgeIdAt(i);
            if (mesh.isBoundaryEdge(edgeId)) {
                continue;
            }
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            int twin = mesh.halfEdgeTwin(halfEdge);
            rowFaceA[row] = faceIdToActive.get(mesh.halfEdgeFace(halfEdge));
            rowFaceB[row] = faceIdToActive.get(mesh.halfEdgeFace(twin));
            rowKappaPlusHalfPiP[row] = kappa[i] + halfPi * periodJump[i];
            rowOfEdge[i] = row;
            row++;
        }

        final NormalMatrix matrix = new NormalMatrix(faceCount, interiorRowCount,
                rowFaceA, rowFaceB, rowKappaPlusHalfPiP);

        // Coerce per-face constraint angles into a double[] for solveCompact's `start`
        // arg.
        final double[] start = new double[faceCount];
        for (int fAi = 0; fAi < faceCount; fAi++) {
            start[fAi] = faceConstrained[fAi] ? faceConstraintAngle[fAi] : 0.0;
        }

        // Factor once. Shared across all threads — solve() is what's not thread-safe,
        // so each thread needs its own handle. Build them lazily.
        final ThreadLocal<DirectSolver.CholeskyHandle> tlHandle = ThreadLocal
                .withInitial(() -> DirectSolver.factorize(matrix, faceConstrained, OrderingMethod.RCM));
        final ThreadLocal<double[]> tlRhsScratch = ThreadLocal.withInitial(() -> new double[faceCount]);
        final ThreadLocal<double[]> tlThetaScratch = ThreadLocal.withInitial(() -> new double[faceCount]);

        final DirectSolver.CholeskyHandle mainHandle = tlHandle.get();
        if (mainHandle.solver() == null) {
            return;
        }
        final double[] mainRhs = tlRhsScratch.get();
        final double[] mainTheta = tlThetaScratch.get();

        // Initial baseline solve (no perturbation).
        buildRhs(mainRhs, -1, 0);
        DirectSolver.solveCompact(mainHandle, matrix, mainRhs, mainTheta, start, faceConstrained);
        for (int fAi = 0; fAi < faceCount; fAi++) {
            theta[fAi] = (float) mainTheta[fAi];
        }
        double currentEnergy = energyOfTheta(mainTheta, -1, 0);

        // patchFacesByEdge expects the old per-edge face arrays — we still need those
        // for overlap detection in batching.
        final int[] aliFi = new int[edgeCount];
        final int[] aliFj = new int[edgeCount];
        final boolean[] interiorEdge = new boolean[edgeCount];
        for (int eAi = 0; eAi < edgeCount; eAi++) {
            int rowOf = rowOfEdge[eAi];
            if (rowOf < 0)
                continue;
            aliFi[eAi] = rowFaceA[rowOf];
            aliFj[eAi] = rowFaceB[rowOf];
            interiorEdge[eAi] = true;
        }
        final int[][] patchFacesByEdge = buildTwoHopPatchTable(edgeCount, interiorEdge, aliFi, aliFj);

        boolean improved = true;
        improved = false;
        Set<Integer> candidateEdges = new HashSet<>();
        for (Singularity s : singularities) {
            int vId = s.vertexId();
            int outCount = mesh.vertexOutgoingHalfEdgeCount(vId);
            for (int i = 0; i < outCount; i++) {
                int hh = mesh.vertexOutgoingHalfEdgeAt(vId, i);
                int eId = mesh.halfEdgeEdge(hh);
                if (!mesh.isBoundaryEdge(eId)) {
                    candidateEdges.add(edgeIdToActive.get(eId));
                }
            }
        }
        List<Integer> remaining = new ArrayList<>(candidateEdges);
        Collections.sort(remaining);
        boolean[] usedFace = new boolean[faceCount];
        while (!remaining.isEmpty()) {
            if (System.currentTimeMillis() > deadlineMs) {
                throw new IllegalStateException(LOCAL_SEARCH_TIMEOUT_MESSAGE);
            }
            Arrays.fill(usedFace, false);
            List<Integer> batch = new ArrayList<>();
            List<Integer> deferred = new ArrayList<>();
            for (int eAi : remaining) {
                int[] patch = patchFacesByEdge[eAi];
                boolean overlap = false;
                for (int f : patch) {
                    if (usedFace[f]) {
                        overlap = true;
                        break;
                    }
                }
                if (overlap) {
                    deferred.add(eAi);
                } else {
                    batch.add(eAi);
                    for (int f : patch)
                        usedFace[f] = true;
                }
            }

            final double batchCurrentEnergy = currentEnergy;
            for (int activeEdgeIndex : batch) {
                final int oldP = periodJump[activeEdgeIndex];
                DirectSolver.CholeskyHandle h = tlHandle.get();
                double[] rhsScratch = tlRhsScratch.get();
                double[] thetaScratch = tlThetaScratch.get();
                int bestDelta = 0;
                double bestTrialEnergy = batchCurrentEnergy;
                for (int delta : LOCAL_SEARCH_DELTAS) {
                    int trialP = oldP + delta;
                    buildRhs(rhsScratch, activeEdgeIndex, trialP);
                    DirectSolver.solveCompact(h, matrix, rhsScratch, thetaScratch, start,
                            faceConstrained);
                    double energy = energyOfTheta(thetaScratch, activeEdgeIndex, trialP);
                    if (energy < bestTrialEnergy) {
                        bestTrialEnergy = energy;
                        bestDelta = delta;
                    }
                }
                if (bestDelta != 0) {
                    periodJump[activeEdgeIndex] += bestDelta;
                    improved = true;
                }
            }
            if (improved) {
                buildRhs(mainRhs, -1, 0);
                DirectSolver.solveCompact(mainHandle, matrix, mainRhs, mainTheta, start,
                        faceConstrained);
                for (int fAi = 0; fAi < faceCount; fAi++) {
                    theta[fAi] = (float) mainTheta[fAi];
                }
                currentEnergy = energyOfTheta(mainTheta, -1, 0);
            }
            remaining = deferred;
        }
        extractSingularities();
    }

    /**
     * Shortest primal edge path (as active edge ids) between two vertices, bounded
     * to {@code maxHops} edges. BFS over primal vertices crossing only interior
     * edges. Returns the ordered edge list, or null if the target is unreachable
     * within the hop bound.
     */
    private int[] shortestEdgePath(int srcVertexId, int dstVertexId, int maxHops) {
        // BFS: parentEdge[v] = active edge id used to reach v; parentVertex[v]
        // = predecessor vertex id. Keyed by vertex id.
        Map<Integer, Integer> parentEdge = new HashMap<>();
        Map<Integer, Integer> parentVertex = new HashMap<>();
        Map<Integer, Integer> depth = new HashMap<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(srcVertexId);
        depth.put(srcVertexId, 0);
        while (!queue.isEmpty()) {
            int v = queue.poll();
            int d = depth.get(v);
            if (v == dstVertexId) {
                break;
            }
            if (d >= maxHops) {
                continue;
            }
            int outCount = mesh.vertexOutgoingHalfEdgeCount(v);
            for (int i = 0; i < outCount; i++) {
                int he = mesh.vertexOutgoingHalfEdgeAt(v, i);
                int eId = mesh.halfEdgeEdge(he);
                if (mesh.isBoundaryEdge(eId)) {
                    continue;
                }
                int nbr = mesh.halfEdgeEndVertex(he);
                if (depth.containsKey(nbr)) {
                    continue;
                }
                depth.put(nbr, d + 1);
                parentEdge.put(nbr, edgeIdToActive.get(eId));
                parentVertex.put(nbr, v);
                queue.add(nbr);
            }
        }
        if (!parentVertex.containsKey(dstVertexId)) {
            return null;
        }
        // Reconstruct, then reverse to src -> dst order.
        List<Integer> rev = new ArrayList<>();
        int cur = dstVertexId;
        while (cur != srcVertexId) {
            rev.add(parentEdge.get(cur));
            cur = parentVertex.get(cur);
        }
        int[] path = new int[rev.size()];
        for (int k = 0; k < rev.size(); k++) {
            path[k] = rev.get(rev.size() - 1 - k);
        }
        return path;
    }

    /**
     * TODO combine this with the single edge variant to avoid code duplication
     * {@link #buildRhs} variant perturbing several edges at once:
     * {@code periodJump[edgePath[k]]} is treated as
     * {@code periodJump[edgePath[k]] + deltas[k]}.
     */
    private void buildRhsMulti(double[] rhs, int[] edgePath, int[] deltas) {
        Arrays.fill(rhs, 0.0);
        for (int activeEdgeIndex = 0; activeEdgeIndex < edgeCount; activeEdgeIndex++) {
            int row = rowOfEdge[activeEdgeIndex];
            if (row < 0) {
                continue;
            }
            int faceA = rowFaceA[row];
            int faceB = rowFaceB[row];
            int p = periodJump[activeEdgeIndex];
            for (int k = 0; k < edgePath.length; k++) {
                if (edgePath[k] == activeEdgeIndex) {
                    p += deltas[k];
                    break;
                }
            }
            double kk = kappa[activeEdgeIndex] + halfPi * p;
            rhs[faceA] -= kk;
            rhs[faceB] += kk;
        }
    }

    /**
     * 
     * TODO combine this with the single edge variant to avoid code duplication
     * {@link #energyOfTheta} variant evaluating energy as if each
     * {@code edgePath[k]} period jump were offset by {@code deltas[k]}.
     */
    private double energyOfThetaMulti(double[] thetaFull, int[] edgePath, int[] deltas) {
        double e = 0.0;
        for (int eAi = 0; eAi < edgeCount; eAi++) {
            int r = rowOfEdge[eAi];
            if (r < 0) {
                continue;
            }
            int p = periodJump[eAi];
            for (int k = 0; k < edgePath.length; k++) {
                if (edgePath[k] == eAi) {
                    p += deltas[k];
                    break;
                }
            }
            double resid = thetaFull[rowFaceA[r]] + kappa[eAi] + halfPi * p - thetaFull[rowFaceB[r]];
            e += resid * resid;
        }
        return e;
    }

    /**
     * Build the right-hand-side for the theta-only Laplacian, walking interior
     * edges and accumulating ±(κ + (π/2)·p) into the two incident faces. If
     * {@code perturbEdge >= 0}, that edge uses {@code perturbedP} instead of
     * {@code periodJump[perturbEdge]}. Constrained-face contributions are left out
     * of the RHS — {@link AdaptiveSolver#solveCompact} folds them in via the
     * {@code start} / {@code fixed} arguments.
     */
    private void buildRhs(double[] rhs, int perturbEdge, int perturbedPeriodJump) {
        Arrays.fill(rhs, 0.0);
        for (int activeEdgeIndex = 0; activeEdgeIndex < edgeCount; activeEdgeIndex++) {
            int row = rowOfEdge[activeEdgeIndex];
            if (row < 0)
                continue;
            int faceA = rowFaceA[row];
            int faceB = rowFaceB[row];
            double k = kappa[activeEdgeIndex] + halfPi *
                    ((activeEdgeIndex == perturbEdge) ? perturbedPeriodJump : periodJump[activeEdgeIndex]);
            rhs[faceA] -= k;
            rhs[faceB] += k;
        }
    }

    /**
     * Sum of squared per-edge residuals for the given theta. {@code perturbEdge}
     * lets a worker evaluate the energy as if
     * {@code periodJump[perturbEdge] == perturbedP} without mutating shared state.
     */
    private double energyOfTheta(double[] thetaFull, int perturbEdge, int perturbedP) {
        double e = 0.0;
        for (int eAi = 0; eAi < edgeCount; eAi++) {
            int r = rowOfEdge[eAi];
            if (r < 0)
                continue;
            int p = (eAi == perturbEdge) ? perturbedP : periodJump[eAi];
            double resid = thetaFull[rowFaceA[r]] + kappa[eAi] + halfPi * p - thetaFull[rowFaceB[r]];
            e += resid * resid;
        }
        return e;
    }

    /**
     * For each interior edge, the set of face active indices reachable within two
     * dual-graph hops from either incident face. Used to build batches of candidate
     * edges with disjoint patches so their trial solves can run approximately
     * independently in parallel.
     */
    private int[][] buildTwoHopPatchTable(int edgeCount, boolean[] interiorEdge,
            int[] aliFi, int[] aliFj) {
        int[][] result = new int[edgeCount][];
        Set<Integer> patch = new HashSet<>();
        for (int eAi = 0; eAi < edgeCount; eAi++) {
            if (!interiorEdge[eAi]) {
                result[eAi] = new int[0];
                continue;
            }
            patch.clear();
            int[] seeds = { aliFi[eAi], aliFj[eAi] };
            for (int seed : seeds) {
                patch.add(seed);
                int fId = mesh.faceIdAt(seed);
                int hCount = mesh.faceHalfEdgeCount(fId);
                for (int i = 0; i < hCount; i++) {
                    int he = mesh.faceHalfEdgeAt(fId, i);
                    int twin = mesh.halfEdgeTwin(he);
                    int neighborFid = mesh.halfEdgeFace(twin);
                    if (neighborFid == MeshTopology.NONE)
                        continue;
                    int neighborAi = faceIdToActive.get(neighborFid);
                    patch.add(neighborAi);
                    int neighborHCount = mesh.faceHalfEdgeCount(neighborFid);
                    for (int j = 0; j < neighborHCount; j++) {
                        int nhe = mesh.faceHalfEdgeAt(neighborFid, j);
                        int ntwin = mesh.halfEdgeTwin(nhe);
                        int n2Fid = mesh.halfEdgeFace(ntwin);
                        if (n2Fid == MeshTopology.NONE)
                            continue;
                        patch.add(faceIdToActive.get(n2Fid));
                    }
                }
            }
            int[] arr = new int[patch.size()];
            int k = 0;
            for (int f : patch)
                arr[k++] = f;
            result[eAi] = arr;
        }
        return result;
    }

    /**
     * Pick an arbitrary unit vector in the tangent plane defined by {@code n}. Used
     * as a fallback when the natural x-axis collapses to zero length.
     *
     * @param normal unit face normal
     * @param out    scratch vector overwritten with a unit tangent perpendicular to
     *               {@code n}
     */
    public static void arbitraryTangent(Vector3f normal, Vector3f out) {
        if (Math.abs(normal.x) < BASIS_AXIS_PICK_THRESHOLD)
            out.set(1f, 0f, 0f);
        else
            out.set(0f, 1f, 0f);
        float dot = out.dot(normal);
        out.x -= dot * normal.x;
        out.y -= dot * normal.y;
        out.z -= dot * normal.z;
        out.normalize();
    }

    /*
     * B. Singularities
     *
     * Walk the 1-ring of v. For each outgoing half-edge `he`, look up its edge eId.
     * If `he` is the "natural" half-edge of eId (edgeHalfEdge(eId) == he),
     * contribute +(κ_e, p_e); otherwise contribute −(κ_e, p_e).
     *
     * BZK09/Ray08: I(v) = 1/(2π) · (angleDefect(v) + signed κ-walk) + 1/4 · signed
     * p-walk. We store 4·I as an integer.
     */

    /**
     * Compute per-vertex singularity index (×4 to keep integer) from the angle
     * defect plus signed κ- and period-walks around the 1-ring; populates
     * {@link #singularityIndexQuarter} and {@link #singularities}.
     *
     * @return singularity list (also stored in {@link #singularities}); excludes
     *         boundary vertices and zero-index interior vertices
     */
    public List<Singularity> extractSingularities() {
        int vertexCount = mesh.vertexCount();
        singularities.clear();
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();

        for (int vAi = 0; vAi < vertexCount; vAi++) {
            int vId = mesh.vertexIdAt(vAi);
            if (mesh.isBoundaryVertex(vId))
                continue;
            Vector3f vPos = mesh.vertexPosition(vId);

            float angleSum = 0f;
            int faces = mesh.vertexFaceCount(vId);
            for (int i = 0; i < faces; i++) {
                int fId = mesh.vertexFaceAt(vId, i);
                angleSum += mesh.interiorAngleAtVertex(fId, vId, vPos, a, b);
            }
            float defect = (float) (2.0 * Math.PI) - angleSum;

            float signedKappaSum = 0f;
            int signedPeriodSum = 0;
            int outCount = mesh.vertexOutgoingHalfEdgeCount(vId);
            for (int i = 0; i < outCount; i++) {
                int he = mesh.vertexOutgoingHalfEdgeAt(vId, i);
                int eId = mesh.halfEdgeEdge(he);
                if (mesh.isBoundaryEdge(eId))
                    continue;
                int eAi = edgeIdToActive.get(eId);
                int sign = (he == mesh.edgeHalfEdge(eId)) ? 1 : -1;
                signedKappaSum += sign * kappa[eAi];
                signedPeriodSum += sign * periodJump[eAi];
            }

            float iTimes4 = (float) (((defect + signedKappaSum) * 2.0) / Math.PI) + signedPeriodSum;
            int iQuarter = Math.round(iTimes4);
            if (iQuarter != 0) {
                singularities.add(new Singularity(vId, iQuarter));
            }
        }
        return singularities;
    }

    /**
     * Print one-shot solution-quality diagnostics (solver path counts, residuals,
     * smoothness energy, singularity histogram) to stdout.
     *
     * @param system the smooth-energy linear system after the greedy MIP solve
     */
    public void printSolutionDiagnostics(SmoothEnergySystem system) {
        double avgBatch = system.batchCount > 0
                ? (double) system.totalBatchSize / system.batchCount
                : 0.0;
        double avgInitialQueue = system.batchCount > 0
                ? (double) system.totalLocalInitialQueueSize / system.batchCount
                : 0.0;
        System.out.printf(
                "[cross-field] adaptive localGS=%d cg=%d direct=%d failed=%d localIters=%d cgIters=%d localCapHits=%d capFace=%d capChord=%d maxCapResidual=%.6g avgSeedQueue=%.3f maxQueue=%d batches=%d avgBatch=%.3f maxBatch=%d rejectOverlap=%d rejectRoundoff=%d%n",
                system.localGsConverged, system.cgConverged, system.directFallbacks,
                system.failedSolves, system.totalLocalGsIterations, system.totalCgIterations,
                system.localGsCapHits, system.localCapFaceRows, system.localCapChordRows,
                system.maxLocalCapResidual, avgInitialQueue, system.maxLocalQueueSize,
                system.batchCount, avgBatch, system.maxBatchSize,
                system.batchRejectedByOverlap, system.batchRejectedByRoundoff);

        Map<Integer, Integer> histogram = new HashMap<>();
        for (Singularity s : singularities) {
            histogram.merge(s.index4(), 1, Integer::sum);
        }
        System.out.printf("[cross-field] singularityHistogram=%s%n", histogram);
    }

    /**
     * Reduce {@code angle} into the half-open interval {@code [0, PI/2)}.
     *
     * @param angle angle in radians
     * @return canonical representative of {@code angle} modulo PI/2
     */
    public static float canonicalizeMod(float angle) {
        float halfPi = (float) (Math.PI / 2.0);
        float r = (float) (angle - halfPi * Math.floor(angle / halfPi));
        if (r < 0)
            r += halfPi;
        return r;
    }

}
