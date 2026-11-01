package ixdar.geometry.mesh.data.sculpt;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * An over-sketched outline, its points view rays from its eye: it drags the contour it reaches all
 * the way onto the stroke, after SilSketch (Zimmermann, Nealen and Alexa 2007).
 */
public final class SilhouetteStroke extends SculptStroke {

    public static final float CONTOUR_DEPTH_SLACK_EDGES = 2f;

    @Override
    public boolean refines() {
        return false;
    }

    @Override
    public float gatherReach() {
        return radius;
    }

    @Override
    public boolean seesThroughCamera() {
        return false;
    }

    /**
     * The contour vertices (vertex-normal facing changes sign across an edge) whose drag onto the
     * stroke, in the plane through the vertex across the view axis, is within reach and inside the
     * stroke's ends. Only contours the eye sees count, and the outline wins over inner contours.
     *
     * @param sum the pass's displacement sum over the refined surface
     * @return how many seeds were written, each with its drag in the sum's seed drags
     */
    @Override
    public int seed(DisplacementSum sum) {
        MeshTopology mesh = sum.mesh;
        int vertexCount = mesh.vertexCount();
        int pointCount = pointWeight.length;
        float[] facingById = new float[mesh.vertexIdBound()];
        Vector3f position = new Vector3f();
        Vector3f normal = new Vector3f();
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            mesh.vertexPosition(vertexId, position).sub(eyeForward[0], eyeForward[1], eyeForward[2]).normalize();
            facingById[vertexId] = mesh.vertexNormal(vertexId, normal).normalize().dot(position);
        }
        sum.ensureSources(vertexCount);
        boolean[] outlineBySeed = new boolean[vertexCount];
        boolean outlineReached = false;
        float[] strokeAtDepth = new float[pointCount * 3];
        float[] best = new float[3];
        int count = 0;
        for (int activeVertex = 0; activeVertex < vertexCount && pointCount >= 2; activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            float facing = facingById[vertexId];
            boolean contour = false;
            for (int adjacency = 0; adjacency < mesh.vertexEdgeCount(vertexId) && !contour; adjacency++) {
                float neighbour = facingById[mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertexId, adjacency), vertexId)];
                contour = (neighbour > 0f) != (facing > 0f) && Math.abs(facing) <= Math.abs(neighbour);
            }
            if (!contour) {
                continue;
            }
            mesh.vertexPosition(vertexId, position);
            float depthAlongView = (position.x - eyeForward[0]) * eyeForward[3]
                    + (position.y - eyeForward[1]) * eyeForward[4] + (position.z - eyeForward[2]) * eyeForward[5];
            for (int ray = 0; ray < pointCount; ray++) {
                float along = pointXyz[ray * 3] * eyeForward[3] + pointXyz[ray * 3 + 1] * eyeForward[4]
                        + pointXyz[ray * 3 + 2] * eyeForward[5];
                for (int axis = 0; axis < 3; axis++) {
                    strokeAtDepth[ray * 3 + axis] = eyeForward[axis] + pointXyz[ray * 3 + axis] * depthAlongView / along;
                }
            }
            float bestDistance = Float.POSITIVE_INFINITY;
            boolean bestClamped = true;
            for (int segment = 0; segment + 1 < pointCount; segment++) {
                int start = segment * 3;
                int end = start + 3;
                float segmentX = strokeAtDepth[end] - strokeAtDepth[start];
                float segmentY = strokeAtDepth[end + 1] - strokeAtDepth[start + 1];
                float segmentZ = strokeAtDepth[end + 2] - strokeAtDepth[start + 2];
                float lengthSquared = segmentX * segmentX + segmentY * segmentY + segmentZ * segmentZ;
                float fraction = lengthSquared == 0f ? 0f : ((position.x - strokeAtDepth[start]) * segmentX
                        + (position.y - strokeAtDepth[start + 1]) * segmentY
                        + (position.z - strokeAtDepth[start + 2]) * segmentZ) / lengthSquared;
                boolean clamped = segment == 0 && fraction < 0f || segment + 2 == pointCount && fraction > 1f;
                fraction = Math.max(0f, Math.min(1f, fraction));
                float nearestX = strokeAtDepth[start] + segmentX * fraction;
                float nearestY = strokeAtDepth[start + 1] + segmentY * fraction;
                float nearestZ = strokeAtDepth[start + 2] + segmentZ * fraction;
                float distance = position.distance(nearestX, nearestY, nearestZ);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestClamped = clamped;
                    best[0] = nearestX - position.x;
                    best[1] = nearestY - position.y;
                    best[2] = nearestZ - position.z;
                }
            }
            float seen = bestClamped || bestDistance > radius ? 0f
                    : sum.visibility(this, activeVertex, null, CONTOUR_DEPTH_SLACK_EDGES);
            if (seen == 0f) {
                continue;
            }
            outlineBySeed[count] = seen == 1f;
            outlineReached |= seen == 1f;
            System.arraycopy(best, 0, sum.seedDragXyz, count * 3, 3);
            sum.sources[count++] = vertexId;
        }
        int kept = 0;
        for (int seed = 0; seed < count; seed++) {
            if (outlineBySeed[seed] || !outlineReached) {
                System.arraycopy(sum.seedDragXyz, seed * 3, sum.seedDragXyz, kept * 3, 3);
                sum.sources[kept++] = sum.sources[seed];
            }
        }
        return kept;
    }

    @Override
    public float kernel(Vector3f position, Vector3f direction, float edgeFraction) {
        return (1f - edgeFraction * edgeFraction) * (1f - edgeFraction * edgeFraction);
    }

    /**
     * Each vertex takes the drag of its nearest seed scaled by the kernel, the largest of every
     * silhouette's; the sum blurs the field afterwards.
     *
     * @param sum          the pass's displacement sum
     * @param activeVertex the vertex, a dense index
     * @param kernel       the falloff weight there
     * @param seed         index of the seed the vertex is nearest
     * @param direction    unused: a drag has its own direction
     * @return the drag's length
     */
    @Override
    public float deposit(DisplacementSum sum, int activeVertex, float kernel, int seed, Vector3f direction) {
        float dragX = sum.seedDragXyz[seed * 3] * kernel;
        float dragY = sum.seedDragXyz[seed * 3 + 1] * kernel;
        float dragZ = sum.seedDragXyz[seed * 3 + 2] * kernel;
        float dragSquared = dragX * dragX + dragY * dragY + dragZ * dragZ;
        float[] drag = sum.dragByActiveVertexXyz;
        int at = activeVertex * 3;
        if (dragSquared > drag[at] * drag[at] + drag[at + 1] * drag[at + 1] + drag[at + 2] * drag[at + 2]) {
            drag[at] = dragX;
            drag[at + 1] = dragY;
            drag[at + 2] = dragZ;
        }
        return (float) Math.sqrt(dragSquared);
    }
}
