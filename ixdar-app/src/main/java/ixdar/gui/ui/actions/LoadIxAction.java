package ixdar.gui.ui.actions;

import ixdar.common.exceptions.TerminalParseException;
import ixdar.gui.terminal.commands.LoadIxCommand;

/**
 * Menu action that loads an ixdar (.ix) point/knot file into the current scene by
 * delegating to {@link LoadIxCommand}.
 */
public class LoadIxAction implements Action {

    private String fileName;

    /**
     * Bind this action to a specific .ix file to load.
     *
     * @param fileName path of the .ix file to load when invoked
     */
    public LoadIxAction(String fileName) {
        this.fileName = fileName;
    }

    /**
     * Run the load command, printing any terminal parse error to stdout.
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
