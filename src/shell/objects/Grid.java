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
            Vector2f pan = new Vector2f(camera.PanX - camera.defaultPanX, camera.PanY - camera.defaultPanY);

            Vector2f leftDiagonal = new Vector2f(-1, 1);
            float projScale = leftDiagonal.dot(pan) / leftDiagonal.lengthSquared();
            Vector2f panLeftDiagonal = new Vector2f(leftDiagonal).mul(projScale);

            Vector2f rightDiagonal = new Vector2f(1, 1);
            projScale = rightDiagonal.dot(pan) / rightDiagonal.lengthSquared();
            Vector2f panRightDiagonal = new Vector2f(rightDiagonal).mul(projScale);

            Vector2f topRight = new Vector2f(rightDiagonal).mul(camera.getWidth());
            Vector2f botLeft = new Vector2f(0, 0);
            Vector2f bl = new Vector2f(botLeft).add(panLeftDiagonal);
            Vector2f tr = new Vector2f(topRight).add(panLeftDiagonal);

            Drawing.drawScaledSegment(bl, tr,
                    Color.RED,
                    gridLineThickness,
                    camera);
        }
    }

    public abstract String toCoordString();

    public abstract boolean allowsPoint(PointND pt);

    public abstract Class[] allowableTypes();

    public abstract void draw(Camera2D camera, float gridLineThickness);
}
