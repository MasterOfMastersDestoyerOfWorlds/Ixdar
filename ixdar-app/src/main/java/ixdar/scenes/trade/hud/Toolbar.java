package ixdar.scenes.trade.hud;

import java.util.ArrayList;

import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.Palette;
import ixdar.scenes.trade.MapMode;
import ixdar.scenes.trade.TradeScene;
import ixdar.tools.EditHistory;
import ixdar.tools.Tool;
import ixdar.tools.trade.TradeRouteTool;

/**
 * The bottom bar: one button per {@link ToolbarMenu}, each opening a column of buttons above it. Tools
 * holds Pipe, Grow, Collapse, Undo and Redo; View the map modes and which company draws on top; Game
 * Restart and End lap. One row fits a half-screen window.
 */
public class Toolbar {
    public static final float MENU_BUTTON_WIDTH = 170f;
    public static final float ITEM_WIDTH = 190f;
    public static final String LABEL_SEPARATOR = ": ";
    public static final float HEIGHT = Hud.BUTTON_HEIGHT + 2 * Hud.PADDING;

    /** The menu whose column is open, or null when all are shut. */
    public ToolbarMenu open;

    /**
     * Draw the bar, {@link #HEIGHT} tall, and the open menu's column above it.
     *
     * @param scene the trade scene
     * @param width framebuffer width
     */
    public void draw(TradeScene scene, float width) {
        Hud hud = scene.hud;
        Palette palette = scene.palette;
        float height = HEIGHT;
        hud.panel(0, -Hud.CORNER_RADIUS, width, height + Hud.CORNER_RADIUS, palette.text, palette.text);
        float buttonWidth = Math.min(MENU_BUTTON_WIDTH,
                (width - (ToolbarMenu.values().length + 1) * Hud.PADDING) / ToolbarMenu.values().length);
        for (ToolbarMenu menu : ToolbarMenu.values()) {
            String state = switch (menu) {
            case TOOLS -> LABEL_SEPARATOR + scene.activeTool.displayName();
            case VIEW -> LABEL_SEPARATOR + scene.mapMode.label;
            case GAME -> "";
            };
            float x = Hud.PADDING + menu.ordinal() * (buttonWidth + Hud.PADDING);
            hud.button(x, Hud.PADDING, buttonWidth, Hud.BUTTON_HEIGHT, menu.label + state + (open == menu ? " v" : " ^"),
                    open == menu ? palette.accent : palette.panel, true, () -> open = open == menu ? null : menu);
        }
        if (open == null) {
            return;
        }

        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Color> fills = new ArrayList<>();
        ArrayList<Boolean> enabled = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        TradeRouteTool routeTool = scene.activeTool instanceof TradeRouteTool t ? t : scene.pipeTool;
        switch (open) {
        case TOOLS -> {
            for (Tool tool : new Tool[] { scene.pipeTool, scene.growTool, scene.collapseTool }) {
                labels.add(tool.displayName() + "  (" + tool.displayName().charAt(0) + ")");
                fills.add(scene.activeTool == tool ? palette.accent : palette.panel);
                enabled.add(true);
                actions.add(() -> {
                    scene.switchTool(tool);
                    open = null;
                });
            }
            EditHistory<?> history = routeTool.history();
            labels.add("Undo  (Z)");
            fills.add(palette.panel);
            enabled.add(history != null && history.undoDepth > 0);
            actions.add(routeTool::undo);
            labels.add("Redo  (Y)");
            fills.add(palette.panel);
            enabled.add(history != null && history.redoDepth() > 0);
            actions.add(routeTool::redo);
        }
        case VIEW -> {
            for (MapMode mode : MapMode.values()) {
                labels.add(mode.label);
                fills.add(scene.mapMode == mode ? palette.accent : palette.panel);
                enabled.add(true);
                actions.add(() -> scene.mapMode = mode);
            }
            labels.add(scene.rivalOnTop ? "On top: rival" : "On top: you");
            fills.add(palette.panel);
            enabled.add(scene.game.rival != null);
            actions.add(() -> scene.rivalOnTop = !scene.rivalOnTop);
        }
        case GAME -> {
            labels.add("Restart  (R)");
            fills.add(palette.panel);
            enabled.add(true);
            actions.add(() -> {
                open = null;
                scene.loadLevel(scene.levelIndex);
            });
            labels.add("End lap  (E)");
            fills.add(palette.highlight);
            enabled.add(true);
            actions.add(() -> {
                open = null;
                scene.endLap();
            });
        }
        }

        float itemWidth = Math.min(ITEM_WIDTH, width - 2 * Hud.PADDING);
        float left = Math.min(Hud.PADDING + open.ordinal() * (buttonWidth + Hud.PADDING),
                width - itemWidth - Hud.PADDING);
        float columnHeight = labels.size() * (Hud.BUTTON_HEIGHT + Hud.PADDING) + Hud.PADDING;
        hud.panel(left - Hud.PADDING, height, itemWidth + 2 * Hud.PADDING, columnHeight, palette.text,
                palette.panelBorder);
        for (int k = 0; k < labels.size(); k++) {
            float y = height + columnHeight - (k + 1) * (Hud.BUTTON_HEIGHT + Hud.PADDING);
            hud.button(left, y, itemWidth, Hud.BUTTON_HEIGHT, labels.get(k), fills.get(k), enabled.get(k),
                    actions.get(k));
        }
    }
}
