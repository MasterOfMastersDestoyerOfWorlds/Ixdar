package ixdar.graphics.render.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Supplier;

import ixdar.geometry.knot.Knot;
import ixdar.geometry.knot.Segment;
import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.actions.Action;
import ixdar.platform.input.MouseTrap;
import ixdar.scenes.main.MainScene;

public class HyperString {
    public static final int NUM_30 = 30;

    public ArrayList<HyperWord> words;
    public HashMap<Integer, String> strMap;
    public ArrayList<Integer> lineStartMap;
    public ArrayList<HyperString> children;
    public Color defaultColor = Color.IXDAR;
    public int lines = 1;
    public boolean debug;
    public boolean wrap;
    public int charWrap = Integer.MAX_VALUE;
    public Bounds bounds;
    public Object data;
    private int wrappedLines;
    private Font font;

    /**
     * TODO: document {@code HyperString}.
     */
    public HyperString() {
        words = new ArrayList<>();
        strMap = new HashMap<>();
        lineStartMap = new ArrayList<>();
        children = new ArrayList<>();
        lineStartMap.add(0);
        strMap.put(0, "");
        font = Drawing.getDrawing().font;
    }

    /**
     * TODO: document {@code setFont}.
     *
     * @param font TODO: describe
     */
    public void setFont(Font font) {
        this.font = font;
        for (HyperWord w : words) {
            w.setFont(font);
        }
    }

    /**
     * TODO: document {@code addWord}.
     *
     * @param word TODO: describe
     */
    public void addWord(String word) {
        for (String w : word.split(" ")) {
            addWord(w + " ", defaultColor, () -> {
            }, () -> {
            }, () -> {
            });
        }
    }

    /**
     * TODO: document {@code addWord}.
     *
     * @param word TODO: describe
     * @param c TODO: describe
     */
    public void addWord(String word, Color c) {
        for (String w : word.split(" ")) {
            addWord(w + " ", c, () -> {
            }, () -> {
            }, () -> {
            });
        }
    }

    /**
     * TODO: document {@code addWordClick}.
     *
     * @param word TODO: describe
     * @param c TODO: describe
     * @param clickAction TODO: describe
     */
    public void addWordClick(String word, Color c, Action clickAction) {
        for (String w : word.split(" ")) {
            addWord(w + " ", c, () -> {
            }, () -> {
            }, clickAction);
        }
    }

    /**
     * TODO: document {@code addDynamicWordClick}.
     *
     * @param wordAction TODO: describe
     * @param c TODO: describe
     * @param clickAction TODO: describe
     */
    public void addDynamicWordClick(Supplier<ColorText<?>> wordAction, Color c, Action clickAction) {
        words.add(new HyperWord(wordAction, c, () -> {
        }, () -> {
        }, clickAction, font));
    }

    /**
     * TODO: document {@code addWord}.
     *
     * @param word TODO: describe
     * @param c TODO: describe
     * @param hoverAction TODO: describe
     * @param clearHover TODO: describe
     * @param clickAction TODO: describe
     */
    public void addWord(String word, Color c, Action hoverAction, Action clearHover, Action clickAction) {
        for (String w : word.split(" ")) {
            strMap.computeIfPresent(lines - 1, (key, val) -> val + w + " ");
            words.add(new HyperWord(w + " ", c, hoverAction, clearHover, clickAction, font));
        }
    }

    /**
     * TODO: document {@code addDynamicWord}.
     *
     * @param wordAction TODO: describe
     */
    public void addDynamicWord(Supplier<ColorText<?>> wordAction) {
        words.add(new HyperWord(wordAction, defaultColor, () -> {
        }, () -> {
        }, () -> {
        }, font));
    }

    /**
     * TODO: document {@code addDynamicWord}.
     *
     * @param wordAction TODO: describe
     * @param c TODO: describe
     */
    public void addDynamicWord(Supplier<ColorText<?>> wordAction, Color c) {
        words.add(new HyperWord(wordAction, c, () -> {
        }, () -> {
        }, () -> {
        }, font));
    }

    /**
     * TODO: document {@code addDynamicWord}.
     *
     * @param wordAction TODO: describe
     * @param c TODO: describe
     * @param hoverAction TODO: describe
     * @param clearHover TODO: describe
     * @param clickAction TODO: describe
     */
    public void addDynamicWord(Supplier<ColorText<?>> wordAction, Color c, Action hoverAction, Action clearHover,
            Action clickAction) {
        words.add(new HyperWord(wordAction, c, hoverAction, clearHover, clickAction, font));
    }

