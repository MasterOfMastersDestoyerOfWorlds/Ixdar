package ixdar.scenes.trade.hud;

import java.util.ArrayList;
import java.util.function.BiConsumer;

import org.joml.Vector2f;

import ixdar.game.trade.TradeGame;
import ixdar.graphics.render.color.Palette;
import ixdar.scenes.trade.TradeScene;

/**
 * The bar along the top: gold, last lap's profit, the lap count, the level's title and its goal, a share
 * bar on a market war or the medal thresholds on a gold level. Items flow left to right and wrap onto
 * a second row when the window is narrow.
 */
public class TopBar {
    public static final float BAR_ROW_HEIGHT = 40f;
    public static final float ITEM_GAP = 24f;
    public static final float SHARE_BAR_WIDTH = 300f;
    public static final float SHARE_BAR_HEIGHT = 18f;

    /** Where the gold counter sits this frame, which the lap's coins fly to. */
    public final Vector2f goldPosition = new Vector2f();

    /**
     * Draw the bar.
     *
     * @param scene  the trade scene
     * @param width  framebuffer width
     * @param height framebuffer height
     * @return the y of the bar's bottom edge
     */
    public float draw(TradeScene scene, float width, float height) {
        Hud hud = scene.hud;
        TradeGame game = scene.game;
        Palette palette = scene.palette;
        ArrayList<Float> widths = new ArrayList<>();
        ArrayList<BiConsumer<Float, Float>> painters = new ArrayList<>();

        String gold = Math.round(scene.lapAnimation.counterGold(scene)) + " gold";
        widths.add(hud.textWidth(gold, Hud.TITLE_TEXT_HEIGHT));
        painters.add((x, y) -> {
            goldPosition.set(x + hud.textWidth(gold, Hud.TITLE_TEXT_HEIGHT) * 0.5f, y);
            hud.text(gold, goldPosition.x, y, Hud.TITLE_TEXT_HEIGHT, palette.highlight);
        });
        String profit = (game.player.lastProfit >= 0 ? "+" : "") + Math.round(game.player.lastProfit) + " / lap";
        widths.add(hud.textWidth(profit, Hud.TEXT_HEIGHT));
        painters.add((x, y) -> hud.text(profit, x + hud.textWidth(profit, Hud.TEXT_HEIGHT) * 0.5f, y,
                Hud.TEXT_HEIGHT, palette.background));
        String lap = "Lap " + Math.min(game.lap, game.level.laps) + " / " + game.level.laps;
        widths.add(hud.textWidth(lap, Hud.TEXT_HEIGHT));
        painters.add((x, y) -> hud.text(lap, x + hud.textWidth(lap, Hud.TEXT_HEIGHT) * 0.5f, y, Hud.TEXT_HEIGHT,
                palette.background));
        String title = game.level.title;
        widths.add(hud.textWidth(title, Hud.TEXT_HEIGHT));
        painters.add((x, y) -> hud.text(title, x + hud.textWidth(title, Hud.TEXT_HEIGHT) * 0.5f, y, Hud.TEXT_HEIGHT,
                palette.background));
        if (game.level.shareGoal > 0) {
            float barWidth = Math.min(SHARE_BAR_WIDTH, width - 2 * Hud.PADDING);
            widths.add(barWidth);
            painters.add((x, y) -> {
                double you = game.share(game.player);
                double rival = game.rival == null ? 0 : game.share(game.rival);
                float top = y - SHARE_BAR_HEIGHT * 0.5f;
                hud.rectangle(x, top, barWidth, SHARE_BAR_HEIGHT, palette.mutedText);
                hud.rectangle(x, top, (float) (you * barWidth), SHARE_BAR_HEIGHT, palette.accent);
                hud.rectangle(x + (float) (you * barWidth), top, (float) (rival * barWidth), SHARE_BAR_HEIGHT,
                        palette.warning);
                hud.rectangle(x + (float) game.level.shareGoal * barWidth - 1, top - 3, 2, SHARE_BAR_HEIGHT + 6,
                        palette.highlight);
                hud.text("you " + Math.round(you * TradeGame.PERCENT) + "%  rival " + Math.round(
                        rival * TradeGame.PERCENT) + "%  goal " + Math.round(game.level.shareGoal * TradeGame.PERCENT)
                        + "%", x + barWidth * 0.5f, y, Hud.SMALL_TEXT_HEIGHT, palette.background);
            });
        } else {
            StringBuilder medals = new StringBuilder("earned ")
                    .append(Math.round(game.player.gold - game.player.startGold)).append(", medals");
            for (int threshold : game.level.medals) {
                medals.append(' ').append(threshold);
            }
            String text = medals.toString();
            widths.add(hud.textWidth(text, Hud.TEXT_HEIGHT));
            painters.add((x, y) -> hud.text(text, x + hud.textWidth(text, Hud.TEXT_HEIGHT) * 0.5f, y,
                    Hud.TEXT_HEIGHT, palette.highlight));
        }

        int rows = 1;
        float x = Hud.PADDING;
        float[] itemX = new float[widths.size()];
        int[] itemRow = new int[widths.size()];
        for (int k = 0; k < widths.size(); k++) {
            if (x > Hud.PADDING && x + widths.get(k) > width - Hud.PADDING) {
                rows++;
                x = Hud.PADDING;
            }
            itemX[k] = x;
            itemRow[k] = rows - 1;
            x += widths.get(k) + ITEM_GAP;
        }
        float barHeight = rows * BAR_ROW_HEIGHT;
        float bottom = height - barHeight;
        hud.panel(0, bottom, width, barHeight + Hud.CORNER_RADIUS, palette.text, palette.text);
        for (int k = 0; k < painters.size(); k++) {
            painters.get(k).accept(itemX[k], height - (itemRow[k] + 0.5f) * BAR_ROW_HEIGHT);
        }
        return bottom;
    }
}
