
package shell.render.text;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import shell.cameras.Camera;
import shell.cameras.Camera2D;
import shell.platform.Platforms;
import shell.render.Texture;
import shell.render.color.Color;
import shell.render.sdf.SDFTexture;
import shell.render.shaders.ShaderProgram;
import shell.render.shaders.ShaderProgram.ShaderType;

public class Font {

    private static final String ATLAS_JSON_PATH = "opensans.json"; // try classpath then res/

    public final Map<Character, Glyph> glyphs;
    public final Texture texture;

    public float fontHeight;
    public float fontWidth;
    public ShaderProgram shader;
    public int maxTextWidth;
    private SDFTexture sdfTexture;

    public Font() {
        // Load atlas JSON/PNG and build glyphs
        String json = loadResourceText(ATLAS_JSON_PATH);
        FontAtlasDTO root = Platforms.get().parseFontAtlas(json);
        FontAtlasData atlas = new FontAtlasData();
        atlas.width = root.atlas.width;
        atlas.height = root.atlas.height;
        atlas.sizePx = (float) root.atlas.size;
        float lineHeightEm = (float) root.metrics.lineHeight;
        atlas.derivedLineHeight = (atlas.sizePx > 0f ? atlas.sizePx * lineHeightEm : 32f * lineHeightEm);
        this.glyphs = buildGlyphs(root);
        this.fontHeight = atlas.derivedLineHeight;
        this.fontWidth = atlas.sizePx;
        this.texture = Texture.loadTexture("opensans.png");
        this.shader = ShaderType.TextureSDF.shader;
        this.sdfTexture = new SDFTexture(this.texture);
        this.sdfTexture.setSharpCorners(true);
        this.maxTextWidth = 64;
    }

    // --- Drawing API remains the same ---
    public float getWidth(CharSequence text) {
        float width = 0;
        float lineWidth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                width = Math.max(width, lineWidth);
                lineWidth = 0;
                continue;
            }
            if (c == '\r') {
                continue;
            }
            Glyph g = glyphs.get(c);
            if (g != null)
                lineWidth += g.width;
        }
        width = Math.max(width, lineWidth);
        return width;
    }

    public float getWidthScaled(CharSequence text, float glyphHeight) {
        float width = 0;
        float lineWidth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                width = Math.max(width, lineWidth);
                lineWidth = 0;
                continue;
            }
            if (c == '\r') {
                continue;
            }
            Glyph g = glyphs.get(c);
            if (g != null)
                lineWidth += glyphHeight * g.widthHeightRatio;
        }
        width = Math.max(width, lineWidth);
        return width;
    }

    public int getHeight(CharSequence text) {
        int height = 0;
        int lineHeight = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                height += lineHeight;
                lineHeight = 0;
                continue;
            }
            if (c == '\r') {
                continue;
            }
            Glyph g = glyphs.get(c);
            if (g != null)
                lineHeight = Math.max(lineHeight, g.height);
        }
        height += lineHeight;
        return height;
    }

    public void drawText(CharSequence text, float x, float y, float glyphHeight,
            Color c, Camera camera) {

        float textHeight = getHeight(text);

        float drawX = x;
        float drawY = y;
        if (textHeight > fontHeight) {
            drawY += textHeight - fontHeight;
        }

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                drawY -= fontHeight;
                drawX = x;
                continue;
            }
            if (ch == '\r') {
                continue;
            }
            Glyph g = glyphs.get(ch);
            if (g == null)
                continue;
            float w = (g.widthHeightRatio * glyphHeight);
            sdfTexture.drawRegion(drawX, drawY, w, glyphHeight, g.x, g.y, g.width, g.height, c, camera);
            drawX += glyphHeight * g.widthHeightRatio;
        }
        // sdfTexture.drawRegion handles zIndex increment per glyph
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
        drawText(text, xLimit - getWidth(text.substring(0, numCharsBack)), y, height, c, camera);
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

    public void drawHyperStringRow(ArrayList<Word> hyperString, float height, Camera2D camera) {
        float characterOffset = 0;
        float scaleRatio = height / fontHeight;
        for (int i = 0; i < hyperString.size(); i++) {
            Word word = hyperString.get(i);
            drawText(word.text, characterOffset + 0,
                    word.y, height, word.color,
                    camera);
            characterOffset += getWidth(word.text) * scaleRatio;
        }
    }

    public void drawHyperString(HyperString hyperString, float x, float y, float height, Camera2D camera) {
        hyperString.setLineOffsetCentered(camera, x, y, this, 0);
        for (int lineNumber = 0; lineNumber < hyperString.lines; lineNumber++) {
            ArrayList<Word> words = hyperString.getLine(lineNumber);
            for (int i = 0; i < words.size(); i++) {
                Word word = words.get(i);
                drawText(word.text, word.x,
                        word.y, height, word.color,
                        camera);
            }
        }
    }

    public void drawHyperStringRows(HyperString hyperString, int row, float scrollOffsetY, float height,
            Camera2D camera) {
        hyperString.setLineOffsetFromTopRow(camera, row, scrollOffsetY, height, this);
        for (int lineNumber = 0; lineNumber < hyperString.lines; lineNumber++) {
            ArrayList<Word> words = hyperString.getLine(lineNumber);
            for (int i = 0; i < words.size(); i++) {
                Word word = words.get(i);
                if (word.newLine) {
                    continue;
                }
                drawText(word.text, word.x,
                        word.y, height, word.color,
                        camera);
            }
        }
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
            map.put(ch, new Glyph(width, height, x, y, (float) ge.advance));
        }
        return map;
    }

    private static String loadResourceText(String resourcePath) {
        try (InputStream in = Font.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) {
                    bos.write(buf, 0, r);
                }
                return bos.toString(StandardCharsets.UTF_8.name());
            }
        } catch (IOException ignore) {
        }
        try (InputStream in = Font.class.getClassLoader().getResourceAsStream("res/" + resourcePath)) {
            if (in != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) {
                    bos.write(buf, 0, r);
                }
                return bos.toString(StandardCharsets.UTF_8.name());
            }
        } catch (IOException ignore) {
        }
        // Fallback: load from file path under working dir
        try (InputStream in = new java.io.FileInputStream("res/" + resourcePath)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                bos.write(buf, 0, r);
            }
            return bos.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            return "{}";
        }
    }
}
