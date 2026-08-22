package ixdar.geometry.mesh.data.representation;

import java.util.Arrays;
import java.util.HashMap;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.EdgeKey;
import ixdar.common.exceptions.InvalidMeshTopologyException;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.graphics.render.model.HalfEdgeCompiledMeshData;

/**
 * Topology-mutating operations on a {@link HalfEdgeMesh}: add/remove vertices,
 * edges, and faces; bulk allocation from indexed buffers; quad subdivision; and
 * triangulated GPU compilation. All editing keeps the half-edge invariants
 * (twin/next/prev, edge↔half-edge, face↔half-edge) consistent.
 */
public class HalfEdgeMeshEngine {
    public static final String EDGE = "Edge ";
    public static final String POSITION_DATA_MUST_BE_XYZ_TRIPLES = "Position data must be XYZ triples";
    public static final String AND = " and ";
    public static final float NUM_0 = 0f;
    public static final int NUM_3 = 3;
    public static final int NUM_4 = 4;
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_0_25 = 0.25f;
    public static final int NUM_8 = 8;
    public static final int NUM_5 = 5;
    public static final int NUM_6 = 6;
    public static final int NUM_7 = 7;

    /**
     * Allocates a new vertex slot at the given world-space position.
     *
     * @param mesh target mesh
     * @param x x coordinate
     * @param y y coordinate
     * @param z z coordinate
     * @return id of the newly created vertex
     */
    public static int addVertex(HalfEdgeMesh mesh, float x, float y, float z) {
        return mesh.createVertexSlot(x, y, z);
    }

    /**
     * Creates a fresh edge plus its two half-edges between the two existing vertices.
     *
     * @param mesh target mesh
     * @param startVertexId active vertex on one end
     * @param endVertexId active vertex on the other end (must differ from {@code startVertexId})
     * @return id of the newly created edge
     */
    public static int addEdge(HalfEdgeMesh mesh, int startVertexId, int endVertexId) {
        validateDistinctVertices(startVertexId, endVertexId);
        ensureDirectedEdgeAvailable(mesh, startVertexId, endVertexId);
        return createEdgePair(mesh, startVertexId, endVertexId);
    }

    /**
     * Adds a face without recomputing normals; call {@link #computeNormals} when the mesh is complete.
     *
     * @param mesh target mesh
     * @param vertexIds ordered vertex ids defining the face (length &ge; 3)
     * @return id of the newly created face
     */
    public static int addFace(HalfEdgeMesh mesh, int... vertexIds) {
        return addFaceInternal(mesh, vertexIds, false);
    }

    /**
     * Detaches a face from its half-edges, reaps any edges left isolated, and
     * recomputes normals.
     *
     * @param mesh target mesh
     * @param faceId active face id to remove
     */
    public static void removeFace(HalfEdgeMesh mesh, int faceId) {
        removeFaceKeepingNormals(mesh, faceId);
        computeNormals(mesh);
    }

    /**
     * Same as {@link #removeFace} but leaves normals stale. Callers performing
     * many local edits (incremental remeshing) batch a single
     * {@link #computeNormals} at the end instead of paying a full-mesh normal
     * pass per removal.
     *
     * @param mesh target mesh
     * @param faceId active face id to remove
     */
    public static void removeFaceKeepingNormals(HalfEdgeMesh mesh, int faceId) {
        IntIdList vertices = mesh.faceVertices.get(faceId);
        for (int index = 0; index < vertices.size(); index++) {
            mesh.vertexFaces.get(vertices.get(index)).removeValue(faceId);
        }

        IntIdList halfEdges = mesh.faceHalfEdges.get(faceId);
        for (int index = 0; index < halfEdges.size(); index++) {
            int halfEdgeId = halfEdges.get(index);
            mesh.halfEdgeFace[halfEdgeId] = MeshTopology.NONE;
            mesh.halfEdgeNext[halfEdgeId] = MeshTopology.NONE;
            mesh.halfEdgePrev[halfEdgeId] = MeshTopology.NONE;
        }

        IntIdList edges = mesh.faceEdges.get(faceId);
        for (int index = 0; index < edges.size(); index++) {
            int edgeId = edges.get(index);
            if (mesh.hasEdge(edgeId) && isIsolatedEdge(mesh, edgeId)) {
                removeEdge(mesh, edgeId);
            }
        }

        mesh.deactivateFace(faceId);
    }

