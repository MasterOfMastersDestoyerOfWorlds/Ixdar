
package ixdar.graphics.render.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.joml.Vector2f;

import ixdar.graphics.cameras.Camera;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.Texture;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.sdf.SDFTexture;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.graphics.render.shaders.ShaderProgram.ShaderType;
import ixdar.platform.Platforms;

public class Font {
    public static final String RES = "res";
    public static final String FONT_ATLAS_INIT_FAILED = "Font atlas init failed";
    public static final float NUM_0 = 0f;
    public static final float NUM_32 = 32f;
    public static final float NUM_20 = 20f;
    public static final int NUM_64 = 64;
    public static final float NUM_0_25 = 0.25f;
    public static final double NUM_0_0001 = 0.0001;

    private static final String ATLAS_JSON_PATH = "opensans.json";
    public Map<Character, Glyph> glyphs;
    public Texture texture;

    public float fontHeight;
    public float fontWidth;
    public ShaderProgram shader;
    public int maxTextWidth;
    private SDFTexture sdfTexture;
    private float pxPerEm;
    private float ascenderPx;
    private float descenderPx;
    private Map<Integer, Map<Integer, Float>> kerningEm;

    /**
     * Load the OpenSans MSDF atlas (synchronously when available, otherwise
     * asynchronously) and finalize {@link #glyphs}, {@link #texture}, the
     * SDF shader, and metric-derived {@link #fontHeight}/{@link #fontWidth}.
     */
    public Font() {
        String json = Platforms.get().trySyncLoadSource(RES, ATLAS_JSON_PATH);
        if (json != null && !json.isEmpty()) {
            finishFromAtlasJson(json);
            return;
        }
        int platformId = Platforms.gl().getPlatformID();
        Platforms.get().loadSourceAsync(RES, ATLAS_JSON_PATH, platformId, this::finishFromAtlasJson);
    }

    private void finishFromAtlasJson(String json) {
        try {
            if (json == null || json.isEmpty()) {
                Platforms.get().log(FONT_ATLAS_INIT_FAILED);
                return;
            }
            FontAtlasDTO root = Platforms.get().parseFontAtlas(json);
            if (root == null || root.atlas == null || root.metrics == null) {
                Platforms.get().log(FONT_ATLAS_INIT_FAILED);
                return;
            }
            FontAtlasData atlas = new FontAtlasData();
            atlas.width = root.atlas.width;
            atlas.height = root.atlas.height;
            atlas.sizePx = (float) root.atlas.size;
            float lineHeightEm = (float) root.metrics.lineHeight;
            atlas.derivedLineHeight = (atlas.sizePx > NUM_0 ? atlas.sizePx * lineHeightEm : NUM_32 * lineHeightEm);
            this.glyphs = buildGlyphs(root);
            this.pxPerEm = atlas.sizePx;
            this.ascenderPx = (float) (atlas.sizePx * root.metrics.ascender);
            this.descenderPx = (float) (atlas.sizePx * root.metrics.descender);
            this.kerningEm = buildKerning(root);
            this.fontHeight = atlas.derivedLineHeight;
            this.fontWidth = atlas.sizePx;
            final float distanceRange = (float) root.atlas.distanceRange;
            Platforms.get().loadTexture("opensans.png", Platforms.gl().getPlatformID(), t -> {
                this.texture = t;
                this.shader = ShaderType.TextureSDF.getShader();
                this.sdfTexture = new SDFTexture(this.texture);
                this.sdfTexture.setSharpCorners(true);
                this.sdfTexture.setBorderDist(NUM_20);
                this.sdfTexture.setPxRange(distanceRange);
            });
            this.maxTextWidth = NUM_64;
        } catch (Throwable e) {
            Platforms.get().log(FONT_ATLAS_INIT_FAILED);
        }
    }

