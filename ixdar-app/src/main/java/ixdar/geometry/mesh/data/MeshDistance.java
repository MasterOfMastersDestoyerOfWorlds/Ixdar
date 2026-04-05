package ixdar.geometry.mesh.data;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

/**
 * Computes mesh distance metrics between two ArrayMesh instances.
 * Supports Hausdorff distance (worst-case) and Chamfer distance (average).
 */
public final class MeshDistance {

    private MeshDistance() {
    }

    private static final int FLOATS_PER_VERTEX = 3;

    /**
     * Computes the Hausdorff distance between two meshes.
     * The Hausdorff distance is the maximum of the minimum distances from each
     * point in one set to the other set (bidirectional).
     *
     * @param meshA First mesh
     * @param meshB Second mesh
     * @return Hausdorff distance (Euclidean distance)
     */
    public static float hausdorffDistance(ArrayMesh meshA, ArrayMesh meshB) {
        if (meshA == null || meshA.vertexCount() == 0 || meshB == null || meshB.vertexCount() == 0) {
            return Float.MAX_VALUE;
        }

        float[] posA = meshA.copyPositions();
        float[] posB = meshB.copyPositions();

        int vA = meshA.vertexCount();
        int vB = meshB.vertexCount();

        // Compute directed Hausdorff: max over A of min distance to B
        float maxMinDistAtoB = 0f;
        for (int i = 0; i < vA; i++) {
            int oA = i * FLOATS_PER_VERTEX;
            float xA = posA[oA];
            float yA = posA[oA + 1];
            float zA = posA[oA + 2];
            float minDist = Float.MAX_VALUE;
            for (int j = 0; j < vB; j++) {
                int oB = j * FLOATS_PER_VERTEX;
                float dx = xA - posB[oB];
                float dy = yA - posB[oB + 1];
                float dz = zA - posB[oB + 2];
                float distSq = dx * dx + dy * dy + dz * dz;
                if (distSq < minDist) {
                    minDist = (float) Math.sqrt(distSq);
                }
            }
            if (minDist > maxMinDistAtoB) {
                maxMinDistAtoB = minDist;
            }
        }

        // Compute directed Hausdorff: max over B of min distance to A
        float maxMinDistBtoA = 0f;
        for (int j = 0; j < vB; j++) {
            int oB = j * FLOATS_PER_VERTEX;
            float xB = posB[oB];
            float yB = posB[oB + 1];
            float zB = posB[oB + 2];
            float minDist = Float.MAX_VALUE;
            for (int i = 0; i < vA; i++) {
                int oA = i * FLOATS_PER_VERTEX;
                float dx = xB - posA[oA];
                float dy = yB - posA[oA + 1];
                float dz = zB - posA[oA + 2];
                float distSq = dx * dx + dy * dy + dz * dz;
                if (distSq < minDist) {
                    minDist = (float) Math.sqrt(distSq);
                }
            }
            if (minDist > maxMinDistBtoA) {
                maxMinDistBtoA = minDist;
            }
        }

        // Symmetric Hausdorff distance
        return Math.max(maxMinDistAtoB, maxMinDistBtoA);
    }

