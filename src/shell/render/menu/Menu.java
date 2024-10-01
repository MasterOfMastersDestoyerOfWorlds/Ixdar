package shell.render.menu;

import java.util.ArrayList;
import java.io.File;

import shell.file.FileManagement;
import shell.render.menu.action.ChangeScreenAction;
import shell.render.menu.action.LoadIxAction;

public interface Menu {

        public ArrayList<MenuItem> loadMenu();

        public void back();

        public class MainMenu implements Menu {

                String cachedFileString;
                public ArrayList<MenuItem> menuItems;

                MainMenu(String name) {
                        this.cachedFileString = name;
                        initMenu();
                }

                public void initMenu() {

                        menuItems = new ArrayList<>();
                        menuItems.add(new MenuItem("Continue", new LoadIxAction(cachedFileString)));
                        menuItems.add(new MenuItem("Load",
                                        new ChangeScreenAction(new LoadMenu(FileManagement.solutionsFolder, this))));
                        menuItems.add(new MenuItem("Puzzle", null));
                        menuItems.add(new MenuItem("Map Ecditor", null));
                }

                @Override
                public ArrayList<MenuItem> loadMenu() {
                        return menuItems;
                }

                @Override
                public void back() {
                        System.exit(0);
                }

        }

        public class LoadMenu implements Menu {
                public String folder;
                public Menu parent;
                public ArrayList<MenuItem> menuItems;

                public LoadMenu(String folder, Menu parentMenu) {
                        this.folder = folder;
                        this.parent = parentMenu;
                }

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
                                                                                new LoadMenu(folder + "/" + dir, this))));
                                        } else {
                                                String dir = f.getName();
                                                menuItems.add(new MenuItem(dir, new LoadIxAction(dir)));
                                        }
                                }
                        }
                        return menuItems;
                }

                @Override
                public void back() {
                        MenuBox.load(parent);
                }

        }
}
