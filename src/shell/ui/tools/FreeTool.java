package shell.ui.tools;

import shell.render.color.Color;
import shell.render.text.HyperString;

import java.util.ArrayList;

import shell.PointND;
import shell.Toggle;
import shell.cameras.Camera2D;
import shell.knot.Knot;
import shell.knot.Point;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.ui.Drawing;
import shell.ui.main.Main;

public class FreeTool extends Tool {

    public Segment hover;
    public VirtualPoint hoverKP;
    public VirtualPoint hoverCP;

    @Override
    public void draw(Camera2D camera, int minLineThickness) {
        if (displayKP != null) {
            Drawing.drawCircle(displayKP, Color.LIGHT_GRAY, camera, minLineThickness);
        }
    }

    @Override
    public void cycleLeft() {
        ArrayList<Knot> knotsDisplayed = Main.knotsDisplayed;
        for (Knot k : knotsDisplayed) {
            if (k.contains(displayKP)) {
                selectedKP = k.getNextClockWise(displayKP);
                displayKP = selectedKP;
                return;
            }
        }
    };

    @Override
    public void cycleRight() {
        ArrayList<Knot> knotsDisplayed = Main.knotsDisplayed;
        for (Knot k : knotsDisplayed) {
            if (k.contains(displayKP)) {
                selectedKP = k.getNextCounterClockWise(displayKP);
                displayKP = selectedKP;
                return;
            }
        }
    };

    @Override
    public void confirm() {
        Main.calculateSubPaths();
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
    public HyperString buildInfoText() {
        HyperString h = new HyperString();
        HyperString pointInfo = new HyperString();
        PointND coordPoint = null;
        h.addWord("Point: ");
        if (displayKP == null) {
            h.addWord("None");
        } else {
            coordPoint = ((Point) displayKP).p;
            final PointND coordPointF = ((Point) displayKP).p;
            pointInfo.addWord(coordPointF.toString());
            h.addTooltip(displayKP.id + "", Color.BLUE_WHITE, pointInfo,
                    () -> Main.camera.centerOnPoint(coordPointF));
        }
        h.newLine();
        h.addWord("Position:");
        if (displayKP == null) {
            h.addWord("X:"
                    + (int) Main.camera.screenTransformX(Main.mouse.normalizedPosX - Main.MAIN_VIEW_OFFSET_X)
                    + " Y:"
                    + (int) Main.camera.screenTransformY(Main.mouse.normalizedPosY - Main.MAIN_VIEW_OFFSET_Y));
        } else {
            h.addWord("X:" + (int) coordPoint.getCoord(0) + " Y:"
                    + (int) coordPoint.getCoord(1));

        }

        h.newLine();
        h.addWord("Neighbors:");
        if (displayKP == null) {
            h.addWord("None");
        } else {
            h.addWord(displayKP.match1.id + "");
            h.addWord(displayKP.match2.id + "");
        }

        h.newLine();
        h.addWord("Closest Points:");
        if (displayKP == null) {
            h.addWord("None");
        } else {
            h.addWord(displayKP.sortedSegments.get(0).getOther(displayKP).id + "");
            h.addWord(displayKP.sortedSegments.get(1).getOther(displayKP).id + "");
        }

        h.newLine();
        h.addWord("MinKnot: ");
        Knot containingKnot = null;
        for (Knot k : Main.knotsDisplayed) {
            if (k.contains(displayKP)) {
                containingKnot = k;
            }
        }
        if (containingKnot == null) {
            h.addWord("None");
        } else {
            Color c = Main.stickyColor;
            if (canUseToggle(Toggle.drawKnotGradient)) {
                c = Main.getKnotGradientColor(displayKP);
            } else if (canUseToggle(Toggle.drawMetroDiagram)) {
                c = Main.getMetroColor(displayKP, containingKnot);
            }
            String pointStr = "" + displayKP.id + " ";
            final Knot reeK = containingKnot;
            final PointND coordPointF = ((Point) displayKP).p;
            HyperString minKnotInfo = new HyperString();
            if (containingKnot.s1 != null && containingKnot.s2 != null) {
                minKnotInfo.addHyperString(containingKnot.s1.toHyperString(c, false));
                minKnotInfo.addDistance(containingKnot.s1.distance, c);
                minKnotInfo.newLine();
                minKnotInfo.addHyperString(containingKnot.s2.toHyperString(c, false));
                minKnotInfo.addDistance(containingKnot.s2.distance, c);
            }
            h.addTooltip(containingKnot.beforeString(displayKP.id), c, minKnotInfo, () -> Main.camera.zoomToKnot(reeK));
            h.addTooltip(pointStr, Color.BLUE_WHITE, pointInfo, () -> Main.camera.centerOnPoint(coordPointF));
            h.addTooltip(containingKnot.afterString(displayKP.id), c, minKnotInfo, () -> Main.camera.zoomToKnot(reeK));
        }
        h.newLine();

        h.addWord("TopKnot:");
        h.addHyperString(((Knot) Main.result.get(0)).toHyperString());
        h.wrap = true;
        return h;
    }

    @Override
    public Knot selectedKnot() {
        Knot containingKnot = null;
        for (Knot k : Main.knotsDisplayed) {
            if (k.contains(displayKP)) {
                containingKnot = k;
            }
        }
        return containingKnot;
    }
}
