package shell.ui.tools;

import java.util.ArrayList;
import java.util.HashMap;

import org.apache.commons.math3.util.Pair;

import shell.ToggleType;
import shell.cameras.Camera2D;
import shell.cuts.route.Route;
import shell.exceptions.SegmentBalanceException;
import shell.file.Manifold;
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
        FirstEndSelected,
        Compare
    }

    public enum RouteView {
        All,
        Disconnected,
        Connected
    }

    public States state = States.FindStart;
    private RouteView routeView;

    public Manifold displayManifold;

    public Segment startSegment;
    public VirtualPoint startKP;
    public VirtualPoint startCP;

    public Segment firstEndSegment;
    public VirtualPoint firstEndKP;
    public VirtualPoint firstEndCP;
    public Manifold firstManifold;

    public Segment secondEndSegment;
    public VirtualPoint secondEndKP;
    public VirtualPoint secondEndCP;
    public Manifold secondManifold;

    public HashMap<Long, ArrayList<Segment>> negativeSegmentMap;
    HashMap<Long, Integer> colorLookup;
    public static ArrayList<Color> colors;

    Knot manifoldKnot;

    public CompareManifoldTool() {
        disallowedToggles = new ToggleType[] { ToggleType.DrawCutMatch, ToggleType.CanSwitchLayer,
                ToggleType.DrawKnotGradient, ToggleType.DrawMetroDiagram, ToggleType.DrawDisplayedKnots };

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
        firstEndSegment = null;
        firstEndCP = null;
        firstEndKP = null;
        firstManifold = null;
        secondEndSegment = null;
        secondEndCP = null;
        secondEndKP = null;
        secondManifold = null;
        colorLookup = null;
        Main.terminal.instruct("Select the starting cut");
    }

    @Override
    public void hoverChanged() {
        if (state == States.StartSelected || state == States.FirstEndSelected) {
            if (displaySegment != null) {
                displayManifold = Manifold.findManifold(startCP, startKP, displayCP, displayKP, Main.manifolds)
                        .getFirst();
            }
        }
    }

    @Override
    public void draw(Camera2D camera, float minLineThickness) {
        if (state == States.StartSelected || state == States.FirstEndSelected) {
            Manifold m = displayManifold;
            if (m != null) {
                Drawing.drawCutMatch(m.cutMatchList, m.manifoldCutSegment1,
                        m.manifoldCutSegment2, m.manifoldExSegment1, m.manifoldExSegment2,
                        m.manifoldKnot, Drawing.MIN_THICKNESS * 2, Main.retTup.ps, camera);
            }
        }
        if (displaySegment != null
                && !displaySegment.equals(startSegment) && state.ordinal() < States.Compare.ordinal()) {
            Drawing.drawManifoldCut(displayKP, displayCP, camera,
                    minLineThickness * 2);
        }
        if (startSegment != null) {
            Drawing.drawManifoldCut(startKP, startCP,
                    camera,
                    minLineThickness * 2);
        }
        if (firstEndSegment != null) {
            Drawing.drawManifoldCut(firstEndKP, firstEndCP,
                    camera,
                    minLineThickness * 2);
        }
        if (secondEndSegment != null) {
            Drawing.drawManifoldCut(secondEndKP, secondEndCP,
                    camera,
                    minLineThickness * 2);
        }
        if (colorLookup == null) {
            initSegmentMap();
        }
        Drawing.drawGradientPath(manifoldKnot, lookupPairs(manifoldKnot), colorLookup, colors,
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
                Main.terminal.instruct("Select the first end cut");
                clearHover();
            } else if (state == CompareManifoldTool.States.StartSelected) {
                if (!displaySegment.equals(startSegment)) {
                    Pair<Manifold, Integer> p = Manifold.findManifold(startCP, startKP, displayCP, displayKP,
                            Main.manifolds);
                    Manifold m = p.getFirst();
                    if (m != null) {
                        firstEndSegment = displaySegment;
                        firstEndCP = displayCP;
                        firstEndKP = displayKP;
                        firstManifold = m;
                        state = CompareManifoldTool.States.FirstEndSelected;
                        Main.terminal.instruct("Select the second end cut");
                    }
                }
            } else if (state == States.FirstEndSelected) {
                if (!displaySegment.equals(startSegment)) {
                    Pair<Manifold, Integer> p = Manifold.findManifold(startCP, startKP, displayCP, displayKP,
                            Main.manifolds);
                    Manifold m = p.getFirst();
                    if (m != null) {
                        secondEndSegment = displaySegment;
                        secondEndCP = displayCP;
                        secondEndKP = displayKP;
                        secondManifold = m;
                        state = States.Compare;
                        Main.terminal.clearInstruct();
                        initSegmentMap();
                    }
                }

            }
        }
    }

    public void initSegmentMap() {
        manifoldKnot = Main.manifoldKnot;
        negativeSegmentMap = new HashMap<>();
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
            if (firstManifold.cutMatchList == null) {
                try {
                    firstManifold.calculateManifoldCutMatch(Main.shell, k);
                } catch (SegmentBalanceException e) {
                    e.printStackTrace();
                }
            }
            if (secondManifold.cutMatchList == null) {
                try {
                    secondManifold.calculateManifoldCutMatch(Main.shell, k);
                } catch (SegmentBalanceException e) {
                    e.printStackTrace();
                }
            }
            for (Segment s : k.manifoldSegments) {
                long matchId = Segment.idTransformOrdered(s.first.id, s.last.id);
                VirtualPoint f = s.first;
                VirtualPoint l = s.last;
                Route fRouteC = firstManifold.getNeighborRouteC(f, l);
                Route sRouteC = secondManifold.getNeighborRouteC(f, l);
                if (fRouteC.sameRoute(sRouteC)) {
                    colorLookup.put(matchId, 0);
                } else {
                    colorLookup.put(matchId, 1);
                }

                long matchId2 = Segment.idTransformOrdered(s.last.id, s.first.id);

                Route fRouteC2 = firstManifold.getNeighborRouteC(l, f);
                Route sRouteC2 = secondManifold.getNeighborRouteC(l, f);
                if (fRouteC2.sameRoute(sRouteC2)) {
                    colorLookup.put(matchId2, 0);
                } else {
                    colorLookup.put(matchId2, 1);
                }
            }
        }
    }

    @Override
    public String displayName() {
        return "Compare Manifold";
    }

    @Override
    public HyperString buildInfoText() {
        HyperString h = new HyperString();
        h.addLine("View Level: " + routeView.name());
        return h;
    }
}
