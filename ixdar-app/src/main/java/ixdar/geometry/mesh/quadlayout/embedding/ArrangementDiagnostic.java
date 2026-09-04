package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * Named groups of copy-mesh geometry a failure or step wants shown: face groups drawn as dot
 * clouds, path groups as lines, marker groups as large markers. Groups are id-space; the
 * resolve methods turn them into the flat-xyz clouds and polylines the renderer takes.
 */
public final class ArrangementDiagnostic {

    /** Components of a packed position. */
    private static final int COMPONENTS = 3;

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

    /**
     * One flat-xyz dot cloud per face group: the centre of every listed face.
     *
     * @param copy the working copy the face ids index into
     * @return face-centre clouds, one per group, in group order
     */
    public List<float[]> faceGroupCenters(HalfEdgeMesh copy) {
        List<float[]> clouds = new ArrayList<>(faceGroups.size());
        Vector3f corner = new Vector3f();
        Vector3f center = new Vector3f();
        for (int[] faceIds : faceGroups) {
            float[] centers = new float[faceIds.length * COMPONENTS];
            for (int index = 0; index < faceIds.length; index++) {
                int corners = copy.faceHalfEdgeCount(faceIds[index]);
                center.zero();
                for (int cornerIndex = 0; cornerIndex < corners; cornerIndex++) {
                    copy.vertexPosition(copy.faceVertexAt(faceIds[index], cornerIndex), corner);
                    center.add(corner);
                }
                center.div(Math.max(1, corners));
                write(centers, index * COMPONENTS, center);
            }
            clouds.add(centers);
        }
        return clouds;
    }

    /**
     * One flat-xyz point set per marker group: the position of every listed vertex.
     *
     * @param copy the working copy the vertex ids index into
     * @return marker positions, one array per group, in group order
     */
    public List<float[]> markerGroupPositions(HalfEdgeMesh copy) {
        List<float[]> groups = new ArrayList<>(markerGroups.size());
        Vector3f point = new Vector3f();
        for (int[] vertexIds : markerGroups) {
            float[] positions = new float[vertexIds.length * COMPONENTS];
            for (int index = 0; index < vertexIds.length; index++) {
                copy.vertexPosition(vertexIds[index], point);
                write(positions, index * COMPONENTS, point);
            }
            groups.add(positions);
        }
        return groups;
    }

    /**
     * One flat-xyz polyline per path group, points in walking order.
     *
     * @param copy the working copy the vertex ids index into
     * @return polylines, one array per path, in group order
     */
    public List<float[]> pathPolylines(HalfEdgeMesh copy) {
        List<float[]> polylines = new ArrayList<>(pathGroups.size());
        Vector3f point = new Vector3f();
        for (List<Integer> path : pathGroups) {
            float[] positions = new float[path.size() * COMPONENTS];
            for (int index = 0; index < path.size(); index++) {
                copy.vertexPosition(path.get(index), point);
                write(positions, index * COMPONENTS, point);
            }
            polylines.add(positions);
        }
        return polylines;
    }

    /**
     * Writes one point into a flat-xyz array.
     *
     * @param target   flat array to write into
     * @param at       first float index to write
     * @param position point to write
     */
    private static void write(float[] target, int at, Vector3f position) {
        target[at] = position.x;
        target[at + 1] = position.y;
        target[at + 2] = position.z;
    }
}
