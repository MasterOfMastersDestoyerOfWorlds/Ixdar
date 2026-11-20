package ixdar.geometry.mesh.data.sculpt;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.PackedPoints;

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
        Vector3f eyePosition = new Vector3f(eyeForward[0], eyeForward[1], eyeForward[2]);
        Vector3f forward = new Vector3f(eyeForward[3], eyeForward[4], eyeForward[5]);
        Vector3f position = new Vector3f();
        Vector3f normal = new Vector3f();
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            mesh.vertexPosition(vertexId, position).sub(eyePosition).normalize();
            facingById[vertexId] = mesh.vertexNormal(vertexId, normal).normalize().dot(position);
        }
        sum.ensureSources(vertexCount);
        boolean[] outlineBySeed = new boolean[vertexCount];
        boolean outlineReached = false;
        PackedPoints strokeAtDepth = new PackedPoints(pointCount);
        Vector3f best = new Vector3f();
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
            float depthAlongView = new Vector3f(position).sub(eyePosition).dot(forward);
            for (int ray = 0; ray < pointCount; ray++) {
                Vector3f point = pointXyz.at(ray);
                strokeAtDepth.set(ray, point.mul(depthAlongView / point.dot(forward)).add(eyePosition));
            }
            float bestDistance = Float.POSITIVE_INFINITY;
            boolean bestClamped = true;
            for (int segment = 0; segment + 1 < pointCount; segment++) {
                Vector3f nearest = strokeAtDepth.at(segment);
                Vector3f along = strokeAtDepth.at(segment + 1).sub(nearest);
                float lengthSquared = along.lengthSquared();
                float fraction = lengthSquared == 0f ? 0f
                        : new Vector3f(position).sub(nearest).dot(along) / lengthSquared;
                boolean clamped = segment == 0 && fraction < 0f || segment + 2 == pointCount && fraction > 1f;
                nearest.fma(Math.max(0f, Math.min(1f, fraction)), along);
                float distance = position.distance(nearest);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestClamped = clamped;
                    best.set(nearest).sub(position);
                }
            }
            float seen = bestClamped || bestDistance > radius ? 0f
                    : sum.visibility(this, activeVertex, null, CONTOUR_DEPTH_SLACK_EDGES);
            if (seen == 0f) {
                continue;
            }
            outlineBySeed[count] = seen == 1f;
            outlineReached |= seen == 1f;
            sum.seedDragXyz.set(count, best);
            sum.sources[count++] = vertexId;
        }
        int kept = 0;
        PackedPoints drags = sum.seedDragXyz;
        for (int seed = 0; seed < count; seed++) {
            if (outlineBySeed[seed] || !outlineReached) {
                drags.set(kept, drags.get(seed, 0), drags.get(seed, 1), drags.get(seed, 2));
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
        Vector3f drag = sum.seedDragXyz.at(seed).mul(kernel);
        PackedPoints drags = sum.dragByActiveVertexXyz;
        float x = drags.get(activeVertex, 0);
        float y = drags.get(activeVertex, 1);
        float z = drags.get(activeVertex, 2);
        if (drag.lengthSquared() > x * x + y * y + z * z) {
            drags.set(activeVertex, drag);
        }
        return drag.length();
    }
}
