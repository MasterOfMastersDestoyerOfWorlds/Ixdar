package ixdar.gui.ui.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Supplier;

import ixdar.geometry.tsp.knot.Knot;
import ixdar.geometry.tsp.knot.Segment;
import org.joml.Vector3f;

import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.text.ColorText;
import ixdar.graphics.render.text.Font;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.actions.Action;
import ixdar.input.MouseTrap;
import ixdar.platform.Platforms;
import ixdar.scenes.Scene;

public class HyperString {
    public static final int TOOLTIP_CHAR_WRAP = 30;

    private static final int ANCHOR_PIXEL_VALUES = 3;

    public ArrayList<HyperWord> words;
    public HashMap<Integer, String> strMap;
    public ArrayList<Integer> lineStartMap;
    public ArrayList<HyperString> children;
    /** Colour of words added without one: the active palette's text when the string is built. */
    public Color defaultColor = Scene.activePalette().text;
    public int lines = 1;
    public boolean debug;
    public boolean wrap;
    public int charWrap = Integer.MAX_VALUE;
    public Bounds bounds;
    public Object data;

    /**
     * Where {@link #layoutAtAnchor} centres the string: x and y in the drawing camera's world, or a
     * world point while {@link #anchorCamera} is a {@link Camera3D}.
     */
    public final Vector3f anchor = new Vector3f();

    /**
     * Camera the anchor is placed through: a {@link Camera3D} projects the world point and hides
     * the string where the surface does; {@code null} or a 2D camera takes the anchor as it is.
     */
    public Camera anchorCamera;

    /** For a 3D anchor, world distance it is pulled toward the eye before the hiding test. */
    public float anchorDepthLift;

    /**
     * Whether the string is drawn and takes hover and clicks: a 3D anchor's layout sets it, and
     * the owner of a 2D-anchored string sets it.
     */
    public boolean shown = true;

    private int wrappedLines;
    private Font font;
    private final float[] anchorPixel = new float[ANCHOR_PIXEL_VALUES];

    /**
     * Build an empty rich-text string with one blank starting line, using
     * the global drawing font.
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
     * Replace the font on this string and propagate to every word.
     *
     * @param font font to use for layout and rendering
     */
    public void setFont(Font font) {
        this.font = font;
        for (HyperWord w : words) {
            w.setFont(font);
        }
    }

    /**
     * Append space-separated words in {@link #defaultColor} with no actions.
     *
     * @param word whitespace-separated text to append
     */
    public void addWord(String word) {
        for (String w : word.split(" ")) {
            addWord(w + " ", defaultColor, () -> {
            }, () -> {
            }, null);
        }
    }

    /**
     * Append space-separated words in the given color with no actions.
     *
     * @param word whitespace-separated text to append
     * @param c color for every word
     */
    public void addWord(String word, Color c) {
        for (String w : word.split(" ")) {
            addWord(w + " ", c, () -> {
            }, () -> {
            }, null);
        }
    }

    /**
     * Append space-separated words in a fill colour with a border band round their glyphs.
     *
     * @param word         whitespace-separated text to append
     * @param fill         colour of the glyphs
     * @param border       colour of the band
     * @param borderPixels width of the band outside the glyph edges, in screen pixels
     */
    public void addWord(String word, Color fill, Color border, float borderPixels) {
        for (String w : word.split(" ")) {
            addWord(w + " ", fill, () -> {
            }, () -> {
            }, null);
            getLastWord().borderColor = border;
            getLastWord().borderPixels = borderPixels;
        }
    }

    /**
     * Append space-separated clickable words.
     *
     * @param word whitespace-separated text to append
     * @param c color for every word
     * @param clickAction action invoked when the word is clicked
     */
    public void addWordClick(String word, Color c, Action clickAction) {
        for (String w : word.split(" ")) {
            addWord(w + " ", c, () -> {
            }, () -> {
            }, clickAction);
        }
    }

    /**
     * Append a clickable word whose text is supplied dynamically each frame.
     *
     * @param wordAction supplier returning the up-to-date colored phrase
     * @param c default color (used until the supplier overrides it)
     * @param clickAction action invoked on click
     */
    public void addDynamicWordClick(Supplier<ColorText<?>> wordAction, Color c, Action clickAction) {
        words.add(new HyperWord(wordAction, c, () -> {
        }, () -> {
        }, clickAction, font));
    }

