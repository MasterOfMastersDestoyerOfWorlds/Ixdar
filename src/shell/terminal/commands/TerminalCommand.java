package shell.terminal.commands;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

import shell.render.color.Color;
import shell.terminal.Terminal;

public abstract class TerminalCommand {

    public void help(Terminal terminal) {
        String commandName = this.fullName();
        String fileLoc = "./src/shell/terminal/help/" + commandName + ".help";
        File f = new File(fileLoc);
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

    public abstract String usage();

    public abstract String desc();

    public abstract String fullName();

    public abstract String shortName();

    public abstract int argLength();

    public abstract String[] run(String[] args, int startIdx, Terminal terminal);
}
