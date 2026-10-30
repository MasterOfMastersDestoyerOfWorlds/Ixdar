package ixdar.scenes.trade.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

import org.joml.Vector2f;
import org.joml.Vector3f;

import ixdar.game.trade.TradeGame;
import ixdar.game.trade.economy.Company;
import ixdar.game.trade.economy.Facility;
import ixdar.game.trade.economy.Loop;
import ixdar.game.trade.map.Bridge;
import ixdar.game.trade.map.City;
import ixdar.game.trade.map.Place;
import ixdar.game.trade.map.River;
import ixdar.game.trade.map.Road;
import ixdar.game.trade.routes.Leg;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.Palette;
import ixdar.graphics.render.sdf.SDFCircle;
import ixdar.graphics.render.sdf.SDFLane;
import ixdar.graphics.render.sdf.SDFLine;
import ixdar.graphics.render.sdf.SDFRoundedRect;
import ixdar.graphics.render.text.Font;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.text.HyperString;
import ixdar.scenes.trade.TradeScene;
import ixdar.scenes.trade.hud.Hud;

/**
 * Draws what runs along grid paths, every width scaled with zoom: rivers along cell sides, roads and
 * bridges, and each company's loops as curved lanes with chevrons, a step shared both ways drawn once
 * with a half-chevron each way. Then the places on top.
 */
public class PathRenderer {
    public static final float LANE_WIDTH = 5f;
    public static final float LANE_WIDTH_IN_CELLS = 0.1f;
    public static final float ROAD_WIDTH_IN_CELLS = 0.07f;
    public static final float ROAD_JOINT_RADIUS = 1f;
    public static final float RIVER_WIDTH_IN_CELLS = 0.2f;
    public static final float BRIDGE_SPAN_IN_RIVERS = 4.2f;
    public static final int PIECE_DIRECTION = 3;
    public static final int PIECE_FORWARD = 3;
    public static final int PIECE_BACKWARD = 4;
    public static final float CITY_RADIUS_IN_CELLS = 0.24f;
    public static final float TOWN_HALF_SIZE_IN_CELLS = 0.1f;
    public static final float LABEL_OFFSET_IN_CELLS = 0.42f;
    public static final float CITY_LABEL_HEIGHT_IN_CELLS = 0.4f;
    public static final float TOWN_LABEL_SHARE = 0.75f;
    public static final float LABEL_OUTLINE_SHARE_OF_HEIGHT = 0.08f;
    public static final float LEG_TAG_OFFSET_IN_CELLS = 0.3f;
    public static final float CITY_BORDER_SHARE = 0.25f;
    public static final float RING_BORDER_SHARE = 0.18f;
    public static final float RING_GROWTH = 1.6f;
    public static final float FOG_ALPHA = 0.14f;
    public static final float UNDERNEATH_ALPHA = 0.6f;
    private static final float LUMA_RED = 0.2126f;
    private static final float LUMA_GREEN = 0.7152f;
    private static final float LUMA_BLUE = 0.0722f;
    private static final float JOINT_MATCH_PIXELS = 0.01f;

    private final SDFLane lane = new SDFLane();
    private final SDFLine line = new SDFLine();
    private final SDFCircle circle = new SDFCircle();
    private final SDFRoundedRect box = new SDFRoundedRect();

