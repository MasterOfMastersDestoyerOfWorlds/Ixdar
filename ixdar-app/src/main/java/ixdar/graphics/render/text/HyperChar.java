package ixdar.graphics.render.text;

import ixdar.graphics.render.Texture;
import ixdar.graphics.render.sdf.SDFTexture;

public class HyperChar extends SDFTexture {
    Character c;
    Font font;

    /**
     * Wrap a single character backed by the font's MSDF atlas (used so each
     * glyph has its own SDF allocation/draw state).
     *
     * @param font owning font (atlas + metrics provider)
     * @param c character this glyph represents
     */
    public HyperChar(Font font, Character c) {
        super(font.texture);
        this.font = font;
        this.c = c;
    }

    /**
     * Return the font atlas, refreshing it from the font if it has loaded
     * since this glyph was constructed.
     *
     * @throws NullPointerException if the font's texture is still {@code null}
     * @return the atlas texture used for SDF sampling
     */
    @Override
    public Texture getTexture() {
        if (texture == null) {
            this.texture = font.texture;
            if(font.texture == null){
                throw new NullPointerException();
            }
        }
        return texture;
    }
}