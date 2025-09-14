package shell.render.text;

import shell.render.color.Color;

public class ColorText {

    public static final ColorText BLANK = new ColorText(new String[] { "" }, new Color[] { Color.BLUE_WHITE });
    public String[] text;
    public Color[] color;

    public ColorText(String[] text, Color[] color) {
        this.text = text;
        this.color = color;
    }

    public ColorText(String text) {
        this.text = text.split(" ");
        this.color = new Color[] { Color.BLUE_WHITE };
    }
}