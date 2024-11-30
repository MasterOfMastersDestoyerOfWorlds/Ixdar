package shell.terminal.commands;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

import shell.render.color.Color;
import shell.terminal.Terminal;
import shell.terminal.TerminalOption;

public abstract class TerminalCommand implements TerminalOption {

    public void help(Terminal terminal) {
        String commandName = this.fullName();
        String fileLoc = "./src/shell/terminal/help/" + commandName + ".help";
        File f = new File(fileLoc);
        terminal.history.addWord(this.fullName(), Color.COMMAND);
        terminal.history.addLine(this.desc(), Color.GREEN);
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line = br.readLine();
            while (line != null) {
                terminal.history.addLine(line, Color.LIGHT_GRAY);
                line = br.readLine();
            }
            br.close();
        } catch (Exception e) {
            terminal.error("helpfile: " + commandName + ".help not found at: " + fileLoc);
        }
        terminal.history.addLine(this.usage(), Color.GREEN);
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
