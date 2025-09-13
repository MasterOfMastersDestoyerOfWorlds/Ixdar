package shell.render.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Supplier;

import shell.cameras.Bounds;
import shell.cameras.Camera2D;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.platform.input.MouseTrap;
import shell.render.color.Color;
import shell.ui.Drawing;
import shell.ui.actions.Action;
import shell.ui.main.Main;

public class HyperString {

    public ArrayList<Word> words;
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

    public HyperString() {
        words = new ArrayList<>();
        strMap = new HashMap<>();
        lineStartMap = new ArrayList<>();
        children = new ArrayList<>();
        lineStartMap.add(0);
        strMap.put(0, "");
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

    public void addDynamicWordClick(Supplier<String> wordAction, Color c, Action clickAction) {
        words.add(new Word(wordAction, c, () -> {
        }, () -> {
        }, clickAction));
    }

    public void addWord(String word, Color c, Action hoverAction, Action clearHover, Action clickAction) {
        for (String w : word.split(" ")) {
            strMap.computeIfPresent(lines - 1, (key, val) -> val + w + " ");
            words.add(new Word(w + " ", c, hoverAction, clearHover, clickAction));
        }
    }

    public void addDynamicWord(Supplier<String> wordAction) {
        words.add(new Word(wordAction, defaultColor, () -> {
        }, () -> {
        }, () -> {
        }));
    }

    public void addDynamicWord(Supplier<String> wordAction, Color c) {
        words.add(new Word(wordAction, c, () -> {
        }, () -> {
        }, () -> {
        }));
    }

    public void addDynamicWord(Supplier<String> wordAction, Color c, Action hoverAction, Action clearHover,
            Action clickAction) {
        words.add(new Word(wordAction, c, hoverAction, clearHover, clickAction));
    }

    public void addLine(String word, Color c) {
        addWord(word, c);
        this.newLine();
    }

    public void addLine(String word) {
        addWord(word);
        this.newLine();
    }

    public void addTooltip(String word, Color c, HyperString toolTipText, Action clickAction) {
        for (String w : word.split(" ")) {
            words.add(new Word(w + " ", c, () -> Main.setTooltipText(toolTipText), () -> Main.clearTooltipText(),
                    clickAction));
        }
    }

    public void addDynamicTooltip(Supplier<String> wordAction, Color c, HyperString toolTipText, Action clickAction) {
        words.add(new Word(wordAction, c, () -> Main.setTooltipText(toolTipText), () -> Main.clearTooltipText(),
                clickAction));
    }

    public void addHoverKnot(String word, Color c, Knot hoverKnot, Action clickAction) {
        HyperString knotText = new HyperString();
        children.add(knotText);
        knotText.addWord(hoverKnot.toString() + " FlatID: " + hoverKnot.id, c);
        knotText.setWrap(true, 30);
        words.add(new Word(word, c, () -> {
            Main.setHoverKnot(hoverKnot);
            Main.setTooltipText(knotText);
        }, () -> {
            Main.clearHoverKnot();
            Main.clearTooltipText();
        }, clickAction));
    }

    public void addHoverSegment(String str, Color c, Segment segment, Action clickAction) {
        HyperString segmentInfo = new HyperString();
        children.add(segmentInfo);
        segmentInfo.addDistance(segment.distance, c);
        words.add(new Word(str, c, () -> {
            Main.setHoverSegment(segment, c);
            Main.setTooltipText(segmentInfo);
        }, () -> {
            Main.clearHoverSegment();
            Main.clearTooltipText();
        }, clickAction));
    }

    public void addDistance(double distance, Color c) {
        addWord(String.format("%.2f", distance), c);
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
            if (w.subWords != null) {
                for (Word subWord : w.subWords) {
                    subWord.calculateClearHover(normalizedPosX, normalizedPosY);
                }
            } else {
                w.calculateClearHover(normalizedPosX, normalizedPosY);
            }
        }
    }

    public void calculateHover(float normalizedPosX, float normalizedPosY) {
        for (Word w : words) {
            if (w.subWords != null) {
                for (Word subWord : w.subWords) {
                    subWord.calculateHover(normalizedPosX, normalizedPosY);
                }
            } else {
                w.calculateHover(normalizedPosX, normalizedPosY);
            }
        }
    }

    public void click(float normalizedPosX, float normalizedPosY) {
        for (Word w : words) {
            if (w.subWords != null) {
                for (Word subWord : w.subWords) {
                    subWord.click(normalizedPosX, normalizedPosY);
                }
            } else {
                w.click(normalizedPosX, normalizedPosY);
            }
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
        wrappedLines = 0;
        for (int j = idxStart; j < idxEnd; j++) {
            Word w = words.get(j);
            ArrayList<Word> subWords;
            if (w.wordAction == null) {
                subWords = new ArrayList<>();
                subWords.add(w);
            } else {
                subWords = w.subWords();
            }
            for (Word subWord : subWords) {
                subWord.setWidth(font);
                charLength += subWord.text.length();
                float wordX = offset;
                float wordWidth = Drawing.FONT_HEIGHT_PIXELS / Drawing.font.fontHeight * subWord.width;

                if (wrap && (wordX + wordWidth > camera.getWidth() || charLength > charWrap)) {
                    row++;
                    wrappedLines++;
                    offset = 0;
                    wordX = 0;
                    charLength = 0;
                }
                float wordY = camera.getHeight() - ((row + 1) * rowHeight) + scrollOffsetY;
                subWord.setBounds(wordX, wordY, camera.getScreenOffsetX() + offset, camera.getScreenOffsetY() + wordY,
                        rowHeight,
                        camera.viewBounds);
                offset += wordWidth;
            }
        }
        return row - startRow + 1;
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
            ArrayList<Word> subWords;
            if (w.wordAction == null) {
                subWords = new ArrayList<>();
                subWords.add(w);
            } else {
                subWords = w.subWords();
            }
            for (Word subWord : subWords) {
                float wordX = x + offset - centerX;
                float wordY = y - centerY;
                subWord.setBounds(wordX, wordY, camera.getScreenOffsetX() + wordX, camera.getScreenOffsetY() + wordY,
                        font.getHeight(subWord.text), camera.viewBounds);
                offset += Drawing.FONT_HEIGHT_PIXELS / Drawing.font.fontHeight * subWord.width;
            }
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
        if (w.newLine) {
            this.newLine();
        } else {
            this.addWord((String) w.text, w.color, w.hoverAction, w.clearHover, w.clickAction);
        }

    }

    public int getLines() {
        if (!wrap) {
            return lines;
        }
        return wrappedLines + lines;
    }

    public void wrap() {
        wrap = true;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public Object getData() {
        return data;
    }

    public void draw() {
        MouseTrap.hyperStrings.add(this);
    }

    public void addWordClick(Object word, Color cyan, Action clickAction) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'addWordClick'");
    }
}
