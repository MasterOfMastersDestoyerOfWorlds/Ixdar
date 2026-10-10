package ixdar.geometry.mesh.data.paths;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;

/**
 * A surface's intrinsic metric and connection: its unflipped signpost intrinsic triangulation
 * (Sharp, Soliman &amp; Crane 2019) with the source-mesh mapping. Never written after {@link #of}.
 *
 * <p>
 * Consumers share one; a geodesic flips its own {@link IntrinsicTriangulation} over it. Half-edge
 * {@code h} sits on edge {@code h >> 1} and twins {@code h ^ 1}.
 */
public final class SurfaceMetric {

    public static final int TRIANGLE_SIDES = 3;

    /** Mesh the metric was measured on; its geometry fixes the edge lengths. */
    public MeshTopology sourceMesh;

    /** Mesh vertex id per dense intrinsic vertex index. */
    public int[] sourceVertexId;

    /** Dense intrinsic vertex index per mesh vertex id; -1 for dead ids. */
    public int[] vertexIndexByVertexId;

    /**
     * Mesh edge id per dense intrinsic edge index, or {@link MeshTopology#NONE} for an edge
     * splitting a source polygon. Source edges come first.
     */
    public int[] sourceEdgeId;

    /** Mesh face id per dense intrinsic face index: the polygon each intrinsic triangle lies in. */
    public int[] sourceFaceId;

    /** Dense intrinsic vertex index each half-edge leaves from. */
    public int[] halfEdgeTail;

    /** Next half-edge around the same face, or -1 when the half-edge is exterior. */
    public int[] halfEdgeNext;

    /** Dense intrinsic face index on the half-edge's left, or -1 when it is exterior. */
    public int[] halfEdgeFace;

    /** One bounding half-edge per intrinsic face. */
    public int[] faceHalfEdge;

    /** Intrinsic length per edge index, shared by both of the edge's half-edges. */
    public double[] edgeLength;

    /** Counter-clockwise angular coordinate of each half-edge at its tail vertex. */
    public double[] signpostAngle;

    /** Total corner angle around each vertex; {@code 2*pi} in the interior of a flat region. */
    public double[] vertexAngleSum;

    /** Outgoing half-edge whose signpost angle is zero, per vertex. */
    public int[] vertexReferenceHalfEdge;

    /** Whether the vertex lies on the source mesh boundary. */
    public boolean[] vertexIsBoundary;

    /** Mean Euclidean length of the mesh's own edges, the unit surface tolerances are quoted in. */
    public double meanEdgeLength;

    /** One past the largest mesh vertex id, the length a per-vertex-id buffer needs. */
    public int vertexIdBound;

    /** Grid over the mesh's vertices that lands an authored point on the surface. */
    public NearestVertex nearestVertex;

    private SurfaceMetric() {
    }

    /**
     * Measures a polygon mesh, taking Euclidean edge lengths; each polygon is ear-split into
     * intrinsic triangles on its shortest valid diagonals.
     *
     * @param mesh source mesh; every face needs at least three sides
     * @throws IllegalArgumentException when a face has fewer than three sides or the mesh is not
     *                                  manifold
     * @return the metric, whose connectivity mirrors {@code mesh}
     */
    public static SurfaceMetric of(MeshTopology mesh) {
        SurfaceMetric metric = new SurfaceMetric();
        metric.sourceMesh = mesh;
        metric.buildConnectivity(mesh);
        metric.measureEdges(mesh);
        metric.layOutSignposts();
        double totalEdgeLength = 0.0;
        for (int edge = 0; edge < mesh.edgeCount(); edge++) {
            totalEdgeLength += metric.edgeLength[edge];
        }
        metric.meanEdgeLength = mesh.edgeCount() == 0 ? 0.0 : totalEdgeLength / mesh.edgeCount();
        metric.vertexIdBound = mesh.vertexCount() == 0 ? 0 : metric.vertexIndexByVertexId.length;
        metric.nearestVertex = NearestVertex.over(mesh);
        return metric;
    }

