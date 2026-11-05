package ixdar.geometry.mesh.data.ops;

import java.util.Arrays;

import ixdar.geometry.mesh.data.paths.query.RayBatch;
import ixdar.geometry.mesh.data.paths.query.TriangleBvh;
import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * Points carried onto a target mesh along a direction each, cast both ways in parallel and kept at
 * the nearer hit: the shrinkwrap node's project mode and the stroke key's goal projection.
 */
public final class MeshProjection {

    /** Signed distance to the hit along each point's direction, + along it; 0 without a hit. */
    public final float[] offset;

    /** The target face each point's nearer ray met, or {@link MeshTopology#NONE}. */
    public final int[] hitFace;

    private MeshProjection(int count) {
        offset = new float[count];
        hitFace = new int[count];
        Arrays.fill(hitFace, MeshTopology.NONE);
    }

    /**
     * Cast every point's ray forward and backward against the target.
     *
     * @param originXyz    packed ray origins, in the target's coordinates
     * @param directionXyz packed ray directions, unit length so offsets are distances
     * @param target       the mesh projected onto
     * @return the nearer hit per point
     */
    public static MeshProjection along(float[] originXyz, float[] directionXyz, MeshTopology target) {
        MeshProjection projection = new MeshProjection(originXyz.length / 3);
        int[] faceOfTriangle = new int[target.triangleCount()];
        TriangleBvh tree = TriangleBvh.over(target, faceOfTriangle);
        new RayBatch(tree, 0).forEach(projection.offset.length, (rays, point) -> {
            double nearest = Double.POSITIVE_INFINITY;
            for (int sign = 1; sign >= -1; sign -= 2) {
                for (int axis = 0; axis < 3; axis++) {
                    rays.origin[axis] = originXyz[point * 3 + axis];
                    rays.direction[axis] = sign * directionXyz[point * 3 + axis];
                }
                int triangle = tree.nearestHitAvoiding(rays.origin, rays.direction, nearest, null, 0, rays.stack,
                        rays.parameter);
                if (triangle >= 0) {
                    nearest = rays.parameter[0];
                    projection.hitFace[point] = faceOfTriangle[triangle];
                    projection.offset[point] = (float) (sign * nearest);
                }
            }
        });
        return projection;
    }
}
