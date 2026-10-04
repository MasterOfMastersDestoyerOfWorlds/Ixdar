package ixdar.graphics.render.model;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;

/**
 * GL_LINES vertices drawn on a surface, filled front to back: per endpoint its xyz, then the
 * normals of the two faces the segment lies between, or the one face it crosses twice over. The
 * line shader drops a fragment when both faces turn away from the camera.
 */
public final class LineSet {

    public static final int FLOATS_PER_VERTEX = 9;
    public static final VertexLayout LAYOUT =
            new VertexLayout(new int[] { 0, 1, 2 }, new int[] { 3, 3, 3 });

    public final float[] vertices;
    public int cursor;

    public final Vector3f start = new Vector3f();
    public final Vector3f end = new Vector3f();
    public final Vector3f normalA = new Vector3f();
    public final Vector3f normalB = new Vector3f();

    /**
     * An empty set.
     *
     * @param segmentCount segments the set will hold
     */
    public LineSet(int segmentCount) {
        this.vertices = new float[segmentCount * 2 * FLOATS_PER_VERTEX];
    }

    /**
     * Append one mesh edge, carrying the normals of the faces on its two sides; a boundary edge
     * carries its one face twice.
     *
     * @param mesh   mesh the edge belongs to
     * @param edgeId edge to append
     */
    public void edge(MeshTopology mesh, int edgeId) {
        int halfEdge = mesh.edgeHalfEdge(edgeId);
        mesh.vertexPosition(mesh.halfEdgeVertex(halfEdge), start);
        mesh.vertexPosition(mesh.halfEdgeEndVertex(halfEdge), end);
        int faceA = mesh.edgeFace(edgeId, 0);
        int faceB = mesh.edgeFace(edgeId, 1);
        mesh.faceNormal(faceA == MeshTopology.NONE ? faceB : faceA, normalA);
        mesh.faceNormal(faceB == MeshTopology.NONE ? faceA : faceB, normalB);
        segment(start, end, normalA, normalB);
    }

    /**
     * Append the step between two consecutive vertices of a path: their shared edge when there is
     * one, else the segment across the face holding both, else the segment carrying one face of
     * each end.
     *
     * @param mesh       mesh the path walks
     * @param fromVertex vertex id the step leaves
     * @param toVertex   vertex id the step reaches
     */
    public void vertexStep(MeshTopology mesh, int fromVertex, int toVertex) {
        int edgeId = mesh.edgeBetween(fromVertex, toVertex);
        if (edgeId != MeshTopology.NONE) {
            edge(mesh, edgeId);
            return;
        }
        mesh.vertexPosition(fromVertex, start);
        mesh.vertexPosition(toVertex, end);
        int fromFace = mesh.vertexFaceCount(fromVertex) > 0 ? mesh.vertexFaceAt(fromVertex, 0)
                : MeshTopology.NONE;
        int toFace = mesh.vertexFaceCount(toVertex) > 0 ? mesh.vertexFaceAt(toVertex, 0)
                : fromFace;
        for (int adjacency = 0; adjacency < mesh.vertexFaceCount(fromVertex); adjacency++) {
            int faceId = mesh.vertexFaceAt(fromVertex, adjacency);
            for (int corner = 0; corner < mesh.faceVertexCount(faceId); corner++) {
                if (mesh.faceVertexAt(faceId, corner) == toVertex) {
                    fromFace = faceId;
                    toFace = faceId;
                }
            }
        }
        if (toFace == MeshTopology.NONE) {
            normalA.zero();
            normalB.zero();
        } else {
            mesh.faceNormal(fromFace == MeshTopology.NONE ? toFace : fromFace, normalA);
            mesh.faceNormal(toFace, normalB);
        }
        segment(start, end, normalA, normalB);
    }

