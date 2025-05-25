package shell.ui.tools;

import java.util.HashMap;

import shell.Toggle;
import shell.cameras.Camera2D;
import shell.cuts.CutMatchList;
import shell.cuts.SortedCutMatchInfo;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.render.color.Color;
import shell.render.color.ColorFixedLerp;
import shell.render.text.HyperString;
import shell.ui.Drawing;
import shell.ui.main.Main;

public class KnotSurfaceViewTool extends Tool {

    public enum States {
        FindStart,
        StartSelected,
        EndSelected;

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

    public States state = States.FindStart;
    private Metric viewMetric;

    public Segment startSegment;
    public long startSegmentId;
    public Knot startKP;
    public Knot startCP;

    public Segment endSegment;
    public Knot endKP;
    public Knot endCP;

    public CutMatchList displayCML;

    HashMap<Long, Color> colorLookup;
    public Knot selectedKnot;
    final static Color matchColor = Color.CYAN;
    final static Color cutColor = Color.ORANGE;

    final static Color lowestColor = Color.GREEN;
    final static Color highestColor = Color.RED;
    final static Color chosenColor = Color.BLUE;
    final static Color notCalculatedColor = Color.DARK_IXDAR;

    final static Color externalMatchColor = Color.GREEN;
    final static Color externalCutColor = Color.MAGENTA;

    public KnotSurfaceViewTool() {
        disallowedToggles = new Toggle[] { Toggle.DrawCutMatch, Toggle.CanSwitchTopLayer,
                Toggle.DrawKnotGradient, Toggle.DrawMetroDiagram, Toggle.DrawDisplayedKnots };
    }

    @Override
    public void reset() {
        super.reset();
        state = States.FindStart;
        viewMetric = Metric.PathLength;
        startSegment = null;
        startKP = null;
        startCP = null;
        startSegmentId = -1;
        endSegment = null;
        endCP = null;
        endKP = null;
        displayCML = null;
        selectedKnot = null;
        colorLookup = null;
        Main.knotDrawLayer = Main.shell.cutEngine.totalLayers - 1;
        Main.updateKnotsDisplayed();
        instruct();
    }

    @Override
    public void hoverChanged() {
        if (state == States.FindStart) {
            if (displaySegment != null) {
                displayCML = SortedCutMatchInfo.findCutMatchList(displayCP, displayKP);
            }
        } else if (state == States.StartSelected || state == States.EndSelected) {
            if (displaySegment != null) {
                for (Knot k : Main.knotsDisplayed) {
                    if (k.contains(displayKP)) {
                        if (k.id != selectedKnot.id) {
                            return;
                        }
                    }
                }
                displayCML = SortedCutMatchInfo.findCutMatchList(startCP, startKP, displayCP, displayKP, selectedKnot);
            }
        }
    }

    @Override
    public void draw(Camera2D camera, float minLineThickness) {
        if (displayCML != null) {
            Drawing.drawCutMatch(displayCML, Drawing.MIN_THICKNESS * 2, Main.retTup.ps, camera);
        }

        if (displaySegment != null && !displaySegment.equals(startSegment)) {
            Drawing.drawManifoldCut(displayKP, displayCP, camera,
                    minLineThickness * 2);
        }
        if (startSegment != null && state.atOrAfter(States.StartSelected)) {
            Drawing.drawManifoldCut(startKP, startCP,
                    camera, minLineThickness * 2);
        }
        if (endSegment != null && state.atOrAfter(States.EndSelected)) {
            Drawing.drawManifoldCut(endKP, endCP, matchColor, cutColor,
                    camera, minLineThickness * 2);
        }
        if (colorLookup == null) {
            initSegmentMap();
        }
        for (Knot k : Main.knotsDisplayed) {
            Drawing.drawGradientPath(k, lookupSegmentPairs(k), colorLookup,
                    camera, Drawing.MIN_THICKNESS);
        }
    }

    @Override
    public void click(Segment s, Knot kp, Knot cp) {
        confirm();
    }

    @Override
    public void confirm() {
        if (displaySegment != null) {
            if (state == KnotSurfaceViewTool.States.FindStart) {
                startSegment = displaySegment;
                startKP = displayKP;
                startCP = displayCP;
                startSegmentId = Segment.idTransformOrdered(startSegment, startKP);
                state = KnotSurfaceViewTool.States.StartSelected;
                for (Knot k : Main.knotsDisplayed) {
                    if (k.contains(startKP)) {
                        selectedKnot = k;
                        break;
                    }
                }
                clearHover();
                initSegmentMap();
            } else if (state == KnotSurfaceViewTool.States.StartSelected) {
                for (Knot k : Main.knotsDisplayed) {
                    if (k.contains(displayKP)) {
                        if (k.id != selectedKnot.id) {
                            return;
                        }
                    }
                }
                if (!displaySegment.equals(startSegment)) {
                    endSegment = displaySegment;
                    endCP = displayCP;
                    endKP = displayKP;
                    state = KnotSurfaceViewTool.States.EndSelected;
                }
            }
            instruct();
        }
    }

    public void instruct() {
        switch (state) {
        case FindStart:
            Main.terminal.instruct("Select the starting cut");
        case StartSelected:
            Main.terminal.instruct("Select the end cut");
        default:
            Main.terminal.clearInstruct();
            break;
        }
    }

