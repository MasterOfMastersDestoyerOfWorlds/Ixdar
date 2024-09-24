package shell.ui.tools;

import java.awt.Graphics2D;
import java.util.ArrayList;

import shell.Main;
import shell.Toggle;
import shell.ToggleType;
import shell.cameras.Camera2D;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.knot.VirtualPoint;

public abstract class Tool {

    public Segment hover;
    public VirtualPoint hoverKP;
    public VirtualPoint hoverCP;

    public void draw(Graphics2D g2, Camera2D camera, int lineThickness) {
        throw new UnsupportedOperationException("Unimplemented method 'draw'");
    };

    public void click(Segment s, VirtualPoint kp, VirtualPoint cp) {
        throw new UnsupportedOperationException("Unimplemented method 'click'");
    };

    public void cycleLeft() {
        ArrayList<Knot> knotsDisplayed = Main.knotsDisplayed;
        if (hover == null) {
            hover = Main.manifoldKnot.manifoldSegments.get(0);
            hoverKP = hover.first;
            hoverCP = hover.last;
        } else {
            for (Knot k : knotsDisplayed) {
                if (k.contains(hoverKP)) {
                    VirtualPoint clockWise = k.getNextClockWise(hoverKP);
                    if (clockWise.equals(hoverCP)) {
                        clockWise = hoverKP;
                        hoverKP = hoverCP;
                        hoverCP = clockWise;
                        hover = hoverKP.getSegment(hoverCP);
                    } else {
                        hoverCP = clockWise;
                        hover = hoverKP.getSegment(hoverCP);
                    }
                    return;
                }
            }
        }
    }

    public void cycleRight() {
        if (hover == null) {
            hover = Main.manifoldKnot.manifoldSegments.get(0);
            hoverKP = hover.first;
            hoverCP = hover.last;
        } else {
            ArrayList<Knot> knotsDisplayed = Main.knotsDisplayed;
            for (Knot k : knotsDisplayed) {
                if (k.contains(hoverKP)) {
                    VirtualPoint clockWise = k.getNextCounterClockWise(hoverKP);
                    if (clockWise.equals(hoverCP)) {
                        clockWise = hoverKP;
                        hoverKP = hoverCP;
                        hoverCP = clockWise;
                        hover = hoverKP.getSegment(hoverCP);
                    } else {
                        hoverCP = clockWise;
                        hover = hoverKP.getSegment(hoverCP);
                    }
                    return;
                }
            }
        }
    }

    public void confirm() {
        throw new UnsupportedOperationException("Unimplemented method 'confirm'");
    };

    public void reset() {
        hover = null;
        hoverCP = null;
        hoverKP = null;
    }

    public void clearHover() {
        hover = null;
        hoverCP = null;
        hoverKP = null;
    }

    public void setHover(Segment s, VirtualPoint kp, VirtualPoint cp) {
        hover = s;
        hoverKP = kp;
        hoverCP = cp;
    }

    ToggleType[] disallowedToggles = new ToggleType[] {};

    public boolean canUseToggle(Toggle toggle) {
        for (int i = 0; i < disallowedToggles.length; i++) {
            if (disallowedToggles[i].equals(toggle.type)) {
                return false;
            }
        }
        return toggle.value;
    }

    public void calculateHover(int mouseX, int mouseY) {
        Tool tool = Main.tool;
        if (mouseX <= Main.main.getWidth() && mouseX >= 0
                && mouseY <= Main.main.getHeight() && mouseY >= 0) {
            ArrayList<Knot> knotsDisplayed = Main.knotsDisplayed;
            Camera2D camera = Main.camera;
            if (knotsDisplayed != null) {
                camera.calculateCameraTransform();
                double x = camera.screenTransformX(mouseX);
                double y = camera.screenTransformY(mouseY);
                double minDist = Double.MAX_VALUE;
                Segment hoverSegment = null;
                for (Knot k : knotsDisplayed) {
                    for (Segment s : k.manifoldSegments) {
                        double result = s.boundContains(x, y);
                        if (result > 0) {
                            if (result < minDist) {
                                minDist = result;
                                hoverSegment = s;
                            }
                        }
                    }
                }
                if (hoverSegment != null) {
                    VirtualPoint closestPoint = hoverSegment.closestPoint(x, y);
                    if (closestPoint.equals(hoverSegment.first)) {
                        tool.setHover(hoverSegment, hoverSegment.first, hoverSegment.last);
                    } else {
                        tool.setHover(hoverSegment, hoverSegment.last, hoverSegment.first);
                    }
                } else {
                    tool.clearHover();
                }
            }
        } else {
            tool.clearHover();
        }
    }

    public String displayName() {
        return "REEEEEEEE";
    }

    public Type toolType() {
        return Type.None;
    }

    public enum Type {
        Free, None
    }

}
