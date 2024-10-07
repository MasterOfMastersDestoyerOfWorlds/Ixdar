package shell.render.text;

import shell.render.color.Color;
import shell.ui.Drawing;
import shell.ui.actions.Action;

public class Word {

    public CharSequence text;
    public Color color;
    public Action hoverAction;
    public Action clickAction;
    public boolean newLine = false;
    public float yOffset;
    public float xOffset;
    public float rowHeight;
    public float width;
    public Action clearHover;

    public Word(String word, Color c, Action hoverAction, Action clearHover, Action clickAction) {
        text = word;
        color = c;
        this.hoverAction = hoverAction;
        this.clearHover = clearHover;
        this.clickAction = clickAction;
    }

    public Word(Word w, Color defaultColor) {
        text = w.text;
        color = defaultColor;
    }

    public Word(boolean b) {

        newLine = b;
    }

    public void setBounds(float x, float y, float height) {
        this.width = Drawing.font.getWidth(text);
        this.xOffset = x;
        this.yOffset = y;
        this.rowHeight = height;
    }

    public void calculateHover(float normalizedPosX, float normalizedPosY) {
        if (normalizedPosX > xOffset && normalizedPosX < xOffset + width &&
                normalizedPosY > yOffset && normalizedPosY < yOffset + rowHeight) {
            hoverAction.perform();
        } else {
            clearHover.perform();
        }
    }

}