    /**
     * Interior angle at a half-edge's tail, between it and the previous half-edge of its triangle.
     * Beside a zero-length side it is {@code pi/2} ({@code pi/3} when every side collapsed), so a
     * triangle's angles always sum to {@code pi} and vertex angle sums keep Gauss-Bonnet.
     *
     * @param halfEdge interior half-edge whose corner is measured
     * @return the corner angle in radians, in {@code [0, pi]}
     */
    public double cornerAngle(int halfEdge) {
        double adjacent = edgeLength[halfEdge >> 1];
        double other = edgeLength[halfEdgeNext[halfEdgeNext[halfEdge]] >> 1];
        double opposite = edgeLength[halfEdgeNext[halfEdge] >> 1];
        double denominator = 2.0 * adjacent * other;
        if (denominator <= 0.0) {
            return adjacent == other ? Math.PI / TRIANGLE_SIDES : Math.PI / 2.0;
        }
        double cosine = (adjacent * adjacent + other * other - opposite * opposite) / denominator;
        return Math.acos(Math.max(-1.0, Math.min(1.0, cosine)));
    }

    private void buildConnectivity(MeshTopology mesh) {
        int maxVertexId = 0;
        for (int index = 0; index < mesh.vertexCount(); index++) {
            maxVertexId = Math.max(maxVertexId, mesh.vertexIdAt(index));
        }
        int vertexCount = mesh.vertexCount();
        sourceVertexId = new int[vertexCount];
        vertexIndexByVertexId = new int[maxVertexId + 1];
        Arrays.fill(vertexIndexByVertexId, -1);
        for (int index = 0; index < vertexCount; index++) {
            int vertexId = mesh.vertexIdAt(index);
            sourceVertexId[index] = vertexId;
            vertexIndexByVertexId[vertexId] = index;
        }

        int sourceEdgeCount = mesh.edgeCount();
        int faceCount = 0;
        int splitEdgeCount = 0;
        for (int index = 0; index < mesh.faceCount(); index++) {
            int faceId = mesh.faceIdAt(index);
            int sides = mesh.faceHalfEdgeCount(faceId);
            if (sides < TRIANGLE_SIDES) {
                throw new IllegalArgumentException("intrinsic triangulation needs polygons, face "
                        + faceId + " has " + sides + " sides");
            }
            splitEdgeCount += sides - TRIANGLE_SIDES;
            faceCount += sides - 2;
        }
        int edgeCount = sourceEdgeCount + splitEdgeCount;
        int halfEdgeCount = 2 * edgeCount;
        sourceEdgeId = new int[edgeCount];
        edgeLength = new double[edgeCount];
        halfEdgeTail = new int[halfEdgeCount];
        halfEdgeNext = new int[halfEdgeCount];
        halfEdgeFace = new int[halfEdgeCount];
        signpostAngle = new double[halfEdgeCount];
        Arrays.fill(halfEdgeNext, -1);
        Arrays.fill(halfEdgeFace, -1);

        int maxHalfEdgeId = 0;
        for (int index = 0; index < mesh.halfEdgeCount(); index++) {
            maxHalfEdgeId = Math.max(maxHalfEdgeId, mesh.halfEdgeIdAt(index));
        }
        int[] halfEdgeIndexByHalfEdgeId = new int[maxHalfEdgeId + 1];
        Arrays.fill(halfEdgeIndexByHalfEdgeId, -1);
        Arrays.fill(sourceEdgeId, sourceEdgeCount, edgeCount, MeshTopology.NONE);
        for (int index = 0; index < sourceEdgeCount; index++) {
            int edgeId = mesh.edgeIdAt(index);
            sourceEdgeId[index] = edgeId;
            int frontId = mesh.edgeHalfEdge(edgeId);
            int backId = mesh.halfEdgeTwin(frontId);
            int front = index << 1;
            int back = front | 1;
            halfEdgeIndexByHalfEdgeId[frontId] = front;
            if (backId >= 0) {
                halfEdgeIndexByHalfEdgeId[backId] = back;
            }
            halfEdgeTail[front] = vertexIndexByVertexId[mesh.halfEdgeVertex(frontId)];
            halfEdgeTail[back] = vertexIndexByVertexId[mesh.halfEdgeEndVertex(frontId)];
        }

        sourceFaceId = new int[faceCount];
        faceHalfEdge = new int[faceCount];
        int nextSplitEdge = sourceEdgeCount;
        int nextFreeFace = 0;
        Vector3f cornerPosition = new Vector3f();
        for (int index = 0; index < mesh.faceCount(); index++) {
            int faceId = mesh.faceIdAt(index);
            int[] boundary = new int[mesh.faceHalfEdgeCount(faceId)];
            for (int side = 0; side < boundary.length; side++) {
                boundary[side] = halfEdgeIndexByHalfEdgeId[mesh.faceHalfEdgeAt(faceId, side)];
                if (boundary[side] < 0) {
                    throw new IllegalArgumentException("face " + faceId
                            + " uses a half-edge its edge does not pair; the mesh is not manifold");
                }
            }
            // Ear-clip a polygon, always cutting the shortest diagonal of an ear valid in its
            // Newell plane, so a convex quad splits on its shorter diagonal and a concave polygon
            // never folds over itself; the last three sides close the last triangle.
            int remaining = boundary.length;
            int[] ring = boundary;
            if (remaining > TRIANGLE_SIDES) {
                double[] xyz = new double[TRIANGLE_SIDES * remaining];
                double[] normal = new double[TRIANGLE_SIDES];
                int[] cornerSlot = new int[remaining];
                for (int corner = 0; corner < remaining; corner++) {
                    mesh.vertexPosition(sourceVertexId[halfEdgeTail[ring[corner]]], cornerPosition);
                    xyz[TRIANGLE_SIDES * corner] = cornerPosition.x;
                    xyz[TRIANGLE_SIDES * corner + 1] = cornerPosition.y;
                    xyz[TRIANGLE_SIDES * corner + 2] = cornerPosition.z;
                    cornerSlot[corner] = corner;
                }
                for (int corner = 0; corner < remaining; corner++) {
                    int here = TRIANGLE_SIDES * corner;
                    int there = TRIANGLE_SIDES * ((corner + 1) % remaining);
                    normal[0] += (xyz[here + 1] - xyz[there + 1]) * (xyz[here + 2] + xyz[there + 2]);
                    normal[1] += (xyz[here + 2] - xyz[there + 2]) * (xyz[here] + xyz[there]);
                    normal[2] += (xyz[here] - xyz[there]) * (xyz[here + 1] + xyz[there + 1]);
                }
                while (remaining > TRIANGLE_SIDES) {
                    int bestEar = 0;
                    boolean bestValid = false;
                    double bestLength = Double.POSITIVE_INFINITY;
                    for (int ear = 0; ear < remaining; ear++) {
                        int previous = cornerSlot[(ear + remaining - 1) % remaining];
                        int middle = cornerSlot[ear];
                        int following = cornerSlot[(ear + 1) % remaining];
                        boolean valid = turn(xyz, previous, middle, following, normal) > 0.0;
                        for (int other = 0; other < remaining && valid; other++) {
                            int slot = cornerSlot[other];
                            valid = slot == previous || slot == middle || slot == following
                                    || turn(xyz, previous, middle, slot, normal) < 0.0
                                    || turn(xyz, middle, following, slot, normal) < 0.0
                                    || turn(xyz, following, previous, slot, normal) < 0.0;
                        }
                        double dx = xyz[TRIANGLE_SIDES * previous]
                                - xyz[TRIANGLE_SIDES * following];
                        double dy = xyz[TRIANGLE_SIDES * previous + 1]
                                - xyz[TRIANGLE_SIDES * following + 1];
                        double dz = xyz[TRIANGLE_SIDES * previous + 2]
                                - xyz[TRIANGLE_SIDES * following + 2];
                        double length = dx * dx + dy * dy + dz * dz;
                        if (valid && !bestValid || valid == bestValid && length < bestLength) {
                            bestEar = ear;
                            bestValid = valid;
                            bestLength = length;
                        }
                    }
                    int incoming = (bestEar + remaining - 1) % remaining;
                    int inEar = nextSplitEdge++ << 1;
                    int onPolygon = inEar | 1;
                    halfEdgeTail[inEar] = halfEdgeTail[ring[(bestEar + 1) % remaining]];
                    halfEdgeTail[onPolygon] = halfEdgeTail[ring[incoming]];
                    linkTriangle(ring[incoming], ring[bestEar], inEar, nextFreeFace++, faceId);
                    ring[incoming] = onPolygon;
                    System.arraycopy(ring, bestEar + 1, ring, bestEar, remaining - bestEar - 1);
                    System.arraycopy(cornerSlot, bestEar + 1, cornerSlot, bestEar,
                            remaining - bestEar - 1);
                    remaining--;
                }
            }
            linkTriangle(ring[0], ring[1], ring[2], nextFreeFace++, faceId);
        }

        vertexIsBoundary = new boolean[vertexCount];
        vertexReferenceHalfEdge = new int[vertexCount];
        Arrays.fill(vertexReferenceHalfEdge, -1);
        for (int halfEdge = 0; halfEdge < halfEdgeCount; halfEdge++) {
            if (halfEdgeNext[halfEdge] < 0) {
                vertexIsBoundary[halfEdgeTail[halfEdge]] = true;
                vertexIsBoundary[halfEdgeTail[halfEdge ^ 1]] = true;
            }
        }
        for (int halfEdge = halfEdgeCount - 1; halfEdge >= 0; halfEdge--) {
            int tail = halfEdgeTail[halfEdge];
            if (halfEdgeNext[halfEdge] < 0) {
                continue;
            }
            boolean startsBoundaryFan = halfEdgeNext[halfEdge ^ 1] < 0;
            if (!vertexIsBoundary[tail] || startsBoundaryFan) {
                vertexReferenceHalfEdge[tail] = halfEdge;
            }
        }
    }

