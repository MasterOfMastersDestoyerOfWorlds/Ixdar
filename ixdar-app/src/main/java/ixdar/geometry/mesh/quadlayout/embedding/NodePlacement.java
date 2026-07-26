package ixdar.geometry.mesh.quadlayout.embedding;

/**
 * Places T-mesh nodes at their exact points in the working copy: reuse a free
 * coincident vertex, split the containing edge, else split the containing face.
 *
 * <p>
 * See also: LCBK19 Section 6.1
 */
public final class NodePlacement {

    public final EmbeddedMeshTopology topology;

    /** Exact placer; its counters report how each node landed. */
    public final FaceChordWalk chordWalk;

    /**
     * Stores the working copy nodes are placed into.
     *
     * @param topology working copy with provenance and claims
     */
    public NodePlacement(EmbeddedMeshTopology topology) {
        this.topology = topology;
        this.chordWalk = new FaceChordWalk(topology);
    }

    /**
     * A claim-free copy vertex at the node's exact point. Must run before any
     * arc is carved, while no edge is yet claimed.
     *
     * @param sourceFace  source active face the node lies in
     * @param barycentric the node's barycentric coordinate in that face
     * @return a copy vertex at the point, owned by nobody
     */
    public int placeVertex(int sourceFace, double[] barycentric) {
        return chordWalk.placeVertex(sourceFace, barycentric);
    }
}
