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
        MainScene.tool.back();
        return new String[] { "ex" };
    }
}
