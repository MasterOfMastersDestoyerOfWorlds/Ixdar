package ixdar.geometry.mesh.quadlayout.crossfield.constraint;

/**
 * Which constraint pinned a face's cross-field direction. Stored per active
 * face in {@code CrossField.faceConstraintSource} so overlays can colour pinned
 * faces by their origin.
 */
public enum ConstraintSource {

    /** Face is not directionally constrained. */
    NONE,

    /** An arm aligned to a boundary edge. */
    BOUNDARY,

    /** An arm aligned to a sharp crease. */
    FEATURE,

    /** Pinned by {@link CurvatureConstraints}: an arm aligned to principal curvature. */
    CURVATURE,

    /** Arbitrary anchor pin added when no other constraint exists, to fix the gauge. */
    ANCHOR;
}
