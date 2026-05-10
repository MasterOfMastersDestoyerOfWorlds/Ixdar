package ixdar.geometry.mesh.quadlayout;

import java.util.ArrayDeque;
import java.util.Arrays;

import java.util.ArrayList;

import ixdar.geometry.mesh.quadlayout.solver.SparseMatrix;

import java.util.List;

import org.ejml.data.DMatrixRMaj;

import org.ejml.data.DMatrixSparseCSC;

import org.ejml.data.DMatrixSparseTriplet;
import java.util.concurrent.ExecutorService;

import org.ejml.ops.DConvertMatrixStruct;

import java.util.concurrent.ExecutionException;

import org.ejml.sparse.FillReducing;

import org.ejml.sparse.csc.factory.LinearSolverFactory_DSCC;

import java.util.concurrent.Callable;

import java.util.concurrent.Executors;

import java.util.concurrent.Future;

import java.util.function.IntConsumer;

/**
 * Adaptive solver ladder for the mixed-integer systems described by
 * Bommes-Zimmer-Kobbelt 2009, Section 2.1.
 *
 * <p>
 * BZK09 solves a quadratic mixed-integer problem by repeatedly rounding one
 * integer variable, holding it fixed, and updating the remaining continuous
 * minimizer. Their adaptive strategy is:
 *
 * <ol>
 * <li>Start with a local Gauss-Seidel update seeded by the nonzero entries of
 * the rounded variable's matrix row.</li>
 * <li>If the local update does not converge, fall back to a global conjugate
 * gradient solve, warm-started from the previous solution.</li>
 * <li>If CG also fails, use a direct sparse solve.</li>
 * </ol>
 *
 * <p>
 * This class intentionally does not know about cross fields, period jumps, or
 * parametrization variables. It operates on a symmetric positive semi-definite
 * matrix plus a right-hand side, with caller-specified fixed variables. That
 * keeps it usable for both BZK09 cross-field smoothing and the later seamless
 * parametrization step.
 */
public final class AdaptiveSolver {
    public static final double NUM_1e_30 = 1e-30;
    public static final double NUM_1_7 = 1.7;
    /** SOR factor for the colored-GS variant; 1.0 keeps convergence stable across colors. */
    public static final double NUM_1_0 = 1.0;
    /** Below this work-set size we run the colored variant serially (per-color thread overhead dominates). */
    public static final int COLORED_GS_MIN_PARALLEL_SIZE = 64;
    public static final int COLORED_GS_MAX_WORKERS = 8;

    private static volatile ExecutorService coloredGsPool;

    private AdaptiveSolver() {
    }

    private static ExecutorService coloredGsPool() {
        ExecutorService p = coloredGsPool;
        if (p != null) return p;
        synchronized (AdaptiveSolver.class) {
            if (coloredGsPool == null) {
                int n = Math.min(Runtime.getRuntime().availableProcessors(), COLORED_GS_MAX_WORKERS);
                coloredGsPool = Executors.newFixedThreadPool(n, r -> {
                    Thread t = new Thread(r, "adaptive-colored-gs");
                    t.setDaemon(true);
                    return t;
                });
            }
            return coloredGsPool;
        }
    }

    /**
     * Solve a fixed-variable symmetric linear system using the BZK09 adaptive
     * ladder, seeded as a global update.
     *
     * <p>
     * This is appropriate for the initial all-continuous minimizer, before a
     * particular integer variable has just been rounded.
     *
     * @param matrix    symmetric system matrix A
     * @param rhs       right-hand side b
     * @param warmStart previous/full solution estimate
     * @param fixed     fixed[i] means x[i] is held at warmStart[i]
     * @param options   solver tolerances and iteration caps
     * @return solution and stats
     */
    public static Result solve(Matrix matrix,
            double[] rhs,
            double[] warmStart,
            boolean[] fixed,
            Options options) {
        return solveAfterRounding(matrix, rhs, warmStart, fixed, -1, options);
    }

    /**
     * Solve after one integer variable has been rounded and fixed.
     *
     * <p>
     * This is the paper-faithful BZK09 update: local Gauss-Seidel begins by pushing
     * every variable whose row depends on {@code roundedVariable}. If
     * {@code roundedVariable < 0}, all free variables are seeded instead.
     *
     * @param matrix          symmetric system matrix A
     * @param rhs             right-hand side b
     * @param warmStart       previous/full solution estimate; fixed values are read
     *                        from this array
     * @param fixed           fixed[i] means x[i] is held at warmStart[i]
     * @param roundedVariable index of the variable just rounded, or negative for
     *                        global seeding
     * @param options         solver tolerances and iteration caps
     * @return solution and stats
     */
    public static Result solveAfterRounding(Matrix matrix,
            double[] rhs,
            double[] warmStart,
            boolean[] fixed,
            int roundedVariable,
            Options options) {
        int[] roundedVariables = roundedVariable >= 0 ? new int[] { roundedVariable } : null;
        return solveAfterRounding(matrix, rhs, warmStart, fixed, roundedVariables,
                roundedVariable >= 0 ? 1 : 0, options);
    }

