package ixdar.geometry.point;

import java.util.ArrayList;

import org.joml.Vector2f;

import ixdar.geometry.knot.Knot;
import ixdar.geometry.knot.Segment;
import ixdar.geometry.shell.Shell;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.gui.ui.Drawing;
import ixdar.platform.Toggle;
import ixdar.scenes.main.MainScene;

public abstract class Grid {

    private static final Color gridColor = Color.LIGHT_GRAY;

    public boolean showGrid = false;

    private static Segment getSegmentPool(ArrayList<Segment> segmentPool, Shell gridShell, int i, Vector2f left,
            Vector2f right) {
        if (segmentPool.size() < i + 1) {
            segmentPool.add(new Segment(new Knot(new PointND.Double(left.x, left.y), gridShell),
                    new Knot(new PointND.Double(right.x, right.y), gridShell), 0));
        }
        return segmentPool.get(i);
    }

    /**
     * Mark this grid so that its lines are rendered. The flag is forwarded to
     * {@link Toggle#DrawGridLines} on the next {@link #init()}.
     */
    public void showGrid() {
        showGrid = true;
    }

    /**
     * Push {@link #showGrid} into {@link Toggle#DrawGridLines} so the renderer
     * picks up the current grid's visibility preference.
     */
    public void init() {
        Toggle.DrawGridLines.value = showGrid;
    }

    /**
     * Format the cursor position under this grid's coordinate system for the
     * status readout (e.g. {@code "X:.. Y:.."} or {@code "Q:.. R:.. S:.."}).
     *
     * @return a printable description of the current cursor coordinates
     */
    public abstract String toCoordString();

    /**
     * Whether {@code pt} is a coordinate type this grid will accept as input.
     *
     * @param pt candidate point to validate
     * @return {@code true} if the concrete subclass of {@code pt} is permitted on this grid
     */
    public abstract boolean allowsPoint(PointND pt);

    /**
     * The {@link PointCollection} subclasses that may be added to this grid.
     *
     * @return classes accepted as input on this grid
     */
    public abstract Class<? extends PointCollection>[] allowableTypes();

    /**
     * Draw this grid's lines through the supplied camera.
     *
     * @param camera 2D camera providing screen/world transforms
     * @param gridLineThickness stroke width, in screen units
     */
    public abstract void draw(Camera2D camera, float gridLineThickness);

    /**
     * Snap a screen-space coordinate to the nearest grid vertex.
     *
     * @param mouseX screen-space X
     * @param mouseY screen-space Y
     * @return the nearest grid point in screen space
     */
    public abstract Vector2f coordinateToNearestGridPoint(float mouseX, float mouseY);

    public static class CartesianGrid extends Grid {
        public static final int NUM_1000 = 1000;
        public static final int NUM_10 = 10;

        ArrayList<Segment> segmentsY = new ArrayList<>();
        ArrayList<Segment> segmentsX = new ArrayList<>();
        Shell gridShell = new Shell();

        /**
         * Cartesian grid imposes no snapping; the cursor coordinate is returned unchanged.
         *
         * @param mouseX screen-space X
         * @param mouseY screen-space Y
         * @return a fresh vector mirroring the input
         */
        @Override
        public Vector2f coordinateToNearestGridPoint(float mouseX, float mouseY) {
            return new Vector2f(mouseX, mouseY);
        }

        /**
         * Format the cursor's world-space coordinates as {@code "X:.. Y:.."}.
         *
         * @return Cartesian coordinate readout for the status bar
         */
        @Override
        public String toCoordString() {
            return "X:" + (int) MainScene.camera.screenTransformX(MainScene.mouse.normalizedPosX - MainScene.MAIN_VIEW_OFFSET_X)
                    + " Y:" + (int) MainScene.camera.screenTransformY(MainScene.mouse.normalizedPosY - MainScene.MAIN_VIEW_OFFSET_Y);
        }

