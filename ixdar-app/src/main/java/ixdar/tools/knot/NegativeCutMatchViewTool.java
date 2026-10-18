package ixdar.tools.knot;

import java.util.ArrayList;
import java.util.HashMap;

import ixdar.annotations.tool.ToolAnnotation;
import ixdar.geometry.tsp.knot.Knot;
import ixdar.geometry.tsp.knot.Segment;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.text.HyperString;
import ixdar.scenes.main.Toggle;
import ixdar.scenes.main.MainScene;

/**
 * Negative Cut-Match View tool: highlights manifold segments whose hole-move
 * cut/match swap would shorten the total length, letting the user inspect the
 * negative-cost matches available at any segment.
 */
@ToolAnnotation(id = NegativeCutMatchViewTool.TOOL_NAME, host = MainScene.class)
public class NegativeCutMatchViewTool extends KnotTool {
    public static final String TOOL_NAME = "negativecutmatchview";
    public static ArrayList<Color> colors;

    public HashMap<Long, ArrayList<Segment>> negativeSegmentMap;
    public int layerCalculated;
    HashMap<Long, Integer> colorLookup;

    /**
     * Build the tool: disallow rendering toggles that would clutter the
     * overlay and seed the {@code colors} palette (blue, red).
     *
     * @param editor the editor the tool works on
     */
    public NegativeCutMatchViewTool(MainScene editor) {
        super(editor);
        disallowedToggles = new Toggle[] { Toggle.DrawCutMatch, Toggle.DrawKnotGradient,
                Toggle.DrawMetroDiagram, Toggle.DrawDisplayedKnots };
        colors = new ArrayList<>();
        colors.add(Color.BLUE);
        colors.add(Color.RED);
    }

    /**
     * Reset selection state and rebuild the negative-segment cache for the
     * current draw layer.
     */
    @Override
    public void activate() {
        super.activate();
        initSegmentMap();
    }

    /** Nothing to confirm: the tool only shows the negative matches. */
    @Override
    public void confirm() {
    }

    /**
     * Draw the orange "cut" segment at the hovered manifold edge, the cyan
     * candidate match segments with negative net length, and color each
     * displayed knot's manifold path by whether it has any negative matches.
     *
     * @param camera the scene camera
     * @param minLineThickness base line thickness in pixels
     */
    @Override
    public void drawOverlay(Camera2D camera, float minLineThickness) {
        if (layerCalculated != editor.knotDrawLayer) {
            initSegmentMap();
            return;
        }
        if (displaySegment != null) {
            long matchId = Segment.idTransformOrdered(displayKP.id, displayCP.id);
            ArrayList<Segment> matchSegments = negativeSegmentMap.get(matchId);
            long cutId = Segment.idTransform(displayKP.id, displayCP.id);
            Segment cutSeg = displayKP.segmentLookup.get(cutId);
            Drawing.drawScaledSegment(cutSeg, Color.BROWN_DARK, 2 * Drawing.MIN_THICKNESS,
                    camera);
            if (matchSegments != null) {
                for (Segment s : matchSegments) {
                    if (!s.equals(editor.hoverSegment)) {
                        Drawing.drawScaledSegment(s, Color.TEAL, Drawing.MIN_THICKNESS,
                                camera);
                    }
                }
                Drawing.drawCircle(displayKP, Color.GREEN, camera, minLineThickness);
            }
        }
        for (Knot k : editor.knotsDisplayed) {
            Drawing.drawGradientPath(k, lookupSegmentPairs(k), colorLookup, colors,
                    camera,
                    Drawing.MIN_THICKNESS);
        }
    }

    /**
     * (Re)compute, for each manifold segment of each displayed knot, the list
     * of off-manifold candidate match segments whose length is shorter than
     * the manifold segment (i.e. would yield a negative-cost cut/match), and
     * record per-direction color indices accordingly.
     */
    public void initSegmentMap() {
        layerCalculated = editor.knotDrawLayer;
        infoText = null;
        ArrayList<Knot> knotsDisplayed = editor.knotsDisplayed;
        negativeSegmentMap = new HashMap<>();
        colorLookup = new HashMap<>();
        for (Knot k : knotsDisplayed) {
            for (Segment s : k.manifoldSegments) {
                long idFirst = Segment.idTransformOrdered(s.first.id, s.last.id);
                long idLast = Segment.idTransformOrdered(s.last.id, s.first.id);
                ArrayList<Segment> firstNegativeSegments = new ArrayList<>();
                ArrayList<Segment> lastNegativeSegments = new ArrayList<>();
                for (Knot vp : k.knotPointsFlattened) {
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

    /**
     * Build the side-panel text describing the hovered cut, its length, and
     * each negative-cost candidate match with its delta.
     *
     * @return the formatted info text
     */
    @Override
    public HyperString buildInfoText() {
        HyperString h = new HyperString();
        if (displaySegment != null) {
            h.addWord("Cut: ", Color.IXDAR);
            h.addHyperString(displaySegment.toHyperString(editor, Color.BROWN_DARK, false));
            h.newLine();
            h.addWord("Cut Length: ");
            h.addDistance(displaySegment.distance, Color.BROWN_DARK);
            h.newLine();
            h.addWord("Knot Point: " + displayKP, Color.GREEN);
            h.newLine();
            h.addWord("Cut Point: " + displayCP, Color.BROWN_DARK);
            long matchId = Segment.idTransformOrdered(displayKP.id, displayCP.id);
            ArrayList<Segment> matchSegments = negativeSegmentMap.get(matchId);
            h.newLine();
            h.addWord("Negative Matches:");
            h.newLine();
            for (Segment s : matchSegments) {
                h.addHyperString(s.toHyperString(editor, Color.TEAL, false));
                h.addDistance(s.distance - displaySegment.distance, Color.RED);
                h.newLine();
            }
            h.newLine();

        }
        h.wrap();
        return h;
    }

    /**
     * {@inheritDoc}.
     *
     * @return the display name "Negative Cut Match View"
     */
    @Override
    public String displayName() {
        return "Negative Cut Match View";
    }

    /**
     * {@inheritDoc}.
     *
     * @return the short terminal alias {@code "neg"}
     */
    @Override
    public String shortName() {
        return "neg";
    }

    /**
     * {@inheritDoc}.
     *
     * @return the full terminal name {@code "negativecutmatchview"}
     */
    @Override
    public String toolName() {
        return TOOL_NAME;
    }

    /**
     * {@inheritDoc}.
     *
     * @return the one-line description of this tool's purpose
     */
    @Override
    public String desc() {
        return "A tool to view hole moves that have negative total length";
    }
}
