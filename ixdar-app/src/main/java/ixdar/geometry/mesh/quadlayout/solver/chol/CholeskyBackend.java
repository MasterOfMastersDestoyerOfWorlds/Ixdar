package ixdar.geometry.mesh.quadlayout.solver.chol;

import ixdar.geometry.mesh.quadlayout.solver.FactorizedSystem;
import ixdar.geometry.mesh.quadlayout.solver.SingularSystemException;
import ixdar.geometry.mesh.quadlayout.solver.matrix.CompressedSparseRowArrays;
import ixdar.geometry.mesh.quadlayout.solver.matrix.NormalMatrix;
import ixdar.platform.Platforms;

/**
 * Cholesky backend selection: PARDISO (MKL), Accelerate on macOS, then {@link EjmlCholeskyFactor}.
 * A singular rung is retried once on a diagonally shifted copy before the next rung is tried.
 */
public final class CholeskyBackend {

    /** Fraction of the largest free diagonal entry used as the shift on a singular retry. */
    public static final double SINGULAR_DIAGONAL_SHIFT_FRACTION = 1.0e-10;

    /**
     * When true, {@link #nativeBackend()} reports no native backend, forcing the
     * pure-Java {@link EjmlCholeskyFactor} path. Set by benchmarks to measure the
     * EJML baseline on machines where a native backend loads.
     */
    public static boolean forceEjml;

    private CholeskyBackend() {
    }

    /**
     * Kick off the native-library probe on a background daemon thread so the load
     * overlaps earlier pipeline stages. No-op on platforms without a native backend.
     */
    public static void preloadAsync() {
        NativeCholeskyBackend backend = Platforms.get().nativeCholeskyBackend();
        if (backend != null) {
            backend.preloadAsync();
        }
    }

    /**
     * Factor the free-variable submatrix of {@code matrix} under the supplied
     * permutation with the best available backend.
     *
     * @param matrix    symmetric system matrix (full-symmetric CSR storage)
     * @param freeCount number of free (non-fixed) variables
     * @param fixed     full-size fixed-variable mask
     * @param compactOf full-index → compact-index, or -1 if fixed
     * @param fullOf    compact-index → full-index, length {@code freeCount}
     * @param perm      permuted-index → old compact-index, length {@code freeCount}
     * @param invPerm   old compact-index → permuted-index, length {@code freeCount}
     * @throws SingularSystemException when every rung refuses the system, shifted and unshifted
     * @return the factorized system operating in permuted compact index space
     */
    public static FactorizedSystem factor(NormalMatrix matrix, int freeCount, boolean[] fixed,
            int[] compactOf, int[] fullOf, int[] perm, int[] invPerm) {
        double shift = diagonalShiftFor(matrix.diagonal, fixed);
        NativeCholeskyBackend backend = nativeBackend();
        if (backend != null) {
            CompressedSparseRowArrays upperCsr = matrix.toPermutedUpperCompressedSparseRow(
                    freeCount, fixed, compactOf, fullOf, perm, invPerm);
            try {
                return backend.factorUpper(upperCsr, freeCount);
            } catch (SingularSystemException singular) {
                recordSingular(matrix, singular, shift);
                for (int row = 0; row < freeCount; row++) {
                    upperCsr.values[upperCsr.rowPtr[row]] += shift;
                }
                try {
                    return backend.factorUpper(upperCsr, freeCount);
                } catch (SingularSystemException stillSingular) {
                    Platforms.log("[solver] shifted native factor still singular (%s);"
                            + " falling to the EJML backend%n", stillSingular.getMessage());
                }
            }
        }
        NormalMatrix.CompressedSparseColumnArrays upperCsc =
                matrix.toPermutedUpperCompressedSparseColumn(
                        freeCount, fixed, compactOf, fullOf, perm, invPerm);
        try {
            return new EjmlCholeskyFactor(upperCsc, freeCount);
        } catch (SingularSystemException singular) {
            recordSingular(matrix, singular, shift);
            for (int column = 0; column < freeCount; column++) {
                upperCsc.values()[upperCsc.colPtr()[column]] += shift;
            }
            return new EjmlCholeskyFactor(upperCsc, freeCount);
        }
    }

    /**
     * The regularizing shift for a system: {@link #SINGULAR_DIAGONAL_SHIFT_FRACTION} of its
     * largest finite free diagonal entry. Non-finite entries are skipped rather than propagated,
     * so a broken assembly still gets a usable shift and a readable log line.
     *
     * @param diagonal full-size diagonal of the system
     * @param fixed    full-size fixed-variable mask
     * @return the shift to add to every free diagonal entry
     */
    public static double diagonalShiftFor(double[] diagonal, boolean[] fixed) {
        double largest = 0.0;
        for (int variable = 0; variable < diagonal.length; variable++) {
            double value = Math.abs(diagonal[variable]);
            if (!fixed[variable] && Double.isFinite(value) && value > largest) {
                largest = value;
            }
        }
        return SINGULAR_DIAGONAL_SHIFT_FRACTION * Math.max(largest, 1.0);
    }

    /**
     * Log the fall-through and stamp the failing pivot and shift on the matrix, so the stage that
     * assembled it can diagnose the singularity against its own DOF maps.
     *
     * @param matrix   the matrix the backend refused
     * @param singular the refusal, carrying the backend's pivot index
     * @param shift    diagonal shift the retry will add
     */
    private static void recordSingular(NormalMatrix matrix, SingularSystemException singular,
            double shift) {
        matrix.singularPivotIndex = singular.pivotIndex;
        matrix.appliedDiagonalShift = shift;
        Platforms.log("[solver] singular system (%s); retrying with diagonal shift %.6e%n",
                singular.getMessage(), shift);
    }

    /**
     * Whether factorizations will take a native path (PARDISO or Accelerate), which also decides the
     * storage callers must hand to {@link FactorizedSystem#refactorize}: row-major for native,
     * column-major for EJML.
     *
     * @return true iff the platform supplies a native backend whose libraries loaded
     */
    public static boolean pardisoAvailable() {
        return nativeBackend() != null;
    }

    /**
     * The platform's native backend when its libraries loaded, else {@code null}.
     *
     * @return usable native backend, or {@code null} to use the pure-Java path
     */
    public static NativeCholeskyBackend nativeBackend() {
        if (forceEjml) {
            return null;
        }
        NativeCholeskyBackend backend = Platforms.get().nativeCholeskyBackend();
        return backend != null && backend.available() ? backend : null;
    }
}
