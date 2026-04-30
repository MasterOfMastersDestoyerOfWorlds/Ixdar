package unit.quadlayout.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.extraction.QVert;
import ixdar.geometry.mesh.quadlayout.extraction.QuadVertexGenerator;

/**
 * PATCH-43 — Stage 1 (QVert generation) tests. Uses hand-built triangle
 * meshes with known integer UV maps so we can count the expected QVerts
 * exactly and assert positions / sources.
 */
public class QuadVertexGeneratorTest {

    /** Single triangle whose UV corners are exactly at integer points
     *  forming a 4x4 right triangle in (u, v) space. Expected QVerts:
     *  3 VERT (corners) + 9 EDGE (3 per edge) + 3 FACE (interior). */
    @Test
    void rightTriangleFourGridFromCorners() {
        // Triangle in 3D: V0=(0,0,0), V1=(1,0,0), V2=(0,1,0).
        // UV: V0=(0,0), V1=(4,0), V2=(0,4).
        ArrayMesh mesh = singleTriangle(
                0f, 0f, 0f,
                1f, 0f, 0f,
                0f, 1f, 0f);
        float[] u = {0f, 4f, 0f};
        float[] v = {0f, 0f, 4f};

        QuadVertexGenerator.Result r = QuadVertexGenerator.generate(mesh, u, v);
        assertNotNull(r);

        // 3 corner integers.
        assertEquals(3, r.vertQVerts().size(),
                "expect 3 vert QVerts at the 3 integer corners");
        // 9 edge integers: 3 along each of 3 edges (interior points only).
        // V0V1 (length 4 along u): integers (1,0)(2,0)(3,0).
        // V0V2 (length 4 along v): integers (0,1)(0,2)(0,3).
        // V1V2 (length 4 hypotenuse): (3,1)(2,2)(1,3).
        assertEquals(9, r.edgeQVerts().size(),
                "expect 9 edge QVerts (3 per edge interior)");
        // Face interior: i+j < 4, i >= 1, j >= 1 → (1,1)(1,2)(2,1)(1,3 excluded
        // since on edge)... actually (1,3),(3,1),(2,2) are on V1V2 edge so they
        // belong to EDGE pass. Inside-triangle integers: (1,1)(1,2)(2,1). 3 total.
        assertEquals(3, r.faceQVerts().size(),
                "expect 3 face QVerts strictly inside the triangle");
        assertEquals(15, r.total(), "total QVert count");

        // Assert no two QVerts share the same (u, v) (dedupe across passes).
        HashSet<Long> seen = new HashSet<>();
        for (QVert q : r.vertQVerts()) seen.add(packUv(q));
        for (QVert q : r.edgeQVerts()) {
            assertTrue(seen.add(packUv(q)),
                    "edge QVert duplicates a vert QVert at (" + q.u() + "," + q.v() + ")");
        }
        for (QVert q : r.faceQVerts()) {
            assertTrue(seen.add(packUv(q)),
                    "face QVert duplicates earlier QVert at (" + q.u() + "," + q.v() + ")");
        }
    }

    /** Triangle whose corners are all non-integer: zero VERT QVerts.
     *  Edges still cross integer iso-lines somewhere though. */
    @Test
    void offsetTriangleProducesNoVertQVerts() {
        ArrayMesh mesh = singleTriangle(
                0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f);
        // UV corners shifted by 0.3 — none are integer.
        float[] u = {0.3f, 3.3f, 0.3f};
        float[] v = {0.3f, 0.3f, 3.3f};

        QuadVertexGenerator.Result r = QuadVertexGenerator.generate(mesh, u, v);
        assertEquals(0, r.vertQVerts().size(), "no integer corners");
        // Edges of THIS triangle never pass through integer (u,v) points:
        // V0V1 has v=0.3, V0V2 has u=0.3, and the hypotenuse has u+v=3.6 —
        // no integer-pair on any of those.
        assertEquals(0, r.edgeQVerts().size(),
                "this triangle's edges contain no integer (u, v) points");
        // Interior integer points: (1,1), (1,2), (2,1) are strictly inside.
        assertEquals(3, r.faceQVerts().size(),
                "interior contains 3 integer (u, v) points");
    }

    /** Tiny triangle inside one integer cell: zero QVerts of any kind. */
    @Test
    void tinyTriangleProducesZeroQVerts() {
        ArrayMesh mesh = singleTriangle(
                0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f);
        // UV corners all inside the unit cell (0,0)-(1,1) and not on edge.
        float[] u = {0.1f, 0.5f, 0.2f};
        float[] v = {0.1f, 0.2f, 0.7f};

        QuadVertexGenerator.Result r = QuadVertexGenerator.generate(mesh, u, v);
        assertEquals(0, r.total(),
                "tiny non-integer triangle produces 0 QVerts; got " + r.total());
    }

    // ---- helpers ----

    private static ArrayMesh singleTriangle(float ax, float ay, float az,
                                            float bx, float by, float bz,
                                            float cx, float cy, float cz) {
        // ArrayMesh requires interior edges. A single triangle has 3 edges,
        // all boundary, zero interior. The cross-field's interior-edge
        // enumeration will be empty — that's OK for the VERT and FACE passes;
        // the EDGE pass just iterates 0 times.
        float[] pos = {ax, ay, az, bx, by, bz, cx, cy, cz};
        int[] faces = {0, 1, 2};
        return new ArrayMesh(pos, null, faces, 3);
    }

    private static long packUv(QVert q) {
        int xi = Math.round(q.u());
        int yi = Math.round(q.v());
        return ((long) (xi & 0xFFFFFFFFL) << 32) | (yi & 0xFFFFFFFFL);
    }
}
