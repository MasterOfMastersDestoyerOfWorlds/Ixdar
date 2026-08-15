package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.List;

/**
 * Named groups of copy-mesh geometry a failure or step wants shown: face groups drawn as dot
 * clouds, path groups as lines, marker groups as large markers. Id-space only; the renderer
 * resolves positions.
 */
public final class ArrangementDiagnostic {

    /** Name per face group, parallel to {@link #faceGroups}. */
    public final List<String> faceGroupNames = new ArrayList<>();

    /** Copy face ids per group, drawn as one dot cloud of face centers. */
    public final List<int[]> faceGroups = new ArrayList<>();

    /** Name per path group, parallel to {@link #pathGroups}. */
    public final List<String> pathGroupNames = new ArrayList<>();

    /** Copy-vertex paths, each drawn as one line strip. */
    public final List<List<Integer>> pathGroups = new ArrayList<>();

    /** Name per marker group, parallel to {@link #markerGroups}. */
    public final List<String> markerGroupNames = new ArrayList<>();

    /** Copy vertex ids per group, each drawn as one set of large markers. */
    public final List<int[]> markerGroups = new ArrayList<>();

    /**
     * Appends one named group of copy faces.
     *
     * @param name      label the log line reports for the group
     * @param copyFaces copy face ids of the group
     */
    public void addFaceGroup(String name, int[] copyFaces) {
        faceGroupNames.add(name);
        faceGroups.add(copyFaces);
    }

    /**
     * Appends one named copy-vertex path.
     *
     * @param name           label the log line reports for the path
     * @param copyVertexPath copy vertex ids the line runs through, in order
     */
    public void addPathGroup(String name, List<Integer> copyVertexPath) {
        pathGroupNames.add(name);
        pathGroups.add(copyVertexPath);
    }

    /**
     * Appends one named group of marker vertices.
     *
     * @param name         label the log line reports for the group
     * @param copyVertices copy vertex ids to mark
     */
    public void addMarkerGroup(String name, int[] copyVertices) {
        markerGroupNames.add(name);
        markerGroups.add(copyVertices);
    }

    /**
     * The group names by kind, in the order the renderer assigns palette colours.
     *
     * @return a one-line description of every group
     */
    public String describeGroups() {
        return "face groups " + faceGroupNames + ", path groups " + pathGroupNames
                + ", marker groups " + markerGroupNames + ", coloured in listed order";
    }
}
