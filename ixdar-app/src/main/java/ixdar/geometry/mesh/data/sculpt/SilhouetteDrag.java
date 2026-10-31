package ixdar.geometry.mesh.data.sculpt;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * An over-sketched outline: the contour vertices a view sees near a drawn stroke, each with the
 * drag perpendicular to the view axis that puts it on the stroke. After SilSketch (Zimmermann,
 * Nealen and Alexa 2007), with the drag spread by the sculpt's geodesic falloff.
 */
public final class SilhouetteDrag {

    public static final String EYE_PREFIX = "eye=";

    public static final String VALUE_SEPARATOR = "/";

    public static final int EYE_AND_FORWARD_VALUES = 6;

    /** Camera position of the view the stroke was drawn in. */
    public final float[] eye = new float[3];

    /** Unit view axis of that camera. */
    public final float[] forward = new float[3];

    /** Contour vertex ids the last {@link #capture} found near the stroke. */
    public int[] sourceVertexIds = new int[0];

    /** How many of {@link #sourceVertexIds} are filled. */
    public int sourceLength;

    /** Drag of each captured vertex onto the stroke, packed xyz, perpendicular to the view axis. */
    public float[] dragXyz = new float[0];

    /**
     * Read the camera from a header flag {@code eye=ex/ey/ez/fx/fy/fz}.
     *
     * @param flag the header flag, eye position then view axis
     * @throws IllegalArgumentException when the flag does not hold six values
     */
    public void parse(String flag) {
        String[] values = flag.substring(EYE_PREFIX.length()).split(VALUE_SEPARATOR);
        if (values.length != EYE_AND_FORWARD_VALUES) {
            throw new IllegalArgumentException("silhouette stroke needs eye=ex/ey/ez/fx/fy/fz: " + flag);
        }
        for (int axis = 0; axis < 3; axis++) {
            eye[axis] = Float.parseFloat(values[axis]);
            forward[axis] = Float.parseFloat(values[axis + 3]);
        }
    }

    /** Reflect the camera through x = 0, for the mirrored copy of a stroke. */
    public void mirrorX() {
        eye[0] = -eye[0];
        forward[0] = -forward[0];
    }

    /**
     * Find the contour vertices whose drag onto the stroke, measured in the plane through the
     * vertex perpendicular to the view axis, is at most {@code reach} and lands inside the stroke.
     *
     * @param mesh       surface whose contour is dragged
     * @param rayXyz     the stroke as unit view-ray directions from {@link #eye}, packed xyz
     * @param rayCount   how many rays {@code rayXyz} holds
     * @param reach      largest drag captured, in world units
     */
    public void capture(MeshTopology mesh, float[] rayXyz, int rayCount, float reach) {
        int vertexCount = mesh.vertexCount();
        float[] facingById = new float[mesh.vertexIdBound()];
        Vector3f position = new Vector3f();
        Vector3f normal = new Vector3f();
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            mesh.vertexPosition(vertexId, position).sub(eye[0], eye[1], eye[2]).normalize();
            facingById[vertexId] = mesh.vertexNormal(vertexId, normal).normalize().dot(position);
        }
        sourceVertexIds = new int[vertexCount];
        dragXyz = new float[vertexCount * 3];
        sourceLength = 0;
        float[] strokeAtDepth = new float[rayCount * 3];
        for (int activeVertex = 0; activeVertex < vertexCount && rayCount >= 2; activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            float facing = facingById[vertexId];
            boolean contour = false;
            for (int adjacency = 0; adjacency < mesh.vertexEdgeCount(vertexId) && !contour; adjacency++) {
                float other = facingById[mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertexId, adjacency), vertexId)];
                contour = (other > 0f) != (facing > 0f) && Math.abs(facing) <= Math.abs(other);
            }
            if (!contour) {
                continue;
            }
            mesh.vertexPosition(vertexId, position);
            float depth = (position.x - eye[0]) * forward[0] + (position.y - eye[1]) * forward[1]
                    + (position.z - eye[2]) * forward[2];
            for (int ray = 0; ray < rayCount; ray++) {
                float along = rayXyz[ray * 3] * forward[0] + rayXyz[ray * 3 + 1] * forward[1]
                        + rayXyz[ray * 3 + 2] * forward[2];
                for (int axis = 0; axis < 3; axis++) {
                    strokeAtDepth[ray * 3 + axis] = eye[axis] + rayXyz[ray * 3 + axis] * depth / along;
                }
            }
            float bestDistance = Float.POSITIVE_INFINITY;
            boolean bestClamped = true;
            float[] best = new float[3];
            for (int segment = 0; segment + 1 < rayCount; segment++) {
                int start = segment * 3;
                int end = start + 3;
                float segmentX = strokeAtDepth[end] - strokeAtDepth[start];
                float segmentY = strokeAtDepth[end + 1] - strokeAtDepth[start + 1];
                float segmentZ = strokeAtDepth[end + 2] - strokeAtDepth[start + 2];
                float lengthSquared = segmentX * segmentX + segmentY * segmentY + segmentZ * segmentZ;
                float fraction = lengthSquared == 0f ? 0f : ((position.x - strokeAtDepth[start]) * segmentX
                        + (position.y - strokeAtDepth[start + 1]) * segmentY
                        + (position.z - strokeAtDepth[start + 2]) * segmentZ) / lengthSquared;
                boolean clamped = segment == 0 && fraction < 0f || segment + 2 == rayCount && fraction > 1f;
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
            if (bestClamped || bestDistance > reach) {
                continue;
            }
            System.arraycopy(best, 0, dragXyz, sourceLength * 3, 3);
            sourceVertexIds[sourceLength++] = vertexId;
        }
    }
}
