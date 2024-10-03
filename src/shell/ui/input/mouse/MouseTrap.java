package shell.ui.input.mouse;

import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.MouseEvent;

import shell.Main;
import shell.cameras.Camera;
import shell.cameras.Camera2D;
import shell.ui.tools.Tool;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.render.Canvas3D;

import java.awt.AWTException;
import java.awt.Cursor;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.geom.Point2D;

import javax.swing.JFrame;

import org.lwjgl.opengl.awt.AWTGLCanvas;

public class MouseTrap implements MouseListener, MouseMotionListener, MouseWheelListener {

    public int queuedMouseWheelTicks = 0;
    Main main;
    long timeLastScroll;
    public static int lastX = Integer.MIN_VALUE;
    public static int lastY = Integer.MIN_VALUE;
    JFrame frame;
    public Camera camera;
    public boolean captureMouse;

    public MouseTrap(Main main, JFrame frame, Camera camera, boolean captureMouse) {
        this.main = main;
        this.frame = frame;
        frame.setCursor(new Cursor(Cursor.CROSSHAIR_CURSOR));
        this.camera = camera;
        this.captureMouse = captureMouse;
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        Knot manifoldKnot = Main.manifoldKnot;
        Camera2D camera = Main.camera;
        if (Main.manifoldKnot != null) {
            camera.calculateCameraTransform();
            double x = camera.screenTransformX(e.getX());
            double y = camera.screenTransformY(e.getY());
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
            float nomalizedPosX = (1 - ((float) e.getX()) / ((float) canvas.getWidth())) * Canvas3D.frameBufferWidth;
            float nomalizedPosY = (1 - ((float) e.getY()) / ((float) canvas.getHeight())) * Canvas3D.frameBufferHeight;

            Canvas3D.menu.click(nomalizedPosX, nomalizedPosY);
        }
    }

    double startX;
    double startY;
    private java.awt.geom.Point2D.Double center;
    private AWTGLCanvas canvas;

    @Override
    public void mousePressed(MouseEvent e) {

        startX = e.getX();
        startY = e.getY();
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        // update pan x and y to follow the mouse
        camera.drag((float) (e.getX() - startX), (float) (e.getY() - startY));
        startX = e.getX();
        startY = e.getY();
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

            float nomalizedPosX = (1 - ((float) e.getX()) / ((float) canvas.getWidth())) * Canvas3D.frameBufferWidth;
            float nomalizedPosY = (1 - ((float) e.getY()) / ((float) canvas.getHeight())) * Canvas3D.frameBufferHeight;

            Canvas3D.menu.setHover(nomalizedPosX, nomalizedPosY);
        }
        if (main != null) {
            Main.tool.calculateHover(e.getX(), e.getY());
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
