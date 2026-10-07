package ixdar.geometry.mesh.data;

import java.util.Map;
import java.util.function.IntFunction;

import org.joml.Vector3f;

/**
 * Animated exploded view of a partitioned surface's regions, writing each region's offset under
 * its draw tag: at amount {@code t} a region moves by {@code t * (regionCentroid - modelCentroid)},
 * so every pair separates along its centroid line.
 */
public final class RegionExplosion {

    public static final int XYZ = 3;

    public static final float SWEEP_SECONDS = 0.6f;

    public static final float FULL_SPREAD = 0.4f;

    /** Names the draw tag each region's offset is written under. */
    public final IntFunction<String> regionTag;

    /** Area-weighted centroid of each region, packed xyz. */
    public float[] regionCentroidXyz = new float[0];

    /** Surface area of each region, one entry per region measured. */
    public double[] regionArea = new double[0];

    /** Area-weighted centroid of the whole surface, the point regions move away from. */
    public final Vector3f modelCentroid = new Vector3f();

    /** How far the view has gone, 0 assembled to 1 fully exploded, before easing. */
    public float progress;

    /** The progress the view is animating toward. */
    public float target;

    /** The eased amount the offsets were last written for, or -1 when they are stale. */
    public float appliedAmount = -1f;

    /**
     * An assembled view with no regions measured yet.
     *
     * @param regionTag names the draw tag a region's offset is written under
     */
    public RegionExplosion(IntFunction<String> regionTag) {
        this.regionTag = regionTag;
    }

    /**
     * Measures every region's area-weighted centroid and the whole surface's in one pass over the
     * faces, keeping the animation where it is and marking the written offsets stale.
     *
     * @param mesh               the partitioned surface
     * @param regionByActiveFace region of each face, by dense face index, in [0, regions)
     * @param regions            number of regions
     * @throws IllegalArgumentException when the face count and the region assignment differ
     */
    public void measure(MeshTopology mesh, int[] regionByActiveFace, int regions) {
        if (regionByActiveFace.length != mesh.faceCount()) {
            throw new IllegalArgumentException(regionByActiveFace.length + " region labels for "
                    + mesh.faceCount() + " faces");
        }
        regionArea = new double[regions];
        appliedAmount = -1f;
        modelCentroid.zero();
        double[] weightedSum = new double[XYZ];
        double[] regionWeightedSum = new double[XYZ * regions];
        Vector3f anchor = new Vector3f();
        Vector3f first = new Vector3f();
        Vector3f second = new Vector3f();
        Vector3f edgeFirst = new Vector3f();
        Vector3f edgeSecond = new Vector3f();
        for (int activeFace = 0; activeFace < regionByActiveFace.length; activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            int base = XYZ * regionByActiveFace[activeFace];
            mesh.vertexPosition(mesh.faceVertexAt(faceId, 0), anchor);
            for (int corner = 2; corner < mesh.faceVertexCount(faceId); corner++) {
                mesh.vertexPosition(mesh.faceVertexAt(faceId, corner - 1), first);
                mesh.vertexPosition(mesh.faceVertexAt(faceId, corner), second);
                edgeFirst.set(first).sub(anchor);
                edgeSecond.set(second).sub(anchor);
                double area = 0.5 * edgeFirst.cross(edgeSecond).length();
                for (int axis = 0; axis < XYZ; axis++) {
                    double moment = area * (anchor.get(axis) + first.get(axis) + second.get(axis))
                            / XYZ;
                    regionWeightedSum[base + axis] += moment;
                    weightedSum[axis] += moment;
                }
                regionArea[base / XYZ] += area;
            }
        }
        double totalArea = 0;
        for (double area : regionArea) {
            totalArea += area;
        }
        for (int axis = 0; totalArea > 0 && axis < XYZ; axis++) {
            modelCentroid.setComponent(axis, (float) (weightedSum[axis] / totalArea));
        }
        // A region without area has no centroid of its own; placing it at the model centroid
        // keeps it still.
        regionCentroidXyz = new float[XYZ * regions];
        for (int region = 0; region < regions; region++) {
            for (int axis = 0; axis < XYZ; axis++) {
                regionCentroidXyz[XYZ * region + axis] = regionArea[region] > 0
                        ? (float) (regionWeightedSum[XYZ * region + axis] / regionArea[region])
                        : modelCentroid.get(axis);
            }
        }
    }

    /** Animate apart, or back together when apart or on the way. */
    public void toggle() {
        target = target > 0f ? 0f : 1f;
    }

    /**
     * Animate to an amount and hold it there.
     *
     * @param amount 0 assembled to 1 fully exploded; clamped into that range
     */
    public void animateTo(float amount) {
        target = Math.max(0f, Math.min(1f, amount));
    }

    /**
     * Whether any region is drawn away from its rest position, which a pick against the
     * assembled surface would misname.
     *
     * @return true while the written amount is above zero
     */
    public boolean exploded() {
        return appliedAmount > 0f;
    }

    /**
     * Move the view on by one frame and write each region's offset for the eased amount, only
     * when that amount changed. A held direction moves the progress and holds it there; otherwise
     * it runs toward {@link #target}. A full sweep takes {@link #SWEEP_SECONDS}.
     *
     * @param seconds       time since the last frame
     * @param heldDirection positive pushes out, negative pulls in, zero animates to the target
     * @param offsets       draw offsets by tag; emptied when the view is assembled
     */
    public void step(float seconds, int heldDirection, Map<String, Vector3f> offsets) {
        float sweep = Math.max(0f, seconds) / SWEEP_SECONDS;
        if (heldDirection != 0) {
            progress = Math.max(0f, Math.min(1f, progress + Math.signum(heldDirection) * sweep));
            target = progress;
        } else if (progress < target) {
            progress = Math.min(target, progress + sweep);
        } else {
            progress = Math.max(target, progress - sweep);
        }
        float eased = progress * progress * (3 - 2 * progress);
        if (eased == appliedAmount) {
            return;
        }
        if (appliedAmount < 0f || eased == 0f) {
            offsets.clear();
        }
        appliedAmount = eased;
        float spread = eased * FULL_SPREAD;
        for (int region = 0; eased > 0f && region < regionArea.length; region++) {
            int base = XYZ * region;
            offsets.computeIfAbsent(regionTag.apply(region), tag -> new Vector3f())
                    .set(regionCentroidXyz[base], regionCentroidXyz[base + 1],
                            regionCentroidXyz[base + 2])
                    .sub(modelCentroid).mul(spread);
        }
    }

    /**
     * Snap back to assembled at once and drop the written offsets.
     *
     * @param offsets draw offsets by tag, emptied
     */
    public void collapse(Map<String, Vector3f> offsets) {
        progress = 0f;
        target = 0f;
        appliedAmount = 0f;
        offsets.clear();
    }
}
