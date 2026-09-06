package ixdar.geometry.mesh.data.paths;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;

/**
 * Signpost intrinsic triangulation over a triangle mesh (Sharp, Soliman &amp; Crane 2019): edge
 * lengths, angular coordinates and vertex angle sums, kept current under edge flip.
 *
 * <p>
 * Half-edge {@code h} sits on edge {@code h >> 1} and twins {@code h ^ 1}; a flip preserves both,
 * so intrinsic vertices stay source vertices.
 */
public final class IntrinsicTriangulation {

    /** Angular slack, in radians, within which a wedge counts as straight. */
    public static final double ANGLE_EPSILON = 1e-5;

    /** Relative signed-area floor a flip's two new triangles must clear. */
    public static final double TRIANGLE_TEST_EPSILON = 1e-6;

    /** Sides of every face the intrinsic triangulation accepts. */
    public static final int TRIANGLE_SIDES = 3;

    /** Mesh the triangulation was built over; its geometry fixes the initial edge lengths. */
    public MeshTopology sourceMesh;

    /** Mesh vertex id per dense intrinsic vertex index. */
    public int[] sourceVertexId;

    /** Dense intrinsic vertex index per mesh vertex id; -1 for dead ids. */
    public int[] vertexIndexByVertexId;

    /** Mesh edge id per dense intrinsic edge index, as the edge stood before any flip. */
    public int[] sourceEdgeId;

    /** Mesh face id per dense intrinsic face index, as the face stood before any flip. */
    public int[] sourceFaceId;

    /** Whether an intrinsic edge is still the source-mesh edge it started as. */
    public boolean[] edgeIsOriginal;

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

    /** Number of intrinsic vertices. */
    public int vertexCount;

    /** Number of intrinsic edges; flips preserve edge identity, so this never changes. */
    public int edgeCount;

    /** Number of intrinsic half-edges, always {@code 2 * edgeCount}. */
    public int halfEdgeCount;

    /** Number of intrinsic faces. */
    public int faceCount;

    private final double[] diamondX = new double[4];
    private final double[] diamondY = new double[4];

    private IntrinsicTriangulation() {
    }

    /**
     * Builds the signpost structure over a triangle mesh, taking Euclidean edge lengths.
     *
     * @param mesh source mesh; every face must be a triangle
     * @throws IllegalArgumentException when a face is not a triangle
     * @return a fresh triangulation whose connectivity mirrors {@code mesh}
     */
    public static IntrinsicTriangulation over(MeshTopology mesh) {
        IntrinsicTriangulation intrinsic = new IntrinsicTriangulation();
        intrinsic.sourceMesh = mesh;
        intrinsic.buildConnectivity(mesh);
        intrinsic.measureEdges(mesh);
        intrinsic.layOutSignposts();
        return intrinsic;
    }

    /**
     * The vertex a half-edge points at, which is its twin's tail.
     *
     * @param halfEdge half-edge to query
     * @return dense intrinsic vertex index of the head
     */
    public int halfEdgeHead(int halfEdge) {
        return halfEdgeTail[halfEdge ^ 1];
    }

    /**
     * The previous half-edge around the same triangle.
     *
     * @param halfEdge interior half-edge to query
     * @return the half-edge whose {@code next} is {@code halfEdge}
     */
    public int halfEdgePrevious(int halfEdge) {
        return halfEdgeNext[halfEdgeNext[halfEdge]];
    }

    /**
     * Whether the half-edge bounds a face rather than the outside of a boundary edge.
     *
     * @param halfEdge half-edge to query
     * @return true when the half-edge carries a face
     */
    public boolean isInterior(int halfEdge) {
        return halfEdgeNext[halfEdge] >= 0;
    }

    /**
     * Whether the edge lies on the mesh boundary, which also makes it unflippable.
     *
     * @param edge dense intrinsic edge index
     * @return true when either side of the edge is exterior
     */
    public boolean isBoundaryEdge(int edge) {
        return halfEdgeNext[edge << 1] < 0 || halfEdgeNext[(edge << 1) | 1] < 0;
    }

