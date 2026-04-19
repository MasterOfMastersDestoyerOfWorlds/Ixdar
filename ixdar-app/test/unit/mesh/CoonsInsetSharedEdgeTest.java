package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ixdar.annotations.meshnode.MeshNode;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;

/**
 * Regression for MESH-45: {@code coons_inset_faces} must merge inner verts
 * along cage edges shared by two selected faces, so adjacent inset regions
 * become one topologically connected inner patch. This is the prerequisite
 * for {@code coons_extrude_mesh(region=true)} to see eye-socket / nasal-cavity
 * carves as single regions instead of cheese-grater fragments.
 */
public class CoonsInsetSharedEdgeTest {

    private static MeshTopology run(String dsl, String finalNodeId) throws Exception {
        List<PythonParser.ParsedNode> ast = new PythonParser(new PythonLexer(dsl)).parseGraph();
        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        Object result = runtime.executeGraphResult(ast, finalNodeId, "geometry");
        assertTrue(result instanceof GeometryBundle,
                "expected GeometryBundle; got " + (result == null ? "null" : result.getClass()));
        return ((GeometryBundle) result).mesh();
    }

    /**
     * Collects the 4 vertex ids of every quad that is entirely composed of
     * new inner vertices (dense index >= origVertCount). These are the
     * "inner quads" produced by the inset.
     */
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
                "cage = assign_bezier_handles(geometry=grid.mesh, weight=0.33)",
                "sel_a = select_by_distance(geometry=cage.geometry, point=<-1.0, 0.0, -1.0>, radius=0.4)",
                "sel_b = select_by_distance(geometry=cage.geometry, point=<0.0, 0.0, -1.0>, radius=0.4)",
                "combo = boolean_math(operation=OR, a=sel_a.selection, b=sel_b.selection)",
                "inset = coons_inset_faces(geometry=cage.geometry, selection=combo.value, inset=0.25)",
                ""
        );
        MeshTopology mesh = run(dsl, "inset");
        assertNotNull(mesh);

        // 3x3 grid = 16 orig verts. Two adjacent selected faces that would
        // pre-fix produce 4 + 4 = 8 fresh inner verts now produce 4 + 4 - 2 = 6
        // because the 2 endpoints of the shared cage edge merge.
        int origVertCount = 16;
        assertEquals(22, mesh.vertexCount(),
                "Expected 16 orig + 6 inner verts (2 merged); got " + mesh.vertexCount()
                        + ". Shared-edge merge may not be firing.");

        // The two inner quads must share exactly 2 vertex ids — the merged
        // endpoints of the shared cage edge.
        List<int[]> inners = innerQuads(mesh, origVertCount);
        assertEquals(2, inners.size(), "expected exactly 2 inner quads, got " + inners.size());
        Set<Integer> a = new HashSet<>();
        for (int v : inners.get(0)) a.add(v);
        Set<Integer> b = new HashSet<>();
        for (int v : inners.get(1)) b.add(v);
        Set<Integer> shared = new HashSet<>(a);
        shared.retainAll(b);
        assertEquals(2, shared.size(),
                "Adjacent selected inner quads must share 2 verts; shared=" + shared);
    }

    @Test
    public void singleSelectedFaceUnchanged() throws Exception {
        String dsl = String.join("\n",
                "grid = mesh_grid(u_tiles=3, v_tiles=3, u_tile_size=1.0, v_tile_size=1.0)",
                "cage = assign_bezier_handles(geometry=grid.mesh, weight=0.33)",
                "sel = select_by_distance(geometry=cage.geometry, point=<0.0, 0.0, 0.0>, radius=0.4)",
                "inset = coons_inset_faces(geometry=cage.geometry, selection=sel.selection, inset=0.25)",
                ""
        );
        MeshTopology mesh = run(dsl, "inset");
        assertNotNull(mesh);

        // Single selected face: 16 orig + 4 fresh inner verts.
        assertEquals(20, mesh.vertexCount());

        // Face count: 8 pass-through + 1 inner + 4 side = 13.
        assertEquals(13, mesh.faceCount());
    }

    @Test
    public void nonEdgeAdjacentFacesStillGetOwnInnerVerts() throws Exception {
        // Select face 0 (top-left, centroid -1,0,-1) and face 4 (center, 0,0,0).
        // They share exactly one vertex but no cage edge, so no merge.
        String dsl = String.join("\n",
                "grid = mesh_grid(u_tiles=3, v_tiles=3, u_tile_size=1.0, v_tile_size=1.0)",
                "cage = assign_bezier_handles(geometry=grid.mesh, weight=0.33)",
                "sel_a = select_by_distance(geometry=cage.geometry, point=<-1.0, 0.0, -1.0>, radius=0.4)",
                "sel_b = select_by_distance(geometry=cage.geometry, point=<0.0, 0.0, 0.0>, radius=0.4)",
                "combo = boolean_math(operation=OR, a=sel_a.selection, b=sel_b.selection)",
                "inset = coons_inset_faces(geometry=cage.geometry, selection=combo.value, inset=0.25)",
                ""
        );
        MeshTopology mesh = run(dsl, "inset");
        assertNotNull(mesh);

        // 16 orig + 4 + 4 fresh = 24. No merge along a shared edge — only one
        // shared cage vertex, which is a 2-face corner without an edge between
        // the two faces, so it falls through to per-face local verts.
        assertEquals(24, mesh.vertexCount());

        // Inner quads must be disjoint (no shared vertex id).
        List<int[]> inners = innerQuads(mesh, 16);
        assertEquals(2, inners.size());
        Set<Integer> a = new HashSet<>();
        for (int v : inners.get(0)) a.add(v);
        Set<Integer> b = new HashSet<>();
        for (int v : inners.get(1)) b.add(v);
        Set<Integer> shared = new HashSet<>(a);
        shared.retainAll(b);
        assertTrue(shared.isEmpty(),
                "Non-edge-adjacent inner quads must be disjoint; shared=" + shared);
    }

    @Test
    public void stripOfThreeAdjacentFacesChainsMerges() throws Exception {
        // Three faces in a row: 0 (centroid -1,0,-1), 1 (0,0,-1), 2 (1,0,-1).
        // Face 0↔1 share edge E01, face 1↔2 share edge E12. Both endpoints of
        // each shared edge are 2-face corners (interior of the grid strip
        // AND at the grid boundary — the boundary endpoints of E01 touch
        // only face 0 and face 1, so they're 2-face corners too).
        String dsl = String.join("\n",
                "grid = mesh_grid(u_tiles=3, v_tiles=3, u_tile_size=1.0, v_tile_size=1.0)",
                "cage = assign_bezier_handles(geometry=grid.mesh, weight=0.33)",
                "sel_a = select_by_distance(geometry=cage.geometry, point=<-1.0, 0.0, -1.0>, radius=0.4)",
                "sel_b = select_by_distance(geometry=cage.geometry, point=<0.0, 0.0, -1.0>, radius=0.4)",
                "sel_c = select_by_distance(geometry=cage.geometry, point=<1.0, 0.0, -1.0>, radius=0.4)",
                "ab = boolean_math(operation=OR, a=sel_a.selection, b=sel_b.selection)",
                "combo = boolean_math(operation=OR, a=ab.value, b=sel_c.selection)",
                "inset = coons_inset_faces(geometry=cage.geometry, selection=combo.value, inset=0.25)",
                ""
        );
        MeshTopology mesh = run(dsl, "inset");
        assertNotNull(mesh);

        // 3 face-local corners * 4 far corners is 12 minus duplicates.
        // Strip layout: face 0 provides 4, face 1 adds 2 new (shares 2 with face 0),
        // face 2 adds 2 new (shares 2 with face 1) → 4 + 2 + 2 = 8 merged inner verts.
        // Total mesh: 16 orig + 8 inner = 24.
        assertEquals(24, mesh.vertexCount(),
                "Strip-of-3 inset expected 8 inner verts (merged chain); got "
                        + (mesh.vertexCount() - 16));

        // All three inner quads must be pairwise connected into a single chain.
        List<int[]> inners = innerQuads(mesh, 16);
        assertEquals(3, inners.size());
    }
}