    /**
     * Splits an edge of a triangle mesh in place at the given position: the edge
     * keeps its id as the half toward its canonical start vertex, a new edge forms
     * the other half, and each incident face is bisected by a new spoke — two new
     * faces, no retired slots.
     *
     * @param mesh   target mesh
     * @param edgeId active edge to split; each incident face must be a triangle
     * @param x      x coordinate of the split point
     * @param y      y coordinate of the split point
     * @param z      z coordinate of the split point
     * @throws InvalidMeshTopologyException if an incident face is not a triangle
     * @return id of the newly created vertex on the split point
     */
    public static int splitEdge(HalfEdgeMesh mesh, int edgeId, float x, float y, float z) {
        int halfEdge = mesh.edgeHalfEdge[edgeId];
        int twin = mesh.halfEdgeTwin[halfEdge];
        int vertexA = mesh.halfEdgeVertex[halfEdge];
        int vertexB = mesh.halfEdgeVertex[twin];
        int faceA = mesh.halfEdgeFace[halfEdge];
        int faceB = mesh.halfEdgeFace[twin];
        if (faceA != MeshTopology.NONE && mesh.faceVertexCount(faceA) != NUM_3
                || faceB != MeshTopology.NONE && mesh.faceVertexCount(faceB) != NUM_3) {
            throw new InvalidMeshTopologyException(EDGE + edgeId
                    + " cannot be split in place: an incident face is not a triangle");
        }
        int nextA = mesh.halfEdgeNext[halfEdge];
        int prevA = mesh.halfEdgePrev[halfEdge];
        int nextB = mesh.halfEdgeNext[twin];
        int prevB = mesh.halfEdgePrev[twin];

        int newVertex = mesh.createVertexSlot(x, y, z);

        mesh.halfEdgeVertex[twin] = newVertex;
        mesh.vertexOutgoingHalfEdges.get(vertexB).removeValue(twin);
        mesh.vertexOutgoingHalfEdges.get(newVertex).add(twin);
        mesh.vertexEdges.get(vertexB).removeValue(edgeId);
        mesh.vertexEdges.get(newVertex).addUnique(edgeId);
        if (mesh.vertexOutgoing[vertexB] == twin) {
            mesh.vertexOutgoing[vertexB] = MeshTopology.NONE;
        }
        mesh.vertexOutgoing[newVertex] = twin;

        int tailEdge = createEdgePair(mesh, newVertex, vertexB);
        int tailForward = mesh.edgeHalfEdge[tailEdge];
        int tailBackward = mesh.halfEdgeTwin[tailForward];

        if (faceA != MeshTopology.NONE) {
            int oppositeA = mesh.halfEdgeVertex[prevA];
            int spokeEdgeA = createEdgePair(mesh, newVertex, oppositeA);
            int spokeForwardA = mesh.edgeHalfEdge[spokeEdgeA];
            int spokeBackwardA = mesh.halfEdgeTwin[spokeForwardA];
            int newFaceA = mesh.createFaceSlot();

            mesh.halfEdgeNext[halfEdge] = spokeForwardA;
            mesh.halfEdgePrev[spokeForwardA] = halfEdge;
            mesh.halfEdgeNext[spokeForwardA] = prevA;
            mesh.halfEdgePrev[prevA] = spokeForwardA;
            mesh.halfEdgeFace[spokeForwardA] = faceA;
            mesh.faceHalfEdge[faceA] = halfEdge;
            IntIdList faceHalfEdges = mesh.faceHalfEdges.get(faceA);
            IntIdList faceVertices = mesh.faceVertices.get(faceA);
            IntIdList faceEdges = mesh.faceEdges.get(faceA);
            faceHalfEdges.clear();
            faceVertices.clear();
            faceEdges.clear();
            faceHalfEdges.add(halfEdge);
            faceHalfEdges.add(spokeForwardA);
            faceHalfEdges.add(prevA);
            faceVertices.add(vertexA);
            faceVertices.add(newVertex);
            faceVertices.add(oppositeA);
            faceEdges.add(edgeId);
            faceEdges.add(spokeEdgeA);
            faceEdges.add(mesh.halfEdgeEdge[prevA]);

            mesh.halfEdgeFace[tailForward] = newFaceA;
            mesh.halfEdgeFace[nextA] = newFaceA;
            mesh.halfEdgeFace[spokeBackwardA] = newFaceA;
            mesh.halfEdgeNext[tailForward] = nextA;
            mesh.halfEdgePrev[nextA] = tailForward;
            mesh.halfEdgeNext[nextA] = spokeBackwardA;
            mesh.halfEdgePrev[spokeBackwardA] = nextA;
            mesh.halfEdgeNext[spokeBackwardA] = tailForward;
            mesh.halfEdgePrev[tailForward] = spokeBackwardA;
            mesh.faceHalfEdge[newFaceA] = tailForward;
            IntIdList newFaceHalfEdges = mesh.faceHalfEdges.get(newFaceA);
            IntIdList newFaceVertices = mesh.faceVertices.get(newFaceA);
            IntIdList newFaceEdges = mesh.faceEdges.get(newFaceA);
            newFaceHalfEdges.add(tailForward);
            newFaceHalfEdges.add(nextA);
            newFaceHalfEdges.add(spokeBackwardA);
            newFaceVertices.add(newVertex);
            newFaceVertices.add(vertexB);
            newFaceVertices.add(oppositeA);
            newFaceEdges.add(tailEdge);
            newFaceEdges.add(mesh.halfEdgeEdge[nextA]);
            newFaceEdges.add(spokeEdgeA);

            mesh.vertexFaces.get(vertexB).removeValue(faceA);
            mesh.vertexFaces.get(vertexB).add(newFaceA);
            mesh.vertexFaces.get(newVertex).add(faceA);
            mesh.vertexFaces.get(newVertex).add(newFaceA);
            mesh.vertexFaces.get(oppositeA).add(newFaceA);
        }

        if (faceB != MeshTopology.NONE) {
            int oppositeB = mesh.halfEdgeVertex[prevB];
            int spokeEdgeB = createEdgePair(mesh, newVertex, oppositeB);
            int spokeForwardB = mesh.edgeHalfEdge[spokeEdgeB];
            int spokeBackwardB = mesh.halfEdgeTwin[spokeForwardB];
            int newFaceB = mesh.createFaceSlot();

            mesh.halfEdgeNext[nextB] = spokeBackwardB;
            mesh.halfEdgePrev[spokeBackwardB] = nextB;
            mesh.halfEdgeNext[spokeBackwardB] = twin;
            mesh.halfEdgePrev[twin] = spokeBackwardB;
            mesh.halfEdgeFace[spokeBackwardB] = faceB;
            mesh.faceHalfEdge[faceB] = twin;
            IntIdList faceHalfEdges = mesh.faceHalfEdges.get(faceB);
            IntIdList faceVertices = mesh.faceVertices.get(faceB);
            IntIdList faceEdges = mesh.faceEdges.get(faceB);
            faceHalfEdges.clear();
            faceVertices.clear();
            faceEdges.clear();
            faceHalfEdges.add(twin);
            faceHalfEdges.add(nextB);
            faceHalfEdges.add(spokeBackwardB);
            faceVertices.add(newVertex);
            faceVertices.add(vertexA);
            faceVertices.add(oppositeB);
            faceEdges.add(edgeId);
            faceEdges.add(mesh.halfEdgeEdge[nextB]);
            faceEdges.add(spokeEdgeB);

            mesh.halfEdgeFace[tailBackward] = newFaceB;
            mesh.halfEdgeFace[spokeForwardB] = newFaceB;
            mesh.halfEdgeFace[prevB] = newFaceB;
            mesh.halfEdgeNext[tailBackward] = spokeForwardB;
            mesh.halfEdgePrev[spokeForwardB] = tailBackward;
            mesh.halfEdgeNext[spokeForwardB] = prevB;
            mesh.halfEdgePrev[prevB] = spokeForwardB;
            mesh.halfEdgeNext[prevB] = tailBackward;
            mesh.halfEdgePrev[tailBackward] = prevB;
            mesh.faceHalfEdge[newFaceB] = tailBackward;
            IntIdList newFaceHalfEdges = mesh.faceHalfEdges.get(newFaceB);
            IntIdList newFaceVertices = mesh.faceVertices.get(newFaceB);
            IntIdList newFaceEdges = mesh.faceEdges.get(newFaceB);
            newFaceHalfEdges.add(tailBackward);
            newFaceHalfEdges.add(spokeForwardB);
            newFaceHalfEdges.add(prevB);
            newFaceVertices.add(vertexB);
            newFaceVertices.add(newVertex);
            newFaceVertices.add(oppositeB);
            newFaceEdges.add(tailEdge);
            newFaceEdges.add(spokeEdgeB);
            newFaceEdges.add(mesh.halfEdgeEdge[prevB]);

            mesh.vertexFaces.get(vertexB).removeValue(faceB);
            mesh.vertexFaces.get(vertexB).add(newFaceB);
            mesh.vertexFaces.get(newVertex).add(faceB);
            mesh.vertexFaces.get(newVertex).add(newFaceB);
            mesh.vertexFaces.get(oppositeB).add(newFaceB);
        }

        if (mesh.vertexOutgoing[vertexB] == MeshTopology.NONE) {
            mesh.vertexOutgoing[vertexB] = mesh.vertexOutgoingHalfEdges.get(vertexB).isEmpty()
                    ? MeshTopology.NONE
                    : mesh.vertexOutgoingHalfEdges.get(vertexB).get(0);
        }
        return newVertex;
    }