    /**
     * Rivers along cell sides, roads through cell centres, then each bridge along the road it carries.
     *
     * @param scene the trade scene
     */
    public void drawTerrain(TradeScene scene) {
        Palette palette = scene.palette;
        float cell = scene.game.map.grid.cellSpacing() * scene.scale();
        line.setStroke(RIVER_WIDTH_IN_CELLS * cell, false, 1f, 0f, true, true, false);
        for (River river : scene.game.map.rivers) {
            for (int k = 1; k < river.points.length; k++) {
                line.draw(scene.screen(river.points[k - 1]), scene.screen(river.points[k]), palette.linkText,
                        scene.mapCamera);
            }
        }
        float roadWidth = ROAD_WIDTH_IN_CELLS * cell;
        for (Road road : scene.game.map.roads) {
            for (int[] piece : pieces(road.nodes)) {
                drawPiece(scene, piece, roadWidth, palette.line, palette.line, false, false);
            }
            for (int end : new int[] { road.nodes[0], road.nodes[road.nodes.length - 1] }) {
                circle.borderThickness = 0;
                circle.draw(scene.screen(scene.game.map.grid.dualPoints().get(end)), roadWidth * ROAD_JOINT_RADIUS,
                        palette.line, palette.line, scene.mapCamera);
            }
        }
        // A bridge follows the road it carries: the road's two pieces meet at the midpoint of the bridged step,
        // and the bridge is each piece's stretch within half a span of that joint, cut from the piece's own
        // curve so it bends with the road. A bridge no road crosses is drawn straight along its step.
        ArrayList<Vector2f> centres = scene.game.map.grid.dualPoints();
        float halfSpan = RIVER_WIDTH_IN_CELLS * scene.game.map.grid.cellSpacing() * BRIDGE_SPAN_IN_RIVERS * 0.5f;
        float halfSpanPixels = halfSpan * scene.scale();
        lane.lineWidth = roadWidth;
        for (Bridge bridge : scene.game.map.bridges) {
            Vector2f joint = new Vector2f(scene.screen(centres.get(bridge.fromCell)))
                    .add(scene.screen(centres.get(bridge.toCell))).mul(0.5f);
            boolean drawn = false;
            for (Road road : scene.game.map.roads) {
                for (int[] piece : pieces(road.nodes)) {
                    Vector2f[] curve = pieceCurve(scene, piece);
                    float share = Math.min(1f, halfSpanPixels / Math.max(curve[0].distance(curve[2]), 1e-6f));
                    Vector2f[] span = curve[0].distance(joint) < JOINT_MATCH_PIXELS ? stretch(curve, 0f, share)
                            : curve[2].distance(joint) < JOINT_MATCH_PIXELS ? stretch(curve, 1f - share, 1f) : null;
                    if (span != null) {
                        lane.draw(span[0], span[1], span[2], palette.panelBorder, palette.panelBorder, false, false,
                                scene.mapCamera);
                        drawn = true;
                    }
                }
            }
            if (!drawn) {
                Vector2f half = new Vector2f(bridge.direction).mul(halfSpan);
                Vector2f a = scene.screen(new Vector2f(bridge.position).sub(half));
                Vector2f b = scene.screen(new Vector2f(bridge.position).add(half));
                lane.draw(a, new Vector2f(a).add(b).mul(0.5f), b, palette.panelBorder, palette.panelBorder, false,
                        false, scene.mapCamera);
            }
        }
    }

    /**
     * Both companies' loops, the one underneath faded, each drawn opaque so overlapping pieces keep one
     * shade. A step two legs share is drawn once: one way, chevrons; both ways, a half-chevron each side.
     *
     * @param scene the trade scene
     */
    public void drawLoops(TradeScene scene) {
        TradeGame game = scene.game;
        Palette palette = scene.palette;
        float cell = game.map.grid.cellSpacing() * scene.scale();
        long keySpan = game.map.grid.dualPoints().size() + 1L;
        for (int pass = 0; pass < 2; pass++) {
            boolean rivalPass = (pass == 0) != scene.rivalOnTop;
            float alpha = pass == 0 ? UNDERNEATH_ALPHA : 1f;
            LinkedHashMap<Long, int[]> drawn = new LinkedHashMap<>();
            HashMap<Long, Color[]> colours = new HashMap<>();
            HashSet<Long> tagged = new HashSet<>();
            ArrayList<Runnable> tags = new ArrayList<>();
            for (Company company : game.companies) {
                if (company.player == rivalPass) {
                    continue;
                }
                for (Loop loop : company.loops) {
                    for (int k = 0; k < loop.stops.length; k++) {
                        Place from = loop.stops[k];
                        Place to = loop.stops[(k + 1) % loop.stops.length];
                        Leg leg = game.table.leg(from, to);
                        Color body;
                        float opacity = alpha;
                        if (company.player) {
                            boolean inside = game.network.knotAt(from, scene.layer) == game.network.knotAt(to,
                                    scene.layer);
                            body = inside ? palette.accent : palette.error;
                        } else {
                            boolean seen = from instanceof City a && game.economy.contested(a, company)
                                    || to instanceof City b && game.economy.contested(b, company);
                            body = palette.warning;
                            opacity = seen ? alpha : FOG_ALPHA;
                        }
                        Color[] paint = { body.flattenedOnto(opacity, palette.background),
                            palette.background };
                        for (int[] piece : pieces(leg.nodes)) {
                            long key = (piece[0] * keySpan + piece[1]) * keySpan + piece[2] + 1;
                            drawn.computeIfAbsent(key, x -> new int[] { piece[0], piece[1], piece[2], 0, 0 })[
                                    piece[PIECE_DIRECTION] == 1 ? PIECE_FORWARD : PIECE_BACKWARD] = 1;
                            colours.putIfAbsent(key, paint);
                        }
                        long pair = Math.min(from.index, to.index) * keySpan + Math.max(from.index, to.index);
                        if (company.player && leg.points.length >= 2 && tagged.add(pair)) {
                            int middle = (leg.points.length - 1) / 2;
                            Vector2f a = scene.screen(leg.points[middle]);
                            Vector2f b = scene.screen(leg.points[middle + 1]);
                            Vector2f across = new Vector2f(a.y - b.y, b.x - a.x);
                            Vector2f at = new Vector2f(a).add(b).mul(0.5f).add(across.lengthSquared() == 0
                                    ? new Vector2f()
                                    : across.normalize().mul(LEG_TAG_OFFSET_IN_CELLS * cell));
                            String text = "+" + leg.cost + (leg.offRoadSteps > 0 ? " (" + leg.offRoadSteps + " off)"
                                    : "");
                            tags.add(() -> scene.hud.tag(text, at.x, at.y, paint[0]));
                        }
                    }
                }
            }
            for (Map.Entry<Long, int[]> entry : drawn.entrySet()) {
                int[] piece = entry.getValue();
                Color[] paint = colours.get(entry.getKey());
                drawPiece(scene, piece, LANE_WIDTH_IN_CELLS * cell, paint[0], paint[1], piece[PIECE_FORWARD] == 1,
                        piece[PIECE_BACKWARD] == 1);
            }
            tags.forEach(Runnable::run);
        }
    }

