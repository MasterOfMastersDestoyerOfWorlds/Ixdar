package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.embedding.FaceStripPath;
import ixdar.geometry.mesh.quadlayout.embedding.SnappingCarve;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * A route that passes C's fan, circles W and returns exactly through C must still be laid
 * through C only once, as block's arc 178 was not.
 *
 * <pre>
 *        R1        B        S3
 *   R2        C         W        S2
 *        R3        A        S1
 * </pre>
 */
class CarveFoldBackCornerTest {

    /** Vertex C, which the route's return leg runs exactly through. */
    private static final int C = 0;

    /** Vertex A, below the edge C's and W's fans share. */
    private static final int A = 1;

    /** Vertex B, above that shared edge. */
    private static final int B = 2;

    /** Vertex W, which the route circles before turning back. */
    private static final int W = 3;

    /** Ring vertex of C above and left of it. */
    private static final int R1 = 4;

    /** Ring vertex of C left of it, the route's start node. */
    private static final int R2 = 5;

    /** Ring vertex of C below and left of it, the route's end node. */
    private static final int R3 = 6;

    /** Ring vertex of W below it. */
    private static final int S1 = 7;

    /** Ring vertex of W right of it. */
    private static final int S2 = 8;

    /** Ring vertex of W above it. */
    private static final int S3 = 9;

    /** Every face of the fixture as its three vertices, in active face order. */
    private static final int[][] FACES = {
        { C, A, B }, { C, B, R1 }, { C, R1, R2 }, { C, R2, R3 }, { C, R3, A },
        { W, B, A }, { W, A, S1 }, { W, S1, S2 }, { W, S2, S3 }, { W, S3, B },
    };

    /** Planar positions of the vertices, as x and y pairs in vertex order. */
    private static final float[] PLANAR = {
        0.0f, 0.0f, 1.0f, -1.0f, 1.0f, 1.0f, 2.0f, 0.0f, -0.5f, 1.2f,
        -1.2f, 0.0f, -0.5f, -1.2f, 2.0f, -1.2f, 3.0f, 0.0f, 2.0f, 1.2f,
    };

    /** The only arc, which carries the folded route. */
    private static final int ARC = 0;

    /** Node the route starts at, on R2. */
    private static final int START_NODE = 0;

    /** Node the route ends at, on R3. */
    private static final int END_NODE = 1;

    /** Where every crossing of the circle and the outward leg sits along its edge. */
    private static final double MID_EDGE = 0.5;

    /** How far along A..B, from A, the outward leg crosses it. */
    private static final double OUTWARD_ON_SHARED_EDGE = 0.8;

    /** How far along A..B, from A, the returning leg crosses it. */
    private static final double RETURN_ON_SHARED_EDGE = 0.2;

    /** Coordinates per vertex position. */
    private static final int COORDINATES = 3;

    /**
     * The outward crossing of C..R1 is released to a lane of its own, since the return leg
     * passes exactly through C and no lane can replace that, so the laid path visits C once.
     */
    @Test
    void aCornerTheReturnLegRunsThroughIsTakenBackFromTheOutwardLeg() {
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(fanPair());
        SnappingCarve carve = new SnappingCarve(topology);
        carve.nodeCount = 2;
        carve.vertexIdByNode = new int[] { R2, R3 };
        topology.ownerNodeByCopyVertex[R2] = START_NODE;
        topology.ownerNodeByCopyVertex[R3] = END_NODE;
        carve.startNodeByArc = new int[] { START_NODE };
        carve.endNodeByArc = new int[] { END_NODE };
        FaceStripPath route = foldedRoute(topology);
        carve.stripByArc = List.of(route);

        carve.carve();

        List<Integer> path = carve.pathByArc[ARC].copyVertexPath;
        assertEquals(path.size(), new HashSet<>(path).size(),
                "the laid path visits a vertex twice: " + path);
        assertEquals(1, carve.repeatedCornerReleaseCount,
                "only the outward crossing on C..R1 gives C back");
        assertEquals(List.of(C, R3), path.subList(path.size() - 2, path.size()),
                "the return leg still runs through C to the end node: " + path);
    }

