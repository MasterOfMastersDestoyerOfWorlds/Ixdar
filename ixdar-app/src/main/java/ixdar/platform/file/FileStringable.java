package ixdar.platform.file;

public interface FileStringable {
    /**
     * Serialize this object to a single line suitable for writing into an {@code .ix} solution
     * file (round-trippable through {@link FileManagement#importFromFile}).
     *
     * @return the file-format text representation
     */
    public String toFileString();
}
