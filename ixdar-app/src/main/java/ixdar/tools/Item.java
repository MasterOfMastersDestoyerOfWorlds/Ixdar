package ixdar.tools;

import java.util.LinkedHashMap;
import java.util.Map;

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

    /** Named measurements a reader compares rather than parses, such as a region's crease share. */
    public final Map<String, Double> numbers = new LinkedHashMap<>();

    /** Named surface points, xyz each, such as where a region's crease lies. */
    public final Map<String, float[]> points = new LinkedHashMap<>();

    /** Named yes-or-no facts, such as whether a region's crease lies inside it. */
    public final Map<String, Boolean> flags = new LinkedHashMap<>();

    /** Whether it is written where its owner saves. */
    public boolean saved;

    /** Whether it is deleted but kept so undo can bring it back. */
    public boolean deleted;
}
