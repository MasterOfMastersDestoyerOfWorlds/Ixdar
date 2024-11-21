package shell.terminal.commands;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;

public abstract class TerminalCommand {
    public void help(ArrayList<String> history) {
        String commandName = this.fullName();
        String fileLoc = "./src/shell/terminal/help/" + commandName + ".help";
        File f = new File(fileLoc);
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line = br.readLine();
            while (line != null) {
                history.add(line);
                line = br.readLine();
            }
            br.close();
        } catch (Exception e) {
            history.add("!TERMINAL ERROR: helpfile: " + commandName + ".help not found at: " + fileLoc);
        }
        history.add(this.usage());
    }

    public abstract String usage();

    public abstract String fullName();

    public abstract String shortName();

    public abstract int argLength();

    public abstract String run(String[] args, int startIdx, ArrayList<String> history);
}
