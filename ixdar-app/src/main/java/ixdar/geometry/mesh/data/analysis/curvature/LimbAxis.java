package ixdar.geometry.mesh.data.analysis.curvature;

import ixdar.geometry.mesh.csg.QuadTriangulation;
import ixdar.geometry.mesh.data.analysis.segmentation.SemanticPatchDecomposer;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * The direction a limb runs at a point on its surface, which is the normal of the plane that
 * girdles it.
 *
 * <p>
 * The minimum-curvature principal direction, which on any tube is the axis. It only seeds the
 * girdling plane's scan, which keeps the shortest closed cut whatever the seed.
 */
public final class LimbAxis {

    public static final int COORDINATES_PER_POINT = 3;

    /** Surface the cached curvature belongs to. */
    public MeshTopology surface;

    /** Principal directions of {@link #surface}. */
    public PrincipalDirectionField curvature;

    /** Wall time the last {@link #cacheFor} spent, in milliseconds. */
    public double buildMillis;

    private int[] denseIndexByVertexId = new int[0];

    /**
     * Builds the principal directions for {@code mesh}, or reuses the cache when it is the same
     * surface.
     *
     * @param mesh surface the tool is previewing loops on
     */
    public void cacheFor(MeshTopology mesh) {
        if (mesh == surface) {
            return;
        }
        long start = System.nanoTime();
        surface = mesh;
        curvature = null;
        denseIndexByVertexId = new int[0];
        if (mesh == null || mesh.faceCount() == 0) {
            buildMillis = 0.0;
            return;
        }
        // A mesh of one face size is read in place, each face fanned from its first corner; a
        // mixed polygon mesh goes in split along each quad's shorter diagonal, its vertices in
        // active order, so the split's vertex ids are the surface's active indices.
        boolean uniform = true;
        int corners = mesh.faceVertexCount(mesh.faceIdAt(0));
        for (int activeFace = 1; uniform && activeFace < mesh.faceCount(); activeFace++) {
            uniform = mesh.faceVertexCount(mesh.faceIdAt(activeFace)) == corners;
        }
        MeshTopology dense = mesh;
        if (!uniform) {
            QuadTriangulation split = new QuadTriangulation(mesh).build();
            dense = HalfEdgeMeshOps.buildFromIndexedMesh(split.positions, split.triangles);
        }
        curvature = PrincipalDirectionField.compute(dense,
                SemanticPatchDecomposer.computeEdgeDihedrals(dense));
        denseIndexByVertexId = new int[mesh.vertexIdBound()];
        for (int index = 0; index < denseIndexByVertexId.length; index++) {
            denseIndexByVertexId[index] = -1;
        }
        for (int index = 0; index < mesh.vertexCount(); index++) {
            int vertexId = mesh.vertexIdAt(index);
            denseIndexByVertexId[vertexId] = uniform ? vertexId : index;
        }
        buildMillis = (System.nanoTime() - start) / 1e6;
    }

    /**
     * The minimum-curvature direction averaged over the hit face's corners and their one-rings,
     * sign-aligned to the first sample because a principal direction is a line, not a vector.
     *
     * @param faceId  face the hit landed on, whose corners the average runs over
     * @param outAxis receives the unit axis, packed xyz
     * @return true when a direction could be averaged
     */
    public boolean curvatureAxisAt(int faceId, float[] outAxis) {
        if (curvature == null || faceId < 0 || !surface.hasFace(faceId)) {
            return false;
        }
        float[] sample = new float[COORDINATES_PER_POINT];
        outAxis[0] = 0f;
        outAxis[1] = 0f;
        outAxis[2] = 0f;
        for (int corner = 0; corner < surface.faceVertexCount(faceId); corner++) {
            int vertexId = surface.faceVertexAt(faceId, corner);
            accumulateDirection(vertexId, sample, outAxis);
            for (int neighbour = 0; neighbour < surface.vertexEdgeCount(vertexId); neighbour++) {
                int halfEdge = surface.vertexOutgoingHalfEdgeAt(vertexId, neighbour);
                if (halfEdge >= 0) {
                    accumulateDirection(surface.halfEdgeEndVertex(halfEdge), sample, outAxis);
                }
            }
        }
        return normalise(outAxis);
    }

    private void accumulateDirection(int vertexId, float[] sample, float[] outAxis) {
        if (vertexId < 0 || vertexId >= denseIndexByVertexId.length) {
            return;
        }
        int denseIndex = denseIndexByVertexId[vertexId];
        if (denseIndex < 0 || denseIndex >= curvature.vertexIdBound()) {
            return;
        }
        curvature.dirMin(denseIndex, sample);
        float alignment = sample[0] * outAxis[0] + sample[1] * outAxis[1] + sample[2] * outAxis[2];
        float sign = alignment < 0f ? -1f : 1f;
        outAxis[0] += sign * sample[0];
        outAxis[1] += sign * sample[1];
        outAxis[2] += sign * sample[2];
    }

    private static boolean normalise(float[] axis) {
        float length = (float) Math.sqrt(
                axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
        if (length <= 0f) {
            return false;
        }
        axis[0] /= length;
        axis[1] /= length;
        axis[2] /= length;
        return true;
    }
}
