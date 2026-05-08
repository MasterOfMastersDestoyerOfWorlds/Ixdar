package ixdar.platform.file;

import java.util.ArrayList;
import java.util.Objects;

public class TextFile {
    public final String path;
    private ArrayList<String> lines;

    /**
     * TODO: document {@code TextFile}.
     *
     * @param path TODO: describe
     */
    public TextFile(String path) {
        this.path = path == null ? "" : path;
        this.lines = new java.util.ArrayList<>();
    }

    /**
     * TODO: document {@code TextFile}.
     *
     * @param path TODO: describe
     * @param lines TODO: describe
     */
    public TextFile(String path, ArrayList<String> lines) {
        this.path = path == null ? "" : path;
        this.lines = lines;
    }

    /**
     * TODO: document {@code TextFile}.
     *
     * @param string TODO: describe
     * @param string2 TODO: describe
     */
    public TextFile(String string, String string2) {
        path = string + string2;
    }

    /**
     * TODO: document {@code getPath}.
     *
     * @return TODO: describe
     */
    public String getPath() {
        return path;
    }

    /**
     * TODO: document {@code getParent}.
     *
     * @return TODO: describe
     */
    public String getParent() {
        int idx = path.lastIndexOf('/') >= 0 ? path.lastIndexOf('/') : path.lastIndexOf('\\');
        return idx >= 0 ? path.substring(0, idx) : "";
    }

    /**
     * TODO: document {@code getName}.
     *
     * @return TODO: describe
     */
    public String getName() {
        int idx = path.lastIndexOf('/') >= 0 ? path.lastIndexOf('/') : path.lastIndexOf('\\');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    /**
     * TODO: document {@code getLines}.
     *
     * @return TODO: describe
     */
    public ArrayList<String> getLines() {
        if (lines == null) {
            lines = new java.util.ArrayList<>();
        }
        return lines;
    }

    /**
     * TODO: document {@code setLines}.
     *
     * @param lines TODO: describe
     */
    public void setLines(ArrayList<String> lines) {
        this.lines = lines;
    }

    /**
     * TODO: document {@code size}.
     *
     * @return TODO: describe
     */
    public int size() {
        return getLines().size();
    }

    /**
     * TODO: document {@code toString}.
     *
     * @return TODO: describe
     */
    @Override
    public String toString() {
        return path;
    }

    /**
     * TODO: document {@code hashCode}.
     *
     * @return TODO: describe
     */
    @Override
    public int hashCode() {
        return Objects.hash(path);
    }

    /**
     * TODO: document {@code equals}.
     *
     * @param obj TODO: describe
     * @return TODO: describe
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
