package ixdar.geometry.mesh;

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