    /**
     * Signed turn at {@code middle} from {@code from} to {@code to}, positive when it is
     * counter-clockwise about the polygon normal.
     */
    private static double turn(double[] xyz, int from, int middle, int to, double[] normal) {
        double inX = xyz[TRIANGLE_SIDES * middle] - xyz[TRIANGLE_SIDES * from];
        double inY = xyz[TRIANGLE_SIDES * middle + 1] - xyz[TRIANGLE_SIDES * from + 1];
        double inZ = xyz[TRIANGLE_SIDES * middle + 2] - xyz[TRIANGLE_SIDES * from + 2];
        double outX = xyz[TRIANGLE_SIDES * to] - xyz[TRIANGLE_SIDES * middle];
        double outY = xyz[TRIANGLE_SIDES * to + 1] - xyz[TRIANGLE_SIDES * middle + 1];
        double outZ = xyz[TRIANGLE_SIDES * to + 2] - xyz[TRIANGLE_SIDES * middle + 2];
        return (inY * outZ - inZ * outY) * normal[0] + (inZ * outX - inX * outZ) * normal[1]
                + (inX * outY - inY * outX) * normal[2];
    }

    private void linkTriangle(int first, int second, int third, int face, int faceId) {
        halfEdgeNext[first] = second;
        halfEdgeNext[second] = third;
        halfEdgeNext[third] = first;
        halfEdgeFace[first] = face;
        halfEdgeFace[second] = face;
        halfEdgeFace[third] = face;
        faceHalfEdge[face] = first;
        sourceFaceId[face] = faceId;
    }

