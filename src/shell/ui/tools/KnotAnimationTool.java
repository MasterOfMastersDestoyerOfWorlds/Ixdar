package shell.ui.tools;

import java.util.ArrayList;
import java.util.HashMap;

import org.apache.commons.math3.util.Pair;

import shell.Toggle;
import shell.cameras.Camera2D;
import shell.cuts.Clockwork;
import shell.cuts.CutMatch;
import shell.cuts.CutMatchList;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.render.Clock;
import shell.render.color.Color;
import shell.render.text.HyperString;
import shell.ui.Drawing;
import shell.ui.main.Main;

public class KnotAnimationTool extends Tool {

    public enum States {
        Forward,
        Backward;

        public boolean atOrAfter(States state) {
            return this.ordinal() >= state.ordinal();
        }

        public boolean before(States state) {
            return this.ordinal() < state.ordinal();
        }
    }

    public enum Metric {
        PathLength,
        IxdarSkipView
    }

    public States state = States.Forward;
    public CutMatchList displayCML;

    public Knot selectedKnot;
    private int cuttingLayer;
    private int matchingLayer;
    private float timeElapsed;
    private float timeStart;
    float LAYER_TIME = 1f;
    float PAUSE_TIME = 0.2f;

    public KnotAnimationTool() {
        disallowedToggles = new Toggle[] { Toggle.DrawCutMatch, Toggle.CanSwitchLayer,
                Toggle.DrawKnotGradient, Toggle.DrawMetroDiagram, Toggle.DrawDisplayedKnots };
    }

    @Override
    public void reset() {
        super.reset();
        state = States.Forward;
        cuttingLayer = 0;
        matchingLayer = 1;
        timeStart = Clock.time();
        timeElapsed = 0;
        Main.updateKnotsDisplayed();
        instruct();
    }

    @Override
    public void hoverChanged() {
    }

    @Override
    public void draw(Camera2D camera, float minLineThickness) {

        float animStep = timeElapsed / LAYER_TIME;
        if (animStep > 1) {
            animStep = 1;
        }
        HashMap<Long, Integer> colorLookup = Main.colorLookup;
        ArrayList<Color> colors = Main.knotGradientColors;
        boolean top = cuttingLayer == Main.totalLayers;
        if (top) {
            for (Integer id : Main.knotLayerLookup.keySet()) {
                if (Main.knotLayerLookup.get(id) == cuttingLayer) {
                    if (Main.shell.cutEngine.flattenEngine.knotToFlatKnot.containsKey(id)) {
                        id = Main.shell.cutEngine.flattenEngine.knotToFlatKnot.get(id);
                    }
                    Knot k = Main.flattenEngine.flatKnots.get(id);
                    ArrayList<Pair<Long, Long>> idTransform = Main.lookupPairs(k);
                    Drawing.setScaledStroke(camera);
                    for (int i = 0; i < k.manifoldSegments.size(); i++) {
                        Segment s = k.manifoldSegments.get(i);
                        Pair<Long, Long> lookUpPair = idTransform.get(i);
                        Drawing.drawGradientSegment(s, colors.get(colorLookup.get(lookUpPair.getFirst())),
                                colors.get(colorLookup.get(lookUpPair.getSecond())),
                                camera);
                    }
                }
            }
        } else {
            for (Integer id : Main.knotLayerLookup.keySet()) {
                if (Main.knotLayerLookup.get(id) == cuttingLayer) {
                    if (Main.shell.cutEngine.flattenEngine.knotToFlatKnot.containsKey(id)) {
                        id = Main.shell.cutEngine.flattenEngine.knotToFlatKnot.get(id);
                    }
                    Clockwork cw = Main.shell.cutEngine.clockwork.get(id);
                    CutMatch cm = null;
                    if (cw != null) {
                        cm = cw.cm;
                    }
                    Knot k = Main.flattenEngine.flatKnots.get(id);
                    ArrayList<Pair<Long, Long>> idTransform = Main.lookupPairs(k);
                    Drawing.setScaledStroke(camera);
                    for (int i = 0; i < k.manifoldSegments.size(); i++) {
                        Segment s = k.manifoldSegments.get(i);
                        Pair<Long, Long> lookUpPair = idTransform.get(i);
                        if (!cm.cutSegments.contains(s)) {
                            Drawing.drawGradientSegment(s, colors.get(colorLookup.get(lookUpPair.getFirst())),
                                    colors.get(colorLookup.get(lookUpPair.getSecond())),
                                    camera);
                        } else if (!top) {
                            Drawing.drawGradientSegmentPartial(s, colors.get(colorLookup.get(lookUpPair.getFirst())),
                                    colors.get(colorLookup.get(lookUpPair.getSecond())), 1 - animStep,
                                    camera);
                        }
                    }
                    for (Segment match : cm.matchSegments) {
                        Color firstColor = colors.get(colorLookup.get((long) match.first.id));
                        Color secondColor = colors.get(colorLookup.get((long) match.last.id));
                        Drawing.drawGradientSegmentPartial(match, firstColor,
                                secondColor, animStep, camera);
                    }
                }
            }
        }
        // if (nextLayer != currLayer) {
        // for (Integer id : Main.knotLayerLookup.keySet()) {
        // if (Main.knotLayerLookup.get(id) == nextLayer) {
        // Knot k = Main.flattenEngine.flatKnots.get(id);
        // ArrayList<Pair<Long, Long>> idTransform = Main.lookupPairs(k);
        // Drawing.sdfLine.setStroke(minLineThickness * camera.ScaleFactor, false, 1f,
        // 0f, true, false);
        // for (int i = 0; i < k.manifoldSegments.size(); i++) {
        // Segment s = k.manifoldSegments.get(i);
        // Pair<Long, Long> lookUpPair = idTransform.get(i);
        // Drawing.drawGradientSegment(s,
        // colors.get(colorLookup.get(lookUpPair.getFirst())),
        // colors.get(colorLookup.get(lookUpPair.getSecond())),
        // camera);
        // }
        // }
        // }
        // }
        float currTime = Clock.time();
        timeElapsed = currTime - timeStart;
        if (timeElapsed > (LAYER_TIME + PAUSE_TIME)) {
            timeStart = currTime;
            timeElapsed = 0;
            cuttingLayer++;
            matchingLayer++;
            if (cuttingLayer > Main.totalLayers) {
                cuttingLayer = 0;
                matchingLayer = 1;
            }
        }

    }

    @Override
    public void click(Segment s, VirtualPoint kp, VirtualPoint cp) {
        confirm();
    }

    @Override
    public void confirm() {
    }

    public void instruct() {
        Main.terminal.instruct("Infinity lies at the bottom of the well.");
    }

    @Override
    public void increaseViewLayer() {
    }

    @Override
    public void decreaseViewLayer() {
    }

    @Override
    public void cycleToolLayerNext() {
    }

    @Override
    public void cycleToolLayerPrev() {
    }

    @Override
    public HyperString buildInfoText() {
        HyperString h = new HyperString();
        h.addLine("Layer: " + cuttingLayer);
        h.addLine("Tour Length: " + String.format("%.2f", Main.tourLength));
        h.wrap();
        return h;
    }

    @Override
    public String displayName() {
        return "Knot Anim";
    }

    @Override
    public String fullName() {
        return "knotanim";
    }

    @Override
    public String shortName() {
        return "ka";
    }

    @Override
    public String desc() {
        return "A tool that animates the calculations done on an ixdar file";
    }
}
