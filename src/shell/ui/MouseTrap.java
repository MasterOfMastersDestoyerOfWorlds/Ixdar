package shell.ui;

import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.ArrayList;
import java.awt.event.MouseEvent;

import shell.Main;
import shell.enums.FindState;
import shell.file.Manifold;
import shell.knot.Knot;
import shell.knot.Segment;

public class MouseTrap implements MouseListener, MouseMotionListener, MouseWheelListener {

    public int queuedMouseWheelTicks = 0;
    Main main;
    long timeLastScroll;

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
            FindState findState = Main.findState;
            ArrayList<Manifold> manifolds = Main.manifolds;

            if (findState.state != FindState.States.None) {
                if (hoverSegment != null) {
                    if (findState.state == FindState.States.FindStart) {
                        findState.setFirstSelected(hoverSegment, hoverSegment.first, hoverSegment.last);
                        findState.clearHover();
                    }
                    if (findState.state == FindState.States.FirstSelected) {
                        if (!hoverSegment.equals(findState.firstSelectedSegment)) {
                            for (int i = 0; i < manifolds.size(); i++) {
                                Manifold m = manifolds.get(i);
                                if (m.manifoldCutSegment1.equals(findState.firstSelectedSegment)
                                        && m.manifoldCutSegment2.equals(hoverSegment)) {
                                    Main.manifoldIdx = i;
                                    break;
                                }
                                if (m.manifoldCutSegment2.equals(findState.firstSelectedSegment)
                                        && m.manifoldCutSegment1.equals(hoverSegment)) {
                                    Main.manifoldIdx = i;
                                    break;
                                }
                            }
                            findState.reset();
                            Main.drawCutMatch = true;
                        }
                    }
                }
                main.repaint();
            }
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
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        queuedMouseWheelTicks += e.getWheelRotation();
        timeLastScroll = System.currentTimeMillis();
        main.repaint();
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        Knot manifoldKnot = Main.manifoldKnot;
        Camera camera = Main.camera;
        if (manifoldKnot != null) {
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
            FindState findState = Main.findState;
            if (findState.state != FindState.States.None) {
                if (hoverSegment != null) {
                    findState.setHover(hoverSegment, hoverSegment.first, hoverSegment.last);
                } else {
                    findState.clearHover();
                }
                main.repaint();
            }
        }
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
            camera.scale(20*camera.ZOOM_SPEED * SHIFT_MOD);
            queuedMouseWheelTicks++;
        }
        if (queuedMouseWheelTicks > 0) {
            camera.scale(-(20*camera.ZOOM_SPEED * SHIFT_MOD));
            queuedMouseWheelTicks--;
        }

        if (!(queuedMouseWheelTicks == 0)) {
            main.repaint();
        }
    }

}