    /**
     * TODO: document {@code addLine}.
     *
     * @param word TODO: describe
     * @param c TODO: describe
     */
    public void addLine(String word, Color c) {
        addWord(word, c);
        this.newLine();
    }

    /**
     * TODO: document {@code addLine}.
     *
     * @param word TODO: describe
     */
    public void addLine(String word) {
        addWord(word);
        this.newLine();
    }

    /**
     * TODO: document {@code addTooltip}.
     *
     * @param word TODO: describe
     * @param c TODO: describe
     * @param toolTipText TODO: describe
     * @param clickAction TODO: describe
     */
    public void addTooltip(String word, Color c, HyperString toolTipText, Action clickAction) {
        for (String w : word.split(" ")) {
            words.add(new HyperWord(w + " ", c, () -> MainScene.setTooltipText(toolTipText), () -> MainScene.clearTooltipText(),
                    clickAction, font));
        }
    }

    /**
     * TODO: document {@code addDynamicTooltip}.
     *
     * @param wordAction TODO: describe
     * @param c TODO: describe
     * @param toolTipText TODO: describe
     * @param clickAction TODO: describe
     */
    public void addDynamicTooltip(Supplier<ColorText<?>> wordAction, Color c, HyperString toolTipText,
            Action clickAction) {
        words.add(new HyperWord(wordAction, c, () -> MainScene.setTooltipText(toolTipText), () -> MainScene.clearTooltipText(),
                clickAction, font));
    }

    /**
     * TODO: document {@code addHoverKnot}.
     *
     * @param word TODO: describe
     * @param c TODO: describe
     * @param hoverKnot TODO: describe
     * @param clickAction TODO: describe
     */
    public void addHoverKnot(String word, Color c, Knot hoverKnot, Action clickAction) {
        HyperString knotText = new HyperString();
        children.add(knotText);
        knotText.addWord(hoverKnot.toString() + " FlatID: " + hoverKnot.id, c);
        knotText.setWrap(true, NUM_30);
        words.add(new HyperWord(word, c, () -> {
            MainScene.setHoverKnot(hoverKnot);
            MainScene.setTooltipText(knotText);
        }, () -> {
            MainScene.clearHoverKnot();
            MainScene.clearTooltipText();
        }, clickAction, font));
    }

    /**
     * TODO: document {@code addHoverSegment}.
     *
     * @param str TODO: describe
     * @param c TODO: describe
     * @param segment TODO: describe
     * @param clickAction TODO: describe
     */
    public void addHoverSegment(String str, Color c, Segment segment, Action clickAction) {
        HyperString segmentInfo = new HyperString();
        children.add(segmentInfo);
        segmentInfo.addDistance(segment.distance, c);
        words.add(new HyperWord(str, c, () -> {
            MainScene.setHoverSegment(segment, c);
            MainScene.setTooltipText(segmentInfo);
        }, () -> {
            MainScene.clearHoverSegment();
            MainScene.clearTooltipText();
        }, clickAction, font));
    }

    /**
     * TODO: document {@code addDistance}.
     *
     * @param distance TODO: describe
     * @param c TODO: describe
     */
    public void addDistance(double distance, Color c) {
        addWord(String.format("%.2f", distance), c);
    }

    private void setWrap(boolean b, int i) {
        wrap = true;
        charWrap = i;
    }

    /**
     * TODO: document {@code getWord}.
     *
     * @param i TODO: describe
     * @return TODO: describe
     */
    public HyperWord getWord(int i) {
        HyperWord w = words.get(i);
        return w;
    }

    /**
     * TODO: document {@code getLastWord}.
     *
     * @return TODO: describe
     */
    public HyperWord getLastWord() {
        HyperWord w = words.get(words.size() - 1);
        return w;
    }

    /**
     * TODO: document {@code newLine}.
     */
    public void newLine() {
        lines++;
        words.add(new HyperWord(true, font));
        lineStartMap.add(words.size() - 1);
        strMap.put(lines - 1, "");
    }

