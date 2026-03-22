package ixdar.graphics.render.text;

import ixdar.graphics.render.Texture;
import ixdar.graphics.render.sdf.SDFTexture;

public class HyperChar extends SDFTexture {
    Character c;
    Font font;

    public HyperChar(Font font, Character c) {
        super(font.texture);
        this.font = font;
        this.c = c;
    }

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