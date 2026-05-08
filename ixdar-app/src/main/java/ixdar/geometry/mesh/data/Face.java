package ixdar.geometry.mesh.data;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Per-face record bundling local-space triangle vertices with the ideal
 * world-space pose and normal used by mesh-node simulation.
 */
public class Face {
    public final int index;
    public final Vector3f localV1;
    public final Vector3f localV2;
    public final Vector3f localV3;
    public final Vector3f position;
    public final Quaternionf rotation;
    public final Vector3f normal;

    /**
     * Holds the input-space description of a single face.
     *
     * @param index identifier within the source mesh
     * @param localV1 first triangle vertex in face-local frame
     * @param localV2 second triangle vertex in face-local frame
     * @param localV3 third triangle vertex in face-local frame
     * @param idealPos rest-pose world position assigned to {@link #position}
     * @param idealRot rest-pose world rotation assigned to {@link #rotation}
     * @param normal world-space face normal
     */
    public Face(
            int index,
            Vector3f localV1,
            Vector3f localV2,
            Vector3f localV3,
            Vector3f idealPos,
            Quaternionf idealRot,
            Vector3f normal) {
        this.index = index;
        this.localV1 = localV1;
        this.localV2 = localV2;
        this.localV3 = localV3;
        this.position = idealPos;
        this.rotation = idealRot;
        this.normal = normal;
    }
}