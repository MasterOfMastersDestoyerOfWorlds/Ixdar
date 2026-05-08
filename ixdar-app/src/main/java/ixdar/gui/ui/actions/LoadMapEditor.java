package ixdar.gui.ui.actions;

import ixdar.common.exceptions.TerminalParseException;
import ixdar.gui.terminal.commands.ChangeToolCommand;
import ixdar.gui.terminal.commands.LoadIxCommand;
import ixdar.gui.ui.tools.MapEditorTool;

public class LoadMapEditor implements Action {

    private String fileName = "";

    /**
     * TODO: document {@code LoadMapEditor}.
     */
    public LoadMapEditor() {
    }

    /**
     * TODO: document {@code perform}.
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
