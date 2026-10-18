package ixdar.scenes.dungeon;

import java.util.List;

import org.joml.Vector3f;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.graphics.cameras.FlyController;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.nodegraph.field.Vector3Field;
import ixdar.platform.Clock;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.nodegraph.syntax.PythonParser;
import ixdar.platform.Platforms;
import ixdar.platform.Platform;
import ixdar.procgen.dungeon.algo.DungeonGrids;
import ixdar.procgen.dungeon.camera.ThirdPersonCamera;
import ixdar.procgen.dungeon.player.PlayerController;
import ixdar.procgen.dungeon.player.PlayerSpawner;
import ixdar.procgen.dungeon.player.SpawnPoint;
import ixdar.procgen.dungeon.render.DebugCapsuleRuntime;
import ixdar.platform.input.Keys;
import ixdar.procgen.dungeon.values.CellType;
import ixdar.scenes.Scene;
import ixdar.tools.ControlHint;

/**
 * Viewer for procedural dungeons, switching with <kbd>F</kbd> between walking a collided player
 * capsule and flying the camera freely. Override the DSL with {@code -Dmesh.dsl=dungeon_3d}.
 *
 * <p>The DSL mesh feeds both the renderer and the collision world, so only 3D dungeons support
 * player mode; 2D ones fall back to fly-cam.
 */
@SceneAnnotation(id = "dungeon-viewer")
public class DungeonViewerScene extends Scene {
    public static final String DSL = ".dsl";
    public static final String PLAYER = "player";
    public static final String FLY_CAM = "fly-cam";
    public static final float MIN_CAMERA_RADIUS = 0.1f;
    public static final float CAMERA_HEIGHT_FRAC = 0.6f;
    public static final float CAMERA_BACK_FRAC = 0.8f;
    public static final float MIN_MOVEMENT_SPEED = 0.05f;
    public static final float MOVEMENT_SPEED_FRAC = 0.15f;
    public static final float DEFAULT_OVERVIEW_YAW = 90f;
    public static final float DEFAULT_OVERVIEW_PITCH = 25f;
    public static final double MAX_FRAME_DT = 0.1;

    private static final String DSL_FOLDER = "dsl";
    private static final String DEFAULT_DSL_RESOURCE = "dungeon_2d.dsl";
    private static final float CAPSULE_HALF_HEIGHT_FRAC = 0.30f; // of cellSize
    private static final float CAPSULE_RADIUS_FRAC = 0.20f;
    private static final float JUMP_SPEED_PER_CELL = 4.0f;       // cells per second
    private static final float WALK_SPEED_PER_CELL = 3.0f;       // cells per second

    private final String dslResource;
    private MeshTopology mesh;
    private volatile HalfEdgeMeshRuntime meshRuntime;

    // Player mode state — populated once the DSL output is ready and we have a 3D grid.
    private CellType[] playerCells;
    private int playerGridW;
    private int playerGridH;
    private int playerGridD;
    private MeshTopology playerRooms;
    private Vector3Field playerRoomHalfExtents;
    private float playerCellSize = 1.0f;
    private PlayerController player;
    private boolean playerMode = true;
    private ViewMode viewMode = ViewMode.FIRST_PERSON;
    private final FlyController flyController = new FlyController(camera, () -> playerMode);
    private final ThirdPersonCamera thirdPersonCamera = new ThirdPersonCamera(camera);
    private DebugCapsuleRuntime capsuleRuntime;

    /**
     * Builds the scene, picking the DSL resource from the {@code ixdar.mesh.dsl} system property
     * (defaulting to {@code dungeon_2d.dsl}) and appending the {@code .dsl} extension if absent.
     */
    public DungeonViewerScene() {
        String v = System.getProperty("ixdar.mesh.dsl");
        String pick = (v != null && !v.isEmpty()) ? v : DEFAULT_DSL_RESOURCE;
        this.dslResource = pick.endsWith(DSL) ? pick : pick + DSL;
    }

