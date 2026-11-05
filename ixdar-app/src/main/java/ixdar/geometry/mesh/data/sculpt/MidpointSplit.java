package ixdar.geometry.mesh.data.sculpt;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.ops.MeshSmoothing;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * One round of adaptive midpoint subdivision: the marked faces split into four, the region closed
 * by transition faces, new vertices placed on the curved surface. The refined mesh keeps the
 * input's vertices first, in their dense order.
 */
public final class MidpointSplit {

    public static final int QUAD_CORNERS = 4;

    /** Refined vertex id of each split edge's midpoint by input edge id, -1 for an edge left whole. */
    public int[] midpointByEdgeId = new int[0];

    /** Refined vertex id of each input vertex by its id. */
    public int[] refinedById = new int[0];

    /** The input mesh of the last {@link #split}. */
    public MeshTopology input;

    /**
     * Split the marked faces and close the split region: a face left whole may have one split edge
     * (a triangle or quad, closed by transition faces) or two opposite ones (a quad); any other face
     * splits too, worked outward. The rule reads topology only, so it mirrors.
     *
     * @param mesh          the surface to refine
     * @param splitByFaceId faces to split by face id; grown in place by the closure
     * @return the refined surface, normals computed
     */
    public HalfEdgeMesh split(MeshTopology mesh, boolean[] splitByFaceId) {
        input = mesh;
        int[] splitFrontier = new int[mesh.faceCount()];
        int frontierLength = 0;
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            if (splitByFaceId[faceId]) {
                splitFrontier[frontierLength++] = faceId;
            }
        }
        boolean[] splitByEdgeId = new boolean[mesh.edgeIdBound()];
        while (frontierLength > 0) {
            int faceId = splitFrontier[--frontierLength];
            int faceCorners = mesh.faceVertexCount(faceId);
            for (int corner = 0; corner < faceCorners; corner++) {
                splitByEdgeId[mesh.edgeBetween(mesh.faceVertexAt(faceId, corner),
                        mesh.faceVertexAt(faceId, (corner + 1) % faceCorners))] = true;
            }
            for (int corner = 0; corner < faceCorners * 2; corner++) {
                int neighbourId = mesh.edgeFace(mesh.edgeBetween(mesh.faceVertexAt(faceId, corner / 2),
                        mesh.faceVertexAt(faceId, (corner / 2 + 1) % faceCorners)), corner % 2);
                if (neighbourId < 0 || splitByFaceId[neighbourId]) {
                    continue;
                }
                int corners = mesh.faceVertexCount(neighbourId);
                int splitEdges = 0;
                int splitMask = 0;
                for (int neighbourCorner = 0; neighbourCorner < corners; neighbourCorner++) {
                    if (splitByEdgeId[mesh.edgeBetween(mesh.faceVertexAt(neighbourId, neighbourCorner),
                            mesh.faceVertexAt(neighbourId, (neighbourCorner + 1) % corners))]) {
                        splitEdges++;
                        splitMask |= 1 << neighbourCorner;
                    }
                }
                boolean closable = splitEdges <= 1 && corners <= QUAD_CORNERS
                        || corners == QUAD_CORNERS && splitEdges == 2 && (splitMask == 0b0101 || splitMask == 0b1010);
                if (!closable) {
                    splitByFaceId[neighbourId] = true;
                    splitFrontier[frontierLength++] = neighbourId;
                }
            }
        }
        HalfEdgeMesh refined = new HalfEdgeMesh();
        Vector3f position = new Vector3f();
        refinedById = new int[mesh.vertexIdBound()];
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            refinedById[vertexId] = refined.addVertex(mesh.vertexPosition(vertexId, position));
        }
        midpointByEdgeId = new int[mesh.edgeIdBound()];
        Arrays.fill(midpointByEdgeId, -1);
        int[] edgeEnds = new int[2];
        // The refined mesh's ids are its dense indices; split vertices also keep their flat place.
        float[] flatXyz = Arrays.copyOf(mesh.packedPositions(null),
                (mesh.vertexCount() + mesh.edgeCount() + mesh.faceCount()) * 3);
        for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
            int edgeId = mesh.edgeIdAt(activeEdge);
            if (splitByEdgeId[edgeId]) {
                edgeEnds[0] = mesh.halfEdgeVertex(mesh.edgeHalfEdge(edgeId));
                edgeEnds[1] = mesh.halfEdgeEndVertex(mesh.edgeHalfEdge(edgeId));
                midpointByEdgeId[edgeId] = refined.addVertex(curvedCentroid(mesh, edgeEnds, 2, position, flatXyz,
                        refined.vertexIdBound() * 3));
            }
        }
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            int corners = mesh.faceVertexCount(faceId);
            int[] faceVertexIds = new int[corners];
            int[] cornerIds = new int[corners];
            int[] midpoints = new int[corners];
            int splitEdges = 0;
            int firstSplit = -1;
            for (int corner = 0; corner < corners; corner++) {
                int vertexId = mesh.faceVertexAt(faceId, corner);
                faceVertexIds[corner] = vertexId;
                cornerIds[corner] = refinedById[vertexId];
                midpoints[corner] = midpointByEdgeId[mesh.edgeBetween(vertexId,
                        mesh.faceVertexAt(faceId, (corner + 1) % corners))];
                if (midpoints[corner] >= 0) {
                    firstSplit = firstSplit < 0 ? corner : firstSplit;
                    splitEdges++;
                }
            }
            if (splitByFaceId[faceId] && corners == 3) {
                refined.addFace(cornerIds[0], midpoints[0], midpoints[2]);
                refined.addFace(midpoints[0], cornerIds[1], midpoints[1]);
                refined.addFace(midpoints[2], midpoints[1], cornerIds[2]);
                refined.addFace(midpoints[0], midpoints[1], midpoints[2]);
            } else if (splitByFaceId[faceId]) {
                int centerId = refined.addVertex(curvedCentroid(mesh, faceVertexIds, corners, position, flatXyz,
                        refined.vertexIdBound() * 3));
                for (int corner = 0; corner < corners; corner++) {
                    refined.addFace(cornerIds[corner], midpoints[corner], centerId,
                            midpoints[(corner + corners - 1) % corners]);
                }
            } else if (splitEdges == 0) {
                refined.addFace(cornerIds);
            } else {
                int start = cornerIds[firstSplit];
                int end = cornerIds[(firstSplit + 1) % corners];
                int after = cornerIds[(firstSplit + 2) % corners];
                int middle = midpoints[firstSplit];
                if (corners == 3) {
                    refined.addFace(start, middle, after);
                    refined.addFace(middle, end, after);
                } else if (splitEdges == 1) {
                    int last = cornerIds[(firstSplit + 3) % corners];
                    refined.addFace(start, middle, last);
                    refined.addFace(middle, end, after);
                    refined.addFace(middle, after, last);
                } else {
                    int last = cornerIds[(firstSplit + 3) % corners];
                    int opposite = midpoints[(firstSplit + 2) % corners];
                    refined.addFace(start, middle, opposite, last);
                    refined.addFace(middle, end, after, opposite);
                }
            }
        }
        // The curved lift folds faces across a sharp rim, where the corners' normals diverge; there
        // it is pulled back toward the flat split.
        int[] refinedActiveById = new int[refined.vertexIdBound()];
        float[] curvedXyz = refined.packedPositions(refinedActiveById);
        MeshSmoothing.unfold(refined, refinedActiveById, flatXyz, curvedXyz, null, StrokeSculpt.UNFOLD_HALVINGS);
        for (int activeVertex = 0; activeVertex < refined.vertexCount(); activeVertex++) {
            int at = activeVertex * 3;
            refined.setVertexPosition(refined.vertexIdAt(activeVertex), curvedXyz[at], curvedXyz[at + 1],
                    curvedXyz[at + 2]);
        }
        refined.computeNormals();
        return refined;
    }

    /**
     * The input's edge creases on the refined mesh of the last {@link #split}: an edge left whole
     * keeps its crease, a split edge's two halves take it.
     *
     * @param refined the refined mesh {@link #split} returned
     * @param creases crease by input edge id, or null
     * @return crease by refined edge id, or null when {@code creases} is
     */
    public float[] carryCreases(MeshTopology refined, float[] creases) {
        if (creases == null) {
            return null;
        }
        float[] refinedCreases = new float[refined.edgeIdBound()];
        for (int activeEdge = 0; activeEdge < input.edgeCount(); activeEdge++) {
            int edgeId = input.edgeIdAt(activeEdge);
            float crease = edgeId < creases.length ? creases[edgeId] : 0f;
            int halfEdge = input.edgeHalfEdge(edgeId);
            int from = refinedById[input.halfEdgeVertex(halfEdge)];
            int to = refinedById[input.halfEdgeEndVertex(halfEdge)];
            int middle = midpointByEdgeId[edgeId];
            if (crease > 0f && middle < 0) {
                refinedCreases[refined.edgeBetween(from, to)] = crease;
            } else if (crease > 0f) {
                refinedCreases[refined.edgeBetween(from, middle)] = crease;
                refinedCreases[refined.edgeBetween(middle, to)] = crease;
            }
        }
        return refinedCreases;
    }

    /**
     * Place a split vertex on the curved surface its corners sample, not their flat centroid, so
     * refined faces keep the surrounding curvature. Phong tessellation (Boubekeur and Alexa 2008),
     * shape factor one half.
     *
     * @param mesh      surface whose vertex normals give the corners' tangent planes
     * @param vertexIds corners, sparse vertex ids
     * @param count     how many leading entries of {@code vertexIds} are corners
     * @param dest      receives the position
     * @param flatXyz   receives the flat centroid at {@code flatAt}
     * @param flatAt    where in {@code flatXyz} the flat centroid goes
     * @return {@code dest}: the centroid moved by half the mean of its projections onto the corners'
     *         tangent planes
     */
    private static Vector3f curvedCentroid(MeshTopology mesh, int[] vertexIds, int count, Vector3f dest,
            float[] flatXyz, int flatAt) {
        Vector3f corner = new Vector3f();
        Vector3f normal = new Vector3f();
        dest.zero();
        for (int index = 0; index < count; index++) {
            dest.add(mesh.vertexPosition(vertexIds[index], corner));
        }
        dest.div(count);
        float liftX = 0f;
        float liftY = 0f;
        float liftZ = 0f;
        for (int index = 0; index < count; index++) {
            mesh.vertexPosition(vertexIds[index], corner);
            mesh.vertexNormal(vertexIds[index], normal);
            if (normal.length() < SculptStroke.MINIMUM_NORMAL_LENGTH) {
                continue;
            }
            normal.normalize();
            float height = (dest.x - corner.x) * normal.x + (dest.y - corner.y) * normal.y
                    + (dest.z - corner.z) * normal.z;
            liftX -= height * normal.x;
            liftY -= height * normal.y;
            liftZ -= height * normal.z;
        }
        flatXyz[flatAt] = dest.x;
        flatXyz[flatAt + 1] = dest.y;
        flatXyz[flatAt + 2] = dest.z;
        return dest.add(liftX * 0.5f / count, liftY * 0.5f / count, liftZ * 0.5f / count);
    }
}