    /**
     * Cities as discs filled by who sells there, bases as squares, towns as small squares; the selected
     * city and the hovered place ringed. Their names come later, from {@link #drawPlaceNames}.
     *
     * @param scene the trade scene
     */
    public void drawPlaces(TradeScene scene) {
        TradeGame game = scene.game;
        Palette palette = scene.palette;
        float cell = game.map.grid.cellSpacing() * scene.scale();
        float radius = CITY_RADIUS_IN_CELLS * cell;
        for (Place place : game.map.places) {
            Vector2f p = scene.placePixel(place);
            if (place instanceof City city) {
                boolean yourBase = city.facility(game.player) == Facility.BASE;
                boolean rivalBase = game.rival != null && city.facility(game.rival) == Facility.BASE;
                boolean you = city.facility(game.player) != Facility.NONE;
                boolean rival = game.rival != null && city.facility(game.rival) != Facility.NONE;
                if (yourBase || rivalBase) {
                    box.draw(p.x - radius, p.y - radius, 2 * radius, 2 * radius, radius * 0.2f, Hud.BORDER_WIDTH,
                            yourBase ? palette.accent : palette.warning, palette.text, scene.mapCamera);
                } else {
                    circle.borderThickness = CITY_BORDER_SHARE;
                    circle.draw(p, radius, you ? palette.accent : rival ? palette.warning : palette.panel,
                            you && rival ? palette.warning : palette.text, scene.mapCamera);
                }
            } else {
                float half = TOWN_HALF_SIZE_IN_CELLS * cell;
                box.draw(p.x - half, p.y - half, 2 * half, 2 * half, half * 0.3f, 1, palette.surface,
                        palette.mutedText, scene.mapCamera);
            }
            if (place == scene.selectedPlace || place == scene.hoveredPlace) {
                circle.borderThickness = RING_BORDER_SHARE;
                circle.draw(p, radius * RING_GROWTH, Color.TRANSPARENT,
                        place == scene.selectedPlace ? palette.text : palette.mutedText, scene.mapCamera);
            }
        }
    }

    /**
     * Every place's name, drawn last on the map so nothing hides it: light glyphs outlined in dark, the
     * outline a share of the text's height.
     *
     * @param scene the trade scene
     */
    public void drawPlaceNames(TradeScene scene) {
        TradeGame game = scene.game;
        Palette palette = scene.palette;
        float cell = game.map.grid.cellSpacing() * scene.scale();
        float cityHeight = scene.mapCamera.screenSize(CITY_LABEL_HEIGHT_IN_CELLS * game.map.grid.cellSpacing());
        float townHeight = cityHeight * TOWN_LABEL_SHARE;
        Vector3f luma = new Vector3f(LUMA_RED, LUMA_GREEN, LUMA_BLUE);
        boolean textLighter = palette.text.toVector3f().dot(luma) >= palette.background.toVector3f().dot(luma);
        Color fill = textLighter ? palette.text : palette.background;
        Color outline = textLighter ? palette.background : palette.text;
        ArrayList<HyperString> cityNames = new ArrayList<>();
        ArrayList<Vector2f> cityAt = new ArrayList<>();
        ArrayList<HyperString> townNames = new ArrayList<>();
        ArrayList<Vector2f> townAt = new ArrayList<>();
        for (Place place : game.map.places) {
            Vector2f p = scene.placePixel(place);
            HyperString name = new HyperString();
            name.addWord(place.name, fill, outline,
                    LABEL_OUTLINE_SHARE_OF_HEIGHT * (place instanceof City ? cityHeight : townHeight));
            if (place instanceof City) {
                cityNames.add(name);
                cityAt.add(new Vector2f(p.x, p.y - Math.max(LABEL_OFFSET_IN_CELLS * cell,
                        CITY_RADIUS_IN_CELLS * cell + cityHeight * 0.5f)));
            } else {
                townNames.add(name);
                townAt.add(new Vector2f(p.x, p.y - Math.max(LABEL_OFFSET_IN_CELLS * TOWN_LABEL_SHARE * cell,
                        TOWN_HALF_SIZE_IN_CELLS * cell + townHeight * 0.5f)));
            }
        }
        Font font = Drawing.getDrawing().font;
        font.drawHyperStrings(townNames, townAt, townHeight, scene.mapCamera);
        font.drawHyperStrings(cityNames, cityAt, cityHeight, scene.mapCamera);
    }

