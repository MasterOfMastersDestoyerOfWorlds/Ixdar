package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

import ixdar.automation.AutomationRuntime;
import ixdar.automation.endpoints.mesh.QuadLayoutExport;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.QuadLayout;
import ixdar.nodegraph.nodes.data.SceneModelNode;
import ixdar.nodegraph.nodes.quadlayout.QuadLayoutNode;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.scenes.QuadLayoutScene;
import ixdar.scenes.Scene;
import unit.GlStandIn;

/**
 * CRAW-114: {@code /mesh/quad-layout/export} writes the quad-layout scene's layout to a GLB that
 * reads back with the layout's quads, and says why when the scene has no layout.
 */
class QuadLayoutExportTest {

    private static final String SPHERE_OFF = "test/resources/quadlayout/figure_7/sphere_base_in_tri.off";

    private static final int STAND_IN_PLATFORM_ID = 7719;

    private static final long ROUTE_WAIT_MILLIS = 10_000L;

    private static final int TRIANGLES_PER_QUAD = 2;

    private static final String OK = "ok";

    private static final String ERROR = "error";

    private static QuadLayout sphereLayout;

    private Platform suitePlatform;

    private GL suiteGl;

    private Scene runtimeCanvasBefore;

    private long renderThreadBefore;

    private QuadLayoutScene scene;

    /** A quad-layout scene bound to the automation runtime, this thread its render thread. */
    @BeforeEach
    void installScene() {
        suitePlatform = Platforms.get();
        suiteGl = Platforms.gl();
        Platform window = (Platform) Proxy.newProxyInstance(Platform.class.getClassLoader(),
                new Class<?>[] {Platform.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getPlatformID")) {
                        return STAND_IN_PLATFORM_ID;
                    }
                    Class<?> type = method.getReturnType();
                    return type == int.class ? 0 : type == float.class ? 0f
                            : type == boolean.class ? false : null;
                });
        Platforms.init(window, GlStandIn.create());
        scene = new QuadLayoutScene();
        AutomationRuntime runtime = AutomationRuntime.get();
        runtimeCanvasBefore = runtime.canvas;
        renderThreadBefore = runtime.renderThreadId;
        runtime.canvas = scene;
    }

    /** Put the runtime and the suite's platform back. */
    @AfterEach
    void restoreSuiteState() {
        AutomationRuntime runtime = AutomationRuntime.get();
        runtime.canvas = runtimeCanvasBefore;
        runtime.renderThreadId = renderThreadBefore;
        Platforms.init(suitePlatform, suiteGl);
    }

    /**
     * Lay the sphere out once, on the suite's platform, whose worker pool the pipeline runs on.
     *
     * @throws Exception when the fixture cannot be read or a stage fails
     */
    @BeforeAll
    static void layOutTheSphere() throws Exception {
        HalfEdgeMesh mesh = (HalfEdgeMesh) MeshLoader.load(SPHERE_OFF);
        mesh.computeNormals();
        NodeGraphRuntime graph = new NodeGraphRuntime(QuadLayoutNode.overSceneModel());
        graph.graphInputs.put(SceneModelNode.BINDING, GeometryBundle.ofMesh(mesh));
        sphereLayout = (QuadLayout) graph.pull(QuadLayoutNode.sceneOutput(QuadLayoutNode.LAYOUT));
    }

    @Test
    void theQuadMeshIsWrittenWithTheLayoutsCounts(@TempDir Path directory) throws Exception {
        QuadLayout layout = sphereLayout;
        scene.layout = layout;
        int quads = layout.quads.mesh().faceCount();

        Path file = directory.resolve("sphere_quads.glb");
        JsonObject written = export(file.toString(), QuadLayoutExport.GEOMETRY_QUADS);
        assertTrue(written.get(OK).getAsBoolean(), written.toString());
        assertEquals(quads, written.get("quad_count").getAsInt());
        assertEquals(layout.crossField.singularityCount(), written.get("singularity_count").getAsInt());
        assertTrue(written.get("patch_count").getAsInt() > 0, written.toString());
        assertEquals(TRIANGLES_PER_QUAD * quads, written.get("triangle_count").getAsInt(),
                "every quad is written as two triangles");
        assertTrue(Files.size(file) > 0, "the GLB is on disk");

        JsonObject patches = export(directory.resolve("sphere_patches.glb").toString(),
                QuadLayoutExport.GEOMETRY_PATCHES);
        assertTrue(patches.get(OK).getAsBoolean(), patches.toString());

        // The glTF reader parses JSON through the platform, which the stand-in window lacks.
        Platforms.init(suitePlatform, suiteGl);
        assertEquals(TRIANGLES_PER_QUAD * quads, MeshLoader.loadBundle(file.toString()).mesh().faceCount(),
                "the GLB reads back with every quad's triangles");
    }

    @Test
    void anEmptySceneSaysWhyItsModelDidNotLoad(@TempDir Path directory) throws Exception {
        scene.modelLoads.finish(scene.modelLoads.request(),
                new IllegalArgumentException("quad_layout: 3 faces fold back"), System.nanoTime());

        String unwritten = directory.resolve("none.glb").toString();
        JsonObject refused = export(unwritten, QuadLayoutExport.GEOMETRY_QUADS);
        assertFalse(refused.get(OK).getAsBoolean(), refused.toString());
        assertTrue(refused.get(ERROR).getAsString().contains("3 faces fold back"), refused.toString());

        JsonObject unknown = export(unwritten, "triangles");
        assertFalse(unknown.get(OK).getAsBoolean(), unknown.toString());
    }

    /**
     * Call the route from a server thread while this thread serves the render-thread queue, as the
     * automation server and the scene's frame loop do.
     *
     * @param path     file to write
     * @param geometry which layout bundle to write
     * @throws Exception when the route itself throws
     * @return the route's reply
     */
    private JsonObject export(String path, String geometry) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty(QuadLayoutExport.PATH, path);
        body.addProperty(QuadLayoutExport.GEOMETRY, geometry);
        JsonObject[] reply = new JsonObject[1];
        Exception[] failure = new Exception[1];
        AutomationRuntime runtime = AutomationRuntime.get();
        runtime.renderThreadId = Thread.currentThread().getId();
        Thread server = new Thread(() -> {
            try {
                reply[0] = new QuadLayoutExport() {
                    {
                        this.runtime = AutomationRuntime.get();
                    }
                }.endpointHandler(body);
            } catch (Exception routeFailure) {
                failure[0] = routeFailure;
            }
        });
        server.start();
        long deadline = System.currentTimeMillis() + ROUTE_WAIT_MILLIS;
        while (server.isAlive() && System.currentTimeMillis() < deadline) {
            runtime.processMainThreadCommands();
            server.join(1L);
        }
        assertFalse(server.isAlive(), "the route never answered");
        if (failure[0] != null) {
            throw failure[0];
        }
        return reply[0];
    }
}
