package ixdar.geometry.mesh;

import org.joml.Quaternionf;
import org.joml.Vector3f;

public class Face {
    public final int index;
    public final Vector3f localV1;
    public final Vector3f localV2;
    public final Vector3f localV3;
    public final Vector3f position;
    public final Quaternionf rotation;
    public final Vector3f normal;

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