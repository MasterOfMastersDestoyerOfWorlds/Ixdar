package shell.ui.actions;

import shell.file.FileManagement;
import shell.ui.Canvas3D;
import shell.ui.main.Main;

public class LoadIxAction implements Action {

    private String fileName;

    public LoadIxAction(String fileName) {
        this.fileName = fileName;
    }

    @Override
    public void perform() {
        FileManagement.updateTestFileCache(fileName);
        Canvas3D.activate(false);
        Main.main(new String[] { fileName });
        Main.activate(true);

    }
}
