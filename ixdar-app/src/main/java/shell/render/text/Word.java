package shell.render.text;

import java.util.ArrayList;
import java.util.function.Supplier;

import shell.cameras.Bounds;
import shell.render.color.Color;
import shell.ui.Drawing;
import shell.ui.actions.Action;

public class Word {

    public CharSequence text;
    public Color color;
    public Supplier<ColorText<?>> wordAction;
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
    public Bounds viewBounds;
    public ArrayList<Word> subWords;
    public static final String WORD_BOUNDS_ID = "WORD";

    public Word(String word, Color c, Action hoverAction, Action clearHover, Action clickAction) {
        text = word;
        color = c;
        this.hoverAction = hoverAction;
        this.clearHover = clearHover;
        if (clickAction != null) {
            this.clickAction = clickAction;
        } else {
            this.clickAction = () -> {
            };
        }
        viewBounds = new Bounds(0, 0, 0, 0, WORD_BOUNDS_ID);
    }

    public Word(boolean b) {
        newLine = b;
        text = "";
        viewBounds = new Bounds(0, 0, 0, 0, WORD_BOUNDS_ID);
    }

    public Word(Supplier<ColorText<?>> wordAction, Color c, Action hoverAction, Action clearHover, Action clickAction) {
        text = "?MissingWord?";
        color = c;
        this.wordAction = wordAction;
        this.hoverAction = hoverAction;
        this.clearHover = clearHover;
        if (clickAction != null) {
            this.clickAction = clickAction;
        } else {
            this.clickAction = () -> {
            };
        }
        viewBounds = new Bounds(0, 0, 0, 0, WORD_BOUNDS_ID);
    }

    public void setBounds(float x, float y, float xScreen, float yScreen, float height, Bounds viewBounds) {
        this.width = Drawing.font.getWidth(text);
        this.x = x;
        this.y = y;
        this.xScreenOffset = xScreen;
        this.yScreenOffset = yScreen;
        this.viewBounds.update(viewBounds);
        this.rowHeight = height;
    }

    public void setWidth(Font font) {
        if (this.newLine) {
            this.width = 0;
        } else {
            this.width = font.getWidth(text);
        }
    }

    public void setZeroWidth() {
        this.width = 0;
    }

    public void calculateClearHover(float normalizedPosX, float normalizedPosY) {
        if (!newLine && !(normalizedPosX > xScreenOffset && normalizedPosX < xScreenOffset + width &&
                normalizedPosY > yScreenOffset && normalizedPosY < yScreenOffset + rowHeight)) {
            clearHover.perform();
        }
    }

    public void calculateHover(float normalizedPosX, float normalizedPosY) {
        if (!newLine && normalizedPosX > xScreenOffset && normalizedPosX < xScreenOffset + width &&
                normalizedPosY > yScreenOffset && normalizedPosY < yScreenOffset + rowHeight
                && viewBounds.contains(normalizedPosX, normalizedPosY)) {
            hoverAction.perform();
        }
    }

    @Override
    public String toString() {
        return (String) text;
    }

    public void click(float normalizedPosX, float normalizedPosY) {
        if (!newLine && normalizedPosX > xScreenOffset && normalizedPosX < xScreenOffset + width &&
                normalizedPosY > yScreenOffset && normalizedPosY < yScreenOffset + rowHeight
                && viewBounds.contains(normalizedPosX, normalizedPosY)) {
            clickAction.perform();
        }
    }

    public ArrayList<Word> subWords() {
        ColorText<?> colorText = wordAction.get();
        ArrayList<Word> subWords = new ArrayList<>();
        int i = 0;
        for (String textPart : colorText.text) {
            subWords.add(new Word(textPart + " ", colorText.color.get(i), hoverAction, clearHover, clickAction));
            i++;
            if (i >= colorText.color.size()) {
                i = 0;
            }
        }
        this.subWords = subWords;
        return subWords;
    }

}