    /**
     * Removes an edge that is no longer attached to any face; both half-edges are
     * deactivated and unregistered from the directed-edge map.
     *
     * @param mesh target mesh
     * @param edgeId active edge id with no incident faces
     * @throws InvalidMeshTopologyException if the edge is incomplete or still attached to a face
     */
    public static void removeEdge(HalfEdgeMesh mesh, int edgeId) {

        int firstHalfEdge = mesh.edgeHalfEdge[edgeId];
        int secondHalfEdge = mesh.halfEdgeTwin[firstHalfEdge];
        if (firstHalfEdge == MeshTopology.NONE || secondHalfEdge == MeshTopology.NONE) {
            throw new InvalidMeshTopologyException(EDGE + edgeId + " is incomplete");
        }
        if (mesh.halfEdgeFace[firstHalfEdge] != MeshTopology.NONE || mesh.halfEdgeFace[secondHalfEdge] != MeshTopology.NONE) {
            throw new InvalidMeshTopologyException(EDGE + edgeId + " still belongs to a face");
        }

        unregisterHalfEdge(mesh, firstHalfEdge);
        unregisterHalfEdge(mesh, secondHalfEdge);
        mesh.deactivateEdge(edgeId);
    }

    /**
     * Deactivates an isolated vertex (no incident half-edges, edges, or faces).
     *
     * @param mesh target mesh
     * @param vertexId active vertex id
     * @throws InvalidMeshTopologyException if the vertex is still connected to any topology
     */
    public static void removeVertex(HalfEdgeMesh mesh, int vertexId) {
        if (!mesh.vertexOutgoingHalfEdges.get(vertexId).isEmpty()
                || !mesh.vertexEdges.get(vertexId).isEmpty()
                || !mesh.vertexFaces.get(vertexId).isEmpty()) {
            throw new InvalidMeshTopologyException("Vertex " + vertexId + " is still connected");
        }
        mesh.deactivateVertex(vertexId);
    }

