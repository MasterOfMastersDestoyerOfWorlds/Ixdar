package ixdar.gui.ui.actions;

import ixdar.common.exceptions.TerminalParseException;
import ixdar.gui.terminal.commands.ChangeToolCommand;
import ixdar.gui.terminal.commands.LoadIxCommand;
import ixdar.gui.ui.tools.MapEditorTool;

/**
 * Menu action that opens the map editor: loads a blank ixdar file then activates
 * {@link MapEditorTool} in the main scene.
 */
public class LoadMapEditor implements Action {

    private String fileName = "";

    /**
     * Create the action; the loaded file name defaults to the empty string,
     * yielding a blank editor session.
     */
    public LoadMapEditor() {
    }

    /**
     * Load the bound file and switch the active tool to {@link MapEditorTool},
     * printing any terminal parse error to stdout.
     */
    @Override
    public void perform() {
        try {
            LoadIxCommand.run(fileName);
            ChangeToolCommand.run(MapEditorTool.class);
        } catch (TerminalParseException e) {
            System.out.println(e.message);
        }
    }
}
