package ixdar.gui.ui.actions;

import ixdar.gui.terminal.TerminalParseException;
import ixdar.gui.terminal.commands.LoadIxCommand;
import ixdar.scenes.main.MainScene;
import ixdar.tools.knot.MapEditorTool;

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
            MainScene editor = LoadIxCommand.run(fileName);
            editor.switchTool(editor.mapEditorTool);
        } catch (TerminalParseException e) {
            System.out.println(e.message);
        }
    }
}