    /**
     * Recomputes per-face and per-vertex normals in place. Face normal is the
     * unit cross of the first two edges; vertex normals accumulate area-weighted
     * face normals (via the unnormalized cross product) and are then normalized.
     *
     * @param mesh target mesh
     */
    public static void computeNormals(HalfEdgeMesh mesh) {
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        Vector3f edgeA = new Vector3f();
        Vector3f edgeB = new Vector3f();
        Vector3f areaNormal = new Vector3f();

        for (int i = 0; i < mesh.vertexCount(); i++) {
            int vertexId = mesh.vertexIdAt(i);
            setVector(mesh.vertexNormals, vertexId, NUM_0, NUM_0, NUM_0);
        }

        for (int i = 0; i < mesh.faceCount(); i++) {
            int faceId = mesh.faceIdAt(i);
            setVector(mesh.faceNormals, faceId, NUM_0, NUM_0, NUM_0);
            if (mesh.faceVertexCount(faceId) < NUM_3) {
                continue;
            }

            mesh.vertexPosition(mesh.faceVertexAt(faceId, 0), p0);
            mesh.vertexPosition(mesh.faceVertexAt(faceId, 1), p1);
            mesh.vertexPosition(mesh.faceVertexAt(faceId, 2), p2);
            edgeA.set(p1).sub(p0);
            edgeB.set(p2).sub(p0);
            edgeA.cross(edgeB, areaNormal);
            if (areaNormal.lengthSquared() == NUM_0) {
                continue;
            }

            Vector3f normalized = new Vector3f(areaNormal).normalize();
            setVector(mesh.faceNormals, faceId, normalized.x, normalized.y, normalized.z);
            for (int j = 0; j < mesh.faceVertexCount(faceId); j++) {
                int vertexId = mesh.faceVertexAt(faceId, j);
                addVector(mesh.vertexNormals, vertexId, areaNormal.x, areaNormal.y, areaNormal.z);
            }
        }

        for (int i = 0; i < mesh.vertexCount(); i++) {
            int vertexId = mesh.vertexIdAt(i);
            int offset = mesh.vertexOffset(vertexId);
            p0.set(mesh.vertexNormals[offset], mesh.vertexNormals[offset + 1], mesh.vertexNormals[offset + 2]);
            if (p0.lengthSquared() > NUM_0) {
                p0.normalize();
                setVector(mesh.vertexNormals, vertexId, p0.x, p0.y, p0.z);
            }
        }
    }

    /**
     * The half-edge form of any mesh representation, the counterpart of
     * {@link ArrayMeshEngine#fromUniformMeshTopology}.
     *
     * @param mesh mesh in either representation
     * @throws IllegalArgumentException if {@code mesh} is null or an unknown representation
     * @return the mesh itself when already half-edge, otherwise a converted copy
     */
    public static HalfEdgeMesh fromMeshTopology(MeshTopology mesh) {
        if (mesh instanceof HalfEdgeMesh halfEdgeMesh) {
            return halfEdgeMesh;
        }
        if (mesh instanceof ArrayMesh arrayMesh) {
            return arrayMesh.toHalfEdgeMesh();
        }
        throw new IllegalArgumentException("No half-edge conversion for "
                + (mesh == null ? "null" : mesh.getClass().getSimpleName()));
    }

