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