    /**
     * Wires up input handlers and the debug capsule runtime, then kicks off the asynchronous
     * DSL load that populates the mesh and (for 3D dungeons) the player collision world.
     *
     * @throws IllegalStateException if the debug capsule runtime cannot be created
     */
    @Override
    public void initGL() {
        super.initGL();
        Platforms.gl().setWindowTitle("Ixdar : Dungeon Viewer");
        MenuBox.menuVisible = false;
        // Player mode captures the cursor, so every move looks; fly-cam looks on a drag.
        mouse.controller = flyController;
        bindAutomationIfAvailable(Platforms.get());
        bindInputDirect(Platforms.get(), keys, mouse);
        try {
            capsuleRuntime = new DebugCapsuleRuntime();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create debug capsule runtime", e);
        }
        // Cursor capture follows player mode.
        applyCursorModeForCurrentMode();

        Platforms.get().log("[dungeon-viewer] loading " + dslResource);
        Platforms.get().loadSourceAsync(DSL_FOLDER, dslResource,
                Platforms.gl().getPlatformID(), this::executeDsl);
    }

    private void executeDsl(String dslCode) {
        NodeGraphRuntime runtime = NodeGraphRuntime.fromSource(dslCode);
        List<PythonParser.ParsedNode> ast = runtime.statements;

        String finalId = ast.get(ast.size() - 1).id;
        MeshTopology result;
        try {
            result = runtime.executeGraphToMesh(ast, finalId, "mesh");
            runtime.logTimings("[dungeon]");
        } catch (Exception e) {
            Platforms.get().log("[dungeon-viewer] DSL execution failed: " + e.getMessage());
            throw new IllegalStateException("Failed to execute DSL: " + dslResource, e);
        }
        if (!(result instanceof ArrayMesh)) {
            throw new IllegalStateException(
                    "dungeon DSL final output should be ArrayMesh, got "
                            + (result == null ? "null" : result.getClass().getSimpleName()));
        }
        this.mesh = result;
        try {
            meshRuntime = new HalfEdgeMeshRuntime();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create mesh GL runtime", e);
        }
        meshRuntime.upload(mesh);

        // Pull the dungeon's tile grid for player collision. Walks the DSL: prefer
        // astar_corridors_3d / astar_corridors output, falling back to no-grid (fly-cam only).
        bindPlayerGridFromDsl(ast, runtime);

        positionCameraAboveDungeon();
        if (player == null || playerRooms == null) {
            // No 3D grid available — disable player mode for this DSL.
            playerMode = false;
        } else {
            spawnPlayerAtRoomZero();
        }
        Platforms.get().log("[dungeon-viewer] mesh ready verts=" + mesh.vertexCount()
                + " faces=" + mesh.faceCount() + " mode=" + (playerMode ? PLAYER : FLY_CAM));
    }

    /**
     * Looks for the tile-lattice geometry produced by an {@code astar_corridors_3d} node so the
     * player has a collision world. 2D dungeons leave {@link #playerCells} null and the scene
     * falls back to fly-cam mode.
     */
    private void bindPlayerGridFromDsl(List<PythonParser.ParsedNode> ast, NodeGraphRuntime runtime) {
        for (PythonParser.ParsedNode n : ast) {
            if ("astar_corridors_3d".equals(n.type)) {
                Object tiles = runtime.getNodeOutput(n.id, NodeGraphRuntime.GEOMETRY);
                if (tiles instanceof GeometryBundle lattice) {
                    int[] dims = DungeonGrids.latticeDims(lattice);
                    this.playerGridW = dims[0];
                    this.playerGridH = dims[1];
                    this.playerGridD = dims[2];
                    this.playerCells = DungeonGrids.latticeCells(lattice, dims[0], dims[1], dims[2]);
                    // Find the cell_size from the dungeon_grid_to_mesh_3d node downstream.
                    for (PythonParser.ParsedNode m : ast) {
                        if ("dungeon_grid_to_mesh_3d".equals(m.type)) {
                            Object cs = m.arguments.get("cell_size");
                            if (cs instanceof Number csNum) {
                                this.playerCellSize = csNum.floatValue();
                            }
                            break;
                        }
                    }
                    // Find the rooms produced by random_rooms so PlayerSpawner has them.
                    for (PythonParser.ParsedNode m : ast) {
                        if ("random_rooms".equals(m.type)) {
                            Object rooms = runtime.getNodeOutput(m.id, NodeGraphRuntime.GEOMETRY);
                            if (rooms instanceof GeometryBundle rb) {
                                this.playerRooms = rb.mesh();
                                this.playerRoomHalfExtents = DungeonGrids.halfExtents(rb);
                            }
                            break;
                        }
                    }
                    float hh = playerCellSize * CAPSULE_HALF_HEIGHT_FRAC;
                    float r  = playerCellSize * CAPSULE_RADIUS_FRAC;
                    float jump = JUMP_SPEED_PER_CELL * playerCellSize;
                    float walk = WALK_SPEED_PER_CELL * playerCellSize;
                    this.player = new PlayerController(playerCells, playerGridW, playerGridH, playerGridD,
                            playerCellSize, new Vector3f(0f, 0f, 0f),
                            hh, r, PlayerController.DEFAULT_GRAVITY, jump, walk);
                    return;
                }
            }
        }
    }