    /**
     * Builds a triangle {@link HalfEdgeMesh} from packed positions and a flat triangle
     * index buffer, computing normals.
     *
     * @param positions packed xyz triples
     * @param faceIndices vertex indices in groups of three
     * @throws IllegalArgumentException if {@code positions.length} is not divisible by 3 or
     *         {@code faceIndices.length} is not divisible by 3
     * @return populated half-edge mesh with normals computed
     */
    public static HalfEdgeMesh buildFromIndexedMesh(float[] positions, int[] faceIndices) {
        if (positions.length % HalfEdgeMesh.FLOATS_PER_VERTEX != 0) {
            throw new IllegalArgumentException(POSITION_DATA_MUST_BE_XYZ_TRIPLES);
        }
        if (faceIndices.length % NUM_3 != 0) {
            throw new IllegalArgumentException("Face indices must be triangles");
        }

        int vertexCapacity = positions.length / HalfEdgeMesh.FLOATS_PER_VERTEX;
        int faceCapacity = faceIndices.length / NUM_3;
        HalfEdgeMesh mesh = new HalfEdgeMesh(vertexCapacity, faceIndices.length / 2,
                faceCapacity, faceIndices.length);
        for (int i = 0; i < positions.length; i += HalfEdgeMesh.FLOATS_PER_VERTEX) {
            addVertex(mesh, positions[i], positions[i + 1], positions[i + 2]);
        }
        int[] face = new int[NUM_3];
        for (int i = 0; i < faceIndices.length; i += NUM_3) {
            face[0] = faceIndices[i];
            face[1] = faceIndices[i + 1];
            face[2] = faceIndices[i + 2];
            addFaceInternal(mesh, face, false);
        }
        computeNormals(mesh);
        return mesh;
    }

    /**
     * Pre-sized mesh build for faces of uniform vertex count (3 for triangles, 4 for quads).
     * For closed manifolds the edge/half-edge counts are derived exactly from total face vertices.
     * Does not compute normals — caller must call {@link HalfEdgeMesh#computeNormals()} when ready.
     *
     * @param positions    xyz triples
     * @param faceIndices  flat array of vertex indices, grouped by {@code vertsPerFace}
     * @param vertsPerFace vertices per face (3 or 4)
     * @throws IllegalArgumentException if positions are not xyz triples or the index buffer
     *         is not a multiple of {@code vertsPerFace}
     * @return populated half-edge mesh; caller must invoke {@code computeNormals()} when ready
     */
    public static HalfEdgeMesh bulkAllocate(float[] positions, int[] faceIndices, int vertsPerFace) {
        if (positions.length % HalfEdgeMesh.FLOATS_PER_VERTEX != 0) {
            throw new IllegalArgumentException(POSITION_DATA_MUST_BE_XYZ_TRIPLES);
        }
        if (faceIndices.length % vertsPerFace != 0) {
            throw new IllegalArgumentException("Face indices must be groups of " + vertsPerFace);
        }
        int v = positions.length / HalfEdgeMesh.FLOATS_PER_VERTEX;
        int f = faceIndices.length / vertsPerFace;
        int totalFaceVerts = faceIndices.length;
        int e = totalFaceVerts / 2;
        int he = totalFaceVerts;
        HalfEdgeMesh mesh = new HalfEdgeMesh(v, e, f, he);
        for (int i = 0; i < v; i++) {
            int o = i * HalfEdgeMesh.FLOATS_PER_VERTEX;
            mesh.createVertexSlot(positions[o], positions[o + 1], positions[o + 2]);
        }
        int[] face = new int[vertsPerFace];
        for (int q = 0; q < faceIndices.length; q += vertsPerFace) {
            System.arraycopy(faceIndices, q, face, 0, vertsPerFace);
            addFaceInternal(mesh, face, false);
        }
        return mesh;
    }

    /**
     * Bulk-allocate with mixed polygon sizes. Each face's vertex count is given by
     * {@code faceVertexCounts[i]}, and {@code faceIndicesFlat} packs all faces' indices
     * end-to-end. Use {@link #bulkAllocate} instead when every face has the same size.
     *
     * @param positions packed xyz triples
     * @param faceVertexCounts per-face vertex count (each &ge; 3)
     * @param faceIndicesFlat all face indices concatenated end-to-end
     * @throws IllegalArgumentException if positions are not xyz triples, any face count is
     *         &lt; 3, or the flat buffer length disagrees with the sum of face counts
     * @return populated half-edge mesh; normals are not computed
     */
    public static HalfEdgeMesh bulkAllocateMixed(float[] positions,
                                                 int[] faceVertexCounts,
                                                 int[] faceIndicesFlat) {
        if (positions.length % HalfEdgeMesh.FLOATS_PER_VERTEX != 0) {
            throw new IllegalArgumentException(POSITION_DATA_MUST_BE_XYZ_TRIPLES);
        }
        int totalFaceVerts = 0;
        for (int c : faceVertexCounts) {
            if (c < NUM_3) {
                throw new IllegalArgumentException("Face vertex count must be >= 3, got " + c);
            }
            totalFaceVerts += c;
        }
        if (totalFaceVerts != faceIndicesFlat.length) {
            throw new IllegalArgumentException(
                    "Flat face indices length " + faceIndicesFlat.length
                            + " does not match sum of face vertex counts " + totalFaceVerts);
        }
        int v = positions.length / HalfEdgeMesh.FLOATS_PER_VERTEX;
        int f = faceVertexCounts.length;
        int e = totalFaceVerts / 2;
        int he = totalFaceVerts;
        HalfEdgeMesh mesh = new HalfEdgeMesh(v, e, f, he);
        for (int i = 0; i < v; i++) {
            int o = i * HalfEdgeMesh.FLOATS_PER_VERTEX;
            mesh.createVertexSlot(positions[o], positions[o + 1], positions[o + 2]);
        }
        int cursor = 0;
        for (int fi = 0; fi < f; fi++) {
            int count = faceVertexCounts[fi];
            int[] face = new int[count];
            System.arraycopy(faceIndicesFlat, cursor, face, 0, count);
            addFaceInternal(mesh, face, false);
            cursor += count;
        }
        return mesh;
    }