    /**
     * Solve after one or more independent integer variables have been rounded and
     * fixed. The local Gauss-Seidel seed is the union of each rounded variable's
     * immediate dependency patch.
     *
     * @param matrix           symmetric system matrix A
     * @param rhs              right-hand side b
     * @param warmStart        previous/full solution estimate; fixed values are
     *                         read from this array
     * @param fixed            fixed[i] means x[i] is held at warmStart[i]
     * @param roundedVariables variables rounded since the last update
     * @param roundedCount     number of valid entries in {@code roundedVariables}
     * @param options          solver tolerances and iteration caps
     * @return solution and stats
     */
    public static Result solveAfterRounding(Matrix matrix,
            double[] rhs,
            double[] warmStart,
            boolean[] fixed,
            int[] roundedVariables,
            int roundedCount,
            Options options) {
        validateInputs(matrix, rhs, warmStart, fixed);
        Options opts = options == null ? new Options() : options;
        double[] x = warmStart.clone();

        if (roundedCount <= 0) {
            BootstrapResult bootstrap = bootstrapSolve(matrix, rhs, x, fixed);
            double resNorm = residualNorm(matrix, rhs, bootstrap.x, fixed);
            System.err.printf("[bootstrap] residualNorm=%.3e%n", resNorm);
            return new Result(bootstrap.x, new Stats(
                    bootstrap.method, bootstrap.converged, 0, 0,
                    bootstrap.residualNorm, false, 0, 0, 0.0, -1));

        }

        LocalResult local = opts.useColoredGaussSeidel
                ? coloredLocalGaussSeidel(matrix, rhs, x, fixed,
                        roundedVariables, roundedCount, opts.coloring, opts)
                : localGaussSeidel(matrix, rhs, x, fixed,
                        roundedVariables, roundedCount, opts);
        if (local.converged) {
            double residual = residualNorm(matrix, rhs, local.x, fixed);
            return new Result(local.x, new Stats(
                    Method.LOCAL_GAUSS_SEIDEL, true, local.iterations, 0, residual,
                    local.hitCap, local.initialQueueSize, local.maxQueueSize,
                    local.capResidualNorm, local.capResidualRow));
        }

        CgResult cg = conjugateGradient(matrix, rhs, local.x, fixed, opts);
        if (cg.converged) {
            double residual = residualNorm(matrix, rhs, cg.x, fixed);
            return new Result(cg.x, new Stats(
                    Method.CONJUGATE_GRADIENT, true, local.iterations, cg.iterations, residual,
                    local.hitCap, local.initialQueueSize, local.maxQueueSize,
                    local.capResidualNorm, local.capResidualRow));
        }

        if (opts.useDirectFallback) {
            double[] direct = directSolve(matrix, rhs, cg.x, fixed);
            double residual = residualNorm(matrix, rhs, direct, fixed);
            return new Result(direct, new Stats(
                    Method.DIRECT, true, local.iterations, cg.iterations, residual,
                    local.hitCap, local.initialQueueSize, local.maxQueueSize,
                    local.capResidualNorm, local.capResidualRow));
        }

        double residual = residualNorm(matrix, rhs, cg.x, fixed);
        return new Result(cg.x, new Stats(
                Method.FAILED, false, local.iterations, cg.iterations, residual,
                local.hitCap, local.initialQueueSize, local.maxQueueSize,
                local.capResidualNorm, local.capResidualRow));
    }

    /**
     * Greedy first-fit coloring of the matrix's variable dependency graph
     * (off-diagonal nonzero pattern). Within a color, no two variables are
     * connected by a nonzero off-diagonal entry, so they can be Gauss-Seidel
     * updated in parallel without changing each other's residual computation.
     *
     * @param matrix symmetric system matrix
     * @return per-row color index in {@code [0, numColors)}; {@link #colorCount(int[])} returns the count
     */
    public static int[] computeGreedyColoring(Matrix matrix) {
        int n = matrix.size();
        int[] colors = new int[n];
        Arrays.fill(colors, -1);
        boolean[] used = new boolean[n + 1];
        for (int row = 0; row < n; row++) {
            int touched = 0;
            for (int c = matrix.rowStart(row); c < matrix.rowEnd(row); c++) {
                int neighbor = matrix.column(c);
                if (neighbor != row && colors[neighbor] >= 0) {
                    int col = colors[neighbor];
                    if (col < used.length) {
                        used[col] = true;
                        touched++;
                    }
                }
            }
            int chosen = 0;
            while (chosen < used.length && used[chosen]) {
                chosen++;
            }
            colors[row] = chosen;
            // Reset the marks we set this iteration without scanning all of `used`.
            for (int c = matrix.rowStart(row); c < matrix.rowEnd(row); c++) {
                int neighbor = matrix.column(c);
                if (neighbor != row && colors[neighbor] >= 0) {
                    int col = colors[neighbor];
                    if (col < used.length) used[col] = false;
                }
            }
            if (touched == 0) {
                // nothing to reset
            }
        }
        return colors;
    }

