package ixdar.geometry.mesh;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.TriangulationRule;

/**
 * Order-independent SHA-256 of triangle soup: round positions, canonicalize each triangle,
 * sort triangles — stable across vertex ordering.
 */
public final class MeshCanonicalFingerprint {
    public static final int BYTES_PER_FLOAT = 4;

    public static final String ALGORITHM_ID = "ixdar-mesh-fingerprint-v1";

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
        for (int i = 0; i < 3; i++) {
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
     * Triangle count of {@code mesh} once every face is fanned from its first corner.
     *
     * @param mesh mesh to count, or {@code null}
     * @return number of triangles, or 0 when {@code mesh} is null
     */
    public static int triangleCount(MeshTopology mesh) {
        return mesh == null ? 0 : mesh.triangleCount();
    }

    /**
     * Order-independent SHA-256 fingerprint of {@code mesh}: every face fanned into triangles from
     * its first corner, so the same geometry fingerprints the same whatever its ids.
     *
     * @param mesh mesh to fingerprint, or {@code null}
     * @return lowercase hex SHA-256; the empty-input digest when {@code mesh} is null
     */
    public static String sha256Hex(MeshTopology mesh) {
        if (mesh == null) {
            return sha256HexBytes(new byte[0]);
        }
        List<float[][]> triangles = new ArrayList<>(triangleCount(mesh));
        Vector3f scratch = new Vector3f();
        int[] corners = mesh.triangulate(TriangulationRule.FAN, null);
        for (int slot = 0; slot < corners.length; slot += 3) {
            triangles.add(sortCorners(corner(mesh, corners[slot], scratch),
                    corner(mesh, corners[slot + 1], scratch),
                    corner(mesh, corners[slot + 2], scratch)));
        }
        triangles.sort(TRIANGLE_ORDER);
        return sha256HexBytes(encodeTriangles(triangles));
    }

    /**
     * One triangle corner's rounded position.
     *
     * @param mesh mesh to read
     * @param vertexId the corner's vertex
     * @param scratch reusable vector
     * @return the rounded {@code (x, y, z)}
     */
    private static float[] corner(MeshTopology mesh, int vertexId, Vector3f scratch) {
        mesh.vertexPosition(vertexId, scratch);
        return new float[] {
                roundCoord(scratch.x),
                roundCoord(scratch.y),
                roundCoord(scratch.z),
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
        ByteBuffer buf = ByteBuffer.allocate(n * 3 * 3 * BYTES_PER_FLOAT);
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
}
