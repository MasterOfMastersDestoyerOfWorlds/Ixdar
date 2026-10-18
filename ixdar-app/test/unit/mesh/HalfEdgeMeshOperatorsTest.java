package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.data.representation.InvalidMeshTopologyException;
import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * The local editing operators of {@link HalfEdgeMesh} — flipEdge, splitFace, collapseEdge,
 * splitEdge and replaceFaces — must leave a consistent, manifold half-edge structure and carry
 * every declared attribute layer the way its declaration says.
 */
public class HalfEdgeMeshOperatorsTest {

    private static final int FACE_FILL = -1;
    private static final int EDGE_FILL = -2;
    private static final int VERTEX_FILL = -3;
    private static final int FACE_TAG_BASE = 100;
    private static final int EDGE_TAG_BASE = 1000;
    private static final int VERTEX_TAG_BASE = 10_000;
    private static final int OCTAHEDRON_TOP = 0;
    private static final int OCTAHEDRON_BOTTOM = 5;
    private static final int OCTAHEDRON_OPPOSITE_EQUATOR = 3;
    private static final int OCTAHEDRON_VERTICES = 6;
    private static final int OCTAHEDRON_EDGES = 12;
    private static final int OCTAHEDRON_FACES = 8;
    private static final int GRID_SIDE = 4;

    /**
     * Flipping an equator edge of an octahedron joins the poles' neighbours across it, keeps the
     * counts, gives both new faces the canonical face's tag, the new diagonal the fill, and
     * leaves every other edge's tag where it was.
     */
    @Test
    void flipEdgeReplacesTheDiagonalAndCarriesLayers() {
        HalfEdgeMesh mesh = octahedron();
        int faceLayer = mesh.declareFaceLayer(FACE_FILL);
        int edgeLayer = mesh.declareEdgeLayer(EDGE_FILL);
        tagEveryElement(mesh, faceLayer, edgeLayer, MeshTopology.NONE);
        int edgeId = mesh.edgeBetween(1, 2);
        int canonicalFace = mesh.edgeFace(edgeId, 0);
        int keptOuterEdge = mesh.edgeBetween(1, OCTAHEDRON_TOP);
        int keptOuterTag = mesh.edgeLayers[edgeLayer][keptOuterEdge];

        int diagonal = mesh.flipEdge(edgeId);

        assertNull(firstInconsistency(mesh));
        assertEquals(OCTAHEDRON_VERTICES, mesh.vertexCount());
        assertEquals(OCTAHEDRON_EDGES, mesh.edgeCount());
        assertEquals(OCTAHEDRON_FACES, mesh.faceCount());
        assertEquals(MeshTopology.NONE, mesh.edgeBetween(1, 2));
        assertEquals(diagonal, mesh.edgeBetween(OCTAHEDRON_TOP, OCTAHEDRON_BOTTOM));
        assertEquals(EDGE_FILL, mesh.edgeLayers[edgeLayer][diagonal]);
        assertEquals(keptOuterTag, mesh.edgeLayers[edgeLayer][keptOuterEdge]);
        for (int side = 0; side < 2; side++) {
            assertEquals(FACE_TAG_BASE + canonicalFace,
                    mesh.faceLayers[faceLayer][mesh.edgeFace(diagonal, side)]);
        }
    }

    /** Neither a boundary edge nor an edge whose opposite corners are already joined flips. */
    @Test
    void flipEdgeRefusesBoundaryAndJoinedCorners() {
        HalfEdgeMesh grid = grid();
        assertThrows(InvalidMeshTopologyException.class, () -> grid.flipEdge(grid.edgeBetween(0, 1)));
        HalfEdgeMesh tetrahedron = tetrahedron();
        assertThrows(InvalidMeshTopologyException.class,
                () -> tetrahedron.flipEdge(tetrahedron.edgeBetween(0, 1)));
        assertNull(firstInconsistency(tetrahedron));
    }