    /**
     * TODO: document {@code getWidthPixels}.
     *
     * @return TODO: describe
     */
    public float getWidthPixels() {

        float max = 0;
        wrappedLines = 0;
        for (String str : strMap.values()) {

            int chars = 0;
            float lineWidth = 0;
            for (String w : str.split(" ")) {
                String r = w + " ";
                float width = Drawing.FONT_HEIGHT_PIXELS / font.fontHeight * font.getWidth(r);
                chars += r.length();
                if (wrap && chars > charWrap) {
                    if (max < lineWidth) {
                        max = lineWidth;
                    }
                    wrappedLines++;
                    chars = r.length();
                    lineWidth = 0;
                }
                lineWidth += width;
            }
            if (max < lineWidth) {
                max = lineWidth;
            }
        }
        return max;
    }

    /**
     * TODO: document {@code getHeightPixels}.
     *
     * @return TODO: describe
     */
    public int getHeightPixels() {
        return (int) Drawing.FONT_HEIGHT_PIXELS * (wrap ? (lines + wrappedLines) : lines);
    }

    /**
     * TODO: document {@code getLine}.
     *
     * @param i TODO: describe
     * @return TODO: describe
     */
    public ArrayList<HyperWord> getLine(int i) {
        ArrayList<HyperWord> line = new ArrayList<>();
        int idxStart = lineStartMap.get(i);
        int idxEnd = words.size();
        if (i < lines - 1) {
            idxEnd = lineStartMap.get(i + 1);
        }
        for (int j = idxStart; j < idxEnd; j++) {
            line.add(words.get(j));
        }
        return line;
    }

    /**
     * TODO: document {@code calculateClearHover}.
     *
     * @param normalizedPosX TODO: describe
     * @param normalizedPosY TODO: describe
     */
    public void calculateClearHover(float normalizedPosX, float normalizedPosY) {
        for (HyperWord w : words) {
            if (w.subWords != null) {
                for (HyperWord subWord : w.subWords) {
                    subWord.calculateClearHover(normalizedPosX, normalizedPosY);
                }
            } else {
                w.calculateClearHover(normalizedPosX, normalizedPosY);
            }
        }
    }

    /**
     * TODO: document {@code calculateHover}.
     *
     * @param normalizedPosX TODO: describe
     * @param normalizedPosY TODO: describe
     */
    public void calculateHover(float normalizedPosX, float normalizedPosY) {
        for (HyperWord w : words) {
            if (w.subWords != null) {
                for (HyperWord subWord : w.subWords) {
                    subWord.calculateHover(normalizedPosX, normalizedPosY);
                }
            } else {
                w.calculateHover(normalizedPosX, normalizedPosY);
            }
        }
    }

    /**
     * TODO: document {@code click}.
     *
     * @param normalizedPosX TODO: describe
     * @param normalizedPosY TODO: describe
     */
    public void click(float normalizedPosX, float normalizedPosY) {
        for (HyperWord w : words) {
            if (w.subWords != null) {
                for (HyperWord subWord : w.subWords) {
                    subWord.click(normalizedPosX, normalizedPosY);
                }
            } else {
                w.click(normalizedPosX, normalizedPosY);
            }
        }
    }

    /**
     * TODO: document {@code setLineOffsetFromTopRow}.
     *
     * @param camera TODO: describe
     * @param row TODO: describe
     * @param scrollOffsetY TODO: describe
     * @param rowHeight TODO: describe
     * @return TODO: describe
     */
    public int setLineOffsetFromTopRow(Camera2D camera, int row, float scrollOffsetY, float rowHeight) {
        int startRow = row;
        for (int i = 0; i < lines; i++) {
            row += setLineOffsetFromTopRow(camera, row, scrollOffsetY, rowHeight, i);
        }
        return row - startRow;
    }

