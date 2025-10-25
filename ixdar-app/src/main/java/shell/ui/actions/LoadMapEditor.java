package shell.ui.actions;

import shell.exceptions.TerminalParseException;
import shell.terminal.commands.ChangeToolCommand;
import shell.terminal.commands.LoadIxCommand;
import shell.ui.tools.MapEditorTool;

public class LoadMapEditor implements Action {

    private String fileName = "";

    public LoadMapEditor() {
    }

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