    /**
     * Flipping the diagonal of a lone square reaps and re-creates its four boundary edges, and
     * each comes back with its own tag.
     */
    @Test
    void reCreatedBoundaryEdgesGetTheirTagsBack() {
        HalfEdgeMesh mesh = mesh(new float[] { 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0 },
                new int[] { 0, 1, 2, 0, 2, 3 });
        int edgeLayer = mesh.declareEdgeLayer(EDGE_FILL);
        tagEveryElement(mesh, MeshTopology.NONE, edgeLayer, MeshTopology.NONE);
        int[][] sides = { { 0, 1 }, { 1, 2 }, { 2, 3 }, { 3, 0 } };
        int[] tags = new int[sides.length];
        for (int side = 0; side < sides.length; side++) {
            tags[side] = mesh.edgeLayers[edgeLayer][mesh.edgeBetween(sides[side][0], sides[side][1])];
        }

        mesh.flipEdge(mesh.edgeBetween(0, 2));

        assertNull(firstInconsistency(mesh));
        for (int side = 0; side < sides.length; side++) {
            assertEquals(tags[side],
                    mesh.edgeLayers[edgeLayer][mesh.edgeBetween(sides[side][0], sides[side][1])]);
        }
    }

    /**
     * Splitting an octahedron face mints one vertex with three spokes and two more faces; the
     * three children carry the face's tag, the spokes the fill and the new vertex the fill.
     */
    @Test
    void splitFaceFansAroundOneVertexAndChildrenInherit() {
        HalfEdgeMesh mesh = octahedron();
        int faceLayer = mesh.declareFaceLayer(FACE_FILL);
        int edgeLayer = mesh.declareEdgeLayer(EDGE_FILL);
        int vertexLayer = mesh.declareVertexLayer(VERTEX_FILL, MeshTopology.NONE);
        tagEveryElement(mesh, faceLayer, edgeLayer, vertexLayer);
        int faceId = mesh.faceIdAt(0);

        int newVertex = mesh.splitFace(faceId, centroid(mesh, faceId));

        assertNull(firstInconsistency(mesh));
        assertEquals(OCTAHEDRON_VERTICES + 1, mesh.vertexCount());
        assertEquals(OCTAHEDRON_EDGES + 3, mesh.edgeCount());
        assertEquals(OCTAHEDRON_FACES + 2, mesh.faceCount());
        assertEquals(3, mesh.vertexFaceCount(newVertex));
        assertEquals(VERTEX_FILL, mesh.vertexLayers[vertexLayer][newVertex]);
        for (int index = 0; index < mesh.vertexFaceCount(newVertex); index++) {
            assertEquals(FACE_TAG_BASE + faceId,
                    mesh.faceLayers[faceLayer][mesh.vertexFaceAt(newVertex, index)]);
        }
        for (int index = 0; index < mesh.vertexEdgeCount(newVertex); index++) {
            assertEquals(EDGE_FILL, mesh.edgeLayers[edgeLayer][mesh.vertexEdgeAt(newVertex, index)]);
        }
    }

    /**
     * Splitting an edge hands its tag to both halves and, through a vertex layer that reads
     * it, to the minted vertex; a vertex layer that reads nothing gives the fill, and each new
     * face carries the tag of the face it split from.
     */
    @Test
    void splitEdgeHalvesAndMintedVertexInherit() {
        HalfEdgeMesh mesh = octahedron();
        int faceLayer = mesh.declareFaceLayer(FACE_FILL);
        int edgeLayer = mesh.declareEdgeLayer(EDGE_FILL);
        int readingLayer = mesh.declareVertexLayer(VERTEX_FILL, edgeLayer);
        int plainLayer = mesh.declareVertexLayer(VERTEX_FILL, MeshTopology.NONE);
        tagEveryElement(mesh, faceLayer, edgeLayer, plainLayer);
        int edgeId = mesh.edgeBetween(1, 2);
        int tag = mesh.edgeLayers[edgeLayer][edgeId];
        int faceA = mesh.edgeFace(edgeId, 0);
        int faceB = mesh.edgeFace(edgeId, 1);

        int newVertex = mesh.splitEdge(edgeId, new Vector3f(mesh.edgeMidpoint(edgeId, new Vector3f())));

        assertNull(firstInconsistency(mesh));
        int tailEdge = mesh.edgeBetween(newVertex, 2);
        assertEquals(tag, mesh.edgeLayers[edgeLayer][edgeId]);
        assertEquals(tag, mesh.edgeLayers[edgeLayer][tailEdge]);
        assertEquals(tag, mesh.vertexLayers[readingLayer][newVertex]);
        assertEquals(VERTEX_FILL, mesh.vertexLayers[plainLayer][newVertex]);
        assertEquals(FACE_TAG_BASE + faceA, mesh.faceLayers[faceLayer][mesh.edgeFace(tailEdge, 0)]);
        assertEquals(FACE_TAG_BASE + faceB, mesh.faceLayers[faceLayer][mesh.edgeFace(tailEdge, 1)]);
    }

