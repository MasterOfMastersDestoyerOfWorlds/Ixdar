package shell.ui.tools;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.HashMap;

import shell.Main;
import shell.ToggleType;
import shell.file.Manifold;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.ui.Camera;
import shell.ui.Drawing;

public class NegativeCutMatchViewTool extends Tool {

    public Manifold manifold;
    public HashMap<Long, ArrayList<Segment>> negativeSegmentMap;
    public int layerCalculated;

    public NegativeCutMatchViewTool() {
        disallowedToggles = new ToggleType[] { ToggleType.DrawCutMatch };
    }

    @Override
    public void reset() {
        hover = null;
        hoverCP = null;
        hoverKP = null;
        manifold = null;
        negativeSegmentMap = null;
    }

    @Override
    public void draw(Graphics2D g2, Camera camera, int minLineThickness) {
        if (layerCalculated != Main.metroDrawLayer) {
            initSegmentMap();
            return;
        }
        if (hover != null) {
            long matchId = Segment.idTransformOrdered(hoverKP.id, hoverCP.id);
            ArrayList<Segment> matchSegments = negativeSegmentMap.get(matchId);
            long cutId = Segment.idTransform(hoverKP.id, hoverCP.id);
            Segment cutSeg = hoverKP.segmentLookup.get(cutId);
            if(matchSegments == null){
                float z =0;
            }
            for (Segment s : matchSegments) {
                Drawing.drawSingleCutMatch(Main.main, g2, s, cutSeg, Drawing.MIN_THICKNESS * 2, Main.retTup.ps, camera);
            }
            Drawing.drawCircle(g2, hoverKP, Color.green, camera, minLineThickness);
        }
    }

    @Override
    public void click(Segment s, VirtualPoint kp, VirtualPoint cp) {

    }

    @Override
    public String displayName() {
        return "Negative Cut Match View";
    }

    public void initSegmentMap() {
        layerCalculated = Main.metroDrawLayer;
        ArrayList<Knot> knotsDisplayed = Main.knotsDisplayed;
        negativeSegmentMap = new HashMap<>();
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
                        if (firstSegment.distance - s.distance < 0) {
                            firstNegativeSegments.add(firstSegment);
                        }
                        if (lastSegment.distance - s.distance < 0) {
                            lastNegativeSegments.add(lastSegment);
                        }
                    }
                }
                negativeSegmentMap.put(idFirst, firstNegativeSegments);
                negativeSegmentMap.put(idLast, lastNegativeSegments);
            }

        }
    }
}
