package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.nodes.data.TagGeometryNode;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;

/**
 * Regression for DSL-46: the parser's LHS assignment id is threaded into
 * {@code NodeGraphRuntime}, and any node that exposes {@code generated} +
 * {@code geometry} outputs gets its newly-created faces auto-tagged with that
 * LHS name. Feedback loops downstream (mesh_compare_regions, .tags.json
 * sidecar) can then report per-feature error using names the author wrote,
 * rather than opaque spatial bins.
 */
public class AutoTagLhsTest {

    private static GeometryBundle run(String dsl, String finalNodeId) throws Exception {
        List<PythonParser.ParsedNode> ast = new PythonParser(new PythonLexer(dsl)).parseGraph();
        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        Object result = runtime.executeGraphResult(ast, finalNodeId, "geometry");
        assertTrue(result instanceof GeometryBundle,
                "expected GeometryBundle, got " + (result == null ? "null" : result.getClass()));
        return (GeometryBundle) result;
    }

    @Test
    public void lhsNameAppearsInTagsSlot() throws Exception {
        String dsl = String.join("\n",
                "base = cube(size=1.0)",
                "cage = assign_bezier_handles(geometry=base.mesh, weight=0.33)",
                "eyes = coons_inset_faces(geometry=cage.geometry, inset=0.2)",
                ""
        );
        GeometryBundle out = run(dsl, "eyes");

        var tags = TagGeometryNode.getTags(out);
        assertNotNull(tags, "tags slot must be populated by auto-tag hook");
        assertTrue(tags.containsKey("eyes"),
                "expected tag 'eyes' from LHS; got " + tags.keySet());
        boolean[] mask = tags.get("eyes");
        assertEquals(out.mesh().vertexCount(), mask.length);

        int tagged = 0;
        for (boolean b : mask) {
            if (b) tagged++;
        }
        assertTrue(tagged > 0, "tag mask must mark at least one inner-face vertex");
        assertTrue(tagged < mask.length, "tag must not cover the whole mesh");
    }

    @Test
    public void chainedFeaturesAccumulateTags() throws Exception {
        String dsl = String.join("\n",
                "base = cube(size=1.0)",
                "cage = assign_bezier_handles(geometry=base.mesh, weight=0.33)",
                "eyes = coons_inset_faces(geometry=cage.geometry, inset=0.2)",
                "deeper = coons_extrude_mesh(geometry=eyes.geometry, selection=eyes.generated, offset=-0.1, region=true)",
                ""
        );
        GeometryBundle out = run(dsl, "deeper");

        var tags = TagGeometryNode.getTags(out);
        assertNotNull(tags);
        assertTrue(tags.containsKey("eyes"), "first feature's tag must survive downstream ops: " + tags.keySet());
        assertTrue(tags.containsKey("deeper"), "second feature's tag must be present: " + tags.keySet());
    }

    @Test
    public void userTagGeometryAppendsNotOverwrites() throws Exception {
        String dsl = String.join("\n",
                "base = cube(size=1.0)",
                "cage = assign_bezier_handles(geometry=base.mesh, weight=0.33)",
                "eyes = coons_inset_faces(geometry=cage.geometry, inset=0.2)",
                "tagged = tag_geometry(geometry=eyes.geometry, tags=\"extra\")",
                ""
        );
        GeometryBundle out = run(dsl, "tagged");

        var tags = TagGeometryNode.getTags(out);
        assertNotNull(tags);
        assertTrue(tags.containsKey("eyes"), "auto-tag 'eyes' must still be present after user tag_geometry");
        assertTrue(tags.containsKey("extra"), "user-supplied 'extra' tag must be appended");
    }

    @Test
    public void nodeWithoutGeneratedPortNotTagged() throws Exception {
        String dsl = String.join("\n",
                "base = cube(size=1.0)",
                "cage = assign_bezier_handles(geometry=base.mesh, weight=0.33)",
                ""
        );
        GeometryBundle out = run(dsl, "cage");

        var tags = TagGeometryNode.getTags(out);
        // Either absent or empty — hook must skip nodes without (generated + geometry).
        if (tags != null) {
            assertFalse(tags.containsKey("cage"),
                    "assign_bezier_handles has no 'generated' port and must not be tagged");
            assertFalse(tags.containsKey("base"),
                    "cube has no 'generated' port and must not be tagged");
        }
    }
}
