package ixdar.tools.knot;

import java.util.ArrayList;
import java.util.HashMap;

import org.joml.Vector2f;

import ixdar.annotations.geometry.Geometry;
import ixdar.annotations.tool.ToolAnnotation;
import ixdar.geometry.tsp.knot.Knot;
import ixdar.geometry.tsp.knot.Segment;
import ixdar.geometry.grid2d.Grid;
import ixdar.geometry.tsp.input.PointCollection;
import ixdar.geometry.tsp.points.PointND;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorRGB;
import ixdar.gui.terminal.Terminal;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.text.HyperString;
import ixdar.scenes.main.Toggle;
import ixdar.scenes.main.MainScene;

/**
 * Map Editor tool: lets the user add, delete, move, group, and ungroup
 * point-collection elements on the active grid by clicking. Cycles through
 * its {@link States} via the layer next/prev controls.
 */
@ToolAnnotation(id = MapEditorTool.TOOL_NAME, host = MainScene.class)
public class MapEditorTool extends KnotTool {

    public static final String TOOL_NAME = "mapeditor";

    public static ArrayList<Color> colors;
    public static Class<? extends PointCollection> currentCollectionType;
    public static PointCollection currentCollection;

    private static HashMap<Class<? extends Geometry>, PointCollection> pointCollectionClassMap;

    public States state = States.Add;

    public Segment startSegment;
    public Knot startKP;
    public Knot startCP;

    public Vector2f hoverPoint;
    HashMap<Long, Integer> colorLookup;

    /**
     * Build the tool: disallow toggles incompatible with editing (cut-match
     * preview, layer switching, knot-gradient/metro/displayed-knots draw),
     * and snapshot the terminal's point-collection class map.
     *
     * @param editor the editor the tool works on
     */
    public MapEditorTool(MainScene editor) {
        super(editor);
        disallowedToggles = new Toggle[] { Toggle.DrawCutMatch, Toggle.CanSwitchLayer,
                Toggle.DrawKnotGradient, Toggle.DrawMetroDiagram, Toggle.DrawDisplayedKnots };
        layerKeysStep = "editing state";
        pointCollectionClassMap = Terminal.pointCollectionClassMap;
    }

    /**
     * Start in the {@link States#Add} mode for the grid's first
     * allowable point-collection type and re-issue the on-screen instruction.
     */
    @Override
    public void activate() {
        super.activate();
        state = States.Add;
        startSegment = null;
        startKP = null;
        startCP = null;
        colorLookup = null;
        editor.updateKnotsDisplayed();
        pointCollectionClassMap = Terminal.pointCollectionClassMap;
        currentCollectionType = editor.grid.allowableTypes()[0];
        currentCollection = pointCollectionClassMap.get(currentCollectionType);
        instruct();
    }

    /**
     * Project the mouse into world space and (when {@link Toggle#SnapToGrid}
     * is on) snap the resulting point to the nearest grid coordinate, caching
     * it in {@link #hoverPoint} for the next draw.
     *
     * @param mouseX framebuffer x of the cursor
     * @param mouseY framebuffer y of the cursor, y up
     */
    @Override
    public void onHover(float mouseX, float mouseY) {
        Camera2D camera = editor.editorCamera;
        camera.calculateCameraTransform(editor.retTup.ps);
        float x = camera.screenTransformX(mouseX - editor.mainViewOffsetX);
        float y = camera.screenTransformY(mouseY - editor.mainViewOffsetY);
        if (Toggle.SnapToGrid.value) {
            Grid grid = editor.grid;
            hoverPoint = grid.coordinateToNearestGridPoint(x, y);
            hoverPoint.x = camera.pointTransformX(hoverPoint.x);
            hoverPoint.y = camera.pointTransformY(hoverPoint.y);
        }
    }

    /**
     * Draw a red circle at the snapped hover point so the user can see where
     * the next click will land on the grid.
     *
     * @param camera the scene camera
     * @param minLineThickness base line thickness in pixels
     */
    @Override
    public void drawOverlay(Camera2D camera, float minLineThickness) {
        if (hoverPoint != null) {
            Drawing.drawCircle(hoverPoint, ColorRGB.RED, camera, minLineThickness);
        }
    }

