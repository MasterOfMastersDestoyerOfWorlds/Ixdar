package shell.ui.tools;

import java.util.ArrayList;
import java.util.HashMap;

import org.joml.Vector2f;

import shell.Toggle;
import shell.cameras.Camera2D;
import shell.cuts.Manifold;
import shell.cuts.route.Route;
import shell.knot.Segment;
import shell.knot.Knot;
import shell.point.Grid;
import shell.point.PointCollection;
import shell.point.PointND;
import shell.render.color.Color;
import shell.render.color.ColorRGB;
import shell.render.text.HyperString;
import shell.terminal.Terminal;
import shell.ui.Drawing;
import shell.ui.main.Main;

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

    public Manifold displayManifold;
    public Route displayRouteAlpha;
    public Route displayRouteBeta;

    public Segment startSegment;
    public Knot startKP;
    public Knot startCP;

    public Vector2f hoverPoint;
    HashMap<Long, Integer> colorLookup;

    public static ArrayList<Color> colors;

    private static HashMap<Class<PointCollection>, PointCollection> pointCollectionClassMap;
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
        displayManifold = null;
        startSegment = null;
        startKP = null;
        startCP = null;
        colorLookup = null;
        displayRouteAlpha = null;
        displayRouteBeta = null;
        Main.knotDrawLayer = Main.shell.cutEngine.totalLayers;
        Main.updateKnotsDisplayed();
        pointCollectionClassMap = Terminal.pointCollectionClassMap;
        currentCollectionType = Main.grid.allowableTypes()[0];
        currentCollection = pointCollectionClassMap.get(currentCollectionType);
        instruct();
    }

    @Override
    public void calculateHover(float mouseX, float mouseY) {
        mouseX = mouseX - ScreenOffsetX;
        mouseY = mouseY - ScreenOffsetY;
        Camera2D camera = Main.camera;
        camera.calculateCameraTransform();
        float x = camera.screenTransformX(mouseX);
        float y = camera.screenTransformY(mouseY);
        if (Toggle.SnapToGrid.value) {
            Grid grid = Main.grid;
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
            Main.terminal.instruct("Add a " + currentCollection.fullName() + " to the grid by clicking.");
            break;
        case Delete:
            Main.terminal.instruct("Select any group and press enter to remove it from the grid.");
            break;
        case Group:
            Main.terminal.instruct("Select any number of points and groups and press enter to group them together.");
            break;
        case UnGroup:
            Main.terminal.instruct("Select any group and press enter to ungroup them.");
            break;
        case Move:
            Main.terminal.instruct("Select any point or group and drag to move it.");
            break;
        default:
            Main.terminal.clearInstruct();
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
            h.addWord(Main.grid.toCoordString());
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
