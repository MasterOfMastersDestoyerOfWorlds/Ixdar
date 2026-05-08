package ixdar.gui.ui.menu;

import java.io.File;
import java.util.ArrayList;

import ixdar.gui.ui.actions.ChangeScreenAction;
import ixdar.gui.ui.actions.LoadIxAction;
import ixdar.gui.ui.actions.LoadMapEditor;
import ixdar.platform.file.FileManagement;

public interface Menu {

    /**
     * TODO: document {@code loadMenu}.
     *
     * @return TODO: describe
     */
    public ArrayList<MenuItem> loadMenu();

    /**
     * TODO: document {@code back}.
     */
    public void back();

    public class MainMenu implements Menu {
        public ArrayList<MenuItem> menuItems;

        String cachedFileString;

        MainMenu(String name) {
            this.cachedFileString = name;
            initMenu();
        }

        /**
         * TODO: document {@code initMenu}.
         */
        public void initMenu() {

            menuItems = new ArrayList<>();
            menuItems.add(new MenuItem("Continue", new LoadIxAction(cachedFileString)));
            menuItems.add(new MenuItem("Load",
                    new ChangeScreenAction(new LoadMenu(FileManagement.solutionsFolder, this))));
            menuItems.add(new MenuItem("Settings", null));
            menuItems.add(new MenuItem("Map Editor", new LoadMapEditor()));
        }

        /**
         * TODO: document {@code loadMenu}.
         *
         * @return TODO: describe
         */
        @Override
        public ArrayList<MenuItem> loadMenu() {
            return menuItems;
        }

        /**
         * TODO: document {@code back}.
         */
        @Override
        public void back() {
            ixdar.platform.Platforms.get().exit(0);
        }

    }

    public class LoadMenu implements Menu {
        public String folder;
        public Menu parent;
        public ArrayList<MenuItem> menuItems;

        /**
         * TODO: document {@code LoadMenu}.
         *
         * @param folder TODO: describe
         * @param parentMenu TODO: describe
         */
        public LoadMenu(String folder, Menu parentMenu) {
            this.folder = folder;
            this.parent = parentMenu;
        }

        /**
         * TODO: document {@code loadMenu}.
         *
         * @return TODO: describe
         */
        @Override
        public ArrayList<MenuItem> loadMenu() {
            if (menuItems == null) {
                File solutions = new File(folder);

                menuItems = new ArrayList<>();
                File[] items = solutions.listFiles();
                for (int i = 0; i < items.length; i++) {
                    File f = items[i];
                    if (f.isDirectory()) {
                        String dir = f.getName();
                        menuItems.add(new MenuItem(dir,
                                new ChangeScreenAction(
                                        new LoadMenu(folder + "/" + dir,
                                                this))));
                    } else {
                        String dir = f.getName();
                        menuItems.add(new MenuItem(dir, new LoadIxAction(dir)));
                    }
                }
            }
            return menuItems;
        }

        /**
         * TODO: document {@code back}.
         */
        @Override
        public void back() {
            MenuBox.load(parent);
        }

    }
}