    /**
     * The folded route: out from R2 across C..R1, C..B and A..B near B, once round W, back
     * across A..B near A, exactly through C and along C..R3.
     *
     * @param topology working copy of the fixture
     * @return the route refined onto it
     */
    private FaceStripPath foldedRoute(EmbeddedMeshTopology topology) {
        FaceStripPath route = new FaceStripPath(topology, ARC);
        route.addPassage(face(C, R1, R2), corner(C, R1, R2, R2), onEdge(C, R1, R2, C, R1, MID_EDGE));
        route.addPassage(face(C, B, R1), onEdge(C, B, R1, C, R1, MID_EDGE),
                onEdge(C, B, R1, C, B, MID_EDGE));
        route.addPassage(face(C, A, B), onEdge(C, A, B, C, B, MID_EDGE),
                onEdge(C, A, B, A, B, OUTWARD_ON_SHARED_EDGE));
        route.addPassage(face(W, B, A), onEdge(W, B, A, A, B, OUTWARD_ON_SHARED_EDGE),
                onEdge(W, B, A, W, B, MID_EDGE));
        route.addPassage(face(W, S3, B), onEdge(W, S3, B, W, B, MID_EDGE),
                onEdge(W, S3, B, W, S3, MID_EDGE));
        route.addPassage(face(W, S2, S3), onEdge(W, S2, S3, W, S3, MID_EDGE),
                onEdge(W, S2, S3, W, S2, MID_EDGE));
        route.addPassage(face(W, S1, S2), onEdge(W, S1, S2, W, S2, MID_EDGE),
                onEdge(W, S1, S2, W, S1, MID_EDGE));
        route.addPassage(face(W, A, S1), onEdge(W, A, S1, W, S1, MID_EDGE),
                onEdge(W, A, S1, W, A, MID_EDGE));
        route.addPassage(face(W, B, A), onEdge(W, B, A, W, A, MID_EDGE),
                onEdge(W, B, A, A, B, RETURN_ON_SHARED_EDGE));
        route.addPassage(face(C, A, B), onEdge(C, A, B, A, B, RETURN_ON_SHARED_EDGE),
                corner(C, A, B, C));
        route.addPassage(face(C, R2, R3), corner(C, R2, R3, C), corner(C, R2, R3, R3));
        return route;
    }

    /**
     * The active index of the fixture face with these corners.
     *
     * @param first  its first corner
     * @param second its second corner
     * @param third  its third corner
     * @throws IllegalArgumentException when no face has them in that order
     * @return the face's index in {@link #FACES}
     */
    private static int face(int first, int second, int third) {
        for (int index = 0; index < FACES.length; index++) {
            if (FACES[index][0] == first && FACES[index][1] == second
                    && FACES[index][2] == third) {
                return index;
            }
        }
        throw new IllegalArgumentException("no face " + first + ", " + second + ", " + third);
    }

    /**
     * The barycentric of one corner of a face, in that face's frame.
     *
     * @param first  the face's first corner
     * @param second its second corner
     * @param third  its third corner
     * @param vertex the corner wanted
     * @return weight one at that corner
     */
    private static double[] corner(int first, int second, int third, int vertex) {
        return onEdge(first, second, third, vertex, vertex, 0.0);
    }

    /**
     * The barycentric of a point on an edge of a face, in that face's frame.
     *
     * @param first  the face's first corner
     * @param second its second corner
     * @param third  its third corner
     * @param from   edge end the position is measured from
     * @param to     the other edge end
     * @param along  position along the edge from {@code from}
     * @return the point's barycentric
     */
    private static double[] onEdge(int first, int second, int third, int from, int to,
            double along) {
        int[] corners = { first, second, third };
        double[] barycentric = new double[COORDINATES];
        for (int index = 0; index < COORDINATES; index++) {
            barycentric[index] += corners[index] == from ? 1.0 - along : 0.0;
            barycentric[index] += corners[index] == to ? along : 0.0;
        }
        return barycentric;
    }

    /**
     * Two vertex fans, around C and around W, sharing the edge A..B, in the plane.
     *
     * @return the fixture mesh
     */
    private static HalfEdgeMesh fanPair() {
        float[] positions = new float[PLANAR.length / 2 * COORDINATES];
        for (int vertex = 0; vertex < PLANAR.length / 2; vertex++) {
            positions[vertex * COORDINATES] = PLANAR[vertex * 2];
            positions[vertex * COORDINATES + 1] = PLANAR[vertex * 2 + 1];
        }
        int[] faceIndices = new int[FACES.length * COORDINATES];
        for (int index = 0; index < FACES.length; index++) {
            System.arraycopy(FACES[index], 0, faceIndices, index * COORDINATES, COORDINATES);
        }
        return HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faceIndices);
    }
}
