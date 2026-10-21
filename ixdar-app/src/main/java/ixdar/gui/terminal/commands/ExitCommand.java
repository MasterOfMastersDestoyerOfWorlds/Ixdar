package ixdar.gui.terminal.commands;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.annotations.command.Subsystem;
import ixdar.gui.terminal.Terminal;
import ixdar.scenes.Scene;

@CommandAnnotation(id = "ex", acts = Subsystem.VIEW)
public class ExitCommand extends TerminalCommand {

    public static String cmd = "ex";

    @Override
    public String fullName() {
        return "exit";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "exit the tool or view";
    }

    @Override
    public String usage() {
        return "usage: ex|exit";
    }

    @Override
    public int argLength() {
        return 0;
    }

    /**
     * Press the view's Esc: leave the active tool, or the view itself from its default tool.
     *
     * @param args tokenised command line, unused
     * @param startIdx index of the first argument, unused
     * @param terminal terminal whose view is left
     * @return {@code ex} again for tab-completion
     */
    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        Scene view = terminal.dataset(Subsystem.VIEW);
        view.escapePressed();
        return new String[] { cmd };
    }
}
