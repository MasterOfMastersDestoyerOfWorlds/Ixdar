package ixdar.gui.ui.tools;

import java.util.ArrayList;
import java.util.HashMap;

import ixdar.geometry.knot.Knot;
import ixdar.geometry.knot.Segment;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.text.HyperString;
import ixdar.gui.ui.Drawing;
import ixdar.platform.Toggle;
import ixdar.scenes.main.MainScene;

public class NeighborViewTool extends Tool {
    public static ArrayList<Color> colors;

    public int layerCalculated;
    HashMap<Long, Integer> colorLookup;

    /**
     * TODO: document {@code NeighborViewTool}.
     */
    public NeighborViewTool() {
        disallowedToggles = new Toggle[] { Toggle.DrawCutMatch, Toggle.DrawKnotGradient,
                Toggle.DrawMetroDiagram, Toggle.DrawDisplayedKnots };
        colors = new ArrayList<>();
        colors.add(Color.GREEN);
        colors.add(Color.YELLOW);
    }

    /**
     * TODO: document {@code reset}.
     */
    @Override
    public void reset() {
        super.reset();
        initSegmentMap();
    }

    /**
     * TODO: document {@code draw}.
     *
     * @param camera TODO: describe
     * @param minLineThickness TODO: describe
     */
    @Override
    public void draw(Camera2D camera, float minLineThickness) {
        if (layerCalculated != MainScene.knotDrawLayer) {
            initSegmentMap();
            return;
        }
        for (Long segmentId : colorLookup.keySet()) {
            Segment s = MainScene.shell.segmentLookup.get(segmentId);
            Drawing.drawDashedSegment(s, colors.get(colorLookup.get(segmentId)), camera);
        }
    }

    /**
     * TODO: document {@code initSegmentMap}.
     */
    public void initSegmentMap() {
        layerCalculated = MainScene.knotDrawLayer;
        ArrayList<Knot> knotsDisplayed = MainScene.knotsDisplayed;
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

    /**
     * TODO: document {@code buildInfoText}.
     *
     * @return TODO: describe
     */
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

    /**
     * TODO: document {@code displayName}.
     *
     * @return TODO: describe
     */
    @Override
    public String displayName() {
        return "Neighbor View";
    }

    /**
     * TODO: document {@code shortName}.
     *
     * @return TODO: describe
     */
    @Override
    public String shortName() {
        return "nbr";
    }

    /**
     * TODO: document {@code fullName}.
     *
     * @return TODO: describe
     */
    @Override
    public String fullName() {
        return "neighborview";
    }

    /**
     * TODO: document {@code desc}.
     *
     * @return TODO: describe
     */
    @Override
    public String desc() {
        return "A tool to view the neighbors of all of the points";
    }
}
