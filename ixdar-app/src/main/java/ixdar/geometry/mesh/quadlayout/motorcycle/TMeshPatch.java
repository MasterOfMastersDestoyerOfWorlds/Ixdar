package ixdar.geometry.mesh.quadlayout.motorcycle;

import java.util.ArrayList;
import java.util.List;

/**
 * One face of the planar T-mesh arrangement bounded by trace arcs.
 */
public final class TMeshPatch {

    public final int patchId;
    public final List<Integer> boundingArcIds = new ArrayList<>();

    /**
     * Creates one patch entry in the T-mesh arrangement.
     *
     * @param patchId unique patch id
     */
    public TMeshPatch(int patchId) {
        this.patchId = patchId;
    }
}
