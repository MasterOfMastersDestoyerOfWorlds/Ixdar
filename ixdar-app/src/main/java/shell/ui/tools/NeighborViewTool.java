package shell.ui.tools;

import java.util.ArrayList;
import java.util.HashMap;

import shell.Toggle;
import shell.cameras.Camera2D;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.render.color.Color;
import shell.render.text.HyperString;
import shell.ui.Drawing;
import shell.ui.main.Main;

public class NeighborViewTool extends Tool {

    public int layerCalculated;
    HashMap<Long, Integer> colorLookup;
    public static ArrayList<Color> colors;

    public NeighborViewTool() {
        disallowedToggles = new Toggle[] { Toggle.DrawCutMatch, Toggle.DrawKnotGradient,
                Toggle.DrawMetroDiagram, Toggle.DrawDisplayedKnots };
        colors = new ArrayList<>();
        colors.add(Color.GREEN);
        colors.add(Color.YELLOW);
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
        for (Long segmentId : colorLookup.keySet()) {
            Segment s = Main.shell.segmentLookup.get(segmentId);
            Drawing.drawDashedSegment(s, colors.get(colorLookup.get(segmentId)), camera);
        }
    }

    public void initSegmentMap() {
        layerCalculated = Main.knotDrawLayer;
        ArrayList<Knot> knotsDisplayed = Main.knotsDisplayed;
        colorLookup = new HashMap<>();
        for (Knot k : knotsDisplayed) {
            int i = 0;
            while (i < k.sortedSegments.size() && i < 2) {
                Segment s = k.sortedSegments.get(i);
                colorLookup.put(s.id, i);
                i++;
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
            h.newLine();

        }
        h.wrap();
        return h;
    }

    @Override
    public String displayName() {
        return "Neighbor View";
    }

    @Override
    public String shortName() {
        return "nbr";
    }

    @Override
    public String fullName() {
        return "neighborview";
    }

    @Override
    public String desc() {
        return "A tool to view the neighbors of all of the points";
    }
}
