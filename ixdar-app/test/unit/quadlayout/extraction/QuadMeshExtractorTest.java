package unit.quadlayout.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.extraction.QuadMeshExtractor;

/**
 * PATCH-43 end-to-end tests for the QEx pipeline.
 *
 * <p>Phase A coverage: every quad cell falls strictly inside one triangle.
 * We construct a single big triangle whose UV is shifted off-axis so all
 * integer (u, v) points land inside (FACE source) and the surrounding
 * mesh edges contain none. The extractor should walk through Stages 1-4
 * and emit a small quad mesh from those interior FACE QVerts.
 *
 * <p>Tests of cross-triangle extraction (where quads straddle mesh edges)
 * land alongside Phase B/C of {@code QuadPortGenerator}.
 */
public class QuadMeshExtractorTest {

    /** Off-axis right triangle: UV corners (0.3, 0.3), (5.3, 0.3), (0.3, 5.3).
     *  Interior integer points (i, j) where i + j ≤ 5 (strictly inside the
     *  hypotenuse u + v = 5.6) and i, j ≥ 1:
     *    (1,1) (2,1) (3,1) (4,1)
     *    (1,2) (2,2) (3,2)
     *    (1,3) (2,3)
     *    (1,4)
     *  Quad cells require all 4 corners (i,j),(i+1,j),(i,j+1),(i+1,j+1)
     *  inside, so i + j + 2 ≤ 5 → i + j ≤ 3:
     *    cell (1,1): corners 2+2=4 ≤ 5  ✓
     *    cell (2,1): corners 3+2=5 ≤ 5  ✓
     *    cell (1,2): corners 2+3=5 ≤ 5  ✓
     *  Cells (3,1), (2,2), (1,3): a corner exceeds u+v=5 → not fully inside.
     *  Total: 3 quads expected. */
    @Test
    void offsetRightTriangleProducesThreeQuads() {
        ArrayMesh mesh = singleTriangle(
                0f, 0f, 0f,
                1f, 0f, 0f,
                0f, 1f, 0f);
        float[] u = {0.3f, 5.3f, 0.3f};
        float[] v = {0.3f, 0.3f, 5.3f};

        QuadMeshExtractor.Result r = QuadMeshExtractor.extract(mesh, u, v);
        assertNotNull(r);

        // Stage 1 sanity.
        assertEquals(0, r.qVerts().vertQVerts().size(), "no integer corners");
        assertEquals(0, r.qVerts().edgeQVerts().size(),
                "edges of this offset triangle contain no integer (u, v)");
        assertEquals(10, r.qVerts().faceQVerts().size(),
                "10 integer points strictly inside the offset 5x5 triangle");

        // Stage 2: 4 ports per QVert => 40 ports.
        assertEquals(40, r.ports().size());

        // Stage 3: edges connect adjacent integer points within the triangle.
        // Horizontal pairs: (1,1)-(2,1), (2,1)-(3,1), (3,1)-(4,1),
        //                   (1,2)-(2,2), (2,2)-(3,2),
        //                   (1,3)-(2,3) = 6
        // Vertical pairs:   (1,1)-(1,2), (2,1)-(2,2), (3,1)-(3,2),
        //                   (1,2)-(1,3), (2,2)-(2,3),
        //                   (1,3)-(1,4) = 6
        // Total = 12 QEdges.
        assertEquals(12, r.edges().size(),
                "12 same-face iso-line connections expected");

        // Stage 4: 3 quads (see comment block above).
        assertEquals(3, r.faces().size(),
                "3 quad cells fully inside the triangle");

        // Output quad mesh: 3 quads, vertex count = number of unique QVerts
        // that appear as corners. Touched QVerts:
        //   cell (1,1): (1,1)(2,1)(2,2)(1,2)
        //   cell (2,1): (2,1)(3,1)(3,2)(2,2)
        //   cell (1,2): (1,2)(2,2)(2,3)(1,3)
        // Unique: (1,1)(2,1)(3,1)(1,2)(2,2)(3,2)(1,3)(2,3) = 8 vertices.
        assertNotNull(r.quadMesh(), "should produce a non-null quad mesh");
        assertEquals(3, r.quadMesh().faceCount(),
                "quad mesh face count must match QFace count");
        assertEquals(8, r.quadMesh().vertexCount(),
                "8 unique QVerts span the 3 quad cells");
    }

