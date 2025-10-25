package ixdar.gui.ui.actions;

import ixdar.common.exceptions.TerminalParseException;
import ixdar.gui.terminal.commands.LoadIxCommand;

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
