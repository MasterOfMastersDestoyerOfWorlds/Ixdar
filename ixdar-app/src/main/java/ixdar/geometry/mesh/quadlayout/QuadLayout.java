package ixdar.geometry.mesh.quadlayout;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.gridmap.GlobalGridMap;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessUv;

/**
 * One quad layout of a triangle mesh (Lyon 2021, LCBK19, LCK21a), the typed result of the
 * {@code quad_layout} node group: every stage's product, from the cross field to the patch
 * surfaces.
 */
public final class QuadLayout {

    /** The cross field the layout follows, with its cone points. */
    public CrossField crossField;

    /** The seamless parametrization the separatrices are traced in. */
    public SeamlessUv seamless;

    /**
     * The motorcycle-graph arrangement. The quantization works in place, so once it has run this
     * is the same network as {@link #skeleton}.
     */
    public ArcNetwork motorcycle;

    /** The quantized separatrix skeleton. */
    public ArcNetwork skeleton;

    /** The embedded T-mesh, contracted to a fixed point: the layout's patches and arcs. */
    public ArcNetwork tmesh;

    /** The patch maps in one common integer grid, Newton-relaxed and extracted. */
    public GlobalGridMap globalGrid;

    /** The extracted pure quad mesh. */
    public GeometryBundle quads;

    /** Every live patch's quad grid on the surface, with per-face patch ids. */
    public GeometryBundle patchSurfaces;

    /** The same patch grids as each patch's Coons blend of its boundary. */
    public GeometryBundle patchCoons;
}
