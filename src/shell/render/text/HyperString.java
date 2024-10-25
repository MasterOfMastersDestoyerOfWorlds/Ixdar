package shell.render.text;

import java.util.ArrayList;
import java.util.HashMap;

import shell.cameras.Bounds;
import shell.cameras.Camera2D;
import shell.knot.Knot;
import shell.render.color.Color;
import shell.ui.Drawing;
import shell.ui.actions.Action;
import shell.ui.input.mouse.MouseTrap;
import shell.ui.main.Main;

public class HyperString {

    public ArrayList<Word> words;
    public HashMap<Integer, String> strMap;
    public ArrayList<Integer> lineStartMap;
    public Color defaultColor = Color.IXDAR;
    public int lines = 1;
    public boolean debug;
    public boolean wrap;
    public int charWrap = Integer.MAX_VALUE;
    public Bounds bounds;
    private int wrappedLines;

    public HyperString() {
        words = new ArrayList<>();
        strMap = new HashMap<>();
        lineStartMap = new ArrayList<>();
        lineStartMap.add(0);
        strMap.put(0, "");
        MouseTrap.hyperStrings.add(this);
    }

    public void addWord(String word) {
        for (String w : word.split(" ")) {
            addWord(w + " ", defaultColor, () -> {
            }, () -> {
            }, () -> {
            });
        }
    }

    public void addWord(String word, Color c) {
        for (String w : word.split(" ")) {
            addWord(w + " ", c, () -> {
            }, () -> {
            }, () -> {
            });
        }
    }

    public void addWordClick(String word, Color c, Action clickAction) {
        for (String w : word.split(" ")) {
            addWord(w + " ", c, () -> {
            }, () -> {
            }, clickAction);
        }
    }

    public void addWord(String word, Color c, Action hoverAction, Action clearHover, Action clickAction) {
        for (String w : word.split(" ")) {
            strMap.computeIfPresent(lines - 1, (key, val) -> val + w + " ");
            words.add(new Word(word, c, hoverAction, clearHover, clickAction));
        }
    }

    public void addTooltip(String word, Color c, HyperString toolTipText, Action clickAction) {
        for (String w : word.split(" ")) {
            strMap.computeIfPresent(lines - 1, (key, val) -> val + w + " ");
            words.add(new Word(word, c,
                    () -> Main.setTooltipText(toolTipText),
                    () -> Main.clearTooltipText(),
                    clickAction));
        }
    }

    public void addHoverKnot(String word, Color c, Knot hoverKnot, Action clickAction) {
        for (String w : word.split(" ")) {
            strMap.computeIfPresent(lines - 1, (key, val) -> val + w + " ");
            HyperString knotText = new HyperString();
            knotText.addWord(hoverKnot.toString(), c);
            knotText.setWrap(true, 30);
            words.add(new Word(word, c,
                    () -> {
                        Main.setHoverKnot(hoverKnot);
                        Main.setTooltipText(knotText);
                    },
                    () -> {
                        Main.clearHoverKnot();
                        Main.clearTooltipText();
                    },
                    clickAction));
        }
    }

    private void setWrap(boolean b, int i) {
        wrap = true;
        charWrap = i;
    }

    public Word getWord(int i) {
        Word w = words.get(i);
        return w;
    }

    public Word getLastWord() {
        Word w = words.get(words.size() - 1);
        return w;
    }

    public void newLine() {
        lines++;
        words.add(new Word(true));
        lineStartMap.add(words.size() - 1);
        strMap.put(lines - 1, "");
    }

