package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.InvalidMeshTopologyException;
import ixdar.nodegraph.nodes.data.SurfaceMetricNode;
import ixdar.nodegraph.runtime.NodeGraphRuntime;

/**
 * A port declared {@link ixdar.nodegraph.api.InputPort#manifoldGeometry} is checked by the runtime
 * before its node evaluates: an indexed mesh is refused naming the node, a half-edge mesh passes.
 */
class ManifoldInputPortTest {

    private static final String GRAPH = """
            carrier = cube()
            surface = surface_metric(geometry=carrier.mesh)
            """;

    private static final String SURFACE = "surface";

    private static final String SURFACE_GEOMETRY = SURFACE + "." + SurfaceMetricNode.GEOMETRY.name;

    // One triangle: an indexed mesh, the form only loaders and repair_mesh produce.
    private static final float[] TRIANGLE_POSITIONS = { 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f };

    private static final int[] TRIANGLE_CORNERS = { 0, 1, 2 };

    @Test
    void indexedMeshOnAManifoldPortIsRefusedNamingTheNode() {
        ArrayMesh indexed = new ArrayMesh(TRIANGLE_POSITIONS, null, TRIANGLE_CORNERS,
                HalfEdgeMesh.TRIANGLE_CORNERS);
        InvalidMeshTopologyException refused = assertThrows(InvalidMeshTopologyException.class,
                () -> NodeGraphRuntime.fromSource(GRAPH).executeGraphResult(SURFACE,
                        SurfaceMetricNode.METRIC.name,
                        Map.of(SURFACE_GEOMETRY, GeometryBundle.ofMesh(indexed))));
        assertEquals("surface_metric" + NodeGraphRuntime.NEEDS_MANIFOLD_MESH, refused.getMessage());
    }

    @Test
    void halfEdgeMeshOnAManifoldPortReachesTheNode() throws Exception {
        HalfEdgeMesh surface = HalfEdgeMesh.buildFromIndexedMesh(TRIANGLE_POSITIONS, TRIANGLE_CORNERS);
        assertNotNull(NodeGraphRuntime.fromSource(GRAPH).executeGraphResult(SURFACE,
                SurfaceMetricNode.METRIC.name, Map.of(SURFACE_GEOMETRY, GeometryBundle.ofMesh(surface))));
    }
}
