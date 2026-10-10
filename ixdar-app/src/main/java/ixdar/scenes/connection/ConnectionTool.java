package ixdar.scenes.connection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.ops.MeshRepairReport;
import ixdar.geometry.mesh.data.paths.SurfaceConnection;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.graphics.render.model.MeshOverlayRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.model.ControlHint;
import ixdar.scenes.ring.EditTool;
import ixdar.scenes.ring.RingScene;
import ixdar.scenes.ring.RingTool;

/**
 * The editing scene's connection tool: two picks on the surface show the shortest path joining
 * them and the narrowest cross-section on it, the neck, which Enter makes a ring to edit.
 */
public final class ConnectionTool implements EditTool {

    public static final String LOG_PREFIX = "[connection] ";

    public static final String TOOL_NAME = "connection";

    public static final String STATUS_LINE = "connection tool: click two places to pick them "
            + "(cyan anchors, yellow under the cursor; drag one to move it). Yellow is the shortest "
            + "path between them, green the narrowest cross-section on it, snapped to mesh edges. "
            + "Enter makes the green loop a ring and opens it in the ring tool; W rings as walls, "
            + "F repair_mesh fills in orange-red, C clears, Esc back to orbit";

    public static final String FILL_TAG = "repair_fill";

    public static final int XYZ = SurfaceConnection.COORDINATES_PER_POINT;

    public static final int PICKS = 2;

    /** Scene the tool runs on, whose ring tool holds the rings that act as walls. */
    public final RingScene scene;

    /** The connection over the shown surface, built when the tool first runs on it. */
    public SurfaceConnection connection;

    /** Whether the tool is the scene's active one. */
    public boolean active;

    /** Whether the rings bound the path and the cross-sections sampled along it. */
    public boolean ringsAreWalls = true;

    /** Whether the faces repair_mesh added are coloured on the surface. */
    public boolean showingFills;

    /** Vertex each pick sits on, start then end, -1 while unset; the end is set only after. */
    public final int[] pickVertexId = { -1, -1 };

    /** Active face each pick was made on, which the connection starts from, parallel. */
    public final int[] pickActiveFace = { -1, -1 };

    /** Pick under the cursor, which a press drags, or -1. */
    public int hoveredPick = -1;

    /** Pick being dragged along the surface, or -1. */
    public int draggingPick = -1;

    /** The neck snapped to mesh edges, its vertex ids in walking order; empty when none. */
    public int[] neckLoopVertexId = new int[0];

    /** Faces repair_mesh added to the shown surface, by active face index; empty when unknown. */
    public boolean[] filledByActiveFace = new boolean[0];

    /** The last action's outcome, as logged. */
    public String lastRow = "";

    /** Why the last action failed, or empty. */
    public String lastError = "";

    /** Drawn number of the ring the last Enter made, or -1. */
    public int confirmedRingNumber = -1;

    private int builtRingRevision = -1;
    private boolean pendingClick;
    private boolean overlayStale;
    private int uploadedHoveredPick = -1;
    private int grabbedVertexId = -1;
    private HalfEdgeMeshRuntime.ShaderMode shaderModeBefore;

    /**
     * Binds the tool to its scene.
     *
     * @param scene the editing scene whose surface and rings the tool reads
     */
    public ConnectionTool(RingScene scene) {
        this.scene = scene;
    }

    @Override
    public String toolName() {
        return TOOL_NAME;
    }

    /** Take clicks and pick drags from the orbit and show the connection the tool holds. */
    @Override
    public void activate() {
        active = true;
        if (scene.orbitMouse != null) {
            scene.orbitMouse.toolClick = button -> pendingClick = active;
            // A press on a pick keeps the drag for moving it rather than orbiting the camera.
            scene.orbitMouse.toolGrab = () -> {
                if (!active || hoveredPick < 0) {
                    return false;
                }
                draggingPick = hoveredPick;
                grabbedVertexId = pickVertexId[draggingPick];
                return true;
            };
            scene.orbitMouse.toolRelease = this::releasePick;
        }
        Platforms.get().log(LOG_PREFIX + STATUS_LINE);
        overlayStale = true;
        if (showingFills) {
            showFills(true);
        }
    }

