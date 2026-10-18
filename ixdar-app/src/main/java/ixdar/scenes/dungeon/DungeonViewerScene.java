package ixdar.scenes.dungeon;

import java.io.IOException;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.graphics.cameras.FlyController;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.nodegraph.runtime.GraphNodeContext;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.nodegraph.field.Vector3Field;
import ixdar.platform.Clock;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.models.GraphChoice;
import ixdar.nodegraph.syntax.PythonParser;
import ixdar.platform.Platforms;
import ixdar.platform.Platform;
import ixdar.procgen.dungeon.algo.DungeonGrids;
import ixdar.procgen.dungeon.camera.ThirdPersonCamera;
import ixdar.procgen.dungeon.nodes.AStarCorridorsNode;
import ixdar.procgen.dungeon.nodes.DungeonGridToMeshNode;
import ixdar.procgen.dungeon.player.PlayerController;
import ixdar.procgen.dungeon.player.PlayerSpawner;
import ixdar.procgen.dungeon.player.SpawnPoint;
import ixdar.procgen.dungeon.render.DebugCapsuleRuntime;
import ixdar.platform.input.Keys;
import ixdar.procgen.dungeon.values.CellType;
import ixdar.scenes.model.ModelScene;
import ixdar.tools.ControlHint;

/**
 * Viewer for the registered dungeon graphs, picked from the model menu or with
 * {@code -Dixdar.model=dungeon_3d}: <kbd>F</kbd> switches between walking a collided player capsule,
 * which only a 3D dungeon's tile lattice supports, and flying the camera.
 */
@SceneAnnotation(id = "dungeon-viewer")
public class DungeonViewerScene extends ModelScene {
    public static final String PLANAR_DUNGEON = "dungeon_2d";
    public static final String LAYERED_DUNGEON = "dungeon_3d";
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

    private static final float CAPSULE_HALF_HEIGHT_FRAC = 0.30f; // of cellSize
    private static final float CAPSULE_RADIUS_FRAC = 0.20f;
    private static final float JUMP_SPEED_PER_CELL = 4.0f;       // cells per second
    private static final float WALK_SPEED_PER_CELL = 3.0f;       // cells per second

    private MeshTopology mesh;

    // Player mode state — populated once a graph whose tiles are a 3D lattice has run.
    private CellType[] playerCells;
    private int playerGridW;
    private int playerGridH;
    private int playerGridD;
    private MeshTopology playerRooms;
    private Vector3Field playerRoomHalfExtents;
    private float playerCellSize = 1.0f;
    private PlayerController player;
    private boolean playerMode;
    private ViewMode viewMode = ViewMode.FIRST_PERSON;
    private final FlyController flyController = new FlyController(camera, () -> playerMode);
    private final ThirdPersonCamera thirdPersonCamera = new ThirdPersonCamera(camera);
    private DebugCapsuleRuntime capsuleRuntime;

    /** Registers the planar and the layered dungeon graphs for the model menu. */
    public DungeonViewerScene() {
        registerGraph(new GraphChoice(PLANAR_DUNGEON, "dsl/dungeon_2d.dsl", Map.of()));
        registerGraph(new GraphChoice(LAYERED_DUNGEON, "dsl/dungeon_3d.dsl", Map.of()));
    }

    @Override
    public String windowTitle() {
        return "Ixdar : Dungeon Viewer";
    }

    /**
     * The dungeon {@code -Dixdar.model} names, else the planar dungeon.
     *
     * @return the initial model's {@code graph:} token, or the requested path
     */
    @Override
    public String resolveInitialModel() {
        String requested = System.getProperty(COMMON_MODEL_PROPERTY);
        return requested == null || requested.isBlank() ? GRAPH_PREFIX + PLANAR_DUNGEON
                : super.resolveInitialModel();
    }

    /**
     * Hide the main menu and bind the input as every model scene does, then hand the mouse to the
     * fly controller: player mode captures the cursor, so every move looks; fly-cam looks on a drag.
     */
    @Override
    public void initInput() {
        MenuBox.menuVisible = false;
        super.initInput();
        mouse.controller = flyController;
    }

    /**
     * The surface runtime the dungeon is drawn through, and the debug capsule third person shows.
     *
     * @throws IllegalStateException if the debug capsule runtime cannot be created
     * @return the surface runtime
     */
    @Override
    public HalfEdgeMeshRuntime createRuntime() {
        try {
            capsuleRuntime = new DebugCapsuleRuntime();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create debug capsule runtime", e);
        }
        return new HalfEdgeMeshRuntime();
    }

