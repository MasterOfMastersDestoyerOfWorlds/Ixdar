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
            double x = camera.invertTransformX(e.getX());
            double y = camera.invertTransformY(e.getY());
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
                kp = hoverSegment.first;
                cp = hoverSegment.last;
            }
            tool.click(hoverSegment, kp, cp);
            main.repaint();
        }
    }

    @Override
    public void mousePressed(MouseEvent e) {
        System.out.println("Holding: " + e.getX() + " , " + e.getY());
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        System.out.println("Released: " + e.getX() + " , " + e.getY());
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

    @Override
    public void mouseDragged(MouseEvent e) {
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