    /** Hand the mouse back and take the drawing off the surface, keeping the picks. */
    @Override
    public void deactivate() {
        releasePick();
        active = false;
        pendingClick = false;
        hoveredPick = -1;
        if (scene.orbitMouse != null) {
            scene.orbitMouse.toolClick = null;
            scene.orbitMouse.toolGrab = null;
            scene.orbitMouse.toolRelease = null;
        }
        if (scene.surfaceRuntime() instanceof MeshOverlayRuntime overlay) {
            overlay.clearDiagnostic();
            overlay.clearMarkers();
        }
        if (showingFills) {
            showFills(false);
        }
    }

    @Override
    public void addControls(List<ControlHint> controls) {
        controls.add(new ControlHint("click", "pick two places (cyan)"));
        controls.add(new ControlHint("drag pick", "move it, re-run on release"));
        controls.add(new ControlHint(Keys.ENTER, "enter", "make the green neck a ring and edit it",
                this::confirmNeck));
        controls.add(new ControlHint(Keys.W, "W", "rings as walls on / off", () -> {
            ringsAreWalls = !ringsAreWalls;
            Platforms.get().log(LOG_PREFIX + "rings are " + (ringsAreWalls ? "" : "not ") + "walls");
            if (pickVertexId[1] >= 0) {
                run();
            }
        }));
        controls.add(new ControlHint(Keys.F, "F", "repair_mesh fills (orange-red) on / off", () -> {
            showingFills = !showingFills;
            if (active) {
                showFills(showingFills);
            }
        }));
        controls.add(new ControlHint(Keys.C, "C", "clear picks", () -> {
            Arrays.fill(pickVertexId, -1);
            draggingPick = -1;
            hoveredPick = -1;
            neckLoopVertexId = new int[0];
            if (connection != null) {
                connection.connected = false;
            }
            lastRow = "picks cleared";
            overlayStale = true;
        }));
    }

