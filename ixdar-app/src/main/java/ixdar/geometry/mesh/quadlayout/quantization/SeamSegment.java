package ixdar.geometry.mesh.quadlayout.quantization;

import org.joml.Vector3f;

/**
 * One boundary edge segment of a tessellated layout patch grid, used by
 * {@link LayoutSeamAudit} to match patch boundaries against each other: a
 * watertight reassembly shares every segment between exactly two patches.
 */
public final class SeamSegment {

    /** Rectangle the segment's patch grid belongs to. */
    public final int rectangleId;

    /** First endpoint in grid order. */
    public final Vector3f start;

    /** Second endpoint in grid order. */
    public final Vector3f end;

    /** Set once the segment has been paired with a partner segment. */
    public boolean matched;

    /**
     * Creates one boundary segment.
     *
     * @param rectangleId rectangle the owning patch grid belongs to
     * @param start       first endpoint in grid order
     * @param end         second endpoint in grid order
     */
    public SeamSegment(int rectangleId, Vector3f start, Vector3f end) {
        this.rectangleId = rectangleId;
        this.start = start;
        this.end = end;
    }

    /**
     * Chord length of the segment.
     *
     * @return distance between the two endpoints
     */
    public double length() {
        return start.distance(end);
    }

    /**
     * Whether this segment coincides with another within a tolerance, in either
     * orientation.
     *
     * @param other     candidate partner segment
     * @param tolerance maximum endpoint distance still considered coincident
     * @return true when both endpoint pairs are within the tolerance
     */
    public boolean coincides(SeamSegment other, double tolerance) {
        boolean sameOrder = start.distance(other.start) <= tolerance
                && end.distance(other.end) <= tolerance;
        boolean swapped = start.distance(other.end) <= tolerance
                && end.distance(other.start) <= tolerance;
        return sameOrder || swapped;
    }
}