    /**
     * Number of distinct colors in a coloring (i.e. {@code max(colors)+1}).
     *
     * @param colors per-row color index produced by {@link #computeGreedyColoring}
     * @return number of distinct colors used
     */
    public static int colorCount(int[] colors) {
        int max = -1;
        for (int c : colors) if (c > max) max = c;
        return max + 1;
    }

    private static LocalResult localGaussSeidel(Matrix matrix,
            double[] rhs,
            double[] start,
            boolean[] fixed,
            int[] roundedVariables,
            int roundedCount,
            Options options) {
        int n = matrix.size();
        double[] x = start.clone();
        IntArrayQueue queue = new IntArrayQueue(n);
        boolean[] inQueue = new boolean[n];

        for (int i = 0; i < roundedCount; i++) {
            enqueueDependents(matrix, roundedVariables[i], fixed, queue, inQueue);
        }

        int initialQueueSize = queue.size();
        int maxQueueSize = initialQueueSize;
        int iterations = 0;
        while (!queue.isEmpty() && iterations < options.localMaxIterations) {
            iterations++;
            int row = queue.poll();
            inQueue[row] = false;
            if (fixed[row]) {
                continue;
            }

            double residual = rhs[row] - rowDot(matrix, row, x);
            if (Math.abs(residual) <= options.localTolerance) {
                continue;
            }

            double diag = matrix.diag(row);
            if (Math.abs(diag) < NUM_1e_30) {
                return new LocalResult(x, iterations, false, false, initialQueueSize,
                        maxQueueSize, 0.0, -1);
            }

            double delta = residual / diag;
            x[row] += NUM_1_7 * delta;
            if (Math.abs(delta) > options.localTolerance) {
                enqueueDependents(matrix, row, fixed, queue, inQueue);
            }
            maxQueueSize = Math.max(maxQueueSize, queue.size());
        }

        boolean hitCap = !queue.isEmpty();
        CapResidual capResidual = hitCap
                ? maxQueuedResidual(matrix, rhs, x, fixed, queue)
                : new CapResidual(0.0, -1);
        return new LocalResult(x, iterations, queue.isEmpty(), hitCap, initialQueueSize,
                maxQueueSize, capResidual.norm, capResidual.row);
    }

