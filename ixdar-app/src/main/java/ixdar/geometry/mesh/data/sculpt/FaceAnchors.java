package ixdar.geometry.mesh.data.sculpt;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.paths.query.NearestVertex;
import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * Points held on a fixed mesh as a face plus barycentric coordinates in one triangle of its fan
 * (corners 0, k + 1, k + 2), anchored to the nearest surface point and evaluated back.
 */
public final class FaceAnchors {

    public static final int FIELDS = 4;

    /** The mesh anchors are held on. */
    public final MeshTopology mesh;

    /** Vertex index the search starts from. */
    public final NearestVertex nearestVertex;

    /** Distance from the last {@link #anchor}ed point to its anchor. */
    public float lastDistance;

    /**
     * Index a mesh for anchoring.
     *
     * @param mesh the mesh anchors are held on, never edited afterwards
     */
    public FaceAnchors(MeshTopology mesh) {
        this.mesh = mesh;
        this.nearestVertex = NearestVertex.over(mesh);
    }

    /**
     * Anchor a world point to the nearest point of the faces within two rings of its nearest
     * vertex: of each fan triangle, the nearest point (Ericson, Real-Time Collision Detection, 5.1.5).
     *
     * @param x      point x
     * @param y      point y
     * @param z      point z
     * @param anchor receives {@link #FIELDS} floats at {@code at}: face id, fan triangle, u, v
     * @param at     where in {@code anchor} to write
     * @return the face id, or -1 for a mesh with no vertex
     */
    public int anchor(float x, float y, float z, float[] anchor, int at) {
        int start = nearestVertex.nearest(x, y, z, Float.POSITIVE_INFINITY);
        if (start < 0) {
            return -1;
        }
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        Vector3f c = new Vector3f();
        float best = Float.POSITIVE_INFINITY;
        int ring = mesh.vertexEdgeCount(start);
        for (int around = -1; around < ring; around++) {
            int vertexId = around < 0 ? start : mesh.edgeOtherVertex(mesh.vertexEdgeAt(start, around), start);
            for (int faceIndex = 0; faceIndex < mesh.vertexFaceCount(vertexId); faceIndex++) {
                int faceId = mesh.vertexFaceAt(vertexId, faceIndex);
                int corners = mesh.faceVertexCount(faceId);
                mesh.vertexPosition(mesh.faceVertexAt(faceId, 0), a);
                for (int fan = 0; fan + 2 < corners; fan++) {
                    mesh.vertexPosition(mesh.faceVertexAt(faceId, fan + 1), b).sub(a);
                    mesh.vertexPosition(mesh.faceVertexAt(faceId, fan + 2), c).sub(a);
                    float apX = x - a.x;
                    float apY = y - a.y;
                    float apZ = z - a.z;
                    float d1 = b.x * apX + b.y * apY + b.z * apZ;
                    float d2 = c.x * apX + c.y * apY + c.z * apZ;
                    float bb = b.lengthSquared();
                    float bc = b.dot(c);
                    float cc = c.lengthSquared();
                    // d3..d6 are the dot products from the second and third corners.
                    float d3 = d1 - bb;
                    float d4 = d2 - bc;
                    float d5 = d1 - bc;
                    float d6 = d2 - cc;
                    float vc = d1 * d4 - d3 * d2;
                    float vb = d5 * d2 - d1 * d6;
                    float va = d3 * d6 - d5 * d4;
                    float u;
                    float v;
                    if (d1 <= 0f && d2 <= 0f) {
                        u = 0f;
                        v = 0f;
                    } else if (d3 >= 0f && d4 <= d3) {
                        u = 1f;
                        v = 0f;
                    } else if (d6 >= 0f && d5 <= d6) {
                        u = 0f;
                        v = 1f;
                    } else if (vc <= 0f && d1 >= 0f && d3 <= 0f) {
                        u = d1 / (d1 - d3);
                        v = 0f;
                    } else if (vb <= 0f && d2 >= 0f && d6 <= 0f) {
                        u = 0f;
                        v = d2 / (d2 - d6);
                    } else if (va <= 0f && d4 - d3 >= 0f && d5 - d6 >= 0f) {
                        v = (d4 - d3) / ((d4 - d3) + (d5 - d6));
                        u = 1f - v;
                    } else {
                        float denominator = 1f / (va + vb + vc);
                        u = vb * denominator;
                        v = vc * denominator;
                    }
                    float offX = a.x + u * b.x + v * c.x - x;
                    float offY = a.y + u * b.y + v * c.y - y;
                    float offZ = a.z + u * b.z + v * c.z - z;
                    float distance = offX * offX + offY * offY + offZ * offZ;
                    if (distance < best) {
                        best = distance;
                        anchor[at] = faceId;
                        anchor[at + 1] = fan;
                        anchor[at + 2] = u;
                        anchor[at + 3] = v;
                    }
                }
            }
        }
        lastDistance = (float) Math.sqrt(best);
        return (int) anchor[at];
    }

    /**
     * Where an anchor lies on a mesh: this one, or another sharing its faces and corner order.
     *
     * @param surface the mesh to evaluate on
     * @param anchor  anchors, {@link #FIELDS} floats each
     * @param at      the anchor's offset in {@code anchor}
     * @param dest    receives the position
     * @return {@code dest}
     */
    public static Vector3f position(MeshTopology surface, float[] anchor, int at, Vector3f dest) {
        int faceId = (int) anchor[at];
        int fan = (int) anchor[at + 1];
        Vector3f corner = new Vector3f();
        surface.vertexPosition(surface.faceVertexAt(faceId, 0), dest).mul(1f - anchor[at + 2] - anchor[at + 3]);
        dest.fma(anchor[at + 2], surface.vertexPosition(surface.faceVertexAt(faceId, fan + 1), corner));
        return dest.fma(anchor[at + 3], surface.vertexPosition(surface.faceVertexAt(faceId, fan + 2), corner));
    }
}