    /**
     * Compute the rendered pixel width of {@code text}, accounting for
     * kerning and taking the maximum across newline-separated lines.
     *
     * @param text source text (may contain {@code \n})
     * @return widest line's pixel width
     */
    public float getWidth(CharSequence text) {
        if (glyphs == null) {
            return NUM_0;
        }
        float maxWidthPx = NUM_0;
        float lineAdvanceEm = NUM_0;
        int prevCodePoint = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                float lineWidthPx = lineAdvanceEm * pxPerEm;
                if (lineWidthPx > maxWidthPx)
                    maxWidthPx = lineWidthPx;
                lineAdvanceEm = NUM_0;
                prevCodePoint = -1;
                continue;
            }
            if (c == '\r') {
                continue;
            }
            Glyph g = glyphs.get(c);
            if (g == null)
                continue;
            if (prevCodePoint != -1) {
                lineAdvanceEm += getKerningEm(prevCodePoint, c);
            }
            lineAdvanceEm += g.advance;
            prevCodePoint = c;
        }
        float lastLineWidthPx = lineAdvanceEm * pxPerEm;
        if (lastLineWidthPx > maxWidthPx)
            maxWidthPx = lastLineWidthPx;
        return maxWidthPx;
    }

    /**
     * Compute rendered pixel height as line count times {@link #fontHeight}.
     *
     * @param text source text (may contain {@code \n})
     * @return total pixel height for all lines
     */
    public int getHeight(CharSequence text) {
        if (glyphs == null) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                lines++;
            }
        }
        return Math.round(lines * fontHeight);
    }

    /**
     * Render a sequence of {@link HyperChar} glyphs at {@code (x, y)} using
     * the active SDF shader's already-running setup pass. Walks the string
     * laying out plane-space glyph quads with kerning and newlines.
     *
     * @param text glyph stream to render
     * @param x left x in world coordinates
     * @param y top y in world coordinates
     * @param glyphHeight target glyph height in pixels
     * @param c text tint
     * @param camera camera providing transform and z-index
     */
    public void drawTextNoSetup(ArrayList<HyperChar> text, float x, float y, float glyphHeight,
            Color c, Camera camera) {
        if (sdfTexture == null || glyphs == null) {
            return;
        }
        float scale = glyphHeight / fontHeight;
        float drawX = x;
        float baselineY = y + (ascenderPx * scale) * NUM_0_25;
        float penEm = NUM_0;
        int prevCodePoint = -1;

        for (int i = 0; i < text.size(); i++) {
            char ch = text.get(i).c;
            if (ch == '\n') {
                baselineY -= fontHeight * scale;
                penEm = NUM_0;
                prevCodePoint = -1;
                continue;
            }
            if (ch == '\r') {
                continue;
            }
            Glyph g = glyphs.get(ch);
            if (g == null) {
                prevCodePoint = -1;
                continue;
            }
            if (prevCodePoint != -1) {
                penEm += getKerningEm(prevCodePoint, ch);
            }
            float glyphLeftPx = (penEm + g.planeLeft) * pxPerEm * scale;
            float glyphBottomPx = (g.planeBottom) * pxPerEm * scale;
            float glyphWidthPx = (g.planeRight - g.planeLeft) * pxPerEm * scale;
            float glyphHeightPx = (g.planeTop - g.planeBottom) * pxPerEm * scale;

            if (glyphWidthPx > 0 && glyphHeightPx > 0) {

                HyperChar h = text.get(i);
                h.drawRegionNoSetup(drawX + glyphLeftPx, baselineY + glyphBottomPx,
                        glyphWidthPx, glyphHeightPx,
                        g.x, g.y, g.width, g.height, c, camera);
            }
            penEm += g.advance;
            prevCodePoint = ch;
        }
    }

    /**
     * Free the GL atlas texture.
     */
    public void dispose() {
        texture.delete();
    }

    /**
     * Lay out a {@link HyperString} centered on {@code (x, y)} and draw all
     * its words (including dynamic sub-words) under a single SDF shader pass.
     *
     * @param hyperString text to render
     * @param x center x in world coordinates
     * @param y center y in world coordinates
     * @param height row height in pixels
     * @param camera 2D camera providing transform and z-index
     */
    public void drawHyperString(HyperString hyperString, float x, float y, float height, Camera2D camera) {
        hyperString.setLineOffsetCentered(camera, x, y, this, 0);
        sdfTexture.setup(camera);
        for (int lineNumber = 0; lineNumber < hyperString.lines; lineNumber++) {
            hyperString.draw();
            ArrayList<HyperWord> words = hyperString.getLine(lineNumber);
            for (int i = 0; i < words.size(); i++) {
                HyperWord word = words.get(i);
                if (word.subWords != null) {
                    for (HyperWord subWord : word.subWords) {
                        drawTextNoSetup(subWord.text, subWord.x,
                                subWord.y, height, subWord.color,
                                camera);
                    }
                } else {
                    drawTextNoSetup(word.text, word.x,
                            word.y, height, word.color,
                            camera);
                }
            }
        }
        sdfTexture.cleanup(camera);
    }

    /**
     * Lay out and draw multiple {@link HyperString}s at parallel positions
     * inside a single SDF shader pass.
     *
     * @param hyperStrings strings to render
     * @param xLoc center positions, one per string (must match length)
     * @param height row height in pixels
     * @param camera 2D camera providing transform and z-index
     */
    public void drawHyperStrings(ArrayList<HyperString> hyperStrings, ArrayList<Vector2f> xLoc, float height,
            Camera2D camera) {
        sdfTexture.setup(camera);
        for (int j = 0; j < hyperStrings.size(); j++) {
            Vector2f loc = xLoc.get(j);
            HyperString hyperString = hyperStrings.get(j);
            hyperString.setLineOffsetCentered(camera, loc.x, loc.y, this, 0);
            hyperString.draw();
            for (int lineNumber = 0; lineNumber < hyperString.lines; lineNumber++) {
                ArrayList<HyperWord> words = hyperString.getLine(lineNumber);
                for (int i = 0; i < words.size(); i++) {
                    HyperWord word = words.get(i);
                    if (word.subWords != null) {
                        for (HyperWord subWord : word.subWords) {
                            drawTextNoSetup(subWord.text, subWord.x,
                                    subWord.y, height, subWord.color,
                                    camera);
                        }
                    } else {
                        drawTextNoSetup(word.text, word.x,
                                word.y, height, word.color,
                                camera);
                    }
                }
            }
        }
        sdfTexture.cleanup(camera);
    }

    /**
     * Lay out a {@link HyperString} top-down starting at {@code row} with a
     * vertical scroll offset, draw the visible (non-culled, non-newline)
     * words inside a single SDF shader pass.
     *
     * @param hyperString text to render
     * @param row row index of the first line from the top
     * @param scrollOffsetY vertical scroll offset in pixels
     * @param height row height in pixels
     * @param camera 2D camera providing transform and z-index
     */
    public void drawHyperStringRows(HyperString hyperString, int row, float scrollOffsetY, float height,
            Camera2D camera) {
        if (sdfTexture == null) {
            return;
        }

        sdfTexture.setup(camera);
        hyperString.setLineOffsetFromTopRow(camera, row, scrollOffsetY, height);
        hyperString.draw();
        for (int lineNumber = 0; lineNumber < hyperString.lines; lineNumber++) {
            ArrayList<HyperWord> words = hyperString.getLine(lineNumber);
            for (int i = 0; i < words.size(); i++) {
                HyperWord word = words.get(i);
                if (word.culled) {
                    continue;
                }
                if (word.newLine) {
                    continue;
                }
                if (word.subWords != null) {
                    for (HyperWord subWord : word.subWords) {
                        drawTextNoSetup(subWord.text, subWord.x,
                                subWord.y, height, subWord.color,
                                camera);
                    }
                } else {
                    drawTextNoSetup(word.text, word.x,
                            word.y, height, word.color,
                            camera);
                }
            }
        }
        sdfTexture.cleanup(camera);
    }

    private static Map<Character, Glyph> buildGlyphs(FontAtlasDTO root) {
        HashMap<Character, Glyph> map = new HashMap<>();
        if (root.glyphs == null)
            return map;
        for (FontAtlasDTO.GlyphEntry ge : root.glyphs) {
            if (ge.atlasBounds == null)
                continue;
            int x = (int) Math.floor(ge.atlasBounds.left + NUM_0_0001);
            int y = (int) Math.floor(ge.atlasBounds.bottom + NUM_0_0001);
            int width = (int) Math.round(ge.atlasBounds.right - ge.atlasBounds.left);
            int height = (int) Math.round(ge.atlasBounds.top - ge.atlasBounds.bottom);
            if (width <= 0 || height <= 0)
                continue;
            char ch = (char) ge.unicode;
            float pl = ge.planeBounds != null ? (float) ge.planeBounds.left : NUM_0;
            float pb = ge.planeBounds != null ? (float) ge.planeBounds.bottom : NUM_0;
            float pr = ge.planeBounds != null ? (float) ge.planeBounds.right : NUM_0;
            float pt = ge.planeBounds != null ? (float) ge.planeBounds.top : NUM_0;
            map.put(ch, new Glyph(width, height, x, y, (float) ge.advance, pl, pb, pr, pt));
        }
        for (SpecialGlyphs specialGlyph : SpecialGlyphs.values()) {
            map.put(specialGlyph.getChar(), specialGlyph.glyph);
        }
        return map;
    }

    private static Map<Integer, Map<Integer, Float>> buildKerning(FontAtlasDTO root) {
        HashMap<Integer, Map<Integer, Float>> kerning = new HashMap<>();
        if (root.kerning == null)
            return kerning;
        for (FontAtlasDTO.KerningEntry ke : root.kerning) {
            int u1 = ke.unicode1;
            int u2 = ke.unicode2;
            float advEm = (float) ke.advance;
            kerning.computeIfAbsent(u1, k -> new HashMap<>()).put(u2, advEm);
        }
        return kerning;
    }

    private float getKerningEm(int prevCodePoint, int codePoint) {
        if (kerningEm == null)
            return NUM_0;
        Map<Integer, Float> m = kerningEm.get(prevCodePoint);
        if (m == null)
            return NUM_0;
        Float v = m.get(codePoint);
        return v != null ? v.floatValue() : NUM_0;
    }

    private static class FontAtlasData {
        int width;
        int height;
        float sizePx;
        float derivedLineHeight;
    }

}
