package ixdar.geometry.mesh;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.graphics.render.model.HalfEdgeCompiledMeshData;

/**
 * Order-independent SHA-256 of triangle soup: round positions, canonicalize each triangle,
 * sort triangles — stable across vertex ordering.
 */
public final class MeshCanonicalFingerprint {
    public static final int NUM_3 = 3;
    public static final int NUM_8 = 8;
    public static final int NUM_4 = 4;

    public static final String ALGORITHM_ID = "ixdar-mesh-fingerprint-v1";

    /** Must match Python {@code quilt_mesh_fingerprint.py} (scale = 10^5). */
    public static final float POSITION_ROUND_SCALE = 1.0e5f;

    private static final Comparator<float[]> CORNER_ORDER = (p, q) -> {
        int c = Float.compare(p[0], q[0]);
        if (c != 0) {
            return c;
        }
        c = Float.compare(p[1], q[1]);
        if (c != 0) {
            return c;
        }
        return Float.compare(p[2], q[2]);
    };

    private static final Comparator<float[][]> TRIANGLE_ORDER = (a, b) -> {
        for (int i = 0; i < NUM_3; i++) {
            int c = CORNER_ORDER.compare(a[i], b[i]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    };

    private MeshCanonicalFingerprint() {
    }

    /**
     * TODO: document {@code triangleCount}.
     *
     * @param mesh TODO: describe
     * @return TODO: describe
     */
    public static int triangleCount(MeshTopology mesh) {
        if (mesh == null) {
            return 0;
        }
        HalfEdgeCompiledMeshData data = ((HalfEdgeMesh) mesh).compileSurfaceData();
        return data.indices.length / NUM_3;
    }

    /**
     * TODO: document {@code sha256Hex}.
     *
     * @param mesh TODO: describe
     * @return TODO: describe
     */
    public static String sha256Hex(MeshTopology mesh) {
        if (mesh == null) {
            return sha256HexEmpty();
        }
        HalfEdgeCompiledMeshData data = ((HalfEdgeMesh) mesh).compileSurfaceData();
        return sha256HexFromCompiled(data);
    }

    static String sha256HexFromCompiled(HalfEdgeCompiledMeshData data) {
        float[] verts = data.vertices;
        int[] ind = data.indices;
        int triCount = ind.length / NUM_3;
        List<float[][]> triangles = new ArrayList<>(triCount);
        for (int t = 0; t < triCount; t++) {
            int i0 = ind[t * NUM_3];
            int i1 = ind[t * NUM_3 + 1];
            int i2 = ind[t * NUM_3 + 2];
            float[] c0 = corner(verts, i0);
            float[] c1 = corner(verts, i1);
            float[] c2 = corner(verts, i2);
            float[][] sortedCorners = sortCorners(c0, c1, c2);
            triangles.add(sortedCorners);
        }
        triangles.sort(TRIANGLE_ORDER);
        byte[] payload = encodeTriangles(triangles);
        return sha256HexBytes(payload);
    }

    private static float[] corner(float[] verts, int vertexIndex) {
        int o = vertexIndex * NUM_8;
        return new float[] {
                roundCoord(verts[o]),
                roundCoord(verts[o + 1]),
                roundCoord(verts[o + 2]),
        };
    }

    static float roundCoord(float v) {
        return Math.round(v * POSITION_ROUND_SCALE) / POSITION_ROUND_SCALE;
    }

    private static float[][] sortCorners(float[] a, float[] b, float[] c) {
        float[][] t = new float[][] { a, b, c };
        Arrays.sort(t, CORNER_ORDER);
        return t;
    }

    static byte[] encodeTriangles(List<float[][]> sortedTriangles) {
        int n = sortedTriangles.size();
        ByteBuffer buf = ByteBuffer.allocate(n * NUM_3 * NUM_3 * NUM_4);
        buf.order(ByteOrder.BIG_ENDIAN);
        for (float[][] tri : sortedTriangles) {
            for (float[] corner : tri) {
                buf.putFloat(corner[0]);
                buf.putFloat(corner[1]);
                buf.putFloat(corner[2]);
            }
        }
        return buf.array();
    }

    private static String sha256HexBytes(byte[] payload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(payload);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256HexEmpty() {
        return sha256HexBytes(new byte[0]);
    }
}