    /**
     * Computes the Chamfer distance between two meshes.
     * The Chamfer distance is the average of:
     * - Mean minimum distance from A to B
     * - Mean minimum distance from B to A
     *
     * @param meshA First mesh
     * @param meshB Second mesh
     * @return Chamfer distance (Euclidean distance)
     */
    public static float chamferDistance(ArrayMesh meshA, ArrayMesh meshB) {
        if (meshA == null || meshA.vertexCount() == 0 || meshB == null || meshB.vertexCount() == 0) {
            return Float.MAX_VALUE;
        }

        float[] posA = meshA.copyPositions();
        float[] posB = meshB.copyPositions();

        int vA = meshA.vertexCount();
        int vB = meshB.vertexCount();

        // Compute directed Chamfer: mean over A of min distance to B
        double sumMinDistAtoB = 0.0;
        for (int i = 0; i < vA; i++) {
            int oA = i * FLOATS_PER_VERTEX;
            float xA = posA[oA];
            float yA = posA[oA + 1];
            float zA = posA[oA + 2];
            float minDist = Float.MAX_VALUE;
            for (int j = 0; j < vB; j++) {
                int oB = j * FLOATS_PER_VERTEX;
                float dx = xA - posB[oB];
                float dy = yA - posB[oB + 1];
                float dz = zA - posB[oB + 2];
                float distSq = dx * dx + dy * dy + dz * dz;
                if (distSq < minDist) {
                    minDist = (float) Math.sqrt(distSq);
                }
            }
            sumMinDistAtoB += minDist;
        }
        double meanDistAtoB = sumMinDistAtoB / vA;

        // Compute directed Chamfer: mean over B of min distance to A
        double sumMinDistBtoA = 0.0;
        for (int j = 0; j < vB; j++) {
            int oB = j * FLOATS_PER_VERTEX;
            float xB = posB[oB];
            float yB = posB[oB + 1];
            float zB = posB[oB + 2];
            float minDist = Float.MAX_VALUE;
            for (int i = 0; i < vA; i++) {
                int oA = i * FLOATS_PER_VERTEX;
                float dx = xB - posA[oA];
                float dy = yB - posA[oA + 1];
                float dz = zB - posA[oA + 2];
                float distSq = dx * dx + dy * dy + dz * dz;
                if (distSq < minDist) {
                    minDist = (float) Math.sqrt(distSq);
                }
            }
            sumMinDistBtoA += minDist;
        }
        double meanDistBtoA = sumMinDistBtoA / vB;

        // Symmetric Chamfer distance
        return (float) ((meanDistAtoB + meanDistBtoA) / 2.0);
    }

    /**
     * Computes the similarity score between two meshes as a percentage (0-100%).
     * Lower distances indicate higher similarity.
     *
     * The score is computed as: 100 * exp(-distance / scale)
     * where scale is a configurable parameter that determines how quickly
     * the score decays with distance.
     *
     * @param meshA First mesh
     * @param meshB Second mesh
     * @param distanceType Type of distance to use
     * @param scale Characteristic scale for normalization (larger = more tolerant)
     * @return Similarity score in range [0, 100]
     */
    public static float similarityScore(ArrayMesh meshA, ArrayMesh meshB, DistanceType distanceType, float scale) {
        if (meshA == null || meshA.vertexCount() == 0 || meshB == null || meshB.vertexCount() == 0) {
            return 0f;
        }
        if (scale <= 0f) {
            scale = 1f;
        }

        float distance;
        if (distanceType == DistanceType.HAUSDORFF) {
            distance = hausdorffDistance(meshA, meshB);
        } else {
            distance = chamferDistance(meshA, meshB);
        }

        if (distance == Float.MAX_VALUE) {
            return 0f;
        }

        // Exponential decay: score = 100 * exp(-distance / scale)
        // At distance = 0, score = 100
        // At distance = scale, score = 100 * exp(-1) ≈ 36.8
        // At distance = 3*scale, score = 100 * exp(-3) ≈ 5.0
        float score = 100f * (float) Math.exp(-distance / scale);

        // Clamp to [0, 100]
        return Math.max(0f, Math.min(100f, score));
    }

    /**
     * Computes similarity score with default scale (1.0).
     *
     * @param meshA First mesh
     * @param meshB Second mesh
     * @param distanceType Type of distance to use
     * @return Similarity score in range [0, 100]
     */
    public static float similarityScore(ArrayMesh meshA, ArrayMesh meshB, DistanceType distanceType) {
        return similarityScore(meshA, meshB, distanceType, 1.0f);
    }

    /**
     * Computes all metrics between two meshes.
     *
     * @param meshA First mesh
     * @param meshB Second mesh
     * @return MeshMetrics containing all computed values
     */
    public static MeshMetrics computeAllMetrics(ArrayMesh meshA, ArrayMesh meshB) {
        return computeAllMetrics(meshA, meshB, 1.0f);
    }

    /**
     * Computes all metrics between two meshes with custom scale.
     *
     * @param meshA First mesh
     * @param meshB Second mesh
     * @param scale Characteristic scale for similarity normalization
     * @return MeshMetrics containing all computed values
     */
    public static MeshMetrics computeAllMetrics(ArrayMesh meshA, ArrayMesh meshB, float scale) {
        if (meshA == null || meshB == null) {
            return new MeshMetrics(Float.MAX_VALUE, Float.MAX_VALUE, 0f);
        }

        float hausdorff = 0f;
        float chamfer = 0f;

        if (meshA.vertexCount() > 0 && meshB.vertexCount() > 0) {
            hausdorff = hausdorffDistance(meshA, meshB);
            chamfer = chamferDistance(meshA, meshB);
        }

        float score = 0f;
        if (hausdorff != Float.MAX_VALUE) {
            score = 100f * (float) Math.exp(-hausdorff / Math.max(scale, 1e-10f));
            score = Math.max(0f, Math.min(100f, score));
        }

        return new MeshMetrics(hausdorff, chamfer, score);
    }