    /**
     * Colored variant of {@link #localGaussSeidel}. Variables are partitioned
     * into colors such that no two same-color variables share an off-diagonal
     * nonzero in {@code matrix}; within a color, updates are applied in
     * parallel because they don't enter each other's {@code rowDot}. Uses
     * SOR factor 1.0 (no over-relaxation) since colored-Jacobi-style
     * convergence is more sensitive than the serial variant.
     *
     * <p>Caller may pass a precomputed {@code colors} array; otherwise the
     * coloring is computed from {@code matrix}.
     */
    private static LocalResult coloredLocalGaussSeidel(Matrix matrix,
            double[] rhs,
            double[] start,
            boolean[] fixed,
            int[] roundedVariables,
            int roundedCount,
            int[] colors,
            Options options) {
        int n = matrix.size();
        double[] x = start.clone();
        int[] effColors = colors != null ? colors : computeGreedyColoring(matrix);
        int numColors = colorCount(effColors);
        IntArrayQueue[] queues = new IntArrayQueue[numColors];
        for (int c = 0; c < numColors; c++) {
            queues[c] = new IntArrayQueue(n);
        }
        boolean[] inQueue = new boolean[n];

        for (int i = 0; i < roundedCount; i++) {
            int rv = roundedVariables[i];
            if (rv < 0 || rv >= n) continue;
            for (int c = matrix.rowStart(rv); c < matrix.rowEnd(rv); c++) {
                int col = matrix.column(c);
                if (fixed[col] || inQueue[col]) continue;
                queues[effColors[col]].offer(col);
                inQueue[col] = true;
            }
        }

        int initialQueueSize = 0;
        for (var q : queues) initialQueueSize += q.size();
        int maxQueueSize = initialQueueSize;
        int iterations = 0;
        ExecutorService pool = coloredGsPool();
        int nWorkers = Math.min(Runtime.getRuntime().availableProcessors(), COLORED_GS_MAX_WORKERS);

        boolean anyWork = true;
        while (anyWork && iterations < options.localMaxIterations) {
            anyWork = false;
            for (int c = 0; c < numColors; c++) {
                IntArrayQueue q = queues[c];
                if (q.isEmpty()) continue;
                anyWork = true;
                int batchSize = q.size();
                int[] toProcess = new int[batchSize];
                for (int i = 0; i < batchSize; i++) {
                    int v = q.poll();
                    inQueue[v] = false;
                    toProcess[i] = v;
                }
                iterations += batchSize;
                int[][] collected;
                if (batchSize < COLORED_GS_MIN_PARALLEL_SIZE) {
                    int[] deps = processColorChunkSerial(matrix, rhs, x, fixed,
                            options, toProcess, 0, batchSize);
                    collected = new int[][] { deps };
                } else {
                    int chunkSize = Math.max(1, (batchSize + nWorkers - 1) / nWorkers);
                    List<Callable<int[]>> tasks =
                            new ArrayList<>(nWorkers);
                    for (int s = 0; s < batchSize; s += chunkSize) {
                        final int chunkStart = s;
                        final int chunkEnd = Math.min(s + chunkSize, batchSize);
                        tasks.add(() -> processColorChunkSerial(matrix, rhs, x, fixed,
                                options, toProcess, chunkStart, chunkEnd));
                    }
                    collected = new int[tasks.size()][];
                    try {
                        List<Future<int[]>> futures =
                                pool.invokeAll(tasks);
                        for (int i = 0; i < futures.size(); i++) {
                            collected[i] = futures.get(i).get();
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return new LocalResult(x, iterations, false, false,
                                initialQueueSize, maxQueueSize, 0.0, -1);
                    } catch (ExecutionException ee) {
                        return new LocalResult(x, iterations, false, false,
                                initialQueueSize, maxQueueSize, 0.0, -1);
                    }
                }
                for (int[] depList : collected) {
                    if (depList == null) continue;
                    for (int dep : depList) {
                        if (!inQueue[dep] && !fixed[dep]) {
                            queues[effColors[dep]].offer(dep);
                            inQueue[dep] = true;
                        }
                    }
                }
                int totalQ = 0;
                for (var qq : queues) totalQ += qq.size();
                if (totalQ > maxQueueSize) maxQueueSize = totalQ;
            }
        }

        boolean hitCap = false;
        for (var q : queues) if (!q.isEmpty()) { hitCap = true; break; }
        CapResidual cap = hitCap ? maxQueuedResidualMulti(matrix, rhs, x, fixed, queues)
                                 : new CapResidual(0.0, -1);
        return new LocalResult(x, iterations, !hitCap, hitCap, initialQueueSize,
                maxQueueSize, cap.norm, cap.row);
    }

    /**
     * Process {@code toProcess[start..end)} as a single-threaded GS pass: for
     * each row, compute its residual, do an SOR update, and collect dependents
     * to re-enqueue. Returns the dependent indices encountered (deduplication
     * happens at the caller's barrier).
     */
    private static int[] processColorChunkSerial(Matrix matrix, double[] rhs, double[] x,
            boolean[] fixed, Options options, int[] toProcess, int start, int end) {
        ArrayList<Integer> deps = null;
        for (int i = start; i < end; i++) {
            int row = toProcess[i];
            if (fixed[row]) continue;
            double residual = rhs[row] - rowDot(matrix, row, x);
            if (Math.abs(residual) <= options.localTolerance) continue;
            double diag = matrix.diag(row);
            if (Math.abs(diag) < NUM_1e_30) continue;
            double delta = residual / diag;
            // Within a color all writes are to disjoint rows, so SOR with the
            // same omega as the serial path (1.7) is valid.
            x[row] += NUM_1_7 * delta;
            if (Math.abs(delta) > options.localTolerance) {
                if (deps == null) deps = new ArrayList<>();
                for (int c = matrix.rowStart(row); c < matrix.rowEnd(row); c++) {
                    int col = matrix.column(c);
                    if (!fixed[col]) deps.add(col);
                }
            }
        }
        if (deps == null) return new int[0];
        int[] arr = new int[deps.size()];
        for (int i = 0; i < deps.size(); i++) arr[i] = deps.get(i);
        return arr;
    }

    private static CapResidual maxQueuedResidualMulti(Matrix matrix, double[] rhs,
            double[] x, boolean[] fixed, IntArrayQueue[] queues) {
        double maxNorm = 0.0;
        int maxRow = -1;
        for (IntArrayQueue q : queues) {
            CapResidual cr = maxQueuedResidual(matrix, rhs, x, fixed, q);
            if (cr.norm > maxNorm) { maxNorm = cr.norm; maxRow = cr.row; }
        }
        return new CapResidual(maxNorm, maxRow);
    }

    /**
     * Fill {@code patch} with the two-hop dependency patch used by callers to test
     * whether batched rounded variables are locally independent.
     *
     * <p>
     * The caller owns {@code marked}; this method sets entries for variables in the
     * patch and does not clear them. It returns the number of appended entries in
     * {@code patch}. Pass a fresh or already-cleared marker array when the returned
     * patch should be independent from previous calls.
     *
     * @param matrix          symmetric system matrix A
     * @param roundedVariable variable considered for rounding
     * @param fixed           fixed[i] variables are not added except for the center
     * @param patch           output variable indices
     * @param marked          scratch/accumulated marker array
     * @return number of entries written to {@code patch}
     */
    public static int collectAffectedPatch(Matrix matrix,
            int roundedVariable,
            boolean[] fixed,
            int[] patch,
            boolean[] marked) {
        if (roundedVariable < 0 || roundedVariable >= matrix.size()) {
            return 0;
        }
        int count = addPatchVariable(roundedVariable, patch, marked, 0);
        int firstRingStart = count;
        for (int c = matrix.rowStart(roundedVariable); c < matrix.rowEnd(roundedVariable); c++) {
            int col = matrix.column(c);
            if (!fixed[col]) {
                count = addPatchVariable(col, patch, marked, count);
            }
        }
        int firstRingEnd = count;
        for (int i = firstRingStart; i < firstRingEnd; i++) {
            int row = patch[i];
            for (int c = matrix.rowStart(row); c < matrix.rowEnd(row); c++) {
                int col = matrix.column(c);
                if (!fixed[col]) {
                    count = addPatchVariable(col, patch, marked, count);
                }
            }
        }
        return count;
    }

    private static CgResult conjugateGradient(Matrix matrix,
            double[] rhs,
            double[] start,
            boolean[] fixed,
            Options options) {
        int n = matrix.size();
        double[] x = start.clone();
        double[] r = new double[n];
        double rhsNormSq = 0.0;
        for (int i = 0; i < n; i++) {
            if (fixed[i]) {
                continue;
            }
            r[i] = rhs[i] - rowDot(matrix, i, x);
            rhsNormSq += rhs[i] * rhs[i];
        }

        double[] z = new double[n];
        double[] p = new double[n];
        double rzOld = 0.0;
        for (int i = 0; i < n; i++) {
            if (fixed[i]) {
                continue;
            }
            double diag = matrix.diag(i);
            z[i] = Math.abs(diag) > NUM_1e_30 ? r[i] / diag : r[i];
            p[i] = z[i];
            rzOld += r[i] * z[i];
        }

        double toleranceSq = options.cgTolerance * options.cgTolerance * Math.max(rhsNormSq, 1.0);
        if (residualNormSquared(r, fixed) <= toleranceSq) {
            return new CgResult(x, 0, true);
        }

        double[] ap = new double[n];
        int iteration = 0;
        for (; iteration < options.cgMaxIterations; iteration++) {
            Arrays.fill(ap, 0.0);
            for (int i = 0; i < n; i++) {
                if (!fixed[i]) {
                    ap[i] = rowDot(matrix, i, p);
                }
            }

            double pAp = 0.0;
            for (int i = 0; i < n; i++) {
                if (!fixed[i]) {
                    pAp += p[i] * ap[i];
                }
            }
            if (Math.abs(pAp) < NUM_1e_30) {
                break;
            }

            double alpha = rzOld / pAp;
            for (int i = 0; i < n; i++) {
                if (fixed[i]) {
                    continue;
                }
                x[i] += alpha * p[i];
                r[i] -= alpha * ap[i];
            }
            if (residualNormSquared(r, fixed) <= toleranceSq) {
                return new CgResult(x, iteration + 1, true);
            }

            double rzNew = 0.0;
            for (int i = 0; i < n; i++) {
                if (fixed[i]) {
                    continue;
                }
                double diag = matrix.diag(i);
                z[i] = Math.abs(diag) > NUM_1e_30 ? r[i] / diag : r[i];
                rzNew += r[i] * z[i];
            }
            double beta = rzNew / rzOld;
            for (int i = 0; i < n; i++) {
                if (!fixed[i]) {
                    p[i] = z[i] + beta * p[i];
                }
            }
            rzOld = rzNew;
        }
        return new CgResult(x, iteration, false);
    }

    /**
     * Solve {@code A x = b} for the free variables (those with {@code !fixed[i]})
     * using a sparse Cholesky factorization with reverse-Cuthill-McKee ordering,
     * holding the fixed entries at {@code start[i]}. Throws when the matrix is
     * not positive definite (e.g. for closed surfaces with no anchored variable).
     *
     * @param matrix symmetric system matrix A
     * @param rhs    right-hand side b
     * @param start  initial values; only the fixed entries are read
     * @param fixed  per-variable fixed flag
     * @throws IllegalStateException if the Cholesky factorization fails
     * @return solution with fixed entries copied from {@code start}
     */
    public static double[] directSolve(Matrix matrix,
            double[] rhs,
            double[] start,
            boolean[] fixed) {
        int n = matrix.size();

        // Step 1: identify free variables (the ones we actually solve for)
        int[] compactOf = new int[n];
        int[] fullOf = new int[n];
        Arrays.fill(compactOf, -1);
        int freeCount = 0;
        for (int i = 0; i < n; i++) {
            if (!fixed[i]) {
                compactOf[i] = freeCount;
                fullOf[freeCount] = i;
                freeCount++;
            }
        }
        if (freeCount == 0) {
            return start.clone();
        }

        // Step 2: build adjacency list of the compact (free-only) matrix
        // for the reordering algorithm
        int[][] adj = buildAdjacency(matrix, fixed, compactOf, freeCount);

        // Step 3: compute reordering using Reverse Cuthill-McKee
        int[] perm = reverseCuthillMcKee(adj);
        int[] invPerm = new int[freeCount];
        for (int i = 0; i < freeCount; i++) {
            invPerm[perm[i]] = i;
        }

        // Step 4: build the permuted matrix (in new variable order) for EJML.
        // EJML's Cholesky needs the upper triangle in CSC format.
        DMatrixSparseTriplet triplets = new DMatrixSparseTriplet(freeCount, freeCount, 0);
        double[] permRhs = new double[freeCount];
        for (int newRow = 0; newRow < freeCount; newRow++) {
            int oldU = perm[newRow]; // compact-index in old order
            int row = fullOf[oldU]; // full-index into matrix
            double value = rhs[row];
            triplets.addItem(newRow, newRow, matrix.diag(row));
            for (int c = matrix.rowStart(row); c < matrix.rowEnd(row); c++) {
                int col = matrix.column(c);
                double a = matrix.value(c);
                if (fixed[col]) {
                    value -= a * start[col];
                } else {
                    int oldV = compactOf[col];
                    int newCol = invPerm[oldV];
                    if (newRow < newCol) { // upper triangle only
                        triplets.addItem(newRow, newCol, a);
                    }
                }
            }
            permRhs[newRow] = value;
        }

        DMatrixSparseCSC csc = new DMatrixSparseCSC(freeCount, freeCount,
                triplets.nz_length);
        DConvertMatrixStruct.convert(triplets, csc);

        // Step 5: factorize and solve
        var solver = LinearSolverFactory_DSCC
                .cholesky(FillReducing.NONE);
        if (!solver.setA(csc)) {
            throw new IllegalStateException("Cholesky factorization failed");
        }
        DMatrixRMaj b = new DMatrixRMaj(freeCount, 1, true, permRhs);
        DMatrixRMaj solX = new DMatrixRMaj(freeCount, 1);
        solver.solve(b, solX);

        // Step 6: unpermute the solution back into the full-size answer
        double[] x = start.clone();
        for (int newRow = 0; newRow < freeCount; newRow++) {
            int oldU = perm[newRow];
            x[fullOf[oldU]] = solX.get(newRow, 0);
        }
        return x;
    }

    /**
     * Build adjacency list of the compact symmetric matrix (free vars only).
     *
     * @param matrix    full symmetric system matrix A
     * @param fixed     mask of held-fixed variables
     * @param compactOf full-index → compact-index lookup (or {@code -1} for fixed rows)
     * @param freeCount number of free variables (size of the compact problem)
     * @return per-free-variable list of free-variable neighbours (off-diagonal only)
     */
    private static int[][] buildAdjacency(Matrix matrix,
            boolean[] fixed,
            int[] compactOf,
            int freeCount) {
        int n = matrix.size();
        int[] degree = new int[freeCount];
        for (int i = 0; i < n; i++) {
            if (fixed[i])
                continue;
            int u = compactOf[i];
            for (int c = matrix.rowStart(i); c < matrix.rowEnd(i); c++) {
                int col = matrix.column(c);
                if (!fixed[col] && col != i) {
                    degree[u]++;
                }
            }
        }
        int[][] adj = new int[freeCount][];
        for (int u = 0; u < freeCount; u++) {
            adj[u] = new int[degree[u]];
        }
        int[] cursor = new int[freeCount];
        for (int i = 0; i < n; i++) {
            if (fixed[i])
                continue;
            int u = compactOf[i];
            for (int c = matrix.rowStart(i); c < matrix.rowEnd(i); c++) {
                int col = matrix.column(c);
                if (!fixed[col] && col != i) {
                    adj[u][cursor[u]++] = compactOf[col];
                }
            }
        }
        return adj;
    }

    /**
     * Reverse Cuthill-McKee ordering. Returns perm[newIndex] = oldIndex.
     *
     * @param adj per-vertex neighbour lists for the compact problem
     * @return permutation mapping new compact index to old compact index
     */
    private static int[] reverseCuthillMcKee(int[][] adj) {
        int n = adj.length;
        int[] perm = new int[n];
        boolean[] visited = new boolean[n];
        int filled = 0;

        while (filled < n) {
            // Find unvisited node of minimum degree as the BFS start
            int start = -1;
            int minDeg = Integer.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (!visited[i] && adj[i].length < minDeg) {
                    minDeg = adj[i].length;
                    start = i;
                }
            }

            // BFS, sorting each level's neighbors by degree
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            visited[start] = true;
            while (!queue.isEmpty()) {
                int u = queue.poll();
                perm[filled++] = u;
                int[] nbrs = adj[u].clone();
                // Sort unvisited neighbors by ascending degree
                Integer[] boxed = new Integer[nbrs.length];
                for (int i = 0; i < nbrs.length; i++)
                    boxed[i] = nbrs[i];
                Arrays.sort(boxed, (a, b) -> adj[a].length - adj[b].length);
                for (int v : boxed) {
                    if (!visited[v]) {
                        visited[v] = true;
                        queue.add(v);
                    }
                }
            }
        }

        // Reverse the order
        int[] reversed = new int[n];
        for (int i = 0; i < n; i++) {
            reversed[i] = perm[n - 1 - i];
        }
        return reversed;
    }

