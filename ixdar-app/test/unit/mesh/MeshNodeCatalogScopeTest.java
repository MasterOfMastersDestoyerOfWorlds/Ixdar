package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.MeshNodeRegistry_MeshNodes;
import ixdar.geometry.mesh.documentation.MeshNodeCatalog;
import ixdar.geometry.mesh.nodes.primitives.CubeMeshNode;

public class MeshNodeCatalogScopeTest {

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseNodes(String json) {
        Map<String, Object> parsed = new Gson().fromJson(json, Map.class);
        return (List<Map<String, Object>>) parsed.get("nodes");
    }

    @Test
    public void annotationDefaultIncludesBothScopes() {
        MeshNodeAnnotation ann = CubeMeshNode.class.getAnnotation(MeshNodeAnnotation.class);
        assertNotNull(ann, "CubeMeshNode should carry the @MeshNodeAnnotation");
        List<String> scopes = List.of(ann.scopes());
        assertTrue(scopes.contains("mesh"), "default scopes should include mesh");
        assertTrue(scopes.contains("dungeon"), "default scopes should include dungeon");
    }

    @Test
    public void catalogJsonIncludesScopesFieldOnEveryNode() {
        String json = MeshNodeCatalog.toJson(MeshNodeRegistry_MeshNodes.MAP);
        List<Map<String, Object>> nodes = parseNodes(json);
        assertTrue(nodes.size() > 0, "registry should not be empty");
        for (Map<String, Object> node : nodes) {
            Object scopes = node.get("scopes");
            assertNotNull(scopes, "every node entry must include scopes field: " + node.get("id"));
            assertTrue(scopes instanceof List && !((List<?>) scopes).isEmpty(),
                    "scopes must be non-empty list: " + node.get("id"));
        }
    }

    @Test
    public void meshScopeFilterExcludesDungeonOnlyNodes() {
        // PROCGEN-5 introduced five dungeon-only wrappers (scopes={"dungeon"} only). They must
        // NOT appear in the mesh-scope catalog, which is what Daud's agent consumes. Per-id
        // isolation is tested in DungeonNodeWrappersTest; here we verify the cardinality
        // difference matches the count of dungeon-only nodes currently on the registry.
        String mesh = MeshNodeCatalog.toJsonFromAnnotationRegistry("mesh");
        String all = MeshNodeCatalog.toJsonFromAnnotationRegistry();
        int meshCount = parseNodes(mesh).size();
        int allCount = parseNodes(all).size();
        assertTrue(meshCount < allCount,
                "mesh-scope catalog must exclude some nodes (dungeon-only wrappers)");
        assertTrue(meshCount > 0, "mesh-scope catalog should not be empty");
    }

    @Test
    public void dungeonScopeFilterIncludesDungeonAndDefaultNodes() {
        // Dungeon-scope catalog includes every node whose scopes contain "dungeon" — that's
        // the 5 dungeon-only wrappers plus all default-scoped nodes. Since no mesh-only nodes
        // exist on the registry today, dungeon-scope should equal the full catalog.
        String dungeon = MeshNodeCatalog.toJsonFromAnnotationRegistry("dungeon");
        String all = MeshNodeCatalog.toJsonFromAnnotationRegistry();
        assertEquals(parseNodes(all).size(), parseNodes(dungeon).size(),
                "with no mesh-only nodes, dungeon-scope catalog should equal the full catalog");
    }

    @Test
    public void unknownScopeReturnsEmpty() {
        String json = MeshNodeCatalog.toJsonFromAnnotationRegistry("nonexistent-scope");
        assertEquals(0, parseNodes(json).size(), "filtering by an unknown scope yields zero nodes");
    }

    @Test
    public void nullScopeOrNoArgReturnsAll() {
        String explicit = MeshNodeCatalog.toJson(MeshNodeRegistry_MeshNodes.MAP, null);
        String noArg = MeshNodeCatalog.toJson(MeshNodeRegistry_MeshNodes.MAP);
        assertEquals(parseNodes(noArg).size(), parseNodes(explicit).size(),
                "null scope and no-arg overload should be equivalent");
    }
}
