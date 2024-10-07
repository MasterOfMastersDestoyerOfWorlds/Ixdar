package shell.render.text;

import java.util.ArrayList;
import java.util.HashMap;

import shell.cameras.Camera2D;
import shell.render.color.Color;
import shell.ui.Drawing;
import shell.ui.actions.Action;

public class HyperString {

    public ArrayList<Word> words;
    public HashMap<Integer, String> strMap;
    public ArrayList<Integer> lineStartMap;
    public Color defaultColor = Color.IXDAR;
    public int lines = 1;

    public HyperString() {
        words = new ArrayList<>();
        strMap = new HashMap<>();
        lineStartMap = new ArrayList<>();
        lineStartMap.add(0);
    }

    public void addWord(String word) {
        addWord(word, defaultColor, () -> {
        }, () -> {
        }, () -> {
        });
    }

    public void addWord(String word, Color c, Action hoverAction, Action clearHover, Action clickAction) {
        strMap.computeIfPresent(lines - 1, (key, val) -> val + word);
        words.add(new Word(word, c, hoverAction, clearHover, clickAction));
    }

    public Word getWord(int i) {
        Word w = words.get(i);
        return w;
    }

    public void newLine() {
        lines++;
        words.add(new Word(true));
        lineStartMap.add(words.size() - 1);
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

    public void calculateHover(float normalizedPosX, float normalizedPosY) {
        for (Word w : words) {
            w.calculateHover(normalizedPosX, normalizedPosY);
        }
    }

    public void setScreenOffset(Camera2D camera, int row, float rowHeight, Font font, int i) {
        int idxStart = lineStartMap.get(i);
        int idxEnd = words.size();
        if (i < lines - 1) {
            idxEnd = lineStartMap.get(i + 1);
        }
        float offset = 0;
        for (int j = idxStart; j < idxEnd; j++) {
            Word w = words.get(j);
            w.setBounds(camera.getScreenOffsetX() + offset,
                    camera.getScreenOffsetY() + camera.getHeight() - ((row + 1) * rowHeight), rowHeight);
            offset += Drawing.FONT_HEIGHT_PIXELS / Drawing.font.fontHeight * w.width;
        }
    }

}