    /**
     * Cut a path through cell centres into curve pieces named the same whichever way they are walked: a
     * straight half step at each end, and one piece bent through each inner cell.
     *
     * @param cells the path's cells in travel order
     * @return each piece as first, middle and last cell (-1 for an end's half step), then 1 when walked
     *         first to last
     */
    private static ArrayList<int[]> pieces(int[] cells) {
        ArrayList<int[]> pieces = new ArrayList<>();
        int count = cells.length;
        if (count < 2) {
            return pieces;
        }
        pieces.add(new int[] { cells[0], cells[1], -1, 1 });
        for (int k = 1; k < count - 1; k++) {
            int in = cells[k - 1], out = cells[k + 1];
            pieces.add(in < out ? new int[] { in, cells[k], out, 1 } : new int[] { out, cells[k], in, 0 });
        }
        pieces.add(new int[] { cells[count - 1], cells[count - 2], -1, 0 });
        return pieces;
    }

    /**
     * Draw one piece from {@link #pieces} as a butt-ended lane piece, so consecutive pieces meet at a
     * shared tangent without overlapping.
     *
     * @param scene    the trade scene
     * @param piece    first, middle and last cell as {@link #pieces} gives them
     * @param width    stroke width in pixels
     * @param body     the lane's opaque colour
     * @param chevron  the chevrons' colour
     * @param forward  whether traffic runs from the first cell towards the last
     * @param backward whether traffic runs back
     */
    private void drawPiece(TradeScene scene, int[] piece, float width, Color body, Color chevron, boolean forward,
            boolean backward) {
        Vector2f[] curve = pieceCurve(scene, piece);
        lane.lineWidth = width;
        lane.draw(curve[0], curve[1], curve[2], body, chevron, forward, backward, scene.mapCamera);
    }

    /**
     * The quadratic Bezier a piece from {@link #pieces} draws as, in pixels: from the midpoint of its first
     * step, bent through its middle cell, to the midpoint of its last; an end piece runs straight from its
     * cell to the midpoint of its one step.
     *
     * @param scene the trade scene
     * @param piece first, middle and last cell as {@link #pieces} gives them
     * @return start, control and end
     */
    private static Vector2f[] pieceCurve(TradeScene scene, int[] piece) {
        ArrayList<Vector2f> centres = scene.game.map.grid.dualPoints();
        Vector2f first = scene.screen(centres.get(piece[0]));
        Vector2f middle = scene.screen(centres.get(piece[1]));
        if (piece[2] < 0) {
            Vector2f end = new Vector2f(first).add(middle).mul(0.5f);
            return new Vector2f[] { first, new Vector2f(first).add(end).mul(0.5f), end };
        }
        return new Vector2f[] { new Vector2f(first).add(middle).mul(0.5f), middle,
                new Vector2f(middle).add(scene.screen(centres.get(piece[2]))).mul(0.5f) };
    }

    /**
     * The part of a quadratic Bezier between two parameters, itself a quadratic Bezier (de Casteljau): its
     * ends are the curve's points there, its control the start pushed along the start's tangent.
     *
     * @param curve start, control and end
     * @param from  the part's first parameter
     * @param to    its last
     * @return the part's start, control and end
     */
    private static Vector2f[] stretch(Vector2f[] curve, float from, float to) {
        Vector2f start = pointAt(curve, from);
        Vector2f tangent = new Vector2f(curve[1]).sub(curve[0]).mul(2 * (1 - from))
                .add(new Vector2f(curve[2]).sub(curve[1]).mul(2 * from));
        return new Vector2f[] { start, new Vector2f(start).add(tangent.mul((to - from) * 0.5f)), pointAt(curve, to) };
    }

    /**
     * A quadratic Bezier's point at a parameter.
     *
     * @param curve start, control and end
     * @param t     the parameter, 0 at the start
     * @return the point
     */
    private static Vector2f pointAt(Vector2f[] curve, float t) {
        float s = 1 - t;
        return new Vector2f(curve[0]).mul(s * s).add(new Vector2f(curve[1]).mul(2 * s * t))
                .add(new Vector2f(curve[2]).mul(t * t));
    }
}