    /** PATCH-58 — two triangles forming a unit square, with corners at
     *  integer (u, v). Expected: exactly 1 quad spanning both triangles.
     *
     *  <p>Triangulation: T1 = (A, B, C), T2 = (A, C, D) where
     *  <ul>
     *    <li>A = (0, 0, 0), UV = (0, 0)</li>
     *    <li>B = (1, 0, 0), UV = (1, 0)</li>
     *    <li>C = (1, 1, 0), UV = (1, 1)</li>
     *    <li>D = (0, 1, 0), UV = (0, 1)</li>
     *  </ul>
     *
     *  <p>QVerts: 4 VERT QVerts at the corners (all integer); 0 EDGE
     *  QVerts (the diagonal AC has no integer points strictly between
     *  (0, 0) and (1, 1)); 0 FACE QVerts. The four corners are connected
     *  by iso-lines that travel along mesh edges (A→B, B→C, C→D, D→A) —
     *  these require Phase C cross-triangle tracing because the iso-line
     *  from A in +u direction starts in T1 and ends at B in T1 (same
     *  face — but B's port pointing -u must be in T1 too, which the
     *  Phase B VERT-port wedge logic handles).
     */
    @Test
    void twoTriangleSquareProducesOneQuad() {
        // Vertices: A=0, B=1, C=2, D=3.
        float[] pos = {
                0f, 0f, 0f,   // A
                1f, 0f, 0f,   // B
                1f, 1f, 0f,   // C
                0f, 1f, 0f,   // D
        };
        // T1 = A,B,C (CCW from above, normal +z); T2 = A,C,D
        int[] faces = {
                0, 1, 2,
                0, 2, 3,
        };
        ArrayMesh mesh = new ArrayMesh(pos, null, faces, 3);

        // Per-corner UVs matching the 3D positions.
        // T1 corners 0,1,2 map to A=(0,0), B=(1,0), C=(1,1).
        // T2 corners 0,1,2 map to A=(0,0), C=(1,1), D=(0,1).
        float[] u = {0f, 1f, 1f,   // T1
                     0f, 1f, 0f};  // T2
        float[] v = {0f, 0f, 1f,
                     0f, 1f, 1f};

        QuadMeshExtractor.Result r = QuadMeshExtractor.extract(mesh, u, v);
        assertNotNull(r);
        assertEquals(4, r.qVerts().vertQVerts().size(),
                "4 VERT QVerts at the integer corners");
        assertEquals(0, r.qVerts().edgeQVerts().size(),
                "no integer (u, v) strictly inside any mesh edge");
        assertEquals(0, r.qVerts().faceQVerts().size(),
                "no integer (u, v) strictly inside either triangle");
        assertEquals(1, r.faces().size(),
                "exactly 1 quad face spanning both triangles");
        assertNotNull(r.quadMesh());
        assertEquals(1, r.quadMesh().faceCount());
        assertEquals(4, r.quadMesh().vertexCount(),
                "quad has 4 unique corner vertices");
    }

    /** Tiny triangle inside one cell: zero QVerts, zero quads, null mesh. */
    @Test
    void tinyTriangleProducesNoQuadMesh() {
        ArrayMesh mesh = singleTriangle(
                0f, 0f, 0f,
                1f, 0f, 0f,
                0f, 1f, 0f);
        float[] u = {0.1f, 0.5f, 0.2f};
        float[] v = {0.1f, 0.2f, 0.7f};
        QuadMeshExtractor.Result r = QuadMeshExtractor.extract(mesh, u, v);
        assertEquals(0, r.qVerts().total());
        assertEquals(0, r.faces().size());
        assertEquals(null, r.quadMesh());
    }

    private static ArrayMesh singleTriangle(float ax, float ay, float az,
                                            float bx, float by, float bz,
                                            float cx, float cy, float cz) {
        float[] pos = {ax, ay, az, bx, by, bz, cx, cy, cz};
        int[] faces = {0, 1, 2};
        return new ArrayMesh(pos, null, faces, 3);
    }
}