    /**
     * One level of linear quad subdivision (edge midpoints + face centroids).
     * Matches ArrayMeshEngine.subdivideQuadsOnce for uniform quad meshes.
     *
     * @param src source mesh; must contain only quads (or be empty)
     * @throws IllegalArgumentException if any face is not a quad
     * @throws IllegalStateException if a required edge midpoint is missing during face emission
     * @return new subdivided {@link HalfEdgeMesh} with normals computed
     */
    public static HalfEdgeMesh subdivideQuadsOnce(HalfEdgeMesh src) {
        if (src == null || src.vertexCount() == 0) {
            return new HalfEdgeMesh();
        }
        if (src.faceVertexCount(src.faceIdAt(0)) != NUM_4) {
            throw new IllegalArgumentException("subdivideQuadsOnce requires all faces to be quads");
        }

        int srcV = src.vertexCount();
        int srcE = src.edgeCount();
        int srcF = src.faceCount();
        int outV = srcV + srcE + srcF;
        int outF = srcF * NUM_4;

        HalfEdgeMesh out = new HalfEdgeMesh(outV, srcE * NUM_4, outF, srcE * NUM_4);

        // Copy original vertex positions
        for (int i = 0; i < srcV; i++) {
            int vid = src.vertexIdAt(i);
            Vector3f vp = src.vertexPosition(vid, new Vector3f());
            out.createVertexSlot(vp.x, vp.y, vp.z);
        }

        // Create edge midpoints
        HashMap<Long, Integer> edgeMidMap = new HashMap<>(srcE * NUM_4 / NUM_3 + 1);
        for (int ei = 0; ei < srcE; ei++) {
            int eid = src.edgeIdAt(ei);
            int he = src.edgeHalfEdge(eid);
            int va = src.halfEdgeVertex(he);
            int vb = src.halfEdgeEndVertex(he);
            Vector3f p0 = src.vertexPosition(va, new Vector3f());
            Vector3f p1 = src.vertexPosition(vb, new Vector3f());
            Vector3f mid = new Vector3f().add(p0).add(p1).mul(NUM_0_5);
            int midIdx = srcV + ei;
            out.createVertexSlot(mid.x, mid.y, mid.z);
            edgeMidMap.put(EdgeKey.undirected(va, vb), midIdx);
        }

        // Create face centroids
        Vector3f p = new Vector3f();
        for (int fi = 0; fi < srcF; fi++) {
            int fid = src.faceIdAt(fi);
            p.set(NUM_0, NUM_0, NUM_0);
            for (int k = 0; k < NUM_4; k++) {
                int vidx = src.faceVertexAt(fid, k);
                Vector3f vp = src.vertexPosition(vidx, new Vector3f());
                p.add(vp);
            }
            p.mul(NUM_0_25);
            out.createVertexSlot(p.x, p.y, p.z);
        }

        for (int fi = 0; fi < srcF; fi++) {
            int fid = src.faceIdAt(fi);
            int[] faceVerts = new int[NUM_4];
            for (int k = 0; k < NUM_4; k++) {
                faceVerts[k] = src.faceVertexAt(fid, k);
            }
            int centroid = srcV + srcE + fi;
            for (int k = 0; k < NUM_4; k++) {
                int va = faceVerts[k];
                int vb = faceVerts[(k + 1) % NUM_4];
                int vc = faceVerts[(k + NUM_3) % NUM_4];
                int nva = va;
                Integer midAB = edgeMidMap.get(EdgeKey.undirected(va, vb));
                Integer midCA = edgeMidMap.get(EdgeKey.undirected(vc, va));
                if (midAB == null || midCA == null) {
                    throw new IllegalStateException("missing edge midpoint");
                }
                out.addFace(nva, midAB, centroid, midCA);
            }
        }

        out.computeNormals();
        return out;
    }


