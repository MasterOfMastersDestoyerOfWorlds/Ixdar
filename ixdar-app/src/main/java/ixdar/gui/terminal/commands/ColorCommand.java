package ixdar.gui.terminal.commands;

import java.util.ArrayList;
import java.util.Random;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.graphics.render.color.Color;
import ixdar.gui.terminal.Terminal;
import ixdar.scenes.main.Toggle;
import ixdar.scenes.main.MainScene;

@CommandAnnotation(id = "clr")
public class ColorCommand extends TerminalCommand {

    public static String cmd = "clr";

    @Override
    public String fullName() {
        return "color";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "randomize the colors in the graph";
    }

    @Override
    public String usage() {
        return "usage: clr|color";
    }

    @Override
    public int argLength() {
        return 0;
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        MainScene editor = MainScene.current();
        Random colorSeed = new Random();
        editor.stickyColor = Color.random(colorSeed);
        if (editor.tool.canUseToggle(Toggle.DrawMetroDiagram)) {
            editor.metroColors = new ArrayList<>();
            int totalLayers = editor.totalLayers;
            float startHue = colorSeed.nextFloat();
            float step = 1.0f / ((float) totalLayers);
            for (int i = 0; i <= totalLayers; i++) {
                editor.metroColors.add(Color.fullHue((startHue + step * i) % 1.0f));
            }
        }
        float startHue = colorSeed.nextFloat();
        float step = 1.0f / ((float) editor.resultKnots.size());
        for (int i = 0; i < editor.knotGradientColors.size(); i++) {
            editor.knotGradientColors.set(i, Color.fullHue((startHue + step * i) % 1.0f));
        }
        return new String[] { cmd };
    }
}
