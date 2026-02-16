package ixdar.scenes.anatomy;

import org.joml.Vector2f;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.canvas.Canvas3D;
import ixdar.game.IrregularQuadLayoutGenerator;
import ixdar.geometry.point.IrregularQuadGrid;
import ixdar.geometry.point.PointND;
import ixdar.geometry.shell.Shell;
import ixdar.graphics.render.Clock;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.text.HyperString;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.platform.Platforms;
import ixdar.platform.automation.AutomationInputBinder;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.MouseTrap;
import ixdar.platform.input.SceneInputFrameUpdater;
import ixdar.platform.input.Scene2DMousePanTrap;

@SceneAnnotation(id = "irregular-grid-canvas")
public class IrregularGridScene extends Canvas3D {
    private static final long SEED = 123L;
    private static final int RELAX_ITERS = 8;
    private static final int HEX_RADIUS = 5;
    private static final float TRIANGLE_SIZE = 0.12f;
    private static final float JITTER = 0f;

    public static final String VIEW_MAIN = "MAIN";

    private IrregularQuadGrid grid;
    private HyperString fpsText;

    private final Vector2f tmpA = new Vector2f();
    private final Vector2f tmpB = new Vector2f();
    private final Vector2f tmpScreen = new Vector2f();

    @Override
    public void initGL() {
        super.initGL();
        MenuBox.menuVisible = false;
        keys = new KeyGuy(camera2D, this);
        mouse = new Scene2DMousePanTrap(camera2D, this);
        AutomationInputBinder.bind(Platforms.get(), keys, mouse);
        buildGrid();
        centerCameraOnGrid();
        fpsText = new HyperString();
        MouseTrap.subscribeScrollRegion(camera2D.getBounds(),
                (scrollUp, deltaSeconds) -> camera2D.onScroll(scrollUp, deltaSeconds));
    }

    @Override
    public void drawScene() {
        camera2D.updateView(VIEW_MAIN);
        SceneInputFrameUpdater.update(keys, mouse);
        camera2D.setZIndex(camera);
        camera2D.calculateCameraTransform(camera2D.ps);
        Drawing.getDrawing().sdfLine.setCulling(false);
        Drawing.getDrawing().sdfLine.setStroke(2f * camera2D.ScaleFactor, false, 1f, 0f, true, false, false);
        for (int i = 0; i < grid.edgeIndices().size(); i++) {
            int[] edge = grid.edgeIndices().get(i);
            Vector2f from = grid.anchorPoints().get(edge[0]);
            Vector2f to = grid.anchorPoints().get(edge[1]);
            tmpA.set(camera2D.pointTransformX(from.x), camera2D.pointTransformY(from.y));
            tmpB.set(camera2D.pointTransformX(to.x), camera2D.pointTransformY(to.y));
            Drawing.getDrawing().sdfLine.draw(tmpA, tmpB, Color.LIGHT_GRAY, camera2D);
        }
        for (Vector2f pt : grid.anchorPoints()) {
            tmpScreen.set(camera2D.pointTransformX(pt.x), camera2D.pointTransformY(pt.y));
            Drawing.drawCircle(tmpScreen, Color.CYAN, camera2D, 2f);
        }
        for (Vector2f pt : grid.dualPoints()) {
            tmpScreen.set(camera2D.pointTransformX(pt.x), camera2D.pointTransformY(pt.y));
            Drawing.drawCircle(tmpScreen, Color.ORANGE, camera2D, 3f);
        }
        fpsText = new HyperString();
        fpsText.addWord("FPS: " + Clock.fps(), Color.CYAN);
        Drawing.getDrawing().font.drawHyperStringRows(fpsText, 0, 10f, Drawing.FONT_HEIGHT_PIXELS, camera2D);
        Drawing.getDrawing().sdfLine.setCulling(true);
    }

    public long getSeed() {
        return SEED;
    }

    public int getRelaxIters() {
        return RELAX_ITERS;
    }

    public float getJitter() {
        return JITTER;
    }

    public int getPrimalPointCount() {
        return grid == null ? 0 : grid.anchorCount();
    }

    public int getDualPointCount() {
        return grid == null ? 0 : grid.dualPointCount();
    }

    public int getEdgeCount() {
        return grid == null ? 0 : grid.edgeCount();
    }

    public float getHorizontalEdgeStdDev() {
        return grid == null ? 0f : grid.horizontalEdgeStdDev();
    }

    public float getVerticalEdgeStdDev() {
        return grid == null ? 0f : grid.verticalEdgeStdDev();
    }

    public float getHorizontalEdgeMean() {
        return grid == null ? 0f : grid.horizontalEdgeMean();
    }

    public float getVerticalEdgeMean() {
        return grid == null ? 0f : grid.verticalEdgeMean();
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
