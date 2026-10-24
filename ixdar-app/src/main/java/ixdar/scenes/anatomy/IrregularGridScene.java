package ixdar.scenes.anatomy;

import org.joml.Vector2f;

import ixdar.annotations.command.Subsystem;
import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.grid2d.IrregularQuadLayoutGenerator;
import ixdar.geometry.grid2d.IrregularQuadGrid;
import ixdar.geometry.tsp.points.PointND;
import ixdar.geometry.tsp.shell.Shell;
import ixdar.platform.Clock;
import ixdar.graphics.render.color.Color;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.gui.ui.text.HyperString;
import ixdar.input.CanvasScroll;
import ixdar.input.PointerDispatcher;
import ixdar.platform.input.Keys;
import ixdar.scenes.Scene;
import ixdar.tools.ControlHint;

@SceneAnnotation(id = "irregular-grid-canvas")
public class IrregularGridScene extends Scene {
    public static final float LINE_WIDTH_SCALE = 2f;
    public static final float ANCHOR_DOT_RADIUS = 2f;
    public static final float DUAL_DOT_RADIUS = 3f;
    public static final float TEXT_Y_OFFSET = 10f;

    public static final String VIEW_MAIN = "MAIN";
    private static final long SEED = 123L;
    private static final int RELAX_ITERS = 8;
    private static final int HEX_RADIUS = 5;
    private static final float TRIANGLE_SIZE = 0.12f;
    private static final float JITTER = 0f;

    private IrregularQuadGrid grid;
    private HyperString fpsText;

    private final Vector2f tmpA = new Vector2f();
    private final Vector2f tmpB = new Vector2f();
    private final Vector2f tmpScreen = new Vector2f();

    /**
     * Initialize input handlers, the 2D camera's scroll taking the wheel over its view and the
     * drag, build the Townscaper-style hex/quad grid and frame the camera around it.
     */
    @Override
    public void initGL() {
        super.initGL();
        MenuBox.menuVisible = false;
        mouse.camera = camera2D;
        CanvasScroll gridScroll = new CanvasScroll(camera2D, false, () -> active);
        mouse.controller = gridScroll;
        PointerDispatcher.current().subscribe(camera2D.mainViewBounds, gridScroll);
        buildGrid();
        centerCameraOnGrid();
        fpsText = new HyperString();
    }

    /**
     * The grid's one key: R frames the grid again.
     */
    @Override
    public void setControls() {
        controls.add(new ControlHint(Keys.R, "R", "reset the camera", camera2D::reset));
        super.setControls();
    }

    /**
     * Per-frame: draw all primal edges as gray SDF lines, draw cyan dots
     * at primal anchors and orange dots at dual points, then overlay the
     * FPS readout. Input is pumped once per frame by {@link #paintGL()}.
     */
    @Override
    public void drawScene() {
        camera2D.updateView(VIEW_MAIN);
        camera2D.setZIndex(camera);
        camera2D.calculateCameraTransform(camera2D.ps);
        Drawing.getDrawing().sdfLine.setCulling(false);
        Drawing.getDrawing().sdfLine.setStroke(LINE_WIDTH_SCALE * camera2D.ScaleFactor, false, 1f, JITTER, true, false, false);
        for (int i = 0; i < grid.edgeIndices().size(); i++) {
            int[] edge = grid.edgeIndices().get(i);
            Vector2f from = grid.anchorPoints().get(edge[0]);
            Vector2f to = grid.anchorPoints().get(edge[1]);
            tmpA.set(camera2D.pointTransformX(from.x), camera2D.pointTransformY(from.y));
            tmpB.set(camera2D.pointTransformX(to.x), camera2D.pointTransformY(to.y));
            Drawing.getDrawing().sdfLine.draw(tmpA, tmpB, Color.GREY_DARK, camera2D);
        }
        for (Vector2f pt : grid.anchorPoints()) {
            tmpScreen.set(camera2D.pointTransformX(pt.x), camera2D.pointTransformY(pt.y));
            Drawing.drawCircle(tmpScreen, Color.TEAL, camera2D, ANCHOR_DOT_RADIUS);
        }
        for (Vector2f pt : grid.dualPoints()) {
            tmpScreen.set(camera2D.pointTransformX(pt.x), camera2D.pointTransformY(pt.y));
            Drawing.drawCircle(tmpScreen, Color.BROWN_DARK, camera2D, DUAL_DOT_RADIUS);
        }
        fpsText = new HyperString();
        fpsText.addWord("FPS: " + Clock.fps(), Color.TEAL);
        Drawing.getDrawing().font.drawHyperStringRows(fpsText, 0, TEXT_Y_OFFSET, Drawing.FONT_HEIGHT_PIXELS, camera2D);
        Drawing.getDrawing().sdfLine.setCulling(true);
    }

    /**
     * The scene shows a grid: the irregular quad grid it built.
     *
     * @param subsystem a scene-held subsystem
     * @return the grid for {@link Subsystem#GRID}, null until it is built, else the base's
     */
    @Override
    public Object dataset(Subsystem subsystem) {
        return subsystem == Subsystem.GRID ? grid : super.dataset(subsystem);
    }

    private void buildGrid() {
        IrregularQuadLayoutGenerator.Layout layout = IrregularQuadLayoutGenerator.generateTownscaperHex(HEX_RADIUS,
                TRIANGLE_SIZE, SEED, RELAX_ITERS);
        grid = new IrregularQuadGrid(layout, SEED, RELAX_ITERS, JITTER);
    }

    private void centerCameraOnGrid() {
        if (grid == null) {
            return;
        }
        Shell camShell = new Shell();
        for (Vector2f point : grid.anchorPoints()) {
            camShell.add(new PointND.Double(point.x, point.y));
        }
        for (Vector2f point : grid.dualPoints()) {
            camShell.add(new PointND.Double(point.x, point.y));
        }
        camera2D.ps = camShell.toPointSet();
        camera2D.calculateCameraTransform(camera2D.ps);
        camera2D.reset();
    }

}
