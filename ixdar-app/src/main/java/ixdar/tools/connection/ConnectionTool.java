package ixdar.tools.connection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.annotations.tool.ToolAnnotation;
import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.ops.MeshRepairReport;
import ixdar.geometry.mesh.data.paths.query.SurfaceConnection;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.graphics.render.model.MeshOverlayRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.input.KeyNames;
import ixdar.platform.input.Keys;
import ixdar.scenes.ring.RingScene;
import ixdar.tools.ControlHint;
import ixdar.tools.Draft;
import ixdar.tools.Item;
import ixdar.tools.Preview;
import ixdar.tools.Tool;
import ixdar.tools.ring.RingTool;

/**
 * The editing scene's connection tool: two picks on the surface show the shortest path joining
 * them and the narrowest cross-section on it, the neck, which Enter makes a ring to edit.
 */
@ToolAnnotation(id = ConnectionTool.TOOL_NAME)
public final class ConnectionTool extends Tool {

    public static final String LOG_PREFIX = "[connection] ";

    public static final String TOOL_NAME = "connection";

    public static final String FILL_TAG = "repair_fill";

    public static final int XYZ = SurfaceConnection.COORDINATES_PER_POINT;

    public static final int PICKS = 2;

    public static final String START_PICK = "start";

    public static final String NECK = "neck";

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

    @Override
    public String desc() {
        return "Pick two surface places (cyan) to see the shortest path "
                + "joining them (yellow) and its narrowest cross-section snapped to mesh edges, the neck (green), "
                + "which can become a ring in the ring tool";
    }

    /**
     * The two picks, start then end, with the one being dragged selected and the one under the
     * cursor hovered; valid once both are set.
     *
     * @return the picks, or {@code null} before the first
     */
    @Override
    public Draft draft() {
        if (pickVertexId[0] < 0) {
            return null;
        }
        Draft picks = new Draft();
        boolean both = pickVertexId[1] >= 0;
        picks.points = scene.ringTool.positionsOf(both ? pickVertexId : new int[] { pickVertexId[0] });
        picks.pointLabels = both ? new String[] { START_PICK, "end" } : new String[] { START_PICK };
        picks.selectedPoint = draggingPick;
        picks.hoveredPoint = hoveredPick;
        picks.valid = both;
        picks.label = "picks";
        picks.size = picks.pointLabels.length;
        picks.detail = ringsAreWalls ? "rings are walls" : "rings are not walls";
        return picks;
    }

    /**
     * The traced connection: the neck's narrowest point, whether the picks are joined, the time
     * the path and the cross-sections took, and the path and neck as the detail.
     *
     * @return the preview, or {@code null} before the picks were joined once
     */
    @Override
    public Preview preview() {
        if (connection == null) {
            return null;
        }
        return new Preview(connection.narrowestPoint, connection.connected,
                connection.pathMillis + connection.girthMillis, lastRow);
    }

    /**
     * The joined path and the neck on it: the path by its faces and length, the neck by its loop's
     * edges, girth and fingerprint at its narrowest point.
     *
     * @return the path then the neck, empty while the picks are not joined
     */
    @Override
    public List<Item> items() {
        List<Item> found = new ArrayList<>();
        if (connection == null || !connection.connected) {
            return found;
        }
        Item path = new Item();
        path.label = "path";
        int middle = connection.pathPolyline.length / XYZ / 2;
        if (middle * XYZ + XYZ <= connection.pathPolyline.length) {
            System.arraycopy(connection.pathPolyline, middle * XYZ, path.location, 0, XYZ);
        }
        path.size = connection.pathActiveFace.length;
        path.length = connection.pathLength;
        path.detail = connection.pathFilledFaceCount + " repair_mesh fill faces";
        found.add(path);
        Item neck = new Item();
        neck.label = NECK;
        System.arraycopy(connection.narrowestPoint, 0, neck.location, 0, XYZ);
        neck.size = neckLoopVertexId.length;
        neck.length = connection.narrowestGirth;
        neck.fingerprint = EdgeMarks.fingerprint(connection.mesh,
                connection.narrowestMarkedByEdgeId);
        neck.detail = String.format(Locale.ROOT, "widest %.5f, %d crossing rings passed over",
                connection.widestGirth, connection.girthsCrossingWalls);
        found.add(neck);
        return found;
    }