    /**
     * Collapsing a pole-to-equator octahedron edge leaves a closed five-vertex mesh whose kept
     * vertex, rebuilt faces, re-created and merged edges carry the tags their elements had.
     */
    @Test
    void collapseEdgeMergesOntoTheKeptVertex() {
        HalfEdgeMesh mesh = octahedron();
        int faceLayer = mesh.declareFaceLayer(FACE_FILL);
        int edgeLayer = mesh.declareEdgeLayer(EDGE_FILL);
        int vertexLayer = mesh.declareVertexLayer(VERTEX_FILL, MeshTopology.NONE);
        tagEveryElement(mesh, faceLayer, edgeLayer, vertexLayer);
        int edgeId = mesh.edgeBetween(OCTAHEDRON_TOP, 1);
        int kept = mesh.halfEdgeVertex(mesh.edgeHalfEdge(edgeId));
        int removed = mesh.halfEdgeEndVertex(mesh.edgeHalfEdge(edgeId));
        int farPole = kept == OCTAHEDRON_TOP ? OCTAHEDRON_BOTTOM : OCTAHEDRON_OPPOSITE_EQUATOR;
        int removedToFarPoleTag = mesh.edgeLayers[edgeLayer][mesh.edgeBetween(removed, farPole)];
        int mergedEdge = mesh.edgeBetween(kept, 2);
        int mergedTag = mesh.edgeLayers[edgeLayer][mergedEdge];
        Set<Integer> fanTags = new HashSet<>();
        for (int index = 0; index < mesh.vertexFaceCount(removed); index++) {
            int faceId = mesh.vertexFaceAt(removed, index);
            if (faceId != mesh.edgeFace(edgeId, 0) && faceId != mesh.edgeFace(edgeId, 1)) {
                fanTags.add(mesh.faceLayers[faceLayer][faceId]);
            }
        }

        int result = mesh.collapseEdge(edgeId, mesh.vertexPosition(kept));

        assertNull(firstInconsistency(mesh));
        assertEquals(kept, result);
        assertTrue(!mesh.hasVertex(removed));
        assertEquals(OCTAHEDRON_VERTICES - 1, mesh.vertexCount());
        assertEquals(OCTAHEDRON_EDGES - 3, mesh.edgeCount());
        assertEquals(OCTAHEDRON_FACES - 2, mesh.faceCount());
        assertEquals(VERTEX_TAG_BASE + kept, mesh.vertexLayers[vertexLayer][kept]);
        assertEquals(removedToFarPoleTag, mesh.edgeLayers[edgeLayer][mesh.edgeBetween(kept, farPole)]);
        assertEquals(mergedTag, mesh.edgeLayers[edgeLayer][mergedEdge]);
        Set<Integer> rebuiltTags = new HashSet<>();
        for (int index = 0; index < mesh.vertexFaceCount(kept); index++) {
            int faceId = mesh.vertexFaceAt(kept, index);
            if (mesh.faceVertexAt(faceId, 0) == farPole || mesh.faceVertexAt(faceId, 1) == farPole
                    || mesh.faceVertexAt(faceId, 2) == farPole) {
                rebuiltTags.add(mesh.faceLayers[faceLayer][faceId]);
            }
        }
        assertEquals(fanTags, rebuiltTags);
    }