    /**
     * TODO: document {@code setLineOffsetFromTopRow}.
     *
     * @param camera TODO: describe
     * @param row TODO: describe
     * @param scrollOffsetY TODO: describe
     * @param rowHeight TODO: describe
     * @param lineNumber TODO: describe
     * @return TODO: describe
     */
    public int setLineOffsetFromTopRow(Camera2D camera, int row, float scrollOffsetY, float rowHeight,
            int lineNumber) {
        int startRow = row;
        int idxStart = lineStartMap.get(lineNumber);
        int idxEnd = words.size();
        if (lineNumber < lines - 1) {
            idxEnd = lineStartMap.get(lineNumber + 1);
        }
        float offset = 0;
        float charLength = 0;
        wrappedLines = 0;
        for (int j = idxStart; j < idxEnd; j++) {
            HyperWord w = words.get(j);
            ArrayList<HyperWord> subWords;
            if (w.wordAction == null) {
                subWords = new ArrayList<>();
                subWords.add(w);
            } else {
                subWords = w.subWords();
            }
            for (HyperWord subWord : subWords) {
                charLength += subWord.text.size();
                float wordX = offset;
                float wordWidth = Drawing.FONT_HEIGHT_PIXELS / font.fontHeight * subWord.width;

                if (wrap && (wordX + wordWidth > camera.getWidth() || charLength > charWrap)) {
                    row++;
                    wrappedLines++;
                    offset = 0;
                    wordX = 0;
                    charLength = 0;
                }
                float wordY = camera.getHeight() - ((row + 1) * rowHeight) + scrollOffsetY;
                if (wordY < 0 || wordY > camera.getHeight()) {
                    subWord.culled = true;
                } else {
                    subWord.culled = false;
                }
                subWord.setBounds(wordX, wordY, camera.getScreenOffsetX() + offset, camera.getScreenOffsetY() + wordY,
                        rowHeight,
                        camera.viewBounds);
                offset += wordWidth;
            }
        }
        return row - startRow + 1;
    }

    /**
     * TODO: document {@code setLineOffsetCentered}.
     *
     * @param camera TODO: describe
     * @param x TODO: describe
     * @param y TODO: describe
     * @param font TODO: describe
     * @param lineNumber TODO: describe
     */
    public void setLineOffsetCentered(Camera2D camera, float x, float y, Font font, int lineNumber) {
        String lineText = strMap.get(lineNumber);
        float centerX = Drawing.FONT_HEIGHT_PIXELS / font.fontHeight * font.getWidth(lineText) / 2;
        float centerY = Drawing.FONT_HEIGHT_PIXELS / font.fontHeight * font.getHeight(lineText) / 2;
        int idxStart = lineStartMap.get(lineNumber);
        int idxEnd = words.size();
        if (lineNumber < lines - 1) {
            idxEnd = lineStartMap.get(lineNumber + 1);
        }
        float offset = 0;
        for (int j = idxStart; j < idxEnd; j++) {
            HyperWord w = words.get(j);
            if (w.newLine) {
                continue;
            }
            ArrayList<HyperWord> subWords;
            if (w.wordAction == null) {
                subWords = new ArrayList<>();
                subWords.add(w);
            } else {
                subWords = w.subWords();
            }
            for (HyperWord subWord : subWords) {
                float wordX = x + offset - centerX;
                float wordY = y - centerY;
                subWord.setBounds(wordX, wordY, camera.getScreenOffsetX() + wordX, camera.getScreenOffsetY() + wordY,
                        font.getHeight(subWord.charSequence), camera.viewBounds);
                offset += Drawing.FONT_HEIGHT_PIXELS / font.fontHeight * subWord.width;
            }
        }
    }

    /**
     * TODO: document {@code toString}.
     *
     * @return TODO: describe
     */
    @Override
    public String toString() {
        return words.toString();
    }

    /**
     * TODO: document {@code addHyperString}.
     *
     * @param h TODO: describe
     */
    public void addHyperString(HyperString h) {
        for (HyperWord w : h.words) {
            this.addWord(w);
        }
    }

    private void addWord(HyperWord w) {
        if (w.newLine) {
            this.newLine();
        } else {
            this.addWord((String) w.charSequence, w.color, w.hoverAction, w.clearHover, w.clickAction);
        }

    }

    /**
     * TODO: document {@code getLines}.
     *
     * @return TODO: describe
     */
    public int getLines() {
        if (!wrap) {
            return lines;
        }
        return wrappedLines + lines;
    }

    /**
     * TODO: document {@code wrap}.
     */
    public void wrap() {
        wrap = true;
    }

    /**
     * TODO: document {@code setData}.
     *
     * @param data TODO: describe
     */
    public void setData(Object data) {
        this.data = data;
    }

    /**
     * TODO: document {@code getData}.
     *
     * @return TODO: describe
     */
    public Object getData() {
        return data;
    }

    /**
     * TODO: document {@code draw}.
     */
    public void draw() {
        MouseTrap.hyperStrings.add(this);
    }

    /**
     * TODO: document {@code addWordClick}.
     *
     * @param word TODO: describe
     * @param cyan TODO: describe
     * @param clickAction TODO: describe
     * @throws UnsupportedOperationException TODO: describe
     */
    public void addWordClick(Object word, Color cyan, Action clickAction) {
        throw new UnsupportedOperationException("Unimplemented method 'addWordClick'");
    }
}
