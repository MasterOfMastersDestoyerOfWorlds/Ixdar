package shell.render.text;

import java.util.ArrayList;

import shell.render.color.Color;

public class ColorText<T> {
    public static final ColorText<Float> BLANK = new ColorText<Float>("", Color.BLUE_WHITE);
    public ArrayList<String> text;
    public ArrayList<Color> color;
    public T data;

    public ColorText(String text, Color color, T data) {
        this.text = new ArrayList<>();
        for (String s : text.split(" ")) {
            this.text.add(s);
        }
        this.color = new ArrayList<>();
        for (String s : this.text) {
            this.color.add(color);
        }
        this.data = data;
    }

    public ColorText(ColorText<T> text, T data) {
        this.text = new ArrayList<>(text.text);
        this.color = new ArrayList<>(text.color);
        this.data = data;
    }

    public ColorText(String text, Color color) {
        this(text, color, null);
    }

    public ColorText(String scalarString, T value) {
        this(scalarString, Color.BLUE_WHITE, value);
    }

    public ColorText(String scalarString) {
        this(scalarString, Color.BLUE_WHITE, null);
    }

    public void setData(T data) {
        this.data = data;
    }

    public T getData() {
        return data;
    }

    private ColorText() {
    }

    public ColorText<T> join(ColorText<T> v) {
        ColorText<T> result = new ColorText<>();
        result.text = new ArrayList<>(this.text);
        result.color = new ArrayList<>(this.color);
        result.data = this.data;
        result.text.addAll(v.text);
        result.color.addAll(v.color);
        result.data = v.data;
        return result;
    }
}