    /**
     * Append space-separated words with hover/click handlers.
     *
     * @param word whitespace-separated text to append
     * @param c color for every word
     * @param hoverAction action invoked while the cursor is over the word
     * @param clearHover action invoked when the cursor leaves the word
     * @param clickAction action invoked on click
     */
    public void addWord(String word, Color c, Action hoverAction, Action clearHover, Action clickAction) {
        for (String w : word.split(" ")) {
            strMap.computeIfPresent(lines - 1, (key, val) -> val + w + " ");
            words.add(new HyperWord(w + " ", c, hoverAction, clearHover, clickAction, font));
        }
    }

    /**
     * Append a dynamic word in the default color with no actions.
     *
     * @param wordAction supplier returning the up-to-date colored phrase
     */
    public void addDynamicWord(Supplier<ColorText<?>> wordAction) {
        words.add(new HyperWord(wordAction, defaultColor, () -> {
        }, () -> {
        }, null, font));
    }

    /**
     * Append a dynamic word in the given color with no actions.
     *
     * @param wordAction supplier returning the up-to-date colored phrase
     * @param c default color
     */
    public void addDynamicWord(Supplier<ColorText<?>> wordAction, Color c) {
        words.add(new HyperWord(wordAction, c, () -> {
        }, () -> {
        }, null, font));
    }

    /**
     * Append a dynamic word with full hover/click handlers.
     *
     * @param wordAction supplier returning the up-to-date colored phrase
     * @param c default color
     * @param hoverAction action invoked while the cursor is over the word
     * @param clearHover action invoked when the cursor leaves the word
     * @param clickAction action invoked on click
     */
    public void addDynamicWord(Supplier<ColorText<?>> wordAction, Color c, Action hoverAction, Action clearHover,
            Action clickAction) {
        words.add(new HyperWord(wordAction, c, hoverAction, clearHover, clickAction, font));
    }

    /**
     * Append words then a newline.
     *
     * @param word whitespace-separated text to append
     * @param c color for every word
     */
    public void addLine(String word, Color c) {
        addWord(word, c);
        this.newLine();
    }

    /**
     * Append words in the default color, then a newline.
     *
     * @param word whitespace-separated text to append
     */
    public void addLine(String word) {
        addWord(word);
        this.newLine();
    }

    /**
     * Append clickable words that show {@code toolTipText} on hover.
     *
     * @param word whitespace-separated text to append
     * @param c color for every word
     * @param toolTipText rich-text tooltip displayed while hovered
     * @param clickAction action invoked on click
     */
    public void addTooltip(String word, Color c, HyperString toolTipText, Action clickAction) {
        for (String w : word.split(" ")) {
            words.add(new HyperWord(w + " ", c, () -> Scene.instance.setTooltipText(toolTipText),
                    () -> Scene.instance.clearTooltipText(), clickAction, font));
        }
    }

    /**
     * Append a dynamic clickable word that shows {@code toolTipText} on hover.
     *
     * @param wordAction supplier returning the up-to-date colored phrase
     * @param c default color
     * @param toolTipText rich-text tooltip displayed while hovered
     * @param clickAction action invoked on click
     */
    public void addDynamicTooltip(Supplier<ColorText<?>> wordAction, Color c, HyperString toolTipText,
            Action clickAction) {
        words.add(new HyperWord(wordAction, c, () -> Scene.instance.setTooltipText(toolTipText),
                () -> Scene.instance.clearTooltipText(), clickAction, font));
    }

