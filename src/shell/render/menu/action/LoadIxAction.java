package shell.render.menu.action;

import shell.Main;
import shell.file.FileManagement;
import shell.render.Canvas3D;
import shell.render.menu.MenuBox;
import shell.render.menu.MenuItem.MenuAction;

public class LoadIxAction implements MenuAction {

    private String fileName;

    public LoadIxAction(String fileName) {
        this.fileName = fileName;
    }

    public void perform() {
        FileManagement.updateTestFileCache(fileName);
        Canvas3D.keys.active = false;
        MenuBox.menuVisible = false;
        Main.main(new String[] { fileName });

    }
}
