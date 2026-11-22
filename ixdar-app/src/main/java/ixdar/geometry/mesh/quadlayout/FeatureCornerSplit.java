package ixdar.geometry.mesh.quadlayout;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.fields.crossfield.NDirectionField;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh.EdgeFaceIds;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.geometry.mesh.data.representation.PackedPoints;

/**
 * Chooses the quad layout's alignment edges and refines the mesh so no triangle has two, which
 * would collapse it on one iso-line (BZK09 §5.2): splitting, as BCE13 §3.2 does.
 */
public final class FeatureCornerSplit {

    public static final String LOG_PREFIX = "[feature-corners] ";

    public static final String ALIGNMENT_SLOT = "_alignment_edges";

    private static final int TRIANGLE_CORNERS = 3;

    private static final int CORNER_PAIR_FEATURES = 2;

    private static final float HALF = 0.5f;

    private static final int NOT_ALIGNMENT = 0;

    private static final int ALIGNMENT = 1;

    /** The mesh to lay out: the input itself when nothing needed splitting, else a split copy. */
    public final HalfEdgeMesh mesh;

    /** Per edge id of {@link #mesh}, whether the layout aligns to it. */
    public final boolean[] alignmentByEdgeId;

    /** Edges split: the third edge of each triangle that had exactly two alignment edges, once. */
    public final int edgeSplitCount;

    /**
     * Crease edges left unaligned because they closed a triangle of three: two of a triangle's
     * alignment edges always share an iso coordinate, so three cannot all be kept.
     */
    public final int prunedCount;

    private FeatureCornerSplit(HalfEdgeMesh mesh, boolean[] alignmentByEdgeId, int edgeSplitCount,
            int prunedCount) {
        this.mesh = mesh;
        this.alignmentByEdgeId = alignmentByEdgeId;
        this.edgeSplitCount = edgeSplitCount;
        this.prunedCount = prunedCount;
    }

    /**
     * Choose a surface's alignment edges and split its feature corners, leaving the input untouched.
     *
     * @param source             the triangle mesh to lay out, its normals computed
     * @param featureDihedralCos the cross field's feature threshold
     * @return the result; {@link #mesh} is {@code source} itself when no triangle needed a split
     */
    public static FeatureCornerSplit of(HalfEdgeMesh source, float featureDihedralCos) {
        int[] pruned = new int[1];
        boolean[] alignment = alignmentEdges(source, featureDihedralCos, pruned);
        if (!markCorners(source, alignment, null)) {
            return new FeatureCornerSplit(source, alignment, 0, pruned[0]);
        }
        HalfEdgeMesh copy = copyOf(source);
        boolean[] copyAlignment = alignmentEdges(copy, featureDihedralCos, pruned);
        boolean[] splitEdge = new boolean[copy.edgeIdBound()];
        markCorners(copy, copyAlignment, splitEdge);
        // The choice rides an edge layer through the splits: a split edge's new half inherits it,
        // and the spokes to the opposite corners take the fill, so a pruned crease stays pruned.
        int layer = copy.declareEdgeLayer(NOT_ALIGNMENT);
        for (int edgeId = 0; edgeId < copyAlignment.length; edgeId++) {
            copy.edgeLayers[layer][edgeId] = copyAlignment[edgeId] ? ALIGNMENT : NOT_ALIGNMENT;
        }
        int edgeSplits = 0;
        Vector3f start = new Vector3f();
        Vector3f end = new Vector3f();
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
        copy.computeNormals();
        boolean[] splitAlignment = new boolean[copy.edgeIdBound()];
        for (int activeEdge = 0; activeEdge < copy.edgeCount(); activeEdge++) {
            int edgeId = copy.edgeIdAt(activeEdge);
            splitAlignment[edgeId] = copy.edgeLayers[layer][edgeId] == ALIGNMENT;
        }
        return new FeatureCornerSplit(copy, splitAlignment, edgeSplits, pruned[0]);
    }

    /**
     * The alignment edges, by edge id: {@link NDirectionField#isAlignmentEdge}'s boundaries and
     * creases, less the mildest interior crease of every triangle all three of whose edges qualify.
     *
     * @param mesh               the surface
     * @param featureDihedralCos the cross field's feature threshold
     * @param pruned             receives the number of creases left unaligned
     * @return per edge id, whether the layout aligns to it
     */
    private static boolean[] alignmentEdges(HalfEdgeMesh mesh, float featureDihedralCos, int[] pruned) {
        boolean[] alignment = new boolean[mesh.edgeIdBound()];
        float[] normalDot = new float[mesh.edgeIdBound()];
        for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
            EdgeFaceIds edge = mesh.edgeFaceIds(activeEdge);
            alignment[edge.edgeId] = NDirectionField.isAlignmentEdge(mesh, edge, featureDihedralCos);
            normalDot[edge.edgeId] = mesh.isBoundaryEdge(edge.edgeId) ? Float.NEGATIVE_INFINITY
                    : mesh.faceNormal(edge.faceA).dot(mesh.faceNormal(edge.faceB));
        }
        pruned[0] = 0;
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            int mildest = -1;
            int features = 0;
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                int edgeId = mesh.faceEdgeAt(faceId, corner);
                if (!alignment[edgeId]) {
                    continue;
                }
                features++;
                if (normalDot[edgeId] > Float.NEGATIVE_INFINITY
                        && (mildest < 0 || normalDot[edgeId] > normalDot[mildest])) {
                    mildest = edgeId;
                }
            }
            if (features == TRIANGLE_CORNERS && mildest >= 0) {
                alignment[mildest] = false;
                pruned[0]++;
            }
        }
        return alignment;
    }

    /**
     * Mark, for every triangle with exactly two alignment edges, its third edge.
     *
     * @param mesh      the surface
     * @param alignment per edge id, whether the layout aligns to it
     * @param splitEdge receives true per edge id to split; null to only test
     * @return whether any triangle needs a split
     */
    private static boolean markCorners(HalfEdgeMesh mesh, boolean[] alignment, boolean[] splitEdge) {
        boolean any = false;
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            int features = 0;
            int plain = -1;
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                int edgeId = mesh.faceEdgeAt(faceId, corner);
                if (alignment[edgeId]) {
                    features++;
                } else {
                    plain = edgeId;
                }
            }
            if (features != CORNER_PAIR_FEATURES) {
                continue;
            }
            any = true;
            if (splitEdge == null) {
                return true;
            }
            splitEdge[plain] = true;
        }
        return any;
    }

    /**
     * A fresh half-edge copy of a triangle mesh, its normals computed, so splits never touch the
     * graph's input.
     *
     * @param source the mesh to copy
     * @return the copy
     */
    private static HalfEdgeMesh copyOf(HalfEdgeMesh source) {
        int[] activeOf = new int[source.vertexIdBound()];
        PackedPoints positions = source.packedPositions(activeOf);
        int[] faceIndices = new int[source.faceCount() * TRIANGLE_CORNERS];
        for (int activeFace = 0; activeFace < source.faceCount(); activeFace++) {
            int faceId = source.faceIdAt(activeFace);
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                faceIndices[activeFace * TRIANGLE_CORNERS + corner] = activeOf[source.faceVertexAt(faceId, corner)];
            }
        }
        HalfEdgeMesh copy = HalfEdgeMeshOps.buildFromIndexedMesh(positions, faceIndices);
        copy.computeNormals();
        return copy;
    }
}
