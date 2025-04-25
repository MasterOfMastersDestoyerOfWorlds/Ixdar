package shell.ui.tools;

import java.util.ArrayList;
import java.util.HashMap;

import org.apache.commons.math3.util.Pair;

import shell.Toggle;
import shell.cameras.Camera2D;
import shell.cuts.Manifold;
import shell.cuts.route.Route;
import shell.exceptions.SegmentBalanceException;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.render.color.Color;
import shell.render.text.HyperString;
import shell.ui.Drawing;
import shell.ui.main.Main;

public class CompareManifoldTool extends Tool {

    public enum States {
        FindStart,
        StartSelected,
        AlphaEndSelected,
        Compare;

        public boolean atOrAfter(States state) {
            return this.ordinal() >= state.ordinal();
        }

        public boolean before(States state) {
            return this.ordinal() < state.ordinal();
        }
    }

    public enum RouteView {
        Disconnected,
        Connected
    }

    public States state = States.FindStart;
    private RouteView routeView;

    public Manifold displayManifold;
    public Route displayRouteAlpha;
    public Route displayRouteBeta;

    public Segment startSegment;
    public VirtualPoint startKP;
    public VirtualPoint startCP;

    public Segment alphaEndSegment;
    public VirtualPoint alphaEndKP;
    public VirtualPoint alphaEndCP;
    public Manifold alphaManifold;

    public Segment betaEndSegment;
    public VirtualPoint betaEndKP;
    public VirtualPoint betaEndCP;
    public Manifold betaManifold;

    HashMap<Long, Integer> colorLookup;
    public static ArrayList<Color> colors;

    Knot manifoldKnot;
    final static Color alphaMatchColor = Color.PURPLE;
    final static Color alphaCutColor = Color.GREEN;

    final static Color betaMatchColor = Color.MAGENTA;
    final static Color betaCutColor = Color.YELLOW;

    public CompareManifoldTool() {
        disallowedToggles = new Toggle[] { Toggle.DrawCutMatch, Toggle.CanSwitchLayer,
                Toggle.DrawKnotGradient, Toggle.DrawMetroDiagram, Toggle.DrawDisplayedKnots };

        colors = new ArrayList<>();
        colors.add(Color.BLUE);
        colors.add(Color.RED);
        colors.add(Color.YELLOW);
    }

    @Override
    public void reset() {
        super.reset();
        state = States.FindStart;
        routeView = RouteView.Connected;
        displayManifold = null;
        startSegment = null;
        startKP = null;
        startCP = null;
        alphaEndSegment = null;
        alphaEndCP = null;
        alphaEndKP = null;
        alphaManifold = null;
        betaEndSegment = null;
        betaEndCP = null;
        betaEndKP = null;
        betaManifold = null;
        colorLookup = null;
        displayRouteAlpha = null;
        displayRouteBeta = null;
        Main.knotDrawLayer = Main.shell.cutEngine.totalLayers;
        Main.updateKnotsDisplayed();
        instruct();
    }

    @Override
    public void hoverChanged() {
        if (state == States.StartSelected || state == States.AlphaEndSelected) {
            if (displaySegment != null) {
                displayManifold = Manifold.findManifold(startCP, startKP, displayCP, displayKP, Main.manifolds)
                        .getFirst();
            }
        } else if (state == States.Compare) {
            if (displayKP != null) {
                displayRouteAlpha = alphaManifold.getNeighborRoute(displayKP, displayCP,
                        routeView == RouteView.Connected);
                displayRouteBeta = betaManifold.getNeighborRoute(displayKP, displayCP,
                        routeView == RouteView.Connected);
            }
        }
    }

    @Override
    public void draw(Camera2D camera, float minLineThickness) {
        if (state == States.StartSelected || state == States.AlphaEndSelected) {
            Manifold m = displayManifold;
            if (m != null) {
                Drawing.drawCutMatch(m.cutMatchList, m.manifoldCutSegment1,
                        m.manifoldCutSegment2, m.manifoldExSegment1, m.manifoldExSegment2,
                        m.manifoldKnot, Drawing.MIN_THICKNESS * 2, Main.retTup.ps, camera);
            }
        } else if (state == States.Compare) {
            if (displayRouteAlpha != null) {
                Drawing.drawRouteComparison(displayRouteAlpha, alphaMatchColor, alphaCutColor, displayRouteBeta,
                        Color.MAGENTA, Color.YELLOW, Drawing.MIN_THICKNESS * 2,
                        Main.retTup.ps, camera);
            }
        }
        if (displaySegment != null
                && !displaySegment.equals(startSegment) && state.before(States.Compare)) {
            Drawing.drawManifoldCut(displayKP, displayCP, camera,
                    minLineThickness * 2);
        }
        if (startSegment != null && state.atOrAfter(States.StartSelected)) {
            Drawing.drawManifoldCut(startKP, startCP,
                    camera, minLineThickness * 2);
        }
        if (alphaEndSegment != null && state.atOrAfter(States.AlphaEndSelected)) {
            Drawing.drawManifoldCut(alphaEndKP, alphaEndCP, alphaMatchColor, alphaCutColor,
                    camera, minLineThickness * 2);
        }
        if (betaEndSegment != null && state.atOrAfter(States.Compare)) {
            Drawing.drawManifoldCut(betaEndKP, betaEndCP, Color.MAGENTA, Color.YELLOW,
                    camera, minLineThickness * 2);
        }
        if (colorLookup == null) {
            initSegmentMap();
        }
        Drawing.drawGradientPath(manifoldKnot, lookupSegmentPairs(manifoldKnot), colorLookup, colors,
                camera,
                Drawing.MIN_THICKNESS);
    }

