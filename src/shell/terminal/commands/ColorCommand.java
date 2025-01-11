package shell.terminal.commands;

import java.util.ArrayList;
import java.util.Random;

import shell.Toggle;
import shell.render.color.Color;
import shell.render.color.ColorRGB;
import shell.terminal.Terminal;
import shell.ui.main.Main;

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
        Main.stickyColor = new ColorRGB(colorSeed.nextFloat(), colorSeed.nextFloat(),
                colorSeed.nextFloat());
        if (Main.tool.canUseToggle(Toggle.drawMetroDiagram)) {
            Main.metroColors = new ArrayList<>();
            int totalLayers = Main.shell.cutEngine.totalLayers;
            float startHue = colorSeed.nextFloat();
            float step = 1.0f / ((float) totalLayers);
            for (int i = 0; i <= totalLayers; i++) {
                Main.metroColors.add(Color.getHSBColor((startHue + step * i) % 1.0f, 1.0f, 1.0f));
            }
        }
        float startHue = colorSeed.nextFloat();
        float step = 1.0f / ((float) Main.shell.cutEngine.flatKnots.size());
        for (int i = 0; i < Main.knotGradientColors.size(); i++) {
            Main.knotGradientColors.set(i, Color.getHSBColor((startHue + step * i) % 1.0f, 1.0f, 1.0f));
        }
        return new String[] { cmd };
    }
}
