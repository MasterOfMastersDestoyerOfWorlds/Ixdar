package ixdar.graphics.render.model;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.TriangleBvh;
import ixdar.geometry.mesh.data.TriangleGeometry;

/**
 * Finds the faces of a surface that a world-space line lies on, for lines handed over as bare
 * coordinates: a ring or a diagnostic path traced across the surface carries no face ids, yet the
 * line shader needs the normals of the surface under every segment.
 */
public final class SurfaceFaceLocator {

    public static final float ON_FACE_DISTANCE_PER_BOUNDING_DIAGONAL = 1e-4f;

    public final MeshTopology mesh;
    public final TriangleBvh triangles = new TriangleBvh();
    public final float onSurfaceDistance;
    public int[] nodeStack = new int[0];

    public final Vector3f midpoint = new Vector3f();
    public final Vector3f cornerA = new Vector3f();
    public final Vector3f edgeB = new Vector3f();
    public final Vector3f edgeC = new Vector3f();
    public final Vector3f toPoint = new Vector3f();
    public final Vector3f normal = new Vector3f();

    /**
     * Index every face of {@code mesh}, fan-triangulated, in a bounding-volume hierarchy.
     *
     * @param mesh surface the located lines lie on
     */
    public SurfaceFaceLocator(MeshTopology mesh) {
        this.mesh = mesh;
        int triangleCount = 0;
        for (int face = 0; face < mesh.faceCount(); face++) {
            triangleCount += Math.max(0, mesh.faceVertexCount(mesh.faceIdAt(face)) - 2);
        }
        int maxVertexId = 0;
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            maxVertexId = Math.max(maxVertexId, mesh.vertexIdAt(vertex));
        }
        float[] positions = new float[(maxVertexId + 1) * TriangleGeometry.COMPONENTS];
        Vector3f position = new Vector3f();
        Vector3f low = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f high = new Vector3f(Float.NEGATIVE_INFINITY);
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            int vertexId = mesh.vertexIdAt(vertex);
            mesh.vertexPosition(vertexId, position);
            positions[vertexId * TriangleGeometry.COMPONENTS] = position.x;
            positions[vertexId * TriangleGeometry.COMPONENTS + 1] = position.y;
            positions[vertexId * TriangleGeometry.COMPONENTS + 2] = position.z;
            low.min(position);
            high.max(position);
        }
        int[] corners = new int[triangleCount * TriangleBvh.TRIANGLE_CORNERS];
        int[] faceOfTriangle = new int[triangleCount];
        int triangle = 0;
        for (int face = 0; face < mesh.faceCount(); face++) {
            int faceId = mesh.faceIdAt(face);
            for (int fan = 2; fan < mesh.faceVertexCount(faceId); fan++) {
                corners[triangle * TriangleBvh.TRIANGLE_CORNERS] = mesh.faceVertexAt(faceId, 0);
                corners[triangle * TriangleBvh.TRIANGLE_CORNERS + 1] =
                        mesh.faceVertexAt(faceId, fan - 1);
                corners[triangle * TriangleBvh.TRIANGLE_CORNERS + 2] =
                        mesh.faceVertexAt(faceId, fan);
                faceOfTriangle[triangle++] = faceId;
            }
        }
        triangles.positions = positions;
        triangles.triangleCorners = corners;
        triangles.triangleGroup = faceOfTriangle;
        triangles.triangleCount = triangleCount;
        triangles.build();
        nodeStack = new int[Math.max(1, triangles.nodeCount)];
        onSurfaceDistance = triangleCount == 0 ? 0f : high.distance(low)
                * ON_FACE_DISTANCE_PER_BOUNDING_DIAGONAL;
    }

    /**
     * Append segments lying on the surface, each carrying the normals of the faces under its
     * midpoint: two faces when it runs along an edge, else the one face it crosses, twice. A
     * segment off the surface gets zero normals, which the line shader never discards.
     *
     * @param segmentEndpoints packed xyz, two endpoints per segment
     * @param lines            receives one segment per endpoint pair
     */
    public void appendSegments(float[] segmentEndpoints, LineSet lines) {
        int stride = 2 * TriangleGeometry.COMPONENTS;
        int[] corners = triangles.triangleCorners;
        float[] positions = triangles.positions;
        for (int base = 0; base + stride <= segmentEndpoints.length; base += stride) {
            int far = base + TriangleGeometry.COMPONENTS;
            lines.start.set(segmentEndpoints[base], segmentEndpoints[base + 1],
                    segmentEndpoints[base + 2]);
            lines.end.set(segmentEndpoints[far], segmentEndpoints[far + 1],
                    segmentEndpoints[far + 2]);
            midpoint.set(lines.start).add(lines.end).mul(0.5f);
            int nearestFace = MeshTopology.NONE;
            int secondFace = MeshTopology.NONE;
            float nearestDistance = onSurfaceDistance;
            int top = 0;
            if (triangles.nodeCount > 0) {
                nodeStack[top++] = 0;
            }
            while (top > 0) {
                int node = nodeStack[--top];
                int box = node * TriangleGeometry.COMPONENTS;
                if (midpoint.x < triangles.nodeMin[box] - onSurfaceDistance
                        || midpoint.y < triangles.nodeMin[box + 1] - onSurfaceDistance
                        || midpoint.z < triangles.nodeMin[box + 2] - onSurfaceDistance
                        || midpoint.x > triangles.nodeMax[box] + onSurfaceDistance
                        || midpoint.y > triangles.nodeMax[box + 1] + onSurfaceDistance
                        || midpoint.z > triangles.nodeMax[box + 2] + onSurfaceDistance) {
                    continue;
                }
                int left = triangles.nodeLeftChild[node];
                if (left >= 0) {
                    nodeStack[top++] = left;
                    nodeStack[top++] = left + 1;
                    continue;
                }
                int end = triangles.nodeTriangleStart[node] + triangles.nodeTriangleCount[node];
                for (int index = triangles.nodeTriangleStart[node]; index < end; index++) {
                    int triangle = triangles.triangleOrder[index];
                    int first = triangle * TriangleBvh.TRIANGLE_CORNERS;
                    int a = corners[first] * TriangleGeometry.COMPONENTS;
                    int b = corners[first + 1] * TriangleGeometry.COMPONENTS;
                    int c = corners[first + 2] * TriangleGeometry.COMPONENTS;
                    cornerA.set(positions[a], positions[a + 1], positions[a + 2]);
                    edgeB.set(positions[b], positions[b + 1], positions[b + 2]).sub(cornerA);
                    edgeC.set(positions[c], positions[c + 1], positions[c + 2]).sub(cornerA);
                    toPoint.set(midpoint).sub(cornerA);
                    edgeB.cross(edgeC, normal);
                    float doubleArea = normal.length();
                    if (doubleArea == 0f) {
                        continue;
                    }
                    float planeDistance = Math.abs(toPoint.dot(normal)) / doubleArea;
                    // Barycentric weights of the midpoint's projection onto the triangle's plane,
                    // with a slack of the on-surface distance measured across the triangle.
                    float bb = edgeB.dot(edgeB);
                    float bc = edgeB.dot(edgeC);
                    float cc = edgeC.dot(edgeC);
                    float pb = toPoint.dot(edgeB);
                    float pc = toPoint.dot(edgeC);
                    float determinant = bb * cc - bc * bc;
                    float weightB = (cc * pb - bc * pc) / determinant;
                    float weightC = (bb * pc - bc * pb) / determinant;
                    float slack = onSurfaceDistance * (float) Math.sqrt(Math.max(bb, cc))
                            / doubleArea;
                    if (planeDistance > onSurfaceDistance || weightB < -slack
                            || weightC < -slack || weightB + weightC > 1f + slack) {
                        continue;
                    }
                    int faceId = triangles.triangleGroup[triangle];
                    if (faceId == nearestFace || faceId == secondFace) {
                        continue;
                    }
                    if (nearestFace == MeshTopology.NONE || planeDistance < nearestDistance) {
                        secondFace = nearestFace;
                        nearestFace = faceId;
                        nearestDistance = planeDistance;
                    } else if (secondFace == MeshTopology.NONE) {
                        secondFace = faceId;
                    }
                }
            }
            if (nearestFace == MeshTopology.NONE) {
                lines.normalA.zero();
                lines.normalB.zero();
            } else {
                mesh.faceNormal(nearestFace, lines.normalA);
                mesh.faceNormal(secondFace == MeshTopology.NONE ? nearestFace : secondFace,
                        lines.normalB);
            }
            lines.segment(lines.start, lines.end, lines.normalA, lines.normalB);
        }
    }
}
