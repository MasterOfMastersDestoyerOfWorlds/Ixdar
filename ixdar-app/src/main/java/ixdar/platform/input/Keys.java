package ixdar.platform.input;

import java.util.HashMap;
import java.util.Map;

/**
 * The keys and mouse gestures a control is bound to: each one's GLFW code, its label in text, and
 * its glyph in the key glyph atlas, res/keyglyphs.png, built by {@code tools/build_key_glyphs.py}.
 */
public enum Keys {

    // https://github.com/Templarian/MaterialDesign, Apache License 2.0.
    ENTER(257, 0xE100, "enter"),
    LEFT_SHIFT(340, Codes.SHIFT_GLYPH, Codes.SHIFT),
    LEFT_CONTROL(341, Codes.CTRL_GLYPH, Codes.CTRL),
    ESCAPE(256, 0xE103, "esc"),
    BACKSPACE(259, 0xE104, "backspace"),
    DELETE(261, 0xE105, "del"),
    TAB(258, 0xE107, "tab"),
    LEFT_ALT(342, Codes.ALT_GLYPH, Codes.ALT),
    UP(265, 0xE109, "up"),
    DOWN(264, 0xE10A, "down"),
    LEFT(263, 0xE10B, "left"),
    RIGHT(262, 0xE10C, "right"),
    SPACE(32, 0xE110, "space"),
    RIGHT_SHIFT(344, Codes.SHIFT_GLYPH, Codes.SHIFT),
    RIGHT_CONTROL(345, Codes.CTRL_GLYPH, Codes.CTRL),
    RIGHT_ALT(346, Codes.ALT_GLYPH, Codes.ALT),
    LEFT_SUPER(343, Codes.NO_GLYPH, Codes.SUPER),
    RIGHT_SUPER(347, Codes.NO_GLYPH, Codes.SUPER),

    A('A'),
    B('B'),
    C('C'),
    D('D'),
    E('E'),
    F('F'),
    G('G'),
    H('H'),
    I('I'),
    J('J'),
    K('K'),
    L('L'),
    M('M'),
    N('N'),
    O('O'),
    P('P'),
    Q('Q'),
    R('R'),
    S('S'),
    T('T'),
    U('U'),
    V('V'),
    W('W'),
    X('X'),
    Y('Y'),
    Z('Z'),
    DIGIT_0('0'),
    DIGIT_1('1'),
    DIGIT_2('2'),
    DIGIT_3('3'),
    DIGIT_4('4'),
    DIGIT_5('5'),
    DIGIT_6('6'),
    DIGIT_7('7'),
    DIGIT_8('8'),
    DIGIT_9('9'),
    MINUS('-'),
    EQUAL('='),
    COMMA(','),
    PERIOD('.'),
    LEFT_BRACKET('['),
    RIGHT_BRACKET(']'),
    GRAVE(96, Codes.KEYCAP_GLYPHS_START + '~', "~"),

    CLICK(Codes.NO_KEY_CODE, 0xE10D, "click"),
    DRAG(Codes.NO_KEY_CODE, 0xE10E, "drag"),
    SCROLL(Codes.NO_KEY_CODE, 0xE10F, "scroll"),
    DOUBLE_CLICK(Codes.NO_KEY_CODE, Codes.NO_GLYPH, "double-click");

    public static final int ACTION_RELEASE = 0;

    public static final int ACTION_PRESS = 1;

    public static final int ACTION_REPEAT = 2;

    public static final int MOUSE_BUTTON_LEFT = 0;

    public static final int MOUSE_BUTTON_RIGHT = 1;

    public static final int NO_KEY_CODE = Codes.NO_KEY_CODE;

    private static final Map<Integer, Keys> BY_KEY_CODE = new HashMap<>();

    static {
        for (Keys key : values()) {
            if (key.keyCode != NO_KEY_CODE) {
                BY_KEY_CODE.putIfAbsent(key.keyCode, key);
            }
        }
    }

    /** GLFW key code, or {@link #NO_KEY_CODE} for a mouse gesture. */
    public final int keyCode;

    /** The key glyph atlas's private-use character for the key, or 0 when it has none. */
    public final char glyph;

    /** The key's name in text, such as {@code esc} or {@code A}. */
    public final String label;

    Keys(char printable) {
        this(printable, Codes.KEYCAP_GLYPHS_START + printable, String.valueOf(printable));
    }

    Keys(int keyCode, int glyph, String label) {
        this.keyCode = keyCode;
        this.glyph = (char) glyph;
        this.label = label;
    }

    /**
     * The key a GLFW key code names.
     *
     * @param keyCode the code
     * @return its key, or {@code null} for a code none here has
     */
    public static Keys of(int keyCode) {
        return BY_KEY_CODE.get(keyCode);
    }

    /** What the constants are built from: an enum's constants come before its own fields. */
    private static final class Codes {

        static final int NO_KEY_CODE = -1;

        static final int NO_GLYPH = 0;

        static final int KEYCAP_GLYPHS_START = 0xE200;

        static final int SHIFT_GLYPH = 0xE101;

        static final int CTRL_GLYPH = 0xE102;

        static final int ALT_GLYPH = 0xE108;

        static final String SHIFT = "shift";

        static final String CTRL = "ctrl";

        static final String ALT = "alt";

        static final String SUPER = "super";

        private Codes() {
        }
    }
}
