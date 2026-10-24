package ixdar.geometry.mesh.data.labels;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.paths.spline.SurfaceWaypoints;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.RingRegions;

/**
 * Named labels on a mesh, each kept as the surface points that select it, never as element
 * numbers: a point picks its nearest face and so the ring region holding it, which stays true when
 * rings are reordered, added or edited elsewhere. Labels keep the order they were first given.
 */
public final class MeshLabels {

    public static final String SLOT = "_labels";

    public static final int XYZ = SurfaceWaypoints.COORDINATES_PER_WAYPOINT;

    public static final int UNLABELLED = -1;

    public static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    /** Name of each label, in the order the labels were first given. */
    public final List<String> names = new ArrayList<>();

    /** Surface points selecting each label's elements, packed xyz, parallel to {@link #names}. */
    public final List<float[]> pointsByLabel = new ArrayList<>();

    /** An empty label set. */
    public MeshLabels() {
    }

    /**
     * A copy that shares no arrays with {@code other}, so editing it leaves the original alone.
     *
     * @param other labels to copy
     */
    public MeshLabels(MeshLabels other) {
        names.addAll(other.names);
        for (float[] points : other.pointsByLabel) {
            pointsByLabel.add(points.clone());
        }
    }

    /**
     * The labels a bundle carries in {@link #SLOT}.
     *
     * @param bundle bundle to read, or {@code null}
     * @return the bundle's labels, or an empty set when it carries none
     */
    public static MeshLabels inBundle(GeometryBundle bundle) {
        return bundle != null && bundle.slots().get(SLOT) instanceof MeshLabels labels ? labels
                : new MeshLabels();
    }

    /**
     * Why a name cannot be a label: it must be one identifier, a letter then letters, digits and
     * underscores, since a saved label statement is bound under it.
     *
     * @param name candidate name
     * @return the refusal, or empty when the name is fine
     */
    public static String nameRefusal(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            return "a label name is a letter followed by letters, digits and underscores, such as "
                    + "claw_left, not '" + name + "'";
        }
        return "";
    }

    /**
     * Add surface points to a label, creating it after the others when it is new.
     *
     * @param name      label name
     * @param packedXyz the points, packed xyz
     * @throws IllegalArgumentException when the name is refused or the points are not whole xyz
     */
    public void add(String name, float[] packedXyz) {
        String refusal = nameRefusal(name);
        if (!refusal.isEmpty()) {
            throw new IllegalArgumentException(refusal);
        }
        if (packedXyz.length % XYZ != 0) {
            throw new IllegalArgumentException("label " + name + " got " + packedXyz.length
                    + " coordinates, not whole x,y,z points");
        }
        int label = names.indexOf(name);
        if (label < 0) {
            names.add(name);
            pointsByLabel.add(packedXyz.clone());
            return;
        }
        float[] before = pointsByLabel.get(label);
        float[] after = Arrays.copyOf(before, before.length + packedXyz.length);
        System.arraycopy(packedXyz, 0, after, before.length, packedXyz.length);
        pointsByLabel.set(label, after);
    }

    /**
     * The label of each ring region: the last label with a point whose nearest face lies in it.
     *
     * @param regions the regions the points are resolved against
     * @return one label index per region, {@link #UNLABELLED} where no point falls
     */
    public int[] labelByRegion(RingRegions regions) {
        int[] labelOf = new int[regions.regionCount];
        Arrays.fill(labelOf, UNLABELLED);
        for (int label = 0; label < names.size(); label++) {
            float[] points = pointsByLabel.get(label);
            for (int point = 0; point < points.length / XYZ; point++) {
                int activeFace = regions.nearestActiveFace(points[XYZ * point],
                        points[XYZ * point + 1], points[XYZ * point + 2]);
                if (activeFace != MeshTopology.NONE) {
                    labelOf[regions.regionByActiveFace[activeFace]] = label;
                }
            }
        }
        return labelOf;
    }

    /**
     * Drop every point that selects one ring region, from whichever labels hold one, and the
     * labels left with no point.
     *
     * @param regions the regions the points are resolved against
     * @param region  the region to clear
     * @return how many points were dropped
     */
    public int unlabel(RingRegions regions, int region) {
        int dropped = 0;
        for (int label = names.size() - 1; label >= 0; label--) {
            float[] points = pointsByLabel.get(label);
            float[] kept = new float[points.length];
            int keptCount = 0;
            for (int point = 0; point < points.length / XYZ; point++) {
                int activeFace = regions.nearestActiveFace(points[XYZ * point],
                        points[XYZ * point + 1], points[XYZ * point + 2]);
                if (activeFace != MeshTopology.NONE
                        && regions.regionByActiveFace[activeFace] == region) {
                    dropped++;
                    continue;
                }
                System.arraycopy(points, XYZ * point, kept, XYZ * keptCount, XYZ);
                keptCount++;
            }
            if (keptCount == 0) {
                names.remove(label);
                pointsByLabel.remove(label);
            } else {
                pointsByLabel.set(label, Arrays.copyOf(kept, XYZ * keptCount));
            }
        }
        return dropped;
    }
}