    /**
     * Append the step between two consecutive points of a path traced across the surface, each on
     * a vertex, an edge or inside a face, carrying the faces both points bound: two when the step
     * runs along an edge, else the one face it crosses. Two vertices take {@link #vertexStep}.
     *
     * @param mesh       mesh the path walks
     * @param packedXyz  packed xyz of the path's points, each on the surface
     * @param vertexId   vertex id per point, or -1 off a vertex
     * @param edgeId     edge id per point, or -1 off an edge
     * @param faceId     face id per point inside a face, or -1 elsewhere
     * @param fromPoint  index of the point the step leaves
     * @param toPoint    index of the point the step reaches
     */
    public void pathStep(MeshTopology mesh, float[] packedXyz, int[] vertexId, int[] edgeId,
            int[] faceId, int fromPoint, int toPoint) {
        int fromVertex = vertexId[fromPoint];
        int toVertex = vertexId[toPoint];
        if (fromVertex >= 0 && toVertex >= 0) {
            vertexStep(mesh, fromVertex, toVertex);
            return;
        }
        start.set(packedXyz[3 * fromPoint], packedXyz[3 * fromPoint + 1],
                packedXyz[3 * fromPoint + 2]);
        end.set(packedXyz[3 * toPoint], packedXyz[3 * toPoint + 1], packedXyz[3 * toPoint + 2]);
        int fromInside = faceId[fromPoint];
        int toInside = faceId[toPoint];
        int fromFaces = fromVertex >= 0 ? mesh.vertexFaceCount(fromVertex)
                : fromInside >= 0 ? 1 : 2;
        int toFaces = toVertex >= 0 ? mesh.vertexFaceCount(toVertex) : toInside >= 0 ? 1 : 2;
        int firstFace = MeshTopology.NONE;
        int secondFace = MeshTopology.NONE;
        for (int fromSlot = 0; fromSlot < fromFaces; fromSlot++) {
            int face = fromVertex >= 0 ? mesh.vertexFaceAt(fromVertex, fromSlot)
                    : fromInside >= 0 ? fromInside
                    : mesh.edgeFace(edgeId[fromPoint], fromSlot);
            for (int toSlot = 0; face != MeshTopology.NONE && toSlot < toFaces; toSlot++) {
                int other = toVertex >= 0 ? mesh.vertexFaceAt(toVertex, toSlot)
                        : toInside >= 0 ? toInside
                        : mesh.edgeFace(edgeId[toPoint], toSlot);
                if (other == face && face != firstFace) {
                    secondFace = firstFace == MeshTopology.NONE ? secondFace : face;
                    firstFace = firstFace == MeshTopology.NONE ? face : firstFace;
                }
            }
        }
        if (firstFace == MeshTopology.NONE) {
            normalA.zero();
            normalB.zero();
        } else {
            mesh.faceNormal(firstFace, normalA);
            mesh.faceNormal(secondFace == MeshTopology.NONE ? firstFace : secondFace, normalB);
        }
        segment(start, end, normalA, normalB);
    }

    /**
     * Append one segment with the normals of the surface it lies on.
     *
     * @param segmentStart first endpoint
     * @param segmentEnd   second endpoint
     * @param surfaceA     normal of the first face the segment lies on
     * @param surfaceB     normal of the second face, or the first again
     */
    public void segment(Vector3f segmentStart, Vector3f segmentEnd, Vector3f surfaceA,
            Vector3f surfaceB) {
        for (Vector3f point : new Vector3f[] { segmentStart, segmentEnd }) {
            vertices[cursor] = point.x;
            vertices[cursor + 1] = point.y;
            vertices[cursor + 2] = point.z;
            vertices[cursor + 3] = surfaceA.x;
            vertices[cursor + 4] = surfaceA.y;
            vertices[cursor + 5] = surfaceA.z;
            vertices[cursor + 6] = surfaceB.x;
            vertices[cursor + 7] = surfaceB.y;
            vertices[cursor + 8] = surfaceB.z;
            cursor += FLOATS_PER_VERTEX;
        }
    }

    /**
     * Endpoints appended so far.
     *
     * @return the count
     */
    public int vertexCount() {
        return cursor / FLOATS_PER_VERTEX;
    }
}
