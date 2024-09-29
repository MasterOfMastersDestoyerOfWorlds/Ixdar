package shell.render.menu;

import java.util.ArrayList;

import shell.render.Canvas3D;
import shell.render.Color;
import shell.render.color.ColorBox;
import shell.render.sdf.SDFUnion;
import shell.render.text.Font;

public class Menu {
    SDFUnion menuOuterBorder;
    Font font;
    ArrayList<String> menuItems;
    int hoverItem = -1;
    float scale = 3f;
    float hoverX;
    float hoverY;
    float alpha;
    Color outerColor;
    Color outerFlash;
    Color innerColor;
    ColorBox boundingBox;

    public Menu() {
        alpha = 0.7f;

        innerColor = new Color(Color.NAVY, alpha);
        outerColor = new Color(Color.BLUE_WHITE, alpha);
        outerFlash = new Color(Color.RED);
        menuOuterBorder = new SDFUnion("menu_inner.png", Color.NAVY, 0.95f, 0, -0.02f, "menu_outer.png",
                Color.BLUE_WHITE, alpha, 5, 2);
        boundingBox = new ColorBox();
        menuItems = new ArrayList<>();
        menuItems.add("Continue");
        menuItems.add("Load");
        menuItems.add("Puzzle");
        menuItems.add("Map Editor");
        font = new Font();
    }

    public void draw(float zIndex) {
        float itemHeight = menuOuterBorder.outerTexture.height * scale / 2;
        float itemWidth = menuOuterBorder.outerTexture.width * scale * 0.91f;
        for (int i = 0; i < menuItems.size(); i++) {
            float centerX = Canvas3D.frameBufferWidth / 2;
            float centerY = Canvas3D.frameBufferHeight / 2 - (itemHeight * i * 1.5f);
            float leftBoundX = centerX - itemWidth / 2;
            float rightBoundX = centerX + itemWidth / 2;
            float upBoundX = centerY + itemHeight / 2;
            float downBoundX = centerY - itemHeight / 2;
            if (hoverX > leftBoundX && hoverX < rightBoundX && hoverY > downBoundX && hoverY < upBoundX) {

                menuOuterBorder.drawCentered(centerX, centerY, scale, zIndex, innerColor, outerFlash);
                // boundingBox.drawCoords(leftBoundX, downBoundX, rightBoundX, upBoundX, zIndex + 0.1f,
                //         new Color(Color.YELLOW, 0.5f));
            } else {
                menuOuterBorder.drawCentered(centerX, centerY, scale, zIndex);

            }
            font.drawTextCentered(menuItems.get(i), centerX, centerY + itemHeight * 0.075f, zIndex + 0.5f,
                    itemHeight / 2,
                    Color.BLUE_WHITE);
        }
    }

    public void setHover(float x, float y) {
        hoverX = x;
        hoverY = y;
    }

}