    public float getWidthPixels() {

        float max = 0;
        wrappedLines = 0;
        for (String str : strMap.values()) {

            int chars = 0;
            float lineWidth = 0;
            for (String w : str.split(" ")) {
                String r = w + " ";
                float width = Drawing.FONT_HEIGHT_PIXELS / Drawing.font.fontHeight * Drawing.font.getWidth(r);
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

    public int getHeightPixels() {
        return (int) Drawing.FONT_HEIGHT_PIXELS * (wrap ? (lines + wrappedLines) : lines);
    }

    public ArrayList<Word> getLine(int i) {
        ArrayList<Word> line = new ArrayList<>();
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

    public void calculateClearHover(float normalizedPosX, float normalizedPosY) {
        for (Word w : words) {
            w.calculateClearHover(normalizedPosX, normalizedPosY);
        }
    }

    public void calculateHover(float normalizedPosX, float normalizedPosY) {
        for (Word w : words) {
            w.calculateHover(normalizedPosX, normalizedPosY);
        }
    }

    public void click(float normalizedPosX, float normalizedPosY) {
        for (Word w : words) {
            w.click(normalizedPosX, normalizedPosY);
        }
    }

    public int setLineOffsetFromTopRow(Camera2D camera, int row, float scrollOffsetY, float rowHeight, Font font) {
        int startRow = row;
        for (int i = 0; i < lines; i++) {
            row += setLineOffsetFromTopRow(camera, row, scrollOffsetY, rowHeight, font, i);
        }
        return row - startRow;
    }

    public int setLineOffsetFromTopRow(Camera2D camera, int row, float scrollOffsetY, float rowHeight, Font font,
            int lineNumber) {
        int startRow = row;
        int idxStart = lineStartMap.get(lineNumber);
        int idxEnd = words.size();
        if (lineNumber < lines - 1) {
            idxEnd = lineStartMap.get(lineNumber + 1);
        }
        float offset = 0;
        float charLength = 0;
        for (int j = idxStart; j < idxEnd; j++) {
            Word w = words.get(j);
            if (w.newLine) {
                continue;
            }

            w.setWidth(font);
            charLength += w.text.length();
            float wordX = offset;
            float wordWidth = Drawing.FONT_HEIGHT_PIXELS / Drawing.font.fontHeight * w.width;
            if (wrap && (wordX + wordWidth > camera.getWidth() || charLength > charWrap)) {
                row++;
                offset = 0;
                wordX = 0;
                charLength = 0;
            }
            float wordY = camera.getHeight() - ((row + 1) * rowHeight) + scrollOffsetY;
            w.setBounds(wordX, wordY, camera.getScreenOffsetX() + offset,
                    camera.getScreenOffsetY() + wordY, rowHeight, camera.viewBounds);
            offset += wordWidth;
        }
        return row - startRow + 1;
    }

    public void setLineOffset(Camera2D camera, float x, float y, Font font, int lineNumber) {
        int idxStart = lineStartMap.get(lineNumber);
        int idxEnd = words.size();
        int row = 0;
        if (lineNumber < lines - 1) {
            idxEnd = lineStartMap.get(lineNumber + 1);
        }
        float offset = 0;
        float charLength = 0;
        for (int j = idxStart; j < idxEnd; j++) {
            Word w = words.get(j);
            if (w.newLine) {
                continue;
            }
            float wordX = x + offset;
            charLength += w.text.length();
            float wordWidth = Drawing.FONT_HEIGHT_PIXELS / Drawing.font.fontHeight * w.width;
            if (wrap && (wordX + wordWidth > camera.getWidth() || charLength > charWrap)) {
                row++;
                offset = 0;
                wordX = 0;
                charLength = 0;
            }
            float wordY = y - ((row + 1) * Drawing.font.fontHeight);
            w.setBounds(wordX, wordY, camera.getScreenOffsetX() + wordX,
                    camera.getScreenOffsetY() + wordY, font.getHeight(w.text), camera.viewBounds);
            offset += Drawing.FONT_HEIGHT_PIXELS / Drawing.font.fontHeight * w.width;
        }
    }

    public void setLineOffsetCentered(Camera2D camera, float x, float y, Font font, int lineNumber) {
        String lineText = strMap.get(lineNumber);
        float centerX = Drawing.FONT_HEIGHT_PIXELS / Drawing.font.fontHeight * Drawing.font.getWidth(lineText) / 2;
        float centerY = Drawing.FONT_HEIGHT_PIXELS / Drawing.font.fontHeight * font.getHeight(lineText) / 2;
        int idxStart = lineStartMap.get(lineNumber);
        int idxEnd = words.size();
        if (lineNumber < lines - 1) {
            idxEnd = lineStartMap.get(lineNumber + 1);
        }
        float offset = 0;
        for (int j = idxStart; j < idxEnd; j++) {
            Word w = words.get(j);
            if (w.newLine) {
                continue;
            }
            float wordX = x + offset - centerX;
            float wordY = y - centerY;
            w.setBounds(wordX, wordY, camera.getScreenOffsetX() + wordX,
                    camera.getScreenOffsetY() + wordY, font.getHeight(w.text), camera.viewBounds);
            offset += Drawing.FONT_HEIGHT_PIXELS / Drawing.font.fontHeight * w.width;
        }
    }

    @Override
    public String toString() {
        return words.toString();
    }

    public void addHyperString(HyperString h) {
        for (Word w : h.words) {
            this.addWord(w);
        }
    }

    private void addWord(Word w) {
        this.addWord((String) w.text, w.color, w.hoverAction, w.clearHover, w.clickAction);
    }

    public int getLines() {
        if (!wrap) {
            return lines;
        }
        return wrappedLines + lines;
    }

}