    /**
     * One frame while active: rebuild the face graph for a new surface, re-run when the rings
     * moved, pick under the cursor, move a dragged pick or find the hovered one, then run a
     * queued click.
     */
    @Override
    public void perFrame() {
        HalfEdgeMeshRuntime runtime = scene.surfaceRuntime();
        MeshTopology surface = scene.halfEdgeSurface();
        if (!active || runtime == null || surface == null || surface.faceCount() == 0) {
            pendingClick = false;
            return;
        }
        prepare(surface);
        if (builtRingRevision != scene.ringTool.ringRevision) {
            builtRingRevision = scene.ringTool.ringRevision;
            if (pickVertexId[1] >= 0) {
                run();
            }
        }
        if (!runtime.facePickReady()) {
            runtime.uploadFacePickBuffer(surface, scene.regionLayer.hiddenByActiveFace);
        }
        boolean hit = scene.pickCursor(runtime, surface);
        if (draggingPick >= 0) {
            if (hit && scene.cursorVertexId != pickVertexId[draggingPick]) {
                pickVertexId[draggingPick] = scene.cursorVertexId;
                pickActiveFace[draggingPick] = connection.activeFaceByFaceId[scene.cursorFaceId];
                overlayStale = true;
            }
        } else {
            int picked = pickVertexId[1] >= 0 ? 2 : pickVertexId[0] >= 0 ? 1 : 0;
            hoveredPick = hit ? scene.anchorUnderCursor(runtime, surface,
                    Arrays.copyOf(pickVertexId, picked)) : -1;
        }
        if (pendingClick) {
            pendingClick = false;
            lastError = "";
            if (!hit) {
                lastError = "the click missed the surface";
                Platforms.get().log(LOG_PREFIX + lastError);
            } else if (hoveredPick < 0) {
                int pick = pickVertexId[0] >= 0 && pickVertexId[1] < 0 ? 1 : 0;
                pickVertexId[pick] = scene.cursorVertexId;
                pickActiveFace[pick] = connection.activeFaceByFaceId[scene.cursorFaceId];
                if (pick == 1) {
                    run();
                } else {
                    pickVertexId[1] = -1;
                    connection.connected = false;
                    neckLoopVertexId = new int[0];
                    lastRow = "start picked; click the other place";
                    Platforms.get().log(LOG_PREFIX + lastRow);
                    overlayStale = true;
                }
            }
        }
        // Draw the connection over the surface, the path yellow and the neck green, and the picks
        // as the ring tool's anchor discs: cyan, the one under the cursor or dragged yellow and
        // larger, drawn last so it wins an overlap.
        if (!overlayStale && hoveredPick == uploadedHoveredPick
                || !(runtime instanceof MeshOverlayRuntime overlay)) {
            return;
        }
        overlayStale = false;
        uploadedHoveredPick = hoveredPick;
        if (pickVertexId[0] < 0) {
            overlay.clearDiagnostic();
            overlay.clearMarkers();
            return;
        }
        SurfaceConnection shown = connection;
        List<float[]> polylines = new ArrayList<>();
        List<Color> colors = new ArrayList<>();
        boolean joined = pickVertexId[1] >= 0 && shown.connected && draggingPick < 0;
        Vector3f position = new Vector3f();
        if (joined) {
            polylines.add(shown.pathPolyline);
            colors.add(Color.CONNECTION_PATH);
            if (neckLoopVertexId.length > 0) {
                float[] neck = new float[XYZ * (neckLoopVertexId.length + 1)];
                for (int index = 0; index <= neckLoopVertexId.length; index++) {
                    shown.mesh.vertexPosition(neckLoopVertexId[index % neckLoopVertexId.length],
                            position);
                    neck[XYZ * index] = position.x;
                    neck[XYZ * index + 1] = position.y;
                    neck[XYZ * index + 2] = position.z;
                }
                polylines.add(neck);
                colors.add(Color.CONNECTION_NECK);
            }
        }
        int emphasised = draggingPick >= 0 ? draggingPick : hoveredPick;
        int picked = pickVertexId[1] >= 0 ? PICKS : 1;
        int[] discVertexId = new int[picked];
        int[] discColor = new int[picked];
        float[] discDiameter = new float[picked];
        int disc = 0;
        for (int pick = 0; pick < picked; pick++) {
            if (pick != emphasised) {
                discVertexId[disc] = pickVertexId[pick];
                discColor[disc] = RingTool.AUTHORED_ANCHOR_COLOR;
                discDiameter[disc++] = RingTool.AUTHORED_ANCHOR_PIXELS;
            }
        }
        if (emphasised >= 0 && emphasised < picked) {
            discVertexId[disc] = pickVertexId[emphasised];
            discColor[disc] = RingTool.SELECTED_ANCHOR_COLOR;
            discDiameter[disc] = RingTool.SELECTED_ANCHOR_PIXELS;
        }
        overlay.setDiagnostic(List.of(), List.of(), polylines, colors);
        overlay.setAnchorDiscs(discVertexId, discColor, discDiameter);
        if (joined) {
            overlay.capDiagnosticRegion((float) shown.pathLength);
        }
    }

    /**
     * Join two surface positions as two clicks there would: each pick goes on the face nearest
     * its position, at that face's corner nearest it.
     *
     * @param from first position, packed xyz
     * @param to   second position, packed xyz
     * @return true when the two are joined and a neck was found
     */
    public boolean connectPoints(float[] from, float[] to) {
        MeshTopology surface = scene.halfEdgeSurface();
        if (surface == null || surface.faceCount() == 0) {
            lastError = "no surface is shown";
            return false;
        }
        prepare(surface);
        builtRingRevision = scene.ringTool.ringRevision;
        Vector3f corner = new Vector3f();
        float[][] positions = { from, to };
        for (int pick = 0; pick < PICKS; pick++) {
            float[] position = positions[pick];
            int face = connection.nearestActiveFace(position[0], position[1], position[2]);
            int faceId = surface.faceIdAt(face);
            double nearest = Double.POSITIVE_INFINITY;
            for (int index = 0; index < surface.faceVertexCount(faceId); index++) {
                int vertexId = surface.faceVertexAt(faceId, index);
                surface.vertexPosition(vertexId, corner);
                double distance = corner.distance(position[0], position[1], position[2]);
                if (distance < nearest) {
                    nearest = distance;
                    pickVertexId[pick] = vertexId;
                }
            }
            pickActiveFace[pick] = face;
        }
        return run();
    }