    /**
     * Append a clickable word that, while hovered, runs {@code highlight}
     * and shows a tooltip describing {@code hoverKnot}'s identifier.
     *
     * @param word word text
     * @param c color for the word
     * @param hoverKnot mesh-node knot the tooltip describes
     * @param highlight action that highlights the knot when the hover starts
     * @param clearHighlight action that drops the highlight when the hover ends
     * @param clickAction action invoked on click
     */
    public void addHoverKnot(String word, Color c, Knot hoverKnot, Action highlight, Action clearHighlight,
            Action clickAction) {
        HyperString knotText = new HyperString();
        children.add(knotText);
        knotText.addWord(hoverKnot.toString() + " FlatID: " + hoverKnot.id, c);
        knotText.setWrap(true, TOOLTIP_CHAR_WRAP);
        words.add(new HyperWord(word, c, () -> {
            highlight.perform();
            Scene.instance.setTooltipText(knotText);
        }, () -> {
            clearHighlight.perform();
            Scene.instance.clearTooltipText();
        }, clickAction, font));
    }

    /**
     * Append a clickable word that, while hovered, runs {@code highlight}
     * and shows a tooltip with {@code segment}'s distance.
     *
     * @param str word text
     * @param c color for the word
     * @param segment knot segment the tooltip describes
     * @param highlight action that highlights the segment when the hover starts
     * @param clearHighlight action that drops the highlight when the hover ends
     * @param clickAction action invoked on click
     */
    public void addHoverSegment(String str, Color c, Segment segment, Action highlight, Action clearHighlight,
            Action clickAction) {
        HyperString segmentInfo = new HyperString();
        children.add(segmentInfo);
        segmentInfo.addDistance(segment.distance, c);
        words.add(new HyperWord(str, c, () -> {
            highlight.perform();
            Scene.instance.setTooltipText(segmentInfo);
        }, () -> {
            clearHighlight.perform();
            Scene.instance.clearTooltipText();
        }, clickAction, font));
    }

    /**
     * Append a distance value formatted to 2 decimals.
     *
     * @param distance numeric distance to render
     * @param c word color
     */
    public void addDistance(double distance, Color c) {
        addWord(String.format("%.2f", distance), c);
    }

    private void setWrap(boolean b, int i) {
        wrap = true;
        charWrap = i;
    }

    /**
     * Random-access word lookup.
     *
     * @param i word index
     * @return word at index {@code i}
     */
    public HyperWord getWord(int i) {
        HyperWord w = words.get(i);
        return w;
    }

    /**
     * Most recently appended word.
     *
     * @return last word in the list
     */
    public HyperWord getLastWord() {
        HyperWord w = words.get(words.size() - 1);
        return w;
    }

    /**
     * Start a new line: bump the line count, append a sentinel newline word,
     * record the line's starting word index, and reset its string buffer.
     */
    public void newLine() {
        lines++;
        words.add(new HyperWord(true, font));
        lineStartMap.add(words.size() - 1);
        strMap.put(lines - 1, "");
    }

    /**
     * Compute the widest line's pixel width, also recording how many wrap
     * lines would be produced under the current {@link #charWrap} limit.
     *
     * @return max line width in pixels at the configured font height
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
     * Total pixel height (line count times {@code FONT_HEIGHT_PIXELS},
     * including wrap-induced extra lines when wrapping is on).
     *
     * @return rendered height in pixels
     */
    public int getHeightPixels() {
        return (int) Drawing.FONT_HEIGHT_PIXELS * (wrap ? (lines + wrappedLines) : lines);
    }

