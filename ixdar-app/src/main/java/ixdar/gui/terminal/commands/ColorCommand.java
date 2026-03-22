package ixdar.gui.terminal.commands;

import java.util.ArrayList;
import java.util.Random;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorRGB;
import ixdar.gui.terminal.Terminal;
import ixdar.platform.Toggle;
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
        Random colorSeed = new Random();
        MainScene.stickyColor = new ColorRGB(colorSeed.nextFloat(), colorSeed.nextFloat(),
                colorSeed.nextFloat());
        if (MainScene.tool.canUseToggle(Toggle.DrawMetroDiagram)) {
            MainScene.metroColors = new ArrayList<>();
            int totalLayers = MainScene.totalLayers;
            float startHue = colorSeed.nextFloat();
            float step = 1.0f / ((float) totalLayers);
            for (int i = 0; i <= totalLayers; i++) {
                MainScene.metroColors.add(Color.getHSBColor((startHue + step * i) % 1.0f, 1.0f, 1.0f));
            }
        }
        float startHue = colorSeed.nextFloat();
        float step = 1.0f / ((float) MainScene.resultKnots.size());
        for (int i = 0; i < MainScene.knotGradientColors.size(); i++) {
            MainScene.knotGradientColors.set(i, Color.getHSBColor((startHue + step * i) % 1.0f, 1.0f, 1.0f));
        }
        return new String[] { cmd };
    }
}
