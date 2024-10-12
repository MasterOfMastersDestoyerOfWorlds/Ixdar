package shell.render.text;

import java.util.ArrayList;
import java.util.HashMap;

import shell.Main;
import shell.cameras.Camera2D;
import shell.render.color.Color;
import shell.ui.Drawing;
import shell.ui.actions.Action;
import shell.ui.input.mouse.MouseTrap;

public class HyperString {

    public ArrayList<Word> words;
    public HashMap<Integer, String> strMap;
    public ArrayList<Integer> lineStartMap;
    public Color defaultColor = Color.IXDAR;
    public int lines = 1;
    public boolean debug;

    public HyperString() {
        words = new ArrayList<>();
        strMap = new HashMap<>();
        lineStartMap = new ArrayList<>();
        lineStartMap.add(0);
        strMap.put(0, "");
        MouseTrap.hyperStrings.add(this);
    }

    public void addWord(String word) {
        addWord(word, defaultColor, () -> {
        }, () -> {
        }, () -> {
        });
    }

    public void addWord(String word, Color c) {
        addWord(word, c, () -> {
        }, () -> {
        }, () -> {
        });
    }

    public void addWord(String word, Color c, Action hoverAction, Action clearHover, Action clickAction) {
        strMap.computeIfPresent(lines - 1, (key, val) -> val + word);
        words.add(new Word(word, c, hoverAction, clearHover, clickAction));
    }

    public void addTooltip(String word, Color c, HyperString toolTipText) {
        strMap.computeIfPresent(lines - 1, (key, val) -> val + word);
        words.add(new Word(word, c,
                () -> Main.setTooltipText(toolTipText),
                () -> Main.clearTooltipText(),
                () -> {
                }));
    }

    public Word getWord(int i) {
        Word w = words.get(i);
        return w;
    }

    public void newLine() {
        lines++;
        words.add(new Word(true));
        lineStartMap.add(words.size() - 1);
        strMap.put(lines - 1, "");
    }

    public int getWidthPixels() {
        int max = 0;
        for (String str : strMap.values()) {
            int width = Drawing.font.getWidth(str);
            if (max < width) {
                max = width;
            }
        }
        if (max == 0) {
            max = 100;
        }
        return max;
    }

    public int getHeightPixels() {
        return (int) Drawing.FONT_HEIGHT_PIXELS;
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

    public void setLineOffsetFromTopRow(Camera2D camera, int row, float rowHeight, Font font) {
        for (int i = 0; i < lines; i++) {
            setLineOffsetFromTopRow(camera, row + i, rowHeight, font, i);
        }
    }

    public void setLineOffsetFromTopRow(Camera2D camera, int row, float rowHeight, Font font, int lineNumber) {
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
            float wordX = offset;
            float wordY = camera.getHeight() - ((row + 1) * rowHeight);
            w.setBounds(wordX, wordY, camera.getScreenOffsetX() + offset,
                    camera.getScreenOffsetY() + wordY, rowHeight);
            offset += Drawing.FONT_HEIGHT_PIXELS / Drawing.font.fontHeight * w.width;
        }
    }

    public void setLineOffset(Camera2D camera, float x, float y, Font font, int lineNumber) {
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
            float wordX = x + offset;
            float wordY = y;
            w.setBounds(wordX, wordY, camera.getScreenOffsetX() + wordX,
                    camera.getScreenOffsetY() + wordY, font.getHeight(w.text));
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
                    camera.getScreenOffsetY() + wordY, font.getHeight(w.text));
            offset += Drawing.FONT_HEIGHT_PIXELS / Drawing.font.fontHeight * w.width;
        }
    }

    @Override
    public String toString() {
        return words.toString();
    }
}