    /**
     * Build the face graph and read repair_mesh's fill faces when the shown surface changed,
     * dropping picks made on the old one.
     *
     * @param surface the shown surface
     */
    public void prepare(MeshTopology surface) {
        if (connection != null && connection.mesh == surface) {
            return;
        }
        long start = System.nanoTime();
        connection = new SurfaceConnection(surface);
        Arrays.fill(pickVertexId, -1);
        draggingPick = -1;
        hoveredPick = -1;
        neckLoopVertexId = new int[0];
        filledByActiveFace = new boolean[0];
        MeshRepairReport repair = scene.getLastRepairReport();
        if (repair != null && repair.outputFaceCount == surface.faceCount()) {
            filledByActiveFace = new boolean[surface.faceCount()];
            for (int face : repair.fillFaceIndices) {
                filledByActiveFace[face] = true;
            }
        }
        connection.filledByActiveFace = filledByActiveFace;
        Platforms.get().log(String.format(Locale.ROOT, LOG_PREFIX + "face graph of %d faces, "
                + "%d repair_mesh fill faces, in %.0f ms", connection.faceCount,
                repair == null ? 0 : repair.fillFaceIndices.length,
                (System.nanoTime() - start) / SurfaceConnection.NANOS_PER_MILLI));
    }

    /**
     * Connect the two picks with the rings as walls or not, order the neck's snapped edges into
     * a loop, log what was found and redraw.
     *
     * @return true when the picks are joined and the neck is one closed edge loop
     */
    public boolean run() {
        lastError = "";
        neckLoopVertexId = new int[0];
        if (connection == null || pickVertexId[1] < 0) {
            lastError = "pick two places first";
            return false;
        }
        boolean[] walls = new boolean[0];
        if (ringsAreWalls) {
            for (boolean[] marks : scene.ringTool.liveRingMarks().values()) {
                if (marks == null) {
                    continue;
                }
                if (walls.length < marks.length) {
                    walls = Arrays.copyOf(walls, marks.length);
                }
                for (int edgeId = 0; edgeId < marks.length; edgeId++) {
                    walls[edgeId] |= marks[edgeId];
                }
            }
        }
        connection.wallByEdgeId = walls;
        boolean found = connection.connect(pickActiveFace[0], pickActiveFace[1]);
        overlayStale = true;
        SurfaceConnection joined = connection;
        if (!joined.connected) {
            lastError = joined.failure + (ringsAreWalls ? " (rings are walls; W lifts them)" : "");
            lastRow = "not joined";
            Platforms.get().log(LOG_PREFIX + lastError);
            return false;
        }
        if (found) {
            neckLoopVertexId = RingTool.orderedLoop(joined.mesh, joined.narrowestMarkedByEdgeId);
            found = neckLoopVertexId.length > 0;
        }
        if (!found) {
            lastError = joined.failure.isEmpty()
                    ? "the neck's snapped edges are not one closed loop" : joined.failure;
        }
        lastRow = String.format(Locale.ROOT, "path %.4f over %d faces%s; neck %.4f around, "
                + "%d edges, at %s (widest %.4f%s); %.0f ms path, %.0f ms cross-sections",
                joined.pathLength, joined.pathActiveFace.length,
                joined.pathFilledFaceCount == 0 ? ""
                        : " (" + joined.pathFilledFaceCount + " repair_mesh fill faces)",
                joined.narrowestGirth, neckLoopVertexId.length, xyzText(joined.narrowestPoint),
                joined.widestGirth, joined.girthsCrossingWalls == 0 ? ""
                        : ", " + joined.girthsCrossingWalls + " crossing rings passed over",
                joined.pathMillis, joined.girthMillis);
        Platforms.get().log(LOG_PREFIX + lastRow + (lastError.isEmpty() ? "" : "; " + lastError));
        return found;
    }

