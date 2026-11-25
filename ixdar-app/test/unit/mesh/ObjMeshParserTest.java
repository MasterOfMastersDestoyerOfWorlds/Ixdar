package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.io.ObjMeshParser;
import ixdar.geometry.mesh.data.ops.MeshTopologyAnalysis;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * {@link ObjMeshParser} welds the copies a UV seam splits a vertex into: a unit cube written the
 * way a UV exporter writes it, every face its own chart with its own four vertices, loads as the
 * same closed eight-vertex shell, normals included, as the cube written without seams.
 */
class ObjMeshParserTest {

    private static final String UV_SPLIT_CUBE = """
            v 0 0 0
            v 0 1 0
            v 1 1 0
            v 1 0 0
            v 0 0 1
            v 1 0 1
            v 1 1 1
            v 0 1 1
            v 0 0 0
            v 1 0 0
            v 1 0 1
            v 0 0 1
            v 0 1 0
            v 0 1 1
            v 1 1 1
            v 1 1 0
            v 0 0 0
            v 0 0 1
            v 0 1 1
            v 0 1 0
            v 1 0 0
            v 1 1 0
            v 1 1 1
            v 1 0 1
            vt 0 0
            vt 1 0
            vt 1 1
            vt 0 1
            vt 0 0
            vt 1 0
            vt 1 1
            vt 0 1
            vt 0 0
            vt 1 0
            vt 1 1
            vt 0 1
            vt 0 0
            vt 1 0
            vt 1 1
            vt 0 1
            vt 0 0
            vt 1 0
            vt 1 1
            vt 0 1
            vt 0 0
            vt 1 0
            vt 1 1
            vt 0 1
            vn 0 0 -1
            vn 0 0 -1
            vn 0 0 -1
            vn 0 0 -1
            vn 0 0 1
            vn 0 0 1
            vn 0 0 1
            vn 0 0 1
            vn 0 -1 0
            vn 0 -1 0
            vn 0 -1 0
            vn 0 -1 0
            vn 0 1 0
            vn 0 1 0
            vn 0 1 0
            vn 0 1 0
            vn -1 0 0
            vn -1 0 0
            vn -1 0 0
            vn -1 0 0
            vn 1 0 0
            vn 1 0 0
            vn 1 0 0
            vn 1 0 0
            f 1/1/1 2/2/2 3/3/3 4/4/4
            f 5/5/5 6/6/6 7/7/7 8/8/8
            f 9/9/9 10/10/10 11/11/11 12/12/12
            f 13/13/13 14/14/14 15/15/15 16/16/16
            f 17/17/17 18/18/18 19/19/19 20/20/20
            f 21/21/21 22/22/22 23/23/23 24/24/24
            """;

    private static final String SHARED_CORNER_CUBE = """
            v 0 0 0
            v 1 0 0
            v 1 1 0
            v 0 1 0
            v 0 0 1
            v 1 0 1
            v 1 1 1
            v 0 1 1
            f 1 4 3 2
            f 5 6 7 8
            f 1 2 6 5
            f 4 8 7 3
            f 1 5 8 4
            f 2 3 7 6
            """;

    private static final int CUBE_VERTICES = 8;

    private static final int CUBE_TRIANGLES = 12;

    private static final int CUBE_EDGES = 18;

    private static final int SPHERE_EULER_CHARACTERISTIC = 2;

    private static final float EPSILON = 1e-5f;

    @Test
    void uvSplitCubeLoadsAsOneClosedShell() {
        MeshTopology mesh = assertInstanceOf(HalfEdgeMesh.class, ObjMeshParser.load(UV_SPLIT_CUBE));
        MeshTopologyAnalysis analysis = new MeshTopologyAnalysis();
        analysis.analyze(mesh);

        assertEquals(CUBE_VERTICES, analysis.vertexCount, "each corner's three seam copies weld");
        assertEquals(CUBE_TRIANGLES, analysis.faceCount, "six quads fan into twelve triangles");
        assertEquals(CUBE_EDGES, analysis.edgeCount);
        assertEquals(1, analysis.shellCount, "the six charts join into one shell");
        assertEquals(0, analysis.boundaryEdgeCount, "no seam stays open");
        assertEquals(0, analysis.nonManifoldEdgeCount);
        assertEquals(0, analysis.duplicatePositionVertexCount, "no position is left twice");
        assertEquals(SPHERE_EULER_CHARACTERISTIC, analysis.eulerCharacteristic);
    }

    @Test
    void weldedCornerNormalsMatchTheSharedCube() {
        MeshTopology split = ObjMeshParser.load(UV_SPLIT_CUBE);
        MeshTopology shared = ObjMeshParser.load(SHARED_CORNER_CUBE);
        for (int index = 0; index < split.vertexCount(); index++) {
            int vertex = split.vertexIdAt(index);
            Vector3f position = split.vertexPosition(vertex);
            Vector3f normal = split.vertexNormal(vertex, new Vector3f());
            Vector3f expected = null;
            for (int other = 0; other < shared.vertexCount(); other++) {
                int candidate = shared.vertexIdAt(other);
                if (shared.vertexPosition(candidate).distance(position) < EPSILON) {
                    expected = shared.vertexNormal(candidate, new Vector3f());
                }
            }
            assertNotNull(expected, "the shared cube has a corner at " + position);
            assertEquals(1f, normal.length(), EPSILON, "a welded normal is unit length");
            assertTrue(normal.sub(expected).length() < EPSILON,
                    "the seam copies' flat normals give way to the welded faces' normal at " + position);
        }
    }

    @Test
    void uvSplitCubeMatchesTheSharedCube() {
        MeshTopologyAnalysis split = new MeshTopologyAnalysis();
        split.analyze(ObjMeshParser.load(UV_SPLIT_CUBE));
        MeshTopologyAnalysis shared = new MeshTopologyAnalysis();
        shared.analyze(ObjMeshParser.load(SHARED_CORNER_CUBE));

        assertEquals(shared.vertexCount, split.vertexCount);
        assertEquals(shared.faceCount, split.faceCount);
        assertEquals(shared.edgeCount, split.edgeCount);
        assertEquals(shared.shellCount, split.shellCount);
        assertEquals(shared.boundaryEdgeCount, split.boundaryEdgeCount);
        assertEquals(shared.eulerCharacteristic, split.eulerCharacteristic);
        assertEquals(shared.inwardFaceCount, split.inwardFaceCount, "both wind outward");
    }
}
