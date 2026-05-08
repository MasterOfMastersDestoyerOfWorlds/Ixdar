package ixdar.geometry.mesh.graph;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.FieldContext;
import ixdar.annotations.meshnode.Vec3Field;
import ixdar.geometry.mesh.data.MeshTopology;

/**
 * Vertex-domain field context built from a {@link MeshTopology}.
 */
public final class FieldContextImpl implements MeshFieldContext {
    public static final int NUM_3 = 3;

    private final MeshTopology mesh;
    private Vec3Field positions;
    private Vec3Field normals;

    /**
     * TODO: document {@code FieldContextImpl}.
     *
     * @param mesh TODO: describe
     */
    public FieldContextImpl(MeshTopology mesh) {
        this.mesh = mesh;
    }

    /**
     * TODO: document {@code mesh}.
     *
     * @return TODO: describe
     */
    @Override
    public MeshTopology mesh() {
        return mesh;
    }

    /**
     * TODO: document {@code elementCount}.
     *
     * @return TODO: describe
     */
    @Override
    public int elementCount() {
        if (mesh == null) {
            return 0;
        }
        return mesh.vertexCount();
    }

    /**
     * TODO: document {@code positions}.
     *
     * @return TODO: describe
     */
    @Override
    public Vec3Field positions() {
        if (positions == null) {
            positions = buildPositions();
        }
        return positions;
    }

    /**
     * TODO: document {@code normals}.
     *
     * @return TODO: describe
     */
    @Override
    public Vec3Field normals() {
        if (normals == null) {
            normals = buildNormals();
        }
        return normals;
    }

    private Vec3Field buildPositions() {
        int n = elementCount();
        if (n == 0) {
            return new Vec3Field(new float[0]);
        }
        float[] d = new float[n * NUM_3];
        Vector3f tmp = new Vector3f();
        for (int i = 0; i < n; i++) {
            int vid = mesh.vertexIdAt(i);
            mesh.vertexPosition(vid, tmp);
            d[NUM_3 * i] = tmp.x;
            d[NUM_3 * i + 1] = tmp.y;
            d[NUM_3 * i + 2] = tmp.z;
        }
        return new Vec3Field(d);
    }

    private Vec3Field buildNormals() {
        int n = elementCount();
        if (n == 0) {
            return new Vec3Field(new float[0]);
        }
        float[] d = new float[n * NUM_3];
        Vector3f tmp = new Vector3f();
        for (int i = 0; i < n; i++) {
            int vid = mesh.vertexIdAt(i);
            mesh.vertexNormal(vid, tmp);
            d[NUM_3 * i] = tmp.x;
            d[NUM_3 * i + 1] = tmp.y;
            d[NUM_3 * i + 2] = tmp.z;
        }
        return new Vec3Field(d);
    }
}
