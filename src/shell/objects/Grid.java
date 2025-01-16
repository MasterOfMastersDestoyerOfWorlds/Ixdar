package shell.objects;

import org.joml.Vector2f;

import shell.cameras.Camera2D;
import shell.render.color.Color;
import shell.ui.Drawing;
import shell.ui.main.Main;

@SuppressWarnings("rawtypes")
public abstract class Grid {

    public static class CartesianGrid extends Grid {
        @Override
        public String toCoordString() {
            return "X:" + (int) Main.camera.screenTransformX(Main.mouse.normalizedPosX - Main.MAIN_VIEW_OFFSET_X)
                    + " Y:"
                    + (int) Main.camera.screenTransformY(Main.mouse.normalizedPosY - Main.MAIN_VIEW_OFFSET_Y);
        }

        @Override
        public boolean allowsPoint(PointND pt) {
            return pt instanceof PointND.Double || pt instanceof PointND.Float;
        }

        @Override
        public Class[] allowableTypes() {
            return new Class[] { PointND.Double.class, PointND.Float.class };
        }

        @Override
        public void draw(Camera2D camera, float gridLineThickness) {
            Drawing.drawScaledSegment(new Vector2f(0, 0), new Vector2f(1, 1), Color.RED, gridLineThickness,
                    camera);
        }
    }

    public static class HexGrid extends Grid {
        @Override
        public String toCoordString() {
            double[] hexCoords = PointND.Hex.pixelToHexCoords(
                    Main.camera.screenTransformX(Main.mouse.normalizedPosX - Main.MAIN_VIEW_OFFSET_X),
                    Main.camera.screenTransformY(Main.mouse.normalizedPosY - Main.MAIN_VIEW_OFFSET_Y));
            return "Q:" + (hexCoords[0]) + " R:" + (hexCoords[1]) + " S:"
                    + (hexCoords[2]);
        }

        @Override
        public boolean allowsPoint(PointND pt) {
            return pt instanceof PointND.Hex;
        }

        @Override
        public Class[] allowableTypes() {
            return new Class[] { PointND.Hex.class };
        }

        @Override
        public void draw(Camera2D camera, float gridLineThickness) {
            Color gridColor = Color.LIGHT_GRAY;
            double[] hexCoordsBotLeft = PointND.Hex.pixelToHexCoords(
                    Main.camera.screenTransformX(0),
                    Main.camera.screenTransformY(0));

            double[] hexCoordsTopLeft = PointND.Hex.pixelToHexCoords(
                    Main.camera.screenTransformX(0),
                    Main.camera.screenTransformY(camera.getHeight()));

            double[] hexCoordsTopRight = PointND.Hex.pixelToHexCoords(
                    Main.camera.screenTransformX(camera.getWidth()),
                    Main.camera.screenTransformY(camera.getHeight()));

            double[] hexCoordsBotRight = PointND.Hex.pixelToHexCoords(
                    Main.camera.screenTransformX(camera.getWidth()),
                    Main.camera.screenTransformY(0));
            int gridBucketsQ = (int) Math.ceil(Math.abs(hexCoordsTopLeft[0] - hexCoordsBotRight[0]));
            int gridBucketsR = (int) Math.ceil(Math.abs(hexCoordsTopLeft[1] - hexCoordsBotRight[1]));
            int gridBucketsS = (int) Math.ceil(Math.abs(hexCoordsBotLeft[2] - hexCoordsTopRight[2]));

            double[] hexCoordsMidPoint = PointND.Hex.pixelToHexCoords(
                    Main.camera.screenTransformX(0),
                    Main.camera.screenTransformY(camera.getHeight()));

            Vector2f leftDiagonal = PointND.Hex.getRightDownVector();

            Vector2f rightDiagonal = PointND.Hex.getRightUpVector();

            float midcoord = camera
                    .pointTransformX(PointND.Hex.hexCoordsToPixel((float) Math.floor(hexCoordsBotLeft[0]),
                            (float) hexCoordsMidPoint[1]).x);
            for (int i = 0; i < gridBucketsQ; i++) {
                Vector2f botLeftPointSpace = PointND.Hex.hexCoordsToPixel((float) Math.floor(hexCoordsBotLeft[0]) + i,
                        (float) Math.floor(hexCoordsBotLeft[1]));
                Vector2f botLeft = new Vector2f(camera.pointTransformX(botLeftPointSpace.x) - midcoord,
                        camera.pointTransformY(botLeftPointSpace.y));
                double[] hexCoordsOffsetTopLeft = PointND.Hex.pixelToHexCoords(camera.screenTransformX(botLeft.x),
                        camera.screenTransformY(botLeft.y));
                Vector2f finalBotPointSpace = PointND.Hex.hexCoordsToPixel(
                        (float) Math.floor(hexCoordsOffsetTopLeft[0]),
                        (float) hexCoordsOffsetTopLeft[1]);

                Vector2f finalBotLeft = new Vector2f(camera.pointTransformX(finalBotPointSpace.x),
                        camera.pointTransformY(finalBotPointSpace.y));
                Vector2f topRight = new Vector2f(rightDiagonal).mul(camera.getWidth()).add(finalBotLeft);

                // drawing rightup diagonal
                Drawing.drawScaledSegment(finalBotLeft, topRight,
                        gridColor,
                        gridLineThickness,
                        camera);
            }
            for (int i = 0; i < gridBucketsS; i++) {
                Vector2f topLeftPointSpace = PointND.Hex.hexCoordsToPixel((float) Math.floor(hexCoordsTopLeft[0]) + i,
                        (float) Math.ceil(hexCoordsTopLeft[1]));

                Vector2f topLeft = new Vector2f(camera.pointTransformX(topLeftPointSpace.x) - midcoord,
                        camera.pointTransformY(topLeftPointSpace.y));
                double[] hexCoordsOffsetTopLeft = PointND.Hex.pixelToHexCoords(camera.screenTransformX(topLeft.x),
                        camera.screenTransformY(topLeft.y));
                Vector2f finalTopPointSpace = PointND.Hex.hexCoordsToPixel(
                        (float) Math.floor(hexCoordsOffsetTopLeft[0]),
                        (float) hexCoordsOffsetTopLeft[1]);

                Vector2f finalTopLeft = new Vector2f(camera.pointTransformX(finalTopPointSpace.x),
                        camera.pointTransformY(finalTopPointSpace.y));
                Vector2f botRight = new Vector2f(leftDiagonal).mul(camera.getWidth()).add(finalTopLeft);

                // drawing leftup diagonal
                Drawing.drawScaledSegment(finalTopLeft, botRight,
                        gridColor,
                        gridLineThickness,
                        camera);

            }

            for (int i = 0; i < gridBucketsR; i++) {
                Vector2f botRightPointSpace = PointND.Hex.hexCoordsToPixel((float) Math.floor(hexCoordsBotRight[0]),
                        (float) Math.ceil(hexCoordsBotRight[1]) + i);
                Vector2f botRight = new Vector2f(camera.getWidth(),
                        camera.pointTransformY(botRightPointSpace.y));
                Vector2f botLeft = new Vector2f(0, botRight.y);

                // drawing leftup diagonal
                Drawing.drawScaledSegment(botRight, botLeft,
                        gridColor,
                        gridLineThickness,
                        camera);

            }
        }
    }

    public abstract String toCoordString();

    public abstract boolean allowsPoint(PointND pt);

    public abstract Class[] allowableTypes();

    public abstract void draw(Camera2D camera, float gridLineThickness);
}