    /**
     * Run a dungeon graph and show its mesh. When the tiles the final grid-to-mesh read are a 3D
     * lattice, they become the player's collision world and the player walks from room[0];
     * otherwise the scene flies.
     *
     * @param choice registered dungeon graph to run
     * @throws IllegalStateException when a node fails or the graph ends in no mesh
     * @return the executed runtime
     */
    @Override
    public NodeGraphRuntime loadGraph(GraphChoice choice) {
        NodeGraphRuntime run = super.loadGraph(choice);
        if (!(run.lastOutput(DungeonGridToMeshNode.MESH.name) instanceof GeometryBundle dungeon)) {
            throw new IllegalStateException("dungeon graph " + choice.dslPath + " ends in no mesh");
        }
        this.mesh = dungeon.mesh();
        runtime.upload(mesh);
        frameMesh(mesh);
        updateCurrentChoice();

        player = null;
        GraphNodeContext mesher = run.root.children.get(run.root.children.size() - 1);
        GeometryBundle tiles = mesher.getInput(DungeonGridToMeshNode.GEOMETRY.name, GeometryBundle.class);
        if (tiles != null && DungeonGrids.isLattice(tiles)
                && mesher.inputSources.get(DungeonGridToMeshNode.GEOMETRY.name)
                        instanceof PythonParser.NodeReference tilesSource) {
            int[] dims = DungeonGrids.latticeDims(tiles);
            this.playerGridW = dims[0];
            this.playerGridH = dims[1];
            this.playerGridD = dims[2];
            this.playerCells = DungeonGrids.latticeCells(tiles, dims[0], dims[1], dims[2]);
            Number cellSize = mesher.getInput(DungeonGridToMeshNode.CELL_SIZE.name, Number.class);
            this.playerCellSize = cellSize == null ? 1.0f : cellSize.floatValue();
            // The rooms graph the corridors were carved over carries the room centres and extents.
            GeometryBundle rooms = run.node(tilesSource.nodeId)
                    .getInput(AStarCorridorsNode.GEOMETRY.name, GeometryBundle.class);
            this.playerRooms = rooms.mesh();
            this.playerRoomHalfExtents = DungeonGrids.halfExtents(rooms);
            float hh = playerCellSize * CAPSULE_HALF_HEIGHT_FRAC;
            float r  = playerCellSize * CAPSULE_RADIUS_FRAC;
            float jump = JUMP_SPEED_PER_CELL * playerCellSize;
            float walk = WALK_SPEED_PER_CELL * playerCellSize;
            this.player = new PlayerController(playerCells, playerGridW, playerGridH, playerGridD,
                    playerCellSize, new Vector3f(0f, 0f, 0f),
                    hh, r, PlayerController.DEFAULT_GRAVITY, jump, walk);
        }

        positionCameraAboveDungeon();
        playerMode = player != null;
        viewMode = ViewMode.FIRST_PERSON;
        mouse.controller = flyController;
        applyCursorModeForCurrentMode();
        if (playerMode) {
            spawnPlayerAtRoomZero();
        }
        Platforms.get().log("[dungeon-viewer] mesh ready verts=" + mesh.vertexCount()
                + " faces=" + mesh.faceCount() + " mode=" + (playerMode ? PLAYER : FLY_CAM));
        return run;
    }

    /**
     * Load a mesh file to fly round: it has no tile grid, so the player and its mode are dropped.
     *
     * @param path mesh file path to load
     * @throws IOException if the mesh file cannot be read
     */
    @Override
    public void loadModel(String path) throws IOException {
        player = null;
        playerMode = false;
        applyCursorModeForCurrentMode();
        super.loadModel(path);
        mesh = halfEdgeMesh;
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
     * The viewer's controls: the mouse looks, F walks the player or flies the camera, V swaps first
     * and third person while walking, and R resets the look; Esc stops walking through
     * {@link #escapePressed}.
     */
    @Override
    public void setControls() {
        controls.add(new ControlHint("drag", "look round; while walking every move looks"));
        controls.add(new ControlHint("scroll", "zoom the third-person camera"));
        controls.add(new ControlHint(Keys.F, "F", "walk the player or fly the camera", this::togglePlayerMode));
        controls.add(new ControlHint(Keys.V, "V", "first or third person while walking", this::toggleViewMode));
        controls.add(new ControlHint(Keys.R, "R", "reset the look", camera::reset));
        super.setControls();
    }

    /** Escape stops walking, else does what every model scene's does. */
    @Override
    public void escapePressed() {
        if (playerMode) {
            togglePlayerMode();
        } else {
            super.escapePressed();
        }
    }

    /**
     * The Escape row's text: stop walking, else the model scene's Escape.
     *
     * @return the description
     */
    @Override
    public String escapeDescription() {
        return "stop walking and fly, else " + super.escapeDescription();
    }

    /** Player mode = cursor captured (FPS look). Fly-cam = cursor visible (drag to rotate). */
    private void applyCursorModeForCurrentMode() {
        Platforms.get().setCursorMode(playerMode
                ? Platform.CursorMode.CAPTURED
                : Platform.CursorMode.NORMAL);
    }

    /** Per-frame: the hosted tools, then the player physics in player mode, before the view is drawn. */
    @Override
    public void updateScene() {
        super.updateScene();
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
    }

    /** Refresh the camera, render the dungeon mesh, and overlay the debug capsule in third person. */
    @Override
    public void renderScene() {
        camera.resetView();
        camera.updateViewFirstPerson();
        runtime.render(camera);
        if (playerMode && viewMode == ViewMode.THIRD_PERSON && player != null && capsuleRuntime != null) {
            Vector3f p = player.position();
            float yawRad = (float) Math.toRadians(player.facingYawDegrees());
            capsuleRuntime.render(camera, p.x(), p.y(), p.z(), yawRad,
                    player.radius(), player.halfHeight());
        }
    }

    /**
     * Scene activation hook. On deactivation, releases the capsule runtime as the model scene
     * releases the surface's, so resources don't leak when switching scenes.
     *
     * @param state {@code true} when the scene is being activated, {@code false} on deactivation
     */
    @Override
    public void activate(boolean state) {
        super.activate(state);
        if (!state && capsuleRuntime != null) {
            capsuleRuntime.dispose();
            capsuleRuntime = null;
        }
    }

    /**
     * Releases the GL runtimes and restores the OS cursor so the user can interact with the desktop
     * after the scene exits.
     */
    @Override
    public void shutdown() {
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
