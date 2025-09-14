package shell.ui.tools;

import java.util.ArrayList;

import shell.Toggle;
import shell.cameras.Camera2D;
import shell.knot.Knot;
import shell.point.PointND;
import shell.render.color.Color;
import shell.render.text.ColorText;
import shell.render.text.HyperString;
import shell.ui.Drawing;
import shell.ui.main.Main;

public class FreeTool extends Tool {

    @Override
    public void draw(Camera2D camera, float minLineThickness) {
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
    }

    @Override
    public HyperString buildInfoText() {
        HyperString h = new HyperString();
        HyperString pointInfo = new HyperString();
        h.addWord("Point: ");
        pointInfo.addDynamicWord(() -> {
            if (Main.tool.displayKP == null) {
                return new ColorText("None");
            }
            return new ColorText(((Main.tool.displayKP).p).toString());
        });
        h.addDynamicTooltip(() -> {
            if (displayKP == null) {
                return new ColorText("None");
            }
            return new ColorText(displayKP.id + "");
        }, Color.BLUE_WHITE, pointInfo, () -> {
            if (displayKP != null) {
                Main.camera.centerOnPoint(Main.tool.displayKP.p);
            }
        });
        // h.newLine();
        // h.addWord("Position:");
        // if (displayKP == null) {
        // h.addWord(Main.grid.toCoordString());
        // } else {
        // h.addWord(coordPoint.toCoordString());
        // }

        h.newLine();
        h.addWord("Neighbors:");
        if (displayKP == null) {
            h.addWord("None");
        } else {
            for (Knot match : displayKP.matchList) {
                h.addWord(match.id + "");
            }
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
            if (canUseToggle(Toggle.DrawKnotGradient)) {
                c = Main.getKnotGradientColor(displayKP);
            } else if (canUseToggle(Toggle.DrawMetroDiagram)) {
                c = Main.getMetroColor(displayKP, containingKnot);
            }
            String pointStr = "" + displayKP.id + " ";
            final Knot reeK = containingKnot;
            final PointND coordPointF = (displayKP).p;
            HyperString minKnotInfo = new HyperString();
            if (containingKnot.s1 != null && containingKnot.s2 != null) {
                minKnotInfo.addHyperString(containingKnot.s1.toHyperString(c, false));
                minKnotInfo.addDistance(containingKnot.s1.distance, c);
                minKnotInfo.newLine();
                minKnotInfo.addHyperString(containingKnot.s2.toHyperString(c, false));
                minKnotInfo.addDistance(containingKnot.s2.distance, c);
            }
            minKnotInfo.addLine("FlatID: " + containingKnot.id, c);
            h.addTooltip(containingKnot.beforeString(displayKP.id), c, minKnotInfo, () -> Main.camera.zoomToKnot(reeK));
            h.addTooltip(pointStr, Color.BLUE_WHITE, pointInfo, () -> Main.camera.centerOnPoint(coordPointF));
            h.addTooltip(containingKnot.afterString(displayKP.id), c, minKnotInfo, () -> Main.camera.zoomToKnot(reeK));

        }
        h.newLine();
        if (Main.resultKnots != null && Main.resultKnots.size() > 0) {
            h.addWord("TopKnot:");
            for (Knot topStruct : Main.resultKnots) {
                if (!topStruct.isSingleton()) {
                    h.newLine();
                    h.newLine();
                    h.addHyperString(((Knot) topStruct).toHyperString());
                }
            }
        }
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

    @Override
    public Type toolType() {
        return Type.Free;
    }

    @Override
    public String displayName() {
        return "Free";
    }

    @Override
    public String fullName() {
        return "free";
    }

    @Override
    public String shortName() {
        return "fr";
    }

    @Override
    public String desc() {
        return "The default tool. Gives the most information about knot structure and connections";
    }
}
