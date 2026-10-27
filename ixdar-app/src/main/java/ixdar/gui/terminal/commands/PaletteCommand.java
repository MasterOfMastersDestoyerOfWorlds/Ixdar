package ixdar.gui.terminal.commands;

import java.util.Arrays;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.annotations.command.Subsystem;
import ixdar.graphics.render.color.Palette;
import ixdar.graphics.render.color.Palettes;
import ixdar.gui.terminal.Terminal;
import ixdar.scenes.Scene;

/**
 * {@code palette}: list every palette in Palettes, step to the next, or wear one by name, so a new look
 * can be tried on any scene without touching its code.
 */
@CommandAnnotation(id = "palette", acts = Subsystem.VIEW)
public class PaletteCommand extends TerminalCommand {
    public static final String NEXT = "next";
    public static final String LIST = "list";

    @Override
    public String fullName() {
        return "palette";
    }

    @Override
    public String shortName() {
        return "pal";
    }

    @Override
    public String desc() {
        return "show, cycle or pick the scene's colour palette";
    }

    @Override
    public String usage() {
        return "usage: palette [next|list|<name>]";
    }

    @Override
    public int argLength() {
        return -1;
    }

    /**
     * With no argument or {@code next}, wear the next option; with {@code list}, show them all; with a
     * name, wear that one.
     *
     * @param args     the command line split on spaces
     * @param startIdx where this command's arguments start
     * @param terminal the terminal to report to
     * @return no follow-up command
     */
    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        Scene scene = terminal.scene;
        String wanted = args.length > startIdx ? String.join(" ", Arrays.copyOfRange(args, startIdx,
                args.length)) : NEXT;
        if (wanted.equals(LIST)) {
            for (Palette option : Palettes.OPTIONS) {
                terminal.history.addLine((option == scene.palette ? "* " : "  ") + option.name,
                        scene.palette.text);
            }
            return null;
        }
        if (wanted.equals(NEXT)) {
            scene.cyclePalette();
        } else {
            Palette chosen = null;
            for (Palette option : Palettes.OPTIONS) {
                chosen = option.name.equalsIgnoreCase(wanted) ? option : chosen;
            }
            if (chosen == null) {
                terminal.history.addLine("no palette named " + wanted + "; try palette list",
                        scene.palette.error);
                return null;
            }
            scene.palette = chosen;
        }
        terminal.history.addLine("palette: " + scene.palette.name, scene.palette.success);
        return null;
    }
}