    private static BootstrapResult bootstrapSolve(Matrix matrix,
            double[] rhs,
            double[] start,
            boolean[] fixed) {
        double[] direct = directSolve(matrix, rhs, start, fixed);
        double residual = residualNorm(matrix, rhs, direct, fixed);
        return new BootstrapResult(direct, Method.DIRECT, true, residual);
    }

    private static int addPatchVariable(int variable,
            int[] patch,
            boolean[] marked,
            int count) {
        if (variable < 0 || variable >= marked.length || marked[variable]) {
            return count;
        }
        if (count >= patch.length) {
            throw new IllegalArgumentException("patch output array is too small");
        }
        marked[variable] = true;
        patch[count] = variable;
        return count + 1;
    }

    private static void enqueueDependents(Matrix matrix,
            int row,
            boolean[] fixed,
            IntArrayQueue queue,
            boolean[] inQueue) {
        if (row < 0 || row >= matrix.size()) {
            return;
        }
        for (int c = matrix.rowStart(row); c < matrix.rowEnd(row); c++) {
            int col = matrix.column(c);
            if (fixed[col] || inQueue[col]) {
                continue;
            }
            queue.offer(col);
            inQueue[col] = true;
        }
    }

    private static double rowDot(Matrix matrix, int row, double[] x) {
        double sum = matrix.diag(row) * x[row];
        for (int c = matrix.rowStart(row); c < matrix.rowEnd(row); c++) {
            sum += matrix.value(c) * x[matrix.column(c)];
        }
        return sum;
    }

