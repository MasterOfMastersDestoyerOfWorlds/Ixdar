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

/**
 * Neighbor View tool: draws each knot point's two shortest neighbor segments
 * as dashed overlays, so the user can see the local nearest-neighbor graph at
 * the current draw layer.
 */
public class NeighborViewTool extends Tool {
    public static ArrayList<Color> colors;

    public int layerCalculated;
    HashMap<Long, Integer> colorLookup;

    /**
     * Build the tool: disallow rendering toggles that would clutter the
     * neighbor overlay and seed the {@code colors} palette (green, yellow).
     */
    public NeighborViewTool() {
        disallowedToggles = new Toggle[] { Toggle.DrawCutMatch, Toggle.DrawKnotGradient,
                Toggle.DrawMetroDiagram, Toggle.DrawDisplayedKnots };
        colors = new ArrayList<>();
        colors.add(Color.GREEN);
        colors.add(Color.YELLOW);
    }

    /**
     * Reset selection state and rebuild the neighbor color lookup for the
     * current draw layer.
     */
    @Override
    public void reset() {
        super.reset();
        initSegmentMap();
    }

    /**
     * Draw each cached neighbor segment as a dashed line in its assigned
     * color. Rebuilds the cache lazily when the draw layer has changed.
     *
     * @param camera the scene camera
     * @param minLineThickness base line thickness in pixels (unused; dashes
     *                         derive their thickness from {@link Drawing})
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
     * (Re)compute the segment&rarr;color index lookup for the current draw
     * layer: each displayed knot's first two sorted segments are colored 0
     * (closest neighbor) and 1 (second-closest).
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
     * Build the side-panel text describing the currently displayed segment
     * (cut, length, knot point, cut point).
     *
     * @return the formatted info text
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
     * {@inheritDoc}.
     *
     * @return the display name "Neighbor View"
     */
    @Override
    public String displayName() {
        return "Neighbor View";
    }

    /**
     * {@inheritDoc}.
     *
     * @return the short terminal alias {@code "nbr"}
     */
    @Override
    public String shortName() {
        return "nbr";
    }

    /**
     * {@inheritDoc}.
     *
     * @return the full terminal name {@code "neighborview"}
     */
    @Override
    public String fullName() {
        return "neighborview";
    }

    /**
     * {@inheritDoc}.
     *
     * @return the one-line description of this tool's purpose
     */
    @Override
    public String desc() {
        return "A tool to view the neighbors of all of the points";
    }
}
