package shell.point;

import org.joml.Vector2f;

import shell.Toggle;
import shell.cameras.Camera2D;
import shell.render.color.Color;
import shell.ui.Drawing;
import shell.ui.main.Main;

public abstract class Grid {

    private static final Color gridColor = Color.LIGHT_GRAY;

    public static class CartesianGrid extends Grid {

        @Override
        public Vector2f coordinateToNearestGridPoint(float mouseX, float mouseY) {
            return new Vector2f(mouseX, mouseY);
        }

        @Override
        public String toCoordString() {
            return "X:" + (int) Main.camera.screenTransformX(Main.mouse.normalizedPosX - Main.MAIN_VIEW_OFFSET_X)
                    + " Y:" + (int) Main.camera.screenTransformY(Main.mouse.normalizedPosY - Main.MAIN_VIEW_OFFSET_Y);
        }

        @Override
        public boolean allowsPoint(PointND pt) {
            return pt instanceof PointND.Double || pt instanceof PointND.Float;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Class<? extends PointCollection>[] allowableTypes() {
            return new Class[] { PointND.Double.class, PointND.Float.class };
        }

        @Override
        public void draw(Camera2D camera, float gridLineThickness) {
            double[] hexCoordsTopLeft = new double[] { Main.camera.screenTransformX(0),
                    Main.camera.screenTransformY(camera.getHeight()) };

            double[] hexCoordsBotRight = new double[] { Main.camera.screenTransformX(camera.getWidth()),
                    Main.camera.screenTransformY(0) };

            double unitsPerPixel = Math.abs(hexCoordsTopLeft[0] - hexCoordsBotRight[0]) / camera.getWidth();
            int gridBucketsLogLevel = (int) (Math.log10(unitsPerPixel * 1000)) - 1;
            int mod = 1;
            if (gridBucketsLogLevel >= 0) {
                mod = (int) Math.pow(10, gridBucketsLogLevel);
            }
            int gridBucketsY = (int) Math.ceil(Math.abs(hexCoordsTopLeft[1] - hexCoordsBotRight[1])) / mod + 1;
            int gridBucketsX = (int) Math.ceil(Math.abs(hexCoordsTopLeft[0] - hexCoordsBotRight[0])) / mod + 1;
            int closestMultipleOfMod = (int) (Math.ceil(hexCoordsTopLeft[0] / mod) * mod);

            Drawing.sdfLine.culling = false;
            for (int i = 0; i < gridBucketsX; i++) {
                Vector2f top = new Vector2f(camera.pointTransformX(closestMultipleOfMod + (i * mod)),
                        camera.getHeight());
                Vector2f bot = new Vector2f(top.x, 0);

                Drawing.drawScaledSegment(top, bot, gridColor, gridLineThickness, camera);

            }

            closestMultipleOfMod = (int) (Math.ceil(hexCoordsBotRight[1] / mod) * mod);
            for (int i = 0; i < gridBucketsY; i++) {
                Vector2f left = new Vector2f(camera.getWidth(),
                        camera.pointTransformY(closestMultipleOfMod + (i * mod)));
                Vector2f right = new Vector2f(0, left.y);

                Drawing.drawScaledSegment(left, right, gridColor, gridLineThickness, camera);

            }
            Drawing.sdfLine.culling = true;
        }
    }

    public static class HexGrid extends Grid {
        @Override
        public Vector2f coordinateToNearestGridPoint(float x, float y) {
            double[] hexCoords = PointND.Hex.pixelToHexCoords(x, y);
            hexCoords[0] = Math.round(hexCoords[0]);
            hexCoords[1] = Math.round(hexCoords[1]);
            hexCoords[2] = Math.round(hexCoords[2]);
            return PointND.Hex.hexCoordsToPixel(hexCoords);
        }

        @Override
        public String toCoordString() {
            double[] hexCoords = PointND.Hex.pixelToHexCoords(
                    Main.camera.screenTransformX(Main.mouse.normalizedPosX - Main.MAIN_VIEW_OFFSET_X),
                    Main.camera.screenTransformY(Main.mouse.normalizedPosY - Main.MAIN_VIEW_OFFSET_Y));
            return "Q:" + (hexCoords[0]) + " R:" + (hexCoords[1]) + " S:" + (hexCoords[2]);
        }

        @Override
        public boolean allowsPoint(PointND pt) {
            return pt instanceof PointND.Hex;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Class<? extends PointCollection>[] allowableTypes() {
            return new Class[] { PointND.Hex.class };
        }

        @Override
        public void draw(Camera2D camera, float gridLineThickness) {
            double[] hexCoordsBotLeft = PointND.Hex.pixelToHexCoords(Main.camera.screenTransformX(0),
                    Main.camera.screenTransformY(0));

            double[] hexCoordsTopLeft = PointND.Hex.pixelToHexCoords(Main.camera.screenTransformX(0),
                    Main.camera.screenTransformY(camera.getHeight()));

            double[] hexCoordsTopRight = PointND.Hex.pixelToHexCoords(Main.camera.screenTransformX(camera.getWidth()),
                    Main.camera.screenTransformY(camera.getHeight()));

            double[] hexCoordsBotRight = PointND.Hex.pixelToHexCoords(Main.camera.screenTransformX(camera.getWidth()),
                    Main.camera.screenTransformY(0));
            int gridBucketsQ = (int) Math.ceil(Math.abs(hexCoordsTopLeft[0] - hexCoordsBotRight[0]));
            int gridBucketsR = (int) Math.ceil(Math.abs(hexCoordsTopLeft[1] - hexCoordsBotRight[1]));
            int gridBucketsS = (int) Math.ceil(Math.abs(hexCoordsBotLeft[2] - hexCoordsTopRight[2]));

            double[] hexCoordsMidPoint = PointND.Hex.pixelToHexCoords(Main.camera.screenTransformX(0),
                    Main.camera.screenTransformY(camera.getHeight()));

            Vector2f leftDiagonal = PointND.Hex.getRightDownVector();

            Vector2f rightDiagonal = PointND.Hex.getRightUpVector();
            Drawing.sdfLine.culling = false;
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
                Drawing.drawScaledSegment(finalBotLeft, topRight, gridColor, gridLineThickness, camera);
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
                Drawing.drawScaledSegment(finalTopLeft, botRight, gridColor, gridLineThickness, camera);

            }

            for (int i = 0; i < gridBucketsR; i++) {
                Vector2f botRightPointSpace = PointND.Hex.hexCoordsToPixel((float) Math.floor(hexCoordsBotRight[0]),
                        (float) Math.ceil(hexCoordsBotRight[1]) + i);
                Vector2f botRight = new Vector2f(camera.getWidth(), camera.pointTransformY(botRightPointSpace.y));
                Vector2f botLeft = new Vector2f(0, botRight.y);

                // drawing horizontals
                Drawing.drawScaledSegment(botRight, botLeft, gridColor, gridLineThickness, camera);

            }
            Drawing.sdfLine.culling = true;
        }

    }

    public boolean showGrid = false;

    public void showGrid() {
        showGrid = true;
    }

    public void init() {
        Toggle.DrawGridLines.value = showGrid;
    }

    public abstract String toCoordString();

    public abstract boolean allowsPoint(PointND pt);

    public abstract Class<? extends PointCollection>[] allowableTypes();

    public abstract void draw(Camera2D camera, float gridLineThickness);

    public abstract Vector2f coordinateToNearestGridPoint(float mouseX, float mouseY);
}
