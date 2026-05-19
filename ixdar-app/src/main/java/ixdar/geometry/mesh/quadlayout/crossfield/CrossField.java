package ixdar.geometry.mesh.quadlayout.crossfield;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.NormalMatrix;
import ixdar.geometry.mesh.quadlayout.Singularity;
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
    public static final float RADIUS_STABILITY_WINDOW_FRACTION = 0.25f;
    public static final float SINGLE_RADIUS_RATIO_THRESHOLD = 1.001f;
    public static final long LOCAL_SEARCH_BUDGET_MS = 3000L;
    /**
     * The distances to search for local minima in the smoothness energy.
     */
    public static final int[] LOCAL_SEARCH_DELTAS = { -1, 1, -2, 2 };

    public static volatile String lastDiagnostics = "[cross-field] no diagnostics recorded";

    public static final int CURVATURE_INTERVAL_VALID = 0;
    public static final int CURVATURE_INTERVAL_FAIL_EMPTY = 1;
    public static final int CURVATURE_INTERVAL_FAIL_TAU = 2;
    public static final int CURVATURE_INTERVAL_FAIL_MEAN = 3;

    /**
     * The mesh that the cross field is built from.
     */
    public final HalfEdgeMesh mesh;

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
     * Per-face representative x-axis of the cross field
     */
    public Vector3f[] faceX;
    /**
     * Per-face representative y-axis of the cross field
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
     * Minimum 0-to-1 bending contrast before the strongest bend direction is
     * trusted as a cross-field constraint. A value near 0 means the surface bends
     * similarly in every direction; a value near 1 means one direction dominates.
     */
    public float minimumCurvatureContrast = 0.8f;

    /**
     * Scale used to reject nearly flat regions before adding curvature-based
     * cross-field constraints. The actual threshold is this value divided by the
     * mesh bounding-sphere radius, so it scales with model size.
     */
    public float curvatureScaleK = 0.1f;

    /**
     * BZK09 §3 geodesic-disk radius series. Paper: r ∈ [r0, r1] with r0 = average
     * edge length, r1 = h (target edge length). {@code radiusStartMul} multiplies
     * the average edge length to produce r0
     */
    public float radiusStartMul = 5.0f;

    /**
     * Geometric ratio between consecutive radii in the radius series.
     */
    public float radiusRatio = (float) Math.sqrt(2.0);

    /**
     * How far the angle between two faces can be from flat to be considered a
     * feature edge (cos 90° = 0). Feature edges are aligned with the cross field.
     */
    public float featureDihedralCos = 0f;

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
     * Target quad edge length, expressed as a fraction of the bounding-box
     * diagonal.
     */
    public float targetEdgeLengthFractionOfBounds = 0.01f;

    /**
     * Target quad edge length.
     */
    public float targetQuadEdgeLength;

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

    public int[] vertexInDiskStamp;
    public int[] faceInDiskStamp;
    public int[] edgeProcessedStamp;
    public float[] vertexDistance;
    public int[] verticesVisited;
    public int curvatureStamp = 0;

    /**
     * 
     * Cross field construction.
     * 
     * @param mesh half-edge mesh providing geometry, topology, and active-id
     *             mapping
     */
    public CrossField(HalfEdgeMesh mesh) {
        this.mesh = mesh;
    }

    /**
     * Run the BZK09 pipeline (A1 frames + κ, A2 constraints, A3 Voronoi forest, A4
     * greedy mixed-integer LSQ) and extract singularities.
     *
     * @return {@code this}, with field arrays populated and singularities filled
     */
    public CrossField build() {
        mesh.computeNormals();

        int faceCount = mesh.faceCount();
        int edgeCount = mesh.edgeCount();
        int vertexCount = mesh.vertexCount();

        this.vertexInDiskStamp = new int[vertexCount];
        this.faceInDiskStamp = new int[faceCount];
        this.edgeProcessedStamp = new int[edgeCount];
        this.vertexDistance = new float[vertexCount];
        this.verticesVisited = new int[vertexCount];

        this.theta = new float[faceCount];
        this.periodJump = new int[edgeCount];
        this.kappa = new float[edgeCount];
        this.faceX = new Vector3f[faceCount];
        this.faceY = new Vector3f[faceCount];
        this.alignmentEdgeIds = new HashSet<>();

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
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        Vector3f n = new Vector3f();

        for (int faceIndex = 0; faceIndex < mesh.faceCount(); faceIndex++) {
            int fId = mesh.faceIdAt(faceIndex);
            int halfEdge = mesh.faceHalfEdge(fId);
            int v0 = mesh.halfEdgeVertex(halfEdge);
            int v1 = mesh.halfEdgeEndVertex(halfEdge);

            mesh.vertexPosition(v0, a);
            mesh.vertexPosition(v1, b);
            Vector3f xAxis = new Vector3f(b).sub(a);

            mesh.faceNormal(fId, n);

            float xDotN = xAxis.dot(n);
            xAxis.x -= xDotN * n.x;
            xAxis.y -= xDotN * n.y;
            xAxis.z -= xDotN * n.z;
            float xLen = xAxis.length();
            if (xLen < EPSILON) {
                arbitraryTangent(n, xAxis);
            } else {
                xAxis.div(xLen);
            }
            Vector3f yAxis = new Vector3f();
            n.cross(xAxis, yAxis).normalize();

            faceX[faceIndex] = xAxis;
            faceY[faceIndex] = yAxis;
        }

        /*
         * A1. Transport angles κ_ij Rotate face-i's x-axis about the shared edge by the
         * dihedral angle so it lies in face-j's tangent plane. Express the rotated
         * vector in face-j's frame (faceX[j], faceY[j]): κ_ij = atan2(y-component,
         * x-component).
         */

        Vector3f position1 = new Vector3f();
        Vector3f position2 = new Vector3f();
        Vector3f edgeDir = new Vector3f();
        Vector3f faceNormalU = new Vector3f();
        Vector3f faceNormalV = new Vector3f();
        Vector3f cross = new Vector3f();
        Vector3f xiTransported = new Vector3f();

        for (int i = 0; i < mesh.edgeCount(); i++) {
            int edge = mesh.edgeIdAt(i);
            if (mesh.isBoundaryEdge(edge)) {
                kappa[i] = 0f;
                continue;
            }
            int halfEdge = mesh.edgeHalfEdge(edge);
            int twin = mesh.halfEdgeTwin(halfEdge);
            int halfEdgeFaceId1 = mesh.halfEdgeFace(halfEdge);
            int haldEdgeFaceIndex1 = faceIdToActive.get(halfEdgeFaceId1);
            int halfEdgeFaceId2 = mesh.halfEdgeFace(twin);
            int halfEdgeFaceIndex2 = faceIdToActive.get(halfEdgeFaceId2);

            int vertexId1 = mesh.halfEdgeVertex(halfEdge);
            int vertexId2 = mesh.halfEdgeEndVertex(halfEdge);
            mesh.vertexPosition(vertexId1, position1);
            mesh.vertexPosition(vertexId2, position2);
            edgeDir.set(position2).sub(position1);
            float edgeLen = edgeDir.length();
            if (edgeLen < EPSILON) {
                kappa[i] = 0f;
                continue;
            }
            edgeDir.div(edgeLen);

            mesh.faceNormal(halfEdgeFaceId1, faceNormalU);
            mesh.faceNormal(halfEdgeFaceId2, faceNormalV);

            float cosD = Math.max(-1f, Math.min(1f, faceNormalU.dot(faceNormalV)));
            faceNormalU.cross(faceNormalV, cross);
            float sinD = cross.dot(edgeDir);
            float dihedral = (float) Math.atan2(sinD, cosD);

            xiTransported.set(faceX[haldEdgeFaceIndex1]);

            float dihedralCos = (float) Math.cos(dihedral);
            float dihedralSin = (float) Math.sin(dihedral);
            Vector3f kCrossV = new Vector3f();
            edgeDir.cross(xiTransported, kCrossV);
            float kDotV = edgeDir.dot(xiTransported);
            float oneMinusC = 1f - dihedralCos;
            xiTransported.x = xiTransported.x * dihedralCos + kCrossV.x * dihedralSin + edgeDir.x * kDotV * oneMinusC;
            xiTransported.y = xiTransported.y * dihedralCos + kCrossV.y * dihedralSin + edgeDir.y * kDotV * oneMinusC;
            xiTransported.z = xiTransported.z * dihedralCos + kCrossV.z * dihedralSin + edgeDir.z * kDotV * oneMinusC;

            float crossDirX = xiTransported.dot(faceX[halfEdgeFaceIndex2]);
            float crossDirY = xiTransported.dot(faceY[halfEdgeFaceIndex2]);
            kappa[i] = (float) Math.atan2(crossDirY, crossDirX);
        }

        boolean[] faceConstrained = new boolean[faceCount];
        float[] faceConstraintAngle = new float[faceCount];
        Arrays.fill(faceConstraintAngle, Float.NaN);

        float averageEdgeLength = mesh.computeAverageEdgeLength();
        float boundingSphereRadius = mesh.computeBoundingSphereRadius();
        float curvatureK = curvatureScaleK / Math.max(boundingSphereRadius, EPSILON);

        float targetLength = targetEdgeLengthFractionOfBounds * mesh.computeBoundingBoxDiagonal();
        this.targetQuadEdgeLength = targetLength > 0f ? targetLength : averageEdgeLength;

        applyFeatureEdgeConstraints(faceConstrained, faceConstraintAngle);
        applyBoundaryConstraints(faceConstrained, faceConstraintAngle);
        applyCurvatureConstraints(
                faceConstrained, faceConstraintAngle, averageEdgeLength, curvatureK);
        int totalConstraints = countTrue(faceConstrained);
        if (totalConstraints == 0 && faceCount > 0) {

            faceConstrained[0] = true;
            faceConstraintAngle[0] = 0f;
            totalConstraints = 1;
        }

        boolean[] periodFixed = new boolean[edgeCount];
        int[] periodValue = new int[edgeCount];

        Set<Integer> forestEdgeIds = buildVoronoiSpanningForest(faceConstrained);
        for (int edge : forestEdgeIds) {
            periodFixed[edge] = true;
            periodValue[edge] = 0;
        }
        for (int eAi = 0; eAi < edgeCount; eAi++) {
            if (periodFixed[eAi])
                continue;
            int eId = mesh.edgeIdAt(eAi);
            if (mesh.isBoundaryEdge(eId)) {
                periodFixed[eAi] = true;
                periodValue[eAi] = 0;
                continue;
            }
            int he = mesh.edgeHalfEdge(eId);
            int twin = mesh.halfEdgeTwin(he);
            int faceI = mesh.halfEdgeFace(he);
            int faceJ = mesh.halfEdgeFace(twin);
            int faceIIdx = faceIdToActive.get(faceI);
            int faceJIdx = faceIdToActive.get(faceJ);
            if (faceConstrained[faceIIdx] && faceConstrained[faceJIdx]) {
                float diff = faceConstraintAngle[faceJIdx] - faceConstraintAngle[faceIIdx] - kappa[eAi];
                int p = Math.round(diff / (float) (Math.PI / 2.0));
                periodFixed[eAi] = true;
                periodValue[eAi] = p;
            }
        }

        SmoothEnergySystem system = new SmoothEnergySystem(faceCount, edgeCount,
                faceConstrained, faceConstraintAngle, periodFixed, periodValue);
        system.assemble(mesh, faceIdToActive, kappa, solverLocalMaxIterations, solverCgMaxIterations);
        system.solveGreedyMIP(lastDiagnostics);
        system.unpackInto(mesh, this);
        extractSingularities();

        localSearchSingularityOptimization(faceConstrained, faceConstraintAngle);
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
    private void localSearchSingularityOptimization(boolean[] faceConstrained, float[] faceConstraintAngle) {
        long deadlineMs = System.currentTimeMillis() + LOCAL_SEARCH_BUDGET_MS;
        final int edgeCount = mesh.edgeCount();
        final int faceCount = mesh.faceCount();
        final float halfPi = (float) (Math.PI / 2.0);

        // Count interior edges and collect per-row data for the theta-only Laplacian.
        int interiorRowCount = 0;
        for (int i = 0; i < edgeCount; i++) {
            if (!mesh.isBoundaryEdge(mesh.edgeIdAt(i))) {
                interiorRowCount++;
            }
        }
        if (interiorRowCount == 0) {
            return;
        }
        final int[] rowFaceI = new int[interiorRowCount];
        final int[] rowFaceJ = new int[interiorRowCount];
        final double[] rowKappaPlusHalfPiP = new double[interiorRowCount];
        final int[] rowOfEdge = new int[edgeCount]; // -1 for boundary, row index otherwise
        Arrays.fill(rowOfEdge, -1);
        int row = 0;
        for (int i = 0; i < edgeCount; i++) {
            int edgeId = mesh.edgeIdAt(i);
            if (mesh.isBoundaryEdge(edgeId)) {
                continue;
            }
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            int twin = mesh.halfEdgeTwin(halfEdge);
            rowFaceI[row] = faceIdToActive.get(mesh.halfEdgeFace(halfEdge));
            rowFaceJ[row] = faceIdToActive.get(mesh.halfEdgeFace(twin));
            rowKappaPlusHalfPiP[row] = kappa[i] + halfPi * periodJump[i];
            rowOfEdge[i] = row;
            row++;
        }

        final NormalMatrix matrix = new NormalMatrix(faceCount, interiorRowCount,
                rowFaceI, rowFaceJ, rowKappaPlusHalfPiP);

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
        buildRhs(mainRhs, faceCount, edgeCount, rowOfEdge, rowFaceI, rowFaceJ,
                faceConstrained, faceConstraintAngle, halfPi, /* perturbEdge */ -1, /* perturbedP */ 0);
        DirectSolver.solveCompact(mainHandle, matrix, mainRhs, mainTheta, start, faceConstrained);
        for (int fAi = 0; fAi < faceCount; fAi++) {
            theta[fAi] = (float) mainTheta[fAi];
        }
        double currentEnergy = energyOfTheta(mainTheta, edgeCount, rowOfEdge, rowFaceI, rowFaceJ,
                halfPi, -1, 0);

        // patchFacesByEdge expects the old per-edge face arrays — we still need those
        // for overlap detection in batching.
        final int[] aliFi = new int[edgeCount];
        final int[] aliFj = new int[edgeCount];
        final boolean[] interiorEdge = new boolean[edgeCount];
        for (int eAi = 0; eAi < edgeCount; eAi++) {
            int rowOf = rowOfEdge[eAi];
            if (rowOf < 0)
                continue;
            aliFi[eAi] = rowFaceI[rowOf];
            aliFj[eAi] = rowFaceJ[rowOf];
            interiorEdge[eAi] = true;
        }
        final int[][] patchFacesByEdge = buildTwoHopPatchTable(edgeCount, interiorEdge, aliFi, aliFj);

        final int nWorkers = Math.min(Runtime.getRuntime().availableProcessors(), 8);
        ExecutorService pool = Executors.newFixedThreadPool(nWorkers, r -> {
            Thread t = new Thread(r, "cross-field-localsearch");
            t.setDaemon(true);
            return t;
        });

        boolean improved = true;
        int passes = 0;
        int totalCandidates = 0;
        int totalAccepts = 0;
        int totalBatches = 0;
        try {
            while (improved && passes < 4) {
                improved = false;
                passes++;
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
                        break;
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
                    List<Callable<TrialResult>> tasks = new ArrayList<>(batch.size());
                    for (int eAi : batch) {
                        final int eAiF = eAi;
                        final int oldP = periodJump[eAi];
                        tasks.add(() -> {
                            DirectSolver.CholeskyHandle h = tlHandle.get();
                            double[] rhsScratch = tlRhsScratch.get();
                            double[] thetaScratch = tlThetaScratch.get();
                            int bestDelta = 0;
                            double bestTrialE = batchCurrentEnergy;
                            for (int delta : LOCAL_SEARCH_DELTAS) {
                                int trialP = oldP + delta;
                                buildRhs(rhsScratch, faceCount, edgeCount, rowOfEdge, rowFaceI, rowFaceJ,
                                        faceConstrained, faceConstraintAngle, halfPi, eAiF, trialP);
                                DirectSolver.solveCompact(h, matrix, rhsScratch, thetaScratch, start,
                                        faceConstrained);
                                double e = energyOfTheta(thetaScratch, edgeCount, rowOfEdge, rowFaceI,
                                        rowFaceJ, halfPi, eAiF, trialP);
                                if (e < bestTrialE) {
                                    bestTrialE = e;
                                    bestDelta = delta;
                                }
                            }
                            return new TrialResult(eAiF, bestDelta, bestTrialE);
                        });
                    }
                    List<TrialResult> results = new ArrayList<>(batch.size());
                    try {
                        for (Future<TrialResult> f : pool.invokeAll(tasks)) {
                            results.add(f.get());
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (ExecutionException ee) {
                        throw new RuntimeException(ee.getCause());
                    }
                    totalCandidates += batch.size() * LOCAL_SEARCH_DELTAS.length;
                    totalBatches++;

                    results.sort((a, b) -> Integer.compare(a.eAi, b.eAi));
                    int batchAccepts = 0;
                    for (TrialResult r : results) {
                        if (r.bestDelta != 0) {
                            periodJump[r.eAi] += r.bestDelta;
                            batchAccepts++;
                        }
                    }
                    if (batchAccepts > 0) {
                        improved = true;
                        totalAccepts += batchAccepts;

                        buildRhs(mainRhs, faceCount, edgeCount, rowOfEdge, rowFaceI, rowFaceJ,
                                faceConstrained, faceConstraintAngle, halfPi, -1, 0);
                        DirectSolver.solveCompact(mainHandle, matrix, mainRhs, mainTheta, start,
                                faceConstrained);
                        for (int fAi = 0; fAi < faceCount; fAi++) {
                            theta[fAi] = (float) mainTheta[fAi];
                        }
                        currentEnergy = energyOfTheta(mainTheta, edgeCount, rowOfEdge, rowFaceI,
                                rowFaceJ, halfPi, -1, 0);
                    }
                    remaining = deferred;
                }

                extractSingularities();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Build the right-hand-side for the theta-only Laplacian, walking interior
     * edges and accumulating ±(κ + (π/2)·p) into the two incident faces. If
     * {@code perturbEdge >= 0}, that edge uses {@code perturbedP} instead of
     * {@code periodJump[perturbEdge]}. Constrained-face contributions are left out
     * of the RHS — {@link AdaptiveSolver#solveCompact} folds them in via the
     * {@code start} / {@code fixed} arguments.
     */
    private void buildRhs(double[] rhs, int faceCount, int edgeCount,
            int[] rowOfEdge, int[] rowFaceI, int[] rowFaceJ,
            boolean[] faceConstrained, float[] faceConstraintAngle,
            float halfPi, int perturbEdge, int perturbedP) {
        Arrays.fill(rhs, 0.0);
        for (int eAi = 0; eAi < edgeCount; eAi++) {
            int r = rowOfEdge[eAi];
            if (r < 0)
                continue;
            int fi = rowFaceI[r];
            int fj = rowFaceJ[r];
            int p = (eAi == perturbEdge) ? perturbedP : periodJump[eAi];
            double k = kappa[eAi] + halfPi * p;
            rhs[fi] -= k;
            rhs[fj] += k;
        }
    }

    /**
     * Sum of squared per-edge residuals for the given theta. {@code perturbEdge}
     * lets a worker evaluate the energy as if
     * {@code periodJump[perturbEdge] == perturbedP} without mutating shared state.
     */
    private double energyOfTheta(double[] thetaFull, int edgeCount, int[] rowOfEdge,
            int[] rowFaceI, int[] rowFaceJ, float halfPi, int perturbEdge, int perturbedP) {
        double e = 0.0;
        for (int eAi = 0; eAi < edgeCount; eAi++) {
            int r = rowOfEdge[eAi];
            if (r < 0)
                continue;
            int p = (eAi == perturbEdge) ? perturbedP : periodJump[eAi];
            double resid = thetaFull[rowFaceI[r]] + kappa[eAi] + halfPi * p - thetaFull[rowFaceJ[r]];
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

    /**
     * A2. Directional constraints from principal curvature
     *
     * @param faceConstrained     per-face flag, set to true for faces that receive
     *                            a constraint
     * @param faceConstraintAngle per-face constraint angle (in face-local frame);
     *                            written for newly constrained faces
     * @param averageEdgeLength   pre-computed mean edge length
     * @param curvatureK          BZK09 §3 mean-curvature threshold
     * @return number of newly constrained faces
     */

    public int applyCurvatureConstraints(boolean[] faceConstrained,
            float[] faceConstraintAngle,
            float averageEdgeLength,
            float curvatureK) {
        Vector3f vPos = new Vector3f();
        Vector3f vNormal = new Vector3f();
        Vector3f e1 = new Vector3f();
        Vector3f e2 = new Vector3f();
        int addedConstraints = 0;
        CurvatureConstraintStats stats = new CurvatureConstraintStats();
        float stabilityWindow = RADIUS_STABILITY_WINDOW_FRACTION * targetQuadEdgeLength;

        List<Float> radii = new ArrayList<>();
        float startRadius = radiusStartMul * averageEdgeLength;
        if (radiusRatio <= SINGLE_RADIUS_RATIO_THRESHOLD || targetQuadEdgeLength <= startRadius) {
            radii.add(targetQuadEdgeLength);
        } else {
            for (float r = startRadius; r <= targetQuadEdgeLength + EPSILON; r *= radiusRatio) {
                radii.add(r);
            }
        }

        for (int vAi = 0; vAi < mesh.vertexCount(); vAi++) {
            stats.verticesVisited++;
            int vId = mesh.vertexIdAt(vAi);
            if (mesh.isBoundaryVertex(vId)) {
                stats.boundaryVertices++;
                continue;
            }
            mesh.vertexPosition(vId, vPos);
            mesh.vertexNormal(vId, vNormal);
            arbitraryTangent(vNormal, e1);
            vNormal.cross(e1, e2).normalize();

            List<Float> anglesMaxDir = new ArrayList<>();
            List<Float> kappaMaxList = new ArrayList<>();
            List<Float> kappaMinList = new ArrayList<>();
            List<Float> validRadii = new ArrayList<>();

            for (float r : radii) {
                float[] T = integrateCurvatureTensor(vId, vPos, vNormal, e1, e2, r);
                if (T == null)
                    continue;

                float t00 = T[0];
                float t01 = T[1];
                float t11 = T[2];
                float trace = t00 + t11;
                float diff = t00 - t11;
                float disc = (float) Math.sqrt(diff * diff + 4f * t01 * t01);
                float lambda1 = 0.5f * (trace + disc);
                float lambda2 = 0.5f * (trace - disc);
                float eigBig, eigSmall;
                if (Math.abs(lambda1) >= Math.abs(lambda2)) {
                    eigBig = lambda1;
                    eigSmall = lambda2;
                } else {
                    eigBig = lambda2;
                    eigSmall = lambda1;
                }
                float vx, vy;
                if (Math.abs(t01) > EPSILON) {
                    vx = eigBig - t11;
                    vy = t01;
                } else if (Math.abs(eigBig - t00) < Math.abs(eigBig - t11)) {
                    vx = 1f;
                    vy = 0f;
                } else {
                    vx = 0f;
                    vy = 1f;
                }
                float angle = (float) Math.atan2(vy, vx);
                kappaMaxList.add(eigBig);
                kappaMinList.add(eigSmall);
                anglesMaxDir.add(angle);
                validRadii.add(r);
            }
            if (anglesMaxDir.isEmpty()) {
                stats.noCurvatureSamples++;
                continue;
            }

            int bestIdx = -1;
            float bestJitter = Float.POSITIVE_INFINITY;
            boolean failedTau = false;
            boolean failedMean = false;
            for (int k = 0; k < anglesMaxDir.size(); k++) {
                int intervalStatus = curvatureIntervalStatus(validRadii, kappaMaxList, kappaMinList, k,
                        stabilityWindow, curvatureK);
                if (intervalStatus == CURVATURE_INTERVAL_FAIL_TAU) {
                    failedTau = true;
                    continue;
                }
                if (intervalStatus == CURVATURE_INTERVAL_FAIL_MEAN) {
                    failedMean = true;
                    continue;
                }
                if (intervalStatus != CURVATURE_INTERVAL_VALID) {
                    continue;
                }

                float ak = anglesMaxDir.get(k);
                float center = validRadii.get(k);
                float sumSq = 0f;
                int count = 0;
                for (int j = 0; j < anglesMaxDir.size(); j++) {
                    if (j == k)
                        continue;
                    if (Math.abs(validRadii.get(j) - center) > stabilityWindow)
                        continue;
                    float alpha = anglesMaxDir.get(j) - ak;
                    /** Wrap an angle to (−π/2, π/2]. */
                    float diff = (float) (alpha - Math.PI * Math.floor((alpha + Math.PI / 2.0) / Math.PI));
                    sumSq += diff * diff;
                    count++;
                }
                float jitter = 0f;
                if (count != 0) {
                    jitter = (float) Math.sqrt(sumSq / count);
                }
                if (jitter < bestJitter) {
                    bestJitter = jitter;
                    bestIdx = k;
                }
            }
            if (bestIdx < 0) {
                if (failedTau) {
                    stats.failedAnisotropy++;
                } else if (failedMean) {
                    stats.failedMeanCurvature++;
                } else {
                    stats.failedInterval++;
                }
                continue;
            }
            stats.validIntervals++;
            stats.jitterSum += bestJitter;
            stats.maxJitter = Math.max(stats.maxJitter, bestJitter);
            stats.acceptedVertices++;

            float constraintAngleAtV = anglesMaxDir.get(bestIdx);
            float c = (float) Math.cos(constraintAngleAtV);
            float s = (float) Math.sin(constraintAngleAtV);
            Vector3f constraintDirWorld = new Vector3f(
                    e1.x * c + e2.x * s,
                    e1.y * c + e2.y * s,
                    e1.z * c + e2.z * s);

            int adj = mesh.vertexFaceCount(vId);
            int newlyConstrained = 0;

            int bestFaceAi = -1;
            float bestProjectionLength = -1f;
            for (int i = 0; i < adj; i++) {
                int fId = mesh.vertexFaceAt(vId, i);
                int fAi = faceIdToActive.get(fId);
                if (faceConstrained[fAi]) {
                    stats.faceCollisionCandidates++;
                    continue;
                }
                Vector3f n = new Vector3f();
                mesh.faceNormal(mesh.faceIdAt(fAi), n);
                float dotN = constraintDirWorld.dot(n);
                float x = constraintDirWorld.x - dotN * n.x;
                float y = constraintDirWorld.y - dotN * n.y;
                float z = constraintDirWorld.z - dotN * n.z;
                float projectionLength = (float) Math.sqrt(x * x + y * y + z * z);
                if (projectionLength > bestProjectionLength) {
                    bestProjectionLength = projectionLength;
                    bestFaceAi = fAi;
                }
            }
            if (bestFaceAi >= 0) {
                float angleInFace = projectDirectionToFaceAngle(constraintDirWorld, bestFaceAi);
                faceConstrained[bestFaceAi] = true;
                faceConstraintAngle[bestFaceAi] = canonicalizeMod(angleInFace,
                        (float) (Math.PI / 2.0));
                newlyConstrained = 1;
            }
            if (newlyConstrained > 0) {
                addedConstraints += newlyConstrained;
            } else {
                stats.allIncidentFacesConstrained++;
            }
        }
        stats.addedConstraints = addedConstraints;
        return addedConstraints;
    }

    /**
     * Project a 3D direction into a face's local (x, y) frame and return atan2(y,
     * x).
     *
     * @param dirWorld  3D direction in world coordinates
     * @param faceIndex face active index
     * @return signed angle of the projected direction in face-local frame, in
     *         radians
     */
    public float projectDirectionToFaceAngle(Vector3f dirWorld, int faceIndex) {
        Vector3f n = new Vector3f();
        mesh.faceNormal(mesh.faceIdAt(faceIndex), n);
        float dotN = dirWorld.dot(n);
        Vector3f planar = new Vector3f(
                dirWorld.x - dotN * n.x,
                dirWorld.y - dotN * n.y,
                dirWorld.z - dotN * n.z);
        return (float) Math.atan2(planar.dot(faceY[faceIndex]), planar.dot(faceX[faceIndex]));
    }

    /**
     * Cohen-Steiner integrated curvature tensor over a geodesic disk around
     * {@code centerVertexId}. Walks all triangles whose three vertices fall within
     * the disk and accumulates per-interior-edge dihedral contributions
     * {@code β·|e|·(ē⊗ē)}, normalized by total triangle area. Returns
     * {@code [T00, T01, T11]} expressed in the local tangent basis (e1, e2), or
     * {@code null} when the disk is empty or has no usable area.
     *
     * @param centerVertexId center vertex id
     * @param centerPosition center vertex position
     * @param centerNormal   center vertex normal
     * @param tangentE1      tangent basis vector 1
     * @param tangentE2      tangent basis vector 2 (= centerNormal × tangentE1)
     * @param geodesicRadius geodesic-disk radius (Dijkstra over 1-skeleton)
     * @return three-element {@code [T00, T01, T11]} tensor entries, or {@code null}
     *         when the disk has no usable triangles
     */
    public float[] integrateCurvatureTensor(int centerVertexId, Vector3f centerPosition,
            Vector3f centerNormal, Vector3f tangentE1, Vector3f tangentE2, float geodesicRadius) {

        // Bump stamp; entries with this value are "in this call's set", everything else
        // is implicitly cleared from prior calls.
        final int stamp = ++curvatureStamp;

        // Dijkstra over the 1-skeleton, stamp-marked. vertexInDiskStamp[v] == stamp
        // means "visited this call"; vertexDistance[v] is the best known distance.
        PriorityQueue<DijkstraNode> pq = new PriorityQueue<>();
        pq.offer(new DijkstraNode(0f, centerVertexId));
        vertexInDiskStamp[centerVertexId] = stamp;
        vertexDistance[centerVertexId] = 0f;
        int visitedCount = 0;
        verticesVisited[visitedCount++] = centerVertexId;

        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        while (!pq.isEmpty()) {
            DijkstraNode node = pq.poll();
            int u = node.vertexOrFace;
            // Stale entry check: a better distance was found after this was queued.
            if (node.distance > vertexDistance[u] + EPSILON) {
                continue;
            }
            mesh.vertexPosition(u, a);
            int outCount = mesh.vertexOutgoingHalfEdgeCount(u);
            for (int i = 0; i < outCount; i++) {
                int he = mesh.vertexOutgoingHalfEdgeAt(u, i);
                int w = mesh.halfEdgeEndVertex(he);
                mesh.vertexPosition(w, b);
                float dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
                float wLen = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                float nd = node.distance + wLen;
                if (nd > geodesicRadius) {
                    continue;
                }
                if (vertexInDiskStamp[w] != stamp) {
                    vertexInDiskStamp[w] = stamp;
                    vertexDistance[w] = nd;
                    verticesVisited[visitedCount++] = w;
                    pq.offer(new DijkstraNode(nd, w));
                } else if (nd < vertexDistance[w]) {
                    vertexDistance[w] = nd;
                    pq.offer(new DijkstraNode(nd, w));
                }
            }
        }
        if (visitedCount == 0) {
            return null;
        }

        // Collect faces fully inside the disk.
        int facesFound = 0;
        float totalDiskArea = 0f;
        for (int vi = 0; vi < visitedCount; vi++) {
            int vertexId = verticesVisited[vi];
            int adjacentFaceCount = mesh.vertexFaceCount(vertexId);
            for (int i = 0; i < adjacentFaceCount; i++) {
                int faceId = mesh.vertexFaceAt(vertexId, i);
                if (faceInDiskStamp[faceId] == stamp) {
                    continue;
                }
                int faceVertex0 = mesh.faceVertexAt(faceId, 0);
                int faceVertex1 = mesh.faceVertexAt(faceId, 1);
                int faceVertex2 = mesh.faceVertexAt(faceId, 2);
                if (vertexInDiskStamp[faceVertex0] == stamp
                        && vertexInDiskStamp[faceVertex1] == stamp
                        && vertexInDiskStamp[faceVertex2] == stamp) {
                    faceInDiskStamp[faceId] = stamp;
                    totalDiskArea += faceArea(faceId);
                    facesFound++;
                }
            }
        }
        if (facesFound == 0 || totalDiskArea < EPSILON) {
            return null;
        }

        // Per-edge dihedral contributions.
        float tensor00 = 0f;
        float tensor01 = 0f;
        float tensor11 = 0f;

        Vector3f position0 = new Vector3f();
        Vector3f position1 = new Vector3f();
        Vector3f edgeVector = new Vector3f();
        Vector3f normal0 = new Vector3f();
        Vector3f normal1 = new Vector3f();
        Vector3f normalCross = new Vector3f();
        Vector3f edgeDirInTangentPlane = new Vector3f();

        for (int vi = 0; vi < visitedCount; vi++) {
            int vertexId = verticesVisited[vi];
            int incidentEdgeCount = mesh.vertexEdgeCount(vertexId);
            for (int i = 0; i < incidentEdgeCount; i++) {
                int edgeId = mesh.vertexEdgeAt(vertexId, i);
                if (edgeProcessedStamp[edgeId] == stamp) {
                    continue;
                }
                edgeProcessedStamp[edgeId] = stamp;

                int halfEdge = mesh.edgeHalfEdge(edgeId);
                int edgeStartVertex = mesh.halfEdgeVertex(halfEdge);
                int edgeEndVertex = mesh.halfEdgeEndVertex(halfEdge);
                if (vertexInDiskStamp[edgeStartVertex] != stamp
                        || vertexInDiskStamp[edgeEndVertex] != stamp) {
                    continue;
                }
                if (mesh.isBoundaryEdge(edgeId)) {
                    continue;
                }
                int twinHalfEdge = mesh.halfEdgeTwin(halfEdge);
                int leftFaceId = mesh.halfEdgeFace(halfEdge);
                int rightFaceId = mesh.halfEdgeFace(twinHalfEdge);
                if (faceInDiskStamp[leftFaceId] != stamp
                        || faceInDiskStamp[rightFaceId] != stamp) {
                    continue;
                }

                mesh.vertexPosition(edgeStartVertex, position0);
                mesh.vertexPosition(edgeEndVertex, position1);
                edgeVector.set(position1).sub(position0);
                float edgeLength = edgeVector.length();
                if (edgeLength < EPSILON) {
                    continue;
                }

                mesh.faceNormal(leftFaceId, normal0);
                mesh.faceNormal(rightFaceId, normal1);
                float cosDihedral = Math.max(-1f, Math.min(1f, normal0.dot(normal1)));
                normal0.cross(normal1, normalCross);
                float sinDihedral = normalCross.dot(edgeVector) / edgeLength;
                float dihedralAngle = (float) Math.atan2(sinDihedral, cosDihedral);

                edgeDirInTangentPlane.set(edgeVector).div(edgeLength);
                float normalComponent = edgeDirInTangentPlane.dot(centerNormal);
                edgeDirInTangentPlane.x -= normalComponent * centerNormal.x;
                edgeDirInTangentPlane.y -= normalComponent * centerNormal.y;
                edgeDirInTangentPlane.z -= normalComponent * centerNormal.z;
                float edgeComponentE1 = edgeDirInTangentPlane.dot(tangentE1);
                float edgeComponentE2 = edgeDirInTangentPlane.dot(tangentE2);

                float weight = dihedralAngle * edgeLength;
                tensor00 += weight * edgeComponentE1 * edgeComponentE1;
                tensor01 += weight * edgeComponentE1 * edgeComponentE2;
                tensor11 += weight * edgeComponentE2 * edgeComponentE2;
            }
        }

        tensor00 /= totalDiskArea;
        tensor01 /= totalDiskArea;
        tensor11 /= totalDiskArea;
        return new float[] { tensor00, tensor01, tensor11 };
    }

    /**
     * Status of the BZK09 §3 curvature stability interval centred at radius index
     * {@code k}.
     *
     * @param radii           geometric series of disk radii
     * @param kappaMaxList    per-radius {@code |κ_max|}
     * @param kappaMinList    per-radius {@code |κ_min|}
     * @param k               index in {@code radii} the interval is centred on
     * @param stabilityWindow half-window of radii considered in the stability test
     * @param curvatureK      BZK09 §3 mean-curvature threshold
     * @return one of {@link #CURVATURE_INTERVAL_VALID},
     *         {@link #CURVATURE_INTERVAL_FAIL_EMPTY},
     *         {@link #CURVATURE_INTERVAL_FAIL_TAU}, or
     *         {@link #CURVATURE_INTERVAL_FAIL_MEAN}
     */
    public int curvatureIntervalStatus(List<Float> radii,
            List<Float> kappaMaxList,
            List<Float> kappaMinList,
            int k,
            float stabilityWindow,
            float curvatureK) {
        float center = radii.get(k);
        boolean hasSample = false;
        for (int j = 0; j < radii.size(); j++) {
            if (Math.abs(radii.get(j) - center) > stabilityWindow)
                continue;
            hasSample = true;
            float kmax = kappaMaxList.get(j);
            float kmin = kappaMinList.get(j);
            if (Math.abs(kmax) < EPSILON)
                return CURVATURE_INTERVAL_FAIL_TAU;
            float curvatureConstrast = (Math.abs(kmax) - Math.abs(kmin)) / Math.abs(kmax);
            float meanH = 0.5f * (kmax + kmin);
            if (curvatureConstrast <= minimumCurvatureContrast || Math.abs(meanH) <= curvatureK)
                return curvatureConstrast <= minimumCurvatureContrast
                        ? CURVATURE_INTERVAL_FAIL_TAU
                        : CURVATURE_INTERVAL_FAIL_MEAN;
        }
        return hasSample ? CURVATURE_INTERVAL_VALID : CURVATURE_INTERVAL_FAIL_EMPTY;
    }

    /**
     * BZK09 §5.2 feature-edge alignment constraints: edges whose dihedral angle
     * between the two incident face normals exceeds {@link #featureDihedralCos}
     * (default cos 30° = 0.866) are treated as sharp creases. The cross is aligned
     * with the edge direction in both incident faces. Binary include/exclude
     * decision by dihedral threshold — no scale-invariant adaptation, no confidence
     * weighting; CIE16 is a richer alternative we do not implement.
     *
     * <p>
     * Without this pass, sharp models like fandisk produce many spurious
     * singularities because the field has no incentive to follow features.
     *
     * @param faceConstrained     per-face flag, updated for newly constrained faces
     * @param faceConstraintAngle per-face constraint angle (face-local) overwritten
     *                            for affected faces
     * @return number of newly constrained faces
     */
    public int applyFeatureEdgeConstraints(boolean[] faceConstrained, float[] faceConstraintAngle) {
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        Vector3f n0 = new Vector3f();
        Vector3f n1 = new Vector3f();
        int addedConstraints = 0;
        for (int eAi = 0; eAi < mesh.edgeCount(); eAi++) {
            int eId = mesh.edgeIdAt(eAi);
            if (mesh.isBoundaryEdge(eId)) {
                continue;
            }
            int he = mesh.edgeHalfEdge(eId);
            int twin = mesh.halfEdgeTwin(he);
            int fi = mesh.halfEdgeFace(he);
            int fj = mesh.halfEdgeFace(twin);
            mesh.faceNormal(fi, n0);
            mesh.faceNormal(fj, n1);
            float dot = n0.dot(n1);
            if (dot >= featureDihedralCos) {
                continue;
            }

            int fiAi = faceIdToActive.get(fi);
            int fjAi = faceIdToActive.get(fj);
            alignmentEdgeIds.add(eId);
            if (faceConstrained[fiAi] && faceConstrained[fjAi]) {
                continue;
            }
            int v0 = mesh.halfEdgeVertex(he);
            int v1 = mesh.halfEdgeEndVertex(he);
            mesh.vertexPosition(v0, a);
            mesh.vertexPosition(v1, b);
            Vector3f edgeDir = new Vector3f(b).sub(a);
            for (int sideAi : new int[] { fiAi, fjAi }) {
                if (faceConstrained[sideAi]) {
                    continue;
                }
                float angle = projectDirectionToFaceAngle(edgeDir, sideAi);
                faceConstrained[sideAi] = true;
                faceConstraintAngle[sideAi] = canonicalizeMod(angle, (float) (Math.PI / 2.0));
                addedConstraints++;
            }
        }
        return addedConstraints;
    }

    /**
     * Add directional constraints on both faces incident to each boundary edge. The
     * cross is aligned with the edge direction so the quadrangulation follows the
     * surface boundary.
     *
     * @param faceConstrained     per-face flag, updated for newly constrained
     *                            boundary-incident faces
     * @param faceConstraintAngle per-face constraint angle (face-local) overwritten
     *                            for boundary-incident faces
     * @return number of newly constrained faces
     */
    public int applyBoundaryConstraints(boolean[] faceConstrained, float[] faceConstraintAngle) {
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        int addedConstraints = 0;
        for (int eAi = 0; eAi < mesh.edgeCount(); eAi++) {
            int eId = mesh.edgeIdAt(eAi);
            if (!mesh.isBoundaryEdge(eId))
                continue;
            int he = mesh.edgeHalfEdge(eId);
            int v0 = mesh.halfEdgeVertex(he);
            int v1 = mesh.halfEdgeEndVertex(he);
            mesh.vertexPosition(v0, a);
            mesh.vertexPosition(v1, b);
            Vector3f edgeDir = new Vector3f(b).sub(a);
            alignmentEdgeIds.add(eId);
            addedConstraints += constrainBothFacesOfEdge(eId, edgeDir, faceConstrained, faceConstraintAngle);
        }
        return addedConstraints;
    }

    /**
     * Project {@code edgeDirWorld} into both incident faces of {@code eId} and
     * record the resulting face-local angle as a constraint, marking faces as
     * constrained if they were not already.
     *
     * @param eId                 edge id whose two incident faces are constrained
     * @param edgeDirWorld        edge direction in world coordinates
     * @param faceConstrained     per-face flag, updated for newly constrained faces
     * @param faceConstraintAngle per-face constraint angle (face-local) overwritten
     *                            for both incident faces
     * @return number of newly constrained faces (0, 1, or 2)
     */
    public int constrainBothFacesOfEdge(int eId, Vector3f edgeDirWorld,
            boolean[] faceConstrained,
            float[] faceConstraintAngle) {
        int he = mesh.edgeHalfEdge(eId);
        int twin = mesh.halfEdgeTwin(he);
        int addedConstraints = 0;
        for (int hePick : new int[] { he, twin }) {
            int fId = mesh.halfEdgeFace(hePick);
            if (fId == MeshTopology.NONE)
                continue;
            int fAi = faceIdToActive.get(fId);
            float angle = projectDirectionToFaceAngle(edgeDirWorld, fAi);
            if (!faceConstrained[fAi])
                addedConstraints++;
            faceConstrained[fAi] = true;
            faceConstraintAngle[fAi] = canonicalizeMod(angle, (float) (Math.PI / 2.0));
        }
        return addedConstraints;
    }

    /*
     * A3. Voronoi spanning forest in the dual graph
     */

    /**
     * Multi-source Dijkstra over the dual graph rooted at every constrained face;
     * the shortest-parent edge of each non-constrained face becomes a forest edge
     * whose period jump is fixed to zero in BZK09 §A3.
     *
     * @param faceConstrained per-face flag indicating dual-graph sources
     * @return active edge ids of the spanning-forest edges
     */
    public Set<Integer> buildVoronoiSpanningForest(boolean[] faceConstrained) {
        int faceCount = mesh.faceCount();
        float[] dist = new float[faceCount];
        int[] parentEdgeAi = new int[faceCount];
        Arrays.fill(dist, Float.POSITIVE_INFINITY);
        Arrays.fill(parentEdgeAi, -1);

        PriorityQueue<DijkstraNode> pq = new PriorityQueue<>();
        for (int fAi = 0; fAi < faceCount; fAi++) {
            if (faceConstrained[fAi]) {
                dist[fAi] = 0f;
                pq.offer(new DijkstraNode(0f, fAi));
            }
        }
        Vector3f va = new Vector3f();
        Vector3f vb = new Vector3f();

        while (!pq.isEmpty()) {
            DijkstraNode node = pq.poll();
            int fAi = node.vertexOrFace;
            if (node.distance > dist[fAi] + EPSILON)
                continue;
            int fId = mesh.faceIdAt(fAi);
            int adj = mesh.faceHalfEdgeCount(fId);
            for (int i = 0; i < adj; i++) {
                int he = mesh.faceHalfEdgeAt(fId, i);
                int twin = mesh.halfEdgeTwin(he);
                int gId = mesh.halfEdgeFace(twin);
                if (gId == MeshTopology.NONE)
                    continue;
                int gAi = faceIdToActive.get(gId);
                int eId = mesh.halfEdgeEdge(he);
                int eAi = edgeIdToActive.get(eId);

                int v0 = mesh.halfEdgeVertex(he);
                int v1 = mesh.halfEdgeEndVertex(he);
                mesh.vertexPosition(v0, va);
                mesh.vertexPosition(v1, vb);
                float w = (float) Math.sqrt(
                        (vb.x - va.x) * (vb.x - va.x) +
                                (vb.y - va.y) * (vb.y - va.y) +
                                (vb.z - va.z) * (vb.z - va.z));
                float nd = node.distance + w;
                if (nd < dist[gAi]) {
                    dist[gAi] = nd;
                    parentEdgeAi[gAi] = eAi;
                    pq.offer(new DijkstraNode(nd, gAi));
                }
            }
        }

        Set<Integer> forest = new HashSet<>();
        for (int fAi = 0; fAi < faceCount; fAi++) {
            if (parentEdgeAi[fAi] >= 0 && !faceConstrained[fAi]) {
                forest.add(parentEdgeAi[fAi]);
            }
        }
        return forest;
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
        Vector3f vPos = new Vector3f();
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();

        for (int vAi = 0; vAi < vertexCount; vAi++) {
            int vId = mesh.vertexIdAt(vAi);
            if (mesh.isBoundaryVertex(vId))
                continue;
            mesh.vertexPosition(vId, vPos);

            float angleSum = 0f;
            int faces = mesh.vertexFaceCount(vId);
            for (int i = 0; i < faces; i++) {
                int fId = mesh.vertexFaceAt(vId, i);
                angleSum += interiorAngleAtVertex(fId, vId, vPos, a, b);
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
     * Interior angle of face {@code fId} at vertex {@code vId}.
     *
     * @param fId  face id
     * @param vId  vertex id (must be a corner of {@code fId})
     * @param vPos position of {@code vId}
     * @param a    scratch vector overwritten with the previous-corner edge
     *             direction
     * @param b    scratch vector overwritten with the next-corner edge direction
     * @return angle in radians, or 0 for degenerate triangles or when {@code vId}
     *         is not a corner of {@code fId}
     */
    public float interiorAngleAtVertex(int fId, int vId, Vector3f vPos, Vector3f a, Vector3f b) {
        int adj = mesh.faceHalfEdgeCount(fId);
        for (int i = 0; i < adj; i++) {
            int he = mesh.faceHalfEdgeAt(fId, i);
            if (mesh.halfEdgeVertex(he) == vId) {
                int prev = mesh.halfEdgePrev(he);
                int prevStart = mesh.halfEdgeVertex(prev);
                int nextEnd = mesh.halfEdgeEndVertex(he);
                mesh.vertexPosition(prevStart, a);
                mesh.vertexPosition(nextEnd, b);
                a.sub(vPos);
                b.sub(vPos);
                float la = a.length();
                float lb = b.length();
                if (la < EPSILON || lb < EPSILON)
                    return 0f;
                float c = a.dot(b) / (la * lb);
                c = Math.max(-1f, Math.min(1f, c));
                return (float) Math.acos(c);
            }
        }
        return 0f;
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
     * Count the {@code true} entries in a boolean array.
     *
     * @param values flag array
     * @return number of {@code true} entries in {@code values}
     */
    public static int countTrue(boolean[] values) {
        int count = 0;
        for (boolean value : values) {
            if (value)
                count++;
        }
        return count;
    }

    /**
     * Euclidean area of a triangular face.
     *
     * @param fId face id (assumed triangular)
     * @return Euclidean area of the triangle whose first half-edge starts the face
     */
    public float faceArea(int fId) {
        int he = mesh.faceHalfEdge(fId);
        int v0 = mesh.halfEdgeVertex(he);
        int v1 = mesh.halfEdgeEndVertex(he);
        int v2 = mesh.halfEdgeEndVertex(mesh.halfEdgeNext(he));
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        Vector3f c = new Vector3f();
        mesh.vertexPosition(v0, a);
        mesh.vertexPosition(v1, b);
        mesh.vertexPosition(v2, c);
        b.sub(a);
        c.sub(a);
        Vector3f cross = new Vector3f();
        b.cross(c, cross);
        return 0.5f * cross.length();
    }

    /**
     * Reduce {@code angle} into the half-open interval {@code [0, mod)}.
     *
     * @param angle angle in radians
     * @param mod   positive modulus
     * @return canonical representative of {@code angle} modulo {@code mod}
     */
    public static float canonicalizeMod(float angle, float mod) {
        float r = (float) (angle - mod * Math.floor(angle / mod));
        if (r < 0)
            r += mod;
        return r;
    }

    private record TrialResult(int eAi, int bestDelta, double bestEnergy) {
    }

    public static final class DijkstraNode implements Comparable<DijkstraNode> {
        final float distance;
        final int vertexOrFace;

        DijkstraNode(float distance, int vertexOrFace) {
            this.distance = distance;
            this.vertexOrFace = vertexOrFace;
        }

        /**
         * {@inheritDoc} Orders by ascending {@code distance} for the priority queue.
         */
        @Override
        public int compareTo(DijkstraNode other) {
            return Float.compare(this.distance, other.distance);
        }
    }

    public static final class CurvatureConstraintStats {
        int verticesVisited;
        int boundaryVertices;
        int noCurvatureSamples;
        int failedAnisotropy;
        int failedMeanCurvature;
        int failedInterval;
        int validIntervals;
        int acceptedVertices;
        int faceCollisionCandidates;
        int allIncidentFacesConstrained;
        int addedConstraints;
        double jitterSum;
        float maxJitter;

        double averageJitter() {
            return validIntervals > 0 ? jitterSum / validIntervals : 0.0;
        }
    }

}
