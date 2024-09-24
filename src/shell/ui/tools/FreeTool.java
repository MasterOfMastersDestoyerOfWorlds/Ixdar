package shell.ui.tools;

import java.awt.Color;
import java.awt.Graphics2D;
import java.util.ArrayList;

import shell.Main;
import shell.cameras.Camera2D;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.ui.Drawing;

public class FreeTool extends Tool {

    public Segment hover;
    public VirtualPoint hoverKP;
    public VirtualPoint hoverCP;
    public VirtualPoint selectedPoint;
    public VirtualPoint displayPoint;

    @Override
    public void draw(Graphics2D g2, Camera2D camera, int minLineThickness) {
        if (displayPoint != null) {
            Drawing.drawCircle(g2, displayPoint,Color.lightGray, camera, minLineThickness);
        }
    }

    @Override
    public void setHover(Segment s, VirtualPoint kp, VirtualPoint cp) {
        super.setHover(s, kp, cp);
        displayPoint = kp;
    }

    @Override
    public void clearHover() {
        super.clearHover();
        displayPoint = selectedPoint;
    }

    @Override
    public void cycleLeft() {
        ArrayList<Knot> knotsDisplayed = Main.knotsDisplayed;
        for (Knot k : knotsDisplayed) {
            if (k.contains(displayPoint)) {
                selectedPoint = k.getNextClockWise(displayPoint);
                displayPoint = selectedPoint;
                return;
            }
        }
    };

    @Override
    public void cycleRight() {
        ArrayList<Knot> knotsDisplayed = Main.knotsDisplayed;
        for (Knot k : knotsDisplayed) {
            if (k.contains(displayPoint)) {
                selectedPoint = k.getNextCounterClockWise(displayPoint);
                displayPoint = selectedPoint;
                return;
            }
        }
    };

    @Override
    public void confirm() {
        Main.calculateSubPaths();
    }

    @Override
    public void click(Segment s, VirtualPoint kp, VirtualPoint cp) {
        selectedPoint = kp;
        displayPoint = kp;
    }
    @Override
    public Type toolType() {
        return Type.Free;
    }

    @Override
    public String displayName() {
        return "Free";
    }
}
