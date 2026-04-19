package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ixdar.annotations.meshnode.MeshNode;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.GraphValidator;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;

/**
 * Regression for MESH-43: {@code coons_extrude_mesh(region=true)} must treat
 * each connected component of the selection as its own region. A mask that
 * covers two face clusters sharing a single corner vid (but no edges) must
 * produce two distinct top-vertex sets — no cross-cluster averaging that
 * would fuse unrelated features.
 *
 * <p>Setup: 3x3 grid, then select face 0 (corner) and face 4 (center). They
 * share exactly one vertex at their touching corner. Edge-adjacency considers
 * them disconnected (their shared cage element is a single vertex, not an
 * edge), so the fix allocates one top vertex per (origVid, componentId) —
 * the shared corner ends up with two top verts, one per component.
 */
public class CoonsExtrudeMeshRegionComponentsTest {

    private static final String DSL = String.join("\n",
            "grid = mesh_grid(u_tiles=3, v_tiles=3, u_tile_size=1.0, v_tile_size=1.0)",
            "cage = assign_bezier_handles(geometry=grid.mesh, weight=0.33)",
            "sel_corner = select_by_distance(geometry=cage.geometry, point=<-1.0, 0.0, -1.0>, radius=0.4)",
            "sel_center = select_by_distance(geometry=cage.geometry, point=<0.0, 0.0, 0.0>, radius=0.4)",
            "combo = boolean_math(operation=OR, a=sel_corner.selection, b=sel_center.selection)",
            "ext = coons_extrude_mesh(geometry=cage.geometry, selection=combo.value, offset=0.3, region=true)",
            ""
    );

    @Test
    public void twoDisconnectedComponentsGetSeparateTopVertices() throws Exception {
        MeshTopology mesh = runToMesh();
        assertNotNull(mesh, "coons_extrude_mesh produced null mesh");

        // Grid has 4*4=16 orig verts. Two selected faces × 4 corners each = 8 top
        // verts when components are separated. The old (pre-fix) behavior would
        // have shared the one corner vid between the two components and produced
        // only 7 top verts (total 23). Verify the fix allocates 8 (total 24).
        assertEquals(24, mesh.vertexCount(),
                "Expected 16 orig + 8 top verts (one per (vid, component) pair). "
                        + "Got " + mesh.vertexCount() + " — connected-component "
                        + "splitting regressed?");

        // Face count: 7 pass-through + 2 top + 4 side (corner face has 4 boundary
        // edges, all 4 go to side quads) + 4 side (center face, all 4 boundary) = 17.
        assertEquals(17, mesh.faceCount());
    }

    @Test
    public void regionTrueOnSingleConnectedBlockDoesNotDuplicateVerts() throws Exception {
        String dsl = String.join("\n",
                "grid = mesh_grid(u_tiles=3, v_tiles=3, u_tile_size=1.0, v_tile_size=1.0)",
                "cage = assign_bezier_handles(geometry=grid.mesh, weight=0.33)",
                // select two edge-adjacent faces (face 0 + face 1)
                "sel_a = select_by_distance(geometry=cage.geometry, point=<-1.0, 0.0, -1.0>, radius=0.4)",
                "sel_b = select_by_distance(geometry=cage.geometry, point=<0.0, 0.0, -1.0>, radius=0.4)",
                "combo = boolean_math(operation=OR, a=sel_a.selection, b=sel_b.selection)",
                "ext = coons_extrude_mesh(geometry=cage.geometry, selection=combo.value, offset=0.3, region=true)",
                ""
        );
        MeshTopology mesh = runToMeshFrom(dsl);
        assertNotNull(mesh);

        // Two edge-adjacent selected faces share 2 vertices on the shared edge.
        // Single component → top verts: 4 + 4 - 2 = 6. Total: 16 + 6 = 22.
        assertEquals(22, mesh.vertexCount(),
                "A single connected component must still share top vertices "
                        + "across the shared edge — got " + mesh.vertexCount());
    }

    @Test
    public void topVertexSetsAreDisjointAcrossComponents() throws Exception {
        MeshTopology mesh = runToMesh();
        assertNotNull(mesh);

        // Orig grid has 16 verts (index 0..15). Top verts begin at index 16.
        // For each of the 2 top faces, collect their 4 corner vertex ids.
        // With the fix they must be disjoint (no shared dense index).
        Set<Integer> topVertsOfFirstTopFace = null;
        Set<Integer> topVertsOfSecondTopFace = null;
        int topFacesSeen = 0;
        for (int fi = 0; fi < mesh.faceCount(); fi++) {
            int fid = mesh.faceIdAt(fi);
            if (mesh.faceVertexCount(fid) != 4) continue;
            // A top face's 4 corner verts are all ≥ 16 (in the new-vert range).
            Set<Integer> verts = new HashSet<>();
            boolean allTop = true;
            for (int k = 0; k < 4; k++) {
                int v = mesh.faceVertexAt(fid, k);
                verts.add(v);
                if (v < 16) { allTop = false; break; }
            }
            if (!allTop) continue;
            if (topFacesSeen == 0) topVertsOfFirstTopFace = verts;
            else if (topFacesSeen == 1) topVertsOfSecondTopFace = verts;
            topFacesSeen++;
        }
        assertEquals(2, topFacesSeen, "Expected exactly 2 top faces (one per component)");
        assertNotNull(topVertsOfFirstTopFace);
        assertNotNull(topVertsOfSecondTopFace);
        Set<Integer> intersection = new HashSet<>(topVertsOfFirstTopFace);
        intersection.retainAll(topVertsOfSecondTopFace);
        assertTrue(intersection.isEmpty(),
                "Top vertex sets must be disjoint across components; got overlap " + intersection);
    }

    private static MeshTopology runToMesh() throws Exception {
        return runToMeshFrom(DSL);
    }

    private static MeshTopology runToMeshFrom(String dsl) throws Exception {
        List<PythonParser.ParsedNode> ast = new PythonParser(new PythonLexer(dsl)).parseGraph();
        Map<String, Class<? extends MeshNode>> registry = NodeGraphRuntime.annotationRegistryClasses();
        List<String> errors = GraphValidator.validate(ast, registry);
        assertTrue(errors.isEmpty(), errors::toString);
        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        return runtime.executeGraphToMesh(ast, "ext", "geometry");
    }
}
