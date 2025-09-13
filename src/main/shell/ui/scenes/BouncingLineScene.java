package shell.ui.scenes;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import shell.DistanceMatrix;
import shell.PointSet;
import shell.cameras.Camera2D;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.point.PointND;
import shell.render.color.Color;
import shell.shell.Shell;
import shell.ui.Canvas3D;
import shell.ui.Drawing;

public class BouncingLineScene extends Scene {

    public static final String VIEW_LEFT_RENDER = "LEFT_RENDER";
    public static final String VIEW_RIGHT_CODE = "RIGHT_CODE";

    private float point1X = -0.8f;
    private float point1Y = -0.6f;
    private float point2X = 0.8f;
    private float point2Y = 0.6f;

    private float vel1X = 0.008f;
    private float vel1Y = 0.006f;
    private float vel2X = -0.005f;
    private float vel2Y = -0.007f;

    private PointSet dummyPointSet;
    private DistanceMatrix distanceMatrix;
    private Knot knot1, knot2;
    private Segment lineSegment;

    public BouncingLineScene() {
        super();
    }

    @Override
    public void initGL() throws UnsupportedEncodingException, IOException {
        super.initGL();
        PointND.resetIds();
        dummyPointSet = new PointSet();

        PointND point1 = new PointND.Double(point1X, point1Y);
        PointND point2 = new PointND.Double(point2X, point2Y);
        dummyPointSet.add(point1);
        dummyPointSet.add(point2);
        distanceMatrix = new DistanceMatrix(dummyPointSet);

        Shell dummyShell = new Shell(point1, point2);
        knot1 = new Knot(point1, dummyShell);
        knot2 = new Knot(point2, dummyShell);
        dummyShell.initShell(distanceMatrix);

        camera2D = new Camera2D(
                Canvas3D.frameBufferWidth,
                Canvas3D.frameBufferHeight,
                1.0f,
                0,
                0,
                dummyPointSet);

        double distance = Math.sqrt(
                Math.pow(point2X - point1X, 2) +
                        Math.pow(point2Y - point1Y, 2));
        lineSegment = new Segment(knot1, knot2, distance);

        camera2D.calculateCameraTransform(cameraBounds);
        camera2D.updateView(VIEW_LEFT_RENDER);

        initViews(camera2D, VIEW_LEFT_RENDER, VIEW_RIGHT_CODE);
        // initCodePane("Line SDF", ShaderType.LineSDF.shader, Drawing.sdfLine);
    }

    @Override
    public void drawScene() {
        updateBouncingPoints();
        camera2D.updateSize(Canvas3D.frameBufferWidth, Canvas3D.frameBufferHeight);
        camera2D.resetZIndex();
        camera2D.updateView(VIEW_LEFT_RENDER);
        camera2D.calculateCameraTransform(cameraBounds);
        Drawing.sdfLine.setStroke(Drawing.MIN_THICKNESS * camera2D.ScaleFactor, false, 1f, 0f, true, false);
        Color startColor = Color.RED;
        Color endColor = Color.GREEN;
        Drawing.drawGradientSegment(lineSegment, startColor, endColor, camera2D);

        drawUI(VIEW_LEFT_RENDER, VIEW_RIGHT_CODE);
    }

    private void updateBouncingPoints() {
        point1X += vel1X;
        point1Y += vel1Y;
        point2X += vel2X;
        point2Y += vel2Y;

        float viewW = camera2D.getBounds().viewWidth;
        float viewH = camera2D.getBounds().viewHeight;
        float worldLeft = camera2D.screenTransformX(0f);
        float worldRight = camera2D.screenTransformX(viewW);
        float minX = Math.min(worldLeft, worldRight);
        float maxX = Math.max(worldLeft, worldRight);
        float worldTop = camera2D.screenTransformY(0f);
        float worldBottom = camera2D.screenTransformY(viewH);
        float minY = Math.min(worldTop, worldBottom);
        float maxY = Math.max(worldTop, worldBottom);
        float marginX = 0.001f * (maxX - minX);
        float marginY = 0.001f * (maxY - minY);
        minX += marginX;
        maxX -= marginX;
        minY += marginY;
        maxY -= marginY;

        while (point1X < minX || point1X > maxX) {
            if (point1X < minX) {
                point1X = minX + (minX - point1X);
            } else {
                point1X = maxX - (point1X - maxX);
            }
            vel1X = -vel1X;
        }
        while (point1Y < minY || point1Y > maxY) {
            if (point1Y < minY) {
                point1Y = minY + (minY - point1Y);
            } else {
                point1Y = maxY - (point1Y - maxY);
            }
            vel1Y = -vel1Y;
        }
        while (point2X < minX || point2X > maxX) {
            if (point2X < minX) {
                point2X = minX + (minX - point2X);
            } else {
                point2X = maxX - (point2X - maxX);
            }
            vel2X = -vel2X;
        }
        while (point2Y < minY || point2Y > maxY) {
            if (point2Y < minY) {
                point2Y = minY + (minY - point2Y);
            } else {
                point2Y = maxY - (point2Y - maxY);
            }
            vel2Y = -vel2Y;
        }

        if (knot1 != null && knot1.p != null) {
            if (knot1.p instanceof PointND.Double) {
                ((PointND.Double) knot1.p).setLocation(point1X, point1Y);
            }
        }
        if (knot2 != null && knot2.p != null) {
            if (knot2.p instanceof PointND.Double) {
                ((PointND.Double) knot2.p).setLocation(point2X, point2Y);
            }
        }
        if (lineSegment != null) {
            double distance = Math.sqrt(
                    Math.pow(point2X - point1X, 2) +
                            Math.pow(point2Y - point1Y, 2));
            lineSegment.distance = distance;
        }
    }
}
