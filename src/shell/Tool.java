package shell;

import java.awt.Graphics2D;

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

    public void reset() {
        hover = null;
        hoverCP = null;
        hoverKP = null;
        Main.tool = Main.freeTool;
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
            if (disallowedToggles[i] == toggle.type) {
                return false;
            }
        }
        return toggle.value;
    }
}
