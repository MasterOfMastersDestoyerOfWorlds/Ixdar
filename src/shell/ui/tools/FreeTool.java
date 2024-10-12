package shell.ui.tools;

import shell.render.color.Color;
import shell.render.text.HyperString;

import java.util.ArrayList;

import org.apache.commons.math3.util.Pair;

import shell.Main;
import shell.Toggle;
import shell.cameras.Camera2D;
import shell.knot.Knot;
import shell.knot.Point;
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
    public void draw(Camera2D camera, int minLineThickness) {
        if (displayPoint != null) {
            Drawing.drawCircle(displayPoint, Color.LIGHT_GRAY, camera, minLineThickness);
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

    @Override
    public HyperString info() {
        HyperString h = new HyperString();
        HyperString pointInfo = new HyperString();
        h.addWord("Point: ");
        if (displayPoint == null) {
            h.addWord("None");
        } else {
            pointInfo.addWord(((Point) displayPoint).p.toString());

            h.addTooltip(displayPoint.id + "", Color.BLUE_WHITE, pointInfo);
        }
        h.newLine();
        h.addWord("MinKnot: ");
        Knot containingKnot = null;
        for (Knot k : Main.knotsDisplayed) {
            if (k.contains(displayPoint)) {
                containingKnot = k;
            }
        }
        if (containingKnot == null) {
            h.addWord("None");
        } else {
            Color c = Main.stickyColor;
            if (canUseToggle(Toggle.drawKnotGradient)) {
                c = Main.getKnotGradientColor(displayPoint);
            } else if (canUseToggle(Toggle.drawMetroDiagram)) {
                c = Main.getMetroColor(displayPoint, containingKnot);
            }
            h.addWord(containingKnot.toString(), c);
        }
        return h;
    }
}