    public void initSegmentMap() {
        colorLookup = new HashMap<>();
        if (state == States.FindStart) {
            for (Knot k : Main.knotsDisplayed) {
                SortedCutMatchInfo cutMatchInfo = Main.shell.cutEngine.sortedCutMatchInfoLookup.get(k.id);
                if (cutMatchInfo == null) {
                    continue;
                }
                double min = cutMatchInfo.getMinShortestBySegment();
                double max = cutMatchInfo.getMaxShortestBySegment();
                double range = max - min;
                long chosenCMLId = cutMatchInfo.cw.c.cutID;
                for (Segment s : k.manifoldSegments) {
                    long matchId = Segment.idTransformOrdered(s.first.id, s.last.id);
                    long matchId2 = Segment.idTransformOrdered(s.last.id, s.first.id);
                    CutMatchList shortest = SortedCutMatchInfo.findCutMatchList(matchId, cutMatchInfo);

                    if (shortest == null) {
                        colorLookup.put(matchId2, notCalculatedColor);
                    } else if (shortest.getCutMatch().c.cutID == chosenCMLId) {
                        colorLookup.put(matchId2, chosenColor);
                    } else {
                        double colorOffset = (shortest.delta - min) / range;
                        colorLookup.put(matchId2, new ColorFixedLerp(lowestColor, highestColor, (float) colorOffset));
                    }

                    CutMatchList shortest2 = SortedCutMatchInfo.findCutMatchList(matchId2, cutMatchInfo);
                    if (shortest2 == null) {
                        colorLookup.put(matchId, notCalculatedColor);
                    } else if (shortest2.getCutMatch().c.cutID == chosenCMLId) {
                        colorLookup.put(matchId, chosenColor);
                    } else {
                        double colorOffset2 = (shortest2.delta - min) / range;
                        colorLookup.put(matchId, new ColorFixedLerp(lowestColor, highestColor, (float) colorOffset2));
                    }
                }
            }
        } else if (state == States.StartSelected || state == States.EndSelected) {
            SortedCutMatchInfo cutMatchInfo = Main.shell.cutEngine.sortedCutMatchInfoLookup.get(selectedKnot.id);
            double min = cutMatchInfo.getMinShortestBySegment();
            double max = cutMatchInfo.getMaxShortestBySegment();
            double range = max - min;
            for (Segment s : selectedKnot.manifoldSegments) {
                long matchId = Segment.idTransformOrdered(s.first.id, s.last.id);
                long matchId2 = Segment.idTransformOrdered(s.last.id, s.first.id);
                CutMatchList shortest = SortedCutMatchInfo.findCutMatchList(startSegmentId, matchId, cutMatchInfo);
                if (shortest == null) {
                    colorLookup.put(matchId2, notCalculatedColor);
                } else {
                    double colorOffset = (shortest.delta - min) / range;
                    colorLookup.put(matchId2, new ColorFixedLerp(lowestColor, highestColor, (float) colorOffset));
                }

                CutMatchList shortest2 = SortedCutMatchInfo.findCutMatchList(startSegmentId, matchId2, cutMatchInfo);
                if (shortest2 == null) {
                    colorLookup.put(matchId, notCalculatedColor);
                } else {
                    double colorOffset2 = (shortest2.delta - min) / range;
                    colorLookup.put(matchId, new ColorFixedLerp(lowestColor, highestColor, (float) colorOffset2));
                }
            }
            for (Knot k : Main.knotsDisplayed) {
                if (k.id != selectedKnot.id) {
                    for (Segment s : k.manifoldSegments) {
                        long matchId = Segment.idTransformOrdered(s.first.id, s.last.id);
                        long matchId2 = Segment.idTransformOrdered(s.last.id, s.first.id);
                        colorLookup.put(matchId, notCalculatedColor);
                        colorLookup.put(matchId2, notCalculatedColor);
                    }
                }
            }
        }
    }

    @Override
    public void back() {
        if (state == States.FindStart) {
            super.back();
        } else {
            state = States.values()[state.ordinal() - 1];
            initSegmentMap();
        }
    }

    @Override
    public void increaseViewLayer() {
        super.increaseViewLayer();
        initSegmentMap();
        hoverChanged();
    }

    @Override
    public void decreaseViewLayer() {
        super.decreaseViewLayer();
        initSegmentMap();
        hoverChanged();
    }

    @Override
    public void cycleToolLayerNext() {
        Metric[] metrics = Metric.values();
        viewMetric = viewMetric.ordinal() + 1 >= metrics.length ? metrics[0] : metrics[viewMetric.ordinal() + 1];
        initSegmentMap();
        hoverChanged();
    }

    @Override
    public void cycleToolLayerPrev() {
        Metric[] metrics = Metric.values();
        viewMetric = viewMetric.ordinal() - 1 < 0 ? metrics[metrics.length - 1]
                : metrics[viewMetric.ordinal() - 1];
        initSegmentMap();
        hoverChanged();
    }

    @Override
    public HyperString buildInfoText() {
        HyperString h = new HyperString();
        h.addLine("View Level: " + viewMetric.name());
        if (displayCML != null) {
            h.addHyperString(displayCML.toHyperString(matchColor, cutColor, externalMatchColor, externalCutColor));
        }
        h.wrap();
        return h;
    }

    @Override
    public String displayName() {
        return "Knot Surface";
    }

    @Override
    public String fullName() {
        return "knotsurface";
    }

    @Override
    public String shortName() {
        return "ks";
    }

    @Override
    public String desc() {
        return "A tool that allows the user to compare two cut pairs and their shortest path graphs. Only available in manifold mode";
    }
}
