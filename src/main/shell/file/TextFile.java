package shell.file;

import java.util.ArrayList;
import java.util.Objects;

public class TextFile {
    public final String path;
    private ArrayList<String> lines;

    public TextFile(String path) {
        this.path = path == null ? "" : path;
    }

    public TextFile(String path, ArrayList<String> lines) {
        this.path = path == null ? "" : path;
        this.lines = lines;
    }

    public TextFile(String string, String string2) {
        path = string + string2;
    }

    public String getPath() {
        return path;
    }

    public String getParent() {
        int idx = path.lastIndexOf('/') >= 0 ? path.lastIndexOf('/') : path.lastIndexOf('\\');
        return idx >= 0 ? path.substring(0, idx) : "";
    }

    public String getName() {
        int idx = path.lastIndexOf('/') >= 0 ? path.lastIndexOf('/') : path.lastIndexOf('\\');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    public ArrayList<String> getLines() {
        return lines;
    }

    public void setLines(ArrayList<String> lines) {
        this.lines = lines;
    }

    public int size() {
        return lines.size();
    }

    @Override
    public String toString() {
        return path;
    }

    @Override
    public int hashCode() {
        return Objects.hash(path);
    }

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
