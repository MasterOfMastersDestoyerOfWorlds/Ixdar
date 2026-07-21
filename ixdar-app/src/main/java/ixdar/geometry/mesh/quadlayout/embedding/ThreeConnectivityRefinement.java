package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.List;

/**
 * MPZ14 §4's refinement, the precondition Tutte's map needs: <em>"we ensure that each quad face has
 * a 3-connected mesh, so that bijective parametrizations of the faces over a parametric-domain
 * rectangles can be obtained using Tutte's maps. To achieve this, each edge with two vertices on the
 * boundary is subdivided."</em>
 *
 * <p>Inside a patch region every interior vertex is unclaimed and every boundary vertex is claimed —
 * by one of the bounding arcs or by a node — so the paper's <em>"edge with two vertices on the
 * boundary"</em> is exactly an <em>unclaimed</em> copy edge whose two endpoints are <em>both</em>
 * claimed. No boundary walk is needed: the claim arrays already say which side of the region a
 * vertex is on. Arc-owned edges are left alone; they are the boundary itself, not chords across it.
 *
 * <p>Such an edge is a chord, and it breaks the map two ways. It is a 2-cut of the region, so the
 * region is not 3-connected and Tutte's theorem no longer guarantees an embedding; and it lets a
 * triangle have all three corners on the boundary, which the map pins collinearly onto the
 * rectangle's edges for exactly zero signed area — a degeneracy
 * {@link PatchRectangleMap#assertFoldFree} reports as a fold. Subdividing the chord puts an interior
 * vertex in the middle of it, which removes both.
 *
 * <p>One pass suffices. Subdividing {@code (a,b)} yields only edges incident to the new midpoint,
 * and the midpoint is unclaimed, so no edge it belongs to can have two claimed endpoints — the
 * refinement cannot create fresh chords. The invariant is asserted at the end rather than trusted.
 *
 * <p>LCBK19 applies this lazily rather than generally — <em>"we refine the underlying triangle mesh
 * only if necessary, instead of generally"</em> — so this runs just before the per-patch maps are
 * built, not during the carve and not inside the contraction.
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