    /**
     * Words that make up line {@code i}, between {@link #lineStartMap} bounds.
     *
     * @param i line index
     * @return words in that line
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
     * Fire each word's clear-hover handler when the cursor is no longer
     * inside that word. Recurses into dynamic sub-words.
     *
     * @param normalizedPosX cursor x in screen-normalized coordinates
     * @param normalizedPosY cursor y in screen-normalized coordinates
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
     * Fire each word's hover handler when the cursor is inside that word.
     * Recurses into dynamic sub-words.
     *
     * @param normalizedPosX cursor x in screen-normalized coordinates
     * @param normalizedPosY cursor y in screen-normalized coordinates
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
     * Fire each word's click handler when the cursor is inside that word.
     *
     * @param normalizedPosX cursor x in screen-normalized coordinates
     * @param normalizedPosY cursor y in screen-normalized coordinates
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
     * Lay out every line top-down starting at {@code row}. When wrapping, a static word wider
     * than the view is first split for good into pieces that fit, keeping its colour and actions.
     *
     * @param camera 2D camera providing viewport size and screen offset
     * @param row top row index for the first line
     * @param scrollOffsetY vertical scroll offset in pixels
     * @param rowHeight row height in pixels
     * @return total rows occupied (including wrap-induced extras)
     */
    public int setLineOffsetFromTopRow(Camera2D camera, int row, float scrollOffsetY, float rowHeight) {
        float scale = rowHeight / font.fontHeight;
        float widthPixels = camera.getWidth();
        for (int index = 0; wrap && index < words.size(); index++) {
            HyperWord word = words.get(index);
            if (word.newLine || word.wordAction != null || !(word.width * scale > widthPixels)) {
                continue;
            }
            ArrayList<HyperWord> pieces = new ArrayList<>();
            String rest = word.charSequence.toString();
            while (rest.length() > 1 && font.getWidth(rest) * scale > widthPixels) {
                int fitting = 1;
                while (fitting + 1 < rest.length()
                        && font.getWidth(rest.substring(0, fitting + 1)) * scale <= widthPixels) {
                    fitting++;
                }
                pieces.add(new HyperWord(rest.substring(0, fitting), word.color, word.hoverAction,
                        word.clearHover, word.clickAction, font));
                rest = rest.substring(fitting);
            }
            pieces.add(new HyperWord(rest, word.color, word.hoverAction, word.clearHover,
                    word.clickAction, font));
            for (HyperWord piece : pieces) {
                piece.borderColor = word.borderColor;
                piece.borderPixels = word.borderPixels;
            }
            words.remove(index);
            words.addAll(index, pieces);
            for (int line = 0; line < lineStartMap.size(); line++) {
                if (lineStartMap.get(line) > index) {
                    lineStartMap.set(line, lineStartMap.get(line) + pieces.size() - 1);
                }
            }
            index += pieces.size() - 1;
        }
        int startRow = row;
        for (int i = 0; i < lines; i++) {
            row += setLineOffsetFromTopRow(camera, row, scrollOffsetY, rowHeight, i);
        }
        return row - startRow;
    }

