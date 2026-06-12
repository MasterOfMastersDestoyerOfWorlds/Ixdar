package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.List;

/**
 * Embedding of one T-mesh arc as an edge path on the working copy mesh: the
 * visited copy vertices in travel order (start node vertex first) and the copy
 * edges between them. A collapsed zero arc has a single vertex and no edges.
 */
public final class ArcEdgePath {

    public final int arcId;

    /** Copy vertex ids in travel order; first and last are the node vertices. */
    public final List<Integer> copyVertexPath;

    /** Copy edge ids between consecutive path vertices. */
    public final List<Integer> copyEdgePath;

    /**
     * Records one embedded arc path.
     *
     * @param arcId          embedded arc id
     * @param copyVertexPath copy vertices in travel order
     * @param copyEdgePath   copy edges between consecutive vertices
     */
    public ArcEdgePath(int arcId, List<Integer> copyVertexPath, List<Integer> copyEdgePath) {
        this.arcId = arcId;
        this.copyVertexPath = copyVertexPath;
        this.copyEdgePath = copyEdgePath;
    }
}