    private void measureEdges(MeshTopology mesh) {
        Vector3f tailPosition = new Vector3f();
        Vector3f headPosition = new Vector3f();
        for (int edge = 0; edge < edgeLength.length; edge++) {
            int front = edge << 1;
            mesh.vertexPosition(sourceVertexId[halfEdgeTail[front]], tailPosition);
            mesh.vertexPosition(sourceVertexId[halfEdgeTail[front | 1]], headPosition);
            edgeLength[edge] = tailPosition.distance(headPosition);
        }
    }

    /**
     * Gives every outgoing half-edge its angular coordinate: corner angles summed
     * counter-clockwise from the vertex's reference half-edge, each corner measured once.
     */
    private void layOutSignposts() {
        int vertexCount = sourceVertexId.length;
        vertexAngleSum = new double[vertexCount];
        double[] cornerAngles = new double[halfEdgeTail.length];
        for (int halfEdge = 0; halfEdge < halfEdgeTail.length; halfEdge++) {
            if (halfEdgeNext[halfEdge] >= 0) {
                cornerAngles[halfEdge] = cornerAngle(halfEdge);
                vertexAngleSum[halfEdgeTail[halfEdge]] += cornerAngles[halfEdge];
            }
        }
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int first = vertexReferenceHalfEdge[vertex];
            if (first < 0) {
                continue;
            }
            double running = 0.0;
            int current = first;
            do {
                signpostAngle[current] = running;
                if (halfEdgeNext[current] < 0) {
                    break;
                }
                running += cornerAngles[current];
                current = halfEdgeNext[halfEdgeNext[current]] ^ 1;
            } while (current != first);
        }
    }
}
