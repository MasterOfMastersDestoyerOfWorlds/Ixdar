package ixdar.gui.ui.menu;

import java.io.File;
import java.util.ArrayList;

import ixdar.gui.ui.actions.ChangeScreenAction;
import ixdar.gui.ui.actions.LoadIxAction;
import ixdar.gui.ui.actions.LoadMapEditor;
import ixdar.platform.file.FileManagement;

import ixdar.platform.Platforms;

/**
 * A menu screen: a list of {@link MenuItem}s plus a back-navigation hook.
 * Implementations include the debug {@link MainMenu}, the file-browsing
 * {@link LoadMenu}, and the in-game {@link GameMenu}.
 */
public interface Menu {

    /**
     * Get the items to display in this menu.
     *
     * @return the menu rows in top-to-bottom display order
     */
    public ArrayList<MenuItem> loadMenu();

    /**
     * Handle the back navigation gesture (Escape / back button) for this
     * screen, e.g. return to a parent menu or exit the app.
     */
    public void back();

    /**
     * Top-level debug-mode main menu: Continue, Load, Settings, Map Editor.
     */
    public class MainMenu implements Menu {
        public ArrayList<MenuItem> menuItems;

        String cachedFileString;

        MainMenu(String name) {
            this.cachedFileString = name;
            initMenu();
        }

        /**
         * Populate {@link #menuItems} with the four main-menu rows.
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
         * Return the cached list of main-menu items.
         *
         * @return the menu's items in display order
         */
        @Override
        public ArrayList<MenuItem> loadMenu() {
            return menuItems;
        }

        /**
         * Back action: terminate the application via the platform exit hook.
         */
        @Override
        public void back() {
            Platforms.get().exit(0);
        }

    }

    /**
     * File-browser menu showing the contents of a folder. Subdirectories chain
     * into another {@link LoadMenu}; files become {@link LoadIxAction} items.
     */
    public class LoadMenu implements Menu {
        public String folder;
        public Menu parent;
        public ArrayList<MenuItem> menuItems;

        /**
         * Build a load menu rooted at the given folder.
         *
         * @param folder absolute or relative path to list
         * @param parentMenu menu to return to from {@link #back()}
         */
        public LoadMenu(String folder, Menu parentMenu) {
            this.folder = folder;
            this.parent = parentMenu;
        }

        /**
         * Lazily list the folder, building one menu item per child entry.
         * Subdirectories produce nested {@link LoadMenu} screens; files
         * produce {@link LoadIxAction} items.
         *
         * @return the menu's items in display order
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
         * Back action: switch the active {@link MenuBox} screen to the parent
         * menu passed at construction.
         */
        @Override
        public void back() {
            MenuBox.load(parent);
        }

    }
}