    /**
     * Places the camera above the dungeon bounds looking down and slightly forward — a good
     * vantage to see the layout immediately. Player can then fly down into corridors with WASD.
     */
    private void positionCameraAboveDungeon() {
        if (mesh == null || mesh.vertexCount() == 0) return;
        Vector3f center = mesh.center(new Vector3f());
        float radius = Math.max(MIN_CAMERA_RADIUS, mesh.radius());
        camera.position.set(center.x, center.y + radius * CAMERA_HEIGHT_FRAC, center.z + radius * CAMERA_BACK_FRAC);
        camera.setMovementSpeed(Math.max(MIN_MOVEMENT_SPEED, radius * MOVEMENT_SPEED_FRAC));
        camera.setOrientation(-DEFAULT_OVERVIEW_YAW, -DEFAULT_OVERVIEW_PITCH);
        camera.updateViewFirstPerson();
    }

    /**
     * Spawns the player at the start room (room[0], guaranteed by RoomPlacer3D to be at the
     * grid's center / world origin). Camera yaw is set so the player faces room[1] — gives
     * something to walk toward instead of a blank wall.
     */
    private void spawnPlayerAtRoomZero() {
        if (player == null || playerRooms == null || playerCells == null) return;
        SpawnPoint sp = PlayerSpawner.pick(
                playerRooms, playerRoomHalfExtents, playerCellSize,
                playerGridW, playerGridH, playerGridD,
                player.halfHeight(), player.radius());
        player.teleport(sp.position());
        camera.position.set(sp.position().x(),
                sp.position().y() + player.halfHeight(),
                sp.position().z());
        camera.setOrientation(sp.yawDegrees(), sp.pitchDegrees());
        camera.updateViewFirstPerson();
        Platforms.get().log("[dungeon-viewer] player spawned at room[0] world=("
                + sp.position().x() + "," + sp.position().y() + "," + sp.position().z()
                + ") yaw=" + sp.yawDegrees());
    }

    private void togglePlayerMode() {
        if (player == null) {
            Platforms.get().log("[dungeon-viewer] no 3D grid — player mode unavailable for this DSL");
            return;
        }
        playerMode = !playerMode;
        Platforms.get().log("[dungeon-viewer] mode -> " + (playerMode ? PLAYER : FLY_CAM));
        applyCursorModeForCurrentMode();
        // Default back to first-person on (re-)entering player mode; fly-cam flies either way.
        viewMode = ViewMode.FIRST_PERSON;
        mouse.controller = flyController;
        if (playerMode) {
            spawnPlayerAtRoomZero();
        }
    }

    /** Swap between first- and third-person while in player mode. No-op outside player mode. */
    private void toggleViewMode() {
        if (!playerMode || player == null) return;
        if (viewMode == ViewMode.FIRST_PERSON) {
            thirdPersonCamera.enterFromCurrentCamera(playerCellSize);
            viewMode = ViewMode.THIRD_PERSON;
            mouse.controller = thirdPersonCamera;
        } else {
            // Camera yaw/pitch are the third-person follow angles, so 1P is smooth.
            viewMode = ViewMode.FIRST_PERSON;
            mouse.controller = flyController;
        }
        Platforms.get().log("[dungeon-viewer] view -> " + viewMode);
    }

