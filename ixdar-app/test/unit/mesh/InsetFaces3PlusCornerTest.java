package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;

/**
 * MESH-48: plain inset_faces mirrors MESH-47's 3+ cage-corner emission —
 * cyan dots on each shared edge emanating from the corner + central n-sided
 * fill face. Uses flat-lerp positions (straight along the shared cage edge)
 * instead of Coons surface evaluation.
 */
public class InsetFaces3PlusCornerTest {

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
    public void cubeAllFacesInsetProducesOctagonsAndTriangles() throws Exception {
        String dsl = String.join("\n",
                "base = cube(size=1.0)",
                "inset = inset_faces(geometry=base.mesh, inset=0.2)",
                ""
        );
        MeshTopology mesh = run(dsl, "inset");
        assertNotNull(mesh);

        // Same topology as the Coons variant: 6 octagon inner faces + 8
        // triangle central fills, all side quads dropped (every cube edge
        // is shared between two 3+ corners).
        boolean seenOctagon = false;
        boolean seenTriangle = false;
        for (int fi = 0; fi < mesh.faceCount(); fi++) {
            int fid = mesh.faceIdAt(fi);
            int vpf = mesh.faceVertexCount(fid);
            if (vpf == 8) seenOctagon = true;
            if (vpf == 3) seenTriangle = true;
        }
        assertTrue(seenOctagon, "expected at least one 8-sided octagon inner face");
        assertTrue(seenTriangle, "expected at least one 3-sided central fill face");
    }

    @Test
    public void singleFaceSelectionStaysQuad() throws Exception {
        String dsl = String.join("\n",
                "base = cube(size=1.0)",
                "fidx = input_face_index()",
                "sel = compare(a=fidx.result, b=0.0, mode=EQUAL)",
                "inset = inset_faces(geometry=base.mesh, selection=sel.value, inset=0.2)",
                ""
        );
        MeshTopology mesh = run(dsl, "inset");
        assertNotNull(mesh);
        for (int fi = 0; fi < mesh.faceCount(); fi++) {
            int fid = mesh.faceIdAt(fi);
            int vpf = mesh.faceVertexCount(fid);
            assertTrue(vpf == 4,
                    "single-face selection must keep all faces quads; got vpf=" + vpf);
        }
    }
}
