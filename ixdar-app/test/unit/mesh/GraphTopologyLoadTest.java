package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.nodegraph.runtime.NodeGraphRuntime;

/**
 * The repo graphs coons_cube and test_bridge execute headless through the path the mesh viewer's
 * model menu uses, and the mesh they produce answers every half-edge topology query.
 */
class GraphTopologyLoadTest {

    private static final String GEOMETRY_PORT = "geometry";

    private static final int SHARED_STRIP_EDGES = 7;

    @Test
    void coonsCubeLoads() throws Exception {
        assertConnected(execute("dsl/coons_cube.dsl"));
    }

    @Test
    void testBridgeLoads() throws Exception {
        assertConnected(execute("dsl/test_bridge.dsl"));
    }

    @Test
    void arrayMeshBuildsItsEdgesOnFirstQuery() {
        ArrayMesh strip = twoQuadStrip();

        assertEquals(SHARED_STRIP_EDGES, strip.edgeCount(), "two quads sharing one edge");
        int sharedEdge = strip.faceEdgeAt(0, 1);
        assertEquals(sharedEdge, strip.faceEdgeAt(1, 3), "both quads name the same middle edge");
        assertFalse(strip.isBoundaryEdge(sharedEdge), "the middle edge has a twin");
        assertTrue(strip.isBoundaryEdge(strip.faceEdgeAt(0, 0)), "an outer edge has none");
        assertConnected(strip);
    }

    @Test
    void meshNeverAskedForEdgesNeverBuildsThem() throws Exception {
        ArrayMesh strip = twoQuadStrip();
        strip.computeNormals();
        strip.faceVertexAt(1, 3);
        strip.radius();
        assertNull(builtTopology(strip), "faces, normals and bounds need no edges");

        strip.faceEdgeAt(0, 0);
        assertNotNull(builtTopology(strip), "the first edge query builds");
    }

    private static ArrayMesh twoQuadStrip() {
        float[] positions = {0, 0, 0, 1, 0, 0, 2, 0, 0, 0, 1, 0, 1, 1, 0, 2, 1, 0};
        return new ArrayMesh(positions, null, new int[] {0, 1, 4, 3, 1, 2, 5, 4}, 4);
    }

    private static Object builtTopology(ArrayMesh mesh) throws ReflectiveOperationException {
        Field holder = ArrayMesh.class.getDeclaredField("topology");
        holder.setAccessible(true);
        return ((AtomicReference<?>) holder.get(mesh)).get();
    }

    private static MeshTopology execute(String dslPath) throws Exception {
        String source = new String(GraphTopologyLoadTest.class.getClassLoader().getResourceAsStream(dslPath)
                .readAllBytes(), StandardCharsets.UTF_8);
        NodeGraphRuntime runtime = NodeGraphRuntime.fromSource(source);
        MeshTopology mesh = runtime.executeGraphToMesh(runtime.lastId(), GEOMETRY_PORT);
        assertNotNull(mesh, dslPath + " produced no mesh");
        return mesh;
    }

    private static void assertConnected(MeshTopology mesh) {
        assertTrue(mesh.faceCount() > 0, "mesh has faces");
        assertTrue(mesh.edgeCount() > 0, "mesh has edges");
        for (int activeHalfEdge = 0; activeHalfEdge < mesh.halfEdgeCount(); activeHalfEdge++) {
            int halfEdgeId = mesh.halfEdgeIdAt(activeHalfEdge);
            assertTrue(mesh.hasEdge(mesh.halfEdgeEdge(halfEdgeId)), "half-edge " + halfEdgeId + " has an edge");
        }
    }
}
