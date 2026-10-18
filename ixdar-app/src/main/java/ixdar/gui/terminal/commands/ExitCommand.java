package ixdar.gui.terminal.commands;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.gui.terminal.Terminal;
import ixdar.scenes.main.MainScene;

@CommandAnnotation(id = "ex")
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

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        if (!(terminal.scene instanceof MainScene editor)) {
            terminal.error(shortName() + " is a MainScene command");
            return null;
        }
        editor.back();
        return new String[] { cmd };
    }
}