    private static CapResidual maxQueuedResidual(Matrix matrix,
            double[] rhs,
            double[] x,
            boolean[] fixed,
            IntArrayQueue queue) {
        double[] maxRef = {0.0};
        int[] maxRowRef = {-1};
        queue.forEach(row -> {
            if (fixed[row]) {
                return;
            }
            double residual = Math.abs(rhs[row] - rowDot(matrix, row, x));
            if (residual > maxRef[0]) {
                maxRef[0] = residual;
                maxRowRef[0] = row;
            }
        });
        return new CapResidual(maxRef[0], maxRowRef[0]);
    }

    private static double residualNorm(Matrix matrix, double[] rhs, double[] x, boolean[] fixed) {
        double sum = 0.0;
        for (int i = 0; i < matrix.size(); i++) {
            if (fixed[i]) {
                continue;
            }
            double residual = rhs[i] - rowDot(matrix, i, x);
            sum += residual * residual;
        }
        return Math.sqrt(sum);
    }

    private static double residualNormSquared(double[] residual, boolean[] fixed) {
        double sum = 0.0;
        for (int i = 0; i < residual.length; i++) {
            if (!fixed[i]) {
                sum += residual[i] * residual[i];
            }
        }
        return sum;
    }

    private static void validateInputs(Matrix matrix, double[] rhs, double[] warmStart, boolean[] fixed) {
        int n = matrix.size();
        if (rhs.length != n || warmStart.length != n || fixed.length != n) {
            throw new IllegalArgumentException("matrix/vector size mismatch");
        }
    }

