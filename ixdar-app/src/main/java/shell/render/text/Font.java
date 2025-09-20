
package shell.render.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.joml.Vector2f;

import shell.cameras.Camera;
import shell.cameras.Camera2D;
import shell.platform.Platforms;
import shell.render.Texture;
import shell.render.color.Color;
import shell.render.sdf.SDFTexture;
import shell.render.shaders.ShaderProgram;
import shell.render.shaders.ShaderProgram.ShaderType;

public class Font {

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

    public Font() {
        try {

            String json = Platforms.get().loadSource("res", ATLAS_JSON_PATH);
            FontAtlasDTO root = Platforms.get().parseFontAtlas(json);
            FontAtlasData atlas = new FontAtlasData();
            atlas.width = root.atlas.width;
            atlas.height = root.atlas.height;
            atlas.sizePx = (float) root.atlas.size;
            float lineHeightEm = (float) root.metrics.lineHeight;
            atlas.derivedLineHeight = (atlas.sizePx > 0f ? atlas.sizePx * lineHeightEm : 32f * lineHeightEm);
            this.glyphs = buildGlyphs(root);
            this.pxPerEm = atlas.sizePx;
            this.ascenderPx = (float) (atlas.sizePx * root.metrics.ascender);
            this.descenderPx = (float) (atlas.sizePx * root.metrics.descender);
            this.kerningEm = buildKerning(root);
            this.fontHeight = atlas.derivedLineHeight;
            this.fontWidth = atlas.sizePx;
            Platforms.get().loadTexture("opensans.png", Platforms.gl().getID(), t -> {
                this.texture = t;
                this.shader = ShaderType.TextureSDF.getShader();
                this.sdfTexture = new SDFTexture(this.texture);
                this.sdfTexture.setSharpCorners(true);
            });
            this.maxTextWidth = 64;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public float getWidth(CharSequence text) {
        float maxWidthPx = 0f;
        float lineAdvanceEm = 0f;
        int prevCodePoint = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                float lineWidthPx = lineAdvanceEm * pxPerEm;
                if (lineWidthPx > maxWidthPx)
                    maxWidthPx = lineWidthPx;
                lineAdvanceEm = 0f;
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

    public float getWidthScaled(CharSequence text, float glyphHeight) {
        float scale = glyphHeight / fontHeight;
        return getWidth(text) * scale;
    }

    public int getHeight(CharSequence text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                lines++;
            }
        }
        return Math.round(lines * fontHeight);
    }

    public void drawTextNoSetup(CharSequence text, float x, float y, float glyphHeight,
            Color c, Camera camera) {
        if (sdfTexture == null) {
            return;
        }
        float scale = glyphHeight / fontHeight;
        float drawX = x;
        float baselineY = y + (ascenderPx * scale) * 0.25f;
        float penEm = 0f;
        int prevCodePoint = -1;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                baselineY -= fontHeight * scale;
                penEm = 0f;
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
                sdfTexture.drawRegionNoSetup(drawX + glyphLeftPx, baselineY + glyphBottomPx,
                        glyphWidthPx, glyphHeightPx,
                        g.x, g.y, g.width, g.height, c, camera);
            }
            penEm += g.advance;
            prevCodePoint = ch;
        }
    }

    public void drawText(CharSequence text, float x, float y, float glyphHeight,
            Color c, Camera camera) {
        if (sdfTexture == null) {
            return;
        }
        sdfTexture.setup(camera);
        drawTextNoSetup(text, x, y, glyphHeight, c, camera);
        sdfTexture.cleanup(camera);
    }

    public void drawText(CharSequence text, float x, float y, float height, Camera camera) {
        drawText(text, x, y, height, Color.WHITE, camera);
    }

    public void dispose() {
        texture.delete();
    }

    public void drawNCharactersBack(String text, int xLimit, int y, float height, int numCharsBack,
            Color c, Camera camera) {
        if (text.length() < numCharsBack + 1) {
            int diff = (numCharsBack + 1 - text.length());
            for (int i = 0; i < diff; i++) {
                text += " ";
            }
        }
        float prefixWidth = getWidthScaled(text.substring(0, numCharsBack), height);
        drawText(text, xLimit - prefixWidth, y, height, c, camera);
    }

    public void drawTextCentered(String text, float x, float y, float height, Color c, Camera camera) {
        if (text.length() > maxTextWidth) {

            text = text.substring(0, maxTextWidth - 1) + "~";

        }
        float scaleRatio = height / fontHeight;
        float textWidth = getWidth(text) * scaleRatio;
        float textHeight = getHeight(text) * scaleRatio;
        drawText(text, x - textWidth / 2, y - textHeight / 2, height, c, camera);
    }

    public void drawRow(String string, int row, float yScrollOffset, float height, float rowSpacing, Color c,
            Camera camera) {
        drawText(string, 0, camera.getHeight() - ((row + 1) * height) + yScrollOffset, height, c, camera);
    }

    public void drawHyperString(HyperString hyperString, float x, float y, float height, Camera2D camera) {
        hyperString.setLineOffsetCentered(camera, x, y, this, 0);
        sdfTexture.setup(camera);
        for (int lineNumber = 0; lineNumber < hyperString.lines; lineNumber++) {
            hyperString.draw();
            ArrayList<Word> words = hyperString.getLine(lineNumber);
            for (int i = 0; i < words.size(); i++) {
                Word word = words.get(i);
                if (word.subWords != null) {
                    for (Word subWord : word.subWords) {
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

    public void drawHyperStrings(ArrayList<HyperString> hyperStrings, ArrayList<Vector2f> xLoc, float height,
            Camera2D camera) {
        sdfTexture.setup(camera);
        for (int j = 0; j < hyperStrings.size(); j++) {
            Vector2f loc = xLoc.get(j);
            HyperString hyperString = hyperStrings.get(j);
            hyperString.setLineOffsetCentered(camera, loc.x, loc.y, this, 0);
            hyperString.draw();
            for (int lineNumber = 0; lineNumber < hyperString.lines; lineNumber++) {
                ArrayList<Word> words = hyperString.getLine(lineNumber);
                for (int i = 0; i < words.size(); i++) {
                    Word word = words.get(i);
                    if (word.subWords != null) {
                        for (Word subWord : word.subWords) {
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

    public void drawHyperStringRows(HyperString hyperString, int row, float scrollOffsetY, float height,
            Camera2D camera) {
        if (sdfTexture == null) {
            return;
        }

        Platforms.get().log("Font texture " + shader.platformId + " " + Platforms.gl().getID());
        sdfTexture.setup(camera);
        hyperString.setLineOffsetFromTopRow(camera, row, scrollOffsetY, height, this);
        hyperString.draw();
        for (int lineNumber = 0; lineNumber < hyperString.lines; lineNumber++) {
            ArrayList<Word> words = hyperString.getLine(lineNumber);
            for (int i = 0; i < words.size(); i++) {
                Word word = words.get(i);
                if (word.newLine) {
                    continue;
                }
                if (word.subWords != null) {
                    for (Word subWord : word.subWords) {
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

    private static class FontAtlasData {
        int width;
        int height;
        float sizePx;
        float derivedLineHeight;
    }

    private static Map<Character, Glyph> buildGlyphs(FontAtlasDTO root) {
        HashMap<Character, Glyph> map = new HashMap<>();
        if (root.glyphs == null)
            return map;
        for (FontAtlasDTO.GlyphEntry ge : root.glyphs) {
            if (ge.atlasBounds == null)
                continue;
            int x = (int) Math.floor(ge.atlasBounds.left + 0.0001);
            int y = (int) Math.floor(ge.atlasBounds.bottom + 0.0001);
            int width = (int) Math.round(ge.atlasBounds.right - ge.atlasBounds.left);
            int height = (int) Math.round(ge.atlasBounds.top - ge.atlasBounds.bottom);
            if (width <= 0 || height <= 0)
                continue;
            char ch = (char) ge.unicode;
            float pl = ge.planeBounds != null ? (float) ge.planeBounds.left : 0f;
            float pb = ge.planeBounds != null ? (float) ge.planeBounds.bottom : 0f;
            float pr = ge.planeBounds != null ? (float) ge.planeBounds.right : 0f;
            float pt = ge.planeBounds != null ? (float) ge.planeBounds.top : 0f;
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
            return 0f;
        Map<Integer, Float> m = kerningEm.get(prevCodePoint);
        if (m == null)
            return 0f;
        Float v = m.get(codePoint);
        return v != null ? v.floatValue() : 0f;
    }

}
