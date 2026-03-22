package ixdar.gui.terminal.commands;

import ixdar.annotations.command.OptionList;
import ixdar.annotations.command.TerminalOption;
import ixdar.graphics.render.color.Color;
import ixdar.gui.terminal.Terminal;
import ixdar.platform.Platforms;
import ixdar.platform.file.TextFile;

public abstract class TerminalCommand implements TerminalOption {

    public static void help(Terminal terminal, TerminalOption command) {
        String commandName = command.fullName();
        String fileLoc = "./src/shell/terminal/help/" + commandName + ".help";
        terminal.history.addWord(commandName, Color.COMMAND);
        terminal.history.addLine(command.desc(), Color.GREEN);
        try {
            TextFile file = Platforms.get().loadFile(fileLoc);
            for (String line : file.getLines()) {
                terminal.history.addLine(line, Color.LIGHT_GRAY);
            }
        } catch (Exception e) {
            terminal.error("helpfile: " + commandName + ".help not found at: " + fileLoc);
        }
        terminal.history.addLine(command.usage(), Color.GREEN);
    }

    @Override
    public int minArgLength() {
        return -1;
    }

    @Override
    public OptionList options() {
        return null;
    }

    public abstract String[] run(String[] args, int startIdx, Terminal terminal);

}
