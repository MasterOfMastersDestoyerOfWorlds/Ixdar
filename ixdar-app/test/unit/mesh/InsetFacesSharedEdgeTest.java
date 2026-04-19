package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;

/**
 * Regression for MESH-46: plain {@code inset_faces} must merge inner verts
 * along cage edges shared by two selected faces, mirroring MESH-45's fix for
 * {@code coons_inset_faces} but using straight-line lerps along the shared
 * cage edge rather than Coons surface evaluations.
 */
public class InsetFacesSharedEdgeTest {

    private static MeshTopology run(String dsl, String finalNodeId) throws Exception {
        List<PythonParser.ParsedNode> ast = new PythonParser(new PythonLexer(dsl)).parseGraph();
        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        Object result = runtime.executeGraphResult(ast, finalNodeId, "geometry");
        assertTrue(result instanceof GeometryBundle,
                "expected GeometryBundle; got " + (result == null ? "null" : result.getClass()));
        return ((GeometryBundle) result).mesh();
    }

    private static List<int[]> innerQuads(MeshTopology mesh, int origVertCount) {
        var out = new java.util.ArrayList<int[]>();
        for (int fi = 0; fi < mesh.faceCount(); fi++) {
            int fid = mesh.faceIdAt(fi);
            if (mesh.faceVertexCount(fid) != 4) continue;
            int[] verts = new int[4];
            boolean allNew = true;
            for (int k = 0; k < 4; k++) {
                int v = mesh.faceVertexAt(fid, k);
                verts[k] = v;
                if (v < origVertCount) {
                    allNew = false;
                    break;
                }
            }
            if (allNew) out.add(verts);
        }
        return out;
    }

    @Test
    public void twoAdjacentFacesShareInnerEdge() throws Exception {
        String dsl = String.join("\n",
                "grid = mesh_grid(u_tiles=3, v_tiles=3, u_tile_size=1.0, v_tile_size=1.0)",
                "sel_a = select_by_distance(geometry=grid.mesh, point=<-1.0, 0.0, -1.0>, radius=0.4)",
                "sel_b = select_by_distance(geometry=grid.mesh, point=<0.0, 0.0, -1.0>, radius=0.4)",
                "combo = boolean_math(operation=OR, a=sel_a.selection, b=sel_b.selection)",
                "inset = inset_faces(geometry=grid.mesh, selection=combo.value, inset=0.25)",
                ""
        );
        MeshTopology mesh = run(dsl, "inset");
        assertNotNull(mesh);

        // 16 orig + 6 inner (2 merged endpoints save 2 verts) = 22.
        assertEquals(22, mesh.vertexCount());

        // The two inner quads must share 2 vertex ids on the shared cage edge.
        List<int[]> inners = innerQuads(mesh, 16);
        assertEquals(2, inners.size());
        Set<Integer> a = new HashSet<>();
        for (int v : inners.get(0)) a.add(v);
        Set<Integer> b = new HashSet<>();
        for (int v : inners.get(1)) b.add(v);
        Set<Integer> shared = new HashSet<>(a);
        shared.retainAll(b);
        assertEquals(2, shared.size(),
                "Adjacent inner quads must share exactly 2 verts; got shared=" + shared);
    }

    @Test
    public void singleSelectedFaceUnchanged() throws Exception {
        String dsl = String.join("\n",
                "grid = mesh_grid(u_tiles=3, v_tiles=3, u_tile_size=1.0, v_tile_size=1.0)",
                "sel = select_by_distance(geometry=grid.mesh, point=<0.0, 0.0, 0.0>, radius=0.4)",
                "inset = inset_faces(geometry=grid.mesh, selection=sel.selection, inset=0.25)",
                ""
        );
        MeshTopology mesh = run(dsl, "inset");
        assertNotNull(mesh);

        // 16 orig + 4 inner = 20.  1 inner quad + 4 side quads + 8 pass-through = 13.
        assertEquals(20, mesh.vertexCount());
        assertEquals(13, mesh.faceCount());
    }

    @Test
    public void nonEdgeAdjacentFacesStillGetOwnInnerVerts() throws Exception {
        // Corner + center share a single vertex but no cage edge → no merge.
        String dsl = String.join("\n",
                "grid = mesh_grid(u_tiles=3, v_tiles=3, u_tile_size=1.0, v_tile_size=1.0)",
                "sel_a = select_by_distance(geometry=grid.mesh, point=<-1.0, 0.0, -1.0>, radius=0.4)",
                "sel_b = select_by_distance(geometry=grid.mesh, point=<0.0, 0.0, 0.0>, radius=0.4)",
                "combo = boolean_math(operation=OR, a=sel_a.selection, b=sel_b.selection)",
                "inset = inset_faces(geometry=grid.mesh, selection=combo.value, inset=0.25)",
                ""
        );
        MeshTopology mesh = run(dsl, "inset");
        assertNotNull(mesh);

        assertEquals(24, mesh.vertexCount());
        List<int[]> inners = innerQuads(mesh, 16);
        assertEquals(2, inners.size());
        Set<Integer> a = new HashSet<>();
        for (int v : inners.get(0)) a.add(v);
        Set<Integer> b = new HashSet<>();
        for (int v : inners.get(1)) b.add(v);
        Set<Integer> shared = new HashSet<>(a);
        shared.retainAll(b);
        assertTrue(shared.isEmpty(),
                "Non-edge-adjacent inner quads must be disjoint; got shared=" + shared);
    }
}
