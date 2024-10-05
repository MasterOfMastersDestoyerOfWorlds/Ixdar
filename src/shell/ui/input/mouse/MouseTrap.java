package shell.ui.input.mouse;

import java.awt.AWTException;
import java.awt.Cursor;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.geom.Point2D;

import javax.swing.JFrame;

import org.lwjgl.opengl.awt.AWTGLCanvas;

import shell.Main;
import shell.cameras.Camera;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.ui.Canvas3D;
import shell.ui.tools.Tool;

public class MouseTrap implements MouseListener, MouseMotionListener, MouseWheelListener {

    public int queuedMouseWheelTicks = 0;
    Main main;
    long timeLastScroll;
    public static int lastX = Integer.MIN_VALUE;
    public static int lastY = Integer.MIN_VALUE;
    int width;
    int height;
    JFrame frame;
    public Camera camera;
    public boolean captureMouse;
    public boolean active = true;

    public MouseTrap(Main main, JFrame frame, Camera camera, boolean captureMouse) {
        this.main = main;
        this.frame = frame;
        frame.setCursor(new Cursor(Cursor.CROSSHAIR_CURSOR));
        this.camera = camera;
        this.captureMouse = captureMouse;
        this.canvas = Canvas3D.canvas;
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        if (!active) {
            return;
        }
        Knot manifoldKnot = Main.manifoldKnot;
        normalizedPosX = getNormalizePosX(e);
        normalizedPosY = getNormalizedPosY(e);
        if (Main.manifoldKnot != null && Main.active) {
            camera.calculateCameraTransform();
            double x = camera.screenTransformX(normalizedPosX);
            double y = camera.screenTransformY(normalizedPosY);
            double minDist = Double.MAX_VALUE;
            Segment hoverSegment = null;
            for (Segment s : manifoldKnot.manifoldSegments) {
                double result = s.boundContains(x, y);
                if (result > 0) {
                    if (result < minDist) {
                        minDist = result;
                        hoverSegment = s;
                    }
                }
            }
            Tool tool = Main.tool;
            VirtualPoint kp = null, cp = null;
            if (hoverSegment != null) {
                VirtualPoint closestPoint = hoverSegment.closestPoint(x, y);
                if (closestPoint.equals(hoverSegment.first)) {
                    kp = hoverSegment.first;
                    cp = hoverSegment.last;
                } else {
                    kp = hoverSegment.last;
                    cp = hoverSegment.first;
                }
            }
            tool.click(hoverSegment, kp, cp);
        }
        if (Canvas3D.menu != null) {

            Canvas3D.menu.click(normalizedPosX, normalizedPosY);
        }
    }

    private float getNormalizePosX(MouseEvent e) {
        return (((((float) e.getX()) / ((float) canvas.getWidth()) * Canvas3D.frameBufferWidth)))
                - camera.getScreenOffsetX();
    }

    private float getNormalizedPosY(MouseEvent e) {
        return ((1 - ((float) e.getY()) / ((float) canvas.getHeight())) * Canvas3D.frameBufferHeight)
                - camera.getScreenOffsetY();
    }

    double startX;
    double startY;
    private java.awt.geom.Point2D.Double center;
    private AWTGLCanvas canvas;
    public float normalizedPosX;
    public float normalizedPosY;

    @Override
    public void mousePressed(MouseEvent e) {

        normalizedPosX = getNormalizePosX(e);
        normalizedPosY = getNormalizedPosY(e);
        startX = normalizedPosX;
        startY = normalizedPosY;
    }

    @Override
    public void mouseDragged(MouseEvent e) {

        normalizedPosX = getNormalizePosX(e);
        normalizedPosY = getNormalizedPosY(e);
        // update pan x and y to follow the mouse
        camera.drag((float) (normalizedPosX - startX), (float) (normalizedPosY - startY));
        startX = normalizedPosX;
        startY = normalizedPosY;
    }

    @Override
    public void mouseReleased(MouseEvent e) {
    }

    @Override
    public void mouseEntered(MouseEvent e) {
    }

    @Override
    public void mouseExited(MouseEvent e) {
        if (main != null) {
            Main.tool.clearHover();
        }
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        queuedMouseWheelTicks += e.getWheelRotation();
        timeLastScroll = System.currentTimeMillis();
    }

    public void setCanvas(AWTGLCanvas canvas) {
        this.canvas = canvas;
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        if (!active) {
            return;
        }
        normalizedPosX = getNormalizePosX(e);
        normalizedPosY = getNormalizedPosY(e);
        if (captureMouse && center == null) {
            captureMouse(false);
            return;
        }
        lastX = e.getX();
        lastY = e.getY();
        if (captureMouse) {
            camera.mouseMove((int) center.x, (int) center.y, e);
        } else {
            camera.mouseMove(lastX, lastY, e);
        }
        if (Canvas3D.menu != null && !(this.canvas == null)) {

            Canvas3D.menu.setHover(normalizedPosX, normalizedPosY);
        }
        if (main != null && Main.active) {
            Main.tool.calculateHover(normalizedPosX, normalizedPosY);
        }
        if (captureMouse) {
            // captureMouse(false);
        }
    }

    public void paintUpdate(float SHIFT_MOD) {
        if (System.currentTimeMillis() - timeLastScroll > 60) {
            queuedMouseWheelTicks = 0;
        }
        if (queuedMouseWheelTicks < 0) {
            camera.zoom(true);
            Canvas3D.menu.scroll(true);
            queuedMouseWheelTicks++;
        }
        if (queuedMouseWheelTicks > 0) {
            camera.zoom(false);
            Canvas3D.menu.scroll(false);
            queuedMouseWheelTicks--;
        }

    }

    public void captureMouse(boolean force) {

        if (canvas != null && (frame.hasFocus() || force)) {
            Point2D topLeft = canvas.getLocationOnScreen();
            center = new Point2D.Double((int) (canvas.getWidth() / 2), (int) (canvas.getHeight() / 2));
            int x = (int) (topLeft.getX() + center.getX());
            int y = (int) (topLeft.getY() + center.getY());
            moveMouse(new Point2D.Double(x, y));
        }
    }

    public static void moveMouse(Point2D p) {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] gs = ge.getScreenDevices();

        // Search the devices for the one that draws the specified point.
        for (GraphicsDevice device : gs) {
            GraphicsConfiguration[] configurations = device.getConfigurations();
            for (GraphicsConfiguration config : configurations) {
                Rectangle bounds = config.getBounds();
                if (bounds.contains(p)) {
                    // Set point to screen coordinates.
                    Point2D b = bounds.getLocation();
                    Point2D s = new Point2D.Double(p.getX() - b.getX(), p.getY() - b.getY());

                    try {
                        Robot r = new Robot(device);
                        r.mouseMove((int) s.getX(), (int) s.getY());
                    } catch (AWTException e) {
                        e.printStackTrace();
                    }

                    return;
                }
            }
        }
        return;
    }

}