    /**
     * Lay out a single line top-down: per-word screen bounds, wrapping at the view width or
     * {@link #charWrap}; only rows wholly outside the view are culled, a cut row is left to the
     * viewport clip.
     *
     * @param camera 2D camera providing viewport size and screen offset
     * @param row top row index for this line
     * @param scrollOffsetY vertical scroll offset in pixels
     * @param rowHeight row height in pixels
     * @param lineNumber index of the line to lay out
     * @return rows occupied by this line (1 + wrap rows)
     */
    public int setLineOffsetFromTopRow(Camera2D camera, int row, float scrollOffsetY, float rowHeight,
            int lineNumber) {
        int startRow = row;
        int idxStart = lineStartMap.get(lineNumber);
        int idxEnd = words.size();
        if (lineNumber < lines - 1) {
            idxEnd = lineStartMap.get(lineNumber + 1);
        }
        float scale = rowHeight / font.fontHeight;
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
                float wordWidth = scale * subWord.width;

                if (wrap && offset > 0
                        && (wordX + wordWidth > camera.getWidth() || charLength > charWrap)) {
                    row++;
                    wrappedLines++;
                    offset = 0;
                    wordX = 0;
                    charLength = 0;
                }
                float wordY = camera.getHeight() - ((row + 1) * rowHeight) + scrollOffsetY;
                subWord.culled = wordY + rowHeight <= 0 || wordY >= camera.getHeight();
                subWord.setBounds(wordX, wordY, camera.getScreenOffsetX() + offset, camera.getScreenOffsetY() + wordY,
                        wordWidth, rowHeight, camera.viewBounds);
                offset += wordWidth;
            }
        }
        return row - startRow + 1;
    }

    /**
     * Lay out a single line drawn {@code height} pixels tall, centered on {@code (x, y)}; each
     * word's screen bounds are the rectangle it is drawn in.
     *
     * @param camera 2D camera providing screen offset
     * @param x desired center x in world coordinates
     * @param y desired center y in world coordinates
     * @param font font to measure with
     * @param height glyph height the line is drawn at, in pixels
     * @param lineNumber index of the line to lay out
     */
    public void setLineOffsetCentered(Camera2D camera, float x, float y, Font font, float height,
            int lineNumber) {
        String lineText = strMap.get(lineNumber);
        float scale = height / font.fontHeight;
        float centerX = scale * font.getWidth(lineText) / 2;
        float centerY = scale * font.getHeight(lineText) / 2;
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
                float wordWidth = scale * subWord.width;
                subWord.setBounds(wordX, wordY, camera.getScreenOffsetX() + wordX, camera.getScreenOffsetY() + wordY,
                        wordWidth, scale * font.getHeight(subWord.charSequence), camera.viewBounds);
                offset += wordWidth;
            }
        }
    }

    /**
     * Lay out the first line centred on {@link #anchor}: as given for a 2D anchor, or where a 3D
     * anchor's camera projects it, the string hidden ({@link #shown} false) where nothing shows the
     * point. A hidden string keeps its last bounds and is left out of the draw.
     *
     * @param screen    the 2D camera the string is drawn through, in framebuffer pixels
     * @param rowHeight glyph height the line is drawn at, in pixels
     */
    public void layoutAtAnchor(Camera2D screen, float rowHeight) {
        float x = anchor.x;
        float y = anchor.y;
        if (anchorCamera instanceof Camera3D world) {
            shown = world.pointVisibility.visiblePixel(world, anchor.x, anchor.y, anchor.z, anchorDepthLift,
                    anchorPixel);
            x = anchorPixel[0];
            y = Platforms.get().getFrameBufferHeight() - anchorPixel[1];
        }
        if (shown) {
            setLineOffsetCentered(screen, x, y, font, rowHeight, 0);
        }
    }

    /**
     * Debug string built from the underlying word list.
     *
     * @return list-style representation
     */
    @Override
    public String toString() {
        return words.toString();
    }

    /**
     * Append every word from {@code h} to this string, newlines preserved and a dynamic word kept
     * dynamic.
     *
     * @param h source string
     */
    public void addHyperString(HyperString h) {
        for (HyperWord w : h.words) {
            if (w.newLine) {
                this.newLine();
            } else if (w.wordAction != null) {
                this.addDynamicWord(w.wordAction, w.color, w.hoverAction, w.clearHover, w.clickAction);
            } else {
                this.addWord(w.charSequence.toString(), w.color, w.hoverAction, w.clearHover, w.clickAction);
            }
        }
    }

    /**
     * Total line count, including wrap-induced extras when wrapping is on.
     *
     * @return number of laid-out lines
     */
    public int getLines() {
        if (!wrap) {
            return lines;
        }
        return wrappedLines + lines;
    }

    /**
     * Enable word wrapping at the viewport edge / character limit.
     */
    public void wrap() {
        wrap = true;
    }

    /**
     * Attach an opaque payload to this string.
     *
     * @param data caller-defined payload
     */
    public void setData(Object data) {
        this.data = data;
    }

    /**
     * The opaque payload attached to this string.
     *
     * @return payload (may be {@code null})
     */
    public Object getData() {
        return data;
    }

    /**
     * One plain-text line per laid-out row, newline tokens skipped and trailing whitespace trimmed.
     *
     * @return the rows' text, top first
     */
    public List<String> plainLines() {
        List<String> rows = new ArrayList<>();
        for (int row = 0; row < lines; row++) {
            StringBuilder builder = new StringBuilder();
            for (HyperWord word : getLine(row)) {
                if (!word.newLine) {
                    builder.append(word.toString());
                }
            }
            rows.add(builder.toString().stripTrailing());
        }
        return rows;
    }

    /**
     * Register this string with {@link MouseTrap} so it receives hover/click
     * dispatch this frame, once however many times it is drawn.
     */
    public void draw() {
        if (!MouseTrap.hyperStrings.contains(this)) {
            MouseTrap.hyperStrings.add(this);
        }
    }

    /**
     * Object-typed overload kept for legacy call sites; not implemented.
     *
     * @param word source word (unused)
     * @param cyan color (unused)
     * @param clickAction click action (unused)
     * @throws UnsupportedOperationException always
     */
    public void addWordClick(Object word, Color cyan, Action clickAction) {
        throw new UnsupportedOperationException("Unimplemented method 'addWordClick'");
    }
}
