package shell.render.text;

import java.util.ArrayList;
import java.util.function.Supplier;

import shell.cameras.Bounds;
import shell.render.color.Color;
import shell.ui.Drawing;
import shell.ui.actions.Action;

public class HyperWord {

    public CharSequence charSequence;
    public ArrayList<HyperChar> text;
    public Color color;
    public Supplier<ColorText<?>> wordAction;
    public boolean isDynamic = false;
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
    public ArrayList<HyperWord> subWords;
    private Font font;
    public static final String WORD_BOUNDS_ID = "WORD";
    public boolean culled = false;

    public HyperWord(String word, Color c, Action hoverAction, Action clearHover, Action clickAction, Font font) {
        charSequence = word;
        this.font = font;
        text = toHyperChars(word);
        color = c;
        this.hoverAction = hoverAction;
        this.clearHover = clearHover;
        this.width = Drawing.getDrawing().font.getWidth(charSequence);
        if (clickAction != null) {
            this.clickAction = clickAction;
        } else {
            this.clickAction = () -> {
            };
        }
        viewBounds = new Bounds(0, 0, 0, 0, WORD_BOUNDS_ID);
    }

    private ArrayList<HyperChar> toHyperChars(String word) {
        ArrayList<HyperChar> list = new ArrayList<>();
        for(int codePoint: word.chars().toArray()){
            char c = (char)codePoint;
            list.add(new HyperChar(font, c));
        }
        return list;
    }

    public HyperWord(boolean b, Font font) {
        newLine = b;
        this.font = font;
        charSequence = "";
        text = toHyperChars("");
        this.width = 0;
        viewBounds = new Bounds(0, 0, 0, 0, WORD_BOUNDS_ID);
    }

    public HyperWord(Supplier<ColorText<?>> wordAction, Color c, Action hoverAction, Action clearHover,
            Action clickAction,
            Font font) {
        String val ="?MissingWord?";
        charSequence = val;
        this.font = font;
        text = toHyperChars(val);
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
        isDynamic = true;
    }

    public void setBounds(float x, float y, float xScreen, float yScreen, float height, Bounds viewBounds) {
        this.x = x;
        this.y = y;
        this.xScreenOffset = xScreen;
        this.yScreenOffset = yScreen;
        this.viewBounds.update(viewBounds);
        this.rowHeight = height;
    }

    public void setFont(Font font) {
        this.font = font;
        if (isDynamic) {
            this.width = font.getWidth(charSequence);
        }
    }

    public void setWidth(Font font) {
        this.width = font.getWidth(charSequence);
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
        return (String) charSequence;
    }

    public void click(float normalizedPosX, float normalizedPosY) {
        if (!newLine && normalizedPosX > xScreenOffset && normalizedPosX < xScreenOffset + width &&
                normalizedPosY > yScreenOffset && normalizedPosY < yScreenOffset + rowHeight
                && viewBounds.contains(normalizedPosX, normalizedPosY)) {
            clickAction.perform();
        }
    }

    public ArrayList<HyperWord> subWords() {
        ColorText<?> colorText = wordAction.get();
        if (colorText.dirty) {
            ArrayList<HyperWord> subWords = new ArrayList<>();
            int i = 0;
            for (String textPart : colorText.text) {
                subWords.add(
                        new HyperWord(textPart + " ", colorText.color.get(i), hoverAction, clearHover, clickAction,
                                font));
                i++;
                if (i >= colorText.color.size()) {
                    i = 0;
                }
            }
            colorText.subWords = subWords;
        }
        colorText.dirty = false;
        this.subWords = colorText.subWords;
        return subWords;
    }

}
