package ixdar.geometry.mesh.data;

import org.joml.Quaternionf;
import org.joml.Vector3f;

public class FaceState {
    public final int index;
    public final Vector3f normal;
    public final Vector3f position;
    public final Quaternionf rotation;
    public final Vector3f basePos;
    public final Quaternionf baseRot;
    public final Vector3f renderPos;

    /**
     * TODO: document {@code FaceState}.
     *
     * @param index TODO: describe
     * @param normal TODO: describe
     * @param idealRot TODO: describe
     * @param idealPos TODO: describe
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