        /**
         * Cartesian grids accept any {@link PointND.Double} or {@link PointND.Float}.
         *
         * @param pt candidate point
         * @return {@code true} for floating-point N-D points
         */
        @Override
        public boolean allowsPoint(PointND pt) {
            return pt instanceof PointND.Double || pt instanceof PointND.Float;
        }

        /**
         * The point types this grid accepts: {@link PointND.Double} and
         * {@link PointND.Float}.
         *
         * @return the two floating-point coordinate classes
         */
        @SuppressWarnings("unchecked")
        @Override
        public Class<? extends PointCollection>[] allowableTypes() {
            return new Class[] { PointND.Double.class, PointND.Float.class };
        }

        /**
         * Draw vertical and horizontal grid lines spaced by a power-of-ten step
         * chosen from the current zoom level (see {@code unitsPerPixel}). Lines
         * are pulled from {@code segmentsX}/{@code segmentsY} pools to avoid
         * per-frame allocations.
         *
         * @param camera 2D camera providing screen/world transforms
         * @param gridLineThickness stroke width passed to the SDF line draw
         */
        @Override
        public void draw(Camera2D camera, float gridLineThickness) {
            double[] hexCoordsTopLeft = new double[] { MainScene.camera.screenTransformX(0),
                    MainScene.camera.screenTransformY(camera.getHeight()) };

            double[] hexCoordsBotRight = new double[] { MainScene.camera.screenTransformX(camera.getWidth()),
                    MainScene.camera.screenTransformY(0) };

            double unitsPerPixel = Math.abs(hexCoordsTopLeft[0] - hexCoordsBotRight[0]) / camera.getWidth();
            int gridBucketsLogLevel = (int) (Math.log10(unitsPerPixel * NUM_1000)) - 1;
            int mod = 1;
            if (gridBucketsLogLevel >= 0) {
                mod = (int) Math.pow(NUM_10, gridBucketsLogLevel);
            }
            int gridBucketsY = (int) Math.ceil(Math.abs(hexCoordsTopLeft[1] - hexCoordsBotRight[1])) / mod + 1;
            int gridBucketsX = (int) Math.ceil(Math.abs(hexCoordsTopLeft[0] - hexCoordsBotRight[0])) / mod + 1;
            int closestMultipleOfMod = (int) (Math.ceil(hexCoordsTopLeft[0] / mod) * mod);

            Drawing.getDrawing().sdfLine.setCulling(false);
            for (int i = 0; i < gridBucketsX; i++) {
                Vector2f top = new Vector2f(camera.pointTransformX(closestMultipleOfMod + (i * mod)),
                        camera.getHeight());
                Vector2f bot = new Vector2f(top.x, 0);
                Segment s = getSegmentPool(segmentsY, gridShell, i, top, bot);
                Drawing.drawScaledSegment(s, top, bot, gridColor, gridLineThickness, camera);

            }

            closestMultipleOfMod = (int) (Math.ceil(hexCoordsBotRight[1] / mod) * mod);
            for (int i = 0; i < gridBucketsY; i++) {
                Vector2f left = new Vector2f(camera.getWidth(),
                        camera.pointTransformY(closestMultipleOfMod + (i * mod)));
                Vector2f right = new Vector2f(0, left.y);

                Segment s = getSegmentPool(segmentsY, gridShell, i, left, right);
                Drawing.drawScaledSegment(s, left, right, gridColor, gridLineThickness, camera);

            }
            Drawing.getDrawing().sdfLine.setCulling(true);;
        }

    }

    public static class HexGrid extends Grid {

