package ixdar.gui.ui.tools;

import java.util.ArrayList;
import java.util.HashMap;

import org.joml.Vector2f;

import ixdar.annotations.geometry.Geometry;
import ixdar.geometry.knot.Knot;
import ixdar.geometry.knot.Segment;
import ixdar.geometry.point.Grid;
import ixdar.geometry.point.PointCollection;
import ixdar.geometry.point.PointND;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorRGB;
import ixdar.graphics.render.text.HyperString;
import ixdar.gui.terminal.Terminal;
import ixdar.gui.ui.Drawing;
import ixdar.platform.Toggle;
import ixdar.scenes.main.MainScene;

/**
 * Map Editor tool: lets the user add, delete, move, group, and ungroup
 * point-collection elements on the active grid by clicking. Cycles through
 * its {@link States} via the layer next/prev controls.
 */
public class MapEditorTool extends Tool {

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
     */
    public MapEditorTool() {
        disallowedToggles = new Toggle[] { Toggle.DrawCutMatch, Toggle.CanSwitchLayer,
                Toggle.DrawKnotGradient, Toggle.DrawMetroDiagram, Toggle.DrawDisplayedKnots };
        pointCollectionClassMap = Terminal.pointCollectionClassMap;
    }

    /**
     * Reset editor state to the {@link States#Add} mode for the grid's first
     * allowable point-collection type and re-issue the on-screen instruction.
     */
    @Override
    public void reset() {
        super.reset();
        state = States.Add;
        startSegment = null;
        startKP = null;
        startCP = null;
        colorLookup = null;
        MainScene.updateKnotsDisplayed();
        pointCollectionClassMap = Terminal.pointCollectionClassMap;
        currentCollectionType = MainScene.grid.allowableTypes()[0];
        currentCollection = pointCollectionClassMap.get(currentCollectionType);
        instruct();
    }

    /**
     * Project the mouse into world space and (when {@link Toggle#SnapToGrid}
     * is on) snap the resulting point to the nearest grid coordinate, caching
     * it in {@link #hoverPoint} for the next draw.
     *
     * @param mouseX cursor x in window pixels (pre screen-offset)
     * @param mouseY cursor y in window pixels (pre screen-offset)
     */
    @Override
    public void calculateHover(float mouseX, float mouseY) {
        mouseX = mouseX - ScreenOffsetX;
        mouseY = mouseY - ScreenOffsetY;
        Camera2D camera = MainScene.camera;
        camera.calculateCameraTransform(MainScene.retTup.ps);
        float x = camera.screenTransformX(mouseX);
        float y = camera.screenTransformY(mouseY);
        if (Toggle.SnapToGrid.value) {
            Grid grid = MainScene.grid;
            hoverPoint = grid.coordinateToNearestGridPoint(x, y);
            hoverPoint.x = camera.pointTransformX(hoverPoint.x);
            hoverPoint.y = camera.pointTransformY(hoverPoint.y);
        }
    }

    /**
     * Hook called when the hover target changes; intentionally a no-op here
     * because all hover feedback is driven from {@link #calculateHover}.
     */
    @Override
    public void hoverChanged() {
    }

    /**
     * Draw a red circle at the snapped hover point so the user can see where
     * the next click will land on the grid.
     *
     * @param camera the scene camera
     * @param minLineThickness base line thickness in pixels
     */
    @Override
    public void draw(Camera2D camera, float minLineThickness) {
        if (hoverPoint != null) {
            Drawing.drawCircle(hoverPoint, ColorRGB.RED, camera, minLineThickness);
        }
    }

    /**
     * Click hit-testing is handled inline by the editor states rather than
     * via the inherited segment hit-test, so this hook is intentionally empty.
     *
     * @param mouseX cursor x in window pixels
     * @param mouseY cursor y in window pixels
     */
    @Override
    public void calculateClick(float mouseX, float mouseY) {

    }

    /**
     * On any segment click, simply confirm the pending edit.
     *
     * @param s  hovered segment, ignored
     * @param kp segment knot point, ignored
     * @param cp segment cut point, ignored
     */
    @Override
    public void click(Segment s, Knot kp, Knot cp) {
        confirm();
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
        switch (state) {
        case Add:
            MainScene.terminal.instruct("Add a " + currentCollection.fullName() + " to the grid by clicking.");
            break;
        case Delete:
            MainScene.terminal.instruct("Select any group and press enter to remove it from the grid.");
            break;
        case Group:
            MainScene.terminal.instruct("Select any number of points and groups and press enter to group them together.");
            break;
        case UnGroup:
            MainScene.terminal.instruct("Select any group and press enter to ungroup them.");
            break;
        case Move:
            MainScene.terminal.instruct("Select any point or group and drag to move it.");
            break;
        default:
            MainScene.terminal.clearInstruct();
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
            h.addWord(MainScene.grid.toCoordString());
        } else {
            PointND coordPoint = (displayKP).p;
            h.addWord(coordPoint.toCoordString());
        }
        h.wrap();
        return h;
    }

    /**
     * @return the display name "Map Editor"
     */
    @Override
    public String displayName() {
        return "Map Editor";
    }

    /**
     * @return the full terminal name {@code "mapeditor"}
     */
    @Override
    public String fullName() {
        return "mapeditor";
    }

    /**
     * @return the short terminal alias {@code "me"}
     */
    @Override
    public String shortName() {
        return "me";
    }

    /**
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
