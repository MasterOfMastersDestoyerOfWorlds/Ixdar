package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.PackedPoints;

/**
 * Named groups of copy-mesh geometry a failure or step wants shown: face groups drawn as dot
 * clouds, path groups as lines, marker groups as large markers. Groups are id-space; the
 * resolve methods turn them into the world-space clouds and polylines the renderer takes.
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

    /**
     * One dot cloud per face group: the centre of every listed face.
     *
     * @param copy the working copy the face ids index into
     * @return face-centre clouds, one per group, in group order
     */
    public List<PackedPoints> faceGroupCenters(HalfEdgeMesh copy) {
        List<PackedPoints> clouds = new ArrayList<>(faceGroups.size());
        Vector3f corner = new Vector3f();
        Vector3f center = new Vector3f();
        for (int[] faceIds : faceGroups) {
            PackedPoints centers = new PackedPoints(faceIds.length);
            for (int index = 0; index < faceIds.length; index++) {
                int corners = copy.faceHalfEdgeCount(faceIds[index]);
                center.zero();
                for (int cornerIndex = 0; cornerIndex < corners; cornerIndex++) {
                    copy.vertexPosition(copy.faceVertexAt(faceIds[index], cornerIndex), corner);
                    center.add(corner);
                }
                centers.set(index, center.div(Math.max(1, corners)));
            }
            clouds.add(centers);
        }
        return clouds;
    }

    /**
     * One point set per marker group: the position of every listed vertex.
     *
     * @param copy the working copy the vertex ids index into
     * @return marker positions, one buffer per group, in group order
     */
    public List<PackedPoints> markerGroupPositions(HalfEdgeMesh copy) {
        List<PackedPoints> groups = new ArrayList<>(markerGroups.size());
        Vector3f point = new Vector3f();
        for (int[] vertexIds : markerGroups) {
            PackedPoints positions = new PackedPoints(vertexIds.length);
            for (int index = 0; index < vertexIds.length; index++) {
                positions.set(index, copy.vertexPosition(vertexIds[index], point));
            }
            groups.add(positions);
        }
        return groups;
    }

    /**
     * One polyline per path group, points in walking order.
     *
     * @param copy the working copy the vertex ids index into
     * @return polylines, one buffer per path, in group order
     */
    public List<PackedPoints> pathPolylines(HalfEdgeMesh copy) {
        List<PackedPoints> polylines = new ArrayList<>(pathGroups.size());
        Vector3f point = new Vector3f();
        for (List<Integer> path : pathGroups) {
            PackedPoints positions = new PackedPoints(path.size());
            for (int index = 0; index < path.size(); index++) {
                positions.set(index, copy.vertexPosition(path.get(index), point));
            }
            polylines.add(positions);
        }
        return polylines;
    }
}
