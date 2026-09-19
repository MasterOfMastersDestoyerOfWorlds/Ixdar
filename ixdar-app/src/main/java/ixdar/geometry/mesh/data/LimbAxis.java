package ixdar.geometry.mesh.data;

import ixdar.geometry.mesh.data.MeshSkeletonExtractor.SkeletonBranch;
import ixdar.geometry.mesh.data.MeshSkeletonExtractor.SkeletonResult;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.ArrayMeshEngine;

/**
 * The direction a limb runs at a point on its surface, which is the normal of the plane that
 * girdles it.
 *
 * <p>
 * Two independent estimates: the tangent of the nearest skeleton segment, and the
 * minimum-curvature principal direction, which on any tube is the axis.
 */
public final class LimbAxis {

    /** Coordinates per point in every packed position here. */
    public static final int COORDINATES_PER_POINT = 3;

    /** Voxel-grid resolution handed to {@link MeshSkeletonExtractor}. */
    public int resolution = MeshSkeletonExtractor.NUM_128;

    /** Branch-extraction rounds the skeleton may spend, as the ring producers set. */
    public int skeletonBranchBudget = 4 * MeshSkeletonExtractor.DEFAULT_BRANCH_BUDGET;

    /** Surface the cached skeleton and curvature belong to. */
    public MeshTopology surface;

    /** Skeleton of {@link #surface}, or null when extraction found no branch. */
    public SkeletonResult skeleton;

    /** Principal directions of {@link #surface}, built only when the skeleton is missing. */
    public PrincipalDirectionField curvature;

    /** Wall time the last {@link #cacheFor} spent, in milliseconds. */
    public double buildMillis;

    private ArrayMesh dense;
    private int[] denseIndexByVertexId = new int[0];

    /**
     * Builds the skeleton for {@code mesh}, or reuses the cache when it is the same surface.
     *
     * @param mesh surface the tool is previewing loops on
     */
    public void cacheFor(MeshTopology mesh) {
        if (mesh == surface) {
            return;
        }
        long start = System.nanoTime();
        surface = mesh;
        skeleton = null;
        curvature = null;
        dense = null;
        denseIndexByVertexId = new int[0];
        if (mesh == null || mesh.faceCount() == 0) {
            buildMillis = 0.0;
            return;
        }
        dense = ArrayMeshEngine.fromUniformMeshTopology(mesh);
        SkeletonResult extracted =
                MeshSkeletonExtractor.extract(dense, resolution, skeletonBranchBudget);
        if (!extracted.branches().isEmpty()) {
            skeleton = extracted;
        }
        buildMillis = (System.nanoTime() - start) / 1e6;
    }

    /**
     * The direction of the skeleton segment the point belongs to: the one whose distance best
     * matches its own inscribed radius, so a limb passing close by does not claim the point, and
     * segments rather than samples, since the branches are RDP-simplified to a handful of joints.
     *
     * @param x       hit point x
     * @param y       hit point y
     * @param z       hit point z
     * @param outAxis receives the unit axis, packed xyz
     * @return true when the surface has a skeleton to read a tangent from
     */
    public boolean skeletonAxisAt(float x, float y, float z, float[] outAxis) {
        if (skeleton == null) {
            return false;
        }
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (SkeletonBranch branch : skeleton.branches()) {
            for (int joint = 0; joint + 1 < branch.joints().size(); joint++) {
                float[] from = branch.joints().get(joint).position();
                float[] to = branch.joints().get(joint + 1).position();
                double spanX = to[0] - from[0];
                double spanY = to[1] - from[1];
                double spanZ = to[2] - from[2];
                double spanSquared = spanX * spanX + spanY * spanY + spanZ * spanZ;
                if (spanSquared <= 0.0) {
                    continue;
                }
                double along = ((x - from[0]) * spanX + (y - from[1]) * spanY
                        + (z - from[2]) * spanZ) / spanSquared;
                along = Math.max(0.0, Math.min(1.0, along));
                double gapX = from[0] + along * spanX - x;
                double gapY = from[1] + along * spanY - y;
                double gapZ = from[2] + along * spanZ - z;
                double gap = Math.sqrt(gapX * gapX + gapY * gapY + gapZ * gapZ);
                double thickness = branch.joints().get(joint).radius()
                        + along * (branch.joints().get(joint + 1).radius()
                                - branch.joints().get(joint).radius());
                double distance = Math.abs(gap - thickness);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    outAxis[0] = (float) spanX;
                    outAxis[1] = (float) spanY;
                    outAxis[2] = (float) spanZ;
                }
            }
        }
        return nearestDistance < Double.POSITIVE_INFINITY && normalise(outAxis);
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
        if (surface == null) {
            return false;
        }
        if (curvature == null) {
            if (dense == null) {
                return false;
            }
            curvature = PrincipalDirectionField.compute(dense,
                    SemanticPatchDecomposer.computeEdgeDihedrals(dense));
            denseIndexByVertexId = denseIndexMap();
        }
        if (faceId < 0 || !surface.hasFace(faceId)) {
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
        if (denseIndex < 0 || denseIndex >= curvature.vertexCount()) {
            return;
        }
        curvature.dirMin(denseIndex, sample);
        float alignment = sample[0] * outAxis[0] + sample[1] * outAxis[1] + sample[2] * outAxis[2];
        float sign = alignment < 0f ? -1f : 1f;
        outAxis[0] += sign * sample[0];
        outAxis[1] += sign * sample[1];
        outAxis[2] += sign * sample[2];
    }

    private int[] denseIndexMap() {
        int ceiling = 0;
        for (int index = 0; index < surface.vertexCount(); index++) {
            ceiling = Math.max(ceiling, surface.vertexIdAt(index) + 1);
        }
        int[] map = new int[ceiling];
        for (int index = 0; index < map.length; index++) {
            map[index] = -1;
        }
        for (int index = 0; index < surface.vertexCount(); index++) {
            map[surface.vertexIdAt(index)] = index;
        }
        return map;
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
