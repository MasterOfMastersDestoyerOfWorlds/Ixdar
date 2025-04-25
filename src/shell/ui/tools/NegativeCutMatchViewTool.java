package shell.ui.tools;

import shell.render.color.Color;
import shell.render.text.HyperString;

import java.util.ArrayList;
import java.util.HashMap;
import shell.Toggle;
import shell.cameras.Camera2D;
import shell.cuts.Manifold;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.ui.Drawing;
import shell.ui.main.Main;

public class NegativeCutMatchViewTool extends Tool {

    public Manifold manifold;
    public HashMap<Long, ArrayList<Segment>> negativeSegmentMap;
    public int layerCalculated;
    HashMap<Long, Integer> colorLookup;
    public static ArrayList<Color> colors;

    public NegativeCutMatchViewTool() {
        disallowedToggles = new Toggle[] { Toggle.DrawCutMatch, Toggle.DrawKnotGradient,
                Toggle.DrawMetroDiagram, Toggle.DrawDisplayedKnots };
        colors = new ArrayList<>();
        colors.add(Color.BLUE);
        colors.add(Color.RED);
    }

    @Override
    public void reset() {
        super.reset();
        initSegmentMap();
    }

    @Override
    public void draw(Camera2D camera, float minLineThickness) {
        if (layerCalculated != Main.knotDrawLayer) {
            initSegmentMap();
            return;
        }
        if (displaySegment != null) {
            long matchId = Segment.idTransformOrdered(displayKP.id, displayCP.id);
            ArrayList<Segment> matchSegments = negativeSegmentMap.get(matchId);
            long cutId = Segment.idTransform(displayKP.id, displayCP.id);
            Segment cutSeg = displayKP.segmentLookup.get(cutId);
            Drawing.drawScaledSegment(cutSeg, Color.ORANGE, 2 * Drawing.MIN_THICKNESS,
                    camera);
            if (matchSegments != null) {
                for (Segment s : matchSegments) {
                    if (!s.equals(Main.hoverSegment)) {
                        Drawing.drawScaledSegment(s, Color.CYAN, Drawing.MIN_THICKNESS,
                                camera);
                    }
                }
                Drawing.drawCircle(displayKP, Color.GREEN, camera, minLineThickness);
            }
        }
        for (Knot k : Main.knotsDisplayed) {
            Drawing.drawGradientPath(k, lookupSegmentPairs(k), colorLookup, colors,
                    camera,
                    Drawing.MIN_THICKNESS);
        }
    }

    public void initSegmentMap() {
        layerCalculated = Main.knotDrawLayer;
        ArrayList<Knot> knotsDisplayed = Main.knotsDisplayed;
        negativeSegmentMap = new HashMap<>();
        colorLookup = new HashMap<>();
        for (Knot k : knotsDisplayed) {
            for (Segment s : k.manifoldSegments) {
                long idFirst = Segment.idTransformOrdered(s.first.id, s.last.id);
                long idLast = Segment.idTransformOrdered(s.last.id, s.first.id);
                ArrayList<Segment> firstNegativeSegments = new ArrayList<>();
                ArrayList<Segment> lastNegativeSegments = new ArrayList<>();
                for (VirtualPoint vp : k.knotPointsFlattened) {
                    if (!s.contains(vp)) {
                        Segment firstSegment = vp.getSegment(s.last);
                        Segment lastSegment = vp.getSegment(s.first);
                        if (!k.hasSegment(firstSegment) && firstSegment.distance - s.distance < 0) {
                            firstNegativeSegments.add(firstSegment);
                        }
                        if (!k.hasSegment(lastSegment) && lastSegment.distance - s.distance < 0) {
                            lastNegativeSegments.add(lastSegment);
                        }
                    }
                }
                negativeSegmentMap.put(idFirst, firstNegativeSegments);
                negativeSegmentMap.put(idLast, lastNegativeSegments);
            }
            for (Segment s : k.manifoldSegments) {
                long matchId = Segment.idTransformOrdered(s.first.id, s.last.id);
                if (negativeSegmentMap.get(matchId).size() > 0) {
                    colorLookup.put(matchId, 1);
                } else {
                    colorLookup.put(matchId, 0);
                }

                long matchId2 = Segment.idTransformOrdered(s.last.id, s.first.id);
                if (negativeSegmentMap.get(matchId2).size() > 0) {
                    colorLookup.put(matchId2, 1);
                } else {
                    colorLookup.put(matchId2, 0);
                }
            }

        }
    }

    @Override
    public HyperString buildInfoText() {
        HyperString h = new HyperString();
        if (displaySegment != null) {
            h.addWord("Cut: ", Color.IXDAR);
            h.addHyperString(displaySegment.toHyperString(Color.ORANGE, false));
            h.newLine();
            h.addWord("Cut Length: ");
            h.addDistance(displaySegment.distance, Color.ORANGE);
            h.newLine();
            h.addWord("Knot Point: " + displayKP, Color.GREEN);
            h.newLine();
            h.addWord("Cut Point: " + displayCP, Color.ORANGE);
            long matchId = Segment.idTransformOrdered(displayKP.id, displayCP.id);
            ArrayList<Segment> matchSegments = negativeSegmentMap.get(matchId);
            h.newLine();
            h.addWord("Negative Matches:");
            h.newLine();
            for (Segment s : matchSegments) {
                h.addHyperString(s.toHyperString(Color.CYAN, false));
                h.addDistance(s.distance - displaySegment.distance, Color.RED);
                h.newLine();
            }
            h.newLine();

        }
        h.wrap();
        return h;
    }

    @Override
    public String displayName() {
        return "Negative Cut Match View";
    }

    @Override
    public String shortName() {
        return "neg";
    }

    @Override
    public String fullName() {
        return "negativecutmatchview";
    }

    @Override
    public String desc() {
        return "A tool to view hole moves that have negative total length";
    }
}
