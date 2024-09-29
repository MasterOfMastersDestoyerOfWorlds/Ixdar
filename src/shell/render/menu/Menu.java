package shell.render.menu;

import java.util.ArrayList;

import shell.render.Canvas3D;
import shell.render.Color;
import shell.render.sdf.SDFUnion;
import shell.render.text.Font;

public class Menu {
    SDFUnion menuOuterBorder;
    Font font;
    ArrayList<String> menuItems;

    public Menu() {
        menuOuterBorder = new SDFUnion("menu_inner.png", Color.NAVY, 0.95f, 0, -0.02f, "menu_outer.png",
                Color.BLUE_WHITE, 0.7f, 5, 2);
        menuItems = new ArrayList<>();
        menuItems.add("Continue");
        menuItems.add("Load");
        menuItems.add("Puzzle");
        menuItems.add("Map Editor");
        font = new Font();
    }

    public void draw(float zIndex) {
        float scale = 3f;
        float itemHeight = menuOuterBorder.outerTexture.height * scale / 2;
        for (int i = 0; i < menuItems.size(); i++) {
            float centerX = Canvas3D.frameBufferWidth / 2;
            float centerY = Canvas3D.frameBufferHeight / 2 - (itemHeight * i * 1.5f);

            menuOuterBorder.drawCentered(centerX, centerY, scale, zIndex, new Color(Color.NAVY, 0.8f));
            font.drawTextCentered(menuItems.get(i), centerX, centerY + itemHeight * 0.075f, zIndex + 0.5f,
                    itemHeight / 2,
                    Color.BLUE_WHITE);
        }
    }

}
