package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedMeshTopology;

/**
 * A vertex minted on a source edge is minted once and serves both faces that share it.
 * This is what links an arc's chord into a face with its chord out of the next one: the two
 * passages read the same vertex rather than each getting one of their own.
 */
class ArcLaneStripTest {

    /** Corners of a triangle. */
    private static final int CORNERS = 3;

    /** Columns of the strip; two rows of this many vertices. */
    private static final int COLUMNS = 4;

    /** Faces of the strip. */
    private static final int FACES = 5;

    /** Source active face on one side of the edge under test. */
    private static final int NEAR_FACE = 0;

    /** Source active face on the other side of it. */
    private static final int FAR_FACE = 1;

    /** Arc the chords under test belong to. */
    private static final int ARC = 3;

    /**
     * A lane minted on a shared edge carries a barycentric in both source faces, so a
     * chord approaching from either side can reach it. Without this the passage out of a
     * face could not end where the passage into the next one begins.
     */
    @Test
    void aLaneOnASharedEdgeIsReadableFromBothFaces() {
        EmbeddedMeshTopology topology = strip();

        int lane = topology.splitEdgeAtParameter(sharedEdge(topology), 0.5);

        assertNotNull(topology.barycentricOf(NEAR_FACE, lane),
            "the lane has no barycentric in the face on one side of its edge");
        assertNotNull(topology.barycentricOf(FAR_FACE, lane),
            "the lane has no barycentric in the face on the other side of its edge");
    }

    /**
     * Both faces sharing the edge gain the lane as a corner, so exactly one vertex is
     * minted for the two passages rather than one each.
     */
    @Test
    void oneMintServesBothSidesOfTheEdge() {
        EmbeddedMeshTopology topology = strip();
        int vertices = topology.copy.vertexCount();

        int lane = topology.splitEdgeAtParameter(sharedEdge(topology), 0.5);

        assertEquals(vertices + 1, topology.copy.vertexCount(),
            "the shared edge minted more than the one vertex both faces need");
        assertTrue(holdsAsCorner(topology, NEAR_FACE, lane),
            "the face on one side of the edge does not carry the lane");
        assertTrue(holdsAsCorner(topology, FAR_FACE, lane),
            "the face on the other side of the edge does not carry the lane");
    }

    /**
     * A chord ending at the lane may be laid from either side, which is the passage-in
     * meets passage-out case stated directly.
     */
    @Test
    void chordsFromEitherSideEndAtTheSameLane() {
        EmbeddedMeshTopology topology = strip();
        int lane = topology.splitEdgeAtParameter(sharedEdge(topology), 0.5);
        int intoFace = topology.copyVertexForSourceVertexId(
            topology.sourceMesh.faceVertexAt(topology.sourceMesh.faceIdAt(NEAR_FACE), 0));
        int outOfFace = topology.copyVertexForSourceVertexId(
            topology.sourceMesh.faceVertexAt(topology.sourceMesh.faceIdAt(FAR_FACE), 2));

        List<Integer> arriving = topology.insertChord(NEAR_FACE, intoFace, lane, ARC);
        List<Integer> leaving = topology.insertChord(FAR_FACE, lane, outOfFace, ARC);

        assertEquals(lane, arriving.get(arriving.size() - 1),
            "the passage into the face does not end at the lane");
        assertEquals(lane, leaving.get(0), "the passage out of the face does not start there");
        assertNotEquals(EmbeddedMeshTopology.UNCLAIMED, topology.edgeBetween(intoFace, lane),
            "the arriving chord left no edge behind it");
        assertNotEquals(EmbeddedMeshTopology.UNCLAIMED, topology.edgeBetween(lane, outOfFace),
            "the leaving chord left no edge behind it");
    }

    /**
     * Whether a source face has a copy vertex as a corner of one of its children.
     *
     * @param topology   working copy to look in
     * @param sourceFace source active face to search
     * @param copyVertex copy vertex to find
     * @return true when some child of that face has it as a corner
     */
    private boolean holdsAsCorner(EmbeddedMeshTopology topology, int sourceFace, int copyVertex) {
        for (int child : topology.copyFacesBySourceFace.get(sourceFace)) {
            for (int corner = 0; corner < CORNERS; corner++) {
                if (topology.copy.faceVertexAt(child, corner) == copyVertex) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The copy edge the strip's first two faces share, which is the one a lane on it must
     * serve from both sides.
     *
     * @param topology working copy to look in
     * @return that copy edge's id
     */
    private int sharedEdge(EmbeddedMeshTopology topology) {
        return topology.edgeBetween(
            topology.copyVertexForSourceVertexId(topology.sourceMesh.vertexIdAt(1)),
            topology.copyVertexForSourceVertexId(topology.sourceMesh.vertexIdAt(COLUMNS)));
    }

    /**
     * A strip of five triangles between two rows of four vertices.
     *
     * @return a working copy of that strip
     */
    private EmbeddedMeshTopology strip() {
        float[] positions = new float[COLUMNS * 2 * CORNERS];
        for (int column = 0; column < COLUMNS; column++) {
            positions[column * CORNERS] = column;
            positions[(COLUMNS + column) * CORNERS] = column;
            positions[(COLUMNS + column) * CORNERS + 1] = 1.0f;
        }
        List<Integer> faces = new ArrayList<>();
        for (int column = 0; column < COLUMNS - 1 && faces.size() / CORNERS < FACES; column++) {
            faces.add(column);
            faces.add(column + 1);
            faces.add(COLUMNS + column);
            if (faces.size() / CORNERS >= FACES) {
                break;
            }
            faces.add(COLUMNS + column);
            faces.add(column + 1);
            faces.add(COLUMNS + column + 1);
        }
        int[] faceIndices = new int[faces.size()];
        for (int index = 0; index < faces.size(); index++) {
            faceIndices[index] = faces.get(index);
        }
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faceIndices);
        return new EmbeddedMeshTopology(mesh);
    }
}