    /**
     * The next outgoing half-edge counter-clockwise around a half-edge's tail vertex.
     *
     * @param halfEdge interior outgoing half-edge
     * @return the outgoing half-edge one corner counter-clockwise
     */
    public int counterClockwiseNeighbor(int halfEdge) {
        return halfEdgePrevious(halfEdge) ^ 1;
    }

    /**
     * The next outgoing half-edge clockwise around a half-edge's tail vertex.
     *
     * @param halfEdge outgoing half-edge whose twin is interior
     * @return the outgoing half-edge one corner clockwise
     */
    public int clockwiseNeighbor(int halfEdge) {
        return halfEdgeNext[halfEdge ^ 1];
    }

    /**
     * The outgoing half-edge joining two vertices, found by orbiting the first vertex's fan.
     *
     * @param fromVertex dense intrinsic vertex index the half-edge leaves
     * @param toVertex   dense intrinsic vertex index the half-edge points at
     * @return the half-edge index, or -1 when the two vertices share no intrinsic edge
     */
    public int halfEdgeBetween(int fromVertex, int toVertex) {
        int reference = vertexReferenceHalfEdge[fromVertex];
        if (reference < 0) {
            return -1;
        }
        int current = reference;
        do {
            if (halfEdgeHead(current) == toVertex) {
                return current;
            }
            if (halfEdgeNext[current] < 0) {
                break;
            }
            current = counterClockwiseNeighbor(current);
        } while (current != reference);
        return -1;
    }

    /**
     * Interior angle at a half-edge's tail, between it and the previous half-edge of its face.
     *
     * @param halfEdge interior half-edge whose corner is measured
     * @return the corner angle in radians, in {@code [0, pi]}
     */
    public double cornerAngle(int halfEdge) {
        double adjacent = edgeLength[halfEdge >> 1];
        double other = edgeLength[halfEdgePrevious(halfEdge) >> 1];
        double opposite = edgeLength[halfEdgeNext[halfEdge] >> 1];
        double denominator = 2.0 * adjacent * other;
        if (denominator <= 0.0) {
            return 0.0;
        }
        double cosine = (adjacent * adjacent + other * other - opposite * opposite) / denominator;
        return Math.acos(Math.max(-1.0, Math.min(1.0, cosine)));
    }

    /**
     * Angle folded into the vertex's own angular period {@code [0, vertexAngleSum)}.
     *
     * @param vertex dense intrinsic vertex index
     * @param angle  raw angle in radians
     * @return the equivalent angle inside one turn around {@code vertex}
     */
    public double standardizeAngle(int vertex, double angle) {
        double period = vertexAngleSum[vertex];
        if (period <= 0.0) {
            return 0.0;
        }
        double folded = angle % period;
        return folded < 0.0 ? folded + period : folded;
    }

    /**
     * Angles of the two wedges a path turn cuts the vertex into, left side first.
     *
     * <p>
     * A boundary vertex has no wedge on its outside, so that side reports
     * {@link Double#POSITIVE_INFINITY} and is never chosen for shortening.
     *
     * @param incomingHalfEdge half-edge of the path arriving at the middle vertex
     * @param outgoingHalfEdge half-edge of the path leaving the middle vertex
     * @param sideAngles       two-element buffer filled with {left, right}
     */
    public void measureSideAngles(int incomingHalfEdge, int outgoingHalfEdge, double[] sideAngles) {
        int middleVertex = halfEdgeTail[outgoingHalfEdge];
        double period = vertexAngleSum[middleVertex];
        double angleIn = signpostAngle[incomingHalfEdge ^ 1];
        double angleOut = signpostAngle[outgoingHalfEdge];
        boolean boundary = vertexIsBoundary[middleVertex];

        double right;
        if (angleIn < angleOut) {
            right = angleOut - angleIn;
        } else {
            right = boundary ? Double.POSITIVE_INFINITY : (period - angleIn) + angleOut;
        }
        double left;
        if (angleOut < angleIn) {
            left = angleIn - angleOut;
        } else {
            left = boundary ? Double.POSITIVE_INFINITY : (period - angleOut) + angleIn;
        }
        sideAngles[0] = left;
        sideAngles[1] = right;
    }

