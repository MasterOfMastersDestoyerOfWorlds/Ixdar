package ixdar.geometry.mesh.data.ops;

import java.util.stream.IntStream;

import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.PackedPoints;

/**
 * The folds of a surface, the one fold test quad_layout's refusal and {@link MeshSmoothing#unfold}
 * share: a face's unit normal against the summed unit normals around its corners. Kept current
 * face by face as vertices move.
 */
public final class MeshFolds {

    public static final float TURNED_OVER_COSINE = 0.5f;

    /** The surface's topology. */
    public final MeshTopology mesh;

    /** Dense vertex index by sparse vertex id. */
    public final int[] activeOf;

    /** Unit (Newell) normal of each face, by face id, at the latest positions. */
    public final PackedPoints normalByFaceId;

    /** Each vertex's summed face normals at the start positions, by dense index. */
    public final PackedPoints startCornerXyz;

    /** Each vertex's summed face normals at the latest positions, by dense index. */
    public final PackedPoints cornerXyz;

    /**
     * Which faces fail at the latest positions, by face id: folded against their own corners, or
     * turned more than 120 degrees against their corners at the start.
     */
    public final boolean[] failedByFaceId;

    /** Which faces fold at the start positions, by face id. */
    public final boolean[] foldedAtStart;

    /** Visit stamp of each face id for the current {@link #update}. */
    private final int[] stampByFaceId;

    /** Visit stamp of each dense vertex for the current {@link #update}. */
    private final int[] stampByVertex;

    /** Dense vertices whose summed normal the current {@link #update} recomputes. */
    private final int[] refreshed;

    /** Where each dense vertex's faces start in {@link #vertexFaces}; the next vertex's start ends them. */
    private final int[] vertexFaceStart;

    /** Face ids around each dense vertex, in the mesh's order, end to end. */
    private final int[] vertexFaces;

    /** Where each face id's corners start in {@link #faceCorners}; the next id's start ends them. */
    private final int[] faceCornerStart;

    /** Each face's corners as dense vertex indices, in the mesh's order, end to end by face id. */
    private final int[] faceCorners;

    /** The last stamp handed out. */
    private int stamp;

