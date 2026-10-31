package ixdar.geometry.volume;

import org.joml.Vector3f;

/**
 * A scalar field sampled on a regular lattice of world points, the value a volume port carries:
 * lattice point (x, y, z) sits at {@code origin + (x, y, z) * spacing} and holds
 * {@code values[index(x, y, z)]}. Positive values are inside by convention.
 */
public final class VoxelGrid {
    public static final int AXES = 3;
    public static final int CHAMFER_NEIGHBOURS = 13;
    public static final int NEIGHBOURS = 6;
    public static final float DUAL_STEP = (float) (1.0 / Math.sqrt(12.0));
    public static final float RAY_STEP = 0.5f;
    public static final float HALF_STEP = 0.5f;

    /** Lattice points along x, y and z. */
    public final int[] size;

    /** Index step of one lattice point along y. */
    public final int strideY;

    /** Index step of one lattice point along z. */
    public final int strideZ;

    /** World position of lattice point (0, 0, 0). */
    public final float[] origin;

    /** World distance between neighbouring lattice points. */
    public final float spacing;

    /** One value per lattice point, x fastest. */
    public final float[] values;

    /**
     * A lattice of zeros.
     *
     * @param size    lattice points along x, y and z
     * @param origin  world position of lattice point (0, 0, 0)
     * @param spacing world distance between neighbouring lattice points
     */
    public VoxelGrid(int[] size, float[] origin, float spacing) {
        this.size = size.clone();
        this.origin = origin.clone();
        this.spacing = spacing;
        strideY = size[0];
        strideZ = size[0] * size[1];
        values = new float[size[0] * size[1] * size[2]];
    }

    /**
     * The lattice index of a point.
     *
     * @param x lattice x
     * @param y lattice y
     * @param z lattice z
     * @return its index into {@link #values}
     */
    public int index(int x, int y, int z) {
        return z * strideZ + y * strideY + x;
    }

    /**
     * One lattice coordinate of an index.
     *
     * @param index lattice index
     * @param axis  0 for x, 1 for y, 2 for z
     * @return the coordinate
     */
    public int coordinate(int index, int axis) {
        return axis == 0 ? index % strideY : axis == 1 ? index / strideY % size[1] : index / strideZ;
    }

    /**
     * World position of a lattice point.
     *
     * @param index lattice index
     * @param dest  receives the position
     * @return {@code dest}
     */
    public Vector3f position(int index, Vector3f dest) {
        return dest.set(origin[0] + coordinate(index, 0) * spacing, origin[1] + coordinate(index, 1) * spacing,
                origin[2] + coordinate(index, 2) * spacing);
    }

    /**
     * The middle of the longest run of set lattice points a ray passes, marched half a lattice step at
     * a time through the lattice's bounding sphere.
     *
     * @param set       one flag per lattice point
     * @param origin    ray origin xyz
     * @param direction ray direction xyz, any length
     * @return the lattice index at the middle of the longest run, or -1 when the ray meets none
     */
    public int longestRunMiddle(boolean[] set, float[] origin, float[] direction) {
        Vector3f along = new Vector3f(direction[0], direction[1], direction[2]).normalize();
        float reach = spacing * (size[0] + size[1] + size[2]);
        Vector3f middle = position(index(size[0] / 2, size[1] / 2, size[2] / 2), new Vector3f());
        float start = middle.sub(origin[0], origin[1], origin[2]).dot(along) - reach / 2;
        float step = RAY_STEP * spacing;
        int steps = (int) (reach / step);
        int runStart = -1;
        int bestStart = -1;
        int bestLength = 0;
        for (int at = 0; at <= steps; at++) {
            int index = nearest(origin, along, start + at * step);
            boolean inside = at < steps && index >= 0 && set[index];
            if (inside && runStart < 0) {
                runStart = at;
            } else if (!inside && runStart >= 0) {
                bestStart = at - runStart > bestLength ? runStart : bestStart;
                bestLength = Math.max(bestLength, at - runStart);
                runStart = -1;
            }
        }
        return bestLength == 0 ? -1 : nearest(origin, along, start + (bestStart + (bestLength - 1) / 2f) * step);
    }

    /**
     * Replace {@link #values} with the signed distance, in lattice steps, to the boundary of a solid:
     * positive inside, the boundary half a step from the points on either side of it.
     *
     * @param solid one flag per lattice point
     */
    public void signedDistance(boolean[] solid) {
        boolean[] empty = new boolean[solid.length];
        for (int index = 0; index < solid.length; index++) {
            empty[index] = !solid[index];
        }
        float[] toSolid = chamferDistance(solid);
        float[] toEmpty = chamferDistance(empty);
        for (int index = 0; index < values.length; index++) {
            values[index] = solid[index] ? toEmpty[index] - HALF_STEP : HALF_STEP - toSolid[index];
        }
    }

    /**
     * Average {@link #values} with their six axis neighbours, half and half, a number of times; each
     * point is held at or below its ceiling, the outer layer left as it is.
     *
     * @param passes  averaging passes
     * @param ceiling largest value per lattice point
     */
    public void smooth(int passes, float[] ceiling) {
        float[] averaged = values.clone();
        for (int pass = 0; pass < passes; pass++) {
            for (int index = 0; index < values.length; index++) {
                boolean interior = true;
                for (int axis = 0; axis < AXES; axis++) {
                    int at = coordinate(index, axis);
                    interior &= at > 0 && at < size[axis] - 1;
                }
                if (interior) {
                    float mean = (values[index - 1] + values[index + 1] + values[index - strideY]
                            + values[index + strideY] + values[index - strideZ] + values[index + strideZ]) / NEIGHBOURS;
                    averaged[index] = Math.min(ceiling[index], (values[index] + mean) / 2f);
                }
            }
            System.arraycopy(averaged, 0, values, 0, values.length);
        }
    }

