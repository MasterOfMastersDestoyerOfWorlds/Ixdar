package shell.ui.actions;

import shell.Main;
import shell.file.FileManagement;
import shell.ui.Canvas3D;
import shell.ui.menu.MenuBox;

public class LoadIxAction implements Action {

    private String fileName;

    public LoadIxAction(String fileName) {
        this.fileName = fileName;
    }

    @Override
    public void perform() {
        FileManagement.updateTestFileCache(fileName);
        Canvas3D.keys.active = false;
        Canvas3D.mouse.active = false;
        MenuBox.menuVisible = false;
        Main.main(new String[] { fileName });

    }
}