    /** Show the connection the tool holds; clicks and pick drags arrive through the pointer callbacks. */
    @Override
    public void activate() {
        active = true;
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
                () -> {
                    confirmNeck();
                    scene.errorToasts.show(TOOL_NAME, lastError);
                }));
        controls.add(new ControlHint(Keys.W, "W", "rings as walls on / off", () -> {
            ringsAreWalls = !ringsAreWalls;
            Platforms.get().log(LOG_PREFIX + "rings are " + (ringsAreWalls ? "" : "not ") + "walls");
            if (pickVertexId[1] >= 0 && !run()) {
                scene.errorToasts.show(TOOL_NAME, lastError);
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
            if (pendingClick) {
                lastError = RingScene.NO_SURFACE_REFUSAL;
                scene.errorToasts.show(TOOL_NAME, lastError);
            }
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
                lastError = scene.cursorMissReason;
            } else if (hoveredPick >= 0) {
                lastError = "that place is already picked - drag it to move it, or C clears both";
            } else {
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
            scene.errorToasts.show(TOOL_NAME, lastError);
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
            colors.add(Color.YELLOW_ORANGE);
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
                colors.add(Color.GREEN_TEAL);
            }
        }
        int emphasised = draggingPick >= 0 ? draggingPick : hoveredPick;
        int picked = pickVertexId[1] >= 0 ? PICKS : 1;
        int[] discVertexId = new int[picked];
        Color[] discColor = new Color[picked];
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
            lastError = joined.failure + (ringsAreWalls
                    ? " - the rings are walls; press W to lift them, or pick another place"
                    : " - pick another place");
            lastRow = "not joined";
            return false;
        }
        if (found) {
            neckLoopVertexId = RingTool.orderedLoop(joined.mesh, joined.narrowestMarkedByEdgeId);
            found = neckLoopVertexId.length > 0;
        }
        if (!found) {
            lastError = (joined.failure.isEmpty()
                    ? "the neck's snapped edges are not one closed loop" : joined.failure)
                    + " - drag a pick to try another neck";
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
            lastError = "no neck to confirm - click two places to find the green neck first";
        } else if (rings.draft != null) {
            lastError = "the ring tool has a draft open - Ctrl+R, then confirm (Enter) or discard "
                    + "(X) it";
        } else {
            int ring = rings.confirmLoop(neckLoopVertexId, NECK);
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
            lastError = "no repair_mesh fill faces are known for this surface - nothing to colour; "
                    + "F again turns this off";
            scene.errorToasts.show(TOOL_NAME, lastError);
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
        runtime.setTagColor(FILL_TAG, Color.RED_ORANGE.toVector4f());
        runtime.setShaderMode(HalfEdgeMeshRuntime.ShaderMode.STAGES);
        runtime.setTags(Map.of(FILL_TAG, mask));
        lastRow = fills + " repair_mesh fill faces coloured";
        Platforms.get().log(LOG_PREFIX + lastRow);
    }

    /**
     * A press on a pick keeps the drag for moving it rather than orbiting the camera; Shift leaves
     * the drag to the camera's pan.
     *
     * @param x    framebuffer x of the press, unused: the frame's surface pick moves the pick
     * @param y    framebuffer y of the press, unused
     * @param mods modifier-key bitmask of the press
     * @return true when the tool took the drag
     */
    @Override
    public boolean onPress(float x, float y, int mods) {
        if (!active || hoveredPick < 0 || (mods & KeyNames.MOD_SHIFT) != 0) {
            return false;
        }
        draggingPick = hoveredPick;
        grabbedVertexId = pickVertexId[draggingPick];
        return true;
    }

    /** The drag ended: re-run the connection when the pick moved. */
    public void releasePick() {
        if (draggingPick < 0) {
            return;
        }
        boolean moved = pickVertexId[draggingPick] != grabbedVertexId;
        draggingPick = -1;
        if (moved && pickVertexId[1] >= 0 && !run()) {
            scene.errorToasts.show(TOOL_NAME, lastError);
        }
        overlayStale = true;
    }

    /**
     * End a pick drag, then queue a click for the next frame, which picks the surface under the
     * cursor first.
     *
     * @param x     framebuffer x of the release, unused
     * @param y     framebuffer y of the release, unused
     * @param click true when the press did not move
     * @return {@code click}: every click is the tool's
     */
    @Override
    public boolean onRelease(float x, float y, boolean click) {
        releasePick();
        if (click) {
            pendingClick = active;
        }
        return click;
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