    /**
     * The lattice point nearest a point along a ray.
     *
     * @param origin ray origin xyz
     * @param along  unit ray direction
     * @param at     distance along the ray
     * @return its index, or -1 when the point rounds outside the lattice
     */
    private int nearest(float[] origin, Vector3f along, float at) {
        int latticeX = Math.round((origin[0] + at * along.x - this.origin[0]) / spacing);
        int latticeY = Math.round((origin[1] + at * along.y - this.origin[1]) / spacing);
        int latticeZ = Math.round((origin[2] + at * along.z - this.origin[2]) / spacing);
        return latticeX < 0 || latticeY < 0 || latticeZ < 0 || latticeX >= size[0] || latticeY >= size[1]
                || latticeZ >= size[2] ? -1 : index(latticeX, latticeY, latticeZ);
    }

    /**
     * Distance, in lattice steps, from every point to the nearest seed, by a forward and a backward
     * sweep of the 3x3x3 chamfer mask with weights 1, sqrt 2 and sqrt 3 (Borgefors, "Distance
     * transformations in digital images", CVGIP 34, 1986).
     *
     * @param seeds the points at distance 0
     * @return the distance per lattice point, {@link Float#MAX_VALUE} where no seed exists
     */
    private float[] chamferDistance(boolean[] seeds) {
        float[] distance = new float[values.length];
        for (int index = 0; index < distance.length; index++) {
            distance[index] = seeds[index] ? 0f : Float.MAX_VALUE;
        }
        for (int sweep = 0; sweep < 2; sweep++) {
            int stepSign = sweep == 0 ? 1 : -1;
            for (int ordinal = 0; ordinal < distance.length; ordinal++) {
                int index = sweep == 0 ? ordinal : distance.length - 1 - ordinal;
                // The 13 neighbours a sweep has already passed: lower z, or the same z and lower y,
                // or the same z and y and lower x (mirrored on the way back).
                for (int neighbour = 0; neighbour < CHAMFER_NEIGHBOURS; neighbour++) {
                    int offsetZ = neighbour < 9 ? -1 : 0;
                    int offsetY = neighbour < 9 ? neighbour / 3 - 1 : neighbour < 12 ? -1 : 0;
                    int offsetX = neighbour < 12 ? neighbour % 3 - 1 : -1;
                    int otherX = coordinate(index, 0) + stepSign * offsetX;
                    int otherY = coordinate(index, 1) + stepSign * offsetY;
                    int otherZ = coordinate(index, 2) + stepSign * offsetZ;
                    if (otherX < 0 || otherY < 0 || otherZ < 0 || otherX >= size[0] || otherY >= size[1]
                            || otherZ >= size[2]) {
                        continue;
                    }
                    float through = distance[index(otherX, otherY, otherZ)];
                    if (through < Float.MAX_VALUE) {
                        distance[index] = Math.min(distance[index], through
                                + (float) Math.sqrt(offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ));
                    }
                }
            }
        }
        return distance;
    }

    /**
     * Total-variation denoising of a field toward a target within per-point bounds (the ROF model:
     * minimise TV(u) + fidelity / 2 * |u - target|^2, Rudin, Osher and Fatemi 1992) by the
     * primal-dual algorithm of Chambolle and Pock (JMIV 2011), forward differences, steps 1/sqrt(12).
     *
     * @param target     one value per lattice point, also the starting field
     * @param fidelity   weight of the distance to the target; a ball smaller than about 3 / fidelity
     *                   lattice steps is smoothed away
     * @param lower      smallest value per lattice point
     * @param upper      largest value per lattice point
     * @param iterations primal-dual iterations
     * @return the denoised field
     */
    public float[] totalVariation(float[] target, float fidelity, float[] lower, float[] upper, int iterations) {
        int count = values.length;
        float[] field = target.clone();
        float[] extrapolated = target.clone();
        float[][] dual = new float[AXES][count];
        int[] strides = { 1, strideY, strideZ };
        for (int iteration = 0; iteration < iterations; iteration++) {
            for (int index = 0; index < count; index++) {
                float length = 0f;
                for (int axis = 0; axis < AXES; axis++) {
                    float gradient = coordinate(index, axis) + 1 < size[axis]
                            ? extrapolated[index + strides[axis]] - extrapolated[index] : 0f;
                    dual[axis][index] += DUAL_STEP * gradient;
                    length += dual[axis][index] * dual[axis][index];
                }
                float scale = Math.max(1f, (float) Math.sqrt(length));
                for (int axis = 0; axis < AXES; axis++) {
                    dual[axis][index] /= scale;
                }
            }
            for (int index = 0; index < count; index++) {
                float divergence = 0f;
                for (int axis = 0; axis < AXES; axis++) {
                    int at = coordinate(index, axis);
                    divergence += (at + 1 < size[axis] ? dual[axis][index] : 0f)
                            - (at > 0 ? dual[axis][index - strides[axis]] : 0f);
                }
                float previous = field[index];
                float next = (previous + DUAL_STEP * (divergence + fidelity * target[index]))
                        / (1f + DUAL_STEP * fidelity);
                field[index] = Math.max(lower[index], Math.min(upper[index], next));
                extrapolated[index] = 2f * field[index] - previous;
            }
        }
        return field;
    }
}