    /**
     * An interior grid edge collapses cleanly; a tetrahedron edge (which would duplicate a
     * face), an interior edge joining two boundary vertices, and an edge whose endpoints share
     * a third neighbour are refused with the mesh untouched.
     */
    @Test
    void collapseEdgeRefusesNonManifoldResults() {
        HalfEdgeMesh grid = grid();
        int interior = GRID_SIDE + 1;
        grid.collapseEdge(grid.edgeBetween(interior, interior + 1), grid.vertexPosition(interior));
        assertNull(firstInconsistency(grid));

        HalfEdgeMesh tetrahedron = tetrahedron();
        assertThrows(InvalidMeshTopologyException.class,
                () -> tetrahedron.collapseEdge(tetrahedron.edgeBetween(0, 1), new Vector3f()));
        assertNull(firstInconsistency(tetrahedron));

        HalfEdgeMesh strip = mesh(new float[] { 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0 },
                new int[] { 0, 1, 2, 0, 2, 3 });
        assertThrows(InvalidMeshTopologyException.class,
                () -> strip.collapseEdge(strip.edgeBetween(0, 2), new Vector3f()));
        assertNull(firstInconsistency(strip));

        HalfEdgeMesh openFan = mesh(new float[] { 0, 0, 1, 1, 0, 0, -1, 1, 0, -1, -1, 0 },
                new int[] { 0, 1, 2, 0, 2, 3, 0, 3, 1 });
        assertThrows(InvalidMeshTopologyException.class,
                () -> openFan.collapseEdge(openFan.edgeBetween(1, 2), new Vector3f()));
        assertNull(firstInconsistency(openFan));
    }

    /**
     * A run of operators on a grid keeps the structure consistent after every step, and a face
     * layer declared before them still covers every face id they minted.
     */
    @Test
    void mixedEditsStayConsistent() {
        HalfEdgeMesh mesh = grid();
        int faceLayer = mesh.declareFaceLayer(FACE_FILL);
        tagEveryElement(mesh, faceLayer, MeshTopology.NONE, MeshTopology.NONE);
        int center = GRID_SIDE + 1;
        int minted = mesh.splitFace(mesh.vertexFaceAt(center, 0),
                centroid(mesh, mesh.vertexFaceAt(center, 0)));
        assertNull(firstInconsistency(mesh));
        int spoke = mesh.edgeBetween(center, minted);
        mesh.splitEdge(spoke, mesh.edgeMidpoint(spoke, new Vector3f()));
        assertNull(firstInconsistency(mesh));
        int diagonal = mesh.edgeBetween(center, center + GRID_SIDE + 1);
        mesh.flipEdge(diagonal);
        assertNull(firstInconsistency(mesh));
        for (int active = 0; active < mesh.faceCount(); active++) {
            assertNotEquals(FACE_FILL, mesh.faceLayers[faceLayer][mesh.faceIdAt(active)]);
        }
        HalfEdgeMeshEngine.computeNormals(mesh);
    }

    /**
     * Tags every active face, edge and vertex with its id offset by a per-kind base, skipping
     * a layer passed as {@link MeshTopology#NONE}.
     *
     * @param mesh        mesh to tag
     * @param faceLayer   face layer to tag, or NONE
     * @param edgeLayer   edge layer to tag, or NONE
     * @param vertexLayer vertex layer to tag, or NONE
     */
    private static void tagEveryElement(HalfEdgeMesh mesh, int faceLayer, int edgeLayer,
            int vertexLayer) {
        for (int active = 0; active < mesh.faceCount() && faceLayer != MeshTopology.NONE; active++) {
            mesh.faceLayers[faceLayer][mesh.faceIdAt(active)] = FACE_TAG_BASE + mesh.faceIdAt(active);
        }
        for (int active = 0; active < mesh.edgeCount() && edgeLayer != MeshTopology.NONE; active++) {
            mesh.edgeLayers[edgeLayer][mesh.edgeIdAt(active)] = EDGE_TAG_BASE + mesh.edgeIdAt(active);
        }
        for (int active = 0; active < mesh.vertexCount() && vertexLayer != MeshTopology.NONE;
                active++) {
            mesh.vertexLayers[vertexLayer][mesh.vertexIdAt(active)] = VERTEX_TAG_BASE
                    + mesh.vertexIdAt(active);
        }
    }

