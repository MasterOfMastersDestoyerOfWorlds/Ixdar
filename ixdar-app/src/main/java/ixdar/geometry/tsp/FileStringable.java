package ixdar.geometry.tsp;

public interface FileStringable {
    /**
     * Serialize this object to a single line suitable for writing into an {@code .ix} solution
     * file (round-trippable through {@link PointSetPath#importFromFile}).
     *
     * @return the file-format text representation
     */
    public String toFileString();
}
