package ixdar.gui.terminal.commands;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.graphics.render.color.ColorRGB;
import ixdar.gui.terminal.Terminal;
import ixdar.platform.Toggle;

@CommandAnnotation(id = "tgl")
public class ToggleCommand extends TerminalCommand {

    public static String cmd = "tgl";

    @Override
    public String fullName() {
        return "toggle";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "toggle any global toggle in the codebase";
    }

    @Override
    public String usage() {
        return "usage: tgl|toggle";
    }

    @Override
    public int argLength() {
        return 1;
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        for (Toggle t : Toggle.values()) {
            if (args[startIdx].equals(t.shortName)) {
                t.value = !t.value;
                terminal.history.addLine(
                        "Toggle " + t.shortName + " had value: " + !t.value + " and now has value: " + t.value,
                        ColorRGB.BLUE_WHITE);
                break;
            }
        }
        return new String[] { "tgl " + args[startIdx] };
    }
}
