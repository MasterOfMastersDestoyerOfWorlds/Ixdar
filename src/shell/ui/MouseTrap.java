package shell.ui;

import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.MouseEvent;

import shell.Main;
import shell.ui.tools.Tool;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.knot.VirtualPoint;

public class MouseTrap implements MouseListener, MouseMotionListener, MouseWheelListener {

    public int queuedMouseWheelTicks = 0;
    Main main;
    long timeLastScroll;
    public static int lastX;
    public static int lastY;

    public MouseTrap(Main main) {
        this.main = main;
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        Knot manifoldKnot = Main.manifoldKnot;
        Camera camera = Main.camera;
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
            main.repaint();
        }
    }

    double startX;
    double startY;

    @Override
    public void mousePressed(MouseEvent e) {

        startX = e.getX();
        startY = e.getY();
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        // update pan x and y to follow the mouse
        Camera camera = Main.camera;
        camera.PanX += e.getX() - startX;
        camera.PanY += e.getY() - startY;
        startX = e.getX();
        startY = e.getY();
        main.repaint();
    }

    @Override
    public void mouseReleased(MouseEvent e) {
    }

    @Override
    public void mouseEntered(MouseEvent e) {
    }

    @Override
    public void mouseExited(MouseEvent e) {
        Main.tool.clearHover();
        main.repaint();
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        queuedMouseWheelTicks += e.getWheelRotation();
        timeLastScroll = System.currentTimeMillis();
        main.repaint();
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        lastX = e.getX();
        lastY = e.getY();
        Main.tool.calculateHover(e.getX(), e.getY());
        main.repaint();
    }

    public void paintUpdate(double SHIFT_MOD) {
        if (System.currentTimeMillis() - timeLastScroll > 60) {
            queuedMouseWheelTicks = 0;
        }
        Camera camera = Main.camera;
        if (queuedMouseWheelTicks < 0) {
            camera.scale(20 * camera.ZOOM_SPEED * SHIFT_MOD);
            queuedMouseWheelTicks++;
        }
        if (queuedMouseWheelTicks > 0) {
            camera.scale(-(20 * camera.ZOOM_SPEED * SHIFT_MOD));
            queuedMouseWheelTicks--;
        }

        if (!(queuedMouseWheelTicks == 0)) {
            main.repaint();
        }
    }

}