    /**
     * Clicks are not the map editor's: its edits run through the terminal commands.
     *
     * @param mouseX framebuffer x of the click
     * @param mouseY framebuffer y of the click
     * @return false
     */
    @Override
    public boolean onClick(float mouseX, float mouseY) {
        return false;
    }

    /**
     * Confirm placeholder; concrete add/remove/group operations are wired up
     * elsewhere via the terminal commands.
     */
    @Override
    public void confirm() {
    }

    /**
     * Push a state-appropriate instruction string to the terminal banner so
     * the user knows what this state expects next.
     */
    public void instruct() {
        Terminal terminal = editor.terminal;
        switch (state) {
        case Add:
            terminal.instruct("Add a " + currentCollection.fullName() + " to the grid by clicking.");
            break;
        case Delete:
            terminal.instruct("Select any group and press enter to remove it from the grid.");
            break;
        case Group:
            terminal.instruct("Select any number of points and groups and press enter to group them together.");
            break;
        case UnGroup:
            terminal.instruct("Select any group and press enter to ungroup them.");
            break;
        case Move:
            terminal.instruct("Select any point or group and drag to move it.");
            break;
        default:
            terminal.clearInstruct();
            break;
        }
    }

    /**
     * Override the layer hotkey to cycle the editor {@link #state} forward
     * through {@link States} (wrapping at the end) instead of changing the
     * scene's draw layer.
     */
    @Override
    public void increaseViewLayer() {
        States[] states = States.values();
        state = state.ordinal() + 1 >= states.length ? states[0] : states[state.ordinal() + 1];
        infoText = null;
    }

    /**
     * Override the layer hotkey to cycle the editor {@link #state} backward
     * through {@link States} (wrapping at the start) instead of changing the
     * scene's draw layer.
     */
    @Override
    public void decreaseViewLayer() {
        States[] states = States.values();
        state = state.ordinal() - 1 < 0 ? states[states.length - 1]
                : states[state.ordinal() - 1];
        infoText = null;
    }

    /**
     * Build the side-panel text: current editor state plus the hover/selection
     * coordinate (grid coords, or the displayed knot point's coords).
     *
     * @return the formatted info text
     */
    @Override
    public HyperString buildInfoText() {
        HyperString h = new HyperString();
        h.addLine("Tool State: " + state.name());

        h.addWord("Position:");
        if (displayKP == null) {
            h.addWord(editor.grid.toCoordString(editor));
        } else {
            PointND coordPoint = (displayKP).p;
            h.addWord(coordPoint.toCoordString());
        }
        h.wrap();
        return h;
    }

    /**
     * {@inheritDoc}.
     *
     * @return the display name "Map Editor"
     */
    @Override
    public String displayName() {
        return "Map Editor";
    }

    /**
     * {@inheritDoc}.
     *
     * @return the full terminal name {@code "mapeditor"}
     */
    @Override
    public String toolName() {
        return TOOL_NAME;
    }

    /**
     * {@inheritDoc}.
     *
     * @return the short terminal alias {@code "me"}
     */
    @Override
    public String shortName() {
        return "me";
    }

    /**
     * {@inheritDoc}.
     *
     * @return the one-line description of this tool's purpose
     */
    @Override
    public String desc() {
        return "A tool that allows the user to add, move, or remove points in an ixdar file.";
    }

    /**
     * Editor sub-modes the user cycles through with the layer next/prev keys.
     */
    public enum States {
        Add,
        Delete,
        Move,
        Group,
        UnGroup;

        /**
         * Test whether this state is at or beyond {@code state} in the
         * declared enum order.
         *
         * @param state the threshold state
         * @return true if {@code this.ordinal() >= state.ordinal()}
         */
        public boolean atOrAfter(States state) {
            return this.ordinal() >= state.ordinal();
        }

        /**
         * Test whether this state precedes {@code state} in the declared enum
         * order.
         *
         * @param state the threshold state
         * @return true if {@code this.ordinal() < state.ordinal()}
         */
        public boolean before(States state) {
            return this.ordinal() < state.ordinal();
        }
    }
}
