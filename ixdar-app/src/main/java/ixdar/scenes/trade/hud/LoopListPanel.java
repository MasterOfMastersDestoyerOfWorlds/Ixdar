package ixdar.scenes.trade.hud;

import java.util.ArrayList;

import ixdar.game.trade.TradeGame;
import ixdar.game.trade.economy.Loop;
import ixdar.graphics.render.color.Palette;
import ixdar.scenes.trade.TradeScene;

/**
 * The side panel: the level's goal, then the player's loops with their stops, wagons (one per base on the
 * loop) and cost per unit, a tank bar of blood burned against blood sold, and why a loop is idle.
 */
public class LoopListPanel {
    public static final float FILL_BAR_HEIGHT = 12f;
    public static final float LOOP_BLOCK_HEIGHT = Hud.ROW_HEIGHT * 2.6f;
    public static final float GOAL_LINE_HEIGHT = Hud.ROW_HEIGHT * 0.8f;

    /**
     * Draw the panel between two heights.
     *
     * @param scene  the trade scene
     * @param left   its left edge
     * @param top    its top edge
     * @param bottom its bottom edge
     * @param width  its width
     * @return the panel's bottom edge, higher than {@code bottom} when the loops need less room
     */
    public float draw(TradeScene scene, float left, float top, float bottom, float width) {
        Hud hud = scene.hud;
        TradeGame game = scene.game;
        Palette palette = scene.palette;
        ArrayList<String> goalLines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : game.level.goal.split(" ")) {
            String longer = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && hud.textWidth(longer, Hud.SMALL_TEXT_HEIGHT) > width - 2 * Hud.PADDING) {
                goalLines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(longer);
            }
        }
        goalLines.add(line.toString());
        float goalHeight = goalLines.size() * GOAL_LINE_HEIGHT + Hud.PADDING;
        float needed = goalHeight + Hud.ROW_HEIGHT * 2 + Math.max(1, game.player.loops.size()) * LOOP_BLOCK_HEIGHT;
        bottom = Math.max(bottom, top - needed);
        hud.panel(left, bottom, width, top - bottom, palette.panel, palette.panelBorder);
        float centre = left + width * 0.5f;
        float row = top - Hud.PADDING - GOAL_LINE_HEIGHT * 0.5f;
        for (String goalLine : goalLines) {
            hud.text(goalLine, centre, row, Hud.SMALL_TEXT_HEIGHT, palette.text);
            row -= GOAL_LINE_HEIGHT;
        }
        row -= Hud.PADDING;
        hud.rectangle(left + Hud.PADDING, row + Hud.ROW_HEIGHT * 0.5f, width - 2 * Hud.PADDING, 1,
                palette.line);
        hud.text("Loops", centre, row, Hud.TEXT_HEIGHT, palette.text);
        row -= Hud.ROW_HEIGHT;
        if (game.player.loops.isEmpty()) {
            hud.text("Pipe from one of your bases to start one", centre, row, Hud.SMALL_TEXT_HEIGHT,
                    palette.mutedText);
        }
        int shown = 0;
        for (Loop loop : game.player.loops) {
            if (row - LOOP_BLOCK_HEIGHT < bottom) {
                hud.text("+" + (game.player.loops.size() - shown) + " more", centre, row, Hud.SMALL_TEXT_HEIGHT,
                        palette.mutedText);
                break;
            }
            hud.text(loop.stops.length + " stops" + PlacePanel.SEPARATOR + loop.wagons
                    + (loop.wagons == 1 ? " wagon" : " wagons") + PlacePanel.SEPARATOR
                    + (Double.isInfinite(loop.unitCost) ? "sells nothing"
                            : String.format(PlacePanel.COST_FORMAT, loop.unitCost) + " / unit"),
                    centre, row, Hud.SMALL_TEXT_HEIGHT, palette.accent);
            row -= Hud.ROW_HEIGHT * 0.8f;
            int burn = loop.burn * loop.wagons;
            int capacity = game.player.wagonCapacity * loop.wagons;
            float fillWidth = width - 4 * Hud.PADDING;
            float fillX = left + 2 * Hud.PADDING;
            float perUnit = fillWidth / Math.max(1, Math.max(capacity, burn + loop.cargo));
            float barY = row - FILL_BAR_HEIGHT * 0.5f;
            hud.rectangle(fillX - 1, barY - 1, fillWidth + 2, FILL_BAR_HEIGHT + 2, palette.text);
            hud.rectangle(fillX, barY, fillWidth, FILL_BAR_HEIGHT, palette.panel);
            hud.rectangle(fillX, barY, Math.min(fillWidth, burn * perUnit), FILL_BAR_HEIGHT,
                    loop.fuelDeficit > 0 ? palette.error : palette.accent);
            hud.rectangle(fillX + Math.min(fillWidth, burn * perUnit), barY,
                    Math.max(0, Math.min(fillWidth - burn * perUnit, loop.cargo * perUnit)), FILL_BAR_HEIGHT,
                    palette.selected);
            row -= Hud.ROW_HEIGHT * 0.8f;
            String detail = !loop.passesBase ? "no base on this loop: it runs no wagon"
                    : loop.fuelDeficit > 0 ? "runs dry by " + loop.fuelDeficit + " blood between bases"
                            : burn + " burned" + PlacePanel.SEPARATOR + loop.cargo + " sold" + PlacePanel.SEPARATOR
                                    + Math.max(0, capacity - burn - loop.cargo) + " spare";
            hud.text(detail, centre, row, Hud.SMALL_TEXT_HEIGHT, loop.fuelDeficit > 0 ? palette.error : palette.mutedText);
            row -= Hud.ROW_HEIGHT;
            shown++;
        }
        return bottom;
    }
}
