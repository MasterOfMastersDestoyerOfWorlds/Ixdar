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
    public float yScreenOffset;
    public float xScreenOffset;
    public float rowHeight;
    public float width;
    public Action clearHover;
    public float x;
    public float y;

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

    public void setBounds(float x, float y, float xScreen, float yScreen, float height) {
        this.width = Drawing.font.getWidth(text);
        this.x = x;
        this.y = y;
        this.xScreenOffset = xScreen;
        this.yScreenOffset = yScreen;
        this.rowHeight = height;
    }

    
    public void setWidth(Font font) {
        this.width = font.getWidth(text);
    }

    public void calculateClearHover(float normalizedPosX, float normalizedPosY) {
        if (!newLine && !(normalizedPosX > xScreenOffset && normalizedPosX < xScreenOffset + width &&
                normalizedPosY > yScreenOffset && normalizedPosY < yScreenOffset + rowHeight)) {
            clearHover.perform();
        }
    }

    public void calculateHover(float normalizedPosX, float normalizedPosY) {
        if (!newLine && normalizedPosX > xScreenOffset && normalizedPosX < xScreenOffset + width &&
                normalizedPosY > yScreenOffset && normalizedPosY < yScreenOffset + rowHeight) {
            hoverAction.perform();
        }
    }

    @Override
    public String toString() {
        return (String) text;
    }

}
