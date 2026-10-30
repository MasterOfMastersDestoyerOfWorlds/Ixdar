package ixdar.scenes.trade.hud;

import ixdar.game.trade.TradeGame;
import ixdar.game.trade.economy.Outcome;
import ixdar.game.trade.level.TradeLevel;
import ixdar.graphics.render.color.Palette;
import ixdar.scenes.trade.TradeScene;

/**
 * The level's result over the map: won or lost, the medal, why, and Retry or Next level.
 */
public class OutcomeDialog {
    public static final float BOX_WIDTH = 480f;
    public static final float BOX_HEIGHT = 180f;
    public static final float BUTTON_WIDTH = 150f;

    /**
     * Draw the dialog centred in the space left of the side panel.
     *
     * @param scene  the trade scene
     * @param width  width of the space it centres in
     * @param height framebuffer height
     */
    public void draw(TradeScene scene, float width, float height) {
        Hud hud = scene.hud;
        TradeGame game = scene.game;
        Palette palette = scene.palette;
        float boxWidth = Math.min(BOX_WIDTH, width - 2 * Hud.PADDING);
        float x = (width - boxWidth) * 0.5f;
        float y = (height - BOX_HEIGHT) * 0.5f;
        hud.panel(x, y, boxWidth, BOX_HEIGHT, palette.panel, palette.panelBorder);
        boolean won = game.outcome == Outcome.WON;
        String headline = won ? "Level won" : "Level lost";
        if (won && game.medal > 0) {
            headline += PlacePanel.SEPARATOR + PlacePanel.MEDAL_NAMES[game.medal] + " medal";
        }
        float centre = x + boxWidth * 0.5f;
        hud.text(headline, centre, y + BOX_HEIGHT - Hud.ROW_HEIGHT * 1.5f, Hud.TITLE_TEXT_HEIGHT,
                won ? palette.success : palette.error);
        hud.text(game.outcomeText, centre, y + BOX_HEIGHT - Hud.ROW_HEIGHT * 3, Hud.TEXT_HEIGHT, palette.text);
        float buttonWidth = Math.min(BUTTON_WIDTH, boxWidth * 0.4f);
        hud.button(x + Hud.PADDING * 2, y + Hud.PADDING * 2, buttonWidth, Hud.BUTTON_HEIGHT, "Retry",
                palette.panel, true, () -> scene.loadLevel(scene.levelIndex));
        if (won && scene.levelIndex + 1 < TradeLevel.CAMPAIGN.length) {
            hud.button(x + boxWidth - buttonWidth - Hud.PADDING * 2, y + Hud.PADDING * 2, buttonWidth,
                    Hud.BUTTON_HEIGHT, "Next level", palette.highlight, true,
                    () -> scene.loadLevel(scene.levelIndex + 1));
        }
    }
}
