package shell.platform.input;

public final class Keys {
    private Keys() {
    }

    // Actions
    public static final int ACTION_RELEASE = 0;
    public static final int ACTION_PRESS = 1;
    public static final int ACTION_REPEAT = 2;

    // Mouse
    public static final int MOUSE_BUTTON_LEFT = 0;
    public static final int MOUSE_BUTTON_RIGHT = 1;

    // Control keys (match GLFW constants)
    public static final int ESCAPE = 256;
    public static final int ENTER = 257;
    public static final int TAB = 258;
    public static final int BACKSPACE = 259;
    public static final int LEFT_SHIFT = 340;
    public static final int RIGHT_SHIFT = 344;
    public static final int LEFT_CONTROL = 341;
    public static final int RIGHT_CONTROL = 345;
    public static final int LEFT_ALT = 342;
    public static final int RIGHT_ALT = 346;
    public static final int LEFT_SUPER = 343;
    public static final int RIGHT_SUPER = 347;
    public static final int UP = 265;
    public static final int DOWN = 264;
    public static final int LEFT = 263;
    public static final int RIGHT = 262;

    // Printable keys (ASCII-compatible)
    public static final int SPACE = 32;
    public static final int A = 65;
    public static final int B = 66;
    public static final int C = 67;
    public static final int D = 68;
    public static final int E = 69;
    public static final int F = 70;
    public static final int G = 71;
    public static final int H = 72;
    public static final int I = 73;
    public static final int J = 74;
    public static final int K = 75;
    public static final int L = 76;
    public static final int M = 77;
    public static final int N = 78;
    public static final int O = 79;
    public static final int P = 80;
    public static final int Q = 81;
    public static final int R = 82;
    public static final int S = 83;
    public static final int T = 84;
    public static final int U = 85;
    public static final int V = 86;
    public static final int W = 87;
    public static final int X = 88;
    public static final int Y = 89;
    public static final int Z = 90;
    public static final int MINUS = 45; // '-'
    public static final int EQUAL = 61; // '='
    public static final int LEFT_BRACKET = 91; // '['
    public static final int RIGHT_BRACKET = 93; // ']'
}
