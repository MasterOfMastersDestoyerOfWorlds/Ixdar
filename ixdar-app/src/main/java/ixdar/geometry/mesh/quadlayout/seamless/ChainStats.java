package ixdar.geometry.mesh.quadlayout.seamless;

/**
 * Per-chain measurement collected by {@link ChainTranslationDiagnostic}: how
 * many edges the chain holds, whether canonical A/B flipped at any edge in
 * the chain, and the max component-wise spread of the implied chain
 * translation {@code (s, t)} across the chain's edges. A nonzero spread
 * means BZK09's per-edge integer DOFs were rounded to inconsistent values,
 * which produces the ribbon drift the user is debugging.
 */
public final class ChainStats {
    /** Number of cut edges in this chain. */
    public int edgeCount;
    /** Whether at least one edge in the chain had canonical A on the minus side. */
    public boolean aFlippedSomewhere;
    /**
     * Maximum across the chain's edges of the component-wise range of the
     * implied {@code (s, t)} — i.e. {@code max(s_e) − min(s_e)} or the same
     * for {@code t}, whichever is larger. A consistent chain has spread 0.
     */
    public double maxComponentSpread;
}
