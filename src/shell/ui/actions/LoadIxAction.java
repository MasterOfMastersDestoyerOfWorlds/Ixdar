package shell.ui.actions;

import shell.terminal.commands.LoadIxCommand;

public class LoadIxAction implements Action {

    private String fileName;

    public LoadIxAction(String fileName) {
        this.fileName = fileName;
    }

    @Override
    public void perform() {
        LoadIxCommand.run(fileName);
    }
}
