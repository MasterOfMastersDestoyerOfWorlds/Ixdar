package ixdar.nodegraph.field;

import ixdar.geometry.mesh.data.representation.PackedPoints;

/**
 * Per-element 3D vectors, one point of {@code points} per element.
 */
public record Vector3Field(PackedPoints points) {
    /**
     * Validate that the vectors are present.
     */
    public Vector3Field {
        if (points == null) {
            throw new IllegalArgumentException("points must be non-null");
        }
    }

    /**
     * Number of vectors stored.
     *
     * @return element count
     */
    public int length() {
        return points.size();
    }

    /**
     * X component of the i-th vector.
     *
     * @param i element index, must satisfy {@code 0 <= i < length()}
     * @return the i-th point's x
     */
    public float getX(int i) {
        return points.get(i, 0);
    }

    /**
     * Y component of the i-th vector.
     *
     * @param i element index, must satisfy {@code 0 <= i < length()}
     * @return the i-th point's y
     */
    public float getY(int i) {
        return points.get(i, 1);
    }

    /**
     * Z component of the i-th vector.
     *
     * @param i element index, must satisfy {@code 0 <= i < length()}
     * @return the i-th point's z
     */
    public float getZ(int i) {
        return points.get(i, 2);
    }

    /**
     * Materialize the i-th element as a {@link Vector3Value}.
     *
     * @param i element index, must satisfy {@code 0 <= i < length()}
     * @return new value with x, y, z copied from the i-th point
     */
    public Vector3Value toVector3Value(int i) {
        return new Vector3Value(getX(i), getY(i), getZ(i));
    }

    /**
     * Build a field of {@code len} elements all equal to {@code v}.
     *
     * @param v value to repeat at every element
     * @param len element count, must be non-negative
     * @return new field of length {@code len}
     */
    public static Vector3Field constant(Vector3Value v, int len) {
        PackedPoints d = new PackedPoints(len);
        for (int i = 0; i < len; i++) {
            d.set(i, v.x(), v.y(), v.z());
        }
        return new Vector3Field(d);
    }

    /**
     * Defensive copy of a buffer of points.
     *
     * @param src source points
     * @return new field that does not share storage with {@code src}
     */
    public static Vector3Field copyOf(PackedPoints src) {
        return new Vector3Field(src.copy());
    }
}
