package ixdar.graphics.render.text;

import ixdar.graphics.render.Texture;
import ixdar.graphics.render.sdf.SDFTexture;

public class HyperChar extends SDFTexture {
    Character c;
    Font font;

    /**
     * TODO: document {@code HyperChar}.
     *
     * @param font TODO: describe
     * @param c TODO: describe
     */
    public HyperChar(Font font, Character c) {
        super(font.texture);
        this.font = font;
        this.c = c;
    }

    /**
     * TODO: document {@code getTexture}.
     *
     * @throws NullPointerException TODO: describe
     * @return TODO: describe
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