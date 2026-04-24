package ixdar.procgen.dungeon.scene;

import java.util.List;

import org.joml.Vector3f;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;
import ixdar.platform.Platforms;
import ixdar.platform.gl.Platform;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.MouseTrap;
import ixdar.scenes.Scene;

/**
 * Fly-cam viewer for procedural dungeons. Runs a dungeon {@code .dsl} via {@link NodeGraphRuntime},
 * uploads the emitted mesh to a VBO, and lets the user walk through it with WASD + drag-to-look.
 *
 * <p>Most input plumbing is reused from the rest of the engine — {@link Camera3D} handles yaw/pitch
 * via {@link Camera3D#mouseMove}, {@link KeyGuy} maps WASD to {@link Camera.Direction} via
 * {@code Camera2DInputController}, and {@code SceneInputFrameUpdater} ticks both per frame. The
 * only new piece is {@link FlyCamMouseTrap}, which makes mouse-look drag-gated instead of
 * always-on.
 *
 * <p>Launch: {@code mvn -P dungeon-viewer}. Override the DSL with {@code -Dmesh.dsl=dungeon_2d}
 * or any other id — the scene reads {@code ixdar.mesh.dsl} like the mesh viewer.
 */
@SceneAnnotation(id = "dungeon-viewer")
public class DungeonViewerScene extends Scene {

    private static final String DSL_FOLDER = "dsl";
    private static final String DEFAULT_DSL_RESOURCE = "dungeon_2d.dsl";

    private final String dslResource;
    private MeshTopology mesh;
    private volatile HalfEdgeMeshRuntime meshRuntime;

    public DungeonViewerScene() {
        String v = System.getProperty("ixdar.mesh.dsl");
        String pick = (v != null && !v.isEmpty()) ? v : DEFAULT_DSL_RESOURCE;
        this.dslResource = pick.endsWith(".dsl") ? pick : pick + ".dsl";
    }

    @Override
    public void initGL() {
        super.initGL();
        Platforms.gl().setWindowTitle("Ixdar : Dungeon Viewer");
        MenuBox.menuVisible = false;
        keys = new KeyGuy(camera, this);
        mouse = new FlyCamMouseTrap(camera, this);
        bindAutomationIfAvailable(Platforms.get(), keys, mouse);
        bindInputDirect(Platforms.get(), keys, mouse);

        Platforms.get().log("[dungeon-viewer] loading " + dslResource);
        Platforms.get().loadSourceAsync(DSL_FOLDER, dslResource,
                Platforms.gl().getPlatformID(), this::executeDsl);
    }

    private void executeDsl(String dslCode) {
        PythonLexer lexer = new PythonLexer(dslCode);
        PythonParser parser = new PythonParser(lexer);
        List<PythonParser.ParsedNode> ast = parser.parseGraph();

        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        runtime.registerFunctionDefs(parser.functionDefs());

        String finalId = ast.get(ast.size() - 1).id;
        Object result;
        try {
            result = runtime.executeGraphResult(ast, finalId, "mesh");
        } catch (Exception e) {
            Platforms.get().log("[dungeon-viewer] DSL execution failed: " + e.getMessage());
            throw new IllegalStateException("Failed to execute DSL: " + dslResource, e);
        }
        if (!(result instanceof ArrayMesh)) {
            throw new IllegalStateException(
                    "dungeon DSL final output should be ArrayMesh, got "
                            + (result == null ? "null" : result.getClass().getSimpleName()));
        }
        this.mesh = (MeshTopology) result;
        try {
            meshRuntime = new HalfEdgeMeshRuntime();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create mesh GL runtime", e);
        }
        meshRuntime.upload(mesh);
        positionCameraAboveDungeon();
        Platforms.get().log("[dungeon-viewer] mesh ready verts=" + mesh.vertexCount()
                + " faces=" + mesh.faceCount());
    }

    /**
     * Places the camera above the dungeon bounds looking down and slightly forward — a good
     * vantage to see the layout immediately. Player can then fly down into corridors with WASD.
     */
    private void positionCameraAboveDungeon() {
        if (mesh == null || mesh.vertexCount() == 0) return;
        Vector3f center = mesh.center(new Vector3f());
        float radius = Math.max(0.1f, mesh.radius());
        // Start above the dungeon at ~1.2x radius elevation, looking down-forward.
        camera.position.set(center.x, center.y + radius * 1.2f, center.z + radius * 1.0f);
        // Tune WASD speed to the mesh scale so motion is visible on a unit-scale dungeon but
        // not insta-teleport across it. Roughly: cross the dungeon in ~6 seconds.
        camera.setMovementSpeed(Math.max(0.05f, radius * 0.35f));
        // Face -Z toward the dungeon center and tilt down.
        camera.setOrientation(-90f, -45f);
        camera.updateViewFirstPerson();
    }

    @Override
    public void drawScene() {
        if (meshRuntime == null) return;
        camera.resetView();
        meshRuntime.render(camera);
    }

    @Override
    public void activate(boolean state) {
        super.activate(state);
        if (!state && meshRuntime != null) {
            meshRuntime.dispose();
            meshRuntime = null;
        }
    }

    @Override
    public void shutdown() {
        if (meshRuntime != null) {
            meshRuntime.dispose();
            meshRuntime = null;
        }
        super.shutdown();
    }

    // --- Accessors for tests / automation ------------------------------------

    public int getMeshVertexCount() {
        return mesh == null ? 0 : mesh.vertexCount();
    }

    public int getMeshFaceCount() {
        return mesh == null ? 0 : mesh.faceCount();
    }

    /**
     * Wires platform keyboard/mouse callbacks into our KeyGuy + FlyCamMouseTrap. Mirrors
     * {@code MeshNodeViewerScene.bindInputDirect} — each viewer scene needs to repeat this
     * because the platform callback slots are per-window and can only hold one listener.
     */
    private static void bindInputDirect(Platform platform, KeyGuy keys, MouseTrap mouse) {
        platform.setCursorPosCallback((window, x, y) -> mouse.moveOrDrag(window, (float) x, (float) y));
        platform.setMouseButtonCallback((button, action, mods) -> mouse.mouseButton(button, action, mods));
        platform.setScrollCallback((xoff, yoff) -> mouse.scrollCallback(yoff));
        platform.setKeyCallback((key, scancode, action, mods) -> keys.keyCallback(0L, key, scancode, action, mods));
    }
}
