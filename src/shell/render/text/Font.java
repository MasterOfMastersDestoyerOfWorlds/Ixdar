
package shell.render.text;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.system.MemoryUtil;

import shell.cameras.Camera;
import shell.cameras.Camera2D;
import shell.platform.Platforms;
import shell.platform.gl.GL;
import shell.render.Texture;
import shell.render.color.Color;
import shell.render.shaders.ShaderProgram;
import shell.render.shaders.ShaderProgram.ShaderType;

public class Font {

    private static GL gl = Platforms.gl();
    public final Map<Character, Glyph> glyphs;
    public final Texture texture;

    public float fontHeight;
    public float fontWidth;
    public ShaderProgram shader;
    public int maxTextWidth;

    public Font() {
        this(new java.awt.Font(MONOSPACED, PLAIN, 16), true);
    }

    public Texture createFontTexture(java.awt.Font font, boolean antiAlias) {
        /* Loop through the characters to get charWidth and charHeight */
        int imageWidth = 0;
        int imageHeight = 0;

        /* Start at char #32, because ASCII 0 to 31 are just control codes */
        for (int i = 32; i < 256; i++) {
            if (i == 127) {
                /* ASCII 127 is the DEL control code, so we can skip it */
                continue;
            }
            char c = (char) i;
            BufferedImage ch = createCharImage(font, c, antiAlias);
            if (ch == null) {
                /* If char image is null that font does not contain the char */
                continue;
            }

            imageWidth += ch.getWidth();
            imageHeight = Math.max(imageHeight, ch.getHeight());
        }

        fontHeight = imageHeight;

        /* Image for the texture */
        BufferedImage image = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();

        int x = 0;

        /*
         * Create image for the standard chars, again we omit ASCII 0 to 31
         * because they are just control codes
         */
        for (int i = 32; i < 256; i++) {
            if (i == 127) {
                /* ASCII 127 is the DEL control code, so we can skip it */
                continue;
            }
            char c = (char) i;
            BufferedImage charImage = createCharImage(font, c, antiAlias);
            if (charImage == null) {
                /* If char image is null that font does not contain the char */
                continue;
            }

            int charWidth = charImage.getWidth();
            int charHeight = charImage.getHeight();

            /* Create glyph and draw char on image */
            Glyph ch = new Glyph(charWidth, charHeight, x, image.getHeight() - charHeight, 0f);
            g.drawImage(charImage, x, 0, null);
            x += ch.width;
            glyphs.put(c, ch);
        }

        /* Flip image Horizontal to get the origin to bottom left */
        AffineTransform transform = AffineTransform.getScaleInstance(1f, -1f);
        transform.translate(0, -image.getHeight());
        AffineTransformOp operation = new AffineTransformOp(transform,
                AffineTransformOp.TYPE_NEAREST_NEIGHBOR);
        image = operation.filter(image, null);

        /* Get charWidth and charHeight of image */
        int width = image.getWidth();
        int height = image.getHeight();

        /* Get pixel data of image */
        int[] pixels = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);

