package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.MeshCanonicalFingerprint;
import ixdar.geometry.mesh.data.HalfEdgeMesh;

public class MeshCanonicalFingerprintTest {

    @Test
    public void fingerprintStableForUnitCube() {
        float[] positions = new float[] {
                0f, 0f, 0f,
                1f, 0f, 0f,
                1f, 1f, 0f,
                0f, 1f, 0f,
        };
        int[] faces = new int[] {
                0, 1, 2,
                0, 2, 3,
        };
        HalfEdgeMesh mesh = HalfEdgeMesh.buildFromIndexedMesh(positions, faces);
        String h1 = MeshCanonicalFingerprint.sha256Hex(mesh);
        String h2 = MeshCanonicalFingerprint.sha256Hex(mesh);
        assertEquals(h1, h2);
        assertEquals(64, h1.length());
    }
}
