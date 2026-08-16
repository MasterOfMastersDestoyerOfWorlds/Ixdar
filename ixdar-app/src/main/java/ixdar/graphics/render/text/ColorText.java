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
     * Build a colored phrase by splitting {@code text} on spaces; every word gets
     * the same color. {@code data} is an opaque payload tied to this phrase.
     *
     * @param text  whitespace-separated string
     * @param color color applied to every word
     * @param data  caller-defined payload
     */
    public ColorText(String text, Color color, T data) {
        this.text = new ArrayList<>();
        for (String s : text.split(" ")) {
            this.text.add(s);
        }
        this.color = new ArrayList<>();
        for (int i = 0; i < this.text.size(); i++) {
            this.color.add(color);
        }
        this.data = data;
    }

    /**
     * Copy constructor with a replaced data payload (text and colors are
     * shallow-copied into fresh lists).
     *
     * @param text source phrase
     * @param data new payload
     */
    public ColorText(ColorText<T> text, T data) {
        this.text = new ArrayList<>(text.text);
        this.color = new ArrayList<>(text.color);
        this.data = data;
    }

    /**
     * Build a colored phrase with no payload.
     *
     * @param text  whitespace-separated string
     * @param color color applied to every word
     */
    public ColorText(String text, Color color) {
        this(text, color, null);
    }

    /**
     * Build a phrase coloured with the default scalar palette and a payload.
     *
     * @param scalarString whitespace-separated string
     * @param value        caller-defined payload
     */
    public ColorText(String scalarString, T value) {
        this(scalarString, Color.BLUE_WHITE, value);
    }

    /**
     * Build a phrase coloured with the default scalar palette and no payload.
     *
     * @param scalarString whitespace-separated string
     */
    public ColorText(String scalarString) {
        this(scalarString, Color.BLUE_WHITE, null);
    }

    /**
     * Build an empty phrase. Use {@link #addWord(String, Color)} or
     * {@link #join(ColorText)} to populate.
     */
    public ColorText() {
    }

    /**
     * Set the payload tied to this phrase.
     *
     * @param data caller-defined payload
     */
    public void setData(T data) {
        this.data = data;
    }

    /**
     * The payload tied to this phrase.
     *
     * @return caller-defined payload (may be {@code null})
     */
    public T getData() {
        return data;
    }

    /**
     * Concatenate this phrase with {@code v} into a new phrase; the result's
     * payload comes from {@code v}.
     *
     * @param v phrase to append
     * @return new phrase containing this followed by {@code v}
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
     * Clear all words and per-word colors.
     */
    public void resetText() {
        text = new ArrayList<>();
        color = new ArrayList<>();
    }

    /**
     * Append a single word with its own color.
     *
     * @param word      word text
     * @param wordColor color for this word
     */
    public void addWord(String word, Color wordColor) {
        text.add(word);
        color.add(wordColor);
    }
}