        /* Put pixel data into a ByteBuffer */
        ByteBuffer buffer = MemoryUtil.memAlloc(width * height * 4);
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                /* Pixel as RGBA: 0xAARRGGBB */
                int pixel = pixels[i * width + j];
                /* Red component 0xAARRGGBB >> 16 = 0x0000AARR */
                buffer.put((byte) ((pixel >> 16) & 0xFF));
                /* Green component 0xAARRGGBB >> 8 = 0x00AARRGG */
                buffer.put((byte) ((pixel >> 8) & 0xFF));
                /* Blue component 0xAARRGGBB >> 0 = 0xAARRGGBB */
                buffer.put((byte) (pixel & 0xFF));
                /* Alpha component 0xAARRGGBB >> 24 = 0x000000AA */
                buffer.put((byte) ((pixel >> 24) & 0xFF));
            }
        }
        /* Do not forget to flip the buffer! */
        buffer.flip();

        /* Create texture */
        Texture fontTexture = Texture.createTexture(font.getName(), width, height, buffer);
        MemoryUtil.memFree(buffer);
        return fontTexture;
    }

    /**
     * Creates a char image from specified AWT font and char.
     *
     * @param font      The AWT font
     * @param c         The char
     * @param antiAlias Wheter the char should be antialiased or not
     *
     * @return Char image
     */
    public BufferedImage createCharImage(java.awt.Font font, char c, boolean antiAlias) {
        /* Creating temporary image to extract character size */
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        if (antiAlias) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        }
        g.setFont(font);
        FontMetrics metrics = g.getFontMetrics();
        g.dispose();

        /* Get char charWidth and charHeight */
        int charWidth = metrics.charWidth(c);
        int charHeight = metrics.getHeight();

        /* Check if charWidth is 0 */
        if (charWidth == 0) {
            return null;
        }

        /* Create image for holding the char */
        image = new BufferedImage(charWidth, charHeight, BufferedImage.TYPE_INT_ARGB);
        g = image.createGraphics();
        if (antiAlias) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        }
        g.setFont(font);
        g.setPaint(java.awt.Color.WHITE);
        g.drawString(String.valueOf(c), 0, metrics.getAscent());
        g.dispose();
        return image;
    }

    public float getWidth(CharSequence text) {
        float width = 0;
        float lineWidth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                /*
                 * Line end, set width to maximum from line width and stored
                 * width
                 */
                width = Math.max(width, lineWidth);
                lineWidth = 0;
                continue;
            }
            if (c == '\r') {
                /* Carriage return, just skip it */
                continue;
            }
            Glyph g = glyphs.get(c);
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
                /*
                 * Line end, set width to maximum from line width and stored
                 * width
                 */
                width = Math.max(width, lineWidth);
                lineWidth = 0;
                continue;
            }
            if (c == '\r') {
                /* Carriage return, just skip it */
                continue;
            }
            Glyph g = glyphs.get(c);
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
                /* Line end, add line height to stored height */
                height += lineHeight;
                lineHeight = 0;
                continue;
            }
            if (c == '\r') {
                /* Carriage return, just skip it */
                continue;
            }
            Glyph g = glyphs.get(c);
            lineHeight = Math.max(lineHeight, g.height);
        }
        height += lineHeight;
        return height;
    }

    /**
     * Draw text at the specified position and color.
     *
     * @param canvas3d The renderer to use
     * @param text     Text to draw
     * @param x        X coordinate of the text position
     * @param y        Y coordinate of the text position
     * @param c        Color to use
     * @param camera
     */
    public void drawText(CharSequence text, float x, float y, float glyphHeight,
            Color c, Camera camera) {

        float textHeight = getHeight(text);

        float drawX = x;
        float drawY = y;
        if (textHeight > fontHeight) {
            drawY += textHeight - fontHeight;
        }

        texture.bind();

        shader.use();
        shader.setTexture("texImage", texture, gl.TEXTURE0(), 0);
        shader.begin();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                /* Line feed, set x and y to draw at the next line */
                drawY -= fontHeight;
                drawX = x;
                continue;
            }
            if (ch == '\r') {
                /* Carriage return, just skip it */
                continue;
            }
            Glyph g = glyphs.get(ch);
            float scaledWidth = glyphHeight * g.widthHeightRatio;
            shader.drawTextureRegion(texture, drawX, drawY,
                    drawX + (g.widthHeightRatio * glyphHeight), drawY + glyphHeight,
                    camera.getZIndex(), g.x, g.y, g.width, g.height, c);
            drawX += scaledWidth;
        }
        shader.end();
        camera.incZIndex();
    }

    /**
     * Draw text at the specified position.
     *
     * @param canvas3d The renderer to use
     * @param text     Text to draw
     * @param x        X coordinate of the text position
     * @param y        Y coordinate of the text position
     * @param camera
     */
    public void drawText(CharSequence text, float x, float y, float height, Camera camera) {
        drawText(text, x, y, height, Color.WHITE, camera);
    }

    /**
     * Disposes the font.
     */
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
 
}
