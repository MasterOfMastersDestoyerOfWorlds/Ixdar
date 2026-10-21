package ixdar.gui.terminal.commands;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.annotations.command.Subsystem;
import ixdar.gui.terminal.Terminal;
import ixdar.gui.ui.text.HyperString;

@CommandAnnotation(id = "cl", acts = Subsystem.TERMINAL)
public class ClearCommand extends TerminalCommand {

    public static String cmd = "cl";

    @Override
    public String fullName() {
        return "clear";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "clear the terminal";
    }

    @Override
    public String usage() {
        return "usage: cl|clear";
    }

    @Override
    public int argLength() {
        return 0;
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        terminal.history = new HyperString();
        return new String[] { cmd };
    }
}
