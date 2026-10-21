package ixdar.gui.terminal.commands;

import java.util.ArrayList;
import java.util.Random;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.annotations.command.Subsystem;
import ixdar.geometry.tsp.PointSetPath;
import ixdar.graphics.render.color.Color;
import ixdar.gui.terminal.Terminal;
import ixdar.scenes.Scene;
import ixdar.scenes.main.Toggle;

@CommandAnnotation(id = "clr", acts = {Subsystem.VIEW, Subsystem.KNOT})
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

    /**
     * Give the knot new random colours: the sticky colour, one hue per knot, and one per layer when
     * the view's active tool draws the metro diagram.
     *
     * @param args tokenised command line, unused
     * @param startIdx index of the first argument, unused
     * @param terminal terminal whose scene shows the knot and the view
     * @return {@code clr} again for tab-completion
     */
    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        Scene view = terminal.dataset(Subsystem.VIEW);
        PointSetPath pointSetPath = terminal.dataset(Subsystem.KNOT);
        Random colorSeed = new Random();
        pointSetPath.stickyColor = Color.random(colorSeed);
        if (view.activeTool.canUseToggle(Toggle.DrawMetroDiagram)) {
            pointSetPath.metroColors = new ArrayList<>();
            int totalLayers = pointSetPath.totalLayers;
            float startHue = colorSeed.nextFloat();
            float step = 1.0f / ((float) totalLayers);
            for (int i = 0; i <= totalLayers; i++) {
                pointSetPath.metroColors.add(Color.fullHue((startHue + step * i) % 1.0f));
            }
        }
        float startHue = colorSeed.nextFloat();
        float step = 1.0f / ((float) pointSetPath.resultKnots.size());
        for (int i = 0; i < pointSetPath.knotGradientColors.size(); i++) {
            pointSetPath.knotGradientColors.set(i, Color.fullHue((startHue + step * i) % 1.0f));
        }
        return new String[] { cmd };
    }
}
