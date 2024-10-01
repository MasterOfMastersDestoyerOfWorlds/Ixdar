package shell.render.menu.action;

import shell.Main;
import shell.file.FileManagement;
import shell.render.menu.MenuItem.MenuAction;

public class LoadIxAction implements MenuAction {

    private String fileName;

    public LoadIxAction(String fileName) {
        this.fileName = fileName;
    }

    public void perform() {
        FileManagement.updateTestFileCache(fileName);
        Main.main(new String[] { fileName });
    }
}
