package ixdar.tools;

/**
 * One thing a {@link Tool} or a scene's shared set holds, such as a confirmed ring, a route
 * segment or a region, in the one shape every holder reports. A {@link Draft} is one being built.
 */
public class Item {

    /** The label it is shown under. */
    public String label = "";

    /** Where it sits, xyz: its centroid or midpoint. */
    public float[] location = new float[Draft.COORDINATES_PER_POINT];

    /** A fingerprint of its geometry, empty when it has none. */
    public String fingerprint = "";

    /** How big it is in its own unit: marked edges for a ring, segments for a route, faces. */
    public int size;

    /** Its length along the surface or the map, zero when it has none. */
    public double length;

    /** What else is known of it, one line, empty for nothing. */
    public String detail = "";

    /** Whether it is written where its owner saves. */
    public boolean saved;

    /** Whether it is deleted but kept so undo can bring it back. */
    public boolean deleted;
}
