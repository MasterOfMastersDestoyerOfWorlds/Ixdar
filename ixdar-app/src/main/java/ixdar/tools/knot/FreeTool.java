package ixdar.tools.knot;

import java.util.ArrayList;

import ixdar.annotations.tool.ToolAnnotation;
import ixdar.geometry.tsp.knot.Knot;
import ixdar.geometry.tsp.points.PointND;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.text.ColorText;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.text.HyperString;
import ixdar.scenes.main.MainScene;
import ixdar.scenes.main.Toggle;

@ToolAnnotation(id = FreeTool.TOOL_NAME, host = MainScene.class)
public class FreeTool extends KnotTool {
    public static final String TOOL_NAME = "free";
    public static final String NONE_2 = "None";

    /**
     * Build the editor's default tool.
     *
     * @param editor the editor the tool works on
     */
    public FreeTool(MainScene editor) {
        super(editor);
        cycleKeysStep = "the shown point around its knot";
    }

    @Override
    public void drawOverlay(Camera2D camera, float minLineThickness) {
        if (displayKP != null) {
            Drawing.drawCircle(displayKP, Color.GREY_DARK, camera, minLineThickness);
        }
    }

    @Override
    public void cycleLeft() {
        for (Knot k : editor.knotsDisplayed) {
            if (k.contains(displayKP)) {
                selectedKP = k.getNextClockWise(displayKP);
                displayKP = selectedKP;
                infoText = null;
                return;
            }
        }
    }

    @Override
    public void cycleRight() {
        for (Knot k : editor.knotsDisplayed) {
            if (k.contains(displayKP)) {
                selectedKP = k.getNextCounterClockWise(displayKP);
                displayKP = selectedKP;
                infoText = null;
                return;
            }
        }
    }

    @Override
    public void confirm() {
    }

    @Override
    public HyperString buildInfoText() {
        HyperString h = new HyperString();
        HyperString pointInfo = new HyperString();
        h.addWord("Point: ");
        pointInfo.addDynamicWord(() -> {
            final ColorText NONE = new ColorText(NONE_2);
            if (displayKP == null) {
                return NONE;
            }
            return new ColorText(((displayKP).p).toString());
        });
        h.addDynamicTooltip(() -> {
            final ColorText NONE = new ColorText(NONE_2);
            if (displayKP == null) {
                return NONE;
            }
            return new ColorText(displayKP.id + "");
        }, Color.BLUE_GREY, pointInfo, () -> {
            if (displayKP != null) {
                editor.editorCamera.centerOnPoint(displayKP.p);
            }
        });

        h.newLine();
        h.addWord("Neighbors:");
        if (displayKP == null) {
            h.addWord(NONE_2);
        } else {
            for (Knot match : displayKP.matchList) {
                h.addWord(match.id + "");
            }
        }

        h.newLine();
        h.addWord("Closest Points:");
        if (displayKP == null) {
            h.addWord(NONE_2);
        } else {
            h.addWord(displayKP.sortedSegments.get(0).getOther(displayKP).id + "");
            h.addWord(displayKP.sortedSegments.get(1).getOther(displayKP).id + "");
        }

        h.newLine();
        h.addWord("MinKnot: ");
        Knot containingKnot = selectedKnot();
        if (containingKnot == null) {
            h.addWord(NONE_2);
        } else {
            Color c = editor.stickyColor;
            if (canUseToggle(Toggle.DrawKnotGradient)) {
                c = editor.getKnotGradientColor(displayKP);
            } else if (canUseToggle(Toggle.DrawMetroDiagram)) {
                c = editor.getMetroColor(displayKP, containingKnot);
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
            h.addTooltip(containingKnot.beforeString(displayKP.id), c, minKnotInfo,
                    () -> editor.editorCamera.zoomToKnot(reeK));
            h.addTooltip(pointStr, Color.BLUE_GREY, pointInfo, () -> editor.editorCamera.centerOnPoint(coordPointF));
            h.addTooltip(containingKnot.afterString(displayKP.id), c, minKnotInfo,
                    () -> editor.editorCamera.zoomToKnot(reeK));

        }
        h.newLine();
        ArrayList<Knot> resultKnots = editor.resultKnots;
        if (resultKnots != null && resultKnots.size() > 0) {
            h.addWord("TopKnot:");
            for (Knot topStruct : resultKnots) {
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

    /**
     * The displayed knot holding the shown point, which the camera reset zooms to.
     *
     * @return the last displayed knot containing {@link #displayKP}, or null
     */
    public Knot selectedKnot() {
        Knot containingKnot = null;
        for (Knot k : editor.knotsDisplayed) {
            if (k.contains(displayKP)) {
                containingKnot = k;
            }
        }
        return containingKnot;
    }

    @Override
    public String toolName() {
        return TOOL_NAME;
    }

    @Override
    public String displayName() {
        return "Free";
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
