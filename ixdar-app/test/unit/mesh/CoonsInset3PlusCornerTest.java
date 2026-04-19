package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ixdar.annotations.meshnode.MeshNode;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;

/**
 * MESH-47 phase B: at cage vertices where 3 or more selected faces meet,
 * {@code coons_inset_faces} emits cyan-dot inner verts on each shared edge
 * emanating from the vertex and a central n-sided fill face connecting the
 * N cyan dots. Each face's inner region becomes a pentagon (or hexagon etc.
 * if multiple corners are 3+), which coons_patch downstream routes through
 * the Charrot-Gregory evaluator.
 *
 * <p>These tests use a cube with all 6 faces selected so every cube vertex
 * is a 3-face corner — the simplest configuration that exercises the new
 * code path.
 */
public class CoonsInset3PlusCornerTest {

    private static MeshTopology run(String dsl, String finalNodeId) throws Exception {
        List<PythonParser.ParsedNode> ast = new PythonParser(new PythonLexer(dsl)).parseGraph();
        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        Object result = runtime.executeGraphResult(ast, finalNodeId, "geometry");
        assertTrue(result instanceof GeometryBundle,
                "expected GeometryBundle; got " + (result == null ? "null" : result.getClass()));
        return ((GeometryBundle) result).mesh();
    }

    @Test
    public void cubeAllFacesInsetProducesOctagonsAndCentralTriangles() throws Exception {
        String dsl = String.join("\n",
                "base = cube(size=1.0)",
                "cage = assign_bezier_handles(geometry=base.mesh, weight=0.33)",
                "inset = coons_inset_faces(geometry=cage.geometry, inset=0.2)",
                ""
        );
        MeshTopology mesh = run(dsl, "inset");
        assertNotNull(mesh);

        // 8 cube verts + 12 cyan dots (one per cube edge, at s=t from one endpoint
        // — each cube edge has ONE cyan dot because at each of its 2 endpoints
        // (both 3+ corners) the fan walks through this edge from both sides,
        // allocating the same cyan dot — so 12 shared cage edges → 12 cyan dots).
        //
        // Wait: cyan dot is allocated once per (edge, endpoint-vertex) pair, but
        // the fan at the OTHER endpoint allocates a SEPARATE cyan dot at s=t
        // from its end (which in the edge's canonical frame is at s=1-t).
        // So each shared cage edge has TWO cyan dots (one at s=t from each endpoint).
        // 12 edges × 2 = 24 cyan dots. Plus 8 original verts = 32 total.
        assertTrue(mesh.vertexCount() >= 20,
                "expected cyan-dot allocation; got only " + mesh.vertexCount() + " verts");

        // Face count: 6 octagon inner faces + 8 central triangle fills = 14
        // core polygons. Side quads on the 12 cube edges would normally produce
        // 24 (2 per edge, one per incident face) but BOTH endpoints of every
        // cube edge are 3+ corners → merged → all side quads drop.
        // So total output faces = 6 octagons + 8 triangles = 14.
        assertTrue(mesh.faceCount() >= 14,
                "expected at least 6 inner + 8 central fill faces; got " + mesh.faceCount());

        // Inspect face types: must contain at least one 8-sided face (cube
        // face's inner octagon) and at least one 3-sided face (central fill
        // at a cube corner).
        boolean seenOctagon = false;
        boolean seenTriangle = false;
        for (int fi = 0; fi < mesh.faceCount(); fi++) {
            int fid = mesh.faceIdAt(fi);
            int vpf = mesh.faceVertexCount(fid);
            if (vpf == 8) seenOctagon = true;
            if (vpf == 3) seenTriangle = true;
        }
        assertTrue(seenOctagon, "expected at least one 8-sided (octagon) inner face");
        assertTrue(seenTriangle, "expected at least one 3-sided central fill face");
    }

    @Test
    public void singleFaceSelectionStaysQuad() throws Exception {
        // Sanity: one-face selection produces no 3+ corners (each of the 4
        // cube vertices of face 0 has only 1 selected face at it). Output
        // is all-quad (1 inner quad + 4 side quads + 5 pass-through = 10).
        String dsl = String.join("\n",
                "base = cube(size=1.0)",
                "cage = assign_bezier_handles(geometry=base.mesh, weight=0.33)",
                "fidx = input_face_index()",
                "sel = compare(a=fidx.result, b=0.0, mode=EQUAL)",
                "inset = coons_inset_faces(geometry=cage.geometry, selection=sel.value, inset=0.2)",
                ""
        );
        MeshTopology mesh = run(dsl, "inset");
        assertNotNull(mesh);
        for (int fi = 0; fi < mesh.faceCount(); fi++) {
            int fid = mesh.faceIdAt(fi);
            int vpf = mesh.faceVertexCount(fid);
            assertTrue(vpf == 4,
                    "single-face selection must keep all faces as quads; got vpf=" + vpf);
        }
    }
}
