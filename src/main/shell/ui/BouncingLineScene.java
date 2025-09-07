package shell.ui;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import shell.DistanceMatrix;
import shell.PointSet;
import shell.cameras.Camera2D;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.point.PointND;
import shell.render.color.Color;
import shell.render.shaders.ShaderProgram;
import shell.shell.Shell;
import shell.ui.main.Main;

/**
 * A bouncing line scene that extends Canvas3D
 */
public class BouncingLineScene extends Canvas3D {

    // Two bouncing endpoints
    private float point1X = -0.8f;
    private float point1Y = -0.6f;
    private float point2X = 0.8f;
    private float point2Y = 0.6f;

    // Velocities for each endpoint
    private float vel1X = 0.008f;
    private float vel1Y = 0.006f;
    private float vel2X = -0.005f;
    private float vel2Y = -0.007f;

    // Bounds for bouncing
    private static final float BOUNDS = 0.8f;

    // Scene components
    private Camera2D camera2D;
    private Shell dummyShell;
    private Knot knot1, knot2;
    private Segment lineSegment;
    private PointSet dummyPointSet;
    private DistanceMatrix distanceMatrix;

    public BouncingLineScene() {
        super();
    }

    @Override
    public void initGL() throws UnsupportedEncodingException, IOException {
        // Call parent initGL for basic setup
        super.initGL();
        Main.tool = null;
        // Create a simple point set for camera setup
        dummyPointSet = new PointSet();

        PointND point1 = new PointND.Double(point1X, point1Y);
        PointND point2 = new PointND.Double(point2X, point2Y);

        dummyPointSet.add(point1);
        dummyPointSet.add(point2);

        distanceMatrix = new DistanceMatrix(dummyPointSet);

        dummyShell = new Shell(point1, point2);
        // Create Knot objects
        knot1 = new Knot(point1, dummyShell);
        knot2 = new Knot(point2, dummyShell);

        dummyShell.initShell(distanceMatrix);

        // Set up Camera2D similar to Main.java pattern
        platform.log("Window width: " + Canvas3D.frameBufferWidth + " Window height: " + Canvas3D.frameBufferHeight);
        camera2D = new Camera2D(
                Canvas3D.frameBufferWidth, // Width
                Canvas3D.frameBufferHeight, // Height
                1.0f, // Scale factor - try 1.0 instead of 0.9
                0, // Screen offset X
                0, // Screen offset Y
                dummyPointSet // Point set
        );

        // Debug camera constructor values
        platform.log("After camera creation: Width=" + camera2D.Width + " Height=" + camera2D.Height +
                " ScaleFactor=" + camera2D.ScaleFactor +
                " width=" + camera2D.width + " height=" + camera2D.height);

        // Create distance matrix for the point set (required for
        // Drawing.initDrawingSizes)

        // Create the segment connecting them
        double distance = Math.sqrt(
                Math.pow(point2X - point1X, 2) +
                        Math.pow(point2Y - point1Y, 2));
        lineSegment = new Segment(knot1, knot2, distance);

        // Now properly initialize the camera using its initCamera method (after points
        // are added)
        camera2D.initCamera();

        // Also calculate the initial camera transform
        camera2D.calculateCameraTransform(dummyPointSet);

        // Fix camera dimensions manually AFTER initCamera/calculateCameraTransform
        platform.log("Before fix: ScaleFactor=" + camera2D.ScaleFactor +
                " width=" + camera2D.width + " height=" + camera2D.height);

        if (camera2D.ScaleFactor == 0.0f) {
            camera2D.ScaleFactor = 1.0f;
            platform.log("Fixed ScaleFactor to 1.0");
        }
        if (camera2D.width == 0.0f || camera2D.height == 0.0f) {
            camera2D.width = Canvas3D.frameBufferWidth * camera2D.ScaleFactor;
            camera2D.height = Canvas3D.frameBufferHeight * camera2D.ScaleFactor;
            platform.log("Fixed dimensions to width=" + camera2D.width + " height=" + camera2D.height);
        }

        // Debug after manual fix
        platform.log("After manual fix: ScaleFactor=" + camera2D.ScaleFactor +
                " width=" + camera2D.width + " height=" + camera2D.height);

        // Initialize the drawing system manually (avoid zero thickness issue)
        // Drawing.initDrawingSizes(dummyShell, camera2D, distanceMatrix);
        Drawing.MIN_THICKNESS = 2.0f; // Set a reasonable default thickness
        Drawing.FONT_HEIGHT_PIXELS = 30.0f;
        Drawing.FONT_HEIGHT_LABELS_PIXELS = 30.0f;
        Drawing.CIRCLE_RADIUS = 7.5f;

        // Update the view to match the canvas size
        camera2D.updateView(0, 0, platform.getWindowWidth(), platform.getWindowHeight());

        // Override clear color for bouncing line scene
        gl.clearColor(0.05f, 0.05f, 0.15f, 1.0f);

        platform.log("BouncingLineScene initialized with Drawing.initDrawingSizes");
    }