    /**
     * Extracts all vertex positions from a mesh into a list of Vector3f.
     *
     * @param mesh Input mesh
     * @return List of vertex positions
     */
    public static List<Vector3f> extractVertices(ArrayMesh mesh) {
        List<Vector3f> vertices = new ArrayList<>(mesh.vertexCount());
        float[] pos = mesh.copyPositions();
        Vector3f v = new Vector3f();
        for (int i = 0; i < mesh.vertexCount(); i++) {
            int o = i * FLOATS_PER_VERTEX;
            v.set(pos[o], pos[o + 1], pos[o + 2]);
            vertices.add(new Vector3f(v));
        }
        return vertices;
    }

    /**
     * Computes distance from a point to the nearest vertex in a mesh.
     *
     * @param mesh Mesh to search
     * @param point Query point
     * @return Minimum Euclidean distance
     */
    public static float pointToMeshDistance(ArrayMesh mesh, Vector3f point) {
        if (mesh == null || mesh.vertexCount() == 0) {
            return Float.MAX_VALUE;
        }
        float[] pos = mesh.copyPositions();
        float minDist = Float.MAX_VALUE;
        float px = point.x;
        float py = point.y;
        float pz = point.z;
        for (int i = 0; i < mesh.vertexCount(); i++) {
            int o = i * FLOATS_PER_VERTEX;
            float dx = px - pos[o];
            float dy = py - pos[o + 1];
            float dz = pz - pos[o + 2];
            float distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < minDist) {
                minDist = (float) Math.sqrt(distSq);
            }
        }
        return minDist;
    }

    /**
     * Computes distance from a mesh to a single point (minimum distance from any
     * vertex to the point).
     *
     * @param mesh Mesh to measure
     * @param point Query point
     * @return Minimum Euclidean distance
     */
    public static float meshToPointDistance(ArrayMesh mesh, Vector3f point) {
        return pointToMeshDistance(mesh, point);
    }

    /**
     * Computes the average pairwise distance between corresponding vertices of
     * two meshes (requires same vertex count and ordering).
     *
     * @param meshA First mesh
     * @param meshB Second mesh
     * @return Average Euclidean distance between corresponding vertices
     */
    public static float averageVertexDistance(ArrayMesh meshA, ArrayMesh meshB) {
        if (meshA == null || meshB == null) {
            return Float.MAX_VALUE;
        }
        if (meshA.vertexCount() != meshB.vertexCount() || meshA.vertexCount() == 0) {
            return Float.MAX_VALUE;
        }

        float[] posA = meshA.copyPositions();
        float[] posB = meshB.copyPositions();
        int v = meshA.vertexCount();
        double sumDist = 0.0;

        for (int i = 0; i < v; i++) {
            int o = i * FLOATS_PER_VERTEX;
            float dx = posA[o] - posB[o];
            float dy = posA[o + 1] - posB[o + 1];
            float dz = posA[o + 2] - posB[o + 2];
            sumDist += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        return (float) (sumDist / v);
    }

    /**
     * Type of distance metric to compute.
     */
    public enum DistanceType {
        /** Hausdorff distance (worst-case, symmetric) */
        HAUSDORFF,
        /** Chamfer distance (average, bidirectional) */
        CHAMFER
    }

    /**
     * Container for mesh distance metrics.
     */
    public static class MeshMetrics {
        public final float hausdorffDistance;
        public final float chamferDistance;
        public final float similarityScore;

        public MeshMetrics(float hausdorffDistance, float chamferDistance, float similarityScore) {
            this.hausdorffDistance = hausdorffDistance;
            this.chamferDistance = chamferDistance;
            this.similarityScore = similarityScore;
        }

        @Override
        public String toString() {
            return String.format("MeshMetrics{hausdorff=%.4f, chamfer=%.4f, similarity=%.2f%%}",
                    hausdorffDistance, chamferDistance, similarityScore);
        }
    }
}