    /**
     * Primitive-int FIFO sized for at most {@code n} entries (caller guarantees
     * deduplication via an external {@code inQueue[]} flag, so the buffer never
     * needs to hold more than {@code n} live elements). Avoids the boxing cost
     * of {@link ArrayDeque} on the local Gauss-Seidel hot path, which can run
     * tens of millions of pop/push operations per build.
     */
    static final class IntArrayQueue {
        private final int[] buf;
        private int head;
        private int tail;

        IntArrayQueue(int capacity) {
            this.buf = new int[capacity];
        }

        void offer(int v) {
            buf[tail++] = v;
            if (tail == buf.length) {
                tail = 0;
            }
        }

        int poll() {
            int v = buf[head++];
            if (head == buf.length) {
                head = 0;
            }
            return v;
        }

        boolean isEmpty() {
            return head == tail;
        }

        int size() {
            int s = tail - head;
            return s < 0 ? s + buf.length : s;
        }

        void forEach(IntConsumer consumer) {
            int i = head;
            while (i != tail) {
                consumer.accept(buf[i++]);
                if (i == buf.length) {
                    i = 0;
                }
            }
        }
    }

    /**
     * Sparse row-access interface used by {@link AdaptiveSolver}.
     *
     * <p>
     * The matrix is expected to be symmetric for the CG/direct fallback to match
     * BZK09's normal-equation systems. Diagonal entries are supplied through
     * {@link #diag(int)}; the row-entry range is expected to contain off-diagonal
     * entries only.
     */
    public interface Matrix {
        /**
         * Square dimension of the matrix.
         *
         * @return number of rows and columns
         */
        int size();

