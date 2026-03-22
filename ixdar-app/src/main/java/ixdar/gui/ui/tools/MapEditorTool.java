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

public class MapEditorTool extends Tool {

    public enum States {
        Add,
        Delete,
        Move,
        Group,
        UnGroup;

        public boolean atOrAfter(States state) {
            return this.ordinal() >= state.ordinal();
        }

        public boolean before(States state) {
            return this.ordinal() < state.ordinal();
        }
    }

    public States state = States.Add;

    public Segment startSegment;
    public Knot startKP;
    public Knot startCP;

    public Vector2f hoverPoint;
    HashMap<Long, Integer> colorLookup;

    public static ArrayList<Color> colors;

    private static HashMap<Class<? extends Geometry>, PointCollection> pointCollectionClassMap;
    public static Class<? extends PointCollection> currentCollectionType;
    public static PointCollection currentCollection;

    public MapEditorTool() {
        disallowedToggles = new Toggle[] { Toggle.DrawCutMatch, Toggle.CanSwitchLayer,
                Toggle.DrawKnotGradient, Toggle.DrawMetroDiagram, Toggle.DrawDisplayedKnots };
        pointCollectionClassMap = Terminal.pointCollectionClassMap;
    }

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

    @Override
    public void hoverChanged() {
    }

    @Override
    public void draw(Camera2D camera, float minLineThickness) {
        if (hoverPoint != null) {
            Drawing.drawCircle(hoverPoint, ColorRGB.RED, camera, minLineThickness);
        }
    }

    @Override
    public void calculateClick(float mouseX, float mouseY) {

    }

    @Override
    public void click(Segment s, Knot kp, Knot cp) {
        confirm();
    }

    @Override
    public void confirm() {
    }

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

    @Override
    public void increaseViewLayer() {
        States[] states = States.values();
        state = state.ordinal() + 1 >= states.length ? states[0] : states[state.ordinal() + 1];
    }

    @Override
    public void decreaseViewLayer() {
        States[] states = States.values();
        state = state.ordinal() - 1 < 0 ? states[states.length - 1]
                : states[state.ordinal() - 1];
    }

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

    @Override
    public String displayName() {
        return "Map Editor";
    }

    @Override
    public String fullName() {
        return "mapeditor";
    }

    @Override
    public String shortName() {
        return "me";
    }

    @Override
    public String desc() {
        return "A tool that allows the user to add, move, or remove points in an ixdar file.";
    }
}
