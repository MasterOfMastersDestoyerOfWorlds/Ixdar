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
     * The Private-Use-Area character this special glyph occupies.
     *
     * @return PUA char value
     */
    public char getChar() {
        return value;
    }

    /**
     * The Unicode code point for this special glyph (same numeric value as
     * {@link #getChar()}).
     *
     * @return PUA code point
     */
    public int getCodePoint() {
        return value;
    }
}
