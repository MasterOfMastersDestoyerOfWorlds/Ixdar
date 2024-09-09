package shell.ui.tools;

import java.awt.Graphics2D;

import shell.Main;
import shell.Toggle;
import shell.ToggleType;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.ui.Camera;

public abstract class Tool {

    public Segment hover;
    public VirtualPoint hoverKP;
    public VirtualPoint hoverCP;

    public void draw(Graphics2D g2, Camera camera, int lineThickness) {
        throw new UnsupportedOperationException("Unimplemented method 'draw'");
    };

    public void click(Segment s, VirtualPoint kp, VirtualPoint cp) {
        throw new UnsupportedOperationException("Unimplemented method 'click'");
    };

    public void leftArrow() {
        throw new UnsupportedOperationException("Unimplemented method 'leftArrow'");
    };

    public void rightArrow() {
        throw new UnsupportedOperationException("Unimplemented method 'rightArrow'");
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
            Knot manifoldKnot = Main.manifoldKnot;
            Camera camera = Main.camera;
            if (manifoldKnot != null) {
                camera.calculateCameraTransform();
                double x = camera.invertTransformX(mouseX);
                double y = camera.invertTransformY(mouseY);
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
                if (hoverSegment != null) {
                    tool.setHover(hoverSegment, hoverSegment.first, hoverSegment.last);
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
}
