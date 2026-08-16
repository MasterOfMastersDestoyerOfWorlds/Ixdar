package ixdar.geometry.mesh.quadlayout.solver;

import ixdar.platform.Platforms;

/**
 * Cholesky backend selection by native-library availability. There is no flag
 * or property: every factorization uses the fastest backend that loads, falling
 * back to {@link EjmlCholeskyFactor} when the natives are unavailable.
 */
public final class CholeskyBackend {

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
     * @return the factorized system operating in permuted compact index space
     */
    public static FactorizedSystem factor(NormalMatrix matrix, int freeCount, boolean[] fixed,
            int[] compactOf, int[] fullOf, int[] perm, int[] invPerm) {
        NativeCholeskyBackend backend = nativeBackend();
        if (backend != null) {
            return backend.factorUpper(
                    matrix.toPermutedUpperCompressedSparseRow(
                            freeCount, fixed, compactOf, fullOf, perm, invPerm),
                    freeCount);
        }
        return new EjmlCholeskyFactor(
                matrix.toPermutedUpperCompressedSparseColumn(
                        freeCount, fixed, compactOf, fullOf, perm, invPerm),
                freeCount);
    }

    /**
     * Whether factorizations will take the native path, which also decides the storage callers must
     * hand to {@link FactorizedSystem#refactorize}: row-major for native, column-major for EJML.
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
        NativeCholeskyBackend backend = Platforms.get().nativeCholeskyBackend();
        return backend != null && backend.available() ? backend : null;
    }
}