    /**
     * The folds of a surface at its start positions.
     *
     * @param mesh     the surface's topology
     * @param activeOf dense vertex index by sparse vertex id
     * @param startXyz positions by dense vertex index; may hold more points than the mesh's vertices
     */
    public MeshFolds(MeshTopology mesh, int[] activeOf, PackedPoints startXyz) {
        this.mesh = mesh;
        this.activeOf = activeOf;
        int vertexCount = mesh.vertexCount();
        normalByFaceId = new PackedPoints(mesh.faceIdBound());
        cornerXyz = new PackedPoints(vertexCount);
        failedByFaceId = new boolean[mesh.faceIdBound()];
        stampByFaceId = new int[mesh.faceIdBound()];
        stampByVertex = new int[vertexCount];
        refreshed = new int[vertexCount];
        vertexFaceStart = new int[vertexCount + 1];
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            vertexFaceStart[activeVertex + 1] = vertexFaceStart[activeVertex]
                    + mesh.vertexFaceCount(mesh.vertexIdAt(activeVertex));
        }
        vertexFaces = new int[vertexFaceStart[vertexCount]];
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            for (int slot = 0; slot < vertexFaceStart[activeVertex + 1] - vertexFaceStart[activeVertex]; slot++) {
                vertexFaces[vertexFaceStart[activeVertex] + slot] = mesh.vertexFaceAt(vertexId, slot);
            }
        }
        faceCornerStart = new int[mesh.faceIdBound() + 1];
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            faceCornerStart[faceId + 1] = mesh.faceVertexCount(faceId);
        }
        for (int faceId = 0; faceId < mesh.faceIdBound(); faceId++) {
            faceCornerStart[faceId + 1] += faceCornerStart[faceId];
        }
        faceCorners = new int[faceCornerStart[mesh.faceIdBound()]];
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            for (int corner = 0; corner < faceCornerStart[faceId + 1] - faceCornerStart[faceId]; corner++) {
                faceCorners[faceCornerStart[faceId] + corner] = activeOf[mesh.faceVertexAt(faceId, corner)];
            }
        }
        startCornerXyz = new PackedPoints(vertexCount);
        update(startXyz, IntStream.range(0, vertexCount).toArray(), vertexCount);
        startCornerXyz.copyFrom(cornerXyz);
        foldedAtStart = failedByFaceId.clone();
    }

    /**
     * Bring {@link #failedByFaceId} to new positions where only some vertices moved: only faces whose
     * normal or a corner's normal changed are tested again.
     *
     * @param xyz           positions by dense vertex index
     * @param changed       dense indices of the vertices that moved since the last update or the start
     * @param changedLength how many leading entries of {@code changed} are in use
     */
    public void update(PackedPoints xyz, int[] changed, int changedLength) {
        int normalStamp = ++stamp;
        int refreshedLength = 0;
        for (int index = 0; index < changedLength; index++) {
            int vertex = changed[index];
            for (int slot = vertexFaceStart[vertex]; slot < vertexFaceStart[vertex + 1]; slot++) {
                int faceId = vertexFaces[slot];
                if (stampByFaceId[faceId] == normalStamp) {
                    continue;
                }
                stampByFaceId[faceId] = normalStamp;
                int first = faceCornerStart[faceId];
                int size = faceCornerStart[faceId + 1] - first;
                float normalX = 0f;
                float normalY = 0f;
                float normalZ = 0f;
                for (int corner = 0; corner < size; corner++) {
                    int cornerVertex = faceCorners[first + corner];
                    int next = faceCorners[first + (corner + 1) % size];
                    float fromX = xyz.get(cornerVertex, 0);
                    float fromY = xyz.get(cornerVertex, 1);
                    float fromZ = xyz.get(cornerVertex, 2);
                    float toX = xyz.get(next, 0);
                    float toY = xyz.get(next, 1);
                    float toZ = xyz.get(next, 2);
                    normalX += (fromY - toY) * (fromZ + toZ);
                    normalY += (fromZ - toZ) * (fromX + toX);
                    normalZ += (fromX - toX) * (fromY + toY);
                    if (stampByVertex[cornerVertex] != normalStamp) {
                        stampByVertex[cornerVertex] = normalStamp;
                        refreshed[refreshedLength++] = cornerVertex;
                    }
                }
                float length = (float) Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
                float scale = length > 0f ? 1f / length : 0f;
                normalByFaceId.set(faceId, normalX * scale, normalY * scale, normalZ * scale);
            }
        }
        for (int index = 0; index < refreshedLength; index++) {
            int vertex = refreshed[index];
            float sumX = 0f;
            float sumY = 0f;
            float sumZ = 0f;
            for (int slot = vertexFaceStart[vertex]; slot < vertexFaceStart[vertex + 1]; slot++) {
                int faceId = vertexFaces[slot];
                sumX += normalByFaceId.get(faceId, 0);
                sumY += normalByFaceId.get(faceId, 1);
                sumZ += normalByFaceId.get(faceId, 2);
            }
            cornerXyz.set(vertex, sumX, sumY, sumZ);
        }
        int foldStamp = ++stamp;
        for (int index = 0; index < refreshedLength; index++) {
            int vertex = refreshed[index];
            for (int slot = vertexFaceStart[vertex]; slot < vertexFaceStart[vertex + 1]; slot++) {
                int faceId = vertexFaces[slot];
                if (stampByFaceId[faceId] != foldStamp) {
                    stampByFaceId[faceId] = foldStamp;
                    failedByFaceId[faceId] = folds(faceId, cornerXyz, 0f)
                            || folds(faceId, startCornerXyz, TURNED_OVER_COSINE);
                }
            }
        }
    }

    /**
     * Whether one face folds back: its normal points against the sum of its corners' normals.
     *
     * @param faceId        the face
     * @param corners       vertex normals by dense index, the face's own state's or the start's
     * @param againstCosine how far against counts: 0 past a right angle, 0.5 past 120 degrees
     * @return true when folded
     */
    private boolean folds(int faceId, PackedPoints corners, float againstCosine) {
        float sumX = 0f;
        float sumY = 0f;
        float sumZ = 0f;
        for (int slot = faceCornerStart[faceId]; slot < faceCornerStart[faceId + 1]; slot++) {
            int vertex = faceCorners[slot];
            sumX += corners.get(vertex, 0);
            sumY += corners.get(vertex, 1);
            sumZ += corners.get(vertex, 2);
        }
        float alignment = normalByFaceId.get(faceId, 0) * sumX + normalByFaceId.get(faceId, 1) * sumY
                + normalByFaceId.get(faceId, 2) * sumZ;
        return alignment < -againstCosine * (float) Math.sqrt(sumX * sumX + sumY * sumY + sumZ * sumZ);
    }
}
