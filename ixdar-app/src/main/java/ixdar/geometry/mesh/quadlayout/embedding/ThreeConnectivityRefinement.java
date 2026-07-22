package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes each patch region 3-connected, as {@link PatchRectangleMap} requires, by subdividing every
 * chord: an unclaimed copy edge with both endpoints claimed.
 *
 * <p>Run after the contraction, before the per-patch maps are built. One pass suffices.
 *
 * <p>See also: MPZ14 Section 4
 */
public final class ThreeConnectivityRefinement {

    /** Split position of a subdivided chord; MPZ14 subdivides, and the midpoint is the neutral choice. */
    private static final double EDGE_MIDPOINT = 0.5;

    public final EmbeddedTMesh tmesh;

    /** Chords subdivided by {@link #refine}. */
    public int subdividedChordCount;

    /**
     * Stores the T-mesh whose working copy is refined.
     *
     * @param tmesh embedded T-mesh whose copy mesh is made 3-connected per patch
     */
    public ThreeConnectivityRefinement(EmbeddedTMesh tmesh) {
        this.tmesh = tmesh;
    }

    /**
     * Subdivides every chord, so each patch region becomes 3-connected and its Tutte map is
     * guaranteed bijective.
     *
     * <p>Because subdividing retriangulates the two faces on either side of each chord, any
     * {@link PatchRegions} built before this call is stale and must be rebuilt afterwards.
     *
     * @return the number of chords subdivided
     * @throws IllegalStateException when a chord survives the pass, which would mean the refinement
     *                               created one and the single-pass argument above is wrong
     */
    public int refine() {
        EmbeddedMeshTopology topology = tmesh.topology;
        for (int edgeId : chordEdges()) {
            if (!topology.copy.hasEdge(edgeId)
                    || topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                continue;
            }
            topology.splitEdgeAtParameter(edgeId, EDGE_MIDPOINT);
            subdividedChordCount++;
        }
        List<Integer> remaining = chordEdges();
        if (!remaining.isEmpty()) {
            throw new IllegalStateException("3-connectivity refinement left " + remaining.size()
                    + " chord edge(s) joining two boundary vertices, starting at copy edge "
                    + remaining.get(0) + "; subdividing a chord cannot create one, so the claim"
                    + " arrays and the copy mesh disagree");
        }
        return subdividedChordCount;
    }

    /**
     * The copy mesh's chord edges: unclaimed edges whose two endpoints are both claimed, i.e.
     * MPZ14's edges with two vertices on the boundary.
     *
     * @return the chord edge ids
     */
    private List<Integer> chordEdges() {
        EmbeddedMeshTopology topology = tmesh.topology;
        List<Integer> chords = new ArrayList<>();
        for (int edgeIndex = 0; edgeIndex < topology.copy.edgeCount(); edgeIndex++) {
            int edgeId = topology.copy.edgeIdAt(edgeIndex);
            if (topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                continue;
            }
            int halfEdge = topology.copy.edgeHalfEdge(edgeId);
            if (isBoundaryVertex(topology.copy.halfEdgeVertex(halfEdge))
                    && isBoundaryVertex(topology.copy.halfEdgeEndVertex(halfEdge))) {
                chords.add(edgeId);
            }
        }
        return chords;
    }

    /**
     * Whether a copy vertex lies on a patch boundary rather than in a region's interior — that is,
     * whether an arc or a node owns it.
     *
     * @param copyVertex copy vertex to test
     * @return true when either ownership claim is set
     */
    private boolean isBoundaryVertex(int copyVertex) {
        return tmesh.topology.ownerArcByCopyVertex[copyVertex] != EmbeddedMeshTopology.UNCLAIMED
                || tmesh.topology.ownerNodeByCopyVertex[copyVertex] != EmbeddedMeshTopology.UNCLAIMED;
    }
}