    /**
     * Flips an intrinsic edge when the two triangles around it lay out as a convex diamond.
     *
     * <p>
     * Refusing an inverted or degenerate result is exactly the {@code beta &lt; pi} condition of
     * Sharp &amp; Crane 2020 §3.
     *
     * @param edge dense intrinsic edge index to flip
     * @return true when the edge was flipped, false when it was left alone
     */
    public boolean flipIfPossible(int edge) {
        if (isBoundaryEdge(edge)) {
            return false;
        }
        int frontHalfEdge = edge << 1;
        int backHalfEdge = frontHalfEdge | 1;
        int frontNext = halfEdgeNext[frontHalfEdge];
        int frontPrevious = halfEdgeNext[frontNext];
        int backNext = halfEdgeNext[backHalfEdge];
        int backPrevious = halfEdgeNext[backNext];
        if (halfEdgeNext[frontPrevious] != frontHalfEdge
                || halfEdgeNext[backPrevious] != backHalfEdge) {
            return false;
        }
        int tailVertex = halfEdgeTail[frontHalfEdge];
        int headVertex = halfEdgeTail[backHalfEdge];
        int frontApex = halfEdgeTail[frontPrevious];
        int backApex = halfEdgeTail[backPrevious];
        if (frontApex == backApex || frontNext == backHalfEdge || backNext == frontHalfEdge) {
            return false;
        }

        layOutDiamond(frontHalfEdge);
        double firstArea = cross(diamondX[1] - diamondX[0], diamondY[1] - diamondY[0],
                diamondX[3] - diamondX[0], diamondY[3] - diamondY[0]);
        double secondArea = cross(diamondX[3] - diamondX[2], diamondY[3] - diamondY[2],
                diamondX[1] - diamondX[2], diamondY[1] - diamondY[2]);
        double areaFloor = TRIANGLE_TEST_EPSILON * (firstArea + secondArea);
        if (firstArea < areaFloor || secondArea < areaFloor) {
            return false;
        }
        double newLength = Math.hypot(diamondX[1] - diamondX[3], diamondY[1] - diamondY[3]);
        if (!Double.isFinite(newLength) || newLength <= 0.0) {
            return false;
        }

        int frontFace = halfEdgeFace[frontHalfEdge];
        int backFace = halfEdgeFace[backHalfEdge];
        halfEdgeNext[frontHalfEdge] = backPrevious;
        halfEdgeNext[backPrevious] = frontNext;
        halfEdgeNext[frontNext] = frontHalfEdge;
        halfEdgeNext[backHalfEdge] = frontPrevious;
        halfEdgeNext[frontPrevious] = backNext;
        halfEdgeNext[backNext] = backHalfEdge;
        halfEdgeTail[frontHalfEdge] = frontApex;
        halfEdgeTail[backHalfEdge] = backApex;
        halfEdgeFace[frontPrevious] = backFace;
        halfEdgeFace[backPrevious] = frontFace;
        faceHalfEdge[frontFace] = frontHalfEdge;
        faceHalfEdge[backFace] = backHalfEdge;
        if (vertexReferenceHalfEdge[tailVertex] == frontHalfEdge) {
            vertexReferenceHalfEdge[tailVertex] = backNext;
        }
        if (vertexReferenceHalfEdge[headVertex] == backHalfEdge) {
            vertexReferenceHalfEdge[headVertex] = frontNext;
        }

        edgeLength[edge] = newLength;
        edgeIsOriginal[edge] = false;
        updateAngleFromClockwiseNeighbor(frontHalfEdge);
        updateAngleFromClockwiseNeighbor(backHalfEdge);
        return true;
    }