        ArrayList<Segment> segmentsQ = new ArrayList<>();
        ArrayList<Segment> segmentsR = new ArrayList<>();
        ArrayList<Segment> segmentsS = new ArrayList<>();
        Shell gridShell = new Shell();
        /**
         * Convert a screen-space coordinate to the nearest hex lattice vertex by
         * rounding through the {@code (q, r, s)} representation.
         *
         * @param x screen-space X
         * @param y screen-space Y
         * @return the nearest hex vertex, mapped back to screen space
         */
        @Override
        public Vector2f coordinateToNearestGridPoint(float x, float y) {
            double[] hexCoords = PointND.Hex.pixelToHexCoords(x, y);
            hexCoords[0] = Math.round(hexCoords[0]);
            hexCoords[1] = Math.round(hexCoords[1]);
            hexCoords[2] = Math.round(hexCoords[2]);
            return PointND.Hex.hexCoordsToPixel(hexCoords);
        }

        /**
         * Format the cursor's hex-space coordinates as {@code "Q:.. R:.. S:.."}.
         *
         * @return hex coordinate readout for the status bar
         */
        @Override
        public String toCoordString() {
            double[] hexCoords = PointND.Hex.pixelToHexCoords(
                    MainScene.camera.screenTransformX(MainScene.mouse.normalizedPosX - MainScene.MAIN_VIEW_OFFSET_X),
                    MainScene.camera.screenTransformY(MainScene.mouse.normalizedPosY - MainScene.MAIN_VIEW_OFFSET_Y));
            return "Q:" + (hexCoords[0]) + " R:" + (hexCoords[1]) + " S:" + (hexCoords[2]);
        }

        /**
         * Hex grids accept only {@link PointND.Hex} inputs.
         *
         * @param pt candidate point
         * @return {@code true} when {@code pt} is a {@code PointND.Hex}
         */
        @Override
        public boolean allowsPoint(PointND pt) {
            return pt instanceof PointND.Hex;
        }

        /**
         * The point types this grid accepts: {@link PointND.Hex} only.
         *
         * @return single-entry array referencing {@code PointND.Hex.class}
         */
        @SuppressWarnings("unchecked")
        @Override
        public Class<? extends PointCollection>[] allowableTypes() {
            return new Class[] { PointND.Hex.class };
        }

