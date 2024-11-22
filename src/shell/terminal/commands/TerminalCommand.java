package shell.terminal.commands;

import shell.render.color.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

import shell.render.text.HyperString;
import shell.terminal.Terminal;

public abstract class TerminalCommand {
    public void help(HyperString history) {
        String commandName = this.fullName();
        String fileLoc = "./src/shell/terminal/help/" + commandName + ".help";
        File f = new File(fileLoc);
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line = br.readLine();
            while (line != null) {
                history.addLine(line, Color.LIGHT_GRAY);
                line = br.readLine();
            }
            br.close();
        } catch (Exception e) {
            history.addLine("!TERMINAL ERROR: helpfile: " + commandName + ".help not found at: " + fileLoc, Color.RED);
        }
        history.addLine(this.usage(), Color.GREEN);
    }

    public abstract String usage();

    public abstract String fullName();

    public abstract String shortName();

    public abstract int argLength();

    public abstract String[] run(String[] args, int startIdx, Terminal terminal);
}