    /**
     * Total length of a half-edge chain, used to compare a path against its replacement.
     *
     * @param halfEdges half-edge indices to measure
     * @param count     number of leading entries of {@code halfEdges} to read
     * @return summed intrinsic edge length
     */
    public double chainLength(int[] halfEdges, int count) {
        double total = 0.0;
        for (int index = 0; index < count; index++) {
            total += edgeLength[halfEdges[index] >> 1];
        }
        return total;
    }

    private void buildConnectivity(MeshTopology mesh) {
        int maxVertexId = 0;
        for (int index = 0; index < mesh.vertexCount(); index++) {
            maxVertexId = Math.max(maxVertexId, mesh.vertexIdAt(index));
        }
        vertexCount = mesh.vertexCount();
        sourceVertexId = new int[vertexCount];
        vertexIndexByVertexId = new int[maxVertexId + 1];
        Arrays.fill(vertexIndexByVertexId, -1);
        for (int index = 0; index < vertexCount; index++) {
            int vertexId = mesh.vertexIdAt(index);
            sourceVertexId[index] = vertexId;
            vertexIndexByVertexId[vertexId] = index;
        }

        edgeCount = mesh.edgeCount();
        halfEdgeCount = 2 * edgeCount;
        sourceEdgeId = new int[edgeCount];
        edgeIsOriginal = new boolean[edgeCount];
        edgeLength = new double[edgeCount];
        halfEdgeTail = new int[halfEdgeCount];
        halfEdgeNext = new int[halfEdgeCount];
        halfEdgeFace = new int[halfEdgeCount];
        signpostAngle = new double[halfEdgeCount];
        Arrays.fill(halfEdgeNext, -1);
        Arrays.fill(halfEdgeFace, -1);
        Arrays.fill(edgeIsOriginal, true);

        int maxHalfEdgeId = 0;
        for (int index = 0; index < mesh.halfEdgeCount(); index++) {
            maxHalfEdgeId = Math.max(maxHalfEdgeId, mesh.halfEdgeIdAt(index));
        }
        int[] halfEdgeIndexByHalfEdgeId = new int[maxHalfEdgeId + 1];
        Arrays.fill(halfEdgeIndexByHalfEdgeId, -1);
        for (int index = 0; index < edgeCount; index++) {
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

        faceCount = mesh.faceCount();
        sourceFaceId = new int[faceCount];
        faceHalfEdge = new int[faceCount];
        for (int index = 0; index < faceCount; index++) {
            int faceId = mesh.faceIdAt(index);
            sourceFaceId[index] = faceId;
            if (mesh.faceHalfEdgeCount(faceId) != TRIANGLE_SIDES) {
                throw new IllegalArgumentException("intrinsic triangulation needs triangles, face "
                        + faceId + " has " + mesh.faceHalfEdgeCount(faceId) + " sides");
            }
            int first = halfEdgeIndexByHalfEdgeId[mesh.faceHalfEdgeAt(faceId, 0)];
            int second = halfEdgeIndexByHalfEdgeId[mesh.faceHalfEdgeAt(faceId, 1)];
            int third = halfEdgeIndexByHalfEdgeId[mesh.faceHalfEdgeAt(faceId, 2)];
            if (first < 0 || second < 0 || third < 0) {
                throw new IllegalArgumentException("face " + faceId
                        + " uses a half-edge its edge does not pair; the mesh is not manifold");
            }
            halfEdgeNext[first] = second;
            halfEdgeNext[second] = third;
            halfEdgeNext[third] = first;
            halfEdgeFace[first] = index;
            halfEdgeFace[second] = index;
            halfEdgeFace[third] = index;
            faceHalfEdge[index] = first;
        }

        vertexIsBoundary = new boolean[vertexCount];
        vertexReferenceHalfEdge = new int[vertexCount];
        Arrays.fill(vertexReferenceHalfEdge, -1);
        for (int halfEdge = 0; halfEdge < halfEdgeCount; halfEdge++) {
            int tail = halfEdgeTail[halfEdge];
            if (halfEdgeNext[halfEdge] < 0) {
                vertexIsBoundary[tail] = true;
                vertexIsBoundary[halfEdgeHead(halfEdge)] = true;
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

    private void measureEdges(MeshTopology mesh) {
        Vector3f tailPosition = new Vector3f();
        Vector3f headPosition = new Vector3f();
        for (int edge = 0; edge < edgeCount; edge++) {
            int front = edge << 1;
            mesh.vertexPosition(sourceVertexId[halfEdgeTail[front]], tailPosition);
            mesh.vertexPosition(sourceVertexId[halfEdgeTail[front | 1]], headPosition);
            edgeLength[edge] = tailPosition.distance(headPosition);
        }
    }

    private void layOutSignposts() {
        vertexAngleSum = new double[vertexCount];
        for (int halfEdge = 0; halfEdge < halfEdgeCount; halfEdge++) {
            if (halfEdgeNext[halfEdge] >= 0) {
                vertexAngleSum[halfEdgeTail[halfEdge]] += cornerAngle(halfEdge);
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
                running += cornerAngle(current);
                current = counterClockwiseNeighbor(current);
            } while (current != first);
        }
    }

    private void updateAngleFromClockwiseNeighbor(int halfEdge) {
        int tail = halfEdgeTail[halfEdge];
        if (halfEdgeNext[halfEdge] < 0) {
            signpostAngle[halfEdge] = vertexAngleSum[tail];
            return;
        }
        if (halfEdgeNext[halfEdge ^ 1] < 0) {
            signpostAngle[halfEdge] = 0.0;
            return;
        }
        int clockwise = clockwiseNeighbor(halfEdge);
        signpostAngle[halfEdge] =
                standardizeAngle(tail, signpostAngle[clockwise] + cornerAngle(clockwise));
    }

    /**
     * Lays the two triangles around {@code frontHalfEdge} flat: the half-edge runs from corner 2
     * to corner 0, corner 3 sits at the origin, and edge 3-0 lies along the x axis.
     */
    private void layOutDiamond(int frontHalfEdge) {
        int frontNext = halfEdgeNext[frontHalfEdge];
        int frontPrevious = halfEdgeNext[frontNext];
        int backHalfEdge = frontHalfEdge ^ 1;
        int backNext = halfEdgeNext[backHalfEdge];
        int backPrevious = halfEdgeNext[backNext];

        double lengthZeroOne = edgeLength[frontNext >> 1];
        double lengthOneTwo = edgeLength[frontPrevious >> 1];
        double lengthTwoThree = edgeLength[backNext >> 1];
        double lengthThreeZero = edgeLength[backPrevious >> 1];
        double lengthZeroTwo = edgeLength[frontHalfEdge >> 1];

        diamondX[3] = 0.0;
        diamondY[3] = 0.0;
        diamondX[0] = lengthThreeZero;
        diamondY[0] = 0.0;
        layOutTriangleVertex(3, 0, lengthZeroTwo, lengthTwoThree, 2);
        layOutTriangleVertex(2, 0, lengthZeroOne, lengthOneTwo, 1);
    }

    private void layOutTriangleVertex(int corner, int otherCorner, double oppositeToCorner,
            double toCorner, int target) {
        double dx = diamondX[otherCorner] - diamondX[corner];
        double dy = diamondY[otherCorner] - diamondY[corner];
        double base = Math.hypot(dx, dy);
        if (base <= 0.0) {
            diamondX[target] = diamondX[corner];
            diamondY[target] = diamondY[corner];
            return;
        }
        double along = (toCorner * toCorner + base * base - oppositeToCorner * oppositeToCorner)
                / (2.0 * base);
        double across = Math.sqrt(Math.max(0.0, toCorner * toCorner - along * along));
        double unitX = dx / base;
        double unitY = dy / base;
        diamondX[target] = diamondX[corner] + unitX * along - unitY * across;
        diamondY[target] = diamondY[corner] + unitY * along + unitX * across;
    }

    private static double cross(double firstX, double firstY, double secondX, double secondY) {
        return firstX * secondY - firstY * secondX;
    }
}
