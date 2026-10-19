package ixdar.geometry.mesh.data.representation;

/**
 * How {@link MeshTopology#triangulate} splits a face into triangles; every rule gives a polygon of
 * {@code n} corners {@code n - 2} triangles wound like the face.
 */
public enum TriangulationRule {
    /** Fan from the face's first corner: corners {@code (0, k, k + 1)}. */
    FAN,
    /**
     * Cut a quad along its shorter diagonal (the 0-2 diagonal on a tie), fanning from corner 1
     * when the 1-3 diagonal is shorter; every other face is fanned as {@link #FAN}.
     */
    SHORTER_DIAGONAL
}