    /**
     * The first broken invariant of a half-edge mesh: twins, next/prev cycles against the face
     * rows, edge and vertex adjacency rows against the half-edges, and a single fan of faces
     * around every vertex.
     *
     * @param mesh mesh to check
     * @return a description of the first violation, or {@code null} when consistent
     */
    private static String firstInconsistency(HalfEdgeMesh mesh) {
        for (int active = 0; active < mesh.halfEdgeCount(); active++) {
            int halfEdge = mesh.halfEdgeIdAt(active);
            int twin = mesh.halfEdgeTwin(halfEdge);
            if (twin == halfEdge || mesh.halfEdgeTwin(twin) != halfEdge || !mesh.hasHalfEdge(twin)) {
                return "half-edge " + halfEdge + " has a broken twin";
            }
            int edgeId = mesh.halfEdgeEdge(halfEdge);
            if (!mesh.hasEdge(edgeId) || mesh.halfEdgeEdge(twin) != edgeId
                    || mesh.edgeHalfEdge(edgeId) != halfEdge && mesh.edgeHalfEdge(edgeId) != twin) {
                return "half-edge " + halfEdge + " disagrees with edge " + edgeId;
            }
            int faceId = mesh.halfEdgeFace(halfEdge);
            if (faceId != MeshTopology.NONE && (mesh.halfEdgePrev(mesh.halfEdgeNext(halfEdge)) != halfEdge
                    || mesh.halfEdgeFace(mesh.halfEdgeNext(halfEdge)) != faceId
                    || mesh.halfEdgeVertex(mesh.halfEdgeNext(halfEdge)) != mesh.halfEdgeVertex(twin))) {
                return "half-edge " + halfEdge + " breaks the cycle of face " + faceId;
            }
            int start = mesh.halfEdgeVertex(halfEdge);
            boolean listed = false;
            for (int index = 0; index < mesh.vertexOutgoingHalfEdgeCount(start); index++) {
                listed |= mesh.vertexOutgoingHalfEdgeAt(start, index) == halfEdge;
            }
            if (!listed || mesh.edgeBetween(start, mesh.halfEdgeVertex(twin)) != edgeId) {
                return "half-edge " + halfEdge + " is missing from vertex " + start + "'s rows";
            }
        }
        for (int active = 0; active < mesh.faceCount(); active++) {
            int faceId = mesh.faceIdAt(active);
            int halfEdge = mesh.faceHalfEdge(faceId);
            for (int corner = 0; corner < mesh.faceVertexCount(faceId); corner++) {
                if (mesh.faceHalfEdgeAt(faceId, corner) != halfEdge
                        || mesh.faceVertexAt(faceId, corner) != mesh.halfEdgeVertex(halfEdge)
                        || mesh.faceEdgeAt(faceId, corner) != mesh.halfEdgeEdge(halfEdge)
                        || mesh.halfEdgeFace(halfEdge) != faceId) {
                    return "face " + faceId + " rows disagree with its cycle at corner " + corner;
                }
                halfEdge = mesh.halfEdgeNext(halfEdge);
            }
            if (halfEdge != mesh.faceHalfEdge(faceId)) {
                return "face " + faceId + " cycle is longer than its rows";
            }
        }
        for (int active = 0; active < mesh.vertexCount(); active++) {
            int vertexId = mesh.vertexIdAt(active);
            if (mesh.vertexOutgoingHalfEdgeCount(vertexId) != mesh.vertexEdgeCount(vertexId)) {
                return "vertex " + vertexId + " lists " + mesh.vertexOutgoingHalfEdgeCount(vertexId)
                        + " outgoing half-edges for " + mesh.vertexEdgeCount(vertexId) + " edges";
            }
            Set<Integer> faces = new HashSet<>();
            for (int index = 0; index < mesh.vertexFaceCount(vertexId); index++) {
                faces.add(mesh.vertexFaceAt(vertexId, index));
            }
            int boundaryStarts = 0;
            int fanStart = MeshTopology.NONE;
            for (int index = 0; index < mesh.vertexOutgoingHalfEdgeCount(vertexId); index++) {
                int outgoing = mesh.vertexOutgoingHalfEdgeAt(vertexId, index);
                fanStart = fanStart == MeshTopology.NONE && mesh.halfEdgeFace(outgoing) != MeshTopology.NONE
                        ? outgoing : fanStart;
                if (mesh.halfEdgeFace(mesh.halfEdgeTwin(outgoing)) == MeshTopology.NONE
                        && mesh.halfEdgeFace(outgoing) != MeshTopology.NONE) {
                    boundaryStarts++;
                    fanStart = outgoing;
                }
            }
            if (boundaryStarts > 1) {
                return "vertex " + vertexId + " is a bow-tie with " + boundaryStarts + " fans";
            }
            int reached = 0;
            for (int walk = fanStart; walk != MeshTopology.NONE && mesh.halfEdgeFace(walk) != MeshTopology.NONE
                    && reached <= faces.size();) {
                if (!faces.contains(mesh.halfEdgeFace(walk))) {
                    return "vertex " + vertexId + " fan reaches unlisted face " + mesh.halfEdgeFace(walk);
                }
                reached++;
                walk = mesh.halfEdgeTwin(mesh.halfEdgePrev(walk));
                if (walk == fanStart) {
                    break;
                }
            }
            if (reached != faces.size()) {
                return "vertex " + vertexId + " fan reaches " + reached + " of " + faces.size() + " faces";
            }
        }
        return null;
    }

