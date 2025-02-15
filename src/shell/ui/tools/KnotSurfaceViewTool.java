package shell.ui.tools;

import java.util.ArrayList;
import java.util.HashMap;

import shell.Toggle;
import shell.cameras.Camera2D;
import shell.cuts.CutMatchList;
import shell.cuts.SortedCutMatchInfo;
import shell.cuts.route.Route;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
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
        IxdarSkipView,
        PathCutMatchCount,
    }

    public States state = States.FindStart;
    private Metric viewMetric;

    public Route displayRoute;

    public Segment startSegment;
    public VirtualPoint startKP;
    public VirtualPoint startCP;

    public Segment endSegment;
    public VirtualPoint endKP;
    public VirtualPoint endCP;

    public CutMatchList displayCML;

    HashMap<Long, Color> colorLookup;
    public Knot selectedKnot;
    final static Color matchColor = Color.CYAN;
    final static Color cutColor = Color.ORANGE;

    final static Color lowestColor = Color.GREEN;
    final static Color highestColor = Color.RED;
    final static Color notCalculated = Color.DARK_IXDAR;

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
        endSegment = null;
        endCP = null;
        endKP = null;
        displayCML = null;
        displayRoute = null;
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
            Drawing.drawGradientPath(k, lookupPairs(k), colorLookup,
                    camera, Drawing.MIN_THICKNESS);
        }
    }

    @Override
    public void click(Segment s, VirtualPoint kp, VirtualPoint cp) {
        confirm();
    }

    @Override
    public void confirm() {
        if (displaySegment != null) {
            if (state == KnotSurfaceViewTool.States.FindStart) {
                startSegment = displaySegment;
                startKP = displayKP;
                startCP = displayCP;
                state = KnotSurfaceViewTool.States.StartSelected;
                for (Knot k : Main.knotsDisplayed) {
                    if (k.contains(startKP)) {
                        selectedKnot = k;
                        break;
                    }
                }
                clearHover();
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
                for (Segment s : k.manifoldSegments) {
                    long matchId = Segment.idTransformOrdered(s.first.id, s.last.id);
                    long matchId2 = Segment.idTransformOrdered(s.last.id, s.first.id);
                    ArrayList<CutMatchList> lists = cutMatchInfo.sortedCutMatchListsBySegment.get(matchId);
                    if (lists == null) {
                        colorLookup.put(matchId2, notCalculated);
                    } else {
                        CutMatchList shortest = lists.get(0);
                        double colorOffset = (shortest.delta - min) / range;
                        colorLookup.put(matchId2, new ColorFixedLerp(lowestColor, highestColor, (float) colorOffset));
                    }

                    CutMatchList shortest2 = cutMatchInfo.sortedCutMatchListsBySegment.get(matchId2).get(0);
                    double colorOffset2 = (shortest2.delta - min) / range;
                    colorLookup.put(matchId, new ColorFixedLerp(lowestColor, highestColor, (float) colorOffset2));
                }
            }
        } else if (state == States.StartSelected) {

        } else {
            // if (manifold.cutMatchList == null) {
            // try {
            // manifold.calculateManifoldCutMatch(Main.shell, k);
            // } catch (SegmentBalanceException e) {
            // e.printStackTrace();
            // }
            // }
            // if (viewMetric == Metric.PathLength) {
            // for (Segment s : k.manifoldSegments) {
            // long matchId = Segment.idTransformOrdered(s.first.id, s.last.id);
            // VirtualPoint f = s.first;
            // VirtualPoint l = s.last;
            // Route aRouteC = manifold.getNeighborRouteC(f, l);
            // Route aRouteDC = manifold.getNeighborRouteDC(f, l);
            // if (aRouteC.sameRoute(bRouteC)) {
            // colorLookup.put(matchId, 0);
            // } else if (aRouteC.sameRoute(bRouteDC)) {
            // colorLookup.put(matchId, 2);
            // } else if (bRouteC.sameRoute(aRouteDC)) {
            // colorLookup.put(matchId, 2);
            // } else {
            // colorLookup.put(matchId, 1);
            // }

            // long matchId2 = Segment.idTransformOrdered(s.last.id, s.first.id);

            // Route aRouteC2 = manifold.getNeighborRouteC(l, f);
            // Route aRouteDC2 = manifold.getNeighborRouteDC(l, f);
            // Route bRouteC2 = betaManifold.getNeighborRouteC(l, f);
            // Route bRouteDC2 = betaManifold.getNeighborRouteDC(l, f);
            // if (aRouteC2.sameRoute(bRouteC2)) {
            // colorLookup.put(matchId2, 0);
            // } else if (aRouteC2.sameRoute(bRouteDC2)) {
            // colorLookup.put(matchId2, 2);
            // } else if (bRouteC2.sameRoute(aRouteDC2)) {
            // colorLookup.put(matchId2, 2);
            // } else {
            // colorLookup.put(matchId2, 1);
            // }
            // }
            // } else if (viewMetric == Metric.PathCutMatchCount) {
            // for (Segment s : k.manifoldSegments) {
            // long matchId = Segment.idTransformOrdered(s.first.id, s.last.id);
            // VirtualPoint f = s.first;
            // VirtualPoint l = s.last;
            // Route aRouteDC = manifold.getNeighborRouteDC(f, l);
            // Route aRouteC = manifold.getNeighborRouteC(f, l);
            // Route bRouteDC = betaManifold.getNeighborRouteDC(f, l);
            // Route bRouteC = betaManifold.getNeighborRouteC(f, l);
            // if (aRouteDC.sameRoute(bRouteDC)) {
            // colorLookup.put(matchId, 0);
            // } else if (aRouteDC.sameRoute(bRouteC)) {
            // colorLookup.put(matchId, 2);
            // } else if (bRouteDC.sameRoute(aRouteC)) {
            // colorLookup.put(matchId, 2);
            // } else {
            // colorLookup.put(matchId, 1);
            // }

            // long matchId2 = Segment.idTransformOrdered(s.last.id, s.first.id);

            // Route aRouteDC2 = manifold.getNeighborRouteDC(l, f);
            // Route aRouteC2 = manifold.getNeighborRouteC(l, f);
            // Route bRouteDC2 = betaManifold.getNeighborRouteDC(l, f);
            // Route bRouteC2 = betaManifold.getNeighborRouteC(l, f);
            // if (aRouteDC2.sameRoute(bRouteDC2)) {
            // colorLookup.put(matchId2, 0);
            // } else if (aRouteDC2.sameRoute(bRouteC2)) {
            // colorLookup.put(matchId2, 2);
            // } else if (bRouteDC2.sameRoute(aRouteC2)) {
            // colorLookup.put(matchId2, 2);
            // } else {
            // colorLookup.put(matchId2, 1);
            // }
            // }
            // }
        }
    }

    @Override
    public void back() {
        if (state == States.FindStart) {
            super.back();
        } else {
            state = States.values()[state.ordinal() - 1];
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
        if (state == States.StartSelected) {
            if (displayRoute != null) {
                HyperString alphaCutInfo = new HyperString();
                alphaCutInfo.addLine("end kp: " + endKP.id + " end cp: " + endCP.id, Color.BLUE_WHITE);
                h.addTooltip("Route Alpha: ", matchColor, alphaCutInfo, null);
                h.addLine(displayRoute.toString(), cutColor);
            }
        } else if (state == States.EndSelected) {

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
