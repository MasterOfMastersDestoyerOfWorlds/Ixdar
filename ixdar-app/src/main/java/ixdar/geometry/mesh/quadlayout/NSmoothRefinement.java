package ixdar.geometry.mesh.quadlayout;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.fields.crossfield.TriangleCurvature;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * Refines a triangle mesh until every triangle has {@code |n κ_t| < π}, KCP13 Appendix B's
 * n-smooth condition, under which its triangle indices (§6.1.3) sum to {@code nχ}.
 */
public final class NSmoothRefinement {

    public static final String LOG_PREFIX = "[n-smooth] ";

    public static final int CROSS_FIELD_SYMMETRY = 4;

    public static final int MAX_SPLIT_ROUNDS = 64;

    private static final int NOT_ALIGNMENT = 0;

    private static final int ALIGNMENT = 1;

    private static final float HALF = 0.5f;

    /** The refined mesh: the input itself when every triangle already complied, else a copy. */
    public final HalfEdgeMesh mesh;

    /** Per edge id of {@link #mesh}, whether the layout aligns to it, carried through the splits. */
    public final boolean[] alignmentByEdgeId;

    /** Triangles that broke the condition before any split. */
    public final int violatingAtStart;

    /** Edges split, over every round. */
    public final int edgeSplitCount;

    /** Rounds of splits run. */
    public final int roundCount;

    private NSmoothRefinement(HalfEdgeMesh mesh, boolean[] alignmentByEdgeId, int violatingAtStart,
            int edgeSplitCount, int roundCount) {
        this.mesh = mesh;
        this.alignmentByEdgeId = alignmentByEdgeId;
        this.violatingAtStart = violatingAtStart;
        this.edgeSplitCount = edgeSplitCount;
        this.roundCount = roundCount;
    }

    /**
     * Refines a surface until it is n-smooth, leaving the input untouched.
     *
     * @param source    triangle mesh the cross field will be built on, its normals computed
     * @param alignment per edge id of {@code source}, its alignment edges, or null for none
     * @throws IllegalStateException when {@link #MAX_SPLIT_ROUNDS} rounds leave a triangle
     *                               offending
     * @return the result; {@link #mesh} is {@code source} itself when no triangle offended
     */
    public static NSmoothRefinement of(HalfEdgeMesh source, boolean[] alignment) {
        int violating = markOffendingEdges(source, null);
        if (violating == 0) {
            return new NSmoothRefinement(source, alignment, 0, 0, 0);
        }
        HalfEdgeMesh copy = FeatureCornerSplit.copyOf(source);
        int layer = copy.declareEdgeLayer(NOT_ALIGNMENT);
        if (alignment != null) {
            int[] activeBySourceVertex = new int[source.vertexIdBound()];
            for (int active = 0; active < source.vertexCount(); active++) {
                activeBySourceVertex[source.vertexIdAt(active)] = active;
            }
            for (int activeEdge = 0; activeEdge < source.edgeCount(); activeEdge++) {
                int edgeId = source.edgeIdAt(activeEdge);
                if (edgeId >= alignment.length || !alignment[edgeId]) {
                    continue;
                }
                int halfEdge = source.edgeHalfEdge(edgeId);
                int copyEdge = copy.edgeBetween(
                        copy.vertexIdAt(activeBySourceVertex[source.halfEdgeVertex(halfEdge)]),
                        copy.vertexIdAt(activeBySourceVertex[source.halfEdgeEndVertex(halfEdge)]));
                copy.edgeLayers[layer][copyEdge] = ALIGNMENT;
            }
        }
        int edgeSplits = 0;
        int rounds = 0;
        Vector3f start = new Vector3f();
        Vector3f end = new Vector3f();
        while (true) {
            boolean[] splitEdge = new boolean[copy.edgeIdBound()];
            int offending = markOffendingEdges(copy, splitEdge);
            if (offending == 0) {
                break;
            }
            if (rounds == MAX_SPLIT_ROUNDS) {
                throw new IllegalStateException(LOG_PREFIX + offending + " triangles still break"
                        + " KCP13 Appendix B's n-smooth condition |n kappa_t| < pi after "
                        + MAX_SPLIT_ROUNDS + " rounds of splits");
            }
            for (int edgeId = 0; edgeId < splitEdge.length; edgeId++) {
                if (!splitEdge[edgeId]) {
                    continue;
                }
                int halfEdge = copy.edgeHalfEdge(edgeId);
                copy.vertexPosition(copy.halfEdgeVertex(halfEdge), start);
                copy.vertexPosition(copy.halfEdgeEndVertex(halfEdge), end);
                copy.splitEdge(edgeId, start.add(end).mul(HALF));
                edgeSplits++;
            }
            rounds++;
        }
        copy.computeNormals();
        boolean[] splitAlignment = new boolean[copy.edgeIdBound()];
        for (int activeEdge = 0; activeEdge < copy.edgeCount(); activeEdge++) {
            int edgeId = copy.edgeIdAt(activeEdge);
            splitAlignment[edgeId] = copy.edgeLayers[layer][edgeId] == ALIGNMENT;
        }
        return new NSmoothRefinement(copy, splitAlignment, violating, edgeSplits, rounds);
    }

    /**
     * Counts the triangles breaking {@code |n κ_t| < π}, marking for each the edge opposite its
     * worst corner term; splitting it divides that angle and keeps every angle sum.
     *
     * <p>
     * See also: KCP13 Appendix B
     *
     * @param mesh      the surface
     * @param splitEdge receives true per edge id to split; null to only count
     * @return the number of offending triangles
     */
    private static int markOffendingEdges(HalfEdgeMesh mesh, boolean[] splitEdge) {
        TriangleCurvature curvature = new TriangleCurvature(mesh);
        int offending = 0;
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            double kappa = curvature.measure(mesh.faceIdAt(activeFace));
            if (TriangleCurvature.isNSmooth(kappa, CROSS_FIELD_SYMMETRY)) {
                continue;
            }
            offending++;
            if (splitEdge == null) {
                continue;
            }
            int worst = 0;
            for (int corner = 1; corner < TriangleCurvature.TRIANGLE_CORNERS; corner++) {
                if (Math.signum(kappa) * curvature.term[corner]
                        > Math.signum(kappa) * curvature.term[worst]) {
                    worst = corner;
                }
            }
            splitEdge[mesh.edgeBetween(
                    curvature.corners[(worst + 1) % TriangleCurvature.TRIANGLE_CORNERS],
                    curvature.corners[(worst + 2) % TriangleCurvature.TRIANGLE_CORNERS])] = true;
        }
        return offending;
    }
}