    /**
     * The viewer's keys: F walks the player or flies the camera, V swaps first and third person
     * and Esc leaves player mode, both only while walking, and R resets the look.
     */
    @Override
    public void setControls() {
        controls.add(new ControlHint(Keys.F, "F", "walk the player or fly the camera", this::togglePlayerMode));
        controls.add(new ControlHint(Keys.V, "V", "first or third person while walking", this::toggleViewMode));
        controls.add(new ControlHint(Keys.ESCAPE, "esc", "stop walking and fly", () -> {
            if (playerMode) {
                togglePlayerMode();
            }
        }));
        controls.add(new ControlHint(Keys.R, "R", "reset the look", camera::reset));
        super.setControls();
    }

    /** Player mode = cursor captured (FPS look). Fly-cam = cursor visible (drag to rotate). */
    private void applyCursorModeForCurrentMode() {
        Platforms.get().setCursorMode(playerMode
                ? Platform.CursorMode.CAPTURED
                : Platform.CursorMode.NORMAL);
    }

    /**
     * Per-frame draw. Steps player physics (in player mode), refreshes the camera, renders the
     * dungeon mesh, and overlays the debug capsule when in third-person.
     */
    @Override
    public void drawScene() {
        if (meshRuntime == null) return;
        // Run the player physics (in player mode) before refreshing the view matrix.
        if (playerMode && player != null) {
            float dt = (float) Math.min(MAX_FRAME_DT, Clock.deltaTime()); // clamp to avoid huge dt on stalls
            if (viewMode == ViewMode.THIRD_PERSON) {
                // Update camera FIRST so player.update sees the new yaw and WASD direction
                // remains screen-relative.
                thirdPersonCamera.update(player, playerCells, playerGridW, playerGridH, playerGridD);
                player.update(dt, keys.pressedKeys, camera.yaw);
            } else {
                player.update(dt, keys.pressedKeys, camera.yaw);
                Vector3f eye = player.cameraEyePosition();
                camera.position.set(eye.x(), eye.y(), eye.z());
            }
        }
        camera.resetView();
        camera.updateViewFirstPerson();
        meshRuntime.render(camera);
        if (playerMode && viewMode == ViewMode.THIRD_PERSON && player != null && capsuleRuntime != null) {
            Vector3f p = player.position();
            float yawRad = (float) Math.toRadians(player.facingYawDegrees());
            capsuleRuntime.render(camera, p.x(), p.y(), p.z(), yawRad,
                    player.radius(), player.halfHeight());
        }
    }

    /**
     * Scene activation hook. On deactivation, releases GL-bound mesh and capsule runtimes so
     * resources don't leak when switching scenes.
     *
     * @param state {@code true} when the scene is being activated, {@code false} on deactivation
     */
    @Override
    public void activate(boolean state) {
        super.activate(state);
        if (!state) {
            if (meshRuntime != null) {
                meshRuntime.dispose();
                meshRuntime = null;
            }
            if (capsuleRuntime != null) {
                capsuleRuntime.dispose();
                capsuleRuntime = null;
            }
        }
    }

    /**
     * Releases GL runtimes and restores the OS cursor so the user can interact with the desktop
     * after the scene exits.
     */
    @Override
    public void shutdown() {
        if (meshRuntime != null) {
            meshRuntime.dispose();
            meshRuntime = null;
        }
        if (capsuleRuntime != null) {
            capsuleRuntime.dispose();
            capsuleRuntime = null;
        }
        // Release the cursor so the user can interact with the OS again.
        Platforms.get().setCursorMode(Platform.CursorMode.NORMAL);
        super.shutdown();
    }

    // --- Accessors for tests / automation ------------------------------------

    /**
     * Vertex count of the currently-loaded dungeon mesh.
     *
     * @return number of vertices, or 0 if the DSL has not finished loading
     */
    public int getMeshVertexCount() {
        return mesh == null ? 0 : mesh.vertexCount();
    }

    /**
     * Face count of the currently-loaded dungeon mesh.
     *
     * @return number of faces, or 0 if the DSL has not finished loading
     */
    public int getMeshFaceCount() {
        return mesh == null ? 0 : mesh.faceCount();
    }

    /**
     * Whether the scene is currently in player-walk mode (versus fly-cam).
     *
     * @return {@code true} if the player capsule owns horizontal motion this frame
     */
    public boolean isPlayerMode() {
        return playerMode;
    }

    public enum ViewMode { FIRST_PERSON, THIRD_PERSON }
}
