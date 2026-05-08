package ixdar.gui.ui.actions;

import ixdar.common.exceptions.TerminalParseException;
import ixdar.gui.terminal.commands.LoadIxCommand;

public class LoadIxAction implements Action {

    private String fileName;

    /**
     * TODO: document {@code LoadIxAction}.
     *
     * @param fileName TODO: describe
     */
    public LoadIxAction(String fileName) {
        this.fileName = fileName;
    }

    /**
     * TODO: document {@code perform}.
     */
    @Override
    public void perform() {
        try {
            LoadIxCommand.run(fileName);
        } catch (TerminalParseException e) {
            System.out.println(e.message);
        }
    }
}
