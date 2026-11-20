package ixdar.geometry.mesh.data.ops;

import java.util.Arrays;

import ixdar.geometry.mesh.data.paths.query.RayBatch;
import ixdar.geometry.mesh.data.paths.query.TriangleBvh;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.PackedPoints;

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
     * @param origins    ray origins, in the target's coordinates
     * @param directions ray directions, one per origin, unit length so offsets are distances
     * @param target     the mesh projected onto
     * @return the nearer hit per point
     */
    public static MeshProjection along(PackedPoints origins, PackedPoints directions, MeshTopology target) {
        MeshProjection projection = new MeshProjection(origins.size());
        int[] faceOfTriangle = new int[target.triangleCount()];
        TriangleBvh tree = TriangleBvh.over(target, faceOfTriangle);
        new RayBatch(tree, 0).forEach(projection.offset.length, (rays, point) -> {
            double nearest = Double.POSITIVE_INFINITY;
            for (int sign = 1; sign >= -1; sign -= 2) {
                rays.origin.set(origins.get(point, 0), origins.get(point, 1), origins.get(point, 2));
                rays.direction.set(directions.get(point, 0), directions.get(point, 1), directions.get(point, 2))
                        .mul(sign);
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
