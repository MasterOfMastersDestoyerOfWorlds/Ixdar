package ixdar.graphics.render.text;

import java.util.ArrayList;

import ixdar.graphics.render.color.Color;

public class ColorText<T> {
    public ArrayList<String> text;
    public ArrayList<Color> color;
    public T data;
    public boolean dirty = true;
    public ArrayList<HyperWord> subWords;

    /**
     * TODO: document {@code ColorText}.
     *
     * @param text TODO: describe
     * @param color TODO: describe
     * @param data TODO: describe
     */
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

    /**
     * TODO: document {@code ColorText}.
     *
     * @param text TODO: describe
     * @param data TODO: describe
     */
    public ColorText(ColorText<T> text, T data) {
        this.text = new ArrayList<>(text.text);
        this.color = new ArrayList<>(text.color);
        this.data = data;
    }

    /**
     * TODO: document {@code ColorText}.
     *
     * @param text TODO: describe
     * @param color TODO: describe
     */
    public ColorText(String text, Color color) {
        this(text, color, null);
    }

    /**
     * TODO: document {@code ColorText}.
     *
     * @param scalarString TODO: describe
     * @param value TODO: describe
     */
    public ColorText(String scalarString, T value) {
        this(scalarString, Color.BLUE_WHITE, value);
    }

    /**
     * TODO: document {@code ColorText}.
     *
     * @param scalarString TODO: describe
     */
    public ColorText(String scalarString) {
        this(scalarString, Color.BLUE_WHITE, null);
    }

    /**
     * TODO: document {@code ColorText}.
     */
    public ColorText() {
    }

    /**
     * TODO: document {@code setData}.
     *
     * @param data TODO: describe
     */
    public void setData(T data) {
        this.data = data;
    }

    /**
     * TODO: document {@code getData}.
     *
     * @return TODO: describe
     */
    public T getData() {
        return data;
    }

    /**
     * TODO: document {@code join}.
     *
     * @param v TODO: describe
     * @return TODO: describe
     */
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

    /**
     * TODO: document {@code resetText}.
     */
    public void resetText() {
        text = new ArrayList<>();
        color = new ArrayList<>();
    }

    /**
     * TODO: document {@code addWord}.
     *
     * @param word TODO: describe
     * @param wordColor TODO: describe
     */
    public void addWord(String word, Color wordColor) {
        text.add(word);
        color.add(wordColor);
    }
}