    /**
     * Centroid of a face's corners.
     *
     * @param mesh   mesh holding the face
     * @param faceId face to average
     * @return the centroid
     */
    private static Vector3f centroid(HalfEdgeMesh mesh, int faceId) {
        Vector3f sum = new Vector3f();
        for (int corner = 0; corner < mesh.faceVertexCount(faceId); corner++) {
            sum.add(mesh.vertexPosition(mesh.faceVertexAt(faceId, corner)));
        }
        return sum.div(mesh.faceVertexCount(faceId));
    }

    /**
     * Unit octahedron: vertex 0 the top pole, 1 to 4 the equator in order, 5 the bottom pole.
     *
     * @return the closed mesh
     */
    private static HalfEdgeMesh octahedron() {
        return mesh(new float[] { 0, 0, 1, 1, 0, 0, 0, 1, 0, -1, 0, 0, 0, -1, 0, 0, 0, -1 },
                new int[] { 0, 1, 2, 0, 2, 3, 0, 3, 4, 0, 4, 1, 5, 2, 1, 5, 3, 2, 5, 4, 3, 5, 1, 4 });
    }

    /**
     * Regular tetrahedron, every pair of vertices joined.
     *
     * @return the closed mesh
     */
    private static HalfEdgeMesh tetrahedron() {
        return mesh(new float[] { 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1 },
                new int[] { 0, 2, 1, 0, 1, 3, 0, 3, 2, 1, 2, 3 });
    }

    /**
     * Open grid of {@code GRID_SIDE} by {@code GRID_SIDE} vertices, each cell cut along the
     * diagonal from its lower-left corner.
     *
     * @return the open mesh
     */
    private static HalfEdgeMesh grid() {
        float[] positions = new float[GRID_SIDE * GRID_SIDE * 3];
        for (int vertex = 0; vertex < GRID_SIDE * GRID_SIDE; vertex++) {
            positions[vertex * 3] = vertex % GRID_SIDE;
            positions[vertex * 3 + 1] = vertex / GRID_SIDE;
        }
        int[] triangles = new int[(GRID_SIDE - 1) * (GRID_SIDE - 1) * 2 * 3];
        int cursor = 0;
        for (int row = 0; row < GRID_SIDE - 1; row++) {
            for (int column = 0; column < GRID_SIDE - 1; column++) {
                int corner = row * GRID_SIDE + column;
                int[] pair = { corner, corner + 1, corner + GRID_SIDE + 1,
                    corner, corner + GRID_SIDE + 1, corner + GRID_SIDE };
                System.arraycopy(pair, 0, triangles, cursor, pair.length);
                cursor += pair.length;
            }
        }
        return mesh(positions, triangles);
    }

    /**
     * Builds a triangle mesh.
     *
     * @param positions packed xyz triples
     * @param triangles corners, three per face
     * @return the mesh
     */
    private static HalfEdgeMesh mesh(float[] positions, int[] triangles) {
        return HalfEdgeMeshEngine.buildFromIndexedMesh(positions, triangles);
    }
}