    @Override
    public void click(Segment s, VirtualPoint kp, VirtualPoint cp) {
        confirm();
    }

    @Override
    public void confirm() {
        if (displaySegment != null) {
            if (state == CompareManifoldTool.States.FindStart) {
                startSegment = displaySegment;
                startKP = displayKP;
                startCP = displayCP;
                state = CompareManifoldTool.States.StartSelected;
                clearHover();
            } else if (state == CompareManifoldTool.States.StartSelected) {
                if (!displaySegment.equals(startSegment)) {
                    Pair<Manifold, Integer> p = Manifold.findManifold(startCP, startKP, displayCP, displayKP,
                            Main.manifolds);
                    Manifold m = p.getFirst();
                    if (m != null) {
                        alphaEndSegment = displaySegment;
                        alphaEndCP = displayCP;
                        alphaEndKP = displayKP;
                        alphaManifold = m;
                        state = CompareManifoldTool.States.AlphaEndSelected;
                    }
                }
            } else if (state == States.AlphaEndSelected) {
                if (!displaySegment.equals(startSegment)) {
                    Pair<Manifold, Integer> p = Manifold.findManifold(startCP, startKP, displayCP, displayKP,
                            Main.manifolds);
                    Manifold m = p.getFirst();
                    if (m != null) {
                        betaEndSegment = displaySegment;
                        betaEndCP = displayCP;
                        betaEndKP = displayKP;
                        betaManifold = m;
                        state = States.Compare;
                        initSegmentMap();
                    }
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
            Main.terminal.instruct("Select the alpha path end cut");
        case AlphaEndSelected:
            Main.terminal.instruct("Select the beta path end cut");
        default:
            Main.terminal.clearInstruct();
            break;
        }
    }

    public void initSegmentMap() {
        manifoldKnot = Main.manifoldKnot;
        colorLookup = new HashMap<>();
        Knot k = manifoldKnot;
        if (state != States.Compare) {
            for (Segment s : k.manifoldSegments) {
                long matchId = Segment.idTransformOrdered(s.first.id, s.last.id);
                colorLookup.put(matchId, 0);

                long matchId2 = Segment.idTransformOrdered(s.last.id, s.first.id);
                colorLookup.put(matchId2, 0);
            }
        } else {
            if (alphaManifold.cutMatchList == null) {
                try {
                    alphaManifold.calculateManifoldCutMatch(Main.shell, k);
                } catch (SegmentBalanceException e) {
                    e.printStackTrace();
                }
            }
            if (betaManifold.cutMatchList == null) {
                try {
                    betaManifold.calculateManifoldCutMatch(Main.shell, k);
                } catch (SegmentBalanceException e) {
                    e.printStackTrace();
                }
            }
            if (routeView == RouteView.Connected) {
                for (Segment s : k.manifoldSegments) {
                    long matchId = Segment.idTransformOrdered(s.first.id, s.last.id);
                    VirtualPoint f = s.first;
                    VirtualPoint l = s.last;
                    Route aRouteC = alphaManifold.getNeighborRouteC(f, l);
                    Route aRouteDC = alphaManifold.getNeighborRouteDC(f, l);
                    Route bRouteC = betaManifold.getNeighborRouteC(f, l);
                    Route bRouteDC = betaManifold.getNeighborRouteDC(f, l);
                    if (aRouteC.sameRoute(bRouteC)) {
                        colorLookup.put(matchId, 0);
                    } else if (aRouteC.sameRoute(bRouteDC)) {
                        colorLookup.put(matchId, 2);
                    } else if (bRouteC.sameRoute(aRouteDC)) {
                        colorLookup.put(matchId, 2);
                    } else {
                        colorLookup.put(matchId, 1);
                    }

                    long matchId2 = Segment.idTransformOrdered(s.last.id, s.first.id);

                    Route aRouteC2 = alphaManifold.getNeighborRouteC(l, f);
                    Route aRouteDC2 = alphaManifold.getNeighborRouteDC(l, f);
                    Route bRouteC2 = betaManifold.getNeighborRouteC(l, f);
                    Route bRouteDC2 = betaManifold.getNeighborRouteDC(l, f);
                    if (aRouteC2.sameRoute(bRouteC2)) {
                        colorLookup.put(matchId2, 0);
                    } else if (aRouteC2.sameRoute(bRouteDC2)) {
                        colorLookup.put(matchId2, 2);
                    } else if (bRouteC2.sameRoute(aRouteDC2)) {
                        colorLookup.put(matchId2, 2);
                    } else {
                        colorLookup.put(matchId2, 1);
                    }
                }
            } else if (routeView == RouteView.Disconnected) {
                for (Segment s : k.manifoldSegments) {
                    long matchId = Segment.idTransformOrdered(s.first.id, s.last.id);
                    VirtualPoint f = s.first;
                    VirtualPoint l = s.last;
                    Route aRouteDC = alphaManifold.getNeighborRouteDC(f, l);
                    Route aRouteC = alphaManifold.getNeighborRouteC(f, l);
                    Route bRouteDC = betaManifold.getNeighborRouteDC(f, l);
                    Route bRouteC = betaManifold.getNeighborRouteC(f, l);
                    if (aRouteDC.sameRoute(bRouteDC)) {
                        colorLookup.put(matchId, 0);
                    } else if (aRouteDC.sameRoute(bRouteC)) {
                        colorLookup.put(matchId, 2);
                    } else if (bRouteDC.sameRoute(aRouteC)) {
                        colorLookup.put(matchId, 2);
                    } else {
                        colorLookup.put(matchId, 1);
                    }

                    long matchId2 = Segment.idTransformOrdered(s.last.id, s.first.id);

                    Route aRouteDC2 = alphaManifold.getNeighborRouteDC(l, f);
                    Route aRouteC2 = alphaManifold.getNeighborRouteC(l, f);
                    Route bRouteDC2 = betaManifold.getNeighborRouteDC(l, f);
                    Route bRouteC2 = betaManifold.getNeighborRouteC(l, f);
                    if (aRouteDC2.sameRoute(bRouteDC2)) {
                        colorLookup.put(matchId2, 0);
                    } else if (aRouteDC2.sameRoute(bRouteC2)) {
                        colorLookup.put(matchId2, 2);
                    } else if (bRouteDC2.sameRoute(aRouteC2)) {
                        colorLookup.put(matchId2, 2);
                    } else {
                        colorLookup.put(matchId2, 1);
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
        }
    }

    @Override
    public void increaseViewLayer() {
        cycleToolLayerNext();
    }

    @Override
    public void decreaseViewLayer() {
        cycleToolLayerPrev();
    }

    @Override
    public void cycleToolLayerNext() {
        RouteView[] routeViews = RouteView.values();
        routeView = routeView.ordinal() + 1 >= routeViews.length ? routeViews[0] : routeViews[routeView.ordinal() + 1];
        initSegmentMap();
        hoverChanged();
    }

    @Override
    public void cycleToolLayerPrev() {
        RouteView[] routeViews = RouteView.values();
        routeView = routeView.ordinal() - 1 < 0 ? routeViews[routeViews.length - 1]
                : routeViews[routeView.ordinal() - 1];
        initSegmentMap();
        hoverChanged();
    }

    @Override
    public HyperString buildInfoText() {
        HyperString h = new HyperString();
        h.addLine("View Level: " + routeView.name());
        if (state == States.Compare) {
            if (displayRouteAlpha != null) {
                HyperString alphaCutInfo = new HyperString();
                alphaCutInfo.addLine("end kp: " + alphaEndKP.id + " end cp: " + alphaEndCP.id, Color.BLUE_WHITE);
                h.addTooltip("Route Alpha: ", alphaMatchColor, alphaCutInfo, null);
                h.addLine(displayRouteAlpha.toString(), alphaCutColor);
                h.addHyperString(
                        displayRouteAlpha.compareHyperString(displayRouteBeta, alphaMatchColor, alphaCutColor));
                HyperString betaCutInfo = new HyperString();
                betaCutInfo.addLine("end kp: " + betaEndKP.id + " end cp: " + betaEndCP.id, Color.BLUE_WHITE);
                h.addTooltip("Route Alpha: ", betaMatchColor, betaCutInfo, null);

                h.addLine("Route Beta: ", Color.MAGENTA);
                h.addLine(displayRouteBeta.toString(), Color.YELLOW);
                h.addHyperString(
                        displayRouteBeta.compareHyperString(displayRouteAlpha, betaMatchColor, betaCutColor));
            }
        }
        h.wrap();
        return h;
    }

    @Override
    public String displayName() {
        return "Compare Manifold";
    }

    @Override
    public String fullName() {
        return "changemanifold";
    }

    @Override
    public String shortName() {
        return "cm";
    }

    @Override
    public String desc() {
        return "A tool that allows the user to compare two cut pairs and their shortest path graphs. Only available in manifold mode";
    }
}
