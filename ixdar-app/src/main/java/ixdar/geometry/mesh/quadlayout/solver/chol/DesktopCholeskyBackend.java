package ixdar.geometry.mesh.quadlayout.solver.chol;

import ixdar.geometry.mesh.quadlayout.solver.FactorizedSystem;
import ixdar.geometry.mesh.quadlayout.solver.chol.accelerate.AccelerateSparseBackend;
import ixdar.geometry.mesh.quadlayout.solver.chol.paradiso.PardisoBackend;
import ixdar.geometry.mesh.quadlayout.solver.matrix.CompressedSparseRowArrays;

/**
 * Desktop/headless {@link NativeCholeskyBackend} ladder: PARDISO (MKL) when its
 * natives load, else Accelerate on macOS. When neither loads, {@code available()}
 * is false and {@link CholeskyBackend} falls through to the pure-Java
 * {@link EjmlCholeskyFactor}.
 *
 * <p>Both rungs consume the same upper-triangle CSR storage, so the row-major
 * contract of {@link CholeskyBackend#pardisoAvailable} holds for either.
 */
public final class DesktopCholeskyBackend implements NativeCholeskyBackend {

    private final PardisoBackend pardiso = new PardisoBackend();
    private final AccelerateSparseBackend accelerate = new AccelerateSparseBackend();

    /** {@inheritDoc}. */
    @Override
    public boolean available() {
        return pardiso.available() || accelerate.available();
    }

    /** {@inheritDoc}. */
    @Override
    public void preloadAsync() {
        pardiso.preloadAsync();
        accelerate.preloadAsync();
    }

    /** {@inheritDoc}. */
    @Override
    public FactorizedSystem factorUpper(CompressedSparseRowArrays upperCsr, int dimension) {
        if (pardiso.available()) {
            return pardiso.factorUpper(upperCsr, dimension);
        }
        return accelerate.factorUpper(upperCsr, dimension);
    }
}