        /**
         * Draw the three families of hex grid lines (along Q, R and S axes) for
         * the visible viewport, reusing pooled {@code Segment}s.
         *
         * @param camera 2D camera providing screen/world transforms
         * @param gridLineThickness stroke width passed to the SDF line draw
         */
        @Override
        public void draw(Camera2D camera, float gridLineThickness) {
            double[] hexCoordsBotLeft = PointND.Hex.pixelToHexCoords(MainScene.camera.screenTransformX(0),
                    MainScene.camera.screenTransformY(0));

            double[] hexCoordsTopLeft = PointND.Hex.pixelToHexCoords(MainScene.camera.screenTransformX(0),
                    MainScene.camera.screenTransformY(camera.getHeight()));

            double[] hexCoordsTopRight = PointND.Hex.pixelToHexCoords(MainScene.camera.screenTransformX(camera.getWidth()),
                    MainScene.camera.screenTransformY(camera.getHeight()));

            double[] hexCoordsBotRight = PointND.Hex.pixelToHexCoords(MainScene.camera.screenTransformX(camera.getWidth()),
                    MainScene.camera.screenTransformY(0));
            int gridBucketsQ = (int) Math.ceil(Math.abs(hexCoordsTopLeft[0] - hexCoordsBotRight[0]));
            int gridBucketsR = (int) Math.ceil(Math.abs(hexCoordsTopLeft[1] - hexCoordsBotRight[1]));
            int gridBucketsS = (int) Math.ceil(Math.abs(hexCoordsBotLeft[2] - hexCoordsTopRight[2]));

            double[] hexCoordsMidPoint = PointND.Hex.pixelToHexCoords(MainScene.camera.screenTransformX(0),
                    MainScene.camera.screenTransformY(camera.getHeight()));

            Vector2f leftDiagonal = PointND.Hex.getRightDownVector();

            Vector2f rightDiagonal = PointND.Hex.getRightUpVector();
            Drawing.getDrawing().sdfLine.setCulling(false);;
            float midcoord = camera.pointTransformX(PointND.Hex
                    .hexCoordsToPixel((float) Math.floor(hexCoordsBotLeft[0]), (float) hexCoordsMidPoint[1]).x);
            for (int i = 0; i < gridBucketsQ; i++) {
                Vector2f botLeftPointSpace = PointND.Hex.hexCoordsToPixel((float) Math.floor(hexCoordsBotLeft[0]) + i,
                        (float) Math.floor(hexCoordsBotLeft[1]));
                Vector2f botLeft = new Vector2f(camera.pointTransformX(botLeftPointSpace.x) - midcoord,
                        camera.pointTransformY(botLeftPointSpace.y));
                double[] hexCoordsOffsetTopLeft = PointND.Hex.pixelToHexCoords(camera.screenTransformX(botLeft.x),
                        camera.screenTransformY(botLeft.y));
                Vector2f finalBotPointSpace = PointND.Hex.hexCoordsToPixel(
                        (float) Math.floor(hexCoordsOffsetTopLeft[0]), (float) hexCoordsOffsetTopLeft[1]);

                Vector2f finalBotLeft = new Vector2f(camera.pointTransformX(finalBotPointSpace.x),
                        camera.pointTransformY(finalBotPointSpace.y));
                Vector2f topRight = new Vector2f(rightDiagonal).mul(camera.getWidth()).add(finalBotLeft);

                // drawing rightup diagonal
                Segment s = getSegmentPool(segmentsQ, gridShell, i, finalBotLeft, topRight);
                Drawing.drawScaledSegment(s, finalBotLeft, topRight, gridColor, gridLineThickness, camera);
            }
            for (int i = 0; i < gridBucketsS; i++) {
                Vector2f topLeftPointSpace = PointND.Hex.hexCoordsToPixel((float) Math.floor(hexCoordsTopLeft[0]) + i,
                        (float) Math.ceil(hexCoordsTopLeft[1]));

                Vector2f topLeft = new Vector2f(camera.pointTransformX(topLeftPointSpace.x) - midcoord,
                        camera.pointTransformY(topLeftPointSpace.y));
                double[] hexCoordsOffsetTopLeft = PointND.Hex.pixelToHexCoords(camera.screenTransformX(topLeft.x),
                        camera.screenTransformY(topLeft.y));
                Vector2f finalTopPointSpace = PointND.Hex.hexCoordsToPixel(
                        (float) Math.floor(hexCoordsOffsetTopLeft[0]), (float) hexCoordsOffsetTopLeft[1]);

                Vector2f finalTopLeft = new Vector2f(camera.pointTransformX(finalTopPointSpace.x),
                        camera.pointTransformY(finalTopPointSpace.y));
                Vector2f botRight = new Vector2f(leftDiagonal).mul(camera.getWidth()).add(finalTopLeft);

                // drawing leftup diagonal
                Segment s = getSegmentPool(segmentsS, gridShell, i, finalTopLeft, botRight);
                Drawing.drawScaledSegment(s, finalTopLeft, botRight, gridColor, gridLineThickness, camera);

            }

            for (int i = 0; i < gridBucketsR; i++) {
                Vector2f botRightPointSpace = PointND.Hex.hexCoordsToPixel((float) Math.floor(hexCoordsBotRight[0]),
                        (float) Math.ceil(hexCoordsBotRight[1]) + i);
                Vector2f botRight = new Vector2f(camera.getWidth(), camera.pointTransformY(botRightPointSpace.y));
                Vector2f botLeft = new Vector2f(0, botRight.y);

                // drawing horizontals
                Segment s = getSegmentPool(segmentsR, gridShell, i, botRight, botLeft);
                Drawing.drawScaledSegment(s, botRight, botLeft, gridColor, gridLineThickness, camera);

            }
            Drawing.getDrawing().sdfLine.setCulling(true);
        }

    }
}
