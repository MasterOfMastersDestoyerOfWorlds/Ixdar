package ixdar.graphics.render.text;

public enum SpecialGlyphs {
    COLOR_TRACKER(16, 16, 0, 0, 0.47f, 0.00108f, -0.288f, 0.456f,
            0.592f);

    private static final int PUA_START = 0xE000;
    public Glyph glyph;
    private final char value;

    SpecialGlyphs(int width, int height, int x, int y, float advance,
            float planeLeft, float planeBottom, float planeRight, float planeTop) {
        this.value = (char) (PUA_START + this.ordinal());
        this.glyph = new Glyph(width, height, x, y, (float) advance, planeLeft, planeBottom, planeRight, planeTop);
    }

    /**
     * TODO: document {@code getChar}.
     *
     * @return TODO: describe
     */
    public char getChar() {
        return value;
    }

    /**
     * TODO: document {@code getCodePoint}.
     *
     * @return TODO: describe
     */
    public int getCodePoint() {
        return value;
    }
}