    @Override
    public void paintGL() {
        // Update bouncing point positions
        updateBouncingPoints();

        // Clear the screen with dark blue background
        gl.clearColor(0.05f, 0.05f, 0.15f, 1.0f);
        gl.clear(gl.COLOR_BUFFER_BIT() | gl.DEPTH_BUFFER_BIT());

        // Update camera size and reset z-index
        camera2D.updateSize(Canvas3D.frameBufferWidth, Canvas3D.frameBufferHeight);
        camera2D.resetZIndex();

        // Ensure the camera view is properly set for current frame
        camera2D.updateView(0, 0, Canvas3D.frameBufferWidth, Canvas3D.frameBufferHeight);

        // Critical step from Main.java: calculateCameraTransform before drawing!
        camera2D.calculateCameraTransform(dummyPointSet);

        // CRITICAL FIX: Ensure camera dimensions are valid before drawing
        if (camera2D.ScaleFactor == 0.0f) {
            camera2D.ScaleFactor = 1.0f;
        }
        if (camera2D.width == 0.0f || camera2D.height == 0.0f) {
            camera2D.width = Canvas3D.frameBufferWidth * camera2D.ScaleFactor;
            camera2D.height = Canvas3D.frameBufferHeight * camera2D.ScaleFactor;
        }

        // Debug camera transform values
        platform.log("Camera bounds: minX=" + camera2D.minX + " maxX=" + camera2D.maxX +
                " minY=" + camera2D.minY + " maxY=" + camera2D.maxY +
                " rangeX=" + camera2D.rangeX + " rangeY=" + camera2D.rangeY);

        // Debug the values that could cause NaN in pointTransform
        platform.log("Camera dimensions: width=" + camera2D.width + " height=" + camera2D.height +
                " offsetX=" + camera2D.offsetX + " offsetY=" + camera2D.offsetY +
                " Width=" + camera2D.Width + " Height=" + camera2D.Height +
                " ScaleFactor=" + camera2D.ScaleFactor);

        try {
            // Set up the sdfLine stroke before drawing
            Drawing.sdfLine.setStroke(Drawing.MIN_THICKNESS * camera2D.ScaleFactor, false, 1f, 0f, true, false);

            // Draw the bouncing line using Drawing.drawGradientSegment
            Color startColor = Color.RED;
            Color endColor = Color.GREEN;

            Drawing.drawGradientSegment(lineSegment, startColor, endColor, camera2D);

            // Debug transformed coordinates
            float x1Screen = camera2D.pointTransformX(point1X);
            float y1Screen = camera2D.pointTransformY(point1Y);
            float x2Screen = camera2D.pointTransformX(point2X);
            float y2Screen = camera2D.pointTransformY(point2Y);

            platform.log("Point coords: (" + point1X + "," + point1Y + ") -> (" + x1Screen + "," + y1Screen + "), " +
                    "(" + point2X + "," + point2Y + ") -> (" + x2Screen + "," + y2Screen + ")");
        } catch (Exception e) {
            // Fallback: Draw using direct OpenGL
            platform.log("Drawing fallback - error: " + e.getMessage());
        }

        // Flush all shaders
        for (ShaderProgram s : shaders) {
            s.flush();
        }
    }

    private void updateBouncingPoints() {
        // Update point 1 position
        point1X += vel1X;
        point1Y += vel1Y;

        // Update point 2 position
        point2X += vel2X;
        point2Y += vel2Y;

        // Bounce point 1 off bounds
        if (point1X > BOUNDS || point1X < -BOUNDS) {
            vel1X = -vel1X;
            point1X = Math.max(-BOUNDS, Math.min(BOUNDS, point1X));
        }
        if (point1Y > BOUNDS || point1Y < -BOUNDS) {
            vel1Y = -vel1Y;
            point1Y = Math.max(-BOUNDS, Math.min(BOUNDS, point1Y));
        }

        // Bounce point 2 off bounds
        if (point2X > BOUNDS || point2X < -BOUNDS) {
            vel2X = -vel2X;
            point2X = Math.max(-BOUNDS, Math.min(BOUNDS, point2X));
        }
        if (point2Y > BOUNDS || point2Y < -BOUNDS) {
            vel2Y = -vel2Y;
            point2Y = Math.max(-BOUNDS, Math.min(BOUNDS, point2Y));
        }

        // Update the PointND coordinates
        if (knot1 != null && knot1.p != null) {
            // Update point coordinates
            if (knot1.p instanceof PointND.Double) {
                ((PointND.Double) knot1.p).setLocation(point1X, point1Y);
            }
        }

        if (knot2 != null && knot2.p != null) {
            if (knot2.p instanceof PointND.Double) {
                ((PointND.Double) knot2.p).setLocation(point2X, point2Y);
            }
        }

        // Update segment distance
        if (lineSegment != null) {
            double distance = Math.sqrt(
                    Math.pow(point2X - point1X, 2) +
                            Math.pow(point2Y - point1Y, 2));
            lineSegment.distance = distance;
        }
    }
}