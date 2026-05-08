package ixdar.geometry.mesh.graph;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.FieldContext;
import ixdar.annotations.meshnode.Vector3Field;
import ixdar.geometry.mesh.data.MeshTopology;

/**
 * Vertex-domain field context built from a {@link MeshTopology}.
 */
public final class FieldContextImpl implements MeshFieldContext {
    public static final int NUM_3 = 3;

    private final MeshTopology mesh;
    private Vector3Field positions;
    private Vector3Field normals;

    /**
     * Wraps a {@link MeshTopology} as a vertex-domain field context. Position
     * and normal {@link Vector3Field} views are built lazily on first access.
     *
     * @param mesh source topology; may be {@code null} for an empty context
     */
    public FieldContextImpl(MeshTopology mesh) {
        this.mesh = mesh;
    }

    /** {@inheritDoc}. */
    @Override
    public MeshTopology mesh() {
        return mesh;
    }

    /** {@inheritDoc} Returns vertex count, or 0 when no mesh is bound. */
    @Override
    public int elementCount() {
        if (mesh == null) {
            return 0;
        }
        return mesh.vertexCount();
    }

    /** {@inheritDoc} Built lazily on first call; xyz triples in active-vertex order. */
    @Override
    public Vector3Field positions() {
        if (positions == null) {
            positions = buildPositions();
        }
        return positions;
    }

    /** {@inheritDoc} Built lazily on first call; xyz triples in active-vertex order. */
    @Override
    public Vector3Field normals() {
        if (normals == null) {
            normals = buildNormals();
        }
        return normals;
    }

    private Vector3Field buildPositions() {
        int n = elementCount();
        if (n == 0) {
            return new Vector3Field(new float[0]);
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
        return new Vector3Field(d);
    }

    private Vector3Field buildNormals() {
        int n = elementCount();
        if (n == 0) {
            return new Vector3Field(new float[0]);
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
        return new Vector3Field(d);
    }
}
