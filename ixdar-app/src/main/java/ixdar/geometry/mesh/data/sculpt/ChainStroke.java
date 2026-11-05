package ixdar.geometry.mesh.data.sculpt;

import org.joml.Vector3f;

/**
 * A ridge, valley or smooth polyline: seeded by the vertex chain its points snap to, which it also
 * creases, and shaped by the largest weighted bump of its segments.
 */
public final class ChainStroke extends SculptStroke {

    @Override
    public int seed(DisplacementSum sum) {
        StrokeChain chain = sum.chain;
        chain.snap(sum.mesh, pointXyz, pointWeight.length);
        int count = chain.vertexIds.length;
        sum.ensureSources(count);
        System.arraycopy(chain.vertexIds, 0, sum.sources, 0, count);
        float meanWeight = 0f;
        Vector3f position = new Vector3f();
        for (int seed = 0; seed < count; seed++) {
            int nearest = nearestPoint(pointXyz, sum.mesh.vertexPosition(chain.vertexIds[seed], position));
            meanWeight += (nearest < 0 ? 0f : pointWeight[nearest]) / count;
        }
        for (int edgeId : smooth ? new int[0] : chain.edgeIds) {
            sum.strokeCreaseByEdgeId[edgeId] = Math.max(sum.strokeCreaseByEdgeId[edgeId], sum.creaseScale * meanWeight);
        }
        return count;
    }

    @Override
    public float kernel(Vector3f position, Vector3f direction, float edgeFraction) {
        int pointCount = pointWeight.length;
        int segments = pointCount == 1 ? 1 : pointCount - 1;
        Vector3f vertex = new Vector3f(position);
        Vector3f scratch = new Vector3f();
        float largest = 0f;
        // The grid's elements are the segments plus the last point alone, which a kernel skips.
        StrokeElementGrid grid = elementGrid(direction);
        int cell = grid == null ? 0 : grid.cellOf(vertex.x, vertex.y, vertex.z);
        int elements = grid == null ? segments : cell < 0 ? 0 : grid.first(cell + 1) - grid.first(cell);
        for (int slot = 0; slot < elements; slot++) {
            int segment = grid == null ? slot : grid.element(grid.first(cell) + slot);
            if (segment >= segments) {
                continue;
            }
            int start = segment * 3;
            int end = pointCount == 1 ? start : start + 3;
            float fraction = elementFraction(start, end, position.set(vertex), direction, scratch);
            if (fraction < 1f) {
                float weight = pointWeight[segment] + (pointWeight[end / 3] - pointWeight[segment]) * elementAlong;
                largest = Math.max(largest, weight * (1f - fraction * fraction) * (1f - fraction * fraction));
            }
        }
        return largest;
    }
}
