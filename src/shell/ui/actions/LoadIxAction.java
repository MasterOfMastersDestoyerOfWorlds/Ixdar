package shell.ui.actions;

import shell.exceptions.TerminalParseException;
import shell.terminal.commands.LoadIxCommand;

public class LoadIxAction implements Action {

    private String fileName;

    public LoadIxAction(String fileName) {
        this.fileName = fileName;
    }

    @Override
    public void perform() {
        try {
            LoadIxCommand.run(fileName);
        } catch (TerminalParseException e) {
            System.out.println(e.message);
        }
    }
}