    /**
     * Builds GPU-ready interleaved vertex data and a triangulated index buffer
     * for rendering. Each output vertex is 8 floats (xyz + normal xyz + two zero
     * pads) and faces with &gt; 3 vertices are fan-triangulated. Inactive vertex
     * slots are filtered out.
     *
     * @param mesh source mesh
     * @return compiled mesh data including bounds and bounding sphere
     */
    public static HalfEdgeCompiledMeshData compileSurfaceData(HalfEdgeMesh mesh) {
        int[] vertexRemap = new int[mesh.vertexActive.length];
        Arrays.fill(vertexRemap, MeshTopology.NONE);
        float[] vertices = new float[mesh.vertexCount() * NUM_8];

        for (int i = 0; i < mesh.vertexCount(); i++) {
            int vertexId = mesh.vertexIdAt(i);
            vertexRemap[vertexId] = i;
            int sourceOffset = mesh.vertexOffset(vertexId);
            int targetOffset = i * NUM_8;
            vertices[targetOffset] = mesh.vertexPositions[sourceOffset];
            vertices[targetOffset + 1] = mesh.vertexPositions[sourceOffset + 1];
            vertices[targetOffset + 2] = mesh.vertexPositions[sourceOffset + 2];
            vertices[targetOffset + NUM_3] = mesh.vertexNormals[sourceOffset];
            vertices[targetOffset + NUM_4] = mesh.vertexNormals[sourceOffset + 1];
            vertices[targetOffset + NUM_5] = mesh.vertexNormals[sourceOffset + 2];
            vertices[targetOffset + NUM_6] = NUM_0;
            vertices[targetOffset + NUM_7] = NUM_0;
        }

        int triangleCount = 0;
        for (int i = 0; i < mesh.faceCount(); i++) {
            int faceId = mesh.faceIdAt(i);
            triangleCount += Math.max(0, mesh.faceVertexCount(faceId) - 2);
        }

        int[] indices = new int[triangleCount * NUM_3];
        int indexCursor = 0;
        for (int i = 0; i < mesh.faceCount(); i++) {
            int faceId = mesh.faceIdAt(i);
            int faceVertexCount = mesh.faceVertexCount(faceId);
            if (faceVertexCount < NUM_3) {
                continue;
            }
            int anchor = vertexRemap[mesh.faceVertexAt(faceId, 0)];
            for (int j = 1; j < faceVertexCount - 1; j++) {
                indices[indexCursor++] = anchor;
                indices[indexCursor++] = vertexRemap[mesh.faceVertexAt(faceId, j)];
                indices[indexCursor++] = vertexRemap[mesh.faceVertexAt(faceId, j + 1)];
            }
        }

        Vector3f minBounds = mesh.boundsMin(new Vector3f());
        Vector3f maxBounds = mesh.boundsMax(new Vector3f());
        Vector3f center = mesh.center(new Vector3f());
        float radius = mesh.radius();
        return new HalfEdgeCompiledMeshData(
                vertices,
                indices,
                mesh.vertexCount(),
                mesh.faceCount(),
                minBounds,
                maxBounds,
                center,
                radius);
    }

    static int addFaceInternal(HalfEdgeMesh mesh, int[] vertexIds, boolean recomputeNormals) {
        if (vertexIds.length < NUM_3) {
            throw new InvalidMeshTopologyException("A face needs at least three vertices");
        }

        int faceId = mesh.createFaceSlot();
        int[] faceHalfEdges = new int[vertexIds.length];
        for (int i = 0; i < vertexIds.length; i++) {
            int startVertexId = vertexIds[i];
            int endVertexId = vertexIds[(i + 1) % vertexIds.length];
            validateDistinctVertices(startVertexId, endVertexId);

            int halfEdgeId = ensureEdgePair(mesh, startVertexId, endVertexId);
            if (mesh.halfEdgeFace[halfEdgeId] != MeshTopology.NONE) {
                throw new InvalidMeshTopologyException(
                        "Non-manifold or duplicate face edge between " + startVertexId + AND + endVertexId);
            }
            faceHalfEdges[i] = halfEdgeId;
        }

        mesh.faceHalfEdges.get(faceId).clear();
        mesh.faceVertices.get(faceId).clear();
        mesh.faceEdges.get(faceId).clear();
        mesh.faceHalfEdge[faceId] = faceHalfEdges[0];

        for (int i = 0; i < faceHalfEdges.length; i++) {
            int halfEdgeId = faceHalfEdges[i];
            int nextHalfEdgeId = faceHalfEdges[(i + 1) % faceHalfEdges.length];
            int prevHalfEdgeId = faceHalfEdges[(i + faceHalfEdges.length - 1) % faceHalfEdges.length];
            int vertexId = mesh.halfEdgeVertex[halfEdgeId];
            int edgeId = mesh.halfEdgeEdge[halfEdgeId];

            mesh.halfEdgeFace[halfEdgeId] = faceId;
            mesh.halfEdgeNext[halfEdgeId] = nextHalfEdgeId;
            mesh.halfEdgePrev[halfEdgeId] = prevHalfEdgeId;
            mesh.faceHalfEdges.get(faceId).add(halfEdgeId);
            mesh.faceVertices.get(faceId).add(vertexId);
            mesh.faceEdges.get(faceId).add(edgeId);
            mesh.vertexFaces.get(vertexId).add(faceId);
        }

        if (recomputeNormals) {
            computeNormals(mesh);
        }
        return faceId;
    }

    static int ensureEdgePair(HalfEdgeMesh mesh, int startVertexId, int endVertexId) {
        int existing = findHalfEdge(mesh, startVertexId, endVertexId);
        if (existing != MeshTopology.NONE) {
            return existing;
        }
        int edgeId = createEdgePair(mesh, startVertexId, endVertexId);
        return mesh.edgeHalfEdge[edgeId];
    }

