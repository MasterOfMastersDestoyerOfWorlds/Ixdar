package ixdar.scenes.trade.render;

import java.util.ArrayList;
import java.util.HashSet;

import org.joml.Vector2f;

import ixdar.game.trade.economy.Facility;
import ixdar.game.trade.map.City;
import ixdar.game.trade.map.TradeMap;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.Palette;
import ixdar.graphics.render.sdf.SDFLine;
import ixdar.graphics.render.sdf.SDFPolygon;
import ixdar.scenes.trade.MapMode;
import ixdar.scenes.trade.TradeScene;

/**
 * Draws the irregular grid under the map: every cell side once, boundary included, at a width that
 * scales with zoom, moor cells filled, and in the owner and market-size modes each cell filled by the
 * market nearest it.
 */
public class GridRenderer {
    public static final float GRID_LINE_WIDTH_IN_CELLS = 0.025f;
    public static final float TERRITORY_ALPHA = 0.3f;
    public static final float TERRITORY_RANGE_IN_CELLS = 4f;
    public static final float LARGEST_DEMAND_ALPHA = 0.55f;

    private final TradeMap map;
    /** Each cell side once, as its two corner indices. */
    private final ArrayList<int[]> sides = new ArrayList<>();
    /** The market nearest each cell, or null beyond range. */
    private final City[] nearestCity;
    private final SDFLine line = new SDFLine();
    private final SDFPolygon cellFill = new SDFPolygon();

    /**
     * Index a map's sides and each cell's nearest market.
     *
     * @param map the level's map
     */
    public GridRenderer(TradeMap map) {
        this.map = map;
        HashSet<Long> seen = new HashSet<>();
        int cornerCount = map.grid.anchorCount();
        for (int[] corners : map.grid.faces()) {
            for (int k = 0; k < corners.length; k++) {
                int p = corners[k], q = corners[(k + 1) % corners.length];
                if (seen.add((long) Math.min(p, q) * cornerCount + Math.max(p, q))) {
                    sides.add(new int[] { p, q });
                }
            }
        }
        int cellCount = map.grid.dualPoints().size();
        nearestCity = new City[cellCount];
        float range = TERRITORY_RANGE_IN_CELLS * map.grid.cellSpacing();
        for (int cell = 0; cell < cellCount; cell++) {
            float best = range;
            for (City city : map.cities) {
                float d = map.grid.cellDistance(cell, city.cell);
                if (d < best && !map.moor.blocks(cell)) {
                    best = d;
                    nearestCity[cell] = city;
                }
            }
        }
    }

    /**
     * Draw the cell fills for moor and the map mode, then every side.
     *
     * @param scene the trade scene, for its camera and game
     */
    public void draw(TradeScene scene) {
        int largestDemand = 1;
        for (City city : map.cities) {
            largestDemand = Math.max(largestDemand, city.demand);
        }
        Palette palette = scene.palette;
        for (int cell = 0; cell < nearestCity.length; cell++) {
            Color fill = null;
            City market = nearestCity[cell];
            if (map.moor.blocks(cell)) {
                fill = palette.surface;
            } else if (market != null && scene.mapMode == MapMode.OWNER) {
                int yours = market.lastSold.getOrDefault(scene.game.player, 0);
                int theirs = scene.game.rival == null ? 0 : market.lastSold.getOrDefault(scene.game.rival, 0);
                boolean yourBase = market.facility(scene.game.player) == Facility.BASE;
                boolean theirBase = scene.game.rival != null && market.facility(scene.game.rival) == Facility.BASE;
                if (yours > theirs || (yours == theirs && yourBase)) {
                    fill = palette.accent.withAlpha(TERRITORY_ALPHA);
                } else if (theirs > yours || (yours == theirs && theirBase)) {
                    fill = palette.warning.withAlpha(TERRITORY_ALPHA);
                }
            } else if (market != null && scene.mapMode == MapMode.MARKET_SIZE && market.demand > 0) {
                fill = palette.highlight.withAlpha(LARGEST_DEMAND_ALPHA * market.demand / largestDemand);
            }
            if (fill != null) {
                ArrayList<Vector2f> corners = new ArrayList<>();
                for (int corner : map.grid.faces().get(cell)) {
                    corners.add(scene.screen(map.grid.anchorPoints().get(corner)));
                }
                cellFill.draw(corners, fill, scene.mapCamera);
            }
        }
        line.setStroke(GRID_LINE_WIDTH_IN_CELLS * map.grid.cellSpacing() * scene.scale(), false);
        for (int[] side : sides) {
            line.draw(scene.screen(map.grid.anchorPoints().get(side[0])),
                    scene.screen(map.grid.anchorPoints().get(side[1])), palette.gridLine, scene.mapCamera);
        }
    }
}
