package ixdar.geometry.mesh.data;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Mutable per-face simulation state: live position/rotation plus a captured
 * base pose (used to interpolate from ideal toward live during animation)
 * and a separate render position.
 */
public class FaceState {
    public final int index;
    public final Vector3f normal;
    public final Vector3f position;
    public final Quaternionf rotation;
    public final Vector3f basePos;
    public final Quaternionf baseRot;
    public final Vector3f renderPos;

    /**
     * Initialise live, base, and render fields all to the supplied ideal pose.
     *
     * @param index identifier within the source mesh
     * @param normal world-space face normal
     * @param idealRot rest-pose rotation; copied into {@link #rotation} and {@link #baseRot}
     * @param idealPos rest-pose position; copied into {@link #position}, {@link #basePos}, and {@link #renderPos}
     */
    public FaceState(int index, Vector3f normal, Quaternionf idealRot, Vector3f idealPos) {
        this.index = index;
        this.normal = normal;
        this.position = idealPos;
        this.rotation = idealRot;
        this.basePos = new Vector3f(idealPos);
        this.baseRot = new Quaternionf(idealRot);
        this.renderPos = new Vector3f(idealPos);
    }
}