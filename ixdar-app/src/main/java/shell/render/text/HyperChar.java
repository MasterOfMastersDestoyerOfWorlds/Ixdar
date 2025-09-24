package shell.render.text;
import shell.render.sdf.SDFTexture;

public class HyperChar extends SDFTexture{
    Character c;
    public HyperChar(Font font, Character c) {
        super(font.texture);
        this.c = c;
    }

}