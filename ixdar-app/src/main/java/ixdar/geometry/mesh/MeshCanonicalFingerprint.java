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

    private static final Comparator<Vector3f> CORNER_ORDER = Comparator.<Vector3f>comparingDouble(p -> p.x)
            .thenComparingDouble(p -> p.y).thenComparingDouble(p -> p.z);

    private static final Comparator<Vector3f[]> TRIANGLE_ORDER = (a, b) -> {
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
        List<Vector3f[]> triangles = new ArrayList<>(triangleCount(mesh));
        int[] corners = mesh.triangulate(TriangulationRule.FAN, null);
        for (int slot = 0; slot < corners.length; slot += 3) {
            Vector3f[] triangle = new Vector3f[3];
            for (int corner = 0; corner < 3; corner++) {
                Vector3f position = mesh.vertexPosition(corners[slot + corner]);
                triangle[corner] = position.set(roundCoord(position.x), roundCoord(position.y),
                        roundCoord(position.z));
            }
            Arrays.sort(triangle, CORNER_ORDER);
            triangles.add(triangle);
        }
        triangles.sort(TRIANGLE_ORDER);
        ByteBuffer buf = ByteBuffer.allocate(triangles.size() * 3 * 3 * BYTES_PER_FLOAT);
        buf.order(ByteOrder.BIG_ENDIAN);
        for (Vector3f[] tri : triangles) {
            for (Vector3f corner : tri) {
                buf.putFloat(corner.x);
                buf.putFloat(corner.y);
                buf.putFloat(corner.z);
            }
        }
        return sha256HexBytes(buf.array());
    }

    static float roundCoord(float v) {
        return Math.round(v * POSITION_ROUND_SCALE) / POSITION_ROUND_SCALE;
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
