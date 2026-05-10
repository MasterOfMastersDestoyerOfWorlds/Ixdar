package ixdar.platform.file;

import java.util.ArrayList;
import java.util.Objects;

public class TextFile {
    public final String path;
    private ArrayList<String> lines;

    /**
     * Create an empty text file backed by {@code path} (no I/O performed).
     *
     * @param path logical path; {@code null} is normalized to {@code ""}
     */
    public TextFile(String path) {
        this.path = path == null ? "" : path;
        this.lines = new ArrayList<>();
    }

    /**
     * Create a text file with pre-populated lines.
     *
     * @param path logical path; {@code null} is normalized to {@code ""}
     * @param lines line buffer (stored by reference, not copied)
     */
    public TextFile(String path, ArrayList<String> lines) {
        this.path = path == null ? "" : path;
        this.lines = lines;
    }

    /**
     * Convenience constructor that concatenates two strings into the path
     * (used by {@link FileManagement#getTempFile} to build {@code "temp" + ".ix"}).
     *
     * @param string path prefix
     * @param string2 path suffix
     */
    public TextFile(String string, String string2) {
        path = string + string2;
    }

    /**
     * Logical path of this file.
     *
     * @return path string passed to the constructor
     */
    public String getPath() {
        return path;
    }

    /**
     * Parent directory portion of {@link #path}, splitting on the last {@code /} or {@code \}.
     *
     * @return parent path, or {@code ""} if the path has no separator
     */
    public String getParent() {
        int idx = path.lastIndexOf('/') >= 0 ? path.lastIndexOf('/') : path.lastIndexOf('\\');
        return idx >= 0 ? path.substring(0, idx) : "";
    }

    /**
     * Base name portion of {@link #path}, splitting on the last {@code /} or {@code \}.
     *
     * @return file name, or the whole path if it has no separator
     */
    public String getName() {
        int idx = path.lastIndexOf('/') >= 0 ? path.lastIndexOf('/') : path.lastIndexOf('\\');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    /**
     * Mutable line buffer; lazily created on first access if {@code null}.
     *
     * @return live list of lines (never {@code null})
     */
    public ArrayList<String> getLines() {
        if (lines == null) {
            lines = new ArrayList<>();
        }
        return lines;
    }

    /**
     * Replace the line buffer.
     *
     * @param lines new line list (stored by reference, not copied)
     */
    public void setLines(ArrayList<String> lines) {
        this.lines = lines;
    }

    /**
     * Number of lines in the buffer.
     *
     * @return {@code getLines().size()}
     */
    public int size() {
        return getLines().size();
    }

    /**
     * {@inheritDoc}.
     *
     * @return the path (lines are not included)
     */
    @Override
    public String toString() {
        return path;
    }

    /**
     * {@inheritDoc}.
     *
     * @return hash derived from {@link #path} only
     */
    @Override
    public int hashCode() {
        return Objects.hash(path);
    }

    /**
     * Two {@link TextFile}s are equal iff their {@link #path}s are equal; line contents are not
     * compared.
     *
     * @param obj other object
     * @return true when {@code obj} is a {@link TextFile} with the same path
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        TextFile other = (TextFile) obj;
        return Objects.equals(path, other.path);
    }
}
