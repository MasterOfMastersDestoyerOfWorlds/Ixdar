package ixdar.geometry.mesh.data.sculpt;

import java.util.Arrays;

import org.joml.Vector3f;

/**
 * A polygon's fill samples: seeded by every vertex within the sample spacing, and shaped by the
 * union of the samples' bumps at their bump-weighted mean weight, so a bowl's weights curve its
 * floor.
 */
public final class FillStroke extends SculptStroke {

    /**
     * A fill reaching across the mirror plane into its own reflection joins it as one region, so
     * the two halves union instead of meeting in a groove on the midline.
     *
     * @param reflection the reflected copy
     * @return true when the reflection was merged into this fill
     */
    @Override
    public boolean absorbs(SculptStroke reflection) {
        float nearest = Float.POSITIVE_INFINITY;
        for (float distance : nearestDistances(reflection.pointXyz, pointXyz)) {
            nearest = Math.min(nearest, distance);
        }
        if (nearest >= radius) {
            return false;
        }
        int ownLength = pointXyz.length;
        pointXyz = Arrays.copyOf(pointXyz, ownLength + reflection.pointXyz.length);
        System.arraycopy(reflection.pointXyz, 0, pointXyz, ownLength, reflection.pointXyz.length);
        int ownCount = pointWeight.length;
        pointWeight = Arrays.copyOf(pointWeight, ownCount + reflection.pointWeight.length);
        System.arraycopy(reflection.pointWeight, 0, pointWeight, ownCount, reflection.pointWeight.length);
        return true;
    }

    /**
     * Every vertex within the sample spacing of a sample seeds the search, so a region refined
     * finer than its samples is reached solid; on a coarser mesh each sample still takes its nearest
     * vertex.
     *
     * @param sum the pass's displacement sum over the refined surface
     * @return how many seeds were written
     */
    @Override
    public int seed(DisplacementSum sum) {
        int pointCount = pointWeight.length;
        float spacingSquared = Float.POSITIVE_INFINITY;
        for (int point = 0; point < pointCount; point++) {
            for (int other = point + 1; other < pointCount; other++) {
                spacingSquared = Math.min(spacingSquared, Vector3f.distanceSquared(pointXyz[point * 3],
                        pointXyz[point * 3 + 1], pointXyz[point * 3 + 2], pointXyz[other * 3],
                        pointXyz[other * 3 + 1], pointXyz[other * 3 + 2]));
            }
        }
        int vertexCount = sum.mesh.vertexCount();
        sum.ensureSources(vertexCount);
        boolean[] taken = new boolean[sum.mesh.vertexIdBound()];
        Vector3f position = new Vector3f();
        int count = 0;
        for (int point = 0; point < pointCount; point++) {
            int nearestId = -1;
            float nearestDistance = Float.POSITIVE_INFINITY;
            for (int activeVertex = 0; activeVertex < vertexCount && pointWeight[point] > 0f; activeVertex++) {
                int vertexId = sum.mesh.vertexIdAt(activeVertex);
                float distance = sum.mesh.vertexPosition(vertexId, position).distanceSquared(pointXyz[point * 3],
                        pointXyz[point * 3 + 1], pointXyz[point * 3 + 2]);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestId = vertexId;
                }
                if (distance <= spacingSquared && !taken[vertexId]) {
                    taken[vertexId] = true;
                    sum.sources[count++] = vertexId;
                }
            }
            if (nearestId >= 0 && !taken[nearestId]) {
                taken[nearestId] = true;
                sum.sources[count++] = nearestId;
            }
        }
        return count;
    }

    /**
     * One minus the product of what each sample's bump leaves uncovered (the largest alone scallops
     * the rim between samples), at the bump-weighted mean of their weights.
     *
     * @param position     the vertex's position, overwritten
     * @param direction    the stroke's mean normal, unit length
     * @param edgeFraction unused: a fill measures straight to its samples
     * @return the kernel
     */
    @Override
    public float kernel(Vector3f position, Vector3f direction, float edgeFraction) {
        Vector3f vertex = new Vector3f(position);
        Vector3f scratch = new Vector3f();
        float uncovered = 1f;
        float weightedBumps = 0f;
        float bumps = 0f;
        for (int sample = 0; sample < pointWeight.length; sample++) {
            float fraction = elementFraction(sample * 3, sample * 3, position.set(vertex), direction, scratch);
            if (fraction < 1f) {
                float bump = (1f - fraction * fraction) * (1f - fraction * fraction);
                uncovered *= 1f - bump;
                weightedBumps += pointWeight[sample] * bump;
                bumps += bump;
            }
        }
        return bumps > 0f ? (1f - uncovered) * weightedBumps / bumps : 0f;
    }
}