    /**
     * Enter: confirm the neck as an unsaved ring, then switch to the ring tool with that ring
     * open as the draft, its anchors ready to drag, add and delete.
     *
     * @return true when the ring tool holds the neck as its draft
     */
    public boolean confirmNeck() {
        confirmedRingNumber = -1;
        lastError = "";
        RingTool rings = scene.ringTool;
        if (neckLoopVertexId.length == 0) {
            lastError = "no neck to confirm";
        } else if (rings.draft != null) {
            lastError = "the ring tool has a draft open: confirm or discard it there first";
        } else {
            int ring = rings.confirmLoop(neckLoopVertexId, "neck");
            if (ring >= 0) {
                confirmedRingNumber = rings.drawnRingNumber(ring);
                scene.switchTool(rings);
                if (rings.reopenRing(ring)) {
                    lastRow = "neck confirmed as ring " + confirmedRingNumber + " and open in the "
                            + "ring tool: drag, add or delete its anchors, Enter confirms, Ctrl+S "
                            + "saves";
                    Platforms.get().log(LOG_PREFIX + lastRow);
                    return true;
                }
            }
            lastError = rings.lastError;
        }
        Platforms.get().log(LOG_PREFIX + lastError);
        return false;
    }

    /**
     * Colour the faces repair_mesh added on the surface, or restore its shading.
     *
     * @param show whether to colour them
     */
    public void showFills(boolean show) {
        HalfEdgeMeshRuntime runtime = scene.surfaceRuntime();
        MeshTopology surface = scene.halfEdgeSurface();
        if (runtime == null || surface == null) {
            return;
        }
        if (!show) {
            runtime.clearTags();
            runtime.clearTagColors();
            if (shaderModeBefore != null) {
                runtime.setShaderMode(shaderModeBefore);
            }
            return;
        }
        int fills = 0;
        for (boolean filled : filledByActiveFace) {
            fills += filled ? 1 : 0;
        }
        if (fills == 0) {
            lastError = "no repair_mesh fill faces are known for this surface";
            Platforms.get().log(LOG_PREFIX + lastError);
            return;
        }
        Map<Integer, Integer> activeVertexById = new HashMap<>();
        for (int activeVertex = 0; activeVertex < surface.vertexCount(); activeVertex++) {
            activeVertexById.put(surface.vertexIdAt(activeVertex), activeVertex);
        }
        boolean[] mask = new boolean[surface.vertexCount()];
        for (int face = 0; face < filledByActiveFace.length; face++) {
            if (!filledByActiveFace[face]) {
                continue;
            }
            int faceId = surface.faceIdAt(face);
            for (int corner = 0; corner < surface.faceVertexCount(faceId); corner++) {
                mask[activeVertexById.get(surface.faceVertexAt(faceId, corner))] = true;
            }
        }
        shaderModeBefore = runtime.getShaderMode();
        runtime.clearTagColors();
        runtime.setTagColor(FILL_TAG, Color.CONNECTION_REPAIR_FILL.toVector4f());
        runtime.setShaderMode(HalfEdgeMeshRuntime.ShaderMode.STAGES);
        runtime.setTags(Map.of(FILL_TAG, mask));
        lastRow = fills + " repair_mesh fill faces coloured";
        Platforms.get().log(LOG_PREFIX + lastRow);
    }

    /** The drag ended: re-run the connection when the pick moved. */
    public void releasePick() {
        if (draggingPick < 0) {
            return;
        }
        boolean moved = pickVertexId[draggingPick] != grabbedVertexId;
        draggingPick = -1;
        if (moved && pickVertexId[1] >= 0) {
            run();
        }
        overlayStale = true;
    }

    /**
     * A point as text.
     *
     * @param xyz packed xyz
     * @return the point as {@code x,y,z} to five places
     */
    public static String xyzText(float[] xyz) {
        return String.format(Locale.ROOT, "%.5f,%.5f,%.5f", xyz[0], xyz[1], xyz[2]);
    }
}