        /**
         * Diagonal coefficient of the given row.
         *
         * @param row row index in {@code [0, size())}
         * @return diagonal coefficient A[row,row]
         */
        double diag(int row);

        /**
         * First off-diagonal cursor for the given row.
         *
         * @param row row index in {@code [0, size())}
         * @return first row-entry cursor, inclusive
         */
        int rowStart(int row);

        /**
         * Off-diagonal end cursor for the given row.
         *
         * @param row row index in {@code [0, size())}
         * @return last row-entry cursor, exclusive
         */
        int rowEnd(int row);

        /**
         * Column index referenced by an off-diagonal cursor.
         *
         * @param cursor row-entry cursor in {@code [rowStart(row), rowEnd(row))}
         * @return column index for row-entry cursor
         */
        int column(int cursor);

        /**
         * Coefficient value referenced by an off-diagonal cursor.
         *
         * @param cursor row-entry cursor in {@code [rowStart(row), rowEnd(row))}
         * @return coefficient value for row-entry cursor
         */
        double value(int cursor);
    }

    /**
     * Solver tuning values. Defaults mirror BZK09's intent: cheap local updates
     * first, global iterative solve second, direct solve only as a last resort.
     */
    public static final class Options {
        /** Maximum local Gauss-Seidel row updates before falling back. */
        public int localMaxIterations = 5_000;

        /** Absolute per-row residual tolerance for local Gauss-Seidel. */
        public double localTolerance = 1e-6;

        /** Maximum conjugate-gradient iterations before direct fallback. */
        public int cgMaxIterations = 20_000;

        /** Relative residual tolerance for conjugate gradient. */
        public double cgTolerance = 1e-3;

        /** Whether to use the direct sparse fallback after CG failure. */
        public boolean useDirectFallback = true;

        /**
         * If true, use the colored parallel Gauss-Seidel variant instead of
         * the serial queue-based one. Convergence may take more total
         * iterations (omega=1.0 inside; serial uses 1.7 SOR), but each
         * iteration runs across multiple cores when the work-set is large
         * enough to amortise per-task overhead.
         */
        public boolean useColoredGaussSeidel = false;

        /**
         * Optional precomputed coloring of the matrix (row index → color).
         * Honored only when {@link #useColoredGaussSeidel} is true. If null,
         * the coloring is recomputed per call — pass a cached coloring across
         * the many small per-batch solves of a greedy MIP loop.
         */
        public int[] coloring;
    }

    /** Which rung of the adaptive ladder returned the final solution. */
    public enum Method {
        LOCAL_GAUSS_SEIDEL,
        CONJUGATE_GRADIENT,
        DIRECT,
        FAILED
    }

    /**
     * Solver statistics for one adaptive update.
     *
     * @param method           method that returned the solution
     * @param converged        whether the returned solution met its method
     *                         tolerance
     * @param localIterations  local Gauss-Seidel updates performed
     * @param cgIterations     conjugate-gradient iterations performed
     * @param residualNorm     final Euclidean residual norm over free variables
     * @param localHitCap      whether local Gauss-Seidel stopped at its iteration
     *                         cap
     * @param initialQueueSize variables seeded into the local queue
     * @param maxQueueSize     maximum queue size reached during local GS
     * @param capResidualNorm  largest absolute queued residual when local GS hit
     *                         its iteration cap
     * @param capResidualRow   row producing {@code capResidualNorm}, or -1
     */
    public record Stats(Method method,
            boolean converged,
            int localIterations,
            int cgIterations,
            double residualNorm,
            boolean localHitCap,
            int initialQueueSize,
            int maxQueueSize,
            double capResidualNorm,
            int capResidualRow) {
    }

    /**
     * Result of one adaptive solve.
     *
     * @param x     full solution vector, including fixed variables
     * @param stats iteration and convergence data
     */
    public record Result(double[] x, Stats stats) {
    }

    private record LocalResult(double[] x,
            int iterations,
            boolean converged,
            boolean hitCap,
            int initialQueueSize,
            int maxQueueSize,
            double capResidualNorm,
            int capResidualRow) {
    }

    private record BootstrapResult(double[] x, Method method, boolean converged, double residualNorm) {
    }

    private record CgResult(double[] x, int iterations, boolean converged) {
    }

    private record CapResidual(double norm, int row) {
    }
}
