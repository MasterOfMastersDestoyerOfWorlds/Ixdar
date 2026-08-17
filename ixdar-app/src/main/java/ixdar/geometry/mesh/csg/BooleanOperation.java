package ixdar.geometry.mesh.csg;

/**
 * The three boolean operations QuadMixer defines over a pair of solids.
 *
 * <p>See also: NHE*19 Section 3
 */
public enum BooleanOperation {

    /** Everything inside either solid. */
    UNION,

    /** Everything inside the first solid and outside the second. */
    DIFFERENCE,

    /** Everything inside both solids. */
    INTERSECTION
}