    /**
     * The half-edge running from one vertex to another, or {@link MeshTopology#NONE} when the pair
     * is not connected.
     *
     * <p>Walks the source vertex's outgoing adjacency, which holds every outgoing half-edge whether
     * or not it carries a face.
     *
     * @param mesh          mesh to search
     * @param startVertexId source vertex
     * @param endVertexId   destination vertex
     * @return the directed half-edge id, or {@link MeshTopology#NONE}
     */
    static int findHalfEdge(HalfEdgeMesh mesh, int startVertexId, int endVertexId) {
        IntIdList outgoing = mesh.vertexOutgoingHalfEdges.get(startVertexId);
        for (int index = 0; index < outgoing.size(); index++) {
            int halfEdgeId = outgoing.get(index);
            if (mesh.halfEdgeVertex[mesh.halfEdgeTwin[halfEdgeId]] == endVertexId) {
                return halfEdgeId;
            }
        }
        return MeshTopology.NONE;
    }

    static int createEdgePair(HalfEdgeMesh mesh, int startVertexId, int endVertexId) {
        int edgeId = mesh.createEdgeSlot();
        int forwardHalfEdgeId = mesh.createHalfEdgeSlot(startVertexId);
        int backwardHalfEdgeId = mesh.createHalfEdgeSlot(endVertexId);

        mesh.halfEdgeTwin[forwardHalfEdgeId] = backwardHalfEdgeId;
        mesh.halfEdgeTwin[backwardHalfEdgeId] = forwardHalfEdgeId;
        mesh.halfEdgeEdge[forwardHalfEdgeId] = edgeId;
        mesh.halfEdgeEdge[backwardHalfEdgeId] = edgeId;
        mesh.edgeHalfEdge[edgeId] = forwardHalfEdgeId;

        mesh.vertexOutgoingHalfEdges.get(startVertexId).add(forwardHalfEdgeId);
        mesh.vertexOutgoingHalfEdges.get(endVertexId).add(backwardHalfEdgeId);
        mesh.vertexEdges.get(startVertexId).addUnique(edgeId);
        mesh.vertexEdges.get(endVertexId).addUnique(edgeId);
        if (mesh.vertexOutgoing[startVertexId] == MeshTopology.NONE) {
            mesh.vertexOutgoing[startVertexId] = forwardHalfEdgeId;
        }
        if (mesh.vertexOutgoing[endVertexId] == MeshTopology.NONE) {
            mesh.vertexOutgoing[endVertexId] = backwardHalfEdgeId;
        }

        return edgeId;
    }

    static void unregisterHalfEdge(HalfEdgeMesh mesh, int halfEdgeId) {
        int startVertexId = mesh.halfEdgeVertex[halfEdgeId];
        int edgeId = mesh.halfEdgeEdge[halfEdgeId];

        mesh.vertexOutgoingHalfEdges.get(startVertexId).removeValue(halfEdgeId);
        mesh.vertexEdges.get(startVertexId).removeValue(edgeId);
        if (mesh.vertexOutgoing[startVertexId] == halfEdgeId) {
            mesh.vertexOutgoing[startVertexId] = mesh.vertexOutgoingHalfEdges.get(startVertexId).isEmpty()
                    ? MeshTopology.NONE
                    : mesh.vertexOutgoingHalfEdges.get(startVertexId).get(0);
        }
        mesh.deactivateHalfEdge(halfEdgeId);
    }

    static void ensureDirectedEdgeAvailable(HalfEdgeMesh mesh, int startVertexId, int endVertexId) {
        if (findHalfEdge(mesh, startVertexId, endVertexId) != MeshTopology.NONE) {
            throw new InvalidMeshTopologyException(
                    "Edge between " + startVertexId + AND + endVertexId + " already exists");
        }
    }

    static boolean isIsolatedEdge(HalfEdgeMesh mesh, int edgeId) {
        int firstHalfEdge = mesh.edgeHalfEdge[edgeId];
        int secondHalfEdge = mesh.halfEdgeTwin[firstHalfEdge];
        return mesh.halfEdgeFace[firstHalfEdge] == MeshTopology.NONE && mesh.halfEdgeFace[secondHalfEdge] == MeshTopology.NONE;
    }

    static void validateDistinctVertices(int startVertexId, int endVertexId) {
        if (startVertexId == endVertexId) {
            throw new InvalidMeshTopologyException("Degenerate edge on vertex " + startVertexId);
        }
    }

    private static void setVector(float[] target, int id, float x, float y, float z) {
        int offset = id * HalfEdgeMesh.FLOATS_PER_VERTEX;
        target[offset] = x;
        target[offset + 1] = y;
        target[offset + 2] = z;
    }

    private static void addVector(float[] target, int id, float x, float y, float z) {
        int offset = id * HalfEdgeMesh.FLOATS_PER_VERTEX;
        target[offset] += x;
        target[offset + 1] += y;
        target[offset + 2] += z;
